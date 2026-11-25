package com.iflytek.astron.console.hub.service.ashare;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.iflytek.astron.console.hub.entity.ashare.RuleProgram;
import com.iflytek.astron.console.hub.mapper.ashare.RuleProgramMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Lets the system invent trading rules instead of only tuning numbers, following the shape of
 * FunSearch and AlphaEvolve: a library of rules that have been scored, a model used as the mutation
 * operator, and an automatic evaluator that decides what survives.
 * <p>
 * One round: pick an island, show the agent its best rules together with their scores and the
 * directions already known to fail, ask for new rules in the {@link RuleExpression} language,
 * backtest each one through the usual gate, and store what it scored. Rules live in separate
 * islands so that one early winner cannot flatten the population; the weakest islands are
 * periodically reseeded from the best, which is how FunSearch keeps exploring.
 * <p>
 * Two things keep this honest. Every rule is evaluated by the same gate as any other candidate, so
 * a rule that cannot beat the active strategy is recorded and dropped, not adopted. And every
 * evaluation counts as a trial, so inventing more aggressively raises the deflated-Sharpe bar for
 * everything, which is the price of exploring and is meant to be visible.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuleEvolutionService {

    static final int ISLANDS = 4;
    /** Rules shown to the agent as inspiration, best first. */
    static final int SAMPLE_SIZE = 4;
    /** Rules requested per round; one model call per round. */
    static final int RULES_PER_ROUND = 6;
    static final int MAX_ROUNDS = 3;
    /** A rule that trades less than this out of sample cannot be judged. */
    static final int MIN_TRADES = 10;
    private static final Duration BUDGET = Duration.ofMinutes(4);

    private final RuleProgramMapper programMapper;
    private final StrategyService strategyService;
    private final LessonService lessonService;
    private final AShareReviewService reviewService;

    /**
     * Starting population: the built-in signals written as rules, so the agent has something concrete
     * to read and improve rather than a blank page. Inserted once per user.
     */
    static final Map<String, String> SEEDS = new LinkedHashMap<>();

    static {
        SEEDS.put("cross_up(ma(close,5), ma(close,20)) and close > ma(close,60)", "均线金叉且站上 MA60（内置 MA_CROSS 的规则写法）");
        SEEDS.put("close > highest(high,20) and volratio(5) > 1.2", "放量突破 20 日新高（内置 BREAKOUT 的规则写法）");
        SEEDS.put("cross_up(macd_dif(), macd_dea()) and rsi(close,14) < 70", "MACD 金叉且未超买（内置 MACD 的规则写法）");
        SEEDS.put("rsi(close,6) < 25 and close > ma(close,120)", "长期上升趋势中的短线超卖（均值回归）");
    }

    // ------------------------------------------------------------------ library

    /** Inserts the seed rules for a user if the library is empty; returns how many were added. */
    public int seed(String uid) {
        long existing = programMapper.selectCount(Wrappers.lambdaQuery(RuleProgram.class)
                .eq(RuleProgram::getUid, uid));
        if (existing > 0) {
            return 0;
        }
        int added = 0;
        int i = 0;
        for (var e : SEEDS.entrySet()) {
            RuleProgram row = new RuleProgram();
            row.setUid(uid);
            row.setIsland(i++ % ISLANDS);
            row.setKind(RuleProgram.ENTRY);
            row.setExpression(e.getKey());
            row.setExprHash(hash(normalize(e.getKey())));
            row.setGeneration(0);
            row.setIdea(e.getValue());
            row.setStatus(RuleProgram.SCORED);
            row.setPassedGate(0);
            try {
                programMapper.insert(row);
                added++;
            } catch (RuntimeException ex) {
                log.info("Seed rule already present, uid={}, msg={}", uid, ex.getMessage());
            }
        }
        log.info("Rule library seeded, uid={}, added={}", uid, added);
        return added;
    }

    /** The island's best scored rules, plus one random other for variety. */
    List<RuleProgram> sample(String uid, String kind, int island, int k, Random random) {
        List<RuleProgram> scored = programMapper.selectList(Wrappers.lambdaQuery(RuleProgram.class)
                .eq(RuleProgram::getUid, uid)
                .eq(RuleProgram::getKind, kind)
                .eq(RuleProgram::getIsland, island)
                .eq(RuleProgram::getStatus, RuleProgram.SCORED)
                .orderByDesc(RuleProgram::getOosScore)
                .last("LIMIT " + Math.max(1, k * 3)));
        if (scored.isEmpty()) {
            // A fresh island borrows from the whole library so it still starts from something real.
            scored = programMapper.selectList(Wrappers.lambdaQuery(RuleProgram.class)
                    .eq(RuleProgram::getUid, uid)
                    .eq(RuleProgram::getKind, kind)
                    .eq(RuleProgram::getStatus, RuleProgram.SCORED)
                    .orderByDesc(RuleProgram::getOosScore)
                    .last("LIMIT " + Math.max(1, k)));
        }
        List<RuleProgram> out = new ArrayList<>(scored.subList(0, Math.min(k, scored.size())));
        if (scored.size() > out.size()) {
            RuleProgram extra = scored.get(out.size() + random.nextInt(scored.size() - out.size()));
            if (out.stream().noneMatch(p -> p.getId().equals(extra.getId()))) {
                out.add(extra);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ one evolution run

    /**
     * Runs {@code rounds} rounds of invent-and-measure. Returns what was tried and what survived; a
     * rule that passes the gate becomes a TRIAL version through the normal proposal path, so it still
     * has to win the forward test before it trades.
     */
    public JSONObject evolve(String uid, Integer requestedRounds, String trigger) {
        seed(uid);
        int rounds = Math.max(1, Math.min(MAX_ROUNDS, requestedRounds == null ? 2 : requestedRounds));
        long start = System.currentTimeMillis();
        Random random = new Random(System.nanoTime());
        JSONArray tried = new JSONArray();
        int invented = 0;
        int invalid = 0;
        int scored = 0;
        JSONObject adopted = null;
        for (int r = 0; r < rounds; r++) {
            int island = random.nextInt(ISLANDS);
            List<RuleProgram> parents = sample(uid, RuleProgram.ENTRY, island, SAMPLE_SIZE, random);
            String prompt = prompt(uid, parents, RULES_PER_ROUND);
            String answer = reviewService.askReviewAgent(uid, prompt, BUDGET);
            List<Candidate> candidates = parseRules(answer);
            if (candidates.isEmpty()) {
                log.info("Rule evolution round produced nothing, uid={}, round={}, answerChars={}", uid, r,
                        StringUtils.length(answer));
                continue;
            }
            for (Candidate c : candidates) {
                invented++;
                String bad = RuleExpression.check(c.expression());
                if (bad != null) {
                    invalid++;
                    save(uid, island, c, parents, RuleProgram.INVALID, null, bad);
                    tried.add(new JSONObject().fluentPut("expression", c.expression())
                            .fluentPut("status", "语法不合法")
                            .fluentPut("note", bad));
                    continue;
                }
                if (exists(uid, c.expression())) {
                    tried.add(new JSONObject().fluentPut("expression", c.expression()).fluentPut("status", "已试过"));
                    continue;
                }
                JSONObject result = measure(uid, c, island, parents);
                tried.add(result);
                if (result.getBooleanValue("scored")) {
                    scored++;
                }
                if (result.getBooleanValue("passed") && adopted == null) {
                    adopted = result;
                }
            }
        }
        resetWeakestIsland(uid, random);
        String meta = String.format(Locale.ROOT,
                "规则进化（%s）：生成 %d 条新规则，语法不合法 %d 条，完成回测 %d 条，通过闸门 %d 条。%s",
                trigger, invented, invalid, scored, adopted == null ? 0 : 1,
                adopted == null ? "参数和规则都没能给出可信的改进，当前版本不变。"
                        : "最好的一条已进入前进测试：" + adopted.getString("expression"));
        lessonService.recordMeta(uid, meta, null, "RULE_EVOLUTION");
        log.info("Rule evolution done, uid={}, trigger={}, invented={}, invalid={}, scored={}, passed={}, costMs={}",
                uid, trigger, invented, invalid, scored, adopted == null ? 0 : 1, System.currentTimeMillis() - start);
        return new JSONObject()
                .fluentPut("rounds", rounds)
                .fluentPut("invented", invented)
                .fluentPut("invalid", invalid)
                .fluentPut("scored", scored)
                .fluentPut("tried", tried)
                .fluentPut("trial", adopted)
                .fluentPut("recordedLesson", meta);
    }

    /** A rule the agent proposed. */
    record Candidate(String expression, String idea) {}

    /**
     * Backtests one rule as a CUSTOM entry on the active version, through the same gate as any other
     * candidate, and records what it scored.
     */
    private JSONObject measure(String uid, Candidate c, int island, List<RuleProgram> parents) {
        JSONObject patch = new JSONObject()
                .fluentPut("entryType", StrategyParams.CUSTOM)
                .fluentPut("customEntry", c.expression());
        JSONObject out = new JSONObject().fluentPut("expression", c.expression()).fluentPut("idea", c.idea());
        try {
            JSONObject backtest = strategyService.backtest(uid, patch);
            JSONObject oos = backtest.getJSONObject("result").getJSONObject("outOfSample");
            double score = oos.getDoubleValue("score");
            int trades = oos.getIntValue("trades");
            double dsr = backtest.getDoubleValue("deflatedSharpe");
            List<String> reasons = backtest.getJSONArray("gateCheck") == null ? List.of()
                    : backtest.getJSONArray("gateCheck").toJavaList(String.class);
            boolean judged = trades >= MIN_TRADES;
            RuleProgram saved = save(uid, island, c, parents, judged ? RuleProgram.SCORED : RuleProgram.UNTESTABLE,
                    oos, judged ? null : "样本外只成交 " + trades + " 笔，无法判断");
            if (saved != null) {
                saved.setDeflatedSharpe(dsr);
                saved.setPassedGate(reasons.isEmpty() && judged ? 1 : 0);
                programMapper.updateById(saved);
            }
            out.fluentPut("scored", judged)
                    .fluentPut("oosScore", score)
                    .fluentPut("oosReturnPct", oos.getDoubleValue("returnPct"))
                    .fluentPut("trades", trades)
                    .fluentPut("deflatedSharpe", dsr)
                    .fluentPut("passed", false)
                    .fluentPut("reasons", reasons);
            if (!judged) {
                out.put("status", "成交太少，无法判断");
                return out;
            }
            if (reasons.isEmpty()) {
                // Worth a forward test: go through the normal proposal path so the usual record is kept.
                JSONObject proposed = strategyService.propose(uid, patch,
                        "规则进化：" + StringUtils.abbreviate(c.idea(), 200) + "（自动生成并通过回测闸门）");
                out.fluentPut("passed", true)
                        .fluentPut("version", proposed.get("version"))
                        .fluentPut("status", "通过闸门，进入前进测试");
            } else {
                out.put("status", "未通过闸门");
            }
            return out;
        } catch (RuntimeException e) {
            log.info("Rule measurement failed, uid={}, expr={}, errorType={}, msg={}", uid, c.expression(),
                    e.getClass().getSimpleName(), e.getMessage());
            save(uid, island, c, parents, RuleProgram.UNTESTABLE, null, e.getMessage());
            return out.fluentPut("scored", false).fluentPut("status", "回测失败：" + e.getMessage());
        }
    }

    private RuleProgram save(String uid, int island, Candidate c, List<RuleProgram> parents, String status,
            JSONObject oos, String note) {
        try {
            RuleProgram row = new RuleProgram();
            row.setUid(uid);
            row.setIsland(island);
            row.setKind(RuleProgram.ENTRY);
            row.setExpression(StringUtils.abbreviate(c.expression(), 400));
            row.setExprHash(hash(normalize(c.expression())));
            row.setParentId(parents.isEmpty() ? null : parents.get(0).getId());
            row.setGeneration(parents.isEmpty() ? 1
                    : parents.stream().mapToInt(p -> p.getGeneration() == null ? 0 : p.getGeneration()).max().orElse(0)
                            + 1);
            row.setIdea(StringUtils.abbreviate(c.idea(), 500));
            row.setStatus(status);
            row.setPassedGate(0);
            row.setNote(StringUtils.abbreviate(note, 500));
            if (oos != null) {
                row.setOosScore(oos.getDoubleValue("score"));
                row.setOosReturnPct(oos.getDoubleValue("returnPct"));
                row.setOosMaxDrawdownPct(oos.getDoubleValue("maxDrawdownPct"));
                row.setTrades(oos.getIntValue("trades"));
            }
            programMapper.insert(row);
            return row;
        } catch (RuntimeException e) {
            log.info("Rule not saved (likely a duplicate), uid={}, msg={}", uid, e.getMessage());
            return null;
        }
    }

    private boolean exists(String uid, String expression) {
        return programMapper.selectCount(Wrappers.lambdaQuery(RuleProgram.class)
                .eq(RuleProgram::getUid, uid)
                .eq(RuleProgram::getKind, RuleProgram.ENTRY)
                .eq(RuleProgram::getExprHash, hash(normalize(expression)))) > 0;
    }

    /**
     * Reseeds the weakest island from the library's best rules. Without this the islands converge on
     * small variations of one idea and the search stops exploring.
     */
    void resetWeakestIsland(String uid, Random random) {
        Map<Integer, Double> best = new LinkedHashMap<>();
        for (int i = 0; i < ISLANDS; i++) {
            List<RuleProgram> top = programMapper.selectList(Wrappers.lambdaQuery(RuleProgram.class)
                    .eq(RuleProgram::getUid, uid)
                    .eq(RuleProgram::getStatus, RuleProgram.SCORED)
                    .eq(RuleProgram::getIsland, i)
                    .isNotNull(RuleProgram::getOosScore)
                    .orderByDesc(RuleProgram::getOosScore)
                    .last("LIMIT 1"));
            best.put(i, top.isEmpty() ? Double.NEGATIVE_INFINITY : top.get(0).getOosScore());
        }
        // Only worth doing once every island has something to compare.
        if (best.values().stream().anyMatch(v -> v == Double.NEGATIVE_INFINITY)) {
            return;
        }
        int weakest = best.entrySet().stream().min(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(0);
        int strongest = best.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(0);
        if (weakest == strongest) {
            return;
        }
        List<RuleProgram> source = programMapper.selectList(Wrappers.lambdaQuery(RuleProgram.class)
                .eq(RuleProgram::getUid, uid)
                .eq(RuleProgram::getStatus, RuleProgram.SCORED)
                .eq(RuleProgram::getIsland, strongest)
                .orderByDesc(RuleProgram::getOosScore)
                .last("LIMIT 2"));
        int moved = 0;
        for (RuleProgram p : source) {
            RuleProgram patch = new RuleProgram();
            patch.setId(p.getId());
            patch.setIsland(weakest);
            programMapper.updateById(patch);
            moved++;
        }
        log.info("Island reseeded, uid={}, weakest={}, from={}, moved={}", uid, weakest, strongest, moved);
    }

    // ------------------------------------------------------------------ the prompt

    /**
     * The FunSearch-style prompt: the grammar, a few scored rules from this island to improve on, and
     * the directions already known to fail, so the model spends its attempt on new ground.
     */
    String prompt(String uid, List<RuleProgram> parents, int wanted) {
        StringBuilder sb = new StringBuilder();
        sb.append("【系统任务，用户不在线，不要提问，不要调用任何工具】你是量化策略的规则设计者。请发明 ")
                .append(wanted)
                .append(" 条新的 A 股【入场】规则，用下面这门小语言表达。\n\n");
        sb.append("## 语法\n")
                .append("- 序列：open high low close volume index（index 为上证指数收盘价）\n")
                .append("- 函数：ma(x,n) ema(x,n) rsi(x,n) highest(x,n) lowest(x,n) ref(x,n) std(x,n) roc(x,n) "
                        + "atr(n) volratio(n) macd_dif() macd_dea() cross_up(a,b) cross_down(a,b) abs(x) min(a,b) max(a,b)\n")
                .append("- 运算：+ - * / > < >= <= and or not 和括号；n 为 1–250 的整数\n")
                .append("- highest/lowest 取当前这根之前的 n 根；cross_up(a,b) 表示本根发生上穿\n")
                .append("- 长度不超过 ")
                .append(RuleExpression.MAX_LENGTH)
                .append(" 字符，只能用上面列出的名字\n\n");
        sb.append("## 已经打过分的规则（样本外夏普越高越好）\n");
        if (parents.isEmpty()) {
            sb.append("（本岛还没有记录，请自由发挥）\n");
        } else {
            for (RuleProgram p : parents) {
                sb.append("- `").append(p.getExpression()).append("`");
                if (p.getOosScore() != null) {
                    sb.append(String.format(Locale.ROOT, " → 样本外夏普 %.2f，收益 %.2f%%，成交 %d 笔", p.getOosScore(),
                            p.getOosReturnPct() == null ? 0 : p.getOosReturnPct(),
                            p.getTrades() == null ? 0 : p.getTrades()));
                } else {
                    sb.append(" → 还没有分数");
                }
                if (StringUtils.isNotBlank(p.getIdea())) {
                    sb.append("（思路：").append(StringUtils.abbreviate(p.getIdea(), 80)).append("）");
                }
                sb.append('\n');
            }
        }
        List<RuleProgram> failures = programMapper.selectList(Wrappers.lambdaQuery(RuleProgram.class)
                .eq(RuleProgram::getUid, uid)
                .eq(RuleProgram::getKind, RuleProgram.ENTRY)
                .orderByAsc(RuleProgram::getOosScore)
                .last("LIMIT 5"));
        if (!failures.isEmpty()) {
            sb.append("\n## 表现最差的规则（不要重复这些方向）\n");
            for (RuleProgram p : failures) {
                sb.append("- `").append(p.getExpression()).append("`");
                if (p.getOosScore() != null) {
                    sb.append(String.format(Locale.ROOT, " → 夏普 %.2f", p.getOosScore()));
                } else if (StringUtils.isNotBlank(p.getNote())) {
                    sb.append(" → ").append(StringUtils.abbreviate(p.getNote(), 60));
                }
                sb.append('\n');
            }
        }
        sb.append("\n## 要求\n")
                .append("1. 每条规则必须是一个**结构上的新想法**，不要只改数字（改数字由另一套搜索负责）。\n")
                .append("2. 想法要能说出经济含义，例如趋势确认、波动率压缩后突破、量价背离、相对大盘强弱、均值回归。\n")
                .append("3. 条件不要太苛刻：样本外至少要成交 ")
                .append(MIN_TRADES)
                .append(" 笔，否则无法评估。\n")
                .append("4. 彼此之间要有差异，不要 6 条都是均线变体。\n")
                .append("5. 只输出一个 JSON 数组，不要任何解释文字、不要 Markdown 代码块：\n")
                .append("[{\"expression\":\"...\",\"idea\":\"一句话说明经济含义\"}]\n");
        return sb.toString();
    }

    /** Pulls the rules out of the model's answer, tolerating code fences and stray text. */
    static List<Candidate> parseRules(String answer) {
        if (StringUtils.isBlank(answer)) {
            return List.of();
        }
        int from = answer.indexOf('[');
        int to = answer.lastIndexOf(']');
        if (from < 0 || to <= from) {
            return List.of();
        }
        List<Candidate> out = new ArrayList<>();
        try {
            JSONArray arr = JSON.parseArray(answer.substring(from, to + 1));
            for (int i = 0; i < arr.size(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (o == null) {
                    continue;
                }
                String expr = StringUtils.trimToNull(o.getString("expression"));
                if (expr == null) {
                    continue;
                }
                out.add(new Candidate(expr, StringUtils.defaultIfBlank(o.getString("idea"), "未说明")));
            }
        } catch (RuntimeException e) {
            log.info("Rule answer was not usable JSON: {}", StringUtils.abbreviate(answer, 160));
            return List.of();
        }
        return out;
    }

    // ------------------------------------------------------------------ reading the library

    /** The library: the best rules, the islands and what has been tried. */
    public JSONObject library(String uid) {
        seed(uid);
        List<RuleProgram> all = programMapper.selectList(Wrappers.lambdaQuery(RuleProgram.class)
                .eq(RuleProgram::getUid, uid)
                .orderByDesc(RuleProgram::getOosScore)
                .last("LIMIT 200"));
        JSONArray rows = new JSONArray();
        for (RuleProgram p : all) {
            rows.add(new JSONObject()
                    .fluentPut("id", p.getId())
                    .fluentPut("island", p.getIsland())
                    .fluentPut("generation", p.getGeneration())
                    .fluentPut("expression", p.getExpression())
                    .fluentPut("idea", p.getIdea())
                    .fluentPut("oosScore", p.getOosScore())
                    .fluentPut("oosReturnPct", p.getOosReturnPct())
                    .fluentPut("trades", p.getTrades())
                    .fluentPut("deflatedSharpe", p.getDeflatedSharpe())
                    .fluentPut("passedGate", p.getPassedGate() != null && p.getPassedGate() == 1)
                    .fluentPut("status", p.getStatus())
                    .fluentPut("note", p.getNote())
                    .fluentPut("createTime", String.valueOf(p.getCreateTime())));
        }
        Map<Integer, Long> perIsland = new LinkedHashMap<>();
        for (int i = 0; i < ISLANDS; i++) {
            int island = i;
            perIsland.put(i, all.stream().filter(p -> island == p.getIsland()).count());
        }
        return new JSONObject()
                .fluentPut("islands", ISLANDS)
                .fluentPut("perIsland", perIsland)
                .fluentPut("total", all.size())
                .fluentPut("scored", all.stream().filter(p -> RuleProgram.SCORED.equals(p.getStatus())).count())
                .fluentPut("best", all.stream()
                        .filter(p -> p.getOosScore() != null)
                        .max(Comparator.comparingDouble(RuleProgram::getOosScore))
                        .map(RuleProgram::getExpression)
                        .orElse(null))
                .fluentPut("rules", rows)
                .fluentPut("note", "规则由智能体发明、系统自动回测；每条都要过同一个闸门（含折减夏普），通过后还要做前进测试。"
                        + "发明得越多，折减门槛越高，这是探索的代价。");
    }

    /** Lowercased and stripped of spaces, so trivially different spellings count as the same rule. */
    static String normalize(String expression) {
        return StringUtils.defaultString(expression).toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    static String hash(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(text.hashCode());
        }
    }
}
