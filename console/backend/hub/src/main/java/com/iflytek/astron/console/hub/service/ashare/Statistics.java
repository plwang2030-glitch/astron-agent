package com.iflytek.astron.console.hub.service.ashare;

import java.util.List;

/**
 * Statistics for judging whether a backtest result is a discovery or the best of many tries.
 * <p>
 * Searching many variants against the same out-of-sample window guarantees that some look good by
 * luck: with 1,248 candidates a year and a 5% per-test false-positive rate, about 62 pass by chance
 * alone. The {@link #deflatedSharpe} of Bailey and López de Prado answers the question that matters
 * instead: given that this was the best of {@code trials} attempts on {@code T} observations, how
 * likely is the true Sharpe ratio above zero? The bar therefore rises on its own as the search gets
 * busier, which is what makes searching harder safe rather than self-defeating.
 * <p>
 * This corrects for selection, not for leakage: a strategy that can see the future still scores
 * perfectly here. Point-in-time stock pools and settled-bar anchoring are what guard against that,
 * and they are separate.
 */
public final class Statistics {



    /** Euler–Mascheroni constant, from the expected maximum of N normal draws. */
    static final double EULER = 0.5772156649015329;
    /** Daily returns per year, to move between daily and annualized Sharpe ratios. */
    public static final double TRADING_DAYS = 252;

    private Statistics() {}

    public static double mean(double[] v) {
        if (v == null || v.length == 0) {
            return 0;
        }
        double sum = 0;
        for (double x : v) {
            sum += x;
        }
        return sum / v.length;
    }

    /** Population standard deviation. */
    public static double stdDev(double[] v) {
        if (v == null || v.length < 2) {
            return 0;
        }
        double m = mean(v);
        double sq = 0;
        for (double x : v) {
            sq += (x - m) * (x - m);
        }
        return Math.sqrt(sq / v.length);
    }

    /** Skewness: negative means the big moves are losses, which makes a Sharpe ratio flattering. */
    public static double skewness(double[] v) {
        double sd = stdDev(v);
        if (sd <= 0 || v.length < 3) {
            return 0;
        }
        double m = mean(v);
        double sum = 0;
        for (double x : v) {
            sum += Math.pow((x - m) / sd, 3);
        }
        return sum / v.length;
    }

    /**
     * Kurtosis, not excess: 3 for a normal distribution, more when fat tails make Sharpe unreliable.
     */
    public static double kurtosis(double[] v) {
        double sd = stdDev(v);
        if (sd <= 0 || v.length < 4) {
            return 3;
        }
        double m = mean(v);
        double sum = 0;
        for (double x : v) {
            sum += Math.pow((x - m) / sd, 4);
        }
        return sum / v.length;
    }

    /** Standard normal cumulative distribution, via the error function. */
    public static double normalCdf(double z) {
        return 0.5 * (1 + erf(z / Math.sqrt(2)));
    }

    /** Abramowitz–Stegun 7.1.26: absolute error below 1.5e-7. */
    static double erf(double x) {
        double sign = Math.signum(x);
        double a = Math.abs(x);
        double t = 1 / (1 + 0.3275911 * a);
        double y = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t
                + 0.254829592) * t * Math.exp(-a * a);
        return sign * y;
    }

    /**
     * Inverse standard normal (the probit), by Acklam's rational approximation with one Halley
     * refinement against {@link #normalCdf}. Its accuracy is therefore capped by that function's, about
     * 1e-5 absolute, which is far finer than any decision made from it here.
     */
    public static double normalInv(double p) {
        if (p <= 0 || p >= 1) {
            return p <= 0 ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        }
        double[] a = {-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02,
                1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00};
        double[] b = {-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02,
                6.680131188771972e+01, -1.328068155288572e+01};
        double[] c = {-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00,
                -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00};
        double[] d = {7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00,
                3.754408661907416e+00};
        double pLow = 0.02425;
        double x;
        if (p < pLow) {
            double q = Math.sqrt(-2 * Math.log(p));
            x = (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5])
                    / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
        } else if (p <= 1 - pLow) {
            double q = p - 0.5;
            double r = q * q;
            x = (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q
                    / (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1);
        } else {
            double q = Math.sqrt(-2 * Math.log(1 - p));
            x = -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5])
                    / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
        }
        // One Halley step against the CDF removes most of the remaining error.
        double e = normalCdf(x) - p;
        double u = e * Math.sqrt(2 * Math.PI) * Math.exp(x * x / 2);
        return x - u / (1 + x * u / 2);
    }

    /**
     * The Sharpe ratio the best of {@code trials} worthless strategies would be expected to show, in
     * the same per-period units as {@code sharpeStdDev}. Anything below this is explained by the search
     * effort alone.
     *
     * @param trials how many candidates were evaluated against this data
     * @param sharpeStdDev spread of the candidates' Sharpe ratios, per period
     */
    public static double expectedMaxSharpe(int trials, double sharpeStdDev) {
        int n = Math.max(2, trials);
        double a = normalInv(1 - 1.0 / n);
        double b = normalInv(1 - 1.0 / (n * Math.E));
        return sharpeStdDev * ((1 - EULER) * a + EULER * b);
    }

    /**
     * Probability that the true Sharpe ratio is above zero, given that this result was selected as the
     * best of {@code trials} attempts (Bailey and López de Prado's deflated Sharpe ratio). Accounts for
     * the number of trials, the length of the record, and the skew and fat tails of the returns. Above
     * 0.95 is strong evidence; below 0.5 means the result is no better than the search effort explains.
     *
     * @param dailyReturns the candidate's out-of-sample daily returns
     * @param trials candidates evaluated against the same data, including this one
     * @param annualSharpeStdDev spread of those candidates' annualized Sharpe ratios
     * @return a probability in [0, 1], or 0 when there is not enough data to judge
     */
    public static double deflatedSharpe(double[] dailyReturns, int trials, double annualSharpeStdDev) {
        if (dailyReturns == null || dailyReturns.length < 30) {
            return 0;
        }
        double sd = stdDev(dailyReturns);
        if (sd <= 0) {
            return 0;
        }
        int t = dailyReturns.length;
        double sharpe = mean(dailyReturns) / sd;
        // A spread has to be assumed when the search reports none; 0.5 annualized is a mild default.
        double spread = (annualSharpeStdDev > 0 ? annualSharpeStdDev : 0.5) / Math.sqrt(TRADING_DAYS);
        double expectedMax = expectedMaxSharpe(trials, spread);
        double skew = skewness(dailyReturns);
        double kurt = kurtosis(dailyReturns);
        double variance = 1 - skew * sharpe + (kurt - 1) / 4.0 * sharpe * sharpe;
        if (variance <= 0) {
            return 0;
        }
        double z = (sharpe - expectedMax) * Math.sqrt(t - 1) / Math.sqrt(variance);
        return normalCdf(z);
    }

    /**
     * Probability of backtest overfitting, by the combinatorially symmetric cross-validation of Bailey,
     * Borwein, López de Prado and Zhu. It asks a different question from {@link #deflatedSharpe}: not
     * "is this one result real", but "does picking the best candidate on part of the data tell us
     * anything about the rest".
     * <p>
     * The time blocks are split every possible way into halves. In each split the candidate that led in
     * one half is found, and its rank in the other half is recorded. The answer is the share of splits
     * where the leader ends up below the median: near 0 means the selection generalizes, 0.5 means it
     * is a coin toss, and near 1 means picking the best is actively misleading.
     *
     * @param blockScores one row per candidate, one column per time block
     * @return the probability in [0, 1], or {@code NaN} when there is too little to compare
     */
    public static double probabilityOfOverfitting(double[][] blockScores) {
        if (blockScores == null || blockScores.length < 2) {
            return Double.NaN;
        }
        int candidates = blockScores.length;
        int blocks = blockScores[0].length;
        if (blocks < 4 || blocks % 2 != 0) {
            return Double.NaN;
        }
        for (double[] row : blockScores) {
            if (row == null || row.length != blocks) {
                return Double.NaN;
            }
        }
        List<int[]> splits = combinations(blocks, blocks / 2);
        if (splits.isEmpty()) {
            return Double.NaN;
        }
        int below = 0;
        int counted = 0;
        for (int[] inSample : splits) {
            boolean[] isIn = new boolean[blocks];
            for (int b : inSample) {
                isIn[b] = true;
            }
            int best = -1;
            double bestScore = Double.NEGATIVE_INFINITY;
            double[] outOfSample = new double[candidates];
            for (int c = 0; c < candidates; c++) {
                double in = 0;
                double out = 0;
                for (int b = 0; b < blocks; b++) {
                    if (isIn[b]) {
                        in += blockScores[c][b];
                    } else {
                        out += blockScores[c][b];
                    }
                }
                outOfSample[c] = out;
                if (in > bestScore) {
                    bestScore = in;
                    best = c;
                }
            }
            if (best < 0) {
                continue;
            }
            // The leader's rank on the held-out blocks, lowest first, mapped into (0,1) as
            // rank/(N+1) so that the median sits at 0.5 whatever the number of candidates.
            int beaten = 0;
            for (int c = 0; c < candidates; c++) {
                if (c != best && outOfSample[c] > outOfSample[best]) {
                    beaten++;
                }
            }
            double relativeRank = (double) (candidates - beaten) / (candidates + 1);
            counted++;
            if (relativeRank < 0.5) {
                below++;
            }
        }
        return counted == 0 ? Double.NaN : (double) below / counted;
    }

    /** All ways of choosing {@code k} of {@code n} block indices. */
    static List<int[]> combinations(int n, int k) {
        List<int[]> out = new java.util.ArrayList<>();
        int[] current = new int[k];
        combine(out, current, 0, 0, n, k);
        return out;
    }

    private static void combine(List<int[]> out, int[] current, int at, int from, int n, int k) {
        if (at == k) {
            out.add(current.clone());
            return;
        }
        for (int i = from; i < n; i++) {
            current[at] = i;
            combine(out, current, at + 1, i + 1, n, k);
        }
    }

    /**
     * Upper confidence bound for a search direction: its average result plus a bonus for how little it
     * has been tried, so a direction is never written off after one bad attempt and an untried one is
     * always worth a look.
     *
     * @param mean average out-of-sample score of this direction so far
     * @param tried how many times it has been tried
     * @param total trials across all directions
     */
    public static double upperConfidenceBound(double mean, int tried, int total) {
        if (tried <= 0) {
            return Double.POSITIVE_INFINITY;
        }
        return mean + Math.sqrt(2 * Math.log(Math.max(2, total)) / tried);
    }

    /** Annualized Sharpe ratio of daily returns, as used elsewhere in the gate. */
    public static double annualize(double dailySharpe) {
        return dailySharpe * Math.sqrt(TRADING_DAYS);
    }

    /** Standard deviation of a set of annualized Sharpe ratios, for {@link #deflatedSharpe}. */
    public static double sharpeSpread(java.util.List<Double> annualSharpes) {
        if (annualSharpes == null || annualSharpes.size() < 2) {
            return 0;
        }
        double[] v = new double[annualSharpes.size()];
        for (int i = 0; i < v.length; i++) {
            v[i] = annualSharpes.get(i);
        }
        return stdDev(v);
    }
}
