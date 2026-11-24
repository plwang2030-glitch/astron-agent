package com.iflytek.astron.console.toolkit.service.bot;

import com.openai.core.JsonValue;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiModelProcessServiceTest {

    private static ChatCompletionCreateParams build(boolean volcengine) {
        ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
                .model("m")
                .addUserMessage("hi");
        OpenAiModelProcessService.applyProviderOptions(builder, volcengine);
        return builder.build();
    }

    @Test
    void disablesThinkingForVolcengine() {
        Map<String, JsonValue> extra = build(true)._additionalBodyProperties();

        assertThat(extra).containsKey("thinking");
        assertThat(extra.get("thinking")).isEqualTo(JsonValue.from(Map.of("type", "disabled")));
    }

    @Test
    void sendsNoExtraFieldsToOtherProviders() {
        assertThat(build(false)._additionalBodyProperties()).isEmpty();
    }
}
