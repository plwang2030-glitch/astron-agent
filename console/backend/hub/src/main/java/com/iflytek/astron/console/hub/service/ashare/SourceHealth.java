package com.iflytek.astron.console.hub.service.ashare;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Tries several free market-data providers for the same thing and uses whichever answers.
 * <p>
 * These endpoints are unofficial and go down without warning: on 2026-11-25 every Eastmoney quote
 * host refused connections at 14:50 and a scheduled sell was skipped, because orders are priced
 * from that one call. So a provider that fails twice in a row is set aside for {@link #COOLDOWN}
 * instead of being retried on every request, the provider that last answered is tried first, and a
 * provider still in its cooldown is used anyway rather than giving up when every provider is
 * cooling. Nothing here changes what the data means; a source that cannot supply a particular field
 * is simply not offered for it.
 */
@Slf4j
@Component
public class SourceHealth {

    /** How long a provider is left alone after it fails repeatedly. */
    public static final Duration COOLDOWN = Duration.ofMinutes(3);
    static final int FAILURES_BEFORE_COOLDOWN = 2;

    /** One way of getting the thing: a provider name and the call that asks it. */
    public record Source<T>(String name, Supplier<T>call)
    {
    }

    private static final class State {
        private volatile int consecutiveFailures;
        private volatile long coolingUntil;
        private volatile long lastSuccessAt;
        private volatile long successes;
        private volatile long failures;
        private volatile String lastError;
    }

    private final Map<String, State> states = new ConcurrentHashMap<>();
    /** Provider that last answered for a given kind of request, for reporting what is serving. */
    private final Map<String, String> preferred = new ConcurrentHashMap<>();

    /**
     * The first provider that answers, in the given preference order, except that providers in cooldown
     * are tried only after the others.
     *
     * @param what the kind of request, e.g. {@code quote}; used only to report what is serving it
     * @throws AShareDataException when every provider fails, carrying the last provider's message
     */
    public <T> T fetch(String what, List<Source<T>> sources) {
        if (sources.isEmpty()) {
            throw new AShareDataException("没有可用的数据源");
        }
        long now = System.currentTimeMillis();
        List<Source<T>> ready = new ArrayList<>();
        List<Source<T>> cooling = new ArrayList<>();
        for (Source<T> s : sources) {
            (states.computeIfAbsent(s.name(), k -> new State()).coolingUntil > now ? cooling : ready).add(s);
        }
        // Healthy providers keep their listed order, which is the real preference: the first one
        // carries the most fields. Reordering by "whatever answered last" would strand us on a
        // fallback long after the primary recovered, so recovery is handled by the cooldown expiring
        // rather than by promoting the stand-in.
        List<Source<T>> order = new ArrayList<>(ready);
        order.addAll(cooling);
        AShareDataException last = null;
        for (Source<T> s : order) {
            State state = states.computeIfAbsent(s.name(), k -> new State());
            try {
                T value = s.call().get();
                state.consecutiveFailures = 0;
                state.coolingUntil = 0;
                state.lastSuccessAt = System.currentTimeMillis();
                state.successes++;
                state.lastError = null;
                if (!s.name().equals(preferred.get(what))) {
                    preferred.put(what, s.name());
                    log.info("Market data source for {} is now {}", what, s.name());
                }
                return value;
            } catch (AShareDataException e) {
                last = e;
                state.failures++;
                state.consecutiveFailures++;
                state.lastError = e.getMessage();
                if (state.consecutiveFailures >= FAILURES_BEFORE_COOLDOWN) {
                    state.coolingUntil = System.currentTimeMillis() + COOLDOWN.toMillis();
                    log.warn("Market data source set aside for {} min, source={}, what={}, failures={}, msg={}",
                            COOLDOWN.toMinutes(), s.name(), what, state.consecutiveFailures, e.getMessage());
                }
            }
        }
        throw last == null ? new AShareDataException("所有数据源都取不到 " + what) : last;
    }

    /** Whether this provider is currently being left alone. */
    public boolean cooling(String source) {
        State s = states.get(source);
        return s != null && s.coolingUntil > System.currentTimeMillis();
    }

    /** Which provider currently answers a given kind of request, or {@code null} if untried. */
    public String preferred(String what) {
        return preferred.get(what);
    }

    /** For the page and the agents: which sources are working and which are being left alone. */
    public JSONObject health() {
        long now = System.currentTimeMillis();
        JSONArray rows = new JSONArray();
        states.forEach((name, s) -> rows.add(new JSONObject()
                .fluentPut("source", name)
                .fluentPut("cooling", s.coolingUntil > now)
                .fluentPut("coolingSeconds", s.coolingUntil > now ? (s.coolingUntil - now) / 1000 : 0)
                .fluentPut("successes", s.successes)
                .fluentPut("failures", s.failures)
                .fluentPut("consecutiveFailures", s.consecutiveFailures)
                .fluentPut("lastSuccess", s.lastSuccessAt == 0 ? null
                        : LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(s.lastSuccessAt), TradingClock.ZONE)
                                .withNano(0)
                                .toString()
                                .replace('T', ' '))
                .fluentPut("lastError", s.lastError)));
        JSONObject serving = new JSONObject();
        preferred.forEach(serving::put);
        return new JSONObject()
                .fluentPut("sources", rows)
                .fluentPut("serving", serving)
                .fluentPut("cooldownMinutes", COOLDOWN.toMinutes())
                .fluentPut("note", "行情来自多个免费公开接口，按可用性自动切换；某个接口连续失败会被暂时搁置 "
                        + COOLDOWN.toMinutes() + " 分钟后再试。都不可用时会如实报错，不会用旧价下单。");
    }

    /** Forgets all health state; for tests. */
    void reset() {
        states.clear();
        preferred.clear();
    }
}
