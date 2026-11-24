package com.iflytek.astron.console.commons.constant;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every {@link ResponseEnum} message key must be translated; otherwise the raw key (for example
 * "error.insufficient.permissions") is shown to users.
 */
class ResponseEnumMessagesTest {

    @ParameterizedTest
    @ValueSource(strings = {"messages_zh.properties", "messages_en.properties"})
    void everyResponseEnumKeyHasMessage(String bundle) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(bundle)) {
            assertThat(in).as(bundle).isNotNull();
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }

        List<String> missing = Arrays.stream(ResponseEnum.values())
                .map(ResponseEnum::getMessageKey)
                .filter(key -> !properties.containsKey(key))
                .distinct()
                .toList();

        assertThat(missing).as("ResponseEnum keys missing in " + bundle).isEmpty();
    }
}
