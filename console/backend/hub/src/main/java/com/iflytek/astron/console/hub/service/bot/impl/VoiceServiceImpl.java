package com.iflytek.astron.console.hub.service.bot.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.iflytek.astron.console.commons.util.I18nUtil;
import com.iflytek.astron.console.hub.entity.PronunciationPersonConfig;
import com.iflytek.astron.console.hub.enums.TtsTypeEnum;
import com.iflytek.astron.console.hub.mapper.PronunciationPersonConfigMapper;
import com.iflytek.astron.console.hub.service.bot.VoiceService;
import com.iflytek.astron.console.toolkit.entity.platform.PlatformAccountConfigDto;
import com.iflytek.astron.console.toolkit.service.platform.PlatformAccountService;
import com.iflytek.astron.console.toolkit.tool.http.HttpAuthTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author bowang
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VoiceServiceImpl implements VoiceService {

    private static final String TTS_API_URL = "wss://cbm01.cn-huabei-1.xf-yun.com/v1/private/mcd9m97e6";

    private final PronunciationPersonConfigMapper pronunciationPersonConfigMapper;
    private final PlatformAccountService platformAccountService;

    @Override
    public Map<String, String> getTtsSign() {
        PlatformAccountConfigDto.IflytekOpenPlatformConfig config =
                platformAccountService.requireIflytekOpenPlatform();
        Map<String, String> resultMap = new HashMap<>();
        String url = HttpAuthTool.assembleRequestUrl(
                TTS_API_URL, config.getPlatformApiKey(), config.getPlatformApiSecret());
        resultMap.put("appId", config.getPlatformAppId());
        resultMap.put("url", url);
        resultMap.put("type", TtsTypeEnum.ORIGINAL.name());
        return resultMap;
    }

    @Override
    @Cacheable(value = "pronunciationPersonCache", key = "#root.methodName", cacheManager = "cacheManager5min")
    public List<PronunciationPersonConfig> getPronunciationPerson() {
        LambdaQueryWrapper<PronunciationPersonConfig> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(PronunciationPersonConfig::getSpeakerType, PronunciationPersonConfig.SpeakerTypeEnum.NORMAL);
        queryWrapper.eq(PronunciationPersonConfig::getDeleted, 0);
        queryWrapper.orderByAsc(PronunciationPersonConfig::getSort);
        List<PronunciationPersonConfig> configList = pronunciationPersonConfigMapper.selectList(queryWrapper);
        // Convert name field from key to internationalized value
        for (PronunciationPersonConfig config : configList) {
            if (config.getName() != null) {
                config.setName(I18nUtil.getMessage(config.getName()));
            }
        }
        return configList;
    }

    @Override
    public List<PronunciationPersonConfig> getVolcenginePronunciationPerson(String ttsResourceId,
            String defaultSpeaker) {
        String[][] voices = StringUtils.startsWith(ttsResourceId, "seed-tts-2") ? VOLC_TTS2_VOICES : VOLC_TTS1_VOICES;
        List<PronunciationPersonConfig> list = new ArrayList<>(voices.length + 1);
        boolean defaultListed = false;
        for (String[] voice : voices) {
            defaultListed |= voice[1].equals(defaultSpeaker);
        }
        if (StringUtils.isNotBlank(defaultSpeaker) && !defaultListed) {
            list.add(volcVoice(list.size(), "默认发音人", defaultSpeaker));
        }
        for (String[] voice : voices) {
            list.add(volcVoice(list.size(), voice[0], voice[1]));
        }
        return list;
    }

    private static PronunciationPersonConfig volcVoice(int index, String name, String voiceType) {
        PronunciationPersonConfig item = new PronunciationPersonConfig();
        item.setId((long) -(index + 1));
        item.setName(name);
        item.setVoiceType(voiceType);
        item.setCoverUrl(VOLC_VOICE_COVER);
        item.setSort(index);
        item.setSpeakerType(PronunciationPersonConfig.SpeakerTypeEnum.NORMAL);
        item.setExquisite(0);
        item.setDeleted(0);
        return item;
    }

    private static final String VOLC_VOICE_COVER = "/skylumin-avatar.svg";

    /**
     * Doubao Speech 1.0 voices (X-Api-Resource-Id seed-tts-1.0). Other 1.0 voices can be set as
     * default.
     */
    private static final String[][] VOLC_TTS1_VOICES = {
            {"爽快思思", "zh_female_shuangkuaisisi_moon_bigtts"},
    };

    /** Doubao Speech 2.0 voices (X-Api-Resource-Id seed-tts-2.0), from the official voice list. */
    private static final String[][] VOLC_TTS2_VOICES = {
            {"Vivi 2.0", "zh_female_vv_uranus_bigtts"},
            {"小何 2.0", "zh_female_xiaohe_uranus_bigtts"},
            {"云舟 2.0", "zh_male_m191_uranus_bigtts"},
            {"小天 2.0", "zh_male_taocheng_uranus_bigtts"},
            {"爽快思思 2.0", "zh_female_shuangkuaisisi_uranus_bigtts"},
            {"温暖阿虎 2.0", "zh_male_wennuanahu_uranus_bigtts"},
            {"知性灿灿 2.0", "zh_female_cancan_uranus_bigtts"},
            {"儒雅逸辰 2.0", "zh_male_ruyayichen_uranus_bigtts"},
    };
}
