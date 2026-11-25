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
    void overfittingIsZeroWhenOneCandidateIsGenuinelyBest() {
        // Candidate 0 leads in every block, so picking the leader always generalizes.
        double[][] m = {
                {2, 2, 2, 2, 2, 2},
                {1, 1, 1, 1, 1, 1},
                {0, 0, 0, 0, 0, 0}};
        assertEquals(0.0, Statistics.probabilityOfOverfitting(m), 1e-9);
    }

    @Test
    void overfittingIsOneWhenTheLeaderIsAlwaysTheWorstElsewhere() {
        // Candidate 0 wins the first three blocks and loses the last three, and vice versa: whichever
        // half is used to choose, the choice is wrong on the other half.
        double[][] m = {
                {5, 5, 5, -5, -5, -5},
                {-5, -5, -5, 5, 5, 5}};
        assertEquals(1.0, Statistics.probabilityOfOverfitting(m), 1e-9);
    }

    @Test
    void pureNoiseLandsNearACoinToss() {
        Random r = new Random(42);
        double[][] m = new double[30][6];
        for (double[] row : m) {
            for (int b = 0; b < row.length; b++) {
                row[b] = r.nextGaussian();
            }
        }
        double pbo = Statistics.probabilityOfOverfitting(m);
        assertTrue(pbo > 0.25 && pbo < 0.75, "pbo=" + pbo);
    }

    @Test
    void overfittingNeedsEnoughCandidatesAndAnEvenBlockCount() {
        double[][] one = {{1, 1, 1, 1, 1, 1}};
        assertTrue(Double.isNaN(Statistics.probabilityOfOverfitting(one)));
        assertTrue(Double.isNaN(Statistics.probabilityOfOverfitting(null)));
        // Odd or too few blocks cannot be split into symmetric halves.
        assertTrue(Double.isNaN(Statistics.probabilityOfOverfitting(new double[][] {{1, 2, 3}, {3, 2, 1}})));
        assertTrue(Double.isNaN(Statistics.probabilityOfOverfitting(new double[][] {{1, 2}, {2, 1}})));
        // Ragged input is refused rather than guessed at.
        assertTrue(Double.isNaN(Statistics.probabilityOfOverfitting(
                new double[][] {{1, 2, 3, 4, 5, 6}, {1, 2, 3}})));
    }

    @Test
    void splitsAreEverySymmetricHalf() {
        assertEquals(20, Statistics.combinations(6, 3).size());
        assertEquals(6, Statistics.combinations(4, 2).size());
    }

    @Test
    void anUntriedDirectionIsAlwaysWorthALook() {
        assertEquals(Double.POSITIVE_INFINITY, Statistics.upperConfidenceBound(5, 0, 100));
        // A direction tried once keeps a large bonus; one tried often is judged mostly on its average.
        double rare = Statistics.upperConfidenceBound(0.0, 1, 100);
        double common = Statistics.upperConfidenceBound(0.0, 100, 100);
        assertTrue(rare > common, rare + " > " + common);
        // A better average still wins between equally tried directions.
        assertTrue(Statistics.upperConfidenceBound(0.5, 10, 100) > Statistics.upperConfidenceBound(0.1, 10, 100));
    }

    @Test
    void spreadOfTheSearchesOwnSharpes() {
        assertEquals(0, Statistics.sharpeSpread(List.of()));
        assertEquals(0, Statistics.sharpeSpread(List.of(1.0)));
        assertEquals(1.0, Statistics.sharpeSpread(List.of(-1.0, 1.0)), 1e-9);
    }
}
