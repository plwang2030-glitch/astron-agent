package com.iflytek.astron.console.hub.entity.ashare;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** One simulated A-share account per user. */
@Data
@TableName("paper_account")
public class PaperAccount {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String uid;
    private BigDecimal initialCash;
    /** Available cash. */
    private BigDecimal cash;
    /** Reserved by pending buy orders. */
    private BigDecimal frozenCash;
    /** 1: run the review agent after each trading day. */
    private Integer autoReview;
    /** 1: plan before each open. */
    private Integer preOpenReview;
    /** 1: review after the last trading day of each week. */
    private Integer weeklyReview;
    /** 1: review after the last trading day of each month. */
    private Integer monthlyReview;
    /** 1: weekly automatic strategy search and monthly automatic rollback. */
    private Integer autoEvolve;
    /** 1: the active strategy places entries and all exits; 0: only the automatic stop loss. */
    private Integer autoTrade;
    /** Watched stock codes, JSON array; scanned with the active strategy at 14:50. */
    private String watchlist;
    /** 1: buy watchlist signals automatically (when auto-trading is on); 0: notify only. */
    private Integer watchlistAutoTrade;
    /** Agent that runs the daily review. */
    private Integer reviewBotId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
