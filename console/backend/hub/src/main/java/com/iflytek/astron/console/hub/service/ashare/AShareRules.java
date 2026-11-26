package com.iflytek.astron.console.hub.service.ashare;

import org.apache.commons.lang3.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A-share trading rules used by paper trading and backtests: supported boards, daily price limits,
 * lot sizes and fees. Beijing Stock Exchange, B shares and funds are not supported.
 */
public final class AShareRules {

    public enum Board {
        SH_MAIN("沪市主板"), SZ_MAIN("深市主板"), CHINEXT("创业板"), STAR("科创板");

        private final String label;

        Board(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private static final Pattern CODE = Pattern.compile("(\\d{6})");
    private static final BigDecimal COMMISSION_RATE = new BigDecimal("0.00025");
    private static final BigDecimal MIN_COMMISSION = new BigDecimal("5");
    private static final BigDecimal TRANSFER_RATE = new BigDecimal("0.00001");
    private static final BigDecimal STAMP_RATE = new BigDecimal("0.0005");

    /** Fees of one fill; {@code stamp} is charged on sells only. */
    public record Fees(BigDecimal commission, BigDecimal transfer, BigDecimal stamp, BigDecimal total) {}

    private AShareRules() {}

    /**
     * Extracts the 6-digit code from inputs such as {@code 600519}, {@code sh600519},
     * {@code 600519.SH}.
     */
    public static String normalizeCode(String raw) {
        Matcher m = CODE.matcher(StringUtils.defaultString(raw));
        if (!m.find()) {
            throw new IllegalArgumentException("股票代码需为 6 位数字：" + StringUtils.abbreviate(raw, 20));
        }
        String code = m.group(1);
        board(code);
        return code;
    }

    public static Board board(String code) {
        String c = StringUtils.defaultString(code);
        if (c.startsWith("688") || c.startsWith("689")) {
            return Board.STAR;
        }
        if (c.startsWith("300") || c.startsWith("301")) {
            return Board.CHINEXT;
        }
        if (c.startsWith("600") || c.startsWith("601") || c.startsWith("603") || c.startsWith("605")) {
            return Board.SH_MAIN;
        }
        if (c.startsWith("000") || c.startsWith("001") || c.startsWith("002") || c.startsWith("003")) {
            return Board.SZ_MAIN;
        }
        if (c.startsWith("4") || c.startsWith("8") || c.startsWith("92")) {
            throw new IllegalArgumentException("暂不支持北交所股票：" + c);
        }
        throw new IllegalArgumentException("只支持沪深 A 股（主板 / 创业板 / 科创板），不支持：" + c);
    }

    /**
     * Sina / Tencent symbol derived from an Eastmoney security id. The id carries the exchange
     * ({@code 1.} Shanghai, {@code 0.} Shenzhen), which the code alone does not: 000300 is the CSI 300
     * index on the Shanghai feed, while a code starting 000 would otherwise be read as Shenzhen.
     */
    public static String symbolOfSecid(String secid) {
        int dot = StringUtils.indexOf(secid, '.');
        if (dot <= 0 || dot + 1 >= StringUtils.length(secid)) {
            throw new IllegalArgumentException("证券 id 格式应为 1.600036 或 0.000333：" + secid);
        }
        return ("1".equals(secid.substring(0, dot)) ? "sh" : "sz") + secid.substring(dot + 1);
    }

    /** Sina / Tencent symbol: {@code sh600036}, {@code sz000333}. */
    public static String sinaSymbol(String code) {
        Board b = board(code);
        return (b == Board.SH_MAIN || b == Board.STAR ? "sh" : "sz") + code;
    }

    /** Eastmoney security id: {@code 1.} Shanghai, {@code 0.} Shenzhen. */
    public static String secid(String code) {
        Board b = board(code);
        return (b == Board.SH_MAIN || b == Board.STAR ? "1." : "0.") + code;
    }

    public static boolean isSt(String name) {
        return StringUtils.upperCase(StringUtils.defaultString(name), Locale.ROOT).contains("ST");
    }

    /** Daily price limit as a fraction: 20% STAR / ChiNext, 5% ST on the main boards, else 10%. */
    public static BigDecimal limitPct(String code, String name) {
        Board b = board(code);
        if (b == Board.STAR || b == Board.CHINEXT) {
            return new BigDecimal("0.20");
        }
        return isSt(name) ? new BigDecimal("0.05") : new BigDecimal("0.10");
    }

    public static BigDecimal limitUp(BigDecimal prevClose, String code, String name) {
        return prevClose.multiply(BigDecimal.ONE.add(limitPct(code, name))).setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal limitDown(BigDecimal prevClose, String code, String name) {
        return prevClose.multiply(BigDecimal.ONE.subtract(limitPct(code, name))).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Lot rules. Buys: multiples of 100 (STAR: at least 200, then any integer). Sells: the same, or the
     * whole sellable remainder (odd lots must be sold at once).
     *
     * @return {@code null} when valid, otherwise the reason
     */
    public static String checkQty(String code, boolean buy, int qty, int sellable) {
        if (qty <= 0) {
            return "数量必须大于 0";
        }
        boolean star = board(code) == Board.STAR;
        if (!buy && qty == sellable) {
            return null;
        }
        if (star) {
            return qty >= 200 ? null : "科创板单笔至少 200 股";
        }
        return qty % 100 == 0 ? null : "数量须为 100 股的整数倍";
    }

    /** Largest valid buy quantity whose amount at {@code price} fits in {@code budget}. */
    public static int maxBuyQty(String code, double price, double budget) {
        if (price <= 0 || budget <= 0) {
            return 0;
        }
        int raw = (int) Math.floor(budget / price);
        if (board(code) == Board.STAR) {
            return raw >= 200 ? raw : 0;
        }
        return raw / 100 * 100;
    }

    /** Intraday market orders pay this slippage over the latest price. */
    public static final BigDecimal SLIPPAGE = new BigDecimal("0.001");
    public static final double SLIPPAGE_VALUE = SLIPPAGE.doubleValue();

    /** Buy fill: latest × (1 + slippage), rounded up to the 0.01 tick, never above the limit up. */
    public static BigDecimal buyFillPrice(BigDecimal latest, BigDecimal limitUp) {
        BigDecimal p = latest.multiply(BigDecimal.ONE.add(SLIPPAGE)).setScale(2, RoundingMode.CEILING);
        return limitUp == null ? p : p.min(limitUp);
    }

    /**
     * Sell fill: latest × (1 − slippage), rounded down to the 0.01 tick, never below the limit down.
     */
    public static BigDecimal sellFillPrice(BigDecimal latest, BigDecimal limitDown) {
        BigDecimal p = latest.multiply(BigDecimal.ONE.subtract(SLIPPAGE)).setScale(2, RoundingMode.FLOOR);
        return limitDown == null ? p : p.max(limitDown);
    }

    /** Limit price check: positive, on the 0.01 tick and within today's price limits. */
    public static String checkLimitPrice(BigDecimal price, BigDecimal prevClose, String code, String name) {
        if (price == null || price.signum() <= 0) {
            return "限价必须大于 0";
        }
        if (price.stripTrailingZeros().scale() > 2) {
            return "限价最小变动单位为 0.01 元";
        }
        if (prevClose != null && prevClose.signum() > 0) {
            BigDecimal up = limitUp(prevClose, code, name);
            BigDecimal down = limitDown(prevClose, code, name);
            if (price.compareTo(up) > 0 || price.compareTo(down) < 0) {
                return "限价须在今日涨跌停价 " + down + "–" + up + " 之间";
            }
        }
        return null;
    }

    /**
     * About half of a position for a partial sale, in valid lots: multiples of 100 (STAR: at least 200,
     * then any number); 0 when the position is too small to split.
     */
    public static int halfQty(String code, int qty) {
        int half = qty / 2;
        if (board(code) == Board.STAR) {
            return half >= 200 ? half : 0;
        }
        return half / 100 * 100;
    }

    /** Commission max(5, 0.025%), transfer fee 0.001%, stamp duty 0.05% on sells. */
    public static Fees fees(boolean buy, BigDecimal amount) {
        BigDecimal commission = amount.multiply(COMMISSION_RATE).max(MIN_COMMISSION).setScale(2, RoundingMode.HALF_UP);
        BigDecimal transfer = amount.multiply(TRANSFER_RATE).setScale(2, RoundingMode.HALF_UP);
        BigDecimal stamp = buy ? BigDecimal.ZERO.setScale(2) : amount.multiply(STAMP_RATE).setScale(2, RoundingMode.HALF_UP);
        return new Fees(commission, transfer, stamp, commission.add(transfer).add(stamp));
    }

    public static double feesValue(boolean buy, double amount) {
        return fees(buy, BigDecimal.valueOf(amount)).total().doubleValue();
    }
}
