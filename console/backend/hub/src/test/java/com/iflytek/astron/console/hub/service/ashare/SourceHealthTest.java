package com.iflytek.astron.console.hub.service.ashare;

import com.iflytek.astron.console.hub.service.ashare.SourceHealth.Source;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Failover between the free providers: an outage must not stop trading, and must not be hidden. */
class SourceHealthTest {

    private SourceHealth health;
    private List<String> calls;

    @BeforeEach
    void setUp() {
        health = new SourceHealth();
        calls = new ArrayList<>();
    }

    private Source<String> ok(String name) {
        return new Source<>(name, () -> {
            calls.add(name);
            return name + "-data";
        });
    }

    private Source<String> down(String name) {
        return new Source<>(name, () -> {
            calls.add(name);
            throw new AShareDataException(name + " 连接失败");
        });
    }

    @Test
    void theFirstWorkingProviderAnswers() {
        assertEquals("tencent-data", health.fetch("quote", List.of(down("eastmoney"), ok("tencent"), ok("sina"))));
        // Sina is never asked, because Tencent already answered.
        assertEquals(List.of("eastmoney", "tencent"), calls);
        assertEquals("tencent", health.preferred("quote"));
    }

    @Test
    void theRichestProviderIsRetriedOnceItRecovers() {
        // One blip must not strand us on a fallback: the listed order is the real preference, so the
        // primary is asked again as soon as it works, rather than only when the fallback breaks.
        health.fetch("quote", List.of(down("eastmoney"), ok("tencent")));
        calls.clear();
        assertEquals("eastmoney-data", health.fetch("quote", List.of(ok("eastmoney"), ok("tencent"))));
        assertEquals(List.of("eastmoney"), calls);
        assertEquals("eastmoney", health.preferred("quote"));
    }

    @Test
    void aRepeatedlyFailingProviderIsSetAsideThenTriedAgain() {
        for (int i = 0; i < SourceHealth.FAILURES_BEFORE_COOLDOWN; i++) {
            health.fetch("quote", List.of(down("eastmoney"), ok("tencent")));
        }
        assertTrue(health.cooling("eastmoney"));
        assertFalse(health.cooling("tencent"));
        calls.clear();
        health.fetch("quote", List.of(down("eastmoney"), ok("tencent")));
        // While cooling it is moved behind the others, so the healthy one is asked first.
        assertEquals(List.of("tencent"), calls);
    }

    @Test
    void everythingCoolingStillGetsTriedRatherThanGivingUp() {
        for (int i = 0; i < SourceHealth.FAILURES_BEFORE_COOLDOWN; i++) {
            assertThrows(AShareDataException.class,
                    () -> health.fetch("quote", List.of(down("eastmoney"), down("tencent"))));
        }
        assertTrue(health.cooling("eastmoney"));
        assertTrue(health.cooling("tencent"));
        calls.clear();
        // One of them has recovered: it must still be asked even though its cooldown has not expired.
        assertEquals("tencent-data", health.fetch("quote", List.of(down("eastmoney"), ok("tencent"))));
        assertTrue(calls.contains("tencent"));
    }

    @Test
    void successClearsTheCooldown() {
        for (int i = 0; i < SourceHealth.FAILURES_BEFORE_COOLDOWN; i++) {
            health.fetch("quote", List.of(down("eastmoney"), ok("tencent")));
        }
        assertTrue(health.cooling("eastmoney"));
        health.fetch("quote", List.of(ok("eastmoney")));
        assertFalse(health.cooling("eastmoney"));
    }

    @Test
    void anOutageIsReportedRatherThanPapredOverWithAStalePrice() {
        AShareDataException e = assertThrows(AShareDataException.class,
                () -> health.fetch("quote", List.of(down("eastmoney"), down("tencent"), down("sina"))));
        // The message names a real failure, so a skipped order can be explained.
        assertTrue(e.getMessage().contains("连接失败"), e.getMessage());
        assertEquals(List.of("eastmoney", "tencent", "sina"), calls);
        assertThrows(AShareDataException.class, () -> health.fetch("quote", List.of()));
    }

    @Test
    void differentKindsOfRequestRememberTheirOwnProvider() {
        health.fetch("quote", List.of(down("eastmoney"), ok("tencent")));
        health.fetch("kline", List.of(ok("eastmoney"), ok("tencent")));
        assertEquals("tencent", health.preferred("quote"));
        assertEquals("eastmoney", health.preferred("kline"));
    }

    @Test
    void healthShowsWhatIsServingAndWhatIsSetAside() {
        for (int i = 0; i < SourceHealth.FAILURES_BEFORE_COOLDOWN; i++) {
            health.fetch("quote", List.of(down("eastmoney"), ok("tencent")));
        }
        var json = health.health();
        assertEquals("tencent", json.getJSONObject("serving").getString("quote"));
        var rows = json.getJSONArray("sources");
        assertEquals(2, rows.size());
        var em = rows.stream()
                .map(o -> (com.alibaba.fastjson2.JSONObject) o)
                .filter(o -> "eastmoney".equals(o.getString("source")))
                .findFirst()
                .orElseThrow();
        assertTrue(em.getBooleanValue("cooling"));
        assertTrue(em.getLongValue("failures") >= SourceHealth.FAILURES_BEFORE_COOLDOWN);
        assertTrue(em.getString("lastError").contains("连接失败"));
    }

    @Test
    void onlyProvidersOfferedForAKindAreUsed() {
        // The adjusted series has no Sina provider, so a caller cannot accidentally mix price bases.
        AtomicInteger sinaCalls = new AtomicInteger();
        Source<String> sina = new Source<>("sina", () -> {
            sinaCalls.incrementAndGet();
            return "sina-data";
        });
        assertThrows(AShareDataException.class,
                () -> health.fetch("kline-adjusted", List.of(down("eastmoney"), down("tencent"))));
        assertEquals(0, sinaCalls.get());
        assertEquals("sina-data", health.fetch("kline", List.of(down("eastmoney"), sina)));
    }
}
