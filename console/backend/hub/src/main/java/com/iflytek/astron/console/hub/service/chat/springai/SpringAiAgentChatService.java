package com.iflytek.astron.console.hub.service.chat.springai;

import com.iflytek.astron.console.commons.dto.llm.SparkChatRequest;
import com.iflytek.astron.console.commons.entity.chat.ChatReqRecords;
import com.iflytek.astron.console.commons.entity.chat.ChatTraceSource;
import com.iflytek.astron.console.commons.service.ChatRecordModelService;
import com.iflytek.astron.console.commons.service.data.ChatDataService;
import com.iflytek.astron.console.commons.util.SseEmitterUtil;
import com.alibaba.fastjson2.JSONObject;
import com.iflytek.astron.console.hub.dto.notification.SendNotificationRequest;
import com.iflytek.astron.console.hub.enums.NotificationType;
import com.iflytek.astron.console.hub.service.agentmemory.runtime.AgentMemoryRuntimeService;
import com.iflytek.astron.console.hub.service.notification.NotificationService;
import com.iflytek.astron.console.hub.service.chat.springai.ChatModelFactory.AgentModel;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs a standalone-agent chat turn on Spring AI. Tools (web_search / current_time / MCP) are
 * registered as {@link ToolCallback}s and executed internally by the framework (Spring AI's default
 * internal tool execution). The model is streamed asynchronously: the reactive pipeline is
 * subscribed (NOT blocked) so the controller can return the {@link SseEmitter} immediately and
 * chunks are forwarded incrementally; chat records are persisted on completion.
 */
@Slf4j
@Service
public class SpringAiAgentChatService {

    private static final String SAFE_CHAT_FAILURE = "Failed to process chat request";
    private static final Duration STOP_POLL_INTERVAL = Duration.ofMillis(300);
    /**
     * The turn ends a little before the SSE response times out, so the reply is saved and the client
     * gets a normal end instead of a dead connection.
     */
    static final Duration TURN_LIMIT = Duration.ofMillis(SseEmitterUtil.DEFAULT_SSE_TIMEOUT_MS).minusSeconds(15);
    /** Sub-agents must finish by then, leaving time for the main model to write the final answer. */
    static final Duration TOOL_BUDGET = TURN_LIMIT.minusSeconds(60);
    /** Background turns are not tied to the SSE response. */
    static final Duration BACKGROUND_TURN_LIMIT = Duration.ofMinutes(30);
    static final Duration BACKGROUND_TOOL_BUDGET = BACKGROUND_TURN_LIMIT.minusMinutes(2);
    /** Background turns running at once per user. */
    static final int MAX_BACKGROUND_PER_USER = 2;
    private static final Map<String, AtomicInteger> RUNNING_BACKGROUND = new ConcurrentHashMap<>();

    private final ChatModelFactory chatModelFactory;
    private final AgentToolCallbackResolver toolCallbackResolver;
    private final SubAgentToolFactory subAgentToolFactory;
    private final ExportFileToolFactory exportFileToolFactory;
    private final NotificationService notificationService;
    private final ChatRecordModelService chatRecordModelService;
    private final ChatDataService chatDataService;
    private final AgentMemoryRuntimeService agentMemoryRuntimeService;
    private final Executor agentMemoryExecutor;

    public SpringAiAgentChatService(
            ChatModelFactory chatModelFactory,
            AgentToolCallbackResolver toolCallbackResolver,
            SubAgentToolFactory subAgentToolFactory,
            ExportFileToolFactory exportFileToolFactory,
            NotificationService notificationService,
            ChatRecordModelService chatRecordModelService,
            ChatDataService chatDataService,
            AgentMemoryRuntimeService agentMemoryRuntimeService,
            @Qualifier("agentMemoryExecutor") Executor agentMemoryExecutor) {
        this.chatModelFactory = chatModelFactory;
        this.toolCallbackResolver = toolCallbackResolver;
        this.subAgentToolFactory = subAgentToolFactory;
        this.exportFileToolFactory = exportFileToolFactory;
        this.notificationService = notificationService;
        this.chatRecordModelService = chatRecordModelService;
        this.chatDataService = chatDataService;
        this.agentMemoryRuntimeService = agentMemoryRuntimeService;
        this.agentMemoryExecutor = agentMemoryExecutor;
    }

    public void chat(AgentChatTask task, SseEmitter emitter, String streamId) {
        AgentSseBridge bridge = new AgentSseBridge(emitter, streamId);
        ChatToolContext context = new ChatToolContext(task.getUserId());
        context.setBotId(task.getBotId());
        context.setProgressListener(bridge::emitToolProgress);
        context.setTeamListener(bridge::emitTeamEvent);
        // A 直接回复用户 expert streams its answer as this turn's reply.
        context.setAnswerListener(bridge::emitContent);
        boolean background = task.isBackground();
        if (background && !acquireBackgroundSlot(task.getUserId())) {
            log.warn("Background turn rejected: too many running, uid={}, streamId={}", task.getUserId(), streamId);
            SseEmitterUtil.completeWithError(emitter, "后台任务过多：最多同时运行 " + MAX_BACKGROUND_PER_USER + " 个，请稍后再试");
            return;
        }
        context.setLongRunning(background);
        context.setDeadlineMillis(System.currentTimeMillis()
                + (background ? BACKGROUND_TOOL_BUDGET : TOOL_BUDGET).toMillis());
        try {
            AgentModel agentModel = task.getLlmInfoVo() != null
                    ? chatModelFactory.forCustomModel(task.getLlmInfoVo())
                    : chatModelFactory.forSparkModel(task.getSparkModelName());

            List<ToolCallback> resolved = toolCallbackResolver.resolve(task.getOpenedTool(), task.getMcpServerUrls(),
                    task.getSkills(), task.getTools(), task.getWorkflows(), context, !task.isBuiltinToolsDisabled(),
                    task.getBuiltinTools());
            List<ToolCallback> tools = new ArrayList<>(resolved == null ? List.of() : resolved);
            if (task.getExtraToolCallbacks() != null) {
                tools.addAll(task.getExtraToolCallbacks());
            }
            List<ToolCallback> subAgentTools = subAgentToolFactory.build(task.getSubAgents(), context);
            tools.addAll(subAgentTools);
            if (!subAgentTools.isEmpty()) {
                // Team leads deliver finished files (docx / xlsx / pptx / md / csv).
                tools.add(exportFileToolFactory.create(context));
            }
            boolean deepThinking = !Boolean.FALSE.equals(task.getDeepThinking());
            Map<String, Object> extraBody = new HashMap<>();
            Map<String, Object> thinkingBody = ChatModelFactory.thinkingBody(agentModel, deepThinking);
            if (thinkingBody != null) {
                extraBody.putAll(thinkingBody);
            }
            if (task.getExtraBody() != null) {
                extraBody.putAll(task.getExtraBody());
            }
            log.info(
                    "Agent chat start, streamId={}, modelId={}, spark={}, deepThinking={}, ark={}, toolCount={}, extraToolCount={}, subAgentToolCount={}, extraBodyKeys={}, skillCount={}, openedToolBytes={}, mcpConfigBytes={}, savedToolConfigBytes={}, workflowConfigBytes={}",
                    streamId,
                    agentModel.modelId(),
                    task.getLlmInfoVo() == null,
                    deepThinking,
                    agentModel.ark(),
                    tools.size(),
                    task.getExtraToolCallbacks() == null ? 0 : task.getExtraToolCallbacks().size(),
                    subAgentTools.size(),
                    extraBody.keySet(),
                    task.getSkills() == null ? 0 : task.getSkills().size(),
                    utf8Length(task.getOpenedTool()),
                    utf8Length(task.getMcpServerUrls()),
                    utf8Length(task.getTools()),
                    utf8Length(task.getWorkflows()));

            OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
                    .model(agentModel.modelId())
                    .toolCallbacks(tools);
            if (!extraBody.isEmpty()) {
                optionsBuilder.extraBody(extraBody);
            }
            OpenAiChatOptions options = optionsBuilder.build();
            List<Message> promptMessages = toMessages(task.getMessages());
            appendToSystemPrompt(promptMessages,
                    toolCallbackResolver.builtinContextPrompt(task.getBuiltinTools(), task.getUserId()));
            Prompt prompt = new Prompt(promptMessages, options);

            // Subscribe asynchronously: do NOT block the request thread. The controller returns the
            // SseEmitter right after this call, so Spring MVC starts the streaming response and the
            // chunks below are flushed incrementally. Blocking (blockLast) buffers every SSE event
            // until the controller returns, which breaks streaming.
            AtomicInteger chunkCount = new AtomicInteger();
            if (background) {
                // The client gets a short notice now; the turn keeps running and is saved to the chat.
                bridge.handOffToBackground("已转为后台运行，完成后会在通知中心提醒你，结果会保存在本对话中。",
                        task.getChatId(),
                        task.getChatReqRecords() == null ? null : task.getChatReqRecords().getId());
                log.info("Agent chat moved to background, streamId={}, uid={}, chatId={}", streamId,
                        task.getUserId(), task.getChatId());
            }
            agentModel.chatModel()
                    .stream(prompt)
                    // Runs before the cancel reaches running tool calls, so they see the turn as ended.
                    .doOnCancel(context::cancel)
                    .takeUntilOther(Mono.firstWithSignal(stopSignal(streamId),
                            turnLimit(streamId, background ? BACKGROUND_TURN_LIMIT : TURN_LIMIT)))
                    // Ends running sub-agent calls too: they listen to the context's cancel signal.
                    .doFinally(signal -> {
                        context.cancel();
                        if (background) {
                            releaseBackgroundSlot(task.getUserId());
                        }
                    })
                    .subscribe(
                            resp -> {
                                int n = chunkCount.incrementAndGet();
                                if (n <= 5) {
                                    Generation g = resp.getResult();
                                    String t = g != null && g.getOutput() != null ? g.getOutput().getText() : null;
                                    log.info("agent stream chunk #{}, streamId={}, textLen={}", n, streamId,
                                            t == null ? -1 : t.length());
                                }
                                if (isDeliveredDirectReply(resp, context)) {
                                    // Spring AI returnDirect hands back the expert's answer, which the
                                    // user already received while it was generated.
                                    log.info("Direct reply completed, streamId={}, answerLen={}", streamId,
                                            bridge.getFinalResult().length());
                                    return;
                                }
                                emitChunk(resp, bridge);
                            },
                            error -> {
                                handleStreamError(error, emitter, streamId, task, bridge);
                                if (background) {
                                    notifyBackground(task, streamId, false, bridge.getFinalResult().toString());
                                }
                            },
                            () -> {
                                log.info("agent stream complete, streamId={}, totalChunks={}", streamId,
                                        chunkCount.get());
                                bridge.emitToolTrace(context.drainTrace());
                                persist(task, bridge);
                                notifyComplete(task, streamId, bridge.getFinalResult().toString());
                                Long requestId =
                                        task.getChatReqRecords() == null ? null : task.getChatReqRecords().getId();
                                bridge.complete(task.getChatId(), requestId);
                                scheduleMemoryWrite(task, bridge.getFinalResult().toString());
                                if (background) {
                                    notifyBackground(task, streamId, true, bridge.getFinalResult().toString());
                                }
                            });
        } catch (Exception e) {
            context.cancel();
            if (background) {
                releaseBackgroundSlot(task.getUserId());
            }
            log.error(
                    "Spring AI agent chat setup failed, streamId={}, errorType={}",
                    streamId,
                    e.getClass().getSimpleName());
            SseEmitterUtil.completeWithError(emitter, safeFailureMessage(streamId));
        }
    }

    /**
     * Emits once the user stops generation ({@code POST /chat-message/stop}). Polled on a timer rather
     * than checked per chunk: while a reasoning model is thinking, chunks can be seconds apart, so a
     * per-chunk check kept the upstream model call running long after the user clicked stop.
     */
    static Mono<Long> stopSignal(String streamId) {
        return Flux.interval(STOP_POLL_INTERVAL)
                .filter(tick -> SseEmitterUtil.isStreamStopped(streamId))
                .next()
                .doOnNext(tick -> log.info("Agent chat stopped by user, streamId={}", streamId));
    }

    /** Emits when the turn reaches {@code limit}. */
    static Mono<Long> turnLimit(String streamId, Duration limit) {
        return Mono.delay(limit)
                .doOnNext(tick -> log.warn("Agent chat reached the turn limit of {}s, streamId={}",
                        limit.toSeconds(), streamId));
    }

    private static boolean acquireBackgroundSlot(String uid) {
        AtomicInteger running = RUNNING_BACKGROUND.computeIfAbsent(StringUtils.defaultString(uid),
                k -> new AtomicInteger());
        if (running.incrementAndGet() > MAX_BACKGROUND_PER_USER) {
            running.decrementAndGet();
            return false;
        }
        return true;
    }

    private static void releaseBackgroundSlot(String uid) {
        AtomicInteger running = RUNNING_BACKGROUND.get(StringUtils.defaultString(uid));
        if (running != null && running.decrementAndGet() <= 0) {
            RUNNING_BACKGROUND.remove(StringUtils.defaultString(uid), running);
        }
    }

    /** Tells the user in the notification center that a background turn ended. */
    private void notifyBackground(AgentChatTask task, String streamId, boolean ok, String answer) {
        try {
            SendNotificationRequest request = new SendNotificationRequest();
            request.setType(NotificationType.PERSONAL);
            request.setTitle(ok ? "后台任务已完成" : "后台任务未完成");
            String summary = StringUtils.abbreviate(StringUtils.normalizeSpace(
                    StringUtils.defaultString(task.getRawUserText())), 60);
            request.setBody((ok ? "你的问题「" + summary + "」已处理完成，点击查看结果。"
                    : "你的问题「" + summary + "」处理时出错，已保存的部分结果可在对话中查看。")
                    + (StringUtils.isBlank(answer) ? "" : "\n" + StringUtils.abbreviate(StringUtils.normalizeSpace(answer), 120)));
            request.setPayload(new JSONObject()
                    .fluentPut("outlink", "/chat/" + task.getBotId())
                    .fluentPut("botId", task.getBotId())
                    .fluentPut("chatId", task.getChatId())
                    .toJSONString());
            request.setReceiverUids(List.of(task.getUserId()));
            notificationService.sendNotification(request);
            log.info("Background turn notified, streamId={}, uid={}, ok={}", streamId, task.getUserId(), ok);
        } catch (Exception e) {
            log.warn("Background turn notification failed, streamId={}, errorType={}, msg={}", streamId,
                    e.getClass().getSimpleName(), e.getMessage());
        }
    }

    private void notifyComplete(AgentChatTask task, String streamId, String finalAnswer) {
        if (task.getOnComplete() == null) {
            return;
        }
        try {
            task.getOnComplete().accept(finalAnswer);
        } catch (Exception e) {
            log.warn("Agent chat onComplete callback failed, streamId={}, errorType={}", streamId,
                    e.getClass().getSimpleName());
        }
    }

    private void scheduleMemoryWrite(AgentChatTask task, String assistantAnswer) {
        try {
            CompletableFuture.runAsync(
                    () -> agentMemoryRuntimeService.writeTurn(task, assistantAnswer),
                    agentMemoryExecutor)
                    .exceptionally(error -> {
                        log.warn(
                                "Agent memory async write failed, botId={}, uid={}, errorType={}",
                                task.getBotId(),
                                task.getUserId(),
                                error.getClass().getSimpleName());
                        return null;
                    });
        } catch (RejectedExecutionException e) {
            log.warn("Agent memory write skipped because executor is saturated, botId={}, uid={}",
                    task.getBotId(), task.getUserId());
        }
    }

    private void handleStreamError(Throwable e, SseEmitter emitter, String streamId, AgentChatTask task,
            AgentSseBridge bridge) {
        if (e instanceof WebClientResponseException w) {
            log.error(
                    "Spring AI agent chat failed, streamId={}, httpStatus={}, errorType={}",
                    streamId,
                    w.getStatusCode().value(),
                    e.getClass().getSimpleName());
        } else {
            log.error(
                    "Spring AI agent chat failed, streamId={}, httpStatus={}, errorType={}",
                    streamId,
                    null,
                    e.getClass().getSimpleName());
        }
        // Persist whatever was generated before the error so the partial reply is not lost.
        try {
            persist(task, bridge);
        } catch (Exception persistError) {
            log.error(
                    "Failed to persist partial response after stream error, streamId={}, errorType={}",
                    streamId,
                    persistError.getClass().getSimpleName());
        }
        if (bridge.isDetached()) {
            // Background turn: the client is gone; the user is notified instead.
            return;
        }
        SseEmitterUtil.completeWithError(emitter, safeFailureMessage(streamId));
    }

    private String safeFailureMessage(String streamId) {
        return SAFE_CHAT_FAILURE + " (streamId: " + StringUtils.defaultString(streamId, "unknown") + ")";
    }

    private int utf8Length(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * The response Spring AI builds from a returnDirect tool result, after the answer was already
     * streamed.
     */
    static boolean isDeliveredDirectReply(ChatResponse response, ChatToolContext context) {
        if (response == null || !context.isAnswerDelivered()) {
            return false;
        }
        Generation generation = response.getResult();
        return generation != null && generation.getMetadata() != null
                && ToolExecutionResult.FINISH_REASON.equals(generation.getMetadata().getFinishReason());
    }

    private void emitChunk(ChatResponse response, AgentSseBridge bridge) {
        if (response == null) {
            return;
        }
        Generation generation = response.getResult();
        if (generation == null) {
            return;
        }
        AssistantMessage output = generation.getOutput();
        if (output == null) {
            return;
        }
        String text = output.getText();
        if (StringUtils.isNotEmpty(text)) {
            bridge.emitContent(text);
        }
        Map<String, Object> metadata = output.getMetadata();
        if (metadata != null) {
            for (Map.Entry<String, Object> entry : metadata.entrySet()) {
                if (StringUtils.containsIgnoreCase(entry.getKey(), "reasoning")
                        && entry.getValue() instanceof String reasoning && StringUtils.isNotEmpty(reasoning)) {
                    bridge.emitReasoning(reasoning);
                }
            }
        }
    }

    /**
     * Adds per-turn context (e.g. the A-share trading memory) to the first system message, or as a new
     * leading system message when there is none.
     */
    static void appendToSystemPrompt(List<Message> messages, String extra) {
        if (StringUtils.isBlank(extra)) {
            return;
        }
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof SystemMessage system) {
                messages.set(i, new SystemMessage(StringUtils.defaultString(system.getText()) + extra));
                return;
            }
        }
        messages.add(0, new SystemMessage(extra.trim()));
    }

    private List<Message> toMessages(List<SparkChatRequest.MessageDto> dtos) {
        List<Message> messages = new ArrayList<>();
        if (dtos == null) {
            return messages;
        }
        for (SparkChatRequest.MessageDto dto : dtos) {
            String role = StringUtils.lowerCase(StringUtils.defaultString(dto.getRole()));
            String content = StringUtils.defaultString(dto.getContent());
            switch (role) {
                case "system" -> messages.add(new SystemMessage(content));
                case "assistant" -> messages.add(new AssistantMessage(content));
                default -> messages.add(new UserMessage(content));
            }
        }
        return messages;
    }

    private void persist(AgentChatTask task, AgentSseBridge bridge) {
        ChatReqRecords req = task.getChatReqRecords();
        if (req == null) {
            return;
        }
        StringBuffer sid = new StringBuffer();
        chatRecordModelService.saveChatResponse(req, bridge.getFinalResult(), sid, task.isEdit(), 2);
        chatRecordModelService.saveThinkingResult(req, bridge.getThinkingResult(), task.isEdit());
        saveTrace(req, bridge, task.isEdit());
    }

    private void saveTrace(ChatReqRecords req, AgentSseBridge bridge, boolean edit) {
        if (bridge.getTraceResult().length() == 0) {
            return;
        }
        String trace = bridge.getTraceResult().toString();
        String type = bridge.isManagedSearchTrace() ? "web_search" : "prompt";
        LocalDateTime now = LocalDateTime.now();
        if (edit) {
            ChatTraceSource existing = chatDataService.findTraceSourceByUidAndChatIdAndReqId(
                    req.getUid(), req.getChatId(), req.getId());
            if (existing != null) {
                existing.setContent(trace);
                existing.setType(type);
                existing.setUpdateTime(now);
                chatDataService.updateTraceSourceByUidAndChatIdAndReqId(existing);
            }
        } else {
            ChatTraceSource source = new ChatTraceSource();
            source.setUid(req.getUid());
            source.setChatId(req.getChatId());
            source.setReqId(req.getId());
            source.setContent(trace);
            source.setType(type);
            source.setCreateTime(now);
            source.setUpdateTime(now);
            chatDataService.createTraceSource(source);
        }
    }
}
