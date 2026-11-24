package com.iflytek.astron.console.hub.service.agentbuilder;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.iflytek.astron.console.commons.entity.bot.BotTypeList;
import com.iflytek.astron.console.commons.service.bot.BotService;
import com.iflytek.astron.console.hub.service.agentbuilder.AgentBuilderCatalog.AgentItem;
import com.iflytek.astron.console.hub.service.agentbuilder.AgentBuilderCatalog.BotTypeItem;
import com.iflytek.astron.console.hub.service.agentbuilder.AgentBuilderCatalog.KnowledgeItem;
import com.iflytek.astron.console.hub.service.agentbuilder.AgentBuilderCatalog.ModelItem;
import com.iflytek.astron.console.hub.service.agentbuilder.AgentBuilderCatalog.SkillItem;
import com.iflytek.astron.console.hub.service.agentbuilder.AgentBuilderCatalog.ToolItem;
import com.iflytek.astron.console.hub.service.chat.springai.SubAgentPreparer;
import com.iflytek.astron.console.toolkit.entity.common.PageData;
import com.iflytek.astron.console.toolkit.entity.dto.RepoDto;
import com.iflytek.astron.console.toolkit.entity.dto.ToolBoxVo;
import com.iflytek.astron.console.toolkit.entity.dto.ToolSquareDto;
import com.iflytek.astron.console.toolkit.entity.dto.skill.SkillImportDto;
import com.iflytek.astron.console.toolkit.service.model.LLMService;
import com.iflytek.astron.console.toolkit.service.repo.RepoService;
import com.iflytek.astron.console.toolkit.service.skill.SkillFileService;
import com.iflytek.astron.console.toolkit.service.tool.ToolBoxService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Loads the attachable resources for the agent builder through the same services the config page
 * uses, so the builder only ever sees what the user could pick manually. Each source is loaded
 * independently: one failing source yields an empty list instead of breaking the builder turn. Must
 * run on the request thread.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentBuilderCatalogService {

    private static final int MAX_ITEMS = 100;

    private final SkillFileService skillFileService;
    private final RepoService repoService;
    private final ToolBoxService toolBoxService;
    private final SubAgentPreparer subAgentPreparer;
    private final LLMService llmService;
    private final BotService botService;

    public AgentBuilderCatalog load(String uid, Long spaceId, HttpServletRequest request) {
        long start = System.currentTimeMillis();
        AgentBuilderCatalog catalog = new AgentBuilderCatalog(
                safe("skills", () -> loadSkills(uid, spaceId)),
                safe("knowledge", () -> loadKnowledge(request)),
                safe("tools", this::loadTools),
                safe("agents", () -> loadAgents(uid, spaceId)),
                safe("models", () -> loadModels(request)),
                safe("botTypes", this::loadBotTypes));
        log.info(
                "Agent builder catalog loaded, uid={}, spaceId={}, skills={}, knowledge={}, tools={}, agents={}, models={}, costMs={}",
                uid, spaceId, catalog.skills().size(), catalog.knowledge().size(), catalog.tools().size(),
                catalog.agents().size(), catalog.models().size(), System.currentTimeMillis() - start);
        return catalog;
    }

    private <T> List<T> safe(String source, Supplier<List<T>> loader) {
        try {
            List<T> items = loader.get();
            return items == null ? List.of() : items;
        } catch (Exception e) {
            log.warn("Agent builder catalog source failed, source={}, errorType={}, msg={}", source,
                    e.getClass().getSimpleName(), e.getMessage());
            return List.of();
        }
    }

    private List<SkillItem> loadSkills(String uid, Long spaceId) {
        List<SkillImportDto> skills = skillFileService.listImportableSkills(null, uid, spaceId);
        return skills.stream()
                .limit(MAX_ITEMS)
                .map(s -> new SkillItem(s.getId(), s.getName(), s.getDescription(), s.getFolderName()))
                .toList();
    }

    private List<KnowledgeItem> loadKnowledge(HttpServletRequest request) {
        PageData<RepoDto> page = repoService.list(1, MAX_ITEMS, null, "create_time", request, null);
        if (page == null || page.getPageData() == null) {
            return List.of();
        }
        return page.getPageData()
                .stream()
                .filter(r -> r.getId() != null)
                .map(r -> new KnowledgeItem(r.getId(), r.getName(), r.getDescription()))
                .toList();
    }

    /** Same query and filter as the config page's plugin picker (PluginSelectModal). */
    private List<ToolItem> loadTools() {
        Map<String, ToolItem> byId = new LinkedHashMap<>();
        ToolSquareDto squareQuery = new ToolSquareDto();
        squareQuery.setPage(1);
        squareQuery.setPageSize(MAX_ITEMS);
        // orderFlag is required: the tool square sorts by it and NPEs on null.
        squareQuery.setOrderFlag(0);
        PageData<ToolBoxVo> page = toolBoxService.listToolSquare(squareQuery);
        if (page == null || page.getPageData() == null) {
            return List.of();
        }
        for (ToolBoxVo tool : page.getPageData()) {
            // MCP tools have their own capability block and are not attachable as plugin tools.
            if (tool == null || Boolean.TRUE.equals(tool.getIsMcp()) || StringUtils.isBlank(tool.getToolId())) {
                continue;
            }
            byId.putIfAbsent(tool.getToolId(),
                    new ToolItem(tool.getToolId(), tool.getName(), tool.getDescription(), tool.getIcon()));
        }
        return List.copyOf(byId.values());
    }

    /**
     * Same list and rules as the config page's 添加智能体 picker: instruction-type agents in scope whose
     * reference chain leaves room under {@link SubAgentPreparer#MAX_DEPTH} for the new agent.
     */
    private List<AgentItem> loadAgents(String uid, Long spaceId) {
        return subAgentPreparer.listCandidates(uid, spaceId, null)
                .candidates()
                .stream()
                .filter(SubAgentPreparer.Candidate::selectable)
                .limit(MAX_ITEMS)
                .map(c -> new AgentItem(c.botId(), c.name(), c.description(), c.avatar()))
                .toList();
    }

    /** Same source and parsing as {@code BotCreateController#botModel}. */
    private List<ModelItem> loadModels(HttpServletRequest request) {
        JSONObject result = JSONObject.from(llmService.getLlmAuthList(request, null, "workflow", "spark-llm"));
        List<ModelItem> models = new ArrayList<>();
        if (result == null) {
            return models;
        }
        JSONArray workflowArray = result.getJSONArray("workflow");
        if (workflowArray == null || workflowArray.size() <= 1) {
            return models;
        }
        JSONObject custom = workflowArray.getJSONObject(1);
        JSONArray modelList = custom == null ? null : custom.getJSONArray("modelList");
        if (modelList == null) {
            return models;
        }
        for (int i = 0; i < modelList.size(); i++) {
            JSONObject m = modelList.getJSONObject(i);
            if (m == null || m.getLong("id") == null) {
                continue;
            }
            models.add(new ModelItem(m.getLong("id"), m.getString("name"), m.getString("domain"), m.getString("icon")));
        }
        return models;
    }

    private List<BotTypeItem> loadBotTypes() {
        List<BotTypeList> types = botService.getBotTypeList();
        return types.stream()
                .filter(t -> t.getTypeKey() != null)
                .map(t -> new BotTypeItem(t.getTypeKey(), t.getTypeName()))
                .toList();
    }
}
