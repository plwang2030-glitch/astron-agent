package com.iflytek.astron.console.toolkit.service.platform;

import com.alibaba.fastjson2.JSON;
import com.iflytek.astron.console.commons.constant.RedisKeyConstant;
import com.iflytek.astron.console.commons.constant.ResponseEnum;
import com.iflytek.astron.console.commons.exception.BusinessException;
import com.iflytek.astron.console.toolkit.entity.platform.AiCapability;
import com.iflytek.astron.console.toolkit.entity.platform.AiProvider;
import com.iflytek.astron.console.toolkit.entity.platform.PlatformAccountCardDto;
import com.iflytek.astron.console.toolkit.entity.platform.PlatformAccountConfigDto;
import com.iflytek.astron.console.toolkit.entity.platform.PlatformAccountRuntimeConfigDto;
import com.iflytek.astron.console.toolkit.entity.platform.PlatformAccountType;
import com.iflytek.astron.console.toolkit.entity.platform.VolcengineDefaults;
import com.iflytek.astron.console.toolkit.entity.table.ConfigInfo;
import com.iflytek.astron.console.toolkit.mapper.ConfigInfoMapper;
import com.iflytek.astron.console.toolkit.util.RedisUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
public class PlatformAccountService {

    private static final String CATEGORY = "PLATFORM_ACCOUNT";
    private static final String MASK = "******";

    @Resource
    private ConfigInfoMapper configInfoMapper;

    @Resource
    private RedisUtil redisUtil;

    public List<PlatformAccountCardDto> listCards() {
        return Arrays.stream(PlatformAccountType.values())
                .map(type -> new PlatformAccountCardDto(type, isConfigured(type), getMaskedConfig(type)))
                .toList();
    }

    public PlatformAccountConfigDto getConfig(PlatformAccountType type) {
        String cached = redisUtil.getStr(cacheKey(type));
        if (StringUtils.isNotBlank(cached)) {
            redisUtil.putString(textCacheKey(type), cached);
            return parse(type, cached);
        }
        ConfigInfo configInfo = configInfoMapper.getByCategoryAndCode(CATEGORY, type.getCode());
        if (configInfo == null || StringUtils.isBlank(configInfo.getValue())) {
            return new PlatformAccountConfigDto();
        }
        cacheConfigValue(type, configInfo.getValue());
        return parse(type, configInfo.getValue());
    }

    public PlatformAccountConfigDto getMaskedConfig(PlatformAccountType type) {
        PlatformAccountConfigDto raw = getConfig(type);
        raw.setConfigured(isConfigured(type, raw));
        return mask(type, raw);
    }

    public PlatformAccountConfigDto saveConfig(PlatformAccountType type, PlatformAccountConfigDto request) {
        PlatformAccountConfigDto previous = getConfig(type);
        PlatformAccountConfigDto merged = mergeMaskedSecrets(type, request, previous);
        validate(type, merged);
        String value = JSON.toJSONString(extractValue(type, merged));

        ConfigInfo existing = configInfoMapper.getByCategoryAndCode(CATEGORY, type.getCode());
        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            ConfigInfo configInfo = new ConfigInfo();
            configInfo.setCategory(CATEGORY);
            configInfo.setCode(type.getCode());
            configInfo.setName(type.getDisplayName());
            configInfo.setValue(value);
            configInfo.setIsValid(1);
            configInfo.setCreateTime(now);
            configInfo.setUpdateTime(now);
            configInfoMapper.insert(configInfo);
        } else {
            existing.setName(type.getDisplayName());
            existing.setValue(value);
            existing.setIsValid(1);
            existing.setUpdateTime(now);
            configInfoMapper.updateById(existing);
        }
        redisUtil.remove(cacheKey(type));
        redisUtil.removeString(textCacheKey(type));
        return getMaskedConfig(type);
    }

    public PlatformAccountRuntimeConfigDto getRuntimeConfig() {
        PlatformAccountConfigDto iflytek = getConfig(PlatformAccountType.IFLYTEK_OPEN_PLATFORM);
        PlatformAccountConfigDto virtualMan = getConfig(PlatformAccountType.VIRTUAL_MAN);
        PlatformAccountConfigDto aiAbility = getConfig(PlatformAccountType.AI_ABILITY_CHAT);
        PlatformAccountConfigDto knowledge = getConfig(PlatformAccountType.KNOWLEDGE_PLATFORM);

        PlatformAccountRuntimeConfigDto dto = new PlatformAccountRuntimeConfigDto();
        PlatformAccountConfigDto.IflytekOpenPlatformConfig platform = iflytek.getIflytekOpenPlatform();
        if (platform != null) {
            dto.setSparkAppId(platform.getPlatformAppId());
        }
        PlatformAccountConfigDto.VirtualManConfig virtualManConfig = virtualMan.getVirtualMan();
        if (virtualManConfig != null) {
            dto.setSparkVirtualManAppId(virtualManConfig.getSparkVirtualManAppId());
        }
        dto.setIflytekOpenPlatformConfigured(isConfigured(PlatformAccountType.IFLYTEK_OPEN_PLATFORM, iflytek));
        dto.setAiAbilityChatConfigured(isConfigured(PlatformAccountType.AI_ABILITY_CHAT, aiAbility));
        dto.setVirtualManConfigured(isConfigured(PlatformAccountType.VIRTUAL_MAN, virtualMan));
        dto.setRagflowConfigured(isRagflowConfigured(knowledge));
        dto.setXinghuoKnowledgeConfigured(isXinghuoConfigured(knowledge));
        dto.setVolcengineConfigured(isConfigured(PlatformAccountType.VOLCENGINE));
        PlatformAccountConfigDto volcengine = getConfig(PlatformAccountType.VOLCENGINE);
        PlatformAccountConfigDto.VolcengineConfig volcItem = volcengine == null ? null : volcengine.getVolcengine();
        dto.setVolcengineKnowledgeConfigured(isVolcengineKnowledgeConfigured(volcItem));
        dto.setVolcengineKnowledgeCollection(
                volcItem == null ? null : StringUtils.trimToNull(volcItem.getKnowledgeCollection()));
        return dto;
    }

    /**
     * Provider selected for a capability in 「AI 能力配置」. Missing config, blank or unknown values fall
     * back to iFLYTEK so existing deployments keep their behavior.
     */
    public AiProvider resolveProvider(AiCapability capability) {
        PlatformAccountConfigDto config = getConfig(PlatformAccountType.AI_CAPABILITY_ROUTING);
        PlatformAccountConfigDto.AiCapabilityRoutingConfig routing =
                config == null ? null : config.getAiCapabilityRouting();
        String raw = routing == null ? null : capability.getReader().apply(routing);
        AiProvider provider = AiProvider.fromValue(raw);
        if (provider == null) {
            log.warn("Unknown AI provider '{}' for capability {}, falling back to iflytek", raw, capability.getKey());
            return AiProvider.IFLYTEK;
        }
        return provider;
    }

    public boolean routesToVolcengine(AiCapability capability) {
        return resolveProvider(capability) == AiProvider.VOLCENGINE;
    }

    /** Volcengine Ark credentials with defaults applied to blank base URL and model fields. */
    public PlatformAccountConfigDto.VolcengineConfig requireVolcengineArk() {
        PlatformAccountConfigDto config = getConfig(PlatformAccountType.VOLCENGINE);
        PlatformAccountConfigDto.VolcengineConfig item = config == null ? null : config.getVolcengine();
        if (item == null || !hasText(item.getArkApiKey())) {
            throwNotConfigured("火山引擎（字节）- 方舟 API Key");
        }
        return withVolcengineDefaults(item);
    }

    /**
     * Web search settings with defaults applied. The standalone 豆包搜索 API ({@code doubao_api}) needs
     * only its own search API key; the Ark sources need the Ark API key.
     */
    public PlatformAccountConfigDto.VolcengineConfig requireVolcengineWebSearch() {
        PlatformAccountConfigDto config = getConfig(PlatformAccountType.VOLCENGINE);
        PlatformAccountConfigDto.VolcengineConfig item = config == null ? null : config.getVolcengine();
        boolean standalone = item != null
                && VolcengineDefaults.WEB_SEARCH_SOURCE_DOUBAO_API.equalsIgnoreCase(StringUtils.trim(item.getWebSearchSource()));
        if (standalone) {
            if (!hasText(item.getSearchApiKey())) {
                throwNotConfigured("火山引擎（字节）- 豆包搜索 API Key");
            }
            return withVolcengineDefaults(item);
        }
        return requireVolcengineArk();
    }

    /**
     * Doubao Speech credentials: either the new-console API key or the old-console AppID + Access
     * Token.
     */
    public PlatformAccountConfigDto.VolcengineConfig requireVolcengineSpeech() {
        PlatformAccountConfigDto config = getConfig(PlatformAccountType.VOLCENGINE);
        PlatformAccountConfigDto.VolcengineConfig item = config == null ? null : config.getVolcengine();
        if (!isVolcengineSpeechConfigured(item)) {
            throwNotConfigured("火山引擎（字节）- 豆包语音 API Key 或 AppID + Access Token");
        }
        return withVolcengineDefaults(item);
    }

    /**
     * Knowledge base configured under 平台账号管理 - 火山引擎（字节）, bound when a 火山引擎知识库 repo is created. Throws
     * when it is not configured.
     */
    public String requireVolcengineKnowledgeCollection() {
        PlatformAccountConfigDto config = getConfig(PlatformAccountType.VOLCENGINE);
        PlatformAccountConfigDto.VolcengineConfig item = config == null ? null : config.getVolcengine();
        String collection = item == null ? null : StringUtils.trimToNull(item.getKnowledgeCollection());
        if (collection == null) {
            throwNotConfigured("火山引擎（字节）- 火山知识库名称或 ID");
        }
        return collection;
    }

    /** Volcengine knowledge base (VikingDB) API Key with host / project defaults applied. */
    public PlatformAccountConfigDto.VolcengineConfig requireVolcengineKnowledge() {
        PlatformAccountConfigDto config = getConfig(PlatformAccountType.VOLCENGINE);
        PlatformAccountConfigDto.VolcengineConfig item = config == null ? null : config.getVolcengine();
        if (!isVolcengineKnowledgeConfigured(item)) {
            throwNotConfigured("火山引擎（字节）- 知识库 API Key");
        }
        return withVolcengineDefaults(item);
    }

    /**
     * Endpoint for the system AI Ability Chat helpers. Routed to Volcengine Ark when selected,
     * otherwise the dedicated AI Ability Chat account.
     */
    public PlatformAccountConfigDto.AiAbilityChatConfig resolveAiAbilityChat() {
        if (!routesToVolcengine(AiCapability.AI_ABILITY_CHAT)) {
            return requireAiAbilityChat();
        }
        PlatformAccountConfigDto.VolcengineConfig volc = requireVolcengineArk();
        PlatformAccountConfigDto.AiAbilityChatConfig chat = new PlatformAccountConfigDto.AiAbilityChatConfig();
        chat.setBaseUrl(volc.getArkBaseUrl());
        chat.setModel(volc.getChatModel());
        chat.setApiKey(volc.getArkApiKey());
        log.info("AI Ability Chat routed to volcengine, model={}", chat.getModel());
        return chat;
    }

    private PlatformAccountConfigDto.VolcengineConfig withVolcengineDefaults(
            PlatformAccountConfigDto.VolcengineConfig source) {
        PlatformAccountConfigDto.VolcengineConfig item =
                JSON.parseObject(JSON.toJSONString(source), PlatformAccountConfigDto.VolcengineConfig.class);
        item.setArkBaseUrl(StringUtils.removeEnd(
                StringUtils.defaultIfBlank(StringUtils.trim(item.getArkBaseUrl()), VolcengineDefaults.ARK_BASE_URL),
                "/"));
        item.setChatModel(StringUtils.defaultIfBlank(item.getChatModel(), VolcengineDefaults.CHAT_MODEL));
        item.setVisionModel(StringUtils.defaultIfBlank(item.getVisionModel(), VolcengineDefaults.VISION_MODEL));
        item.setImageModel(StringUtils.defaultIfBlank(item.getImageModel(), VolcengineDefaults.IMAGE_MODEL));
        item.setWebSearchSource(StringUtils.defaultIfBlank(
                item.getWebSearchSource(), VolcengineDefaults.WEB_SEARCH_SOURCE_DOUBAO));
        item.setTtsResourceId(StringUtils.defaultIfBlank(item.getTtsResourceId(), VolcengineDefaults.TTS_RESOURCE_ID));
        item.setTtsSpeaker(StringUtils.defaultIfBlank(item.getTtsSpeaker(), VolcengineDefaults.TTS_SPEAKER));
        item.setAsrResourceId(StringUtils.defaultIfBlank(item.getAsrResourceId(), VolcengineDefaults.ASR_RESOURCE_ID));
        item.setKnowledgeHost(normalizeKnowledgeHost(item.getKnowledgeHost()));
        item.setKnowledgeProject(StringUtils.defaultIfBlank(
                StringUtils.trim(item.getKnowledgeProject()), VolcengineDefaults.KNOWLEDGE_PROJECT));
        return item;
    }

    /** Accepts a bare host or a URL; core/knowledge always uses HTTPS. */
    private String normalizeKnowledgeHost(String host) {
        String value = StringUtils.trimToEmpty(host);
        value = StringUtils.removeStartIgnoreCase(value, "https://");
        value = StringUtils.removeStartIgnoreCase(value, "http://");
        value = StringUtils.removeEnd(value, "/");
        return StringUtils.defaultIfBlank(value, VolcengineDefaults.KNOWLEDGE_HOST);
    }

    private boolean isVolcengineKnowledgeConfigured(PlatformAccountConfigDto.VolcengineConfig item) {
        return item != null && hasText(item.getKnowledgeApiKey());
    }

    private boolean isVolcengineSpeechConfigured(PlatformAccountConfigDto.VolcengineConfig item) {
        return item != null
                && (hasText(item.getSpeechApiKey())
                        || (hasText(item.getSpeechAppId()) && hasText(item.getSpeechAccessToken())));
    }

    private void validate(PlatformAccountType type, PlatformAccountConfigDto config) {
        if (type != PlatformAccountType.AI_CAPABILITY_ROUTING || config.getAiCapabilityRouting() == null) {
            return;
        }
        PlatformAccountConfigDto.AiCapabilityRoutingConfig routing = config.getAiCapabilityRouting();
        for (AiCapability capability : AiCapability.values()) {
            String raw = capability.getReader().apply(routing);
            if (AiProvider.fromValue(raw) == null) {
                log.warn("Rejecting AI capability routing, capability={}, provider={}", capability.getKey(), raw);
                throw new BusinessException(ResponseEnum.PARAMS_ERROR);
            }
        }
    }

    public PlatformAccountConfigDto.IflytekOpenPlatformConfig requireIflytekOpenPlatform() {
        PlatformAccountConfigDto config = getConfig(PlatformAccountType.IFLYTEK_OPEN_PLATFORM);
        if (!isConfigured(PlatformAccountType.IFLYTEK_OPEN_PLATFORM, config)) {
            throwNotConfigured(PlatformAccountType.IFLYTEK_OPEN_PLATFORM.getDisplayName());
        }
        return config.getIflytekOpenPlatform();
    }

    public PlatformAccountConfigDto.IflytekOpenPlatformConfig requireXinghuoKnowledgePlatformCredentials() {
        PlatformAccountConfigDto config = getConfig(PlatformAccountType.IFLYTEK_OPEN_PLATFORM);
        PlatformAccountConfigDto.IflytekOpenPlatformConfig item =
                config == null ? null : config.getIflytekOpenPlatform();
        if (item == null || !hasText(item.getPlatformAppId()) || !hasText(item.getPlatformApiSecret())) {
            throwNotConfigured("讯飞开放平台（星火知识库需要 PLATFORM_APP_ID 和 PLATFORM_API_SECRET）");
        }
        return item;
    }

    public PlatformAccountConfigDto.AiAbilityChatConfig requireAiAbilityChat() {
        PlatformAccountConfigDto config = getConfig(PlatformAccountType.AI_ABILITY_CHAT);
        if (!isConfigured(PlatformAccountType.AI_ABILITY_CHAT, config)) {
            throwNotConfigured(PlatformAccountType.AI_ABILITY_CHAT.getDisplayName());
        }
        return config.getAiAbilityChat();
    }

    public PlatformAccountConfigDto.VirtualManConfig requireVirtualMan() {
        PlatformAccountConfigDto config = getConfig(PlatformAccountType.VIRTUAL_MAN);
        if (!isConfigured(PlatformAccountType.VIRTUAL_MAN, config)) {
            throwNotConfigured(PlatformAccountType.VIRTUAL_MAN.getDisplayName());
        }
        return config.getVirtualMan();
    }

    public PlatformAccountConfigDto.RagflowConfig requireRagflow() {
        PlatformAccountConfigDto config = getConfig(PlatformAccountType.KNOWLEDGE_PLATFORM);
        if (!isRagflowConfigured(config)) {
            throwNotConfigured("知识库平台 - RAGFlow");
        }
        return config.getKnowledgePlatform().getRagflow();
    }

    public PlatformAccountConfigDto.XinghuoKnowledgeConfig requireXinghuoKnowledge() {
        PlatformAccountConfigDto config = getConfig(PlatformAccountType.KNOWLEDGE_PLATFORM);
        if (!isXinghuoConfigured(config)) {
            throwNotConfigured("知识库平台 - 星火知识库");
        }
        return config.getKnowledgePlatform().getXinghuo();
    }

    public boolean isConfigured(PlatformAccountType type) {
        return isConfigured(type, getConfig(type));
    }

    public boolean isConfigured(PlatformAccountType type, PlatformAccountConfigDto config) {
        if (config == null) {
            return false;
        }
        return switch (type) {
            case IFLYTEK_OPEN_PLATFORM -> {
                PlatformAccountConfigDto.IflytekOpenPlatformConfig item = config.getIflytekOpenPlatform();
                yield item != null
                        && hasText(item.getPlatformAppId())
                        && hasText(item.getPlatformApiKey())
                        && hasText(item.getPlatformApiSecret())
                        && hasText(item.getSparkApiPassword())
                        && hasText(item.getSparkRtasrApiKey());
            }
            case AI_ABILITY_CHAT -> {
                PlatformAccountConfigDto.AiAbilityChatConfig item = config.getAiAbilityChat();
                yield item != null && hasText(item.getBaseUrl()) && hasText(item.getModel()) && hasText(item.getApiKey());
            }
            case VIRTUAL_MAN -> {
                PlatformAccountConfigDto.VirtualManConfig item = config.getVirtualMan();
                yield item != null
                        && hasText(item.getSparkVirtualManAppId())
                        && hasText(item.getSparkVirtualManApiKey())
                        && hasText(item.getSparkVirtualManApiSecret());
            }
            case KNOWLEDGE_PLATFORM -> isRagflowConfigured(config) || isXinghuoConfigured(config);
            case VOLCENGINE -> {
                PlatformAccountConfigDto.VolcengineConfig item = config.getVolcengine();
                yield item != null && (hasText(item.getArkApiKey()) || hasText(item.getSearchApiKey())
                        || isVolcengineSpeechConfigured(item)
                        || isVolcengineKnowledgeConfigured(item));
            }
            case AI_CAPABILITY_ROUTING -> {
                PlatformAccountConfigDto.AiCapabilityRoutingConfig item = config.getAiCapabilityRouting();
                yield item != null && Arrays.stream(AiCapability.values())
                        .anyMatch(capability -> StringUtils.isNotBlank(capability.getReader().apply(item)));
            }
        };
    }

    private boolean isRagflowConfigured(PlatformAccountConfigDto config) {
        if (config == null || config.getKnowledgePlatform() == null) {
            return false;
        }
        PlatformAccountConfigDto.RagflowConfig item = config.getKnowledgePlatform().getRagflow();
        return item != null && hasText(item.getBaseUrl()) && hasText(item.getApiToken());
    }

    private boolean isXinghuoConfigured(PlatformAccountConfigDto config) {
        if (config == null || config.getKnowledgePlatform() == null) {
            return false;
        }
        PlatformAccountConfigDto.XinghuoKnowledgeConfig item = config.getKnowledgePlatform().getXinghuo();
        return item != null && hasText(item.getDatasetId());
    }

    private PlatformAccountConfigDto parse(PlatformAccountType type, String value) {
        PlatformAccountConfigDto dto = new PlatformAccountConfigDto();
        switch (type) {
            case IFLYTEK_OPEN_PLATFORM -> dto.setIflytekOpenPlatform(
                    JSON.parseObject(value, PlatformAccountConfigDto.IflytekOpenPlatformConfig.class));
            case AI_ABILITY_CHAT -> dto.setAiAbilityChat(
                    JSON.parseObject(value, PlatformAccountConfigDto.AiAbilityChatConfig.class));
            case VIRTUAL_MAN -> dto.setVirtualMan(
                    JSON.parseObject(value, PlatformAccountConfigDto.VirtualManConfig.class));
            case KNOWLEDGE_PLATFORM -> dto.setKnowledgePlatform(
                    JSON.parseObject(value, PlatformAccountConfigDto.KnowledgePlatformConfig.class));
            case VOLCENGINE -> dto.setVolcengine(
                    JSON.parseObject(value, PlatformAccountConfigDto.VolcengineConfig.class));
            case AI_CAPABILITY_ROUTING -> dto.setAiCapabilityRouting(
                    JSON.parseObject(value, PlatformAccountConfigDto.AiCapabilityRoutingConfig.class));
        }
        return dto;
    }

    private Object extractValue(PlatformAccountType type, PlatformAccountConfigDto dto) {
        return switch (type) {
            case IFLYTEK_OPEN_PLATFORM -> Objects.requireNonNullElseGet(
                    dto.getIflytekOpenPlatform(), PlatformAccountConfigDto.IflytekOpenPlatformConfig::new);
            case AI_ABILITY_CHAT -> Objects.requireNonNullElseGet(
                    dto.getAiAbilityChat(), PlatformAccountConfigDto.AiAbilityChatConfig::new);
            case VIRTUAL_MAN -> Objects.requireNonNullElseGet(
                    dto.getVirtualMan(), PlatformAccountConfigDto.VirtualManConfig::new);
            case KNOWLEDGE_PLATFORM -> Objects.requireNonNullElseGet(
                    dto.getKnowledgePlatform(), PlatformAccountConfigDto.KnowledgePlatformConfig::new);
            case VOLCENGINE -> Objects.requireNonNullElseGet(
                    dto.getVolcengine(), PlatformAccountConfigDto.VolcengineConfig::new);
            case AI_CAPABILITY_ROUTING -> Objects.requireNonNullElseGet(
                    dto.getAiCapabilityRouting(), PlatformAccountConfigDto.AiCapabilityRoutingConfig::new);
        };
    }

    private PlatformAccountConfigDto mergeMaskedSecrets(
            PlatformAccountType type, PlatformAccountConfigDto request, PlatformAccountConfigDto previous) {
        PlatformAccountConfigDto merged = request == null ? new PlatformAccountConfigDto() : request;
        switch (type) {
            case IFLYTEK_OPEN_PLATFORM -> mergeIflytekOpenPlatformSecrets(merged, previous);
            case AI_ABILITY_CHAT -> mergeAiAbilityChatSecrets(merged, previous);
            case VIRTUAL_MAN -> mergeVirtualManSecrets(merged, previous);
            case KNOWLEDGE_PLATFORM -> mergeKnowledgePlatformSecrets(merged, previous);
            case VOLCENGINE -> mergeVolcengineSecrets(merged, previous);
            case AI_CAPABILITY_ROUTING -> {
                // No secrets in routing config.
            }
        }
        return merged;
    }

    private void mergeVolcengineSecrets(PlatformAccountConfigDto merged, PlatformAccountConfigDto previous) {
        if (merged.getVolcengine() == null) {
            merged.setVolcengine(new PlatformAccountConfigDto.VolcengineConfig());
        }
        PlatformAccountConfigDto.VolcengineConfig current = merged.getVolcengine();
        PlatformAccountConfigDto.VolcengineConfig old = previous == null ? null : previous.getVolcengine();
        if (old == null) {
            return;
        }
        current.setArkApiKey(keepOldIfMasked(current.getArkApiKey(), old.getArkApiKey()));
        current.setSpeechApiKey(keepOldIfMasked(current.getSpeechApiKey(), old.getSpeechApiKey()));
        current.setSpeechAccessToken(keepOldIfMasked(current.getSpeechAccessToken(), old.getSpeechAccessToken()));
        current.setKnowledgeApiKey(keepOldIfMasked(current.getKnowledgeApiKey(), old.getKnowledgeApiKey()));
        current.setSearchApiKey(keepOldIfMasked(current.getSearchApiKey(), old.getSearchApiKey()));
    }

    private void mergeIflytekOpenPlatformSecrets(
            PlatformAccountConfigDto merged, PlatformAccountConfigDto previous) {
        if (merged.getIflytekOpenPlatform() == null) {
            merged.setIflytekOpenPlatform(new PlatformAccountConfigDto.IflytekOpenPlatformConfig());
        }
        PlatformAccountConfigDto.IflytekOpenPlatformConfig current = merged.getIflytekOpenPlatform();
        PlatformAccountConfigDto.IflytekOpenPlatformConfig old =
                previous == null ? null : previous.getIflytekOpenPlatform();
        if (old == null) {
            return;
        }
        current.setPlatformApiKey(keepOldIfMasked(current.getPlatformApiKey(), old.getPlatformApiKey()));
        current.setPlatformApiSecret(keepOldIfMasked(current.getPlatformApiSecret(), old.getPlatformApiSecret()));
        current.setSparkApiPassword(keepOldIfMasked(current.getSparkApiPassword(), old.getSparkApiPassword()));
        current.setSparkRtasrApiKey(keepOldIfMasked(current.getSparkRtasrApiKey(), old.getSparkRtasrApiKey()));
    }

    private void mergeAiAbilityChatSecrets(PlatformAccountConfigDto merged, PlatformAccountConfigDto previous) {
        if (merged.getAiAbilityChat() == null) {
            merged.setAiAbilityChat(new PlatformAccountConfigDto.AiAbilityChatConfig());
        }
        PlatformAccountConfigDto.AiAbilityChatConfig current = merged.getAiAbilityChat();
        PlatformAccountConfigDto.AiAbilityChatConfig old = previous == null ? null : previous.getAiAbilityChat();
        if (old != null) {
            current.setApiKey(keepOldIfMasked(current.getApiKey(), old.getApiKey()));
        }
    }

    private void mergeVirtualManSecrets(PlatformAccountConfigDto merged, PlatformAccountConfigDto previous) {
        if (merged.getVirtualMan() == null) {
            merged.setVirtualMan(new PlatformAccountConfigDto.VirtualManConfig());
        }
        PlatformAccountConfigDto.VirtualManConfig current = merged.getVirtualMan();
        PlatformAccountConfigDto.VirtualManConfig old = previous == null ? null : previous.getVirtualMan();
        if (old == null) {
            return;
        }
        current.setSparkVirtualManApiKey(
                keepOldIfMasked(current.getSparkVirtualManApiKey(), old.getSparkVirtualManApiKey()));
        current.setSparkVirtualManApiSecret(
                keepOldIfMasked(current.getSparkVirtualManApiSecret(), old.getSparkVirtualManApiSecret()));
    }

    private void mergeKnowledgePlatformSecrets(PlatformAccountConfigDto merged, PlatformAccountConfigDto previous) {
        if (merged.getKnowledgePlatform() == null) {
            merged.setKnowledgePlatform(new PlatformAccountConfigDto.KnowledgePlatformConfig());
        }
        PlatformAccountConfigDto.KnowledgePlatformConfig current = merged.getKnowledgePlatform();
        PlatformAccountConfigDto.KnowledgePlatformConfig old = previous == null ? null : previous.getKnowledgePlatform();
        if (current.getRagflow() == null) {
            current.setRagflow(new PlatformAccountConfigDto.RagflowConfig());
        }
        if (current.getXinghuo() == null) {
            current.setXinghuo(new PlatformAccountConfigDto.XinghuoKnowledgeConfig());
        }
        if (old != null && old.getRagflow() != null) {
            current.getRagflow().setApiToken(keepOldIfMasked(current.getRagflow().getApiToken(), old.getRagflow().getApiToken()));
        }
    }

    private PlatformAccountConfigDto mask(PlatformAccountType type, PlatformAccountConfigDto config) {
        PlatformAccountConfigDto masked = JSON.parseObject(JSON.toJSONString(config), PlatformAccountConfigDto.class);
        switch (type) {
            case IFLYTEK_OPEN_PLATFORM -> {
                PlatformAccountConfigDto.IflytekOpenPlatformConfig item = masked.getIflytekOpenPlatform();
                if (item != null) {
                    item.setPlatformApiKey(maskValue(item.getPlatformApiKey()));
                    item.setPlatformApiSecret(maskValue(item.getPlatformApiSecret()));
                    item.setSparkApiPassword(maskValue(item.getSparkApiPassword()));
                    item.setSparkRtasrApiKey(maskValue(item.getSparkRtasrApiKey()));
                }
            }
            case AI_ABILITY_CHAT -> {
                PlatformAccountConfigDto.AiAbilityChatConfig item = masked.getAiAbilityChat();
                if (item != null) {
                    item.setApiKey(maskValue(item.getApiKey()));
                }
            }
            case VIRTUAL_MAN -> {
                PlatformAccountConfigDto.VirtualManConfig item = masked.getVirtualMan();
                if (item != null) {
                    item.setSparkVirtualManApiKey(maskValue(item.getSparkVirtualManApiKey()));
                    item.setSparkVirtualManApiSecret(maskValue(item.getSparkVirtualManApiSecret()));
                }
            }
            case KNOWLEDGE_PLATFORM -> {
                PlatformAccountConfigDto.KnowledgePlatformConfig item = masked.getKnowledgePlatform();
                if (item != null && item.getRagflow() != null) {
                    item.getRagflow().setApiToken(maskValue(item.getRagflow().getApiToken()));
                }
            }
            case VOLCENGINE -> {
                PlatformAccountConfigDto.VolcengineConfig item = masked.getVolcengine();
                if (item != null) {
                    item.setArkApiKey(maskValue(item.getArkApiKey()));
                    item.setSpeechApiKey(maskValue(item.getSpeechApiKey()));
                    item.setSpeechAccessToken(maskValue(item.getSpeechAccessToken()));
                    item.setKnowledgeApiKey(maskValue(item.getKnowledgeApiKey()));
                    item.setSearchApiKey(maskValue(item.getSearchApiKey()));
                }
            }
            case AI_CAPABILITY_ROUTING -> {
                // No secrets in routing config.
            }
        }
        return masked;
    }

    private String cacheKey(PlatformAccountType type) {
        return RedisKeyConstant.PLATFORM_ACCOUNT_CONFIG_PREFIX + type.getValue();
    }

    private String textCacheKey(PlatformAccountType type) {
        return RedisKeyConstant.PLATFORM_ACCOUNT_CONFIG_TEXT_PREFIX + type.getValue();
    }

    private void cacheConfigValue(PlatformAccountType type, String value) {
        redisUtil.put(cacheKey(type), value);
        redisUtil.putString(textCacheKey(type), value);
    }

    private void throwNotConfigured(String name) {
        throw new BusinessException(ResponseEnum.PLATFORM_ACCOUNT_NOT_CONFIGURED, name);
    }

    private boolean hasText(String value) {
        return StringUtils.isNotBlank(value)
                && !StringUtils.startsWithIgnoreCase(value, "your-")
                && !"xxx".equalsIgnoreCase(value.trim());
    }

    private String keepOldIfMasked(String current, String old) {
        return isMasked(current) ? old : current;
    }

    private boolean isMasked(String value) {
        return StringUtils.isNotBlank(value) && value.startsWith(MASK);
    }

    private String maskValue(String value) {
        if (StringUtils.isBlank(value)) {
            return value;
        }
        String tail = value.length() <= 4 ? "" : value.substring(value.length() - 4);
        return MASK + tail;
    }
}
