package com.iflytek.astron.console.toolkit.entity.platform;

import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;

/** Providers selectable per {@link AiCapability}. {@link #IFLYTEK} is the default. */
public enum AiProvider {
    IFLYTEK("iflytek"),
    VOLCENGINE("volcengine");

    private final String value;

    AiProvider(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    /**
     * Returns the provider for a stored value; blank maps to {@link #IFLYTEK}, unknown to {@code null}.
     */
    public static AiProvider fromValue(String value) {
        if (StringUtils.isBlank(value)) {
            return IFLYTEK;
        }
        String normalized = value.trim();
        return Arrays.stream(values())
                .filter(provider -> provider.value.equalsIgnoreCase(normalized))
                .findFirst()
                .orElse(null);
    }
}
