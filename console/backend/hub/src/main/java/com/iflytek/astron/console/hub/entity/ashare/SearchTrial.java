package com.iflytek.astron.console.hub.entity.ashare;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * One candidate measured against the out-of-sample data. Counting these is what lets a result be
 * deflated for how hard the system searched before finding it.
 */
@Data
@TableName("ashare_search_trial")
public class SearchTrial {

    public static final String SEARCH = "SEARCH";
    public static final String PROPOSAL = "PROPOSAL";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String uid;
    private String paramsHash;
    private String changes;
    /** Comma-separated names of the fields this candidate changed. */
    private String fields;
    private Integer baseVersion;
    private Double oosScore;
    private Double oosReturnPct;
    private Double significanceZ;
    private Double deflatedSharpe;
    private Integer trialsAtTime;
    private Integer passed;
    private String origin;
    private LocalDateTime createTime;
}
