package com.iflytek.astron.console.toolkit.entity.platform;

import lombok.Getter;

import java.util.function.Function;

/**
 * AI capabilities whose provider can be switched in 「平台账号管理 - AI 能力配置」. The key matches the JSON
 * field stored under {@code platform_account_text:ai_capability_routing}, which Python services
 * also read.
 */
@Getter
public enum AiCapability {
    DEFAULT_CHAT("defaultChat", PlatformAccountConfigDto.AiCapabilityRoutingConfig::getDefaultChat),
    AI_ABILITY_CHAT("aiAbilityChat", PlatformAccountConfigDto.AiCapabilityRoutingConfig::getAiAbilityChat),
    WEB_SEARCH("webSearch", PlatformAccountConfigDto.AiCapabilityRoutingConfig::getWebSearch),
    AVATAR_IMAGE("avatarImage", PlatformAccountConfigDto.AiCapabilityRoutingConfig::getAvatarImage),
    IMAGE_GENERATION("imageGeneration", PlatformAccountConfigDto.AiCapabilityRoutingConfig::getImageGeneration),
    IMAGE_UNDERSTANDING("imageUnderstanding",
            PlatformAccountConfigDto.AiCapabilityRoutingConfig::getImageUnderstanding),
    OCR("ocr", PlatformAccountConfigDto.AiCapabilityRoutingConfig::getOcr),
    TTS("tts", PlatformAccountConfigDto.AiCapabilityRoutingConfig::getTts),
    ASR("asr", PlatformAccountConfigDto.AiCapabilityRoutingConfig::getAsr);

    private final String key;
    private final Function<PlatformAccountConfigDto.AiCapabilityRoutingConfig, String> reader;

    AiCapability(String key, Function<PlatformAccountConfigDto.AiCapabilityRoutingConfig, String> reader) {
        this.key = key;
        this.reader = reader;
    }
}
