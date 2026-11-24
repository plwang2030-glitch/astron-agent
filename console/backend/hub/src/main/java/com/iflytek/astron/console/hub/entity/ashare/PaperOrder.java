package com.iflytek.astron.console.hub.entity.ashare;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Fills at the open of the next trading session after {@link #placedAt}. */
@Data
@TableName("paper_order")
public class PaperOrder {
    public static final String BUY = "BUY";
    public static final String SELL = "SELL";
    public static final String PENDING = "PENDING";
    public static final String FILLED = "FILLED";
    public static final String REJECTED = "REJECTED";
    public static final String CANCELLED = "CANCELLED";
    public static final String MARKET = "MARKET";
    public static final String LIMIT = "LIMIT";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long accountId;
    private String code;
    private String name;
    private String side;
    /** MARKET or LIMIT. */
    private String orderType;
    /** LIMIT orders: highest buy / lowest sell price. */
    private BigDecimal limitPrice;
    private Integer qty;
    private String status;
    /** AGENT or STRATEGY (automatic exit). */
    private String source;
    private LocalDateTime placedAt;
    private BigDecimal frozenAmount;
    private LocalDate fillDate;
    private LocalDateTime fillTime;
    private BigDecimal fillPrice;
    private BigDecimal fillAmount;
    private BigDecimal fees;
    private BigDecimal realizedPnl;
    private String reason;
    private String expectation;
    private Integer confidence;
    private Integer strategyVersion;
    private String rejectReason;
    private String reviewStatus;
    private String reviewOutcome;
    private String reviewNote;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
