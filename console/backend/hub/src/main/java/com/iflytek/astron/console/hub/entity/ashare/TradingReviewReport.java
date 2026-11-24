package com.iflytek.astron.console.hub.entity.ashare;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** One review run of the paper account (scheduled after the close or started by the user). */
@Data
@TableName("trading_review_report")
public class TradingReviewReport {

    public static final String AUTO = "AUTO";
    public static final String MANUAL = "MANUAL";
    public static final String RUNNING = "RUNNING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";
    public static final String PRE_OPEN = "PRE_OPEN";
    public static final String CLOSE = "CLOSE";
    public static final String WEEKLY = "WEEKLY";
    public static final String MONTHLY = "MONTHLY";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String uid;
    private LocalDate tradeDate;
    private String triggerType;
    /** PRE_OPEN / CLOSE / WEEKLY / MONTHLY. */
    private String reviewType;
    /** Reviewed period, e.g. 2026-11-23, 2026-W48, 2026-11. */
    private String period;
    private Integer botId;
    private String status;
    private String content;
    private String error;
    private Integer costMs;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
