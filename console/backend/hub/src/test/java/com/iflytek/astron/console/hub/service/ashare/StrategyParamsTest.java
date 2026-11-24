package com.iflytek.astron.console.hub.service.ashare;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrategyParamsTest {

    @Test
    void defaultsAreValid() {
        assertTrue(StrategyParams.defaults().validate().isEmpty());
    }

    @Test
    void mergeAppliesKnownFieldsOnly() {
        StrategyParams base = StrategyParams.defaults();
        StrategyParams merged = base.merge(new JSONObject().fluentPut("rsiMax", 65).fluentPut("hack", "x"));
        assertEquals(65.0, merged.getRsiMax());
        assertEquals(base.getMaFast(), merged.getMaFast());
        assertTrue(merged.diff(base).contains("rsiMax"));
        assertFalse(merged.diff(base).contains("hack"));
        assertEquals("无变化", base.diff(StrategyParams.defaults()));
    }

    @Test
    void mergeNormalizesUniverse() {
        StrategyParams merged = StrategyParams.defaults()
                .merge(new JSONObject().fluentPut("universe", List.of("sh600036", "600036", "000333.SZ", "300750")));
        assertEquals(List.of("600036", "000333", "300750"), merged.getUniverse());
    }

    @Test
    void validateRejectsOutOfBounds() {
        StrategyParams p = StrategyParams.defaults()
                .merge(new JSONObject().fluentPut("maFast", 10).fluentPut("maSlow", 11).fluentPut("stopLossPct", 1));
        List<String> errors = p.validate();
        assertTrue(errors.stream().anyMatch(e -> e.contains("maSlow")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("stopLossPct")));
        // A hand-picked (MANUAL) universe needs 3–30 stocks; AUTO checks the screen size instead.
        StrategyParams small = StrategyParams.parse("{\"universe\":[\"600036\"]}");
        assertEquals(StrategyParams.MANUAL, small.getUniverseMode());
        assertTrue(small.validate().stream().anyMatch(e -> e.contains("universe")));
        StrategyParams auto = StrategyParams.defaults().merge(new JSONObject().fluentPut("universe", List.of("600036")));
        assertTrue(auto.validate().isEmpty());
        assertTrue(StrategyParams.defaults()
                .merge(new JSONObject().fluentPut("universeSize", 5))
                .validate()
                .stream()
                .anyMatch(e -> e.contains("universeSize")));
    }

    @Test
    void universeModeDefaultsAndCompatibility() {
        StrategyParams d = StrategyParams.defaults();
        assertEquals(StrategyParams.AUTO, d.getUniverseMode());
        assertEquals(30, d.getUniverseSize());
        assertTrue(d.autoUniverse());
        assertTrue(d.describe().startsWith("股票池：规则筛选前 30 只"));
        // Versions saved before the screen existed have no mode: they keep their hand-picked list.
        StrategyParams old = StrategyParams.parse("{\"universe\":[\"600036\",\"000333\",\"601318\"],\"rsiMax\":65}");
        assertEquals(StrategyParams.MANUAL, old.getUniverseMode());
        assertFalse(old.autoUniverse());
        assertTrue(old.describe().startsWith("股票池 3 只（手选）"));
        // A copy round-trips the mode.
        assertEquals(StrategyParams.AUTO, d.copy().getUniverseMode());
        assertEquals(StrategyParams.MANUAL, old.copy().getUniverseMode());
    }

    @Test
    void universeModeIsNotAProposal() {
        StrategyParams old = StrategyParams.parse("{\"universe\":[\"600036\",\"000333\",\"601318\"]}");
        StrategyParams merged = old.merge(new JSONObject().fluentPut("universeMode", "AUTO"));
        assertEquals(StrategyParams.MANUAL, merged.getUniverseMode());
        assertTrue(merged.sameAs(old));
        // In AUTO mode the fallback list does not count as a change; the size does.
        StrategyParams auto = StrategyParams.defaults();
        assertTrue(auto.merge(new JSONObject().fluentPut("universe", List.of("600036", "000333", "601318"))).sameAs(auto));
        assertEquals("universeSize 30→20", auto.merge(new JSONObject().fluentPut("universeSize", 20)).diff(auto));
        // The method change shows in the diff.
        StrategyParams switched = old.copy();
        switched.setUniverseMode(StrategyParams.AUTO);
        assertTrue(switched.diff(old).contains("universeMode MANUAL→AUTO"));
    }

    @Test
    void parseFillsMissingFields() {
        StrategyParams p = StrategyParams.parse("{\"rsiMax\":60}");
        assertEquals(60.0, p.getRsiMax());
        assertEquals(StrategyParams.defaults().getUniverse(), p.getUniverse());
        assertTrue(p.validate().isEmpty());
    }
}
