package com.iflytek.astron.console.toolkit.entity.platform;

import lombok.Data;

@Data
public class PlatformAccountRuntimeConfigDto {
    private String sparkAppId;
    private String sparkVirtualManAppId;
    private boolean iflytekOpenPlatformConfigured;
    private boolean aiAbilityChatConfigured;
    private boolean virtualManConfigured;
    private boolean ragflowConfigured;
    private boolean xinghuoKnowledgeConfigured;
    private boolean volcengineConfigured;
    private boolean volcengineKnowledgeConfigured;
    /** Configured Volcengine knowledge base name / id (not a secret). */
    private String volcengineKnowledgeCollection;
}
