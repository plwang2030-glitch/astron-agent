export type PlatformAccountType =
  | 'iflytek_open_platform'
  | 'ai_ability_chat'
  | 'virtual_man'
  | 'knowledge_platform'
  | 'volcengine'
  | 'ai_capability_routing';

export type AiProvider = 'iflytek' | 'volcengine';

export type AiCapabilityKey =
  | 'defaultChat'
  | 'aiAbilityChat'
  | 'webSearch'
  | 'avatarImage'
  | 'imageGeneration'
  | 'imageUnderstanding'
  | 'ocr'
  | 'tts'
  | 'asr';

export interface PlatformAccountConfig {
  iflytekOpenPlatform?: {
    platformAppId?: string;
    platformApiKey?: string;
    platformApiSecret?: string;
    sparkApiPassword?: string;
    sparkRtasrApiKey?: string;
  };
  aiAbilityChat?: {
    baseUrl?: string;
    model?: string;
    apiKey?: string;
  };
  virtualMan?: {
    sparkVirtualManAppId?: string;
    sparkVirtualManApiKey?: string;
    sparkVirtualManApiSecret?: string;
  };
  knowledgePlatform?: {
    ragflow?: {
      baseUrl?: string;
      apiToken?: string;
      timeout?: number;
      defaultGroup?: string;
    };
    xinghuo?: {
      datasetId?: string;
    };
  };
  volcengine?: {
    arkBaseUrl?: string;
    arkApiKey?: string;
    chatModel?: string;
    visionModel?: string;
    imageModel?: string;
    webSearchSource?: 'doubao' | 'plugin' | 'doubao_api';
    /** 豆包搜索 API Key（webSearchSource 为 doubao_api 时使用） */
    searchApiKey?: string;
    speechApiKey?: string;
    speechAppId?: string;
    speechAccessToken?: string;
    ttsResourceId?: string;
    ttsSpeaker?: string;
    asrResourceId?: string;
    knowledgeApiKey?: string;
    knowledgeHost?: string;
    knowledgeProject?: string;
    knowledgeCollection?: string;
  };
  aiCapabilityRouting?: Partial<Record<AiCapabilityKey, AiProvider>>;
  configured?: boolean;
}

export interface PlatformAccountCard {
  type: PlatformAccountType;
  name: string;
  configured: boolean;
  config: PlatformAccountConfig;
}

export interface PlatformAccountRuntimeConfig {
  sparkAppId?: string;
  sparkVirtualManAppId?: string;
  iflytekOpenPlatformConfigured: boolean;
  aiAbilityChatConfigured: boolean;
  virtualManConfigured: boolean;
  ragflowConfigured: boolean;
  xinghuoKnowledgeConfigured: boolean;
  volcengineConfigured: boolean;
  volcengineKnowledgeConfigured: boolean;
  volcengineKnowledgeCollection?: string | null;
}
