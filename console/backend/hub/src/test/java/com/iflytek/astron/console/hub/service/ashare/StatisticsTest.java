package com.iflytek.astron.console.hub.service.ashare;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatisticsTest {

    /** Daily returns with a given annualized Sharpe ratio, normally distributed. */
    private static double[] returns(double annualSharpe, int days, long seed) {
        Random r = new Random(seed);
        double dailySd = 0.01;
        double dailyMean = annualSharpe / Math.sqrt(Statistics.TRADING_DAYS) * dailySd;
        double[] out = new double[days];
        for (int i = 0; i < days; i++) {
            out[i] = dailyMean + r.nextGaussian() * dailySd;
        }
        return out;
    }

    @Test
    void normalCdfAndItsInverseAgreeWithKnownValues() {
        assertEquals(0.5, Statistics.normalCdf(0), 1e-9);
        assertEquals(0.841344746, Statistics.normalCdf(1), 1e-6);
        // 1.959963985 is the exact 97.5% quantile.
        assertEquals(0.975, Statistics.normalCdf(1.959963985), 1e-6);
        assertEquals(0.001349898, Statistics.normalCdf(-3), 1e-6);
        assertEquals(1.959963985, Statistics.normalInv(0.975), 1e-5);
        assertEquals(1.644853627, Statistics.normalInv(0.95), 1e-5);
        assertEquals(0, Statistics.normalInv(0.5), 1e-9);
        assertEquals(-2.326347874, Statistics.normalInv(0.01), 1e-5);
        // Round trip over the whole usable range.
        for (double p = 0.001; p < 0.999; p += 0.013) {
            assertEquals(p, Statistics.normalCdf(Statistics.normalInv(p)), 1e-6, "p=" + p);
        }
    }

    @Test
    void momentsOfAKnownSample() {
        double[] v = {2, 4, 4, 4, 5, 5, 7, 9};
        assertEquals(5.0, Statistics.mean(v), 1e-9);
        assertEquals(2.0, Statistics.stdDev(v), 1e-9);
        // Population skewness: the third moment 5.25 over sd^3 = 8.
        assertEquals(0.65625, Statistics.skewness(v), 1e-9);
        // Population kurtosis: the fourth moment 44.5 over sd^4 = 16.
        assertEquals(2.78125, Statistics.kurtosis(v), 1e-9);
        // A symmetric sample has no skew; a normal-ish one has kurtosis near 3.
        assertEquals(0, Statistics.skewness(new double[] {-2, -1, 0, 1, 2}), 1e-9);
        assertEquals(3, Statistics.kurtosis(new double[] {1}), 1e-9);
    }

    @Test
    void theBarRisesWithTheNumberOfTrials() {
        // The best of many worthless tries still looks good; that expectation is what we subtract.
        double spread = 0.5 / Math.sqrt(Statistics.TRADING_DAYS);
        double one = Statistics.expectedMaxSharpe(2, spread);
        double many = Statistics.expectedMaxSharpe(24, spread);
        double lots = Statistics.expectedMaxSharpe(1248, spread);
        assertTrue(one < many, one + " < " + many);
        assertTrue(many < lots, many + " < " + lots);
        // With 24 tries and a 0.5 spread of annualized Sharpes, luck alone is worth about 1.0.
        assertEquals(1.0, Statistics.annualize(many), 0.1);
        // A year of weekly searching pushes it well above 1.2.
        assertTrue(Statistics.annualize(lots) > 1.2, String.valueOf(Statistics.annualize(lots)));
    }

    @Test
    void aGenuinelyStrongResultSurvivesAndAWeakOneDoesNot() {
        double[] strong = returns(2.5, 360, 7);
        double[] weak = returns(0.3, 360, 7);
        assertTrue(Statistics.deflatedSharpe(strong, 24, 0.5) > 0.95,
                String.valueOf(Statistics.deflatedSharpe(strong, 24, 0.5)));
        assertTrue(Statistics.deflatedSharpe(weak, 24, 0.5) < 0.5,
                String.valueOf(Statistics.deflatedSharpe(weak, 24, 0.5)));
    }

    @Test
    void theSameResultIsLessConvincingAfterMoreSearching() {
        double[] r = returns(1.4, 360, 11);
        double few = Statistics.deflatedSharpe(r, 5, 0.5);
        double some = Statistics.deflatedSharpe(r, 100, 0.5);
        double many = Statistics.deflatedSharpe(r, 5000, 0.5);
        assertTrue(few > some, few + " > " + some);
        assertTrue(some > many, some + " > " + many);
    }

    @Test
    void aLongerRecordMakesTheSameSharpeMoreConvincing() {
        double low = Statistics.deflatedSharpe(returns(1.5, 120, 3), 24, 0.5);
        double high = Statistics.deflatedSharpe(returns(1.5, 1000, 3), 24, 0.5);
        assertTrue(high > low, high + " > " + low);
    }

    @Test
    void notEnoughDataIsNotEvidence() {
        assertEquals(0, Statistics.deflatedSharpe(null, 10, 0.5));
        assertEquals(0, Statistics.deflatedSharpe(new double[5], 10, 0.5));
        // A flat curve has no volatility and therefore no measurable Sharpe ratio.
        assertEquals(0, Statistics.deflatedSharpe(new double[400], 10, 0.5));
    }

    @Test
    void spreadOfTheSearchesOwnSharpes() {
        assertEquals(0, Statistics.sharpeSpread(List.of()));
        assertEquals(0, Statistics.sharpeSpread(List.of(1.0)));
        assertEquals(1.0, Statistics.sharpeSpread(List.of(-1.0, 1.0)), 1e-9);
    }
}
