package com.iflytek.astron.console.hub.controller.agentbuilder;

import com.iflytek.astron.console.commons.annotation.RateLimit;
import com.iflytek.astron.console.commons.annotation.space.SpacePreAuth;
import com.iflytek.astron.console.commons.constant.ResponseEnum;
import com.iflytek.astron.console.commons.exception.BusinessException;
import com.iflytek.astron.console.commons.response.ApiResult;
import com.iflytek.astron.console.commons.util.RequestContextUtil;
import com.iflytek.astron.console.commons.util.SseEmitterUtil;
import com.iflytek.astron.console.commons.util.space.SpaceInfoUtil;
import com.iflytek.astron.console.hub.dto.agentbuilder.AgentBuildSessionDto;
import com.iflytek.astron.console.hub.dto.agentbuilder.AgentBuilderRequests;
import com.iflytek.astron.console.hub.service.agentbuilder.AgentBuilderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * Agent-first creation: the builder conversation, its draft, trial runs and final creation. All
 * endpoints are scoped to the caller's own sessions in the current space.
 */
@Slf4j
@RestController
@RequestMapping("/agent-builder")
@Tag(name = "Agent Builder", description = "Conversational agent creation")
@RequiredArgsConstructor
public class AgentBuilderController {

    private final AgentBuilderService agentBuilderService;

    @GetMapping("/sessions")
    @Operation(summary = "List my recent builder sessions")
    public ApiResult<List<AgentBuildSessionDto>> list(@RequestParam(defaultValue = "20") int limit) {
        return ApiResult.success(agentBuilderService.listSessions(uid(), spaceId(), limit));
    }

    @PostMapping("/sessions")
    @Operation(summary = "Start a builder session from a goal and optional files")
    @RateLimit(dimension = "USER", window = 1, limit = 1)
    public ApiResult<AgentBuildSessionDto> create(HttpServletRequest request,
            @Valid @RequestBody AgentBuilderRequests.CreateSession body) {
        return ApiResult.success(agentBuilderService.createSession(uid(), spaceId(), body, request));
    }

    @GetMapping("/sessions/{sessionId}")
    @Operation(summary = "Get a builder session with its draft and messages")
    public ApiResult<AgentBuildSessionDto> get(@PathVariable String sessionId) {
        return ApiResult.success(agentBuilderService.getSession(uid(), spaceId(), sessionId));
    }

    @DeleteMapping("/sessions/{sessionId}")
    @Operation(summary = "Delete a builder session")
    public ApiResult<Void> delete(@PathVariable String sessionId) {
        agentBuilderService.deleteSession(uid(), spaceId(), sessionId);
        return ApiResult.success();
    }

    @PutMapping("/sessions/{sessionId}/draft")
    @Operation(summary = "Save manual edits to the draft")
    public ApiResult<AgentBuildSessionDto> updateDraft(@PathVariable String sessionId,
            @Valid @RequestBody AgentBuilderRequests.UpdateDraft body) {
        return ApiResult.success(agentBuilderService.updateDraft(uid(), spaceId(), sessionId, body.getDraft()));
    }

    @PostMapping("/sessions/{sessionId}/undo")
    @Operation(summary = "Undo the latest draft change")
    public ApiResult<AgentBuildSessionDto> undo(@PathVariable String sessionId) {
        return ApiResult.success(agentBuilderService.undo(uid(), spaceId(), sessionId));
    }

    @PostMapping(path = "/sessions/{sessionId}/chat", produces = "text/event-stream;charset=UTF-8")
    @Operation(summary = "Talk to the builder agent (SSE)")
    public SseEmitter chat(HttpServletRequest request, @PathVariable String sessionId,
            @Valid @RequestBody AgentBuilderRequests.Chat body) {
        SseEmitter rejected = rejectNonMember();
        if (rejected != null) {
            return rejected;
        }
        return agentBuilderService.chat(uid(), spaceId(), sessionId, body, request);
    }

    @PostMapping(path = "/sessions/{sessionId}/trial", produces = "text/event-stream;charset=UTF-8")
    @Operation(summary = "Trial-run the draft agent (SSE)")
    public SseEmitter trial(@PathVariable String sessionId, @Valid @RequestBody AgentBuilderRequests.Trial body) {
        SseEmitter rejected = rejectNonMember();
        if (rejected != null) {
            return rejected;
        }
        return agentBuilderService.trial(uid(), spaceId(), sessionId, body);
    }

    /** Creating the agent needs the same space permission as {@code POST /bot/create}. */
    @SpacePreAuth(key = "BotCreateController_createBot_POST")
    @PostMapping("/sessions/{sessionId}/publish")
    @Operation(summary = "Create the agent from the draft")
    @RateLimit(dimension = "USER", window = 1, limit = 1)
    public ApiResult<Integer> publish(@PathVariable String sessionId) {
        return ApiResult.success(agentBuilderService.publish(uid(), spaceId(), sessionId));
    }

    private static String uid() {
        String uid = RequestContextUtil.getUID();
        if (uid == null) {
            throw new BusinessException(ResponseEnum.UNAUTHORIZED);
        }
        return uid;
    }

    private static Long spaceId() {
        Long spaceId = SpaceInfoUtil.getSpaceId();
        if (spaceId != null && !SpaceInfoUtil.checkUserBelongSpace()) {
            throw new BusinessException(ResponseEnum.PERMISSION_NOT_BELONG_SPACE);
        }
        return spaceId;
    }

    private static SseEmitter rejectNonMember() {
        Long spaceId = SpaceInfoUtil.getSpaceId();
        if (spaceId != null && !SpaceInfoUtil.checkUserBelongSpace()) {
            log.warn("Reject agent builder stream from non-member, spaceId={}", spaceId);
            SseEmitter emitter = SseEmitterUtil.createSseEmitter();
            SseEmitterUtil.completeWithError(emitter, "No permission for this space");
            return emitter;
        }
        return null;
    }
}
