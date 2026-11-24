package com.iflytek.astron.console.hub.dto.agentbuilder;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
public class AgentBuildSessionDto {
    private String id;
    private String title;
    private String status;
    private Integer botId;
    private AgentBuilderDraft draft;
    private List<Map<String, Object>> messages;
    private int undoDepth;
    /** {@code true} while the builder is replying for this session. */
    private boolean replying;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
