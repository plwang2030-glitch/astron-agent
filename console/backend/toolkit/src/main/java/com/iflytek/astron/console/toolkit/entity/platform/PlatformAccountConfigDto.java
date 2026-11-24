package com.iflytek.astron.console.toolkit.entity.platform;

import lombok.Data;

@Data
public class PlatformAccountConfigDto {
    private IflytekOpenPlatformConfig iflytekOpenPlatform;
    private AiAbilityChatConfig aiAbilityChat;
    private VirtualManConfig virtualMan;
    private KnowledgePlatformConfig knowledgePlatform;
    private VolcengineConfig volcengine;
    private AiCapabilityRoutingConfig aiCapabilityRouting;
    private Boolean configured;

    @Data
    public static class IflytekOpenPlatformConfig {
        private String platformAppId;
        private String platformApiKey;
        private String platformApiSecret;
        private String sparkApiPassword;
        private String sparkRtasrApiKey;
    }

    @Data
    public static class AiAbilityChatConfig {
        private String baseUrl;
        private String model;
        private String apiKey;
    }

    @Data
    public static class VirtualManConfig {
        private String sparkVirtualManAppId;
        private String sparkVirtualManApiKey;
        private String sparkVirtualManApiSecret;
    }

    @Data
    public static class KnowledgePlatformConfig {
        private RagflowConfig ragflow;
        private XinghuoKnowledgeConfig xinghuo;
    }

    @Data
    public static class RagflowConfig {
        private String baseUrl;
        private String apiToken;
        private Integer timeout;
        private String defaultGroup;
    }

    @Data
    public static class XinghuoKnowledgeConfig {
        private String datasetId;
    }

    /**
     * ByteDance Volcengine credentials. Ark (方舟) covers chat, vision, image generation and web search;
     * Doubao Speech (豆包语音) covers TTS and ASR and uses its own credentials.
     */
    @Data
    public static class VolcengineConfig {
        /** Ark API root, e.g. https://ark.cn-beijing.volces.com/api/v3 */
        private String arkBaseUrl;
        private String arkApiKey;
        private String chatModel;
        private String visionModel;
        private String imageModel;
        /**
         * Web search source: "doubao" (豆包搜索 Custom 版 via Ark), "plugin" (联网内容插件 via Ark) or "doubao_api"
         * (standalone 豆包搜索 API with {@link #searchApiKey}).
         */
        private String webSearchSource;
        /** Standalone 豆包搜索 API key (豆包搜索控制台), used when {@link #webSearchSource} is "doubao_api". */
        private String searchApiKey;
        /** New-console Doubao Speech API key (X-Api-Key). */
        private String speechApiKey;
        /** Old-console Doubao Speech AppID (X-Api-App-Id / X-Api-App-Key). */
        private String speechAppId;
        /** Old-console Doubao Speech access token (X-Api-Access-Key). */
        private String speechAccessToken;
        private String ttsResourceId;
        private String ttsSpeaker;
        private String asrResourceId;
        /** Knowledge base (VikingDB) data-plane API Key (Authorization: Bearer). */
        private String knowledgeApiKey;
        /** Knowledge base API host, e.g. api-knowledgebase.mlp.cn-beijing.volces.com */
        private String knowledgeHost;
        /** Default Volcengine project for knowledge bases bound by name. */
        private String knowledgeProject;
        /**
         * Knowledge base bound when a 火山引擎知识库 repo is created: {@code collection_name},
         * {@code project/collection_name} or a {@code kb-} resource id.
         */
        private String knowledgeCollection;
    }

    /** Per-capability provider selection. Blank or unknown values mean {@code iflytek}. */
    @Data
    public static class AiCapabilityRoutingConfig {
        private String defaultChat;
        private String aiAbilityChat;
        private String webSearch;
        private String avatarImage;
        private String imageGeneration;
        private String imageUnderstanding;
        private String ocr;
        private String tts;
        private String asr;
    }
}
