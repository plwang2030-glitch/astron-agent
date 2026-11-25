package com.iflytek.astron.console.hub.entity.ashare;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** One invented trading rule and what it scored, kept so the search can breed from it. */
@Data
@TableName("ashare_rule_program")
public class RuleProgram {

    public static final String ENTRY = "ENTRY";
    public static final String EXIT = "EXIT";
    /** Backtested and given a score. */
    public static final String SCORED = "SCORED";
    /** Rejected by the rule parser, so it was never run. */
    public static final String INVALID = "INVALID";
    /** Valid but impossible to judge, e.g. it never traded. */
    public static final String UNTESTABLE = "UNTESTABLE";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String uid;
    private Integer island;
    private String kind;
    private String expression;
    private String exprHash;
    private Long parentId;
    private Integer generation;
    private String idea;
    private Double oosScore;
    private Double oosReturnPct;
    private Double oosMaxDrawdownPct;
    private Integer trades;
    private Double deflatedSharpe;
    private Integer passedGate;
    private String status;
    private String note;
    private LocalDateTime createTime;
}
