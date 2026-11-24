package com.iflytek.astron.console.hub.service.chat.springai;

import com.iflytek.astron.console.toolkit.entity.platform.AiCapability;
import com.iflytek.astron.console.toolkit.entity.platform.PlatformAccountConfigDto;
import com.iflytek.astron.console.toolkit.entity.vo.LLMInfoVo;
import com.iflytek.astron.console.toolkit.service.platform.PlatformAccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Map;

/**
 * Builds {@link OpenAiChatModel} instances per request from a bot's model configuration. Custom
 * models use their stored OpenAI-compatible endpoint; Spark (decision A1) uses the iFlytek
 * OpenAI-compatible endpoint with the platform API password.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatModelFactory {

    /** Full Spark OpenAI-compatible completions URL. Confirm against Task 0 Step 1. */
    private static final String SPARK_OPENAI_COMPLETIONS_URL = "https://spark-api-open.xf-yun.com/v1/chat/completions";

    /** Maps stored Spark model names to Spark open-API model ids. Confirm/extend per Task 0 Step 1. */
    private static final Map<String, String> SPARK_MODEL_IDS = Map.of(
            "spark-x1", "x1",
            "spark-max", "generalv3.5",
            "spark-pro", "generalv3",
            "spark-lite", "lite");

    private final PlatformAccountService platformAccountService;

    /** @param ark the endpoint is Volcengine Ark, which accepts the {@code thinking} request field */
    public record AgentModel(OpenAiChatModel chatModel, String modelId, boolean ark) {}

    private static final Map<String, Object> THINKING_DISABLED = Map.of("thinking", Map.of("type", "disabled"));

    /**
     * Request body fields for an agent's 深度思考 switch. On keeps the model's default (no field); off
     * sends Ark's {@code thinking: {type: disabled}}. Other providers use different, model-specific
     * fields, so nothing is sent for them and the switch has no effect there.
     *
     * @return extra body fields, or {@code null} for none
     */
    public static Map<String, Object> thinkingBody(AgentModel model, boolean deepThinking) {
        return !deepThinking && model.ark() ? THINKING_DISABLED : null;
    }

    /** Custom (non-Spark) OpenAI-compatible model. */
    public AgentModel forCustomModel(LLMInfoVo info) {
        String modelId = info.getDomain();
        return new AgentModel(build(info.getUrl(), info.getApiKey(), modelId), modelId, isArk(info.getUrl()));
    }

    static boolean isArk(String url) {
        if (StringUtils.isBlank(url)) {
            return false;
        }
        try {
            String host = URI.create(url.trim()).getHost();
            return host != null && (host.equals("volces.com") || host.endsWith(".volces.com"));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Built-in default model. Spark via the OpenAI-compatible endpoint (decision A1), or Volcengine Ark
     * when 「AI 能力配置 - 默认对话模型」 selects volcengine.
     */
    public AgentModel forSparkModel(String sparkModelName) {
        if (platformAccountService.routesToVolcengine(AiCapability.DEFAULT_CHAT)) {
            PlatformAccountConfigDto.VolcengineConfig volc = platformAccountService.requireVolcengineArk();
            String modelId = volc.getChatModel();
            log.info("Default chat routed to volcengine, requestedModel={}, arkModel={}", sparkModelName, modelId);
            return new AgentModel(build(volc.getArkBaseUrl() + "/chat/completions", volc.getArkApiKey(), modelId),
                    modelId, true);
        }
        PlatformAccountConfigDto.IflytekOpenPlatformConfig config = platformAccountService.requireIflytekOpenPlatform();
        String modelId = sparkModelToOpenAiModelId(sparkModelName);
        return new AgentModel(build(SPARK_OPENAI_COMPLETIONS_URL, config.getSparkApiPassword(), modelId), modelId,
                false);
    }

    private OpenAiChatModel build(String fullUrl, String apiKey, String model) {
        URI uri = URI.create(fullUrl);
        String baseUrl = uri.getScheme() + "://" + uri.getAuthority();
        String completionsPath = StringUtils.defaultIfBlank(uri.getRawPath(), "/v1/chat/completions");
        if (StringUtils.isNotBlank(uri.getRawQuery())) {
            completionsPath += "?" + uri.getRawQuery();
        }
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .completionsPath(completionsPath)
                .apiKey(apiKey)
                .build();
        OpenAiChatOptions options = OpenAiChatOptions.builder().model(model).build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .toolCallingManager(new BlankToolCallFilteringToolCallingManager(ToolCallingManager.builder().build()))
                .build();
    }

    static String sparkModelToOpenAiModelId(String sparkModelName) {
        if (StringUtils.isBlank(sparkModelName)) {
            return sparkModelName;
        }
        return SPARK_MODEL_IDS.getOrDefault(sparkModelName, sparkModelName);
    }
}
