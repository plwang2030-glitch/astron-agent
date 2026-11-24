package com.iflytek.astron.console.hub.service.chat.springai;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.iflytek.astron.console.hub.service.chat.springai.ChatModelFactory.AgentModel;
import com.iflytek.astron.console.toolkit.service.repo.RepoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Exposes each {@link PreparedSubAgent} (an expert) to its parent's model as a
 * {@code call_agent_<botId>} tool. A call runs one turn of the expert (its own prompt, model,
 * knowledge and tools) and returns the answer to the parent, which decides how to reply.
 *
 * <p>
 * Expert-team behaviour: calls of one model response run in parallel ({@link ParallelToolCalls}),
 * every call reports start / partial output / finish as team events, and experts share a per-turn
 * board ({@code team_board_read} / {@code team_board_post}) that also receives each final answer.
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubAgentToolFactory {

    private static final String INPUT_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\",\"description\":"
                    + "\"交给该智能体处理的完整问题，包含必要的上下文\"}},\"required\":[\"query\"]}";
    private static final int KNOWLEDGE_TOP_N = 3;
    /** Upper bound for one expert turn in an interactive chat. */
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(120);
    /** An expert that calls its own experts waits for them too. */
    private static final Duration ROUTING_CALL_TIMEOUT = Duration.ofSeconds(240);
    /** Background turns: experts may work much longer. */
    private static final Duration LONG_CALL_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration LONG_ROUTING_CALL_TIMEOUT = Duration.ofMinutes(15);
    /** Kept free for the caller to use the answer after the expert returns. */
    private static final long CALLER_RESERVE_MS = 20_000;
    /** Below this the expert is not started at all. */
    private static final long MIN_CALL_MS = 10_000;
    /** Partial output is sent to the client in pieces of at least this size or after this delay. */
    private static final int DELTA_MIN_CHARS = 120;
    private static final long DELTA_MAX_DELAY_MS = 500;
    private static final int BOARD_NOTE_MAX_CHARS = 1500;

    /** Added to every expert's system prompt when it is called by a team lead. */
    static final String EXPERT_PROTOCOL = """


            ## 专家团协作
            你正作为专家团的一员处理负责人交给你的任务，只做你职责范围内的部分。
            - 需要参考其他专家的结论时，调用 team_board_read 查看团队共享区；得出对其他专家有用的关键中间结论（数字、事实、约束）时，用 team_board_post 简短发布。
            - 如果你的判断与共享区里其他专家的结论冲突，在回答中用「【分歧】」标出，写明你的观点和依据，不要附和。
            - 拿不准的事实不要编造，写明「待确认」。
            - 回答直接给出结果，不要寒暄。
            """;

    /**
     * Used instead of {@link #EXPERT_PROTOCOL} when the expert replies to the user directly: it is the
     * only expert of the step, so there is nobody to share a board with, and its own style applies.
     */
    static final String DIRECT_PROTOCOL = """


            ## 回复方式
            你的回答会直接作为最终回复发给用户，负责人不会再转述。按你自己的设定直接回复用户，就像用户在直接和你对话。
            """;

    private final ChatModelFactory chatModelFactory;
    private final AgentToolCallbackResolver toolCallbackResolver;
    private final RepoService repoService;

    /** Must run on the request thread: models and the experts' own tools are resolved here. */
    public List<ToolCallback> build(List<PreparedSubAgent> agents, ChatToolContext context) {
        return build(agents, context, null);
    }

    private List<ToolCallback> build(List<PreparedSubAgent> agents, ChatToolContext context, Integer parentBotId) {
        List<ToolCallback> callbacks = new ArrayList<>();
        if (agents == null) {
            return callbacks;
        }
        for (PreparedSubAgent agent : agents) {
            try {
                AgentModel model = agent.llmInfoVo() != null
                        ? chatModelFactory.forCustomModel(agent.llmInfoVo())
                        : chatModelFactory.forSparkModel(agent.sparkModelName());
                List<ToolCallback> subTools = new ArrayList<>();
                try {
                    List<ToolCallback> resolved = toolCallbackResolver.resolve(agent.openedTool(),
                            agent.mcpServerUrls(), agent.skills(), agent.tools(), agent.workflows(), context, true,
                            agent.builtinTools());
                    if (resolved != null) {
                        subTools.addAll(resolved);
                    }
                } catch (Exception e) {
                    log.warn("Sub-agent tools skipped, botId={}, errorType={}, msg={}", agent.botId(),
                            e.getClass().getSimpleName(), e.getMessage());
                }
                subTools.add(new BoardReadTool(context));
                subTools.add(new BoardPostTool(agent.name(), context));
                // Next reference level (SubAgentPreparer stops at MAX_DEPTH).
                subTools.addAll(build(agent.subAgents(), context, agent.botId()));
                callbacks.add(new SubAgentToolCallback(agent, model, subTools, context, parentBotId));
                log.info(
                        "Sub-agent tool registered, tool={}, botId={}, parentBotId={}, model={}, deepThinking={}, ark={}, subToolCount={}, subAgentCount={}, datasetCount={}",
                        agent.toolName(), agent.botId(), parentBotId, model.modelId(), agent.deepThinking(),
                        model.ark(), subTools.size(),
                        agent.subAgents() == null ? 0 : agent.subAgents().size(),
                        agent.datasetIds() == null ? 0 : agent.datasetIds().size());
            } catch (Exception e) {
                log.warn("Sub-agent tool skipped, botId={}, errorType={}, msg={}", agent.botId(),
                        e.getClass().getSimpleName(), e.getMessage());
            }
        }
        return callbacks;
    }

    private class SubAgentToolCallback implements ParallelToolCalls.ParallelSafe, DirectReplyTool {

        /** Set by the tool calling manager while this agent is the only one called and replies directly. */
        private volatile boolean directMode;
        private volatile boolean delivered;
        private final PreparedSubAgent agent;
        private final AgentModel model;
        private final List<ToolCallback> subTools;
        private final ChatToolContext context;
        private final Integer parentBotId;

        SubAgentToolCallback(PreparedSubAgent agent, AgentModel model, List<ToolCallback> subTools,
                ChatToolContext context, Integer parentBotId) {
            this.agent = agent;
            this.model = model;
            this.subTools = subTools;
            this.context = context;
            this.parentBotId = parentBotId;
        }

        @Override
        public boolean directReply() {
            return agent.direct();
        }

        @Override
        public void startDirectReply() {
            delivered = false;
            directMode = true;
        }

        @Override
        public boolean finishDirectReply() {
            boolean result = delivered;
            directMode = false;
            delivered = false;
            return result;
        }

        /** In direct mode the answer text also goes to the user as the reply, as it is generated. */
        private void deliver(Call call, String text) {
            if (!directMode || StringUtils.isEmpty(text)) {
                return;
            }
            if (!delivered) {
                log.info("Direct reply streaming, tool={}, botId={}, callId={}, firstTokenMs={}", agent.toolName(),
                        agent.botId(), call.id, System.currentTimeMillis() - call.start);
            }
            delivered = true;
            context.emitAnswer(text);
        }

        /** Ends a direct reply that broke off after part of it reached the user. */
        private void deliverNotice(String notice) {
            if (directMode && delivered) {
                context.emitAnswer("\n\n" + notice);
            }
        }

        @Override
        public ToolDefinition getToolDefinition() {
            String desc = StringUtils.isBlank(agent.description())
                    ? ""
                    : "：" + StringUtils.abbreviate(StringUtils.normalizeSpace(agent.description()), 300);
            return ToolDefinition.builder()
                    .name(agent.toolName())
                    .description("把问题交给智能体「" + agent.name() + "」处理" + desc + "。")
                    .inputSchema(INPUT_SCHEMA)
                    .build();
        }

        @Override
        public String call(String toolInput) {
            // Already run in parallel with the other calls of this response.
            String prefetched = ParallelToolCalls.take(agent.toolName(), toolInput);
            if (prefetched != null) {
                return prefetched;
            }
            Call call = new Call(UUID.randomUUID().toString().substring(0, 8), parseQuery(toolInput),
                    System.currentTimeMillis(), new AtomicLong());
            log.info("Sub-agent invoked, tool={}, botId={}, parentBotId={}, callId={}, queryLen={}",
                    agent.toolName(), agent.botId(), parentBotId, call.id, call.query.length());
            if (StringUtils.isBlank(call.query)) {
                return "调用智能体「" + agent.name() + "」失败：缺少 query 参数。";
            }
            if (context.isCancelled()) {
                log.info("Sub-agent not started: turn already ended, tool={}", agent.toolName());
                return "对话已结束，未调用智能体「" + agent.name() + "」。";
            }
            // Shares the turn's time budget with every other (nested) expert call.
            boolean routes = agent.subAgents() != null && !agent.subAgents().isEmpty();
            Duration cap = context.isLongRunning()
                    ? (routes ? LONG_ROUTING_CALL_TIMEOUT : LONG_CALL_TIMEOUT)
                    : (routes ? ROUTING_CALL_TIMEOUT : CALL_TIMEOUT);
            long timeoutMs = Math.min(cap.toMillis(), context.remainingMillis() - CALLER_RESERVE_MS);
            if (timeoutMs < MIN_CALL_MS) {
                log.warn("Sub-agent not started: time budget used up, tool={}, remainingMs={}", agent.toolName(),
                        context.remainingMillis());
                return finish(call, "skipped",
                        "剩余时间不足，未调用智能体「" + agent.name() + "」，请根据已有信息直接答复用户。");
            }
            // Old clients show this as 使用工具；the team panel uses the team events below.
            context.emitProgress(new JSONObject()
                    .fluentPut("type", "agent")
                    .fluentPut("status", "running")
                    .fluentPut("deskToolName", "智能体：" + agent.name())
                    .fluentPut("toolName", agent.toolName())
                    .fluentPut("botId", agent.botId()));
            context.emitTeam(event(call, "running").fluentPut("query", StringUtils.abbreviate(call.query, 300)));
            StringBuilder answer = new StringBuilder();
            DeltaBuffer deltas = new DeltaBuffer(call);
            try {
                // A direct reply is the step's only expert: no board round trip, and it speaks to the user.
                boolean direct = directMode;
                List<ToolCallback> tools = direct
                        ? subTools.stream()
                                .filter(t -> !(t instanceof BoardReadTool || t instanceof BoardPostTool))
                                .toList()
                        : subTools;
                List<Message> messages = new ArrayList<>();
                messages.add(new SystemMessage(StringUtils.defaultString(agent.systemPrompt())
                        + StringUtils.defaultString(
                                toolCallbackResolver.builtinContextPrompt(agent.builtinTools(), context.getUserId()))
                        + (direct ? DIRECT_PROTOCOL : EXPERT_PROTOCOL)));
                messages.add(new UserMessage(withBoard(withKnowledge(call.query))));
                OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
                        .model(model.modelId())
                        .toolCallbacks(tools);
                Map<String, Object> thinkingBody = ChatModelFactory.thinkingBody(model, agent.deepThinking());
                if (thinkingBody != null) {
                    optionsBuilder.extraBody(thinkingBody);
                }
                OpenAiChatOptions options = optionsBuilder.build();
                // Streamed like the main chat: the blocking call() path hits the HTTP client's read
                // timeout on slow reasoning models and then Spring AI's retry backoff stalls the turn.
                // takeUntilOther stops the model request when the turn ends (stop, error, turn limit).
                model.chatModel()
                        .stream(new Prompt(messages, options))
                        .takeUntilOther(context.cancelSignal())
                        .doOnNext(response -> {
                            String piece = appendText(response, answer);
                            if (StringUtils.isNotEmpty(piece)) {
                                call.firstTokenAt.compareAndSet(0, System.currentTimeMillis());
                            }
                            deltas.add(piece);
                            deliver(call, piece);
                        })
                        .blockLast(Duration.ofMillis(timeoutMs));
                deltas.flush();
            } catch (Exception e) {
                deltas.flush();
                boolean interrupted = hasCause(e, InterruptedException.class);
                if (context.isCancelled() || interrupted) {
                    // Ending the turn interrupts this blocked thread; keep the interrupt flag for the pool.
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    log.info("Sub-agent call cancelled with the turn, tool={}, botId={}", agent.toolName(),
                            agent.botId());
                    return finish(call, "cancelled", answer.length() > 0
                            ? answer.toString()
                            : "对话已结束，智能体「" + agent.name() + "」未完成。");
                }
                boolean timedOut = e instanceof IllegalStateException
                        && StringUtils.containsIgnoreCase(e.getMessage(), "timeout");
                log.warn("Sub-agent call failed, tool={}, botId={}, timedOut={}, errorType={}, msg={}",
                        agent.toolName(), agent.botId(), timedOut, e.getClass().getSimpleName(), e.getMessage());
                if (timedOut) {
                    String note = "（智能体「" + agent.name() + "」超过 " + timeoutMs / 1000 + " 秒未完成";
                    deliverNotice(note + "，以上为部分内容）");
                    return finish(call, "timeout", answer.length() > 0
                            ? answer + "\n\n" + note + "，以上为部分内容）"
                            : note + "，请根据已有信息答复用户）");
                }
                deliverNotice("（回答中断，请重试）");
                return finish(call, "failed",
                        "调用智能体「" + agent.name() + "」失败：" + e.getClass().getSimpleName());
            }
            if (context.isCancelled()) {
                return finish(call, "cancelled", answer.length() > 0
                        ? answer.toString()
                        : "对话已结束，智能体「" + agent.name() + "」未完成。");
            }
            String text = answer.toString();
            if (StringUtils.isNotBlank(text)) {
                context.postNote(agent.name(), StringUtils.abbreviate(text, BOARD_NOTE_MAX_CHARS), true);
            }
            return finish(call, StringUtils.isNotBlank(text) ? "ok" : "empty",
                    StringUtils.defaultIfBlank(text, "智能体「" + agent.name() + "」没有返回内容。"));
        }

        private JSONObject event(Call call, String status) {
            return new JSONObject()
                    .fluentPut("callId", call.id)
                    .fluentPut("botId", agent.botId())
                    .fluentPut("parentBotId", parentBotId)
                    .fluentPut("name", agent.name())
                    .fluentPut("status", status);
        }

        private String finish(Call call, String status, String content) {
            long costMs = System.currentTimeMillis() - call.start;
            long firstToken = call.firstTokenAt.get();
            log.info("Sub-agent finished, tool={}, botId={}, status={}, answerLen={}, costMs={}, firstTokenMs={}, direct={}",
                    agent.toolName(), agent.botId(), status, content.length(), costMs,
                    firstToken == 0 ? -1 : firstToken - call.start, directMode && delivered);
            String shown = StringUtils.abbreviate(content, 4000);
            context.emitTeam(event(call, status).fluentPut("content", shown).fluentPut("costMs", costMs));
            context.addTrace(new JSONObject()
                    .fluentPut("type", "agent")
                    .fluentPut("status", status)
                    .fluentPut("callId", call.id)
                    .fluentPut("parentBotId", parentBotId)
                    .fluentPut("deskToolName", "智能体：" + agent.name())
                    .fluentPut("name", agent.name())
                    .fluentPut("toolName", agent.toolName())
                    .fluentPut("botId", agent.botId())
                    .fluentPut("costMs", costMs)
                    .fluentPut("arguments", new JSONObject().fluentPut("query", call.query))
                    .fluentPut("content", shown));
            return content;
        }

        /** Collects streamed text and sends it as team {@code delta} events in small pieces. */
        private final class DeltaBuffer {
            private final Call call;
            private final StringBuilder pending = new StringBuilder();
            private long lastSent = System.currentTimeMillis();

            DeltaBuffer(Call call) {
                this.call = call;
            }

            void add(String text) {
                if (StringUtils.isEmpty(text)) {
                    return;
                }
                pending.append(text);
                if (pending.length() >= DELTA_MIN_CHARS
                        || System.currentTimeMillis() - lastSent >= DELTA_MAX_DELAY_MS) {
                    flush();
                }
            }

            void flush() {
                if (pending.length() == 0) {
                    return;
                }
                context.emitTeam(event(call, "running").fluentPut("delta", pending.toString()));
                pending.setLength(0);
                lastSent = System.currentTimeMillis();
            }
        }

        private String appendText(ChatResponse response, StringBuilder answer) {
            if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                return null;
            }
            String text = response.getResult().getOutput().getText();
            if (text != null) {
                answer.append(text);
            }
            return text;
        }

        /** Puts what other experts already concluded in this turn in front of the task. */
        private String withBoard(String input) {
            List<ChatToolContext.BoardNote> notes = context.boardNotes();
            if (notes.isEmpty()) {
                return input;
            }
            StringBuilder sb = new StringBuilder("## 团队共享区（其他专家本轮已有的结论，供参考）\n");
            for (ChatToolContext.BoardNote note : notes) {
                if (!StringUtils.equals(note.author(), agent.name())) {
                    sb.append("- 【")
                            .append(note.author())
                            .append(note.result() ? "·结论" : "·要点")
                            .append("】")
                            .append(StringUtils.abbreviate(StringUtils.normalizeSpace(note.text()), 600))
                            .append('\n');
                }
            }
            return sb.append("\n## 你的任务\n").append(input).toString();
        }

        private String withKnowledge(String query) {
            if (agent.datasetIds() == null || agent.datasetIds().isEmpty()) {
                return query;
            }
            List<String> chunks = new ArrayList<>();
            for (Long repoId : agent.datasetIds()) {
                try {
                    chunks.addAll(repoService.retrieveChunkContentsForRuntime(repoId, query, KNOWLEDGE_TOP_N));
                } catch (Exception e) {
                    log.warn("Sub-agent knowledge retrieval failed, botId={}, repoId={}, errorType={}",
                            agent.botId(), repoId, e.getClass().getSimpleName());
                }
            }
            log.info("Sub-agent knowledge retrieved, botId={}, repoCount={}, chunkCount={}", agent.botId(),
                    agent.datasetIds().size(), chunks.size());
            if (chunks.isEmpty()) {
                return query;
            }
            StringBuilder sb = new StringBuilder("参考资料：\n");
            for (int i = 0; i < chunks.size(); i++) {
                sb.append('[').append(i + 1).append("] ").append(chunks.get(i)).append('\n');
            }
            return sb.append("\n问题：").append(query).toString();
        }

        private String parseQuery(String toolInput) {
            if (StringUtils.isBlank(toolInput)) {
                return "";
            }
            try {
                JSONObject args = JSON.parseObject(toolInput);
                return StringUtils.trimToEmpty(args == null ? null : args.getString("query"));
            } catch (Exception e) {
                // Some models send the raw question instead of JSON
                return toolInput.trim();
            }
        }
    }

    /** @param firstTokenAt epoch millis of the expert's first answer text, 0 until then */
    private record Call(String id, String query, long start, AtomicLong firstTokenAt) {}

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }

    /** {@code team_board_read}: lets an expert read what other experts posted in this turn. */
    private static final class BoardReadTool implements ToolCallback {
        private final ChatToolContext context;

        BoardReadTool(ChatToolContext context) {
            this.context = context;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name("team_board_read")
                    .description("查看专家团本轮的共享区：其他专家已发布的要点和已完成的结论。")
                    .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                    .build();
        }

        @Override
        public String call(String toolInput) {
            List<ChatToolContext.BoardNote> notes = context.boardNotes();
            if (notes.isEmpty()) {
                return "共享区暂无内容。";
            }
            StringBuilder sb = new StringBuilder();
            for (ChatToolContext.BoardNote note : notes) {
                sb.append("【")
                        .append(note.author())
                        .append(note.result() ? "·结论" : "·要点")
                        .append("】")
                        .append(note.text())
                        .append("\n\n");
            }
            return sb.toString();
        }
    }

    /** {@code team_board_post}: lets an expert share a key intermediate finding with the team. */
    private static final class BoardPostTool implements ToolCallback {
        private final String author;
        private final ChatToolContext context;

        BoardPostTool(String author, ChatToolContext context) {
            this.author = author;
            this.context = context;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name("team_board_post")
                    .description("把对其他专家有用的关键中间结论（数字、事实、约束）发布到专家团共享区，200 字以内。")
                    .inputSchema("{\"type\":\"object\",\"properties\":{\"note\":{\"type\":\"string\"}},"
                            + "\"required\":[\"note\"]}")
                    .build();
        }

        @Override
        public String call(String toolInput) {
            String note;
            try {
                JSONObject args = JSON.parseObject(toolInput);
                note = args == null ? null : args.getString("note");
            } catch (Exception e) {
                note = toolInput;
            }
            if (StringUtils.isBlank(note)) {
                return "未发布：note 为空。";
            }
            context.postNote(author, StringUtils.abbreviate(note.trim(), 400), false);
            log.info("Team board note posted, author={}, len={}", author, note.length());
            return "已发布到共享区。";
        }
    }
}
