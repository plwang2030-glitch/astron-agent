package com.iflytek.astron.console.hub.entity.ashare;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("paper_snapshot")
public class PaperSnapshot {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long accountId;
    private LocalDate tradeDate;
    private BigDecimal cash;
    private BigDecimal marketValue;
    private BigDecimal total;
    private LocalDateTime createTime;
}
