package com.iflytek.astron.console.hub.service.volcengine;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Binary framing of the Doubao streaming ASR WebSocket API ({@code /api/v3/sauc/bigmodel*}).
 *
 * <pre>
 * byte 0: protocol version (4 bits) | header size in 4-byte units (4 bits)  -> 0x11
 * byte 1: message type (4 bits) | message type specific flags (4 bits)
 * byte 2: serialization (4 bits) | compression (4 bits)
 * byte 3: reserved
 * [optional int32 sequence when flags bit0 is set]
 * uint32 payload size, payload            (error frames: uint32 code, uint32 size, message)
 * </pre>
 *
 * All integers are big-endian.
 */
public final class VolcAsrProtocol {

    static final int MSG_FULL_CLIENT_REQUEST = 0b0001;
    static final int MSG_AUDIO_ONLY_REQUEST = 0b0010;
    static final int MSG_FULL_SERVER_RESPONSE = 0b1001;
    static final int MSG_ERROR = 0b1111;

    static final int FLAG_NONE = 0b0000;
    static final int FLAG_HAS_SEQUENCE = 0b0001;
    static final int FLAG_LAST_PACKET = 0b0010;

    static final int SERIALIZATION_NONE = 0b0000;
    static final int SERIALIZATION_JSON = 0b0001;
    static final int COMPRESSION_GZIP = 0b0001;

    private VolcAsrProtocol() {}

    /** Full client request carrying audio format and recognition options. */
    public static byte[] fullClientRequest(String uid) {
        JSONObject audio = new JSONObject();
        audio.put("format", "pcm");
        audio.put("codec", "raw");
        audio.put("rate", 16000);
        audio.put("bits", 16);
        audio.put("channel", 1);
        JSONObject request = new JSONObject();
        request.put("model_name", "bigmodel");
        request.put("enable_punc", true);
        request.put("enable_itn", true);
        request.put("result_type", "full");
        request.put("show_utterances", true);
        JSONObject user = new JSONObject();
        user.put("uid", uid);
        JSONObject payload = new JSONObject();
        payload.put("user", user);
        payload.put("audio", audio);
        payload.put("request", request);
        return frame(MSG_FULL_CLIENT_REQUEST, FLAG_NONE, SERIALIZATION_JSON,
                payload.toJSONString().getBytes(StandardCharsets.UTF_8));
    }

    /** Audio-only request with raw 16 kHz 16-bit mono PCM. */
    public static byte[] audioRequest(byte[] pcm, boolean last) {
        return frame(MSG_AUDIO_ONLY_REQUEST, last ? FLAG_LAST_PACKET : FLAG_NONE, SERIALIZATION_NONE, pcm);
    }

    static byte[] frame(int messageType, int flags, int serialization, byte[] payload) {
        byte[] compressed = gzip(payload);
        ByteBuffer buffer = ByteBuffer.allocate(8 + compressed.length);
        buffer.put((byte) 0x11);
        buffer.put((byte) ((messageType << 4) | flags));
        buffer.put((byte) ((serialization << 4) | COMPRESSION_GZIP));
        buffer.put((byte) 0x00);
        buffer.putInt(compressed.length);
        buffer.put(compressed);
        return buffer.array();
    }

    /** Parses a server frame. */
    public static ServerMessage parse(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        int headerSize = (data[0] & 0x0f) * 4;
        int messageType = (data[1] & 0xff) >> 4;
        int flags = data[1] & 0x0f;
        int serialization = (data[2] & 0xff) >> 4;
        int compression = data[2] & 0x0f;
        buffer.position(headerSize);

        if (messageType == MSG_ERROR) {
            int code = buffer.getInt();
            int size = buffer.getInt();
            byte[] message = new byte[Math.min(size, buffer.remaining())];
            buffer.get(message);
            return new ServerMessage(messageType, true, code, new String(message, StandardCharsets.UTF_8), null);
        }

        Integer sequence = null;
        if ((flags & FLAG_HAS_SEQUENCE) != 0) {
            sequence = buffer.getInt();
        }
        boolean last = (flags & FLAG_LAST_PACKET) != 0 || (sequence != null && sequence < 0);
        int size = buffer.getInt();
        byte[] payload = new byte[Math.min(size, buffer.remaining())];
        buffer.get(payload);
        if (compression == COMPRESSION_GZIP && payload.length > 0) {
            payload = gunzip(payload);
        }
        JSONObject json = serialization == SERIALIZATION_JSON && payload.length > 0
                ? JSON.parseObject(new String(payload, StandardCharsets.UTF_8))
                : null;
        return new ServerMessage(messageType, last, 0, null, json);
    }

    /** Full transcript so far ({@code result_type=full}). */
    public static String resultText(JSONObject payload) {
        if (payload == null) {
            return null;
        }
        JSONObject result = payload.getJSONObject("result");
        return result == null ? null : result.getString("text");
    }

    static byte[] gzip(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    static byte[] gunzip(byte[] data) {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public record ServerMessage(int messageType, boolean last, int errorCode, String errorMessage,
            JSONObject payload) {
        public boolean isError() {
            return messageType == MSG_ERROR;
        }
    }
}
