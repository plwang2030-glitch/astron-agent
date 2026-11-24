package com.iflytek.astron.console.hub.service.ashare;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * A backtestable strategy whose structure, not only its numbers, can evolve:
 * <ul>
 * <li>Entry ({@link #entryType}): fast MA crossing above the slow MA within
 * {@code crossWithinDays}, a close above the highest high of the previous {@code breakoutDays}, a
 * MACD golden cross within {@code crossWithinDays}, or any of them. Common filters: RSI range,
 * volume ratio, optionally the close above MA60 and the Shanghai Composite above its MA20
 * ({@link #marketFilter}).</li>
 * <li>Size ({@link #sizing}): EQUAL puts {@code maxWeightPct} of equity in each stock; RISK sizes
 * so that hitting the stop loss costs {@code riskPerTradePct} of equity, capped by
 * {@code maxWeightPct}.</li>
 * <li>Exit: stop loss; trailing stop from the highest price since the buy ({@code trailingStopPct},
 * 0 off); half the position at {@code partialTakeProfitPct} (0 off); the rest at
 * {@code takeProfitPct}; max holding days; fast/slow MA cross down.</li>
 * </ul>
 * Every version can be backtested, so versions are compared objectively.
 */
@Data
public class StrategyParams {

    public static final int MAX_UNIVERSE = 30;
    public static final int MIN_UNIVERSE = 3;
    public static final String MA_CROSS = "MA_CROSS";
    public static final String BREAKOUT = "BREAKOUT";
    public static final String MACD = "MACD";
    public static final String ANY = "ANY";
    /** Entry from {@link #customEntry}, a {@link RuleExpression}. */
    public static final String CUSTOM = "CUSTOM";
    public static final String EQUAL = "EQUAL";
    public static final String RISK = "RISK";
    public static final Set<String> ENTRY_TYPES = Set.of(MA_CROSS, BREAKOUT, MACD, ANY, CUSTOM);
    public static final Set<String> SIZINGS = Set.of(EQUAL, RISK);
    /** The trading universe is the monthly rule-based screen ({@link UniverseScreen}). */
    public static final String AUTO = "AUTO";
    /** The trading universe is the {@link #universe} list. */
    public static final String MANUAL = "MANUAL";
    public static final Set<String> UNIVERSE_MODES = Set.of(AUTO, MANUAL);
    public static final int DEFAULT_UNIVERSE_SIZE = 30;
    /** In the order shown by {@link #diff}. */
    static final List<String> FIELD_ORDER = List.of("universeMode", "universeSize", "universe", "entryType", "maFast", "maSlow", "crossWithinDays",
            "breakoutDays", "rsiMin", "rsiMax", "volumeRatioMin", "requireAboveMa60", "marketFilter", "sizing",
            "riskPerTradePct", "stopLossPct", "trailingStopPct", "partialTakeProfitPct", "takeProfitPct",
            "maxHoldDays", "exitOnMaCrossDown", "customEntry", "customExit", "maxPositions", "maxWeightPct");
    /**
     * Patchable fields; anything else in a proposal is ignored. The universe mode is a method change
     * made outside the gate, not a proposal.
     */
    public static final Set<String> FIELDS =
            FIELD_ORDER.stream().filter(f -> !"universeMode".equals(f)).collect(java.util.stream.Collectors.toUnmodifiableSet());

    /**
     * AUTO: the top {@link #universeSize} of the monthly screen; MANUAL: the {@link #universe} list.
     */
    private String universeMode;
    /** Size of the AUTO trading universe. */
    private Integer universeSize;
    /**
     * Watchlist the strategy scans and is backtested on in MANUAL mode; in AUTO mode only a fallback
     * until the first screen exists.
     */
    private List<String> universe;
    private String entryType;
    private Integer maFast;
    private Integer maSlow;
    private Integer crossWithinDays;
    private Integer breakoutDays;
    private Double rsiMin;
    private Double rsiMax;
    private Double volumeRatioMin;
    private Boolean requireAboveMa60;
    /** Only buy while the Shanghai Composite closes above its MA20. */
    private Boolean marketFilter;
    private String sizing;
    private Double riskPerTradePct;
    private Double stopLossPct;
    /** 0: off. */
    private Double trailingStopPct;
    /** 0: off. Sells half the position there. */
    private Double partialTakeProfitPct;
    private Double takeProfitPct;
    private Integer maxHoldDays;
    private Boolean exitOnMaCrossDown;
    /**
     * Entry rule when {@link #entryType} is CUSTOM (replaces the built-in signal and RSI / volume /
     * MA60 filters).
     */
    private String customEntry;
    /** Optional extra daily exit rule, checked at the close like the MA cross down; empty for none. */
    private String customExit;
    private Integer maxPositions;
    private Double maxWeightPct;

    /**
     * MA trend following with equal weights, no trailing stop and no partial exit; the other options
     * are left for evolution to try. The universe is the monthly rule-based screen; the list is the
     * fallback until it exists (and version 1's hand-picked universe).
     */
    public static StrategyParams defaults() {
        StrategyParams p = new StrategyParams();
        p.universeMode = AUTO;
        p.universeSize = DEFAULT_UNIVERSE_SIZE;
        p.universe = List.of("601318", "600036", "000333", "600900", "601012", "000725", "002415", "600030",
                "601899", "300059", "600276", "002475");
        p.entryType = MA_CROSS;
        p.maFast = 5;
        p.maSlow = 20;
        p.crossWithinDays = 5;
        p.breakoutDays = 20;
        p.rsiMin = 40.0;
        p.rsiMax = 70.0;
        p.volumeRatioMin = 1.0;
        p.requireAboveMa60 = true;
        p.marketFilter = false;
        p.sizing = EQUAL;
        p.riskPerTradePct = 1.0;
        p.stopLossPct = 8.0;
        p.trailingStopPct = 0.0;
        p.partialTakeProfitPct = 0.0;
        p.takeProfitPct = 20.0;
        p.maxHoldDays = 30;
        p.exitOnMaCrossDown = true;
        p.maxPositions = 5;
        p.maxWeightPct = 25.0;
        return p;
    }

    public static StrategyParams parse(String json) {
        StrategyParams p = JSON.parseObject(json, StrategyParams.class);
        return p == null ? defaults() : p.fillDefaults();
    }

    /**
     * Missing fields take the version-1 value (older versions keep their behavior; a version without a
     * universe mode used its hand-picked list).
     */
    public StrategyParams fillDefaults() {
        StrategyParams d = defaults();
        universeMode = Objects.requireNonNullElse(universeMode, MANUAL);
        universeSize = Objects.requireNonNullElse(universeSize, d.universeSize);
        if (universe == null || universe.isEmpty()) {
            universe = d.universe;
        }
        entryType = Objects.requireNonNullElse(entryType, d.entryType);
        maFast = Objects.requireNonNullElse(maFast, d.maFast);
        maSlow = Objects.requireNonNullElse(maSlow, d.maSlow);
        crossWithinDays = Objects.requireNonNullElse(crossWithinDays, d.crossWithinDays);
        breakoutDays = Objects.requireNonNullElse(breakoutDays, d.breakoutDays);
        rsiMin = Objects.requireNonNullElse(rsiMin, d.rsiMin);
        rsiMax = Objects.requireNonNullElse(rsiMax, d.rsiMax);
        volumeRatioMin = Objects.requireNonNullElse(volumeRatioMin, d.volumeRatioMin);
        requireAboveMa60 = Objects.requireNonNullElse(requireAboveMa60, d.requireAboveMa60);
        marketFilter = Objects.requireNonNullElse(marketFilter, d.marketFilter);
        sizing = Objects.requireNonNullElse(sizing, d.sizing);
        riskPerTradePct = Objects.requireNonNullElse(riskPerTradePct, d.riskPerTradePct);
        stopLossPct = Objects.requireNonNullElse(stopLossPct, d.stopLossPct);
        trailingStopPct = Objects.requireNonNullElse(trailingStopPct, d.trailingStopPct);
        partialTakeProfitPct = Objects.requireNonNullElse(partialTakeProfitPct, d.partialTakeProfitPct);
        takeProfitPct = Objects.requireNonNullElse(takeProfitPct, d.takeProfitPct);
        maxHoldDays = Objects.requireNonNullElse(maxHoldDays, d.maxHoldDays);
        exitOnMaCrossDown = Objects.requireNonNullElse(exitOnMaCrossDown, d.exitOnMaCrossDown);
        maxPositions = Objects.requireNonNullElse(maxPositions, d.maxPositions);
        maxWeightPct = Objects.requireNonNullElse(maxWeightPct, d.maxWeightPct);
        return this;
    }

    /** This version with the proposal's known fields applied; the universe is normalized. */
    public StrategyParams merge(JSONObject patch) {
        JSONObject base = JSONObject.from(this);
        if (patch != null) {
            for (String key : patch.keySet()) {
                if (FIELDS.contains(key)) {
                    base.put(key, patch.get(key));
                }
            }
        }
        StrategyParams merged = base.toJavaObject(StrategyParams.class).fillDefaults();
        merged.universe = normalizeUniverse(merged.universe);
        merged.entryType = upper(merged.entryType);
        merged.universeMode = upper(merged.universeMode);
        merged.sizing = upper(merged.sizing);
        return merged;
    }

    private static String upper(String s) {
        return s == null ? null : s.trim().toUpperCase(Locale.ROOT);
    }

    static List<String> normalizeUniverse(List<String> codes) {
        Set<String> out = new LinkedHashSet<>();
        if (codes != null) {
            for (String code : codes) {
                out.add(AShareRules.normalizeCode(code));
            }
        }
        return new ArrayList<>(out);
    }

    /** Copy of this version. */
    public StrategyParams copy() {
        return parse(toJson());
    }

    public boolean autoUniverse() {
        return AUTO.equals(universeMode);
    }

    public boolean usesCustomEntry() {
        return CUSTOM.equals(entryType);
    }

    public boolean hasCustomExit() {
        return customExit != null && !customExit.isBlank();
    }

    public boolean usesMaCross() {
        return MA_CROSS.equals(entryType) || ANY.equals(entryType);
    }

    public boolean usesBreakout() {
        return BREAKOUT.equals(entryType) || ANY.equals(entryType);
    }

    public boolean usesMacd() {
        return MACD.equals(entryType) || ANY.equals(entryType);
    }

    /**
     * Money for a new position: EQUAL puts {@code maxWeightPct} of equity in it; RISK sizes so that the
     * stop loss costs {@code riskPerTradePct} of equity, never more than {@code maxWeightPct}.
     */
    public double positionBudget(double equity) {
        double cap = equity * maxWeightPct / 100;
        if (!RISK.equals(sizing) || stopLossPct <= 0) {
            return cap;
        }
        return Math.min(cap, equity * riskPerTradePct / stopLossPct);
    }

    /** Bounds that keep evolution in a sane range; empty when valid. */
    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        int n = universe == null ? 0 : universe.size();
        if (!UNIVERSE_MODES.contains(universeMode)) {
            errors.add("universeMode 只能是 AUTO / MANUAL");
        } else if (autoUniverse()) {
            range(errors, "universeSize", universeSize, 10, 50);
        } else if (n < MIN_UNIVERSE || n > MAX_UNIVERSE) {
            errors.add("universe 需为 " + MIN_UNIVERSE + "–" + MAX_UNIVERSE + " 只股票，当前 " + n);
        }
        if (!ENTRY_TYPES.contains(entryType)) {
            errors.add("entryType 只能是 MA_CROSS / BREAKOUT / MACD / ANY / CUSTOM");
        }
        if (CUSTOM.equals(entryType)) {
            String bad = RuleExpression.check(customEntry);
            if (bad != null) {
                errors.add("customEntry：" + bad);
            }
        }
        if (hasCustomExit()) {
            String bad = RuleExpression.check(customExit);
            if (bad != null) {
                errors.add("customExit：" + bad);
            }
        }
        if (!SIZINGS.contains(sizing)) {
            errors.add("sizing 只能是 EQUAL / RISK");
        }
        range(errors, "maFast", maFast, 3, 20);
        range(errors, "maSlow", maSlow, 10, 120);
        if (maFast != null && maSlow != null && maSlow < maFast + 3) {
            errors.add("maSlow 需比 maFast 至少大 3");
        }
        range(errors, "crossWithinDays", crossWithinDays, 1, 20);
        range(errors, "breakoutDays", breakoutDays, 10, 60);
        range(errors, "rsiMin", rsiMin, 10, 60);
        range(errors, "rsiMax", rsiMax, 50, 90);
        if (rsiMin != null && rsiMax != null && rsiMax - rsiMin < 10) {
            errors.add("rsiMax 需比 rsiMin 至少大 10");
        }
        range(errors, "volumeRatioMin", volumeRatioMin, 0.5, 3);
        range(errors, "riskPerTradePct", riskPerTradePct, 0.3, 3);
        range(errors, "stopLossPct", stopLossPct, 3, 20);
        if (trailingStopPct == null || (trailingStopPct != 0 && (trailingStopPct < 3 || trailingStopPct > 25))) {
            errors.add("trailingStopPct 需为 0（关闭）或 3–25，当前 " + trailingStopPct);
        }
        range(errors, "takeProfitPct", takeProfitPct, 5, 60);
        if (partialTakeProfitPct == null
                || (partialTakeProfitPct != 0 && (partialTakeProfitPct < 3 || partialTakeProfitPct > 40))) {
            errors.add("partialTakeProfitPct 需为 0（关闭）或 3–40，当前 " + partialTakeProfitPct);
        } else if (partialTakeProfitPct != 0 && takeProfitPct != null && partialTakeProfitPct >= takeProfitPct) {
            errors.add("partialTakeProfitPct 需小于 takeProfitPct");
        }
        range(errors, "maxHoldDays", maxHoldDays, 3, 120);
        range(errors, "maxPositions", maxPositions, 1, 10);
        range(errors, "maxWeightPct", maxWeightPct, 5, 50);
        return errors;
    }

    private static void range(List<String> errors, String name, Number value, double min, double max) {
        if (value == null || value.doubleValue() < min || value.doubleValue() > max) {
            errors.add(name + " 需在 " + fmt(min) + "–" + fmt(max) + " 之间，当前 " + value);
        }
    }

    /** Fields that differ from {@code other}, e.g. {@code rsiMax 70→65}. */
    public String diff(StrategyParams other) {
        JSONObject a = JSONObject.from(other);
        JSONObject b = JSONObject.from(this);
        List<String> parts = new ArrayList<>();
        boolean auto = autoUniverse() && other.autoUniverse();
        boolean manual = !autoUniverse() && !other.autoUniverse();
        for (String key : FIELD_ORDER) {
            // The list does not matter in AUTO mode, nor the size in MANUAL mode.
            if (("universe".equals(key) && auto) || ("universeSize".equals(key) && manual)) {
                continue;
            }
            Object from = a.get(key);
            Object to = b.get(key);
            if (!sameValue(from, to)) {
                parts.add("universe".equals(key)
                        ? "universe " + sizeOf(from) + "→" + sizeOf(to) + " 只"
                        : key + " " + from + "→" + to);
            }
        }
        return parts.isEmpty() ? "无变化" : String.join("，", parts);
    }

    private static boolean sameValue(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return Double.compare(x.doubleValue(), y.doubleValue()) == 0;
        }
        return Objects.equals(String.valueOf(a), String.valueOf(b));
    }

    /** Same strategy (numbers compared by value). */
    public boolean sameAs(StrategyParams other) {
        return "无变化".equals(diff(other));
    }

    private static String sizeOf(Object list) {
        return list instanceof List<?> l ? String.valueOf(l.size()) : "?";
    }

    /** Readable one-paragraph description. */
    public String describe() {
        List<String> entries = new ArrayList<>();
        if (usesCustomEntry()) {
            entries.add("自定义规则「" + customEntry + "」");
        }
        if (usesMaCross()) {
            entries.add(String.format(Locale.ROOT, "MA%d %d 日内上穿 MA%d", maFast, crossWithinDays, maSlow));
        }
        if (usesBreakout()) {
            entries.add("收盘突破前 " + breakoutDays + " 日最高价");
        }
        if (usesMacd()) {
            entries.add("MACD " + crossWithinDays + " 日内金叉");
        }
        List<String> exits = new ArrayList<>();
        exits.add("止损 " + fmt(stopLossPct) + "%");
        if (trailingStopPct != null && trailingStopPct > 0) {
            exits.add("移动止损（从最高价回撤 " + fmt(trailingStopPct) + "%）");
        }
        if (partialTakeProfitPct != null && partialTakeProfitPct > 0) {
            exits.add("+" + fmt(partialTakeProfitPct) + "% 先卖一半");
        }
        exits.add("止盈 " + fmt(takeProfitPct) + "%");
        exits.add("最长持有 " + maxHoldDays + " 个交易日");
        if (Boolean.TRUE.equals(exitOnMaCrossDown)) {
            exits.add("均线死叉");
        }
        if (hasCustomExit()) {
            exits.add("自定义离场「" + customExit + "」");
        }
        String size = RISK.equals(sizing)
                ? "按风险定仓位（每笔止损最多亏总资产 " + fmt(riskPerTradePct) + "%），单只≤" + fmt(maxWeightPct) + "%"
                : "等权，单只≤" + fmt(maxWeightPct) + "%";
        String filters = usesCustomEntry() ? ""
                : String.format(Locale.ROOT, "，RSI14 %s–%s，量比≥%s%s", fmt(rsiMin), fmt(rsiMax), fmt(volumeRatioMin),
                        Boolean.TRUE.equals(requireAboveMa60) ? "，收盘站上 MA60" : "");
        String pool = autoUniverse()
                ? "股票池：规则筛选前 " + universeSize + " 只（每月更新）"
                : "股票池 " + (universe == null ? 0 : universe.size()) + " 只（手选）";
        return String.format(Locale.ROOT, "%s；入场：%s%s%s；离场：%s；仓位：最多 %d 只，%s", pool,
                String.join(" 或 ", entries), filters,
                Boolean.TRUE.equals(marketFilter) ? "，上证指数在 MA20 上方" : "", String.join("、", exits), maxPositions,
                size);
    }

    private static String fmt(Number v) {
        if (v == null) {
            return "-";
        }
        double d = v.doubleValue();
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    public String toJson() {
        return JSON.toJSONString(this);
    }
}
