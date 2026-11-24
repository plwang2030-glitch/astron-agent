package com.iflytek.astron.console.hub.service.volcengine;

import com.iflytek.astron.console.toolkit.entity.platform.PlatformAccountConfigDto;
import org.apache.commons.lang3.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Shared helpers for Doubao Speech (豆包语音) V3 APIs. */
public final class VolcengineSpeech {

    private static final String[] SPEAKER_PREFIXES = {"zh_", "en_", "ja_", "es_", "multi_", "ICL_", "S_", "saturn_"};

    private VolcengineSpeech() {}

    /**
     * Auth headers. The new console uses a single {@code X-Api-Key}; the old console uses AppID +
     * Access Token, where TTS names the AppID header {@code X-Api-App-Id} and ASR names it
     * {@code X-Api-App-Key}.
     */
    public static Map<String, String> authHeaders(
            PlatformAccountConfigDto.VolcengineConfig config, String resourceId, boolean asr) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Api-Resource-Id", resourceId);
        if (StringUtils.isNotBlank(config.getSpeechApiKey())) {
            headers.put("X-Api-Key", config.getSpeechApiKey());
        } else {
            headers.put(asr ? "X-Api-App-Key" : "X-Api-App-Id", config.getSpeechAppId());
            headers.put("X-Api-Access-Key", config.getSpeechAccessToken());
        }
        headers.put(asr ? "X-Api-Connect-Id" : "X-Api-Request-Id", UUID.randomUUID().toString());
        return headers;
    }

    /**
     * Uses the requested voice when it is a Doubao Speech voice; iFLYTEK voice codes map to the
     * default.
     */
    public static String resolveSpeaker(String requested, String defaultSpeaker) {
        if (StringUtils.isNotBlank(requested)) {
            if (requested.contains("bigtts") || StringUtils.startsWithAny(requested, SPEAKER_PREFIXES)) {
                return requested;
            }
        }
        return defaultSpeaker;
    }

    /** iFLYTEK speed 0..100 (50 = normal) to Doubao {@code speech_rate} -50..100 (0 = normal). */
    public static int toSpeechRate(Integer speed) {
        if (speed == null) {
            return 0;
        }
        return Math.max(-50, Math.min(100, (speed - 50) * 2));
    }
}
