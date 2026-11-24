package com.iflytek.astron.console.hub.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** Agent builder session: holds the draft agent config and the builder conversation. */
@Data
@TableName("agent_build_session")
@Schema(name = "AgentBuildSession", description = "Agent builder draft session")
public class AgentBuildSession {

    @TableId(type = IdType.INPUT)
    private String id;

    private String uid;

    private Long spaceId;

    private String title;

    /** Serialized {@code AgentBuilderDraft}. */
    private String draftJson;

    /** JSON array of previous draft snapshots (newest last), used for undo. */
    private String draftHistory;

    /** JSON array of builder conversation messages. */
    private String messagesJson;

    /** {@code draft} or {@code published}. */
    private String status;

    private Integer botId;

    private Integer isDelete;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
