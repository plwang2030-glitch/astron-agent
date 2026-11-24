package com.iflytek.astron.console.hub.controller.bot;

import com.iflytek.astron.console.commons.annotation.RateLimit;
import com.iflytek.astron.console.commons.constant.ResponseEnum;
import com.iflytek.astron.console.commons.exception.BusinessException;
import com.iflytek.astron.console.commons.response.ApiResult;
import com.iflytek.astron.console.commons.util.RequestContextUtil;
import com.iflytek.astron.console.hub.entity.PronunciationPersonConfig;
import com.iflytek.astron.console.hub.enums.TtsTypeEnum;
import com.iflytek.astron.console.hub.service.bot.CustomSpeakerService;
import com.iflytek.astron.console.hub.service.bot.VoiceService;
import com.iflytek.astron.console.hub.service.volcengine.VolcengineTtsService;
import com.iflytek.astron.console.toolkit.entity.platform.AiCapability;
import com.iflytek.astron.console.toolkit.entity.platform.PlatformAccountConfigDto;
import com.iflytek.astron.console.toolkit.entity.platform.VolcengineDefaults;
import com.iflytek.astron.console.toolkit.service.platform.PlatformAccountService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author bowang
 */
@Slf4j
@RestController
@RequestMapping("/voice")
@RequiredArgsConstructor
public class VoiceApiController {

    static final String VOLC_TTS_PATH = "/voice/volc-tts";
    private static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson");

    private final VoiceService voiceService;
    private final CustomSpeakerService customSpeakerService;
    private final PlatformAccountService platformAccountService;
    private final VolcengineTtsService volcengineTtsService;

    @GetMapping(value = "/tts-sign")
    @RateLimit()
    public ApiResult<Map<String, String>> ttsSign(String code) {
        if (customSpeakerService.existsByAssetId(code)) {
            // Cloned voices are trained on iFLYTEK and stay on iFLYTEK.
            return ApiResult.success(customSpeakerService.getCloneSign());
        }
        if (platformAccountService.routesToVolcengine(AiCapability.TTS)) {
            volcengineTtsService.requireConfig();
            Map<String, String> resultMap = new HashMap<>();
            resultMap.put("appId", "");
            resultMap.put("url", VOLC_TTS_PATH);
            resultMap.put("type", TtsTypeEnum.VOLCENGINE.name());
            return ApiResult.success(resultMap);
        }
        return ApiResult.success(voiceService.getTtsSign());
    }

    /**
     * Streams Doubao Speech TTS as NDJSON lines of {@code {"code":0,"data":"<base64 16kHz pcm>"}},
     * ending with {@code {"code":20000000}}; any other code is an error.
     */
    @PostMapping(value = "/volc-tts", produces = "application/x-ndjson")
    @RateLimit()
    public ResponseEntity<StreamingResponseBody> volcTts(@RequestBody VolcTtsRequest request) {
        if (request == null || StringUtils.isBlank(request.getText())) {
            throw new BusinessException(ResponseEnum.PARAMS_ERROR);
        }
        String text = request.getText();
        if (text.length() > VolcengineTtsService.MAX_TEXT_LENGTH) {
            log.info("Volcengine TTS text truncated, length={}", text.length());
            text = text.substring(0, VolcengineTtsService.MAX_TEXT_LENGTH);
        }
        PlatformAccountConfigDto.VolcengineConfig config = volcengineTtsService.requireConfig();
        String uid = RequestContextUtil.getUID();
        String finalText = text;
        StreamingResponseBody body = out -> volcengineTtsService.stream(
                config, uid, finalText, request.getSpeaker(), request.getSpeed(), out);
        return ResponseEntity.ok()
                .contentType(NDJSON)
                .header("Cache-Control", "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(body);
    }

    @GetMapping(value = "/get-pronunciation-person")
    public ApiResult<List<PronunciationPersonConfig>> getPronunciationPerson() {
        if (platformAccountService.routesToVolcengine(AiCapability.TTS)) {
            String resourceId = VolcengineDefaults.TTS_RESOURCE_ID;
            String speaker = VolcengineDefaults.TTS_SPEAKER;
            try {
                PlatformAccountConfigDto.VolcengineConfig config = volcengineTtsService.requireConfig();
                resourceId = config.getTtsResourceId();
                speaker = config.getTtsSpeaker();
            } catch (BusinessException e) {
                // Listing voices should not fail before speech credentials are filled in.
                log.info("Volcengine speech not configured yet, listing default voices");
            }
            return ApiResult.success(voiceService.getVolcenginePronunciationPerson(resourceId, speaker));
        }
        return ApiResult.success(voiceService.getPronunciationPerson());
    }

    @Data
    public static class VolcTtsRequest {
        private String text;
        private String speaker;
        private Integer speed;
    }
}
