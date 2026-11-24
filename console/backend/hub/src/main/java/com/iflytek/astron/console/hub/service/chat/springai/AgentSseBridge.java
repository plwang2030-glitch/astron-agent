package com.iflytek.astron.console.hub.service.chat.springai;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;

/**
 * Bridges Spring AI streaming output to the legacy standalone-bot SSE contract (named events
 * {@code data}/{@code complete}/{@code end}) and accumulates final answer / reasoning / trace for
 * persistence.
 */
@Slf4j
public class AgentSseBridge {

    private final SseEmitter emitter;
    private final String streamId;
    private final StringBuffer finalResult = new StringBuffer();
    private final StringBuffer thinkingResult = new StringBuffer();
    private final StringBuffer traceResult = new StringBuffer();
    private boolean managedSearchTrace = false;
    private volatile boolean detached;

    public AgentSseBridge(SseEmitter emitter, String streamId) {
        this.emitter = emitter;
        this.streamId = streamId;
    }

    public void emitContent(String delta) {
        if (StringUtils.isEmpty(delta)) {
            return;
        }
        finalResult.append(delta);
        JSONArray choices = new JSONArray();
        choices.add(new JSONObject().fluentPut("delta", new JSONObject().fluentPut("content", delta)));
        sendData(new JSONObject().fluentPut("choices", choices));
    }

    public void emitReasoning(String delta) {
        if (StringUtils.isEmpty(delta)) {
            return;
        }
        thinkingResult.append(delta);
        JSONArray choices = new JSONArray();
        choices.add(new JSONObject().fluentPut("delta", new JSONObject().fluentPut("reasoning_content", delta)));
        sendData(new JSONObject().fluentPut("choices", choices));
    }

    public void emitToolTrace(List<JSONObject> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        JSONArray toolCalls = new JSONArray();
        toolCalls.addAll(entries);
        for (JSONObject entry : entries) {
            if ("web_search".equals(entry.getString("type")) || entry.containsKey("web_search")) {
                managedSearchTrace = true;
            }
        }
        if (traceResult.length() > 0) {
            traceResult.append(",");
        }
        traceResult.append(toolCalls.toJSONString());

        JSONArray choices = new JSONArray();
        choices.add(new JSONObject().fluentPut("delta", new JSONObject()));
        choices.add(new JSONObject().fluentPut("delta", new JSONObject().fluentPut("tool_calls", toolCalls)));
        sendData(new JSONObject().fluentPut("choices", choices));
    }

    /**
     * Sends a tool that has started running, in the same {@code tool_calls} shape as
     * {@link #emitToolTrace}, so clients show it right away. Not added to the saved trace; the final
     * trace emitted at the end of the turn replaces it.
     */
    public void emitToolProgress(JSONObject entry) {
        if (entry == null) {
            return;
        }
        JSONArray toolCalls = new JSONArray();
        toolCalls.add(entry);
        JSONArray choices = new JSONArray();
        choices.add(new JSONObject().fluentPut("delta", new JSONObject()));
        choices.add(new JSONObject().fluentPut("delta", new JSONObject().fluentPut("tool_calls", toolCalls)));
        sendData(new JSONObject().fluentPut("choices", choices));
    }

    /**
     * Sends one expert-team progress event: {@code {"type":"team","team":{...}}}. Not persisted here.
     */
    public void emitTeamEvent(JSONObject event) {
        if (event != null) {
            sendData(new JSONObject().fluentPut("type", "team").fluentPut("team", event));
        }
    }

    /**
     * Background turn: tells the client the turn continues in the background, ends the SSE response,
     * and from then on only accumulates output for persistence.
     */
    public void handOffToBackground(String notice, Long chatId, Long requestId) {
        try {
            sendData(new JSONObject().fluentPut("type", "background").fluentPut("message", notice));
            // chatId / reqId let the client watch for the saved answer of this request.
            emitter.send(SseEmitter.event()
                    .name("end")
                    .data(new JSONObject()
                            .fluentPut("end", true)
                            .fluentPut("background", true)
                            .fluentPut("message", notice)
                            .fluentPut("chatId", chatId)
                            .fluentPut("reqId", requestId)
                            .fluentPut("timestamp", System.currentTimeMillis())
                            .toJSONString()));
        } catch (IOException e) {
            log.warn("SSE background notice failed, streamId: {}, error: {}", streamId, e.getMessage());
        } finally {
            detached = true;
            try {
                emitter.complete();
            } catch (Exception e) {
                log.warn("SSE emitter complete failed, streamId: {}, error: {}", streamId, e.getMessage());
            }
        }
    }

    public boolean isDetached() {
        return detached;
    }

    public void complete(Long chatId, Long requestId) {
        if (detached) {
            return;
        }
        try {
            JSONObject completeData = new JSONObject()
                    .fluentPut("finalResult", finalResult.toString())
                    .fluentPut("message", finalResult.toString())
                    .fluentPut("thinkingResult", thinkingResult.toString())
                    .fluentPut("traceResult", traceResult.toString())
                    .fluentPut("timestamp", System.currentTimeMillis())
                    .fluentPut("chatId", chatId)
                    .fluentPut("requestId", requestId)
                    .fluentPut("reqId", requestId)
                    .fluentPut("id", requestId)
                    .fluentPut("interrupted", false);
            emitter.send(SseEmitter.event().name("complete").data(completeData.toJSONString()));

            JSONObject endData = new JSONObject()
                    .fluentPut("end", true)
                    .fluentPut("timestamp", System.currentTimeMillis())
                    .fluentPut("message", finalResult.toString());
            emitter.send(SseEmitter.event().name("end").data(endData.toJSONString()));
        } catch (IOException e) {
            log.warn("SSE complete failed, streamId: {}, error: {}", streamId, e.getMessage());
        } finally {
            try {
                emitter.complete();
            } catch (Exception e) {
                log.warn("SSE emitter complete failed, streamId: {}, error: {}", streamId, e.getMessage());
            }
        }
    }

    private void sendData(JSONObject payload) {
        if (detached) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name("data").data(payload.toJSONString()));
        } catch (IOException e) {
            log.warn("SSE send failed, streamId: {}, error: {}", streamId, e.getMessage());
        }
    }

    public StringBuffer getFinalResult() {
        return finalResult;
    }

    public StringBuffer getThinkingResult() {
        return thinkingResult;
    }

    public StringBuffer getTraceResult() {
        return traceResult;
    }

    public boolean isManagedSearchTrace() {
        return managedSearchTrace;
    }
}
