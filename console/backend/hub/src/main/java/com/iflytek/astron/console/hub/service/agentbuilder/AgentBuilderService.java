package com.iflytek.astron.console.hub.service.agentbuilder;

import com.alibaba.fastjson2.JSON;
import com.iflytek.astron.console.commons.constant.ResponseEnum;
import com.iflytek.astron.console.commons.dto.bot.BotCreateForm;
import com.iflytek.astron.console.commons.dto.bot.BotInfoDto;
import com.iflytek.astron.console.commons.dto.bot.DebugChatBotReqDto;
import com.iflytek.astron.console.commons.dto.llm.SparkChatRequest;
import com.iflytek.astron.console.commons.exception.BusinessException;
import com.iflytek.astron.console.commons.service.bot.BotDatasetService;
import com.iflytek.astron.console.commons.service.bot.BotService;
import com.iflytek.astron.console.commons.util.SseEmitterUtil;
import com.iflytek.astron.console.hub.dto.agentbuilder.AgentBuildSessionDto;
import com.iflytek.astron.console.hub.dto.agentbuilder.AgentBuilderDraft;
import com.iflytek.astron.console.hub.dto.agentbuilder.AgentBuilderRequests;
import com.iflytek.astron.console.hub.entity.AgentBuildSession;
import com.iflytek.astron.console.hub.service.chat.BotChatService;
import com.iflytek.astron.console.hub.service.chat.springai.AgentChatTask;
import com.iflytek.astron.console.hub.service.chat.springai.SpringAiAgentChatService;
import com.iflytek.astron.console.hub.service.chat.springai.SubAgentPreparer;
import com.iflytek.astron.console.toolkit.entity.dto.skill.SkillFileContentDto;
import com.iflytek.astron.console.toolkit.entity.dto.skill.SkillFileTreeNodeDto;
import com.iflytek.astron.console.toolkit.entity.platform.AiCapability;
import com.iflytek.astron.console.toolkit.entity.vo.LLMInfoVo;
import com.iflytek.astron.console.toolkit.entity.vo.skill.SkillFileCreateFolderReq;
import com.iflytek.astron.console.toolkit.entity.vo.skill.SkillFileCreateReq;
import com.iflytek.astron.console.toolkit.service.model.ModelService;
import com.iflytek.astron.console.toolkit.service.platform.PlatformAccountService;
import com.iflytek.astron.console.toolkit.service.repo.MassDatasetInfoService;
import com.iflytek.astron.console.toolkit.service.skill.SkillFileService;
import com.iflytek.astron.console.toolkit.service.tool.AgentToolRuntimeService;
import com.iflytek.astron.console.toolkit.service.workflow.AgentWorkflowRuntimeService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Agent-first creation. A builder agent (running on {@link SpringAiAgentChatService}, the same
 * runtime as regular agents) edits a draft through draft-only tools; the user trial-runs the draft
 * through the regular debug chat path and finally confirms creation, which turns the draft into a
 * normal bot.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentBuilderService {

    static final long SSE_TIMEOUT_MS = 300_000L;
    static final long REPLY_LOCK_STALE_MS = SSE_TIMEOUT_MS + 30_000L;
    static final int MAX_HISTORY_MESSAGES = 20;
    static final int MAX_ATTACHMENT_CHARS = 30_000;
    static final int DEFAULT_BOT_TYPE = 24;
    static final String DEFAULT_AVATAR = "/skylumin-avatar.svg";
    static final String DEFAULT_SPARK_MODEL = "spark-x1";

    private final AgentBuildSessionStore store;
    private final AgentBuilderCatalogService catalogService;
    private final SpringAiAgentChatService agentChatService;
    private final BotChatService botChatService;
    private final ModelService modelService;
    private final PlatformAccountService platformAccountService;
    private final SkillFileService skillFileService;
    private final BotService botService;
    private final BotDatasetService botDatasetService;
    private final MassDatasetInfoService massDatasetInfoService;
    private final AgentToolRuntimeService agentToolRuntimeService;
    private final AgentWorkflowRuntimeService agentWorkflowRuntimeService;
    private final SubAgentPreparer subAgentPreparer;

    /** sessionId -> reply start time; one builder reply per session at a time. */
    private final Map<String, Long> replying = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ sessions

    public AgentBuildSessionDto createSession(String uid, Long spaceId, AgentBuilderRequests.CreateSession req,
            HttpServletRequest request) {
        String goal = StringUtils.trimToEmpty(req.getGoal());
        String context = attachmentContext(req.getAttachments());
        if (goal.isEmpty() && context.isEmpty()) {
            throw new BusinessException(ResponseEnum.PARAMETER_ERROR);
        }
        AgentBuilderCatalog catalog = catalogService.load(uid, spaceId, request);
        AgentBuilderDraft draft = new AgentBuilderDraft();
        if (!catalog.models().isEmpty()) {
            draft.setModel(AgentBuilderTools.toDraftModel(catalog.models().get(0)));
        }

        AgentBuildSession session = new AgentBuildSession();
        session.setId(UUID.randomUUID().toString().replace("-", ""));
        session.setUid(uid);
        session.setSpaceId(spaceId);
        session.setTitle(StringUtils.abbreviate(StringUtils.defaultIfBlank(goal, attachmentNames(req.getAttachments())), 60));
        session.setDraftJson(JSON.toJSONString(draft));
        session.setDraftHistory("[]");
        session.setMessagesJson(JSON.toJSONString(List.of(userMessage(goal, req.getAttachments(), context))));
        session.setStatus("draft");
        session.setIsDelete(0);
        session.setCreateTime(LocalDateTime.now());
        session.setUpdateTime(LocalDateTime.now());
        store.insert(session);
        log.info("Agent builder session created, sessionId={}, uid={}, spaceId={}, goalChars={}, attachmentChars={}",
                session.getId(), uid, spaceId, goal.length(), context.length());
        return toDto(session);
    }

    public AgentBuildSessionDto getSession(String uid, Long spaceId, String sessionId) {
        return toDto(store.requireOwned(uid, spaceId, sessionId));
    }

    public List<AgentBuildSessionDto> listSessions(String uid, Long spaceId, int limit) {
        return store.listOwned(uid, spaceId, limit).stream().map(s -> {
            AgentBuildSessionDto dto = new AgentBuildSessionDto();
            dto.setId(s.getId());
            dto.setTitle(s.getTitle());
            dto.setStatus(s.getStatus());
            dto.setBotId(s.getBotId());
            dto.setCreateTime(s.getCreateTime());
            dto.setUpdateTime(s.getUpdateTime());
            // Only the name is needed to tell drafts apart in the list.
            AgentBuilderDraft summary = new AgentBuilderDraft();
            summary.setName(store.readDraft(s).getName());
            dto.setDraft(summary);
            return dto;
        }).toList();
    }

    public void deleteSession(String uid, Long spaceId, String sessionId) {
        store.requireOwned(uid, spaceId, sessionId);
        store.softDelete(sessionId);
        log.info("Agent builder session deleted, sessionId={}, uid={}", sessionId, uid);
    }

    public AgentBuildSessionDto updateDraft(String uid, Long spaceId, String sessionId, AgentBuilderDraft incoming) {
        AgentBuildSession session = requireEditable(uid, spaceId, sessionId);
        store.mutateDraft(session.getId(), "manual", draft -> applyManualEdit(draft, incoming));
        return toDto(store.requireOwned(uid, spaceId, sessionId));
    }

    public AgentBuildSessionDto undo(String uid, Long spaceId, String sessionId) {
        requireEditable(uid, spaceId, sessionId);
        if (store.undo(sessionId) == null) {
            throw new BusinessException(ResponseEnum.PARAMETER_ERROR);
        }
        return toDto(store.requireOwned(uid, spaceId, sessionId));
    }

    // ------------------------------------------------------------------ builder chat

    /** Streams one builder turn. Everything that needs request context happens before streaming. */
    public SseEmitter chat(String uid, Long spaceId, String sessionId, AgentBuilderRequests.Chat req,
            HttpServletRequest request) {
        String sseId = "ab" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        AgentBuildSession session;
        try {
            session = requireEditable(uid, spaceId, sessionId);
        } catch (BusinessException e) {
            SseEmitterUtil.completeWithError(emitter, e.getMessage());
            return emitter;
        }
        if (!acquireReply(sessionId)) {
            log.info("Agent builder chat rejected, already replying, sessionId={}", sessionId);
            SseEmitterUtil.completeWithError(emitter, "构建助手正在回复，请稍候");
            return emitter;
        }
        emitter.onCompletion(() -> releaseReply(sessionId, sseId));
        emitter.onTimeout(() -> releaseReply(sessionId, sseId));
        emitter.onError(e -> releaseReply(sessionId, sseId));
        try {
            String text = StringUtils.trimToEmpty(req.getText());
            List<Map<String, Object>> messages = store.readMessages(session);
            if (text.isEmpty() && (req.getAttachments() == null || req.getAttachments().isEmpty())) {
                if (messages.isEmpty() || !"user".equals(messages.get(messages.size() - 1).get("role"))) {
                    throw new BusinessException(ResponseEnum.PARAMETER_ERROR);
                }
            } else {
                Map<String, Object> userMessage = userMessage(text, req.getAttachments(), attachmentContext(req.getAttachments()));
                store.appendMessage(sessionId, userMessage);
                messages.add(userMessage);
            }

            AgentBuilderCatalog catalog = catalogService.load(uid, spaceId, request);
            AgentBuilderDraft draft = store.readDraft(session);
            LLMInfoVo llmInfo = resolveBuilderModel(draft, catalog, uid, spaceId);

            List<SparkChatRequest.MessageDto> modelMessages = new ArrayList<>();
            modelMessages.add(message("system", AgentBuilderPrompts.builderSystemPrompt(draft, catalog)));
            int from = Math.max(0, messages.size() - MAX_HISTORY_MESSAGES);
            for (Map<String, Object> m : messages.subList(from, messages.size())) {
                modelMessages.add(message(String.valueOf(m.get("role")), modelContent(m)));
            }

            AgentChatTask task = AgentChatTask.builder()
                    .llmInfoVo(llmInfo)
                    .sparkModelName(llmInfo == null ? DEFAULT_SPARK_MODEL : null)
                    .messages(modelMessages)
                    .userId(uid)
                    .spaceId(spaceId)
                    .debug(true)
                    .builtinToolsDisabled(true)
                    .extraToolCallbacks(AgentBuilderTools.build(sessionId, catalog, store))
                    .extraBody(builderExtraBody(llmInfo))
                    .onComplete(answer -> onBuilderReply(sessionId, sseId, answer))
                    .build();
            log.info("Agent builder chat start, sessionId={}, sseId={}, historyMessages={}, model={}",
                    sessionId, sseId, modelMessages.size() - 1, llmInfo == null ? "default" : llmInfo.getDomain());
            sendStart(emitter, sseId);
            agentChatService.chat(task, emitter, sseId);
        } catch (BusinessException e) {
            log.warn("Agent builder chat setup rejected, sessionId={}, code={}", sessionId, e.getCode());
            releaseReply(sessionId, sseId);
            SseEmitterUtil.completeWithError(emitter, e.getMessage());
        } catch (Exception e) {
            log.error("Agent builder chat setup failed, sessionId={}, errorType={}", sessionId,
                    e.getClass().getSimpleName(), e);
            releaseReply(sessionId, sseId);
            SseEmitterUtil.completeWithError(emitter, "构建助手暂时不可用，请稍后重试");
        }
        return emitter;
    }

    private void onBuilderReply(String sessionId, String sseId, String answer) {
        if (StringUtils.isNotBlank(answer)) {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", "assistant");
            message.put("content", answer);
            message.put("time", System.currentTimeMillis());
            store.appendMessage(sessionId, message);
        }
        log.info("Agent builder reply saved, sessionId={}, sseId={}, answerChars={}", sessionId, sseId,
                answer == null ? 0 : answer.length());
        releaseReply(sessionId, sseId);
    }

    /**
     * Builder model: the draft's model when set, otherwise the first custom model, otherwise the
     * platform default chat model ({@code null}, routed by AI capability config).
     */
    private LLMInfoVo resolveBuilderModel(AgentBuilderDraft draft, AgentBuilderCatalog catalog, String uid, Long spaceId) {
        Long modelId = draft.getModel() != null ? draft.getModel().getModelId() : null;
        if (modelId == null && !catalog.models().isEmpty()) {
            modelId = catalog.models().get(0).modelId();
        }
        if (modelId == null) {
            return null;
        }
        LLMInfoVo info = modelService.getRuntimeModelDetail(modelId, uid, spaceId);
        if (info == null) {
            log.warn("Agent builder model unavailable, falling back to default, modelId={}", modelId);
        }
        return info;
    }

    /** Ark reasoning makes each builder turn take a minute or more; tool editing does not need it. */
    private Map<String, Object> builderExtraBody(LLMInfoVo llmInfo) {
        boolean volcengine = llmInfo != null
                ? StringUtils.containsIgnoreCase(llmInfo.getUrl(), "volces.com")
                : platformAccountService.routesToVolcengine(AiCapability.DEFAULT_CHAT);
        return volcengine ? Map.of("thinking", Map.of("type", "disabled")) : null;
    }

    // ------------------------------------------------------------------ trial run

    /** Runs the draft through the regular debug chat path, so the trial matches the created agent. */
    public SseEmitter trial(String uid, Long spaceId, String sessionId, AgentBuilderRequests.Trial req) {
        String sseId = "at" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        SseEmitter emitter = SseEmitterUtil.createSseEmitter();
        try {
            if (StringUtils.isBlank(req.getText())) {
                throw new BusinessException(ResponseEnum.PARAMETER_ERROR);
            }
            AgentBuildSession session = store.requireOwned(uid, spaceId, sessionId);
            AgentBuilderDraft draft = store.readDraft(session);
            if (draft.getModel() == null || draft.getModel().getModelId() == null) {
                SseEmitterUtil.completeWithError(emitter, "请先为智能体选择模型");
                return emitter;
            }
            List<String> flowIds = draft.getWorkflows().stream().map(AgentBuilderDraft.Workflow::getFlowId).toList();
            if (!agentWorkflowRuntimeService.checkWorkflowsAccessible(uid, spaceId, JSON.toJSONString(workflowEntries(draft)))) {
                SseEmitterUtil.completeWithError(emitter, "Workflow not accessible");
                return emitter;
            }
            DebugChatBotReqDto dto = new DebugChatBotReqDto();
            dto.setText(req.getText().trim());
            dto.setUid(uid);
            dto.setSpaceId(spaceId);
            dto.setPrompt(AgentBuilderPrompts.effectivePrompt(draft, true));
            dto.setMessages(req.getHistory() == null ? new ArrayList<>() : new ArrayList<>(req.getHistory()));
            dto.setSkills(JSON.toJSONString(librarySkillEntries(draft)));
            dto.setTools(JSON.toJSONString(toolEntries(draft)));
            dto.setWorkflows(JSON.toJSONString(workflowEntries(draft)));
            dto.setAgents(JSON.toJSONString(agentEntries(draft)));
            dto.setModel(draft.getModel().getModelDomain());
            dto.setModelId(draft.getModel().getModelId());
            dto.setMaasDatasetList(draft.getKnowledge().stream().map(k -> String.valueOf(k.getId())).toList());
            log.info("Agent builder trial start, sessionId={}, sseId={}, skills={}, tools={}, workflows={}, agents={}, knowledge={}",
                    sessionId, sseId, draft.getSkills().size(), draft.getTools().size(), flowIds.size(),
                    draft.getAgents().size(), draft.getKnowledge().size());
            sendStart(emitter, sseId);
            botChatService.debugChatMessageBot(dto, emitter, sseId);
        } catch (BusinessException e) {
            SseEmitterUtil.completeWithError(emitter, e.getMessage());
        } catch (Exception e) {
            log.error("Agent builder trial failed, sessionId={}, errorType={}", sessionId, e.getClass().getSimpleName(), e);
            SseEmitterUtil.completeWithError(emitter, "试运行失败，请稍后重试");
        }
        return emitter;
    }

    // ------------------------------------------------------------------ publish

    /**
     * Turns the draft into a regular bot with the same checks as {@code POST /bot/create}. Draft skills
     * are saved to the skill library first (request-scoped skill service, so this must run on the
     * request thread).
     */
    @Transactional(rollbackFor = Exception.class)
    public Integer publish(String uid, Long spaceId, String sessionId) {
        AgentBuildSession session = requireEditable(uid, spaceId, sessionId);
        AgentBuilderDraft draft = store.readDraft(session);
        if (StringUtils.isBlank(draft.getName())) {
            throw new BusinessException(ResponseEnum.PARAMETER_ERROR);
        }
        if (draft.getModel() == null || draft.getModel().getModelId() == null) {
            throw new BusinessException(ResponseEnum.PARAMETER_ERROR);
        }
        List<Long> datasetIds = draft.getKnowledge()
                .stream()
                .map(AgentBuilderDraft.Knowledge::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        BotCreateForm form = toBotForm(draft, datasetIds);
        if (!botDatasetService.checkDatasetBelong(uid, spaceId, form.getDatasetList())
                || !agentToolRuntimeService.checkToolsAccessible(uid, spaceId,
                        form.getTools().stream().map(BotCreateForm.BotTool::getToolId).toList())
                || !agentWorkflowRuntimeService.checkWorkflowsAccessible(uid, spaceId,
                        form.getWorkflows().stream().map(BotCreateForm.BotWorkflow::getFlowId).toList())) {
            log.warn("Agent builder publish rejected by ownership check, sessionId={}, uid={}", sessionId, uid);
            throw new BusinessException(ResponseEnum.BOT_BELONG_ERROR);
        }

        ResponseEnum agentError = subAgentPreparer.validateAgents(uid, spaceId,
                form.getAgents().stream().map(BotCreateForm.BotAgent::getBotId).toList(), null);
        if (agentError != null) {
            log.warn("Agent builder publish rejected by sub-agent check, sessionId={}, error={}", sessionId, agentError);
            throw new BusinessException(agentError);
        }
        form.setSkills(persistSkills(draft));
        BotInfoDto botInfo = botService.insertBotBasicInfo(uid, form, spaceId);
        Integer botId = botInfo.getBotId();
        if (!datasetIds.isEmpty()) {
            massDatasetInfoService.botAssociateDataset(uid, botId, datasetIds, form.getSupportDocument());
        }
        store.markPublished(sessionId, botId);
        log.info("Agent builder published, sessionId={}, botId={}, skills={}, tools={}, workflows={}, agents={}, knowledge={}",
                sessionId, botId, form.getSkills().size(), form.getTools().size(), form.getWorkflows().size(),
                form.getAgents().size(), datasetIds.size());
        return botId;
    }

    BotCreateForm toBotForm(AgentBuilderDraft draft, List<Long> datasetIds) {
        BotCreateForm form = new BotCreateForm();
        form.setName(draft.getName().trim());
        form.setBotDesc(StringUtils.defaultString(draft.getDescription()));
        form.setBotType(draft.getBotType() == null ? DEFAULT_BOT_TYPE : draft.getBotType());
        form.setAvatar(StringUtils.defaultIfBlank(draft.getAvatar(), DEFAULT_AVATAR));
        form.setPrologue(draft.getPrologue());
        form.setInputExample(draft.getInputExamples());
        form.setPrompt(AgentBuilderPrompts.effectivePrompt(draft, false));
        form.setPromptType(0);
        form.setSupportContext(1);
        form.setIsSentence(0);
        form.setModel(draft.getModel().getModelDomain());
        form.setModelId(draft.getModel().getModelId());
        form.setMaasDatasetList(datasetIds);
        form.setSupportDocument(datasetIds.isEmpty() ? 0 : 1);
        form.setTools(draft.getTools().stream().map(t -> {
            BotCreateForm.BotTool tool = new BotCreateForm.BotTool();
            tool.setToolId(t.getToolId());
            tool.setName(t.getName());
            tool.setDescription(t.getDescription());
            tool.setIcon(t.getIcon());
            return tool;
        }).collect(Collectors.toCollection(ArrayList::new)));
        form.setWorkflows(draft.getWorkflows().stream().map(w -> {
            BotCreateForm.BotWorkflow workflow = new BotCreateForm.BotWorkflow();
            workflow.setFlowId(w.getFlowId());
            workflow.setName(w.getName());
            workflow.setDescription(w.getDescription());
            return workflow;
        }).collect(Collectors.toCollection(ArrayList::new)));
        form.setAgents(draft.getAgents().stream().map(a -> {
            BotCreateForm.BotAgent agent = new BotCreateForm.BotAgent();
            agent.setBotId(a.getBotId());
            agent.setName(a.getName());
            agent.setDescription(a.getDescription());
            agent.setAvatar(a.getAvatar());
            return agent;
        }).collect(Collectors.toCollection(ArrayList::new)));
        return form;
    }

    /**
     * Library skills are referenced as-is; draft skills become a folder + SKILL.md in the skill
     * library.
     */
    private List<BotCreateForm.BotSkill> persistSkills(AgentBuilderDraft draft) {
        List<BotCreateForm.BotSkill> result = new ArrayList<>();
        Set<String> takenFolders = null;
        for (AgentBuilderDraft.Skill skill : draft.getSkills()) {
            Long skillId = skill.getSkillId();
            if ("draft".equals(skill.getSource())) {
                if (takenFolders == null) {
                    takenFolders = skillFileService.listTree(null)
                            .stream()
                            .map(SkillFileTreeNodeDto::getName)
                            .collect(Collectors.toCollection(HashSet::new));
                }
                String folder = uniqueFolderName(skill.getFolderName(), takenFolders);
                SkillFileCreateFolderReq folderReq = new SkillFileCreateFolderReq();
                folderReq.setParentId(0L);
                folderReq.setName(folder);
                SkillFileTreeNodeDto folderNode = skillFileService.createFolder(folderReq);
                SkillFileCreateReq fileReq = new SkillFileCreateReq();
                fileReq.setParentId(folderNode.getId());
                fileReq.setName("SKILL.md");
                fileReq.setContent(skill.getContent());
                SkillFileContentDto file = skillFileService.createFile(fileReq);
                skillId = file.getId();
                log.info("Agent builder saved draft skill, folder={}, skillFileId={}", folder, skillId);
            }
            if (skillId == null) {
                continue;
            }
            BotCreateForm.BotSkill botSkill = new BotCreateForm.BotSkill();
            botSkill.setSkillId(skillId);
            botSkill.setName(skill.getName());
            botSkill.setDescription(skill.getDescription());
            result.add(botSkill);
        }
        return result;
    }

    static String uniqueFolderName(String base, Set<String> taken) {
        String name = StringUtils.defaultIfBlank(base, "skill");
        String candidate = name;
        for (int i = 2; taken.contains(candidate); i++) {
            candidate = name + "-" + i;
        }
        taken.add(candidate);
        return candidate;
    }

    // ------------------------------------------------------------------ helpers

    private AgentBuildSession requireEditable(String uid, Long spaceId, String sessionId) {
        AgentBuildSession session = store.requireOwned(uid, spaceId, sessionId);
        if ("published".equals(session.getStatus())) {
            throw new BusinessException(ResponseEnum.PARAMETER_ERROR);
        }
        return session;
    }

    private boolean acquireReply(String sessionId) {
        long now = System.currentTimeMillis();
        Long previous = replying.putIfAbsent(sessionId, now);
        if (previous == null) {
            return true;
        }
        if (now - previous > REPLY_LOCK_STALE_MS) {
            log.warn("Agent builder reply lock was stale, taking over, sessionId={}", sessionId);
            return replying.replace(sessionId, previous, now);
        }
        return false;
    }

    private void releaseReply(String sessionId, String sseId) {
        if (replying.remove(sessionId) != null) {
            log.debug("Agent builder reply lock released, sessionId={}, sseId={}", sessionId, sseId);
        }
    }

    private static void sendStart(SseEmitter emitter, String sseId) {
        SseEmitterUtil.sendData(emitter, Map.of(
                "type", "start",
                "sseId", sseId,
                "chatId", 0,
                "botId", 0,
                "timestamp", System.currentTimeMillis()));
    }

    static String applyManualEdit(AgentBuilderDraft draft, AgentBuilderDraft incoming) {
        draft.setName(StringUtils.left(StringUtils.trimToNull(incoming.getName()), AgentBuilderTools.MAX_NAME));
        draft.setDescription(StringUtils.left(StringUtils.trimToNull(incoming.getDescription()), AgentBuilderTools.MAX_DESCRIPTION));
        draft.setBotType(incoming.getBotType());
        draft.setAvatar(StringUtils.trimToNull(incoming.getAvatar()));
        draft.setPrologue(StringUtils.left(StringUtils.trimToNull(incoming.getPrologue()), AgentBuilderTools.MAX_PROLOGUE));
        List<String> examples = incoming.getInputExamples() == null ? List.of()
                : incoming.getInputExamples()
                        .stream()
                        .map(StringUtils::trimToNull)
                        .filter(Objects::nonNull)
                        .map(e -> StringUtils.left(e, AgentBuilderTools.MAX_EXAMPLE_LENGTH))
                        .limit(AgentBuilderTools.MAX_EXAMPLES)
                        .toList();
        draft.setInputExamples(new ArrayList<>(examples));
        draft.setInstructions(StringUtils.left(incoming.getInstructions(), AgentBuilderTools.MAX_INSTRUCTIONS));
        if (incoming.getModel() != null && incoming.getModel().getModelId() != null) {
            draft.setModel(incoming.getModel());
        }
        // Capabilities can only be removed by hand; adding goes through the builder or the config page,
        // and publish re-checks ownership of everything that remains.
        draft.setSkills(keepExisting(draft.getSkills(), incoming.getSkills(),
                s -> s.getSkillId() != null ? "id:" + s.getSkillId() : "folder:" + s.getFolderName()));
        draft.setKnowledge(keepExisting(draft.getKnowledge(), incoming.getKnowledge(), k -> String.valueOf(k.getId())));
        draft.setTools(keepExisting(draft.getTools(), incoming.getTools(), AgentBuilderDraft.Tool::getToolId));
        draft.setWorkflows(keepExisting(draft.getWorkflows(), incoming.getWorkflows(),
                AgentBuilderDraft.Workflow::getFlowId));
        draft.setAgents(keepExisting(draft.getAgents(), incoming.getAgents(), a -> String.valueOf(a.getBotId())));
        if (incoming.getRunModes() != null) {
            draft.setRunModes(incoming.getRunModes());
        }
        if (incoming.getApprovals() != null) {
            draft.setApprovals(incoming.getApprovals()
                    .stream()
                    .filter(a -> a != null && StringUtils.isNotBlank(a.getAction()))
                    .limit(AgentBuilderTools.MAX_APPROVALS)
                    .collect(Collectors.toCollection(ArrayList::new)));
        }
        draft.setBuildNote(StringUtils.left(incoming.getBuildNote(), AgentBuilderTools.MAX_NOTE));
        // Draft skill bodies may be edited by hand.
        if (incoming.getSkills() != null) {
            for (AgentBuilderDraft.Skill edited : incoming.getSkills()) {
                if (!"draft".equals(edited.getSource()) || StringUtils.isBlank(edited.getContent())) {
                    continue;
                }
                draft.getSkills()
                        .stream()
                        .filter(s -> "draft".equals(s.getSource()) && Objects.equals(s.getFolderName(), edited.getFolderName()))
                        .findFirst()
                        .ifPresent(s -> {
                            String content = StringUtils.left(edited.getContent(), AgentBuilderTools.MAX_SKILL_CONTENT);
                            SkillFrontmatter meta = SkillFrontmatter.parse(content);
                            s.setContent(content);
                            if (StringUtils.isNotBlank(meta.name())) {
                                s.setName(meta.name());
                            }
                            if (StringUtils.isNotBlank(meta.description())) {
                                s.setDescription(meta.description());
                            }
                        });
            }
        }
        return "手动编辑草稿";
    }

    private static <T> List<T> keepExisting(List<T> current, List<T> incoming, java.util.function.Function<T, String> key) {
        if (incoming == null) {
            return current;
        }
        Set<String> keep = incoming.stream().filter(Objects::nonNull).map(key).collect(Collectors.toSet());
        return current.stream()
                .filter(item -> keep.contains(key.apply(item)))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private static List<Map<String, Object>> librarySkillEntries(AgentBuilderDraft draft) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (AgentBuilderDraft.Skill skill : draft.getSkills()) {
            if (skill.getSkillId() == null || "draft".equals(skill.getSource())) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("skillId", skill.getSkillId());
            entry.put("name", skill.getName());
            entry.put("description", skill.getDescription());
            entries.add(entry);
        }
        return entries;
    }

    private static List<Map<String, Object>> toolEntries(AgentBuilderDraft draft) {
        return draft.getTools().stream().map(t -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("toolId", t.getToolId());
            entry.put("name", t.getName());
            entry.put("description", t.getDescription());
            entry.put("icon", t.getIcon());
            return entry;
        }).toList();
    }

    private static List<Map<String, Object>> agentEntries(AgentBuilderDraft draft) {
        return draft.getAgents().stream().map(a -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("botId", a.getBotId());
            entry.put("name", a.getName());
            entry.put("description", a.getDescription());
            return entry;
        }).toList();
    }

    private static List<Map<String, Object>> workflowEntries(AgentBuilderDraft draft) {
        return draft.getWorkflows().stream().map(w -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("flowId", w.getFlowId());
            entry.put("name", w.getName());
            entry.put("description", w.getDescription());
            return entry;
        }).toList();
    }

    private static SparkChatRequest.MessageDto message(String role, String content) {
        SparkChatRequest.MessageDto dto = new SparkChatRequest.MessageDto();
        dto.setRole("assistant".equals(role) ? "assistant" : "system".equals(role) ? "system" : "user");
        dto.setContent(content);
        return dto;
    }

    private static String modelContent(Map<String, Object> message) {
        String content = StringUtils.defaultString((String) message.get("content"));
        Object context = message.get("context");
        if (context instanceof String text && StringUtils.isNotBlank(text)) {
            return content + "\n\n" + text;
        }
        return content;
    }

    static Map<String, Object> userMessage(String text, List<AgentBuilderRequests.Attachment> attachments, String context) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", StringUtils.defaultIfBlank(text, "请根据附件内容帮我搭建智能体。"));
        if (attachments != null && !attachments.isEmpty()) {
            message.put("attachments", attachments.stream()
                    .map(a -> Map.of(
                            "name", StringUtils.defaultString(a.getName(), "附件"),
                            "chars", StringUtils.length(a.getContent())))
                    .toList());
        }
        if (StringUtils.isNotBlank(context)) {
            message.put("context", context);
        }
        message.put("time", System.currentTimeMillis());
        return message;
    }

    static String attachmentContext(List<AgentBuilderRequests.Attachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (AgentBuilderRequests.Attachment attachment : attachments) {
            String content = StringUtils.trimToEmpty(attachment.getContent());
            if (content.isEmpty()) {
                continue;
            }
            int remaining = MAX_ATTACHMENT_CHARS - sb.length();
            if (remaining <= 200) {
                break;
            }
            sb.append("【用户上传的文件：").append(StringUtils.defaultString(attachment.getName(), "附件")).append("】\n");
            if (content.length() > remaining) {
                sb.append(content, 0, remaining).append("\n…（文件过长，已截断）");
            } else {
                sb.append(content);
            }
            sb.append("\n\n");
        }
        return sb.toString().strip();
    }

    private static String attachmentNames(List<AgentBuilderRequests.Attachment> attachments) {
        if (attachments == null) {
            return "";
        }
        return attachments.stream()
                .map(AgentBuilderRequests.Attachment::getName)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.joining("、"));
    }

    private AgentBuildSessionDto toDto(AgentBuildSession session) {
        AgentBuildSessionDto dto = new AgentBuildSessionDto();
        dto.setId(session.getId());
        dto.setTitle(session.getTitle());
        dto.setStatus(session.getStatus());
        dto.setBotId(session.getBotId());
        dto.setDraft(store.readDraft(session));
        List<Map<String, Object>> messages = store.readMessages(session);
        // The attachment text is model input only; the page shows the file names.
        messages.forEach(m -> m.remove("context"));
        dto.setMessages(messages);
        dto.setUndoDepth(store.undoDepth(session));
        dto.setReplying(replying.containsKey(session.getId()));
        dto.setCreateTime(session.getCreateTime());
        dto.setUpdateTime(session.getUpdateTime());
        return dto;
    }
}
