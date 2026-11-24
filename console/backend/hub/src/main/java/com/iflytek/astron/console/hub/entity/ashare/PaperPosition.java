package com.iflytek.astron.console.hub.entity.ashare;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("paper_position")
public class PaperPosition {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long accountId;
    private String code;
    private String name;
    private Integer qty;
    /** Total cost including fees. */
    private BigDecimal costAmount;
    /** Highest price since the first buy, for the trailing stop. */
    private BigDecimal peakPrice;
    /** 1: half already sold at the partial take profit. */
    private Integer partialTaken;
    private LocalDate firstBuyDate;
    /** Shares bought on this date ({@link #lastBuyQty}) cannot be sold the same day (T+1). */
    private LocalDate lastBuyDate;
    private Integer lastBuyQty;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
