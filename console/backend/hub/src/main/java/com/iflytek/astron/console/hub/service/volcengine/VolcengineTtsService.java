package com.iflytek.astron.console.hub.service.volcengine;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.iflytek.astron.console.toolkit.entity.platform.PlatformAccountConfigDto;
import com.iflytek.astron.console.toolkit.service.platform.PlatformAccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Doubao Speech TTS through the V3 HTTP chunked API. The upstream stream of JSON objects
 * ({@code {"code":0,"data":"<base64 pcm>"}} ... {@code {"code":20000000}}) is re-emitted as NDJSON
 * so the browser can play 16 kHz PCM as it arrives, the same format the iFLYTEK TTS WebSocket
 * produces.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VolcengineTtsService {

    static final String TTS_URL = "https://openspeech.bytedance.com/api/v3/tts/unidirectional";
    static final int SUCCESS_END_CODE = 20000000;
    public static final int MAX_TEXT_LENGTH = 5000;
    private static final int SAMPLE_RATE = 16000;
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    private final PlatformAccountService platformAccountService;

    /**
     * Validates that speech credentials exist; throws the standard "not configured" error otherwise.
     */
    public PlatformAccountConfigDto.VolcengineConfig requireConfig() {
        return platformAccountService.requireVolcengineSpeech();
    }

    public void stream(PlatformAccountConfigDto.VolcengineConfig config, String uid, String text, String speaker,
            Integer speed, OutputStream out) throws IOException {
        String voice = VolcengineSpeech.resolveSpeaker(speaker, config.getTtsSpeaker());
        JSONObject audioParams = new JSONObject();
        audioParams.put("format", "pcm");
        audioParams.put("sample_rate", SAMPLE_RATE);
        audioParams.put("speech_rate", VolcengineSpeech.toSpeechRate(speed));
        JSONObject reqParams = new JSONObject();
        reqParams.put("text", text);
        reqParams.put("speaker", voice);
        reqParams.put("audio_params", audioParams);
        JSONObject body = new JSONObject();
        body.put("user", Map.of("uid", String.valueOf(uid)));
        body.put("req_params", reqParams);

        Request.Builder builder = new Request.Builder().url(TTS_URL)
                .post(RequestBody.create(body.toJSONString(), JSON_MEDIA_TYPE));
        VolcengineSpeech.authHeaders(config, config.getTtsResourceId(), false).forEach(builder::header);

        long start = System.currentTimeMillis();
        log.info("Volcengine TTS started, uid={}, resource={}, speaker={}, requestedVoice={}, textLength={}",
                uid, config.getTtsResourceId(), voice, speaker, text.length());
        try (Response response = httpClient.newCall(builder.build()).execute()) {
            String logId = response.header("X-Tt-Logid");
            ResponseBody responseBody = response.body();
            if (!response.isSuccessful() || responseBody == null) {
                String detail = responseBody == null ? "" : responseBody.string();
                log.warn("Volcengine TTS HTTP error, uid={}, status={}, logId={}, body={}",
                        uid, response.code(), logId, abbreviate(detail));
                writeLine(out, errorLine(response.code(), "TTS request failed"));
                return;
            }
            int[] stats = new int[2]; // [chunks, finalCode]
            BufferedSource source = responseBody.source();
            JsonObjectSplitter splitter = new JsonObjectSplitter(json -> {
                JSONObject obj = JSON.parseObject(json);
                int code = obj.getIntValue("code");
                if (code != 0) {
                    stats[1] = code;
                }
                if (code != 0 && code != SUCCESS_END_CODE) {
                    log.warn("Volcengine TTS error, uid={}, code={}, message={}, logId={}",
                            uid, code, obj.getString("message"), logId);
                }
                if (obj.containsKey("data") || code != 0) {
                    stats[0]++;
                    writeLine(out, json);
                }
            });
            byte[] buffer = new byte[8192];
            int read;
            while ((read = source.read(buffer)) != -1) {
                splitter.accept(new String(buffer, 0, read, StandardCharsets.UTF_8));
            }
            log.info("Volcengine TTS finished, uid={}, chunks={}, finalCode={}, costMs={}, logId={}",
                    uid, stats[0], stats[1], System.currentTimeMillis() - start, logId);
        } catch (IOException e) {
            log.warn("Volcengine TTS stream aborted, uid={}, errorType={}, message={}",
                    uid, e.getClass().getSimpleName(), e.getMessage());
            throw e;
        }
    }

    private static String errorLine(int code, String message) {
        JSONObject err = new JSONObject();
        err.put("code", code);
        err.put("message", message);
        return err.toJSONString();
    }

    private static void writeLine(OutputStream out, String json) {
        try {
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.write('\n');
            out.flush();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static String abbreviate(String value) {
        return value == null || value.length() <= 300 ? value : value.substring(0, 300) + "...";
    }

    /**
     * Splits a stream of concatenated or newline-separated JSON objects into complete objects.
     * Characters may arrive in arbitrary chunks; braces inside strings are ignored.
     */
    static final class JsonObjectSplitter {
        private final Consumer<String> sink;
        private final StringBuilder current = new StringBuilder();
        private int depth;
        private boolean inString;
        private boolean escaped;

        JsonObjectSplitter(Consumer<String> sink) {
            this.sink = sink;
        }

        void accept(String chunk) {
            for (int i = 0; i < chunk.length(); i++) {
                char c = chunk.charAt(i);
                if (depth == 0 && c != '{') {
                    continue;
                }
                current.append(c);
                if (inString) {
                    if (escaped) {
                        escaped = false;
                    } else if (c == '\\') {
                        escaped = true;
                    } else if (c == '"') {
                        inString = false;
                    }
                    continue;
                }
                if (c == '"') {
                    inString = true;
                } else if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        sink.accept(current.toString());
                        current.setLength(0);
                    }
                }
            }
        }
    }
}
