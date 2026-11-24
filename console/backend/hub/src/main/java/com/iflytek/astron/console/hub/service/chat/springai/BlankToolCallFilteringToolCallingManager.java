package com.iflytek.astron.console.hub.service.chat.springai;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * Wraps a {@link ToolCallingManager} to run independent sub-agent calls in parallel
 * ({@link ParallelToolCalls}) and to drop tool calls whose function name is blank before they are
 * executed. Some OpenAI-compatible providers (e.g. iFlytek Spark) stream a spurious empty-name
 * tool_call; without this, Spring AI's resolver throws "toolName cannot be null or empty" and
 * aborts the whole turn. Standard providers (e.g. deepseek) are unaffected since they never emit
 * blank names.
 */
@Slf4j
public class BlankToolCallFilteringToolCallingManager implements ToolCallingManager {

    private final ToolCallingManager delegate;

    public BlankToolCallFilteringToolCallingManager(ToolCallingManager delegate) {
        this.delegate = delegate;
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
        return delegate.resolveToolDefinitions(chatOptions);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        ChatResponse filtered = stripBlankToolCalls(chatResponse);
        if (log.isInfoEnabled() && filtered != null) {
            // Each tool round costs one more model call; this shows which tools a model asked for.
            log.info("Tool calls requested, tools={}", filtered.getResults()
                    .stream()
                    .filter(g -> g.getOutput() != null && g.getOutput().hasToolCalls())
                    .flatMap(g -> g.getOutput().getToolCalls().stream())
                    .map(ToolCall::name)
                    .toList());
        }
        // A response that asks only for one 直接回复用户 agent: its answer streams to the user and is the
        // reply, so the model is not called again just to repeat it.
        DirectReplyTool direct = singleDirectReplyTool(prompt, filtered);
        if (direct != null) {
            direct.startDirectReply();
        }
        boolean delivered = false;
        ToolExecutionResult result;
        try {
            // Independent sub-agent calls of this response run together; the delegate then only
            // collects their results in its usual sequential loop.
            ParallelToolCalls.prefetch(prompt, filtered);
            result = delegate.executeToolCalls(prompt, filtered);
        } finally {
            ParallelToolCalls.clear();
            if (direct != null) {
                delivered = direct.finishDirectReply();
            }
        }
        if (!delivered) {
            return result;
        }
        log.info("Direct reply delivered, tool={}; the turn ends without another model call",
                direct.getToolDefinition().name());
        return ToolExecutionResult.builder()
                .conversationHistory(result.conversationHistory())
                .returnDirect(true)
                .build();
    }

    /**
     * The only tool call of the response, when it targets a {@link DirectReplyTool} set to reply
     * directly.
     */
    private static DirectReplyTool singleDirectReplyTool(Prompt prompt, ChatResponse response) {
        if (response == null || !(prompt.getOptions() instanceof ToolCallingChatOptions options)
                || options.getToolCallbacks() == null) {
            return null;
        }
        List<ToolCall> calls = new ArrayList<>();
        for (Generation generation : response.getResults()) {
            if (generation.getOutput() != null && generation.getOutput().hasToolCalls()) {
                calls.addAll(generation.getOutput().getToolCalls());
            }
        }
        if (calls.size() != 1) {
            return null;
        }
        for (var callback : options.getToolCallbacks()) {
            if (callback instanceof DirectReplyTool tool && tool.directReply()
                    && callback.getToolDefinition().name().equals(calls.get(0).name())) {
                return tool;
            }
        }
        return null;
    }

    private ChatResponse stripBlankToolCalls(ChatResponse chatResponse) {
        if (chatResponse == null) {
            return null;
        }
        boolean changed = false;
        List<Generation> generations = new ArrayList<>();
        for (Generation generation : chatResponse.getResults()) {
            AssistantMessage message = generation.getOutput();
            if (message != null && message.hasToolCalls()) {
                List<ToolCall> valid = new ArrayList<>();
                for (ToolCall call : message.getToolCalls()) {
                    if (StringUtils.isNotBlank(call.name())) {
                        valid.add(call);
                    } else {
                        log.warn("Dropping tool call with blank name (provider sent a malformed tool_call)");
                    }
                }
                if (valid.size() != message.getToolCalls().size()) {
                    changed = true;
                    String text = message.getText();
                    AssistantMessage filtered = AssistantMessage.builder()
                            .content(text == null ? "" : text)
                            .properties(message.getMetadata())
                            .toolCalls(valid)
                            .media(message.getMedia())
                            .build();
                    generations.add(new Generation(filtered, generation.getMetadata()));
                    continue;
                }
            }
            generations.add(generation);
        }
        return changed ? new ChatResponse(generations, chatResponse.getMetadata()) : chatResponse;
    }
}
