package com.iflytek.astron.console.hub.entity.ashare;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** A backtestable strategy parameter set; only one per user is ACTIVE. */
@Data
@TableName("trading_strategy_version")
public class TradingStrategyVersion {
    public static final String ACTIVE = "ACTIVE";
    public static final String RETIRED = "RETIRED";
    public static final String REJECTED = "REJECTED";
    /** Passed the backtest gate; forward test on new trading days before adoption. */
    public static final String TRIAL = "TRIAL";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String uid;
    private Integer version;
    private Integer parentVersion;
    /** {@link com.iflytek.astron.console.hub.service.ashare.StrategyParams} as JSON. */
    private String params;
    private String rationale;
    private String status;
    /** Backtest of candidate and baseline as JSON. */
    private String evaluation;
    private LocalDateTime createTime;
}
