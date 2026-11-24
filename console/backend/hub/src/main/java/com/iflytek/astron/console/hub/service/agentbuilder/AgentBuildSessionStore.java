package com.iflytek.astron.console.hub.service.agentbuilder;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.iflytek.astron.console.commons.constant.ResponseEnum;
import com.iflytek.astron.console.commons.exception.BusinessException;
import com.iflytek.astron.console.hub.dto.agentbuilder.AgentBuilderDraft;
import com.iflytek.astron.console.hub.entity.AgentBuildSession;
import com.iflytek.astron.console.hub.mapper.AgentBuildSessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Persistence for builder sessions. Draft mutations are serialized per session (the builder's tools
 * run on the model's streaming thread while the user may edit the draft from the page) and every
 * mutation stores an undo snapshot first.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentBuildSessionStore {

    static final int MAX_UNDO = 20;
    static final int MAX_MESSAGES = 200;
    static final int MAX_CHANGES = 100;

    private final AgentBuildSessionMapper mapper;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public AgentBuildSession insert(AgentBuildSession session) {
        mapper.insert(session);
        return session;
    }

    /** Loads a session owned by {@code uid} in {@code spaceId}; throws DATA_NOT_FOUND otherwise. */
    public AgentBuildSession requireOwned(String uid, Long spaceId, String sessionId) {
        if (StringUtils.isBlank(uid)) {
            throw new BusinessException(ResponseEnum.UNAUTHORIZED);
        }
        if (StringUtils.isBlank(sessionId)) {
            throw new BusinessException(ResponseEnum.PARAMETER_ERROR);
        }
        AgentBuildSession session = mapper.selectOne(Wrappers.lambdaQuery(AgentBuildSession.class)
                .eq(AgentBuildSession::getId, sessionId)
                .eq(AgentBuildSession::getIsDelete, 0));
        if (session == null || !Objects.equals(session.getUid(), uid)
                || !Objects.equals(session.getSpaceId(), spaceId)) {
            throw new BusinessException(ResponseEnum.DATA_NOT_FOUND);
        }
        return session;
    }

    public List<AgentBuildSession> listOwned(String uid, Long spaceId, int limit) {
        LambdaQueryWrapper<AgentBuildSession> query = Wrappers.lambdaQuery(AgentBuildSession.class)
                .eq(AgentBuildSession::getUid, uid)
                .eq(AgentBuildSession::getIsDelete, 0)
                .orderByDesc(AgentBuildSession::getUpdateTime)
                .last("LIMIT " + Math.max(1, Math.min(limit, 50)));
        if (spaceId == null) {
            query.isNull(AgentBuildSession::getSpaceId);
        } else {
            query.eq(AgentBuildSession::getSpaceId, spaceId);
        }
        return mapper.selectList(query);
    }

    public AgentBuilderDraft readDraft(AgentBuildSession session) {
        if (StringUtils.isBlank(session.getDraftJson())) {
            return new AgentBuilderDraft();
        }
        AgentBuilderDraft draft = JSON.parseObject(session.getDraftJson(), AgentBuilderDraft.class);
        return draft == null ? new AgentBuilderDraft() : draft;
    }

    public AgentBuilderDraft readDraft(String sessionId) {
        return readDraft(requireById(sessionId));
    }

    public List<Map<String, Object>> readMessages(AgentBuildSession session) {
        return parseMessages(session.getMessagesJson());
    }

    public int undoDepth(AgentBuildSession session) {
        return parseHistory(session.getDraftHistory()).size();
    }

    /**
     * Applies {@code mutation} to the current draft under the session lock. The previous draft is
     * pushed onto the undo stack and a change entry is appended when {@code changeSummary} returns a
     * non-blank text.
     */
    public AgentBuilderDraft mutateDraft(String sessionId, String toolName,
            Function<AgentBuilderDraft, String> mutation) {
        synchronized (lockFor(sessionId)) {
            AgentBuildSession session = requireById(sessionId);
            String before = StringUtils.defaultIfBlank(session.getDraftJson(), JSON.toJSONString(new AgentBuilderDraft()));
            AgentBuilderDraft draft = readDraft(session);
            String summary = mutation.apply(draft);
            appendChange(draft, toolName, summary);

            List<String> history = parseHistory(session.getDraftHistory());
            history.add(before);
            while (history.size() > MAX_UNDO) {
                history.remove(0);
            }
            AgentBuildSession update = new AgentBuildSession();
            update.setId(sessionId);
            update.setDraftJson(JSON.toJSONString(draft));
            update.setDraftHistory(JSON.toJSONString(history));
            update.setUpdateTime(LocalDateTime.now());
            mapper.updateById(update);
            log.info("Agent builder draft updated, sessionId={}, tool={}, undoDepth={}", sessionId, toolName,
                    history.size());
            return draft;
        }
    }

    /** Restores the newest undo snapshot. Returns {@code null} when there is nothing to undo. */
    public AgentBuilderDraft undo(String sessionId) {
        synchronized (lockFor(sessionId)) {
            AgentBuildSession session = requireById(sessionId);
            List<String> history = parseHistory(session.getDraftHistory());
            if (history.isEmpty()) {
                return null;
            }
            String previous = history.remove(history.size() - 1);
            AgentBuilderDraft restored = JSON.parseObject(previous, AgentBuilderDraft.class);
            if (restored == null) {
                restored = new AgentBuilderDraft();
            }
            AgentBuildSession update = new AgentBuildSession();
            update.setId(sessionId);
            update.setDraftJson(JSON.toJSONString(restored));
            update.setDraftHistory(JSON.toJSONString(history));
            update.setUpdateTime(LocalDateTime.now());
            mapper.updateById(update);
            log.info("Agent builder draft undone, sessionId={}, undoDepth={}", sessionId, history.size());
            return restored;
        }
    }

    public void appendMessage(String sessionId, Map<String, Object> message) {
        synchronized (lockFor(sessionId)) {
            AgentBuildSession session = requireById(sessionId);
            List<Map<String, Object>> messages = parseMessages(session.getMessagesJson());
            messages.add(message);
            while (messages.size() > MAX_MESSAGES) {
                messages.remove(0);
            }
            AgentBuildSession update = new AgentBuildSession();
            update.setId(sessionId);
            update.setMessagesJson(JSON.toJSONString(messages));
            update.setUpdateTime(LocalDateTime.now());
            mapper.updateById(update);
        }
    }

    public void markPublished(String sessionId, Integer botId) {
        AgentBuildSession update = new AgentBuildSession();
        update.setId(sessionId);
        update.setStatus("published");
        update.setBotId(botId);
        update.setUpdateTime(LocalDateTime.now());
        mapper.updateById(update);
    }

    public void softDelete(String sessionId) {
        AgentBuildSession update = new AgentBuildSession();
        update.setId(sessionId);
        update.setIsDelete(1);
        update.setUpdateTime(LocalDateTime.now());
        mapper.updateById(update);
    }

    private AgentBuildSession requireById(String sessionId) {
        AgentBuildSession session = mapper.selectById(sessionId);
        if (session == null || Objects.equals(session.getIsDelete(), 1)) {
            throw new BusinessException(ResponseEnum.DATA_NOT_FOUND);
        }
        return session;
    }

    private Object lockFor(String sessionId) {
        return locks.computeIfAbsent(sessionId, k -> new Object());
    }

    private static void appendChange(AgentBuilderDraft draft, String tool, String summary) {
        if (StringUtils.isBlank(summary)) {
            return;
        }
        AgentBuilderDraft.Change change = new AgentBuilderDraft.Change();
        change.setAt(System.currentTimeMillis());
        change.setTool(tool);
        change.setSummary(summary);
        if (draft.getChanges() == null) {
            draft.setChanges(new ArrayList<>());
        }
        draft.getChanges().add(change);
        while (draft.getChanges().size() > MAX_CHANGES) {
            draft.getChanges().remove(0);
        }
    }

    private static List<String> parseHistory(String json) {
        if (StringUtils.isBlank(json)) {
            return new ArrayList<>();
        }
        try {
            return new ArrayList<>(JSON.parseArray(json, String.class));
        } catch (Exception e) {
            log.warn("Corrupt agent builder draft history, resetting, errorType={}", e.getClass().getSimpleName());
            return new ArrayList<>();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parseMessages(String json) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (StringUtils.isBlank(json)) {
            return result;
        }
        try {
            JSONArray array = JSON.parseArray(json);
            for (int i = 0; i < array.size(); i++) {
                Object item = array.get(i);
                if (item instanceof Map<?, ?> map) {
                    result.add((Map<String, Object>) map);
                }
            }
        } catch (Exception e) {
            log.warn("Corrupt agent builder messages, resetting, errorType={}", e.getClass().getSimpleName());
        }
        return result;
    }
}
