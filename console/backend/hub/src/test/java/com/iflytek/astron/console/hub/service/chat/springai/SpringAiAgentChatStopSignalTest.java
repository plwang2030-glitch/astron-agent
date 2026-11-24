package com.iflytek.astron.console.hub.service.chat.springai;

import com.iflytek.astron.console.commons.util.SseEmitterUtil;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class SpringAiAgentChatStopSignalTest {

    @Test
    void stopCancelsUpstreamEvenWhenNoChunkArrives() {
        String streamId = "stop-" + UUID.randomUUID();
        AtomicBoolean upstreamCancelled = new AtomicBoolean(false);
        // A reasoning model that is silent for a long time: no chunk would trigger a per-chunk check.
        Flux<String> silentModel = Flux.<String>never().doOnCancel(() -> upstreamCancelled.set(true));

        SseEmitterUtil.stopStream(streamId);
        long start = System.currentTimeMillis();
        List<String> received = silentModel
                .takeUntilOther(SpringAiAgentChatService.stopSignal(streamId))
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(received).isEmpty();
        assertThat(upstreamCancelled).isTrue();
        assertThat(System.currentTimeMillis() - start).isLessThan(2000);
    }

    @Test
    void streamCompletesNormallyWithoutStopSignal() {
        String streamId = "normal-" + UUID.randomUUID();

        List<String> received = Flux.just("a", "b", "c")
                .takeUntilOther(SpringAiAgentChatService.stopSignal(streamId))
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(received).containsExactly("a", "b", "c");
    }
}
