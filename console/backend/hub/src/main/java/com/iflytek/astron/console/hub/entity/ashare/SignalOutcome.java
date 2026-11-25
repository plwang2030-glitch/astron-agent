package com.iflytek.astron.console.hub.entity.ashare;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * What actually happened after a {@link DecisionSignal}'s window. The grouping fields are copied
 * here at evaluation time, so later edits to the signal cannot change past statistics.
 */
@Data
@TableName("ashare_signal_outcome")
public class SignalOutcome {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long signalId;
    private String uid;
    private String code;
    private String horizon;
    private LocalDate anchorDate;
    private BigDecimal anchorPrice;
    private LocalDate endDate;
    private BigDecimal endPrice;
    private BigDecimal minLow;
    private BigDecimal maxHigh;
    private BigDecimal returnPct;
    /** Worst move against the opinion within the window, in percent (always ≥ 0). */
    private BigDecimal maxAdversePct;
    private String result;
    private String detail;
    private String action;
    private String marketPhase;
    private String source;
    private String dataQuality;
    private String planQuality;
    private LocalDateTime createTime;
}
