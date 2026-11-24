package com.iflytek.astron.console.hub.service.volcengine;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.iflytek.astron.console.hub.websocket.VolcAsrRelayHandlerTestAccess;
import com.iflytek.astron.console.toolkit.entity.platform.PlatformAccountConfigDto;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VolcengineSpeechProtocolTest {

    @Test
    void fullClientRequestHasGzipJsonHeaderAndPayload() {
        byte[] frame = VolcAsrProtocol.fullClientRequest("u1");

        assertThat(frame[0]).isEqualTo((byte) 0x11);
        assertThat(frame[1]).isEqualTo((byte) 0x10);
        assertThat(frame[2]).isEqualTo((byte) 0x11);
        int size = ByteBuffer.wrap(frame, 4, 4).getInt();
        assertThat(size).isEqualTo(frame.length - 8);
        byte[] payload = VolcAsrProtocol.gunzip(java.util.Arrays.copyOfRange(frame, 8, frame.length));
        JSONObject json = JSON.parseObject(new String(payload, StandardCharsets.UTF_8));
        assertThat(json.getJSONObject("audio").getIntValue("rate")).isEqualTo(16000);
        assertThat(json.getJSONObject("request").getString("result_type")).isEqualTo("full");
        assertThat(json.getJSONObject("user").getString("uid")).isEqualTo("u1");
    }

    @Test
    void lastAudioRequestSetsLastPacketFlag() {
        byte[] frame = VolcAsrProtocol.audioRequest(new byte[] {1, 2, 3, 4}, true);

        assertThat(frame[1]).isEqualTo((byte) 0x22);
        assertThat(frame[2]).isEqualTo((byte) 0x01);
        byte[] pcm = VolcAsrProtocol.gunzip(java.util.Arrays.copyOfRange(frame, 8, frame.length));
        assertThat(pcm).containsExactly(1, 2, 3, 4);
    }

    @Test
    void parsesServerResponseWithSequence() {
        byte[] payload = VolcAsrProtocol.gzip(
                "{\"result\":{\"text\":\"你好世界\",\"utterances\":[]}}".getBytes(StandardCharsets.UTF_8));
        ByteBuffer buffer = ByteBuffer.allocate(12 + payload.length);
        buffer.put((byte) 0x11).put((byte) 0x93).put((byte) 0x11).put((byte) 0x00);
        buffer.putInt(-5);
        buffer.putInt(payload.length);
        buffer.put(payload);

        VolcAsrProtocol.ServerMessage message = VolcAsrProtocol.parse(buffer.array());

        assertThat(message.isError()).isFalse();
        assertThat(message.last()).isTrue();
        assertThat(VolcAsrProtocol.resultText(message.payload())).isEqualTo("你好世界");
    }

    @Test
    void parsesErrorFrame() {
        byte[] msg = "invalid audio".getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(12 + msg.length);
        buffer.put((byte) 0x11).put((byte) 0xF0).put((byte) 0x10).put((byte) 0x00);
        buffer.putInt(45000001);
        buffer.putInt(msg.length);
        buffer.put(msg);

        VolcAsrProtocol.ServerMessage message = VolcAsrProtocol.parse(buffer.array());

        assertThat(message.isError()).isTrue();
        assertThat(message.errorCode()).isEqualTo(45000001);
        assertThat(message.errorMessage()).isEqualTo("invalid audio");
    }

    @Test
    void jsonSplitterHandlesConcatenatedAndChunkedObjects() {
        List<String> out = new ArrayList<>();
        VolcengineTtsService.JsonObjectSplitter splitter = new VolcengineTtsService.JsonObjectSplitter(out::add);

        splitter.accept("{\"code\":0,\"data\":\"QU");
        splitter.accept("JD\"}\n{\"code\":0,\"message\":\"a}b\"}{\"code\":20000000}");

        assertThat(out).containsExactly(
                "{\"code\":0,\"data\":\"QUJD\"}",
                "{\"code\":0,\"message\":\"a}b\"}",
                "{\"code\":20000000}");
    }

    @Test
    void speechHeadersUseApiKeyOrAppIdPair() {
        PlatformAccountConfigDto.VolcengineConfig newConsole = new PlatformAccountConfigDto.VolcengineConfig();
        newConsole.setSpeechApiKey("key");
        Map<String, String> headers = VolcengineSpeech.authHeaders(newConsole, "seed-tts-1.0", false);
        assertThat(headers).containsEntry("X-Api-Key", "key")
                .containsKey("X-Api-Request-Id")
                .doesNotContainKey("X-Api-App-Id");

        PlatformAccountConfigDto.VolcengineConfig oldConsole = new PlatformAccountConfigDto.VolcengineConfig();
        oldConsole.setSpeechAppId("app");
        oldConsole.setSpeechAccessToken("token");
        headers = VolcengineSpeech.authHeaders(oldConsole, "volc.bigasr.sauc.duration", true);
        assertThat(headers).containsEntry("X-Api-App-Key", "app")
                .containsEntry("X-Api-Access-Key", "token")
                .containsEntry("X-Api-Resource-Id", "volc.bigasr.sauc.duration")
                .containsKey("X-Api-Connect-Id");
    }

    @Test
    void resolvesSpeakerAndSpeed() {
        assertThat(VolcengineSpeech.resolveSpeaker("x4_lingxiaoqi_oral", "zh_default_bigtts"))
                .isEqualTo("zh_default_bigtts");
        assertThat(VolcengineSpeech.resolveSpeaker("zh_female_vv_uranus_bigtts", "zh_default_bigtts"))
                .isEqualTo("zh_female_vv_uranus_bigtts");
        assertThat(VolcengineSpeech.toSpeechRate(50)).isZero();
        assertThat(VolcengineSpeech.toSpeechRate(100)).isEqualTo(100);
        assertThat(VolcengineSpeech.toSpeechRate(0)).isEqualTo(-50);
    }

    @Test
    void detectsBrowserEndMessage() {
        assertThat(VolcAsrRelayHandlerTestAccess.isEndMessage("{\"end\":true}".getBytes(StandardCharsets.UTF_8)))
                .isTrue();
        assertThat(VolcAsrRelayHandlerTestAccess.isEndMessage(new byte[1280])).isFalse();
    }
}
