package com.iflytek.astron.console.hub.entity.ashare;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** One trading opinion of an agent, recorded so it can be checked against what happened. */
@Data
@TableName("ashare_decision_signal")
public class DecisionSignal {

    public static final String BUY = "BUY";
    public static final String ADD = "ADD";
    public static final String HOLD = "HOLD";
    public static final String WATCH = "WATCH";
    public static final String REDUCE = "REDUCE";
    public static final String SELL = "SELL";
    public static final String AVOID = "AVOID";
    public static final String ALERT = "ALERT";

    public static final String PENDING = "PENDING";
    public static final String HIT = "HIT";
    public static final String MISS = "MISS";
    public static final String UNABLE = "UNABLE";
    public static final String SUPERSEDED = "SUPERSEDED";

    public static final String AGENT = "AGENT";
    public static final String REVIEW = "REVIEW";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String uid;
    private String code;
    private String name;
    private String action;
    /** Evaluation window in trading days: 1d / 3d / 5d / 10d. */
    private String horizon;
    private BigDecimal confidence;
    private BigDecimal entryLow;
    private BigDecimal entryHigh;
    private BigDecimal stopLoss;
    private BigDecimal targetPrice;
    private String reason;
    private String risk;
    private String watch;
    private String source;
    private Integer botId;
    private Long reportId;
    private String marketPhase;
    private String dataQuality;
    private String planQuality;
    /** Trading day the evaluation counts from (the last settled day when recorded). */
    private LocalDate anchorDate;
    private BigDecimal anchorPrice;
    private String status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
