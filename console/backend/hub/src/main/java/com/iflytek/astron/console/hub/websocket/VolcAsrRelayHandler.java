package com.iflytek.astron.console.hub.websocket;

import com.alibaba.fastjson2.JSONObject;
import com.iflytek.astron.console.hub.service.volcengine.VolcAsrProtocol;
import com.iflytek.astron.console.hub.service.volcengine.VolcengineSpeech;
import com.iflytek.astron.console.toolkit.entity.platform.PlatformAccountConfigDto;
import com.iflytek.astron.console.toolkit.service.platform.PlatformAccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Relays browser microphone audio to Doubao streaming ASR. Browsers cannot set the {@code X-Api-*}
 * auth headers on a WebSocket handshake, so console-hub holds the upstream connection.
 *
 * <p>
 * Browser to relay: binary 16 kHz 16-bit mono PCM frames (1280 bytes from {@code record.js}) and a
 * final binary {@code {"end":true}}. Relay to browser: text
 * {@code {"action":"result","provider":"volcengine", "text":...}} with the full transcript so far,
 * or {@code {"action":"error",...}}.
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VolcAsrRelayHandler extends AbstractWebSocketHandler {

    static final String ASR_URL = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async";
    /** 200 ms of 16 kHz 16-bit mono PCM, the packet size recommended by the ASR docs. */
    static final int PACKET_BYTES = 6400;
    static final String ATTR_UID = "volcAsrUid";
    private static final String ATTR_STATE = "volcAsrState";
    private static final int SEND_TIME_LIMIT_MS = 5000;
    private static final int SEND_BUFFER_LIMIT = 512 * 1024;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build();

    private final PlatformAccountService platformAccountService;

    @Override
    public void afterConnectionEstablished(@NotNull WebSocketSession session) throws Exception {
        String uid = String.valueOf(session.getAttributes().get(ATTR_UID));
        WebSocketSession browser =
                new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT);
        PlatformAccountConfigDto.VolcengineConfig config;
        try {
            config = platformAccountService.requireVolcengineSpeech();
        } catch (Exception e) {
            log.warn("Volcengine ASR relay rejected, speech credentials not configured, uid={}", uid);
            sendJson(browser, error(-1, "Volcengine speech is not configured"));
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        RelayState state = new RelayState(browser, uid);
        session.getAttributes().put(ATTR_STATE, state);

        Request.Builder builder = new Request.Builder().url(ASR_URL);
        Map<String, String> headers = VolcengineSpeech.authHeaders(config, config.getAsrResourceId(), true);
        headers.forEach(builder::header);
        state.connectId = headers.get("X-Api-Connect-Id");
        log.info("Volcengine ASR relay opened, uid={}, sessionId={}, resource={}, connectId={}",
                uid, session.getId(), config.getAsrResourceId(), state.connectId);

        state.upstream = httpClient.newWebSocket(builder.build(), new UpstreamListener(state));
        // OkHttp queues frames until the upstream handshake completes, so ordering is preserved.
        state.upstream.send(ByteString.of(VolcAsrProtocol.fullClientRequest(uid)));
    }

    @Override
    protected void handleBinaryMessage(@NotNull WebSocketSession session, @NotNull BinaryMessage message) {
        RelayState state = (RelayState) session.getAttributes().get(ATTR_STATE);
        if (state == null || state.ended) {
            return;
        }
        ByteBuffer payload = message.getPayload();
        byte[] bytes = new byte[payload.remaining()];
        payload.get(bytes);
        if (isEndMessage(bytes)) {
            state.finish();
            return;
        }
        state.append(bytes);
    }

    @Override
    protected void handleTextMessage(@NotNull WebSocketSession session, @NotNull TextMessage message) {
        RelayState state = (RelayState) session.getAttributes().get(ATTR_STATE);
        if (state != null && isEndMessage(message.asBytes())) {
            state.finish();
        }
    }

    @Override
    public void afterConnectionClosed(@NotNull WebSocketSession session, @NotNull CloseStatus status) {
        RelayState state = (RelayState) session.getAttributes().remove(ATTR_STATE);
        if (state == null) {
            return;
        }
        state.finish();
        if (state.upstream != null) {
            state.upstream.close(1000, "client closed");
        }
        log.info("Volcengine ASR relay closed, uid={}, sessionId={}, status={}, audioBytes={}, connectId={}",
                state.uid, session.getId(), status.getCode(), state.audioBytes, state.connectId);
    }

    @Override
    public void handleTransportError(@NotNull WebSocketSession session, @NotNull Throwable exception) {
        log.warn("Volcengine ASR relay browser transport error, sessionId={}, errorType={}",
                session.getId(), exception.getClass().getSimpleName());
    }

    static boolean isEndMessage(byte[] bytes) {
        if (bytes.length == 0 || bytes.length > 64 || bytes[0] != '{') {
            return false;
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        return text.contains("\"end\"") && text.contains("true");
    }

    private static JSONObject error(int code, String desc) {
        JSONObject json = new JSONObject();
        json.put("action", "error");
        json.put("provider", "volcengine");
        json.put("code", code);
        json.put("desc", desc);
        return json;
    }

    private static void sendJson(WebSocketSession browser, JSONObject json) {
        if (!browser.isOpen()) {
            return;
        }
        try {
            browser.sendMessage(new TextMessage(json.toJSONString()));
        } catch (IOException | IllegalStateException e) {
            log.debug("Failed to send ASR message to browser: {}", e.getMessage());
        }
    }

    private static void closeQuietly(WebSocketSession browser, CloseStatus status) {
        try {
            if (browser.isOpen()) {
                browser.close(status);
            }
        } catch (IOException e) {
            log.debug("Failed to close browser ASR session: {}", e.getMessage());
        }
    }

    static final class RelayState {
        final WebSocketSession browser;
        final String uid;
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        volatile WebSocket upstream;
        volatile boolean ended;
        volatile String connectId;
        String lastText;
        long audioBytes;

        RelayState(WebSocketSession browser, String uid) {
            this.browser = browser;
            this.uid = uid;
        }

        synchronized void append(byte[] pcm) {
            if (ended) {
                return;
            }
            buffer.writeBytes(pcm);
            audioBytes += pcm.length;
            if (buffer.size() >= PACKET_BYTES) {
                upstream.send(ByteString.of(VolcAsrProtocol.audioRequest(buffer.toByteArray(), false)));
                buffer.reset();
            }
        }

        synchronized void finish() {
            if (ended) {
                return;
            }
            ended = true;
            if (upstream != null) {
                upstream.send(ByteString.of(VolcAsrProtocol.audioRequest(buffer.toByteArray(), true)));
            }
            buffer.reset();
        }
    }

    private static final class UpstreamListener extends WebSocketListener {
        private final RelayState state;

        UpstreamListener(RelayState state) {
            this.state = state;
        }

        @Override
        public void onOpen(@NotNull WebSocket webSocket, @NotNull Response response) {
            log.info("Volcengine ASR upstream connected, uid={}, logId={}",
                    state.uid, response.header("X-Tt-Logid"));
        }

        @Override
        public void onMessage(@NotNull WebSocket webSocket, @NotNull ByteString bytes) {
            VolcAsrProtocol.ServerMessage message;
            try {
                message = VolcAsrProtocol.parse(bytes.toByteArray());
            } catch (RuntimeException e) {
                log.warn("Volcengine ASR frame parse failed, uid={}, errorType={}",
                        state.uid, e.getClass().getSimpleName());
                return;
            }
            if (message.isError()) {
                log.warn("Volcengine ASR error, uid={}, code={}, message={}, connectId={}",
                        state.uid, message.errorCode(), message.errorMessage(), state.connectId);
                sendJson(state.browser, error(message.errorCode(), message.errorMessage()));
                webSocket.close(1000, "error");
                closeQuietly(state.browser, CloseStatus.SERVER_ERROR);
                return;
            }
            String text = VolcAsrProtocol.resultText(message.payload());
            if (text != null && !text.equals(state.lastText)) {
                state.lastText = text;
                JSONObject json = new JSONObject();
                json.put("action", "result");
                json.put("provider", "volcengine");
                json.put("text", text);
                json.put("final", message.last());
                sendJson(state.browser, json);
            }
            if (message.last()) {
                log.info("Volcengine ASR final result, uid={}, textLength={}",
                        state.uid, text == null ? 0 : text.length());
                webSocket.close(1000, "done");
                closeQuietly(state.browser, CloseStatus.NORMAL);
            }
        }

        @Override
        public void onFailure(@NotNull WebSocket webSocket, @NotNull Throwable t, Response response) {
            int status = response == null ? -1 : response.code();
            String logId = response == null ? null : response.header("X-Tt-Logid");
            log.warn("Volcengine ASR upstream failure, uid={}, httpStatus={}, logId={}, errorType={}, message={}",
                    state.uid, status, logId, t.getClass().getSimpleName(), t.getMessage());
            sendJson(state.browser, error(status, "Volcengine ASR connection failed"));
            closeQuietly(state.browser, CloseStatus.SERVER_ERROR);
        }

        @Override
        public void onClosed(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
            closeQuietly(state.browser, CloseStatus.NORMAL);
        }
    }
}
