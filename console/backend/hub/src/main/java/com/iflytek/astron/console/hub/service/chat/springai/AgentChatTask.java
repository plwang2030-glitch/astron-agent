package com.iflytek.astron.console.hub.service.chat.springai;

import com.alibaba.fastjson2.JSONObject;
import com.iflytek.astron.console.commons.dto.llm.SparkChatRequest;
import com.iflytek.astron.console.commons.entity.chat.ChatReqRecords;
import com.iflytek.astron.console.toolkit.entity.vo.LLMInfoVo;
import lombok.Builder;
import lombok.Data;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Normalized input for a single standalone-agent chat turn (formal / re-answer / debug). */
@Data
@Builder
public class AgentChatTask {

    /**
     * Non-Spark custom model config; {@code null} means a Spark model (use {@link #sparkModelName}).
     */
    private LLMInfoVo llmInfoVo;

    /** Stored Spark model name, used when {@link #llmInfoVo} is {@code null}. */
    private String sparkModelName;

    private List<SparkChatRequest.MessageDto> messages;
    private String openedTool;
    private String mcpServerUrls;

    /**
     * Enriched skills (skillId/name/description/downloadUrl/resources/sandbox), resolved on the request
     * thread before streaming begins.
     */
    private List<JSONObject> skills;

    /** Saved tool-square plugins JSON ({@code [{"toolId":"tool@xxx",...}]}); resolved to Link tools. */
    private String tools;

    /** Saved workflows JSON ({@code [{"flowId":"xxx",...}]}); resolved to workflow tools. */
    private String workflows;

    private String userId;
    private Long chatId;
    private Integer botId;
    private Long spaceId;
    private String debugSessionId;
    private String rawUserText;

    /** {@code null} for debug turns (no persistence). */
    private ChatReqRecords chatReqRecords;

    private boolean edit;
    private boolean debug;

    /**
     * Extra caller-supplied tools (e.g. the agent builder's draft-editing tools), appended after the
     * resolved bot tools. {@code null} for regular chat.
     */
    private List<ToolCallback> extraToolCallbacks;

    /**
     * Attached agents resolved on the request thread; each becomes a {@code call_agent_<botId>} tool.
     * {@code null} or empty for none.
     */
    private List<PreparedSubAgent> subAgents;

    /**
     * When {@code true}, the always-on built-in tools (web_search / current_time) are not registered.
     * Only {@link #extraToolCallbacks} and explicitly configured tools are exposed.
     */
    private boolean builtinToolsDisabled;

    /**
     * Run detached from the SSE response with a longer limit; the user is notified when it ends. Formal
     * chat only (needs {@link #chatReqRecords} for the result to be saved).
     */
    private boolean background;

    /** Provider-specific request body fields (e.g. Ark {@code thinking}); {@code null} for none. */
    private Map<String, Object> extraBody;

    /**
     * The agent's 深度思考 switch; {@code null} or {@code true} keeps the model default. Fields already set
     * in {@link #extraBody} win.
     */
    private Boolean deepThinking;

    /** Opt-in built-in tool groups of the agent (e.g. {@code a_share}); {@code null} for none. */
    private List<String> builtinTools;
    /** Called with the final answer text after the stream completes normally. */
    private Consumer<String> onComplete;
}
