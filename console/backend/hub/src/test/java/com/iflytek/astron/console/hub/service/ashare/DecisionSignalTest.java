package com.iflytek.astron.console.hub.service.ashare;

import com.iflytek.astron.console.hub.entity.ashare.DecisionSignal;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecisionSignalTest {

    private static DecisionSignal signal(BigDecimal entry, BigDecimal stop, BigDecimal target, String watch) {
        DecisionSignal s = new DecisionSignal();
        s.setEntryLow(entry);
        s.setStopLoss(stop);
        s.setTargetPrice(target);
        s.setWatch(watch);
        return s;
    }

    @Test
    void planQualityCountsTheActionableParts() {
        BigDecimal p = BigDecimal.TEN;
        assertEquals("COMPLETE", DecisionSignalService.planQuality(signal(p, p, p, "跌破 20 日线则失效")));
        assertEquals("PARTIAL", DecisionSignalService.planQuality(signal(p, p, null, null)));
        assertEquals("MINIMAL", DecisionSignalService.planQuality(signal(null, p, null, null)));
        assertEquals("MINIMAL", DecisionSignalService.planQuality(signal(null, null, null, " ")));
    }

    @Test
    void everyActionAndHorizonIsKnown() {
        assertEquals(8, DecisionSignalService.ACTIONS.size());
        assertTrue(DecisionSignalService.ACTIONS.contains(DecisionSignal.BUY));
        assertTrue(DecisionSignalService.ACTIONS.contains(DecisionSignal.AVOID));
        assertEquals(java.util.List.of("1d", "3d", "5d", "10d"),
                java.util.List.copyOf(DecisionSignalService.HORIZONS.keySet()));
        assertEquals(10, DecisionSignalService.HORIZONS.get("10d"));
        // Attention-only calls are recorded but never counted as right or wrong.
        assertTrue(DecisionSignalService.NOT_SCORED.contains(DecisionSignal.WATCH));
        assertTrue(DecisionSignalService.NOT_SCORED.contains(DecisionSignal.ALERT));
        assertTrue(DecisionSignalService.NOT_SCORED.stream().noneMatch(a -> DecisionSignal.BUY.equals(a)));
    }

    private static DecisionSignal pending(String action, String horizon) {
        DecisionSignal s = new DecisionSignal();
        s.setAction(action);
        s.setHorizon(horizon);
        return s;
    }

    @Test
    void anOppositeCallReplacesTheEarlierOne() {
        // Recording both a buy and a sell would look right whichever way the price went.
        assertTrue(DecisionSignalService.supersedes(DecisionSignal.SELL, "5d", pending(DecisionSignal.BUY, "5d")));
        assertTrue(DecisionSignalService.supersedes(DecisionSignal.BUY, "3d", pending(DecisionSignal.REDUCE, "10d")));
        assertTrue(DecisionSignalService.supersedes(DecisionSignal.ADD, "5d", pending(DecisionSignal.AVOID, "5d")));
        // The same call restated replaces itself.
        assertTrue(DecisionSignalService.supersedes(DecisionSignal.HOLD, "5d", pending(DecisionSignal.HOLD, "5d")));
    }

    @Test
    void sameDirectionOverDifferentWindowsBothStand() {
        assertFalse(DecisionSignalService.supersedes(DecisionSignal.BUY, "10d", pending(DecisionSignal.BUY, "5d")));
        assertFalse(DecisionSignalService.supersedes(DecisionSignal.ADD, "5d", pending(DecisionSignal.BUY, "5d")));
        // Hold and watch take no side, so they neither replace nor are replaced by a directional call.
        assertFalse(DecisionSignalService.supersedes(DecisionSignal.BUY, "5d", pending(DecisionSignal.HOLD, "3d")));
        assertFalse(DecisionSignalService.supersedes(DecisionSignal.HOLD, "3d", pending(DecisionSignal.SELL, "5d")));
        assertEquals(0, DecisionSignalService.direction(DecisionSignal.WATCH));
        assertEquals(0, DecisionSignalService.direction(null));
        assertEquals(1, DecisionSignalService.direction(DecisionSignal.BUY));
        assertEquals(-1, DecisionSignalService.direction(DecisionSignal.SELL));
    }

    @Test
    void sampleThresholdMatchesTheReference() {
        // A hit rate from a handful of calls means nothing; 30 is the smallest group we report on.
        assertEquals(30, DecisionSignalService.MIN_SAMPLE);
        assertEquals(2.0, DecisionSignalService.NEUTRAL_BAND);
    }
}
