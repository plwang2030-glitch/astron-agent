package com.iflytek.astron.console.hub.service.chat.springai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * Runs independent sub-agent calls of one model response at the same time. Spring AI's
 * {@code DefaultToolCallingManager} executes tool calls one after another; when a response asks for
 * several {@link ParallelSafe} tools, they are started together here first and their results are
 * handed to the sequential loop, which then only collects them.
 */
@Slf4j
public final class ParallelToolCalls {

    /** Tools that may run concurrently with other tools of the same response. */
    public interface ParallelSafe extends ToolCallback {
    }

    /** At most this many calls of one response run at once. */
    private static final int MAX_CONCURRENT = 5;
    private static final ThreadLocal<Map<String, String>> PREFETCHED = new ThreadLocal<>();

    private ParallelToolCalls() {}

    /**
     * Starts every {@link ParallelSafe} call of {@code response} concurrently and waits for all of
     * them. Must be paired with {@link #clear()} after the delegate manager has run.
     */
    static void prefetch(Prompt prompt, ChatResponse response) {
        List<ToolCall> calls = new ArrayList<>();
        Map<String, ToolCallback> callbacks = callbacks(prompt);
        if (response != null) {
            for (Generation generation : response.getResults()) {
                if (generation.getOutput() != null && generation.getOutput().hasToolCalls()) {
                    for (ToolCall call : generation.getOutput().getToolCalls()) {
                        if (callbacks.get(call.name()) instanceof ParallelSafe) {
                            calls.add(call);
                        }
                    }
                }
            }
        }
        if (calls.size() < 2) {
            return;
        }
        long start = System.currentTimeMillis();
        log.info("Running tool calls in parallel, count={}, tools={}", calls.size(),
                calls.stream().map(ToolCall::name).toList());
        Map<String, String> results = new HashMap<>();
        Semaphore permits = new Semaphore(MAX_CONCURRENT);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<String>> futures = new ArrayList<>();
            for (ToolCall call : calls) {
                ToolCallback callback = callbacks.get(call.name());
                futures.add(executor.submit(() -> {
                    permits.acquire();
                    try {
                        return callback.call(call.arguments());
                    } finally {
                        permits.release();
                    }
                }));
            }
            for (int i = 0; i < calls.size(); i++) {
                ToolCall call = calls.get(i);
                try {
                    results.put(key(call.name(), call.arguments()), futures.get(i).get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    results.put(key(call.name(), call.arguments()), "工具调用被中断：" + call.name());
                } catch (Exception e) {
                    log.warn("Parallel tool call failed, tool={}, errorType={}", call.name(),
                            e.getClass().getSimpleName());
                    results.put(key(call.name(), call.arguments()), "工具调用失败：" + call.name());
                }
            }
        }
        PREFETCHED.set(results);
        log.info("Parallel tool calls finished, count={}, costMs={}", calls.size(), System.currentTimeMillis() - start);
    }

    /**
     * Result computed by {@link #prefetch} for this call on the current thread, or {@code null} when
     * the call has not been run yet (then the tool runs normally). Each result is handed out once.
     */
    static String take(String toolName, String arguments) {
        Map<String, String> results = PREFETCHED.get();
        return results == null ? null : results.remove(key(toolName, arguments));
    }

    static void clear() {
        PREFETCHED.remove();
    }

    private static Map<String, ToolCallback> callbacks(Prompt prompt) {
        Map<String, ToolCallback> byName = new HashMap<>();
        if (prompt != null && prompt.getOptions() instanceof ToolCallingChatOptions options
                && options.getToolCallbacks() != null) {
            for (ToolCallback callback : options.getToolCallbacks()) {
                byName.put(callback.getToolDefinition().name(), callback);
            }
        }
        return byName;
    }

    private static String key(String toolName, String arguments) {
        return toolName + '\u0000' + (arguments == null ? "" : arguments);
    }
}
