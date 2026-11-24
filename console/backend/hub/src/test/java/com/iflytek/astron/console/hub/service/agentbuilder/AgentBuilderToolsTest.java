package com.iflytek.astron.console.hub.service.agentbuilder;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.iflytek.astron.console.hub.dto.agentbuilder.AgentBuilderDraft;
import com.iflytek.astron.console.hub.entity.AgentBuildSession;
import com.iflytek.astron.console.hub.mapper.AgentBuildSessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentBuilderToolsTest {

    private static final String SESSION = "s1";

    private final Map<String, AgentBuildSession> rows = new HashMap<>();
    private AgentBuildSessionStore store;
    private Map<String, ToolCallback> tools;

    @BeforeEach
    void setUp() {
        AgentBuildSessionMapper mapper = mock(AgentBuildSessionMapper.class);
        when(mapper.selectById(any())).thenAnswer(inv -> rows.get((String) inv.getArgument(0)));
        when(mapper.updateById(any(AgentBuildSession.class))).thenAnswer(inv -> {
            AgentBuildSession update = inv.getArgument(0);
            AgentBuildSession row = rows.get(update.getId());
            if (update.getDraftJson() != null) {
                row.setDraftJson(update.getDraftJson());
            }
            if (update.getDraftHistory() != null) {
                row.setDraftHistory(update.getDraftHistory());
            }
            return 1;
        });
        AgentBuildSession row = new AgentBuildSession();
        row.setId(SESSION);
        row.setIsDelete(0);
        row.setDraftJson(JSON.toJSONString(new AgentBuilderDraft()));
        row.setDraftHistory("[]");
        rows.put(SESSION, row);
        store = new AgentBuildSessionStore(mapper);

        AgentBuilderCatalog catalog = new AgentBuilderCatalog(
                List.of(new AgentBuilderCatalog.SkillItem(11L, "周报", "汇总周报", "weekly")),
                List.of(new AgentBuilderCatalog.KnowledgeItem(21L, "产品手册", "")),
                List.of(new AgentBuilderCatalog.ToolItem("tool@a", "查天气", "", null)),
                List.of(new AgentBuilderCatalog.AgentItem(6, "客服助手", "售后问题", null)),
                List.of(new AgentBuilderCatalog.ModelItem(1L, "seedpro", "doubao-seed", null)),
                List.of(new AgentBuilderCatalog.BotTypeItem(24, "其他")));
        tools = new HashMap<>();
        for (ToolCallback t : AgentBuilderTools.build(SESSION, catalog, store)) {
            tools.put(t.getToolDefinition().name(), t);
        }
    }

    private JSONObject call(String tool, String args) {
        return JSON.parseObject(tools.get(tool).call(args));
    }

    @Test
    void attachRejectsIdsOutsideCatalogAndKeepsDraftUnchanged() {
        JSONObject result = call("attach_tools", "{\"toolIds\":[\"tool@evil\"]}");
        assertFalse(result.getBooleanValue("ok"));
        assertTrue(store.readDraft(SESSION).getTools().isEmpty());
        assertEquals(0, store.undoDepth(rows.get(SESSION)));
    }

    @Test
    void attachAddsCatalogItemsOnceAndRecordsChange() {
        assertTrue(call("attach_tools", "{\"toolIds\":[\"tool@a\"]}").getBooleanValue("ok"));
        assertTrue(call("attach_tools", "{\"toolIds\":[\"tool@a\"]}").getBooleanValue("ok"));
        assertTrue(call("attach_knowledge", "{\"ids\":[21]}").getBooleanValue("ok"));
        assertTrue(call("attach_agents", "{\"botIds\":[6]}").getBooleanValue("ok"));
        assertTrue(call("attach_skills", "{\"skillIds\":[11]}").getBooleanValue("ok"));
        AgentBuilderDraft draft = store.readDraft(SESSION);
        assertEquals(1, draft.getTools().size());
        assertEquals("查天气", draft.getTools().get(0).getName());
        assertEquals(21L, draft.getKnowledge().get(0).getId());
        assertEquals(6, draft.getAgents().get(0).getBotId());
        assertEquals("客服助手", draft.getAgents().get(0).getName());
        assertEquals("library", draft.getSkills().get(0).getSource());
        assertEquals(4, draft.getChanges().size(), "re-attaching an existing tool records no change");
    }

    @Test
    void attachAgentsRejectsUnknownBotAndRemoveAgentWorks() {
        assertFalse(call("attach_agents", "{\"botIds\":[99]}").getBooleanValue("ok"));
        assertTrue(store.readDraft(SESSION).getAgents().isEmpty());
        assertTrue(call("attach_agents", "{\"botIds\":[6]}").getBooleanValue("ok"));
        assertTrue(call("remove_capability", "{\"type\":\"agent\",\"id\":\"6\"}").getBooleanValue("ok"));
        assertTrue(store.readDraft(SESSION).getAgents().isEmpty());
    }

    @Test
    void upsertSkillRequiresFrontmatterAndReplacesByFolder() {
        assertFalse(call("upsert_skill", "{\"folderName\":\"report\",\"content\":\"no frontmatter\"}").getBooleanValue("ok"));
        assertFalse(call("upsert_skill",
                "{\"folderName\":\"Bad Name\",\"content\":\"---\\nname: a\\ndescription: b\\n---\\nbody\"}")
                .getBooleanValue("ok"));
        assertTrue(call("upsert_skill",
                "{\"folderName\":\"report\",\"content\":\"---\\nname: 日报\\ndescription: 写日报\\n---\\n步骤一\"}")
                .getBooleanValue("ok"));
        assertTrue(call("upsert_skill",
                "{\"folderName\":\"report\",\"content\":\"---\\nname: 日报 v2\\ndescription: 写日报\\n---\\n步骤二\"}")
                .getBooleanValue("ok"));
        AgentBuilderDraft draft = store.readDraft(SESSION);
        assertEquals(1, draft.getSkills().size());
        assertEquals("日报 v2", draft.getSkills().get(0).getName());
        assertEquals("draft", draft.getSkills().get(0).getSource());
    }

    @Test
    void undoRestoresPreviousDraft() {
        call("update_profile", "{\"name\":\"客服助手\"}");
        call("update_profile", "{\"name\":\"售后助手\"}");
        assertEquals("售后助手", store.readDraft(SESSION).getName());
        store.undo(SESSION);
        assertEquals("客服助手", store.readDraft(SESSION).getName());
        store.undo(SESSION);
        assertNull(store.readDraft(SESSION).getName());
        assertNull(store.undo(SESSION));
    }

    @Test
    void profileValidatesLengthsAndBotType() {
        assertFalse(call("update_profile", "{\"name\":\"" + "长".repeat(21) + "\"}").getBooleanValue("ok"));
        assertFalse(call("update_profile", "{\"botType\":999}").getBooleanValue("ok"));
        assertTrue(call("update_profile", "{\"botType\":24,\"inputExamples\":[\"a\",\"b\",\"c\",\"d\"]}").getBooleanValue("ok"));
        assertEquals(3, store.readDraft(SESSION).getInputExamples().size());
    }

    @Test
    void effectivePromptAddsApprovalRulesAndInlinesDraftSkillsOnlyForTrial() {
        AgentBuilderDraft draft = new AgentBuilderDraft();
        draft.setInstructions("你是客服。");
        AgentBuilderDraft.Approval approval = new AgentBuilderDraft.Approval();
        approval.setAction("退款");
        approval.setReason("涉及资金");
        draft.getApprovals().add(approval);
        AgentBuilderDraft.Skill skill = new AgentBuilderDraft.Skill();
        skill.setSource("draft");
        skill.setName("退款流程");
        skill.setContent("---\nname: 退款流程\ndescription: x\n---\n先核对订单");
        draft.getSkills().add(skill);

        String trial = AgentBuilderPrompts.effectivePrompt(draft, true);
        String published = AgentBuilderPrompts.effectivePrompt(draft, false);
        assertTrue(trial.contains("退款（涉及资金）"));
        assertTrue(trial.contains("先核对订单"));
        assertTrue(published.contains("退款（涉及资金）"));
        assertFalse(published.contains("先核对订单"));
    }

    @Test
    void frontmatterParsing() {
        SkillFrontmatter meta = SkillFrontmatter.parse("---\nname: \"周报\"\ndescription: 每周五汇总\n---\n正文");
        assertEquals("周报", meta.name());
        assertEquals("每周五汇总", meta.description());
        assertNull(SkillFrontmatter.parse("---\nname: a\n").name(), "unterminated frontmatter is rejected");
    }

    @Test
    void uniqueFolderNameAvoidsCollisions() {
        Set<String> taken = new HashSet<>(Set.of("report", "report-2"));
        assertEquals("report-3", AgentBuilderService.uniqueFolderName("report", taken));
        assertEquals("other", AgentBuilderService.uniqueFolderName("other", taken));
    }
}
