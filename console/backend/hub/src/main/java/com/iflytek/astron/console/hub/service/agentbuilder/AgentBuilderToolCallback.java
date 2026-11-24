package com.iflytek.astron.console.hub.service.agentbuilder;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.iflytek.astron.console.commons.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.nio.charset.StandardCharsets;
import java.util.function.Function;

/**
 * A builder tool exposed to the model through Spring AI. The handler parses its JSON arguments and
 * edits the draft; validation problems are returned to the model as {@code {"ok":false,...}} so it
 * can correct itself instead of failing the turn.
 */
@Slf4j
public class AgentBuilderToolCallback implements ToolCallback {

    private final ToolDefinition definition;
    private final String sessionId;
    private final Function<JSONObject, String> handler;

    public AgentBuilderToolCallback(String sessionId, String name, String description, String inputSchema,
            Function<JSONObject, String> handler) {
        this.sessionId = sessionId;
        this.definition = ToolDefinition.builder()
                .name(name)
                .description(description)
                .inputSchema(inputSchema)
                .build();
        this.handler = handler;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        long start = System.currentTimeMillis();
        JSONObject args;
        try {
            args = StringUtils.isBlank(toolInput) ? new JSONObject() : JSON.parseObject(toolInput);
            if (args == null) {
                args = new JSONObject();
            }
        } catch (Exception e) {
            log.warn("Agent builder tool got invalid JSON, sessionId={}, tool={}, inputBytes={}", sessionId,
                    definition.name(), toolInput.getBytes(StandardCharsets.UTF_8).length);
            return error("参数不是合法的 JSON");
        }
        try {
            String result = handler.apply(args);
            log.info("Agent builder tool ok, sessionId={}, tool={}, costMs={}", sessionId, definition.name(),
                    System.currentTimeMillis() - start);
            return result;
        } catch (IllegalArgumentException e) {
            log.info("Agent builder tool rejected input, sessionId={}, tool={}, reason={}", sessionId,
                    definition.name(), e.getMessage());
            return error(e.getMessage());
        } catch (BusinessException e) {
            log.warn("Agent builder tool business error, sessionId={}, tool={}, code={}", sessionId,
                    definition.name(), e.getCode());
            return error("操作失败：" + StringUtils.defaultString(e.getMessage(), "业务校验未通过"));
        } catch (Exception e) {
            log.error("Agent builder tool failed, sessionId={}, tool={}, errorType={}", sessionId,
                    definition.name(), e.getClass().getSimpleName(), e);
            return error("内部错误，请稍后重试");
        }
    }

    static String ok(String summary, Object data) {
        JSONObject result = new JSONObject().fluentPut("ok", true).fluentPut("summary", summary);
        if (data != null) {
            result.put("data", data);
        }
        return result.toJSONString();
    }

    static String error(String message) {
        return new JSONObject().fluentPut("ok", false).fluentPut("error", message).toJSONString();
    }
}
