package com.iflytek.astron.console.hub.service.ashare;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * How good the data behind an analysis is, so that a confident-sounding conclusion cannot rest on
 * missing numbers. Every block of an analysis (quote, daily bars, indicators, money flow,
 * announcements, valuation) gets a {@link Status}; the core blocks weigh most in the score. The
 * result is turned into a short section of the agent's prompt that states what is missing and what
 * must therefore not be claimed.
 */
public final class DataQuality {

    /** Blocks that an A-share analysis is built on. Core blocks decide whether a call is allowed. */
    public enum Block {

        QUOTE("实时报价", 25),
        DAILY_BARS("日线历史", 25),
        TECHNICAL("技术指标", 20),
        FUND_FLOW("资金流向", 10),
        NEWS("公告与资讯", 10),
        VALUATION("估值与市值", 10);

        private final String label;
        private final int weight;

        Block(String label, int weight) {
            this.label = label;
            this.weight = weight;
        }

        public String label() {
            return label;
        }

        public int weight() {
            return weight;
        }

        /** A missing core block means no high-confidence buy or sell call. */
        public boolean core() {
            return this == QUOTE || this == DAILY_BARS || this == TECHNICAL;
        }
    }

    /** What happened when the block was fetched. */
    public enum Status {

        FRESH("可用", 1.0),
        /** Usable but older than expected, e.g. the last bar is not the latest trading day. */
        STALE("数据偏旧", 0.6),
        /** The endpoint answered, but with nothing for this stock. */
        MISSING("缺失", 0.0),
        /** The request itself failed (endpoint unreachable or changed). */
        FETCH_FAILED("接口取不到", 0.0),
        /** The market does not provide it for this instrument (not a problem). */
        NOT_SUPPORTED("该标的不适用", -1);

        private final String label;
        private final double factor;

        Status(String label, double factor) {
            this.label = label;
            this.factor = factor;
        }

        public String label() {
            return label;
        }

        /** Share of the block's weight that counts as available; negative means "leave out". */
        public double factor() {
            return factor;
        }
    }

    /** Overall level of the data behind one analysis. */
    public enum Level {
        GOOD("好"), FAIR("一般"), POOR("差");

        private final String label;

        Level(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final Map<Block, Status> blocks = new LinkedHashMap<>();
    private final Map<Block, String> notes = new LinkedHashMap<>();

    public DataQuality() {}

    public DataQuality put(Block block, Status status) {
        return put(block, status, null);
    }

    /** Records a block's status with an optional note (e.g. which day the data stops at). */
    public DataQuality put(Block block, Status status, String note) {
        blocks.put(block, status);
        if (note != null) {
            notes.put(block, note);
        }
        return this;
    }

    public Status status(Block block) {
        return blocks.getOrDefault(block, Status.MISSING);
    }

    /**
     * Usable share of the weighted blocks, 0–100. Blocks that do not apply are left out of both the
     * numerator and the denominator, so an instrument without money flow is not punished for it.
     */
    public int score() {
        double total = 0;
        double have = 0;
        for (Block b : Block.values()) {
            Status s = blocks.get(b);
            if (s == null || s.factor() < 0) {
                continue;
            }
            total += b.weight();
            have += b.weight() * s.factor();
        }
        return total <= 0 ? 0 : (int) Math.round(have / total * 100);
    }

    public Level level() {
        if (coreDegraded()) {
            return score() >= 60 ? Level.FAIR : Level.POOR;
        }
        int score = score();
        return score >= 85 ? Level.GOOD : score >= 60 ? Level.FAIR : Level.POOR;
    }

    /** A core block is missing, failed or stale: no high-confidence call. */
    public boolean coreDegraded() {
        return blocks.entrySet()
                .stream()
                .anyMatch(e -> e.getKey().core() && e.getValue() != Status.FRESH && e.getValue().factor() >= 0);
    }

    /** What the agent must not claim, given what is missing; empty when everything is fresh. */
    public List<String> limitations() {
        List<String> out = new ArrayList<>();
        for (Block b : Block.values()) {
            Status s = blocks.get(b);
            if (s == null || s == Status.FRESH || s.factor() < 0) {
                continue;
            }
            String note = notes.get(b);
            out.add(b.label() + "：" + s.label() + (note == null ? "" : "（" + note + "）"));
        }
        if (coreDegraded()) {
            out.add("核心数据不完整：不要给出高置信度的买卖结论，应说明受限之处并降低确定性");
        }
        return out;
    }

    public JSONObject toJson() {
        JSONObject out = new JSONObject()
                .fluentPut("score", score())
                .fluentPut("level", level().name())
                .fluentPut("levelLabel", level().label());
        JSONObject per = new JSONObject();
        blocks.forEach((b, s) -> per.put(b.name(), s.name()));
        out.put("blocks", per);
        JSONArray limits = new JSONArray();
        limitations().forEach(limits::add);
        out.put("limitations", limits);
        return out;
    }

    /** One line for a prompt or a notification. */
    public String summary() {
        return String.format(Locale.ROOT, "数据质量 %d 分（%s）", score(), level().label());
    }

    /**
     * The section added to an agent's prompt: the market phase rule and the data limitations. Returns
     * an empty string when the phase allows everything and no block is degraded.
     */
    public String promptSection(TradingClock.Phase phase) {
        List<String> limits = limitations();
        StringBuilder sb = new StringBuilder();
        sb.append("\n## 本次分析的数据与时点限制（必须遵守）\n");
        sb.append("- 市场时点：").append(phase.label()).append("。").append(phase.rule()).append('\n');
        sb.append("- ").append(summary()).append('\n');
        if (limits.isEmpty()) {
            sb.append("- 各数据块都正常，无额外限制。\n");
        } else {
            for (String l : limits) {
                sb.append("- ").append(l).append('\n');
            }
        }
        sb.append("- 缺失的数据块只说明「不知道」，不要当作利好或利空；需要的数据取不到时，先说明再给结论。\n");
        return sb.toString();
    }

    /**
     * Status of a daily-bar series: fresh when it ends on the latest settled trading day, stale when it
     * stops earlier, missing when there are no bars.
     */
    public static Status barStatus(List<LocalDate> dates, LocalDate latestSettled, int minBars) {
        if (dates == null || dates.isEmpty()) {
            return Status.MISSING;
        }
        if (dates.size() < minBars) {
            return Status.STALE;
        }
        LocalDate last = dates.get(dates.size() - 1);
        if (latestSettled == null) {
            return Status.FRESH;
        }
        return last.isBefore(latestSettled) ? Status.STALE : Status.FRESH;
    }
}
