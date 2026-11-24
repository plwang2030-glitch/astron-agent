package com.iflytek.astron.console.hub.entity.ashare;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** One monthly screen of the A-share universe. */
@Data
@TableName("ashare_universe_snapshot")
public class UniverseSnapshot {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String month;
    private LocalDate asOf;
    private String rules;
    private String tradeCodes;
    private String poolCodes;
    private String details;
    private Integer candidates;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
