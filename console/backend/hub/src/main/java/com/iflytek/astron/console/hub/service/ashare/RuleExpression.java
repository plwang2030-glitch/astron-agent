package com.iflytek.astron.console.hub.service.ashare;

import com.iflytek.astron.console.hub.service.ashare.EastmoneyClient.Bar;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small, safe rule language over daily bars, so that agents can invent new entry and exit rules
 * that the backtest gate then tests. Expressions are parsed into a tree and evaluated over whole
 * series; nothing is executed. Example:
 * {@code cross_up(ema(close,12), ema(close,26)) and rsi(close,14) < 65}.
 * <p>
 * Series: {@code open high low close volume index} (Shanghai Composite close). Functions (n = bars,
 * 1–250): {@code ma(x,n) ema(x,n) rsi(x,n) highest(x,n) lowest(x,n)} (over the n bars before the
 * current one), {@code ref(x,n)} (n bars ago), {@code std(x,n) roc(x,n)} (% change over n bars),
 * {@code atr(n) volratio(n) macd_dif() macd_dea()}, {@code cross_up(a,b) cross_down(a,b)} (crossed
 * on this bar), {@code abs(x) min(a,b) max(a,b)}. Operators: {@code + - * / > < >= <= and or not},
 * and parentheses. A condition is true where it evaluates to a value other than 0 (NaN is false).
 */
public final class RuleExpression {

    public static final int MAX_LENGTH = 400;
    static final int MAX_NODES = 80;
    static final int MAX_PERIOD = 250;
    static final Set<String> SERIES = Set.of("open", "high", "low", "close", "volume", "index");
    static final Set<String> FUNCTIONS = Set.of("ma", "ema", "rsi", "highest", "lowest", "ref", "std", "roc", "atr",
            "volratio", "macd_dif", "macd_dea", "cross_up", "cross_down", "abs", "min", "max");
    private static final Pattern TOKEN = Pattern.compile(
            "\\s*(?:(\\d+(?:\\.\\d+)?)|([A-Za-z_][A-Za-z_0-9]*)|(>=|<=|[-+*/()<>,]))");
    private static final Pattern NUMBER = Pattern.compile("(?<![A-Za-z_0-9.])(\\d+(?:\\.\\d+)?)");

    private final Node root;
    private final String source;

    private RuleExpression(Node root, String source) {
        this.root = root;
        this.source = source;
    }

    /**
     * @throws IllegalArgumentException with a readable message when the text is not a valid rule
     */
    public static RuleExpression parse(String text) {
        String src = text == null ? "" : text.trim();
        if (src.isEmpty()) {
            throw new IllegalArgumentException("规则表达式为空");
        }
        if (src.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("规则表达式不能超过 " + MAX_LENGTH + " 个字符");
        }
        Parser p = new Parser(tokenize(src));
        Node n = p.or();
        if (p.pos != p.tokens.size()) {
            throw new IllegalArgumentException("规则表达式在「" + p.tokens.get(p.pos) + "」附近有多余内容");
        }
        if (p.nodes > MAX_NODES) {
            throw new IllegalArgumentException("规则表达式太复杂（最多 " + MAX_NODES + " 个节点）");
        }
        return new RuleExpression(n, src);
    }

    /** {@code null} when valid, else the reason. */
    public static String check(String text) {
        try {
            parse(text);
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    public String source() {
        return source;
    }

    /** Bars the expression needs before it can be true (longest look-back). */
    public int lookback() {
        return root.lookback();
    }

    /**
     * Evaluates over a stock's bars.
     *
     * @param indexClose Shanghai Composite close by date, for {@code index}; may be empty
     */
    public double[] evaluate(List<Bar> bars, Map<java.time.LocalDate, Double> indexClose) {
        return root.eval(new Ctx(bars, indexClose));
    }

    /** True at bar {@code i}: a value other than 0, not NaN. */
    public static boolean truthy(double[] v, int i) {
        return i >= 0 && i < v.length && !Double.isNaN(v[i]) && v[i] != 0;
    }

    /**
     * The same rule with its {@code which}-th number (0-based) scaled by {@code factor}; used by the
     * search to try nearby thresholds. Integers stay integers within 1–250.
     */
    public static String scaleNumber(String text, int which, double factor) {
        Matcher m = NUMBER.matcher(text);
        StringBuilder out = new StringBuilder();
        int idx = 0;
        while (m.find()) {
            String repl = m.group(1);
            if (idx == which) {
                double v = Double.parseDouble(repl) * factor;
                boolean integer = !repl.contains(".");
                repl = integer ? String.valueOf(Math.max(1, Math.min(MAX_PERIOD, Math.round(v))))
                        : String.format(Locale.ROOT, "%.2f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
            }
            m.appendReplacement(out, Matcher.quoteReplacement(repl));
            idx++;
        }
        m.appendTail(out);
        return out.toString();
    }

    public static int countNumbers(String text) {
        Matcher m = NUMBER.matcher(text == null ? "" : text);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ parsing

    private static List<String> tokenize(String src) {
        List<String> out = new ArrayList<>();
        Matcher m = TOKEN.matcher(src);
        int pos = 0;
        while (pos < src.length()) {
            if (!m.find(pos) || m.start() != pos) {
                throw new IllegalArgumentException("规则表达式在第 " + (pos + 1) + " 个字符「"
                        + src.substring(pos, Math.min(src.length(), pos + 8)) + "」处无法识别");
            }
            String t = m.group(1) != null ? m.group(1)
                    : m.group(2) != null ? m.group(2).toLowerCase(Locale.ROOT)
                            : m.group(3);
            out.add(t);
            pos = m.end();
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }
        return out;
    }

    private static final class Parser {
        final List<String> tokens;
        int pos;
        int nodes;

        Parser(List<String> tokens) {
            this.tokens = tokens;
        }

        String peek() {
            return pos < tokens.size() ? tokens.get(pos) : null;
        }

        boolean accept(String t) {
            if (t.equals(peek())) {
                pos++;
                return true;
            }
            return false;
        }

        void expect(String t) {
            if (!accept(t)) {
                throw new IllegalArgumentException("规则表达式缺少「" + t + "」" + (peek() == null ? "" : "，在「" + peek() + "」附近"));
            }
        }

        Node count(Node n) {
            nodes++;
            return n;
        }

        Node or() {
            Node n = and();
            while (accept("or")) {
                n = count(new Bin("or", n, and()));
            }
            return n;
        }

        Node and() {
            Node n = not();
            while (accept("and")) {
                n = count(new Bin("and", n, not()));
            }
            return n;
        }

        Node not() {
            if (accept("not")) {
                return count(new Not(not()));
            }
            return cmp();
        }

        Node cmp() {
            Node n = sum();
            String t = peek();
            if (t != null && (t.equals(">") || t.equals("<") || t.equals(">=") || t.equals("<="))) {
                pos++;
                n = count(new Bin(t, n, sum()));
            }
            return n;
        }

        Node sum() {
            Node n = term();
            while (true) {
                if (accept("+")) {
                    n = count(new Bin("+", n, term()));
                } else if (accept("-")) {
                    n = count(new Bin("-", n, term()));
                } else {
                    return n;
                }
            }
        }

        Node term() {
            Node n = factor();
            while (true) {
                if (accept("*")) {
                    n = count(new Bin("*", n, factor()));
                } else if (accept("/")) {
                    n = count(new Bin("/", n, factor()));
                } else {
                    return n;
                }
            }
        }

        Node factor() {
            String t = peek();
            if (t == null) {
                throw new IllegalArgumentException("规则表达式不完整");
            }
            if (accept("-")) {
                return count(new Bin("-", new Num(0), factor()));
            }
            if (accept("(")) {
                Node n = or();
                expect(")");
                return n;
            }
            if (Character.isDigit(t.charAt(0))) {
                pos++;
                return count(new Num(Double.parseDouble(t)));
            }
            pos++;
            if (SERIES.contains(t)) {
                return count(new Series(t));
            }
            if (!FUNCTIONS.contains(t)) {
                throw new IllegalArgumentException("不认识「" + t + "」：可用序列 " + SERIES + "，函数 " + FUNCTIONS);
            }
            expect("(");
            List<Node> args = new ArrayList<>();
            if (!accept(")")) {
                do {
                    args.add(or());
                } while (accept(","));
                expect(")");
            }
            return count(Func.of(t, args));
        }
    }

    // ------------------------------------------------------------------ evaluation

    private record Ctx(List<Bar> bars, Map<java.time.LocalDate, Double> index) {}

    private interface Node {
        double[] eval(Ctx c);

        int lookback();
    }

    private record Num(double v) implements Node {

    public double[] eval(Ctx c) {
        double[] out = new double[c.bars().size()];
        Arrays.fill(out, v);
        return out;
    }

    public int lookback() {
        return 0;
    }

    }

    private record Series(String name) implements Node {

    public double[] eval(Ctx c) {
        List<Bar> b = c.bars();
        double[] out = new double[b.size()];
        for (int i = 0; i < out.length; i++) {
            Bar bar = b.get(i);
            out[i] = switch (name) {
                case "open" -> bar.open();
                case "high" -> bar.high();
                case "low" -> bar.low();
                case "volume" -> bar.volume();
                case "index" -> c.index() == null ? Double.NaN : c.index().getOrDefault(bar.date(), Double.NaN);
                default -> bar.close();
            };
        }
        return out;
    }

    public int lookback() {
        return 0;
    }

    }

    private record Not(Node a) implements Node {

    public double[] eval(Ctx c) {
        double[] v = a.eval(c);
        double[] out = new double[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = Double.isNaN(v[i]) ? Double.NaN : (v[i] == 0 ? 1 : 0);
        }
        return out;
    }

    public int lookback() {
        return a.lookback();
    }

    }

    private record Bin(String op, Node a, Node b) implements Node {

    public double[] eval(Ctx c) {
        double[] x = a.eval(c);
        double[] y = b.eval(c);
        double[] out = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            double p = x[i];
            double q = y[i];
            out[i] = switch (op) {
                case "and" -> (truthy(x, i) && truthy(y, i)) ? 1 : 0;
                case "or" -> (truthy(x, i) || truthy(y, i)) ? 1 : 0;
                case ">" -> Double.isNaN(p) || Double.isNaN(q) ? Double.NaN : (p > q ? 1 : 0);
                case "<" -> Double.isNaN(p) || Double.isNaN(q) ? Double.NaN : (p < q ? 1 : 0);
                case ">=" -> Double.isNaN(p) || Double.isNaN(q) ? Double.NaN : (p >= q ? 1 : 0);
                case "<=" -> Double.isNaN(p) || Double.isNaN(q) ? Double.NaN : (p <= q ? 1 : 0);
                case "+" -> p + q;
                case "-" -> p - q;
                case "*" -> p * q;
                default -> q == 0 ? Double.NaN : p / q;
            };
        }
        return out;
    }

    public int lookback() {
        return Math.max(a.lookback(), b.lookback());
    }

    }

    private record Func(String name, List<Node> args, int period) implements Node {

    static Func of(String name, List<Node> args) {
        int want = switch (name) {
            case "macd_dif", "macd_dea" -> 0;
            case "atr", "volratio", "abs" -> 1;
            default -> 2;
        };
        if (args.size() != want) {
            throw new IllegalArgumentException(name + "() 需要 " + want + " 个参数，实际 " + args.size() + " 个");
        }
        int period = 0;
        boolean periodArg = switch (name) {
            case "ma", "ema", "rsi", "highest", "lowest", "ref", "std", "roc" -> true;
            default -> false;
        };
        if (periodArg || name.equals("atr") || name.equals("volratio")) {
            Node p = args.get(args.size() - 1);
            if (!(p instanceof Num num) || num.v() != Math.rint(num.v()) || num.v() < 1 || num.v() > MAX_PERIOD) {
                throw new IllegalArgumentException(name + "() 的周期必须是 1–" + MAX_PERIOD + " 的整数");
            }
            period = (int) num.v();
        }
        return new Func(name, List.copyOf(args), period);
    }

    public int lookback() {
        int inner = args.stream().mapToInt(Node::lookback).max().orElse(0);
        int own = switch (name) {
            case "macd_dif", "macd_dea" -> 35;
            case "cross_up", "cross_down" -> 1;
            default -> period;
        };
        return inner + own;
    }

    public double[] eval(Ctx c) {
        int n = c.bars().size();
        switch (name) {
            case "macd_dif", "macd_dea" -> {
                Indicators.Macd m = Indicators.macd(Indicators.closes(c.bars()));
                return name.equals("macd_dif") ? m.dif() : m.dea();
            }
            case "atr" -> {
                return atr(c.bars(), period);
            }
            case "volratio" -> {
                return Indicators.volumeRatio(Indicators.volumes(c.bars()), period);
            }
            case "abs" -> {
                double[] x = args.get(0).eval(c);
                double[] out = new double[n];
                for (int i = 0; i < n; i++) {
                    out[i] = Math.abs(x[i]);
                }
                return out;
            }
            case "min", "max", "cross_up", "cross_down" -> {
                double[] x = args.get(0).eval(c);
                double[] y = args.get(1).eval(c);
                double[] out = new double[n];
                for (int i = 0; i < n; i++) {
                    out[i] = switch (name) {
                        case "min" -> Math.min(x[i], y[i]);
                        case "max" -> Math.max(x[i], y[i]);
                        case "cross_up" -> i == 0 || anyNaN(x[i], y[i], x[i - 1], y[i - 1]) ? Double.NaN
                                : (x[i] > y[i] && x[i - 1] <= y[i - 1] ? 1 : 0);
                        default -> i == 0 || anyNaN(x[i], y[i], x[i - 1], y[i - 1]) ? Double.NaN
                                : (x[i] < y[i] && x[i - 1] >= y[i - 1] ? 1 : 0);
                    };
                }
                return out;
            }
            default -> {
                double[] x = args.get(0).eval(c);
                return switch (name) {
                    case "ma" -> Indicators.sma(x, period);
                    case "ema" -> Indicators.ema(x, period);
                    case "rsi" -> Indicators.rsi(x, period);
                    case "highest" -> window(x, period, true);
                    case "lowest" -> window(x, period, false);
                    case "ref" -> ref(x, period);
                    case "std" -> std(x, period);
                    default -> roc(x, period);
                };
            }
        }
    }

    private static boolean anyNaN(double... v) {
        for (double d : v) {
            if (Double.isNaN(d)) {
                return true;
            }
        }
        return false;
    }

    /** Max / min of the {@code n} values before each one (excluding the current bar). */
    private static double[] window(double[] x, int n, boolean max) {
        double[] out = new double[x.length];
        Arrays.fill(out, Double.NaN);
        for (int i = n; i < x.length; i++) {
            double v = max ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
            for (int j = i - n; j < i; j++) {
                v = max ? Math.max(v, x[j]) : Math.min(v, x[j]);
            }
            out[i] = v;
        }
        return out;
    }

    private static double[] ref(double[] x, int n) {
        double[] out = new double[x.length];
        Arrays.fill(out, Double.NaN);
        for (int i = n; i < x.length; i++) {
            out[i] = x[i - n];
        }
        return out;
    }

    private static double[] roc(double[] x, int n) {
        double[] out = new double[x.length];
        Arrays.fill(out, Double.NaN);
        for (int i = n; i < x.length; i++) {
            out[i] = x[i - n] == 0 ? Double.NaN : (x[i] / x[i - n] - 1) * 100;
        }
        return out;
    }

    private static double[] std(double[] x, int n) {
        double[] out = new double[x.length];
        Arrays.fill(out, Double.NaN);
        for (int i = n - 1; i < x.length; i++) {
            double sum = 0;
            double sq = 0;
            for (int j = i - n + 1; j <= i; j++) {
                sum += x[j];
                sq += x[j] * x[j];
            }
            double mean = sum / n;
            out[i] = Math.sqrt(Math.max(0, sq / n - mean * mean));
        }
        return out;
    }

    private static double[] atr(List<Bar> bars, int n) {
        double[] tr = new double[bars.size()];
        for (int i = 0; i < tr.length; i++) {
            Bar b = bars.get(i);
            double prev = i == 0 ? b.close() : bars.get(i - 1).close();
            tr[i] = Math.max(b.high() - b.low(), Math.max(Math.abs(b.high() - prev), Math.abs(b.low() - prev)));
        }
        return Indicators.sma(tr, n);
    }
}}
