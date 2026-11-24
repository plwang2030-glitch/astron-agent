package com.iflytek.astron.console.hub.entity.ashare;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** A lesson from reviews (LESSON / PREFERENCE) or from strategy evolution attempts (META). */
@Data
@TableName("trading_lesson")
public class TradingLesson {
    public static final String LESSON = "LESSON";
    public static final String PREFERENCE = "PREFERENCE";
    public static final String META = "META";
    public static final String CANDIDATE = "CANDIDATE";
    public static final String ACTIVE = "ACTIVE";
    public static final String RETIRED = "RETIRED";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String uid;
    private String kind;
    private String content;
    private String tags;
    /** JSON array of order ids (LESSON) or strategy versions (META). */
    private String evidence;
    private Integer support;
    private Integer contradict;
    private BigDecimal confidence;
    private String status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
