package com.iflytek.astron.console.toolkit.entity.platform;

/**
 * Defaults applied to blank {@link PlatformAccountConfigDto.VolcengineConfig} fields. Python
 * services (core/plugin/aitools) keep the same defaults.
 */
public final class VolcengineDefaults {
    public static final String ARK_BASE_URL = "https://ark.cn-beijing.volces.com/api/v3";
    public static final String CHAT_MODEL = "doubao-seed-2-1-pro-260915";
    public static final String VISION_MODEL = "doubao-seed-2-1-pro-260915";
    public static final String IMAGE_MODEL = "doubao-seedream-5-0-260128";
    public static final String WEB_SEARCH_SOURCE_DOUBAO = "doubao";
    public static final String WEB_SEARCH_SOURCE_PLUGIN = "plugin";
    /** Standalone 豆包搜索 API (own API key, billed per call in the 豆包搜索 console). */
    public static final String WEB_SEARCH_SOURCE_DOUBAO_API = "doubao_api";
    public static final String SEARCH_API_URL = "https://open.feedcoopapi.com/search_api/web_search";
    public static final String TTS_RESOURCE_ID = "seed-tts-1.0";
    public static final String TTS_SPEAKER = "zh_female_shuangkuaisisi_moon_bigtts";
    public static final String ASR_RESOURCE_ID = "volc.bigasr.sauc.duration";
    public static final String KNOWLEDGE_HOST = "api-knowledgebase.mlp.cn-beijing.volces.com";
    public static final String KNOWLEDGE_PROJECT = "default";

    private VolcengineDefaults() {}
}
