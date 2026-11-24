package com.iflytek.astron.console.hub.dto.user;

/**
 * @author wowo_zZ
 * @since 2025/9/9 15:53
 **/

import lombok.Data;

import java.util.List;

@Data
public class MyBotParamDTO {

    private String searchValue;

    private List<Integer> botStatus;

    // Version, 1 is agent, 3 is workflow
    private Integer version;

    /** Bot versions to leave out, e.g. [3] to hide workflows from agent lists. */
    private List<Integer> excludeVersions;

    private int status;

    private int pageIndex = 1;

    private int pageSize = 15;

    // Default is domestic, 1 is domestic, 2 is overseas
    private Integer showType;

    private String sort;

}
