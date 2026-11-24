package com.iflytek.astron.console.hub.service.agentbuilder;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.iflytek.astron.console.hub.dto.agentbuilder.AgentBuilderDraft;
import com.iflytek.astron.console.hub.service.chat.springai.SubAgentPreparer;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The agent builder's tool set. Every tool edits only the draft stored in
 * {@code agent_build_session}; nothing is created or published until the user confirms. IDs the
 * model passes are validated against the catalog the user can actually access.
 */
public final class AgentBuilderTools {

    static final int MAX_NAME = 20;
    static final int MAX_DESCRIPTION = 100;
    static final int MAX_PROLOGUE = 500;
    static final int MAX_EXAMPLES = 3;
    static final int MAX_EXAMPLE_LENGTH = 50;
    static final int MAX_INSTRUCTIONS = 16000;
    static final int MAX_SKILL_CONTENT = 20000;
    static final int MAX_DRAFT_SKILLS = 10;
    static final int MAX_APPROVALS = 20;
    static final int MAX_NOTE = 4000;
    static final Pattern FOLDER_NAME = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");

    private AgentBuilderTools() {}

    public static List<ToolCallback> build(String sessionId, AgentBuilderCatalog catalog, AgentBuildSessionStore store) {
        List<ToolCallback> tools = new ArrayList<>();

        tools.add(new AgentBuilderToolCallback(sessionId, "get_draft",
                "读取当前智能体草稿的完整配置。修改前不确定现状时调用。", "{\"type\":\"object\",\"properties\":{}}",
                args -> AgentBuilderToolCallback.ok("当前草稿", draftForModel(store.readDraft(sessionId)))));

        tools.add(new AgentBuilderToolCallback(sessionId, "update_profile",
                "设置智能体的名称(≤20字)、简介(≤100字)、分类 botType、开场白 prologue、示例问题 inputExamples(≤3条，每条≤50字)。只传需要修改的字段。",
                """
                        {"type":"object","properties":{
                        "name":{"type":"string"},"description":{"type":"string"},
                        "botType":{"type":"integer"},"prologue":{"type":"string"},
                        "inputExamples":{"type":"array","items":{"type":"string"}}}}""",
                args -> {
                    AgentBuilderDraft d = store.mutateDraft(sessionId, "update_profile", draft -> updateProfile(draft, args, catalog));
                    return AgentBuilderToolCallback.ok("已更新基础信息：" + d.getName(), null);
                }));

        tools.add(new AgentBuilderToolCallback(sessionId, "update_instructions",
                "整体替换智能体的系统指令（角色、目标、工作步骤、输出格式、边界）。用 Markdown 书写。",
                """
                        {"type":"object","properties":{"instructions":{"type":"string"}},"required":["instructions"]}""",
                args -> {
                    String text = requireText(args, "instructions", MAX_INSTRUCTIONS);
                    store.mutateDraft(sessionId, "update_instructions", draft -> {
                        draft.setInstructions(text);
                        return "更新系统指令（" + text.length() + " 字）";
                    });
                    return AgentBuilderToolCallback.ok("已更新系统指令", null);
                }));

        tools.add(new AgentBuilderToolCallback(sessionId, "upsert_skill",
                "把一段可复用的做事方法写成技能文件 SKILL.md（新建，或按 folderName 覆盖已有草稿技能）。"
                        + "content 必须以 YAML frontmatter 开头，包含 name 和 description，例如 ---\\nname: 周报汇总\\ndescription: 何时使用、做什么\\n---，"
                        + "正文写清楚适用场景、步骤、输入输出和注意事项。folderName 用小写字母、数字和连字符。",
                """
                        {"type":"object","properties":{"folderName":{"type":"string"},"content":{"type":"string"}},
                        "required":["folderName","content"]}""",
                args -> upsertSkill(sessionId, store, args)));

        tools.add(new AgentBuilderToolCallback(sessionId, "attach_skills",
                "从技能库挂载已有技能，skillIds 必须来自目录中的 skillId。",
                """
                        {"type":"object","properties":{"skillIds":{"type":"array","items":{"type":"integer"}}},"required":["skillIds"]}""",
                args -> attachSkills(sessionId, store, catalog, args)));

        tools.add(new AgentBuilderToolCallback(sessionId, "attach_knowledge",
                "挂载知识库，ids 必须来自目录中的知识库 id。",
                """
                        {"type":"object","properties":{"ids":{"type":"array","items":{"type":"integer"}}},"required":["ids"]}""",
                args -> attachKnowledge(sessionId, store, catalog, args)));

        tools.add(new AgentBuilderToolCallback(sessionId, "attach_tools",
                "挂载插件工具，toolIds 必须来自目录中的 toolId。",
                """
                        {"type":"object","properties":{"toolIds":{"type":"array","items":{"type":"string"}}},"required":["toolIds"]}""",
                args -> attachTools(sessionId, store, catalog, args)));

        tools.add(new AgentBuilderToolCallback(sessionId, "attach_agents",
                "添加本空间的其他智能体，对话时由模型按用户意图把问题交给它们处理（最多 " + SubAgentPreparer.MAX_AGENTS
                        + " 个）。botIds 必须来自目录。分派规则写进系统指令，例如“售后问题交给客服助手”。",
                """
                        {"type":"object","properties":{"botIds":{"type":"array","items":{"type":"integer"}}},"required":["botIds"]}""",
                args -> attachAgents(sessionId, store, catalog, args)));

        tools.add(new AgentBuilderToolCallback(sessionId, "remove_capability",
                "从草稿移除一项能力。type 取 skill / knowledge / tool / agent / workflow；id 为 skillId 或草稿技能的 folderName、知识库 id、toolId、智能体 botId、flowId。",
                """
                        {"type":"object","properties":{"type":{"type":"string","enum":["skill","knowledge","tool","agent","workflow"]},
                        "id":{"type":"string"}},"required":["type","id"]}""",
                args -> removeCapability(sessionId, store, args)));

        tools.add(new AgentBuilderToolCallback(sessionId, "set_model",
                "设置智能体使用的模型，modelId 必须来自目录。",
                """
                        {"type":"object","properties":{"modelId":{"type":"integer"}},"required":["modelId"]}""",
                args -> setModel(sessionId, store, catalog, args)));

        tools.add(new AgentBuilderToolCallback(sessionId, "set_run_modes",
                "设置运行方式：chat 对话；schedule 定时（enabled + 用中文描述时间，例如每个工作日 9:00）；wecom 企业微信。只传需要修改的字段。",
                """
                        {"type":"object","properties":{"chat":{"type":"boolean"},
                        "schedule":{"type":"object","properties":{"enabled":{"type":"boolean"},"description":{"type":"string"}}},
                        "wecom":{"type":"object","properties":{"enabled":{"type":"boolean"}}}}}""",
                args -> setRunModes(sessionId, store, args)));

        tools.add(new AgentBuilderToolCallback(sessionId, "set_approvals",
                "整体替换需要人工确认的操作列表。action 写清楚哪类操作（如 对外发送邮件、修改订单金额），reason 写为什么要确认。",
                """
                        {"type":"object","properties":{"approvals":{"type":"array","items":{"type":"object",
                        "properties":{"action":{"type":"string"},"reason":{"type":"string"}},"required":["action"]}}},
                        "required":["approvals"]}""",
                args -> setApprovals(sessionId, store, args)));

        tools.add(new AgentBuilderToolCallback(sessionId, "write_build_note",
                "整体替换构建备注：记录假设、待用户确认的问题、试跑要验证的点。",
                """
                        {"type":"object","properties":{"note":{"type":"string"}},"required":["note"]}""",
                args -> {
                    String note = requireText(args, "note", MAX_NOTE);
                    store.mutateDraft(sessionId, "write_build_note", draft -> {
                        draft.setBuildNote(note);
                        return "更新构建备注";
                    });
                    return AgentBuilderToolCallback.ok("已更新构建备注", null);
                }));
        return tools;
    }

    /** Draft view for the model: drops the change log to keep the context small. */
    static JSONObject draftForModel(AgentBuilderDraft draft) {
        JSONObject json = JSONObject.parseObject(JSON.toJSONString(draft, JSONWriter.Feature.WriteNulls));
        json.remove("changes");
        return json;
    }

    static String updateProfile(AgentBuilderDraft draft, JSONObject args, AgentBuilderCatalog catalog) {
        List<String> changed = new ArrayList<>();
        if (args.containsKey("name")) {
            draft.setName(requireText(args, "name", MAX_NAME));
            changed.add("名称");
        }
        if (args.containsKey("description")) {
            draft.setDescription(optionalText(args, "description", MAX_DESCRIPTION));
            changed.add("简介");
        }
        if (args.containsKey("botType")) {
            Integer botType = args.getInteger("botType");
            if (botType == null || (!catalog.botTypes().isEmpty() && !catalog.hasBotType(botType))) {
                throw new IllegalArgumentException("botType 不在分类目录中");
            }
            draft.setBotType(botType);
            changed.add("分类");
        }
        if (args.containsKey("prologue")) {
            draft.setPrologue(optionalText(args, "prologue", MAX_PROLOGUE));
            changed.add("开场白");
        }
        if (args.containsKey("inputExamples")) {
            JSONArray array = args.getJSONArray("inputExamples");
            List<String> examples = new ArrayList<>();
            if (array != null) {
                for (int i = 0; i < array.size() && examples.size() < MAX_EXAMPLES; i++) {
                    String example = StringUtils.trimToEmpty(array.getString(i));
                    if (StringUtils.isNotEmpty(example)) {
                        examples.add(StringUtils.left(example, MAX_EXAMPLE_LENGTH));
                    }
                }
            }
            draft.setInputExamples(examples);
            changed.add("示例问题");
        }
        if (changed.isEmpty()) {
            throw new IllegalArgumentException("没有提供要修改的字段");
        }
        return "更新" + String.join("、", changed);
    }

    static String upsertSkill(String sessionId, AgentBuildSessionStore store, JSONObject args) {
        String folderName = StringUtils.lowerCase(StringUtils.trimToEmpty(args.getString("folderName")));
        if (!FOLDER_NAME.matcher(folderName).matches()) {
            throw new IllegalArgumentException("folderName 只能包含小写字母、数字和连字符，且以字母或数字开头");
        }
        String content = requireText(args, "content", MAX_SKILL_CONTENT);
        SkillFrontmatter meta = SkillFrontmatter.parse(content);
        if (StringUtils.isBlank(meta.name()) || StringUtils.isBlank(meta.description())) {
            throw new IllegalArgumentException("SKILL.md 必须以 frontmatter 开头并包含 name 和 description");
        }
        boolean[] created = {false};
        store.mutateDraft(sessionId, "upsert_skill", draft -> {
            AgentBuilderDraft.Skill existing = draft.getSkills()
                    .stream()
                    .filter(s -> "draft".equals(s.getSource()) && folderName.equals(s.getFolderName()))
                    .findFirst()
                    .orElse(null);
            if (existing == null) {
                long draftCount = draft.getSkills().stream().filter(s -> "draft".equals(s.getSource())).count();
                if (draftCount >= MAX_DRAFT_SKILLS) {
                    throw new IllegalArgumentException("草稿技能最多 " + MAX_DRAFT_SKILLS + " 个");
                }
                existing = new AgentBuilderDraft.Skill();
                existing.setSource("draft");
                existing.setFolderName(folderName);
                draft.getSkills().add(existing);
                created[0] = true;
            }
            existing.setName(meta.name());
            existing.setDescription(meta.description());
            existing.setContent(content);
            return (created[0] ? "新建技能 " : "修改技能 ") + meta.name();
        });
        return AgentBuilderToolCallback.ok((created[0] ? "已新建技能 " : "已更新技能 ") + meta.name(), null);
    }

    static String attachSkills(String sessionId, AgentBuildSessionStore store, AgentBuilderCatalog catalog,
            JSONObject args) {
        List<AgentBuilderCatalog.SkillItem> items = new ArrayList<>();
        for (Long id : longList(args, "skillIds")) {
            items.add(catalog.findSkill(id)
                    .orElseThrow(() -> new IllegalArgumentException("skillId=" + id + " 不在技能目录中")));
        }
        List<String> names = new ArrayList<>();
        store.mutateDraft(sessionId, "attach_skills", draft -> {
            for (AgentBuilderCatalog.SkillItem item : items) {
                boolean present = draft.getSkills().stream().anyMatch(s -> Objects.equals(s.getSkillId(), item.skillId()));
                if (present) {
                    continue;
                }
                AgentBuilderDraft.Skill skill = new AgentBuilderDraft.Skill();
                skill.setSkillId(item.skillId());
                skill.setName(item.name());
                skill.setDescription(item.description());
                skill.setFolderName(item.folderName());
                skill.setSource("library");
                draft.getSkills().add(skill);
                names.add(item.name());
            }
            return names.isEmpty() ? null : "挂载技能 " + String.join("、", names);
        });
        return AgentBuilderToolCallback.ok(names.isEmpty() ? "这些技能已挂载" : "已挂载技能 " + String.join("、", names), null);
    }

    static String attachKnowledge(String sessionId, AgentBuildSessionStore store, AgentBuilderCatalog catalog,
            JSONObject args) {
        List<AgentBuilderCatalog.KnowledgeItem> items = new ArrayList<>();
        for (Long id : longList(args, "ids")) {
            items.add(catalog.findKnowledge(id)
                    .orElseThrow(() -> new IllegalArgumentException("知识库 id=" + id + " 不在目录中")));
        }
        List<String> names = new ArrayList<>();
        store.mutateDraft(sessionId, "attach_knowledge", draft -> {
            for (AgentBuilderCatalog.KnowledgeItem item : items) {
                if (draft.getKnowledge().stream().anyMatch(k -> Objects.equals(k.getId(), item.id()))) {
                    continue;
                }
                AgentBuilderDraft.Knowledge k = new AgentBuilderDraft.Knowledge();
                k.setId(item.id());
                k.setName(item.name());
                draft.getKnowledge().add(k);
                names.add(item.name());
            }
            return names.isEmpty() ? null : "挂载知识库 " + String.join("、", names);
        });
        return AgentBuilderToolCallback.ok(names.isEmpty() ? "这些知识库已挂载" : "已挂载知识库 " + String.join("、", names), null);
    }

    static String attachTools(String sessionId, AgentBuildSessionStore store, AgentBuilderCatalog catalog,
            JSONObject args) {
        List<AgentBuilderCatalog.ToolItem> items = new ArrayList<>();
        for (String id : stringList(args, "toolIds")) {
            items.add(catalog.findTool(id)
                    .orElseThrow(() -> new IllegalArgumentException("toolId=" + id + " 不在工具目录中")));
        }
        List<String> names = new ArrayList<>();
        store.mutateDraft(sessionId, "attach_tools", draft -> {
            for (AgentBuilderCatalog.ToolItem item : items) {
                if (draft.getTools().stream().anyMatch(t -> StringUtils.equals(t.getToolId(), item.toolId()))) {
                    continue;
                }
                AgentBuilderDraft.Tool t = new AgentBuilderDraft.Tool();
                t.setToolId(item.toolId());
                t.setName(item.name());
                t.setDescription(item.description());
                t.setIcon(item.icon());
                draft.getTools().add(t);
                names.add(item.name());
            }
            return names.isEmpty() ? null : "挂载工具 " + String.join("、", names);
        });
        return AgentBuilderToolCallback.ok(names.isEmpty() ? "这些工具已挂载" : "已挂载工具 " + String.join("、", names), null);
    }

    static String attachAgents(String sessionId, AgentBuildSessionStore store, AgentBuilderCatalog catalog,
            JSONObject args) {
        List<AgentBuilderCatalog.AgentItem> items = new ArrayList<>();
        for (Long id : longList(args, "botIds")) {
            Integer botId = id.intValue();
            items.add(catalog.findAgent(botId)
                    .orElseThrow(() -> new IllegalArgumentException("botId=" + botId + " 不在智能体目录中")));
        }
        List<String> names = new ArrayList<>();
        store.mutateDraft(sessionId, "attach_agents", draft -> {
            for (AgentBuilderCatalog.AgentItem item : items) {
                if (draft.getAgents().stream().anyMatch(a -> Objects.equals(a.getBotId(), item.botId()))) {
                    continue;
                }
                if (draft.getAgents().size() >= SubAgentPreparer.MAX_AGENTS) {
                    throw new IllegalArgumentException("最多添加 " + SubAgentPreparer.MAX_AGENTS + " 个智能体");
                }
                AgentBuilderDraft.SubAgent a = new AgentBuilderDraft.SubAgent();
                a.setBotId(item.botId());
                a.setName(item.name());
                a.setDescription(item.description());
                a.setAvatar(item.avatar());
                draft.getAgents().add(a);
                names.add(item.name());
            }
            return names.isEmpty() ? null : "添加智能体 " + String.join("、", names);
        });
        return AgentBuilderToolCallback.ok(names.isEmpty() ? "这些智能体已添加" : "已添加智能体 " + String.join("、", names), null);
    }

    static String removeCapability(String sessionId, AgentBuildSessionStore store, JSONObject args) {
        String type = StringUtils.trimToEmpty(args.getString("type"));
        String id = StringUtils.trimToEmpty(args.getString("id"));
        if (id.isEmpty()) {
            throw new IllegalArgumentException("缺少 id");
        }
        boolean[] removed = {false};
        store.mutateDraft(sessionId, "remove_capability", draft -> {
            switch (type) {
                case "skill" -> removed[0] = draft.getSkills()
                        .removeIf(
                                s -> id.equals(String.valueOf(s.getSkillId())) || id.equals(s.getFolderName()));
                case "knowledge" -> removed[0] = draft.getKnowledge().removeIf(k -> id.equals(String.valueOf(k.getId())));
                case "tool" -> removed[0] = draft.getTools().removeIf(t -> id.equals(t.getToolId()));
                case "agent" -> removed[0] = draft.getAgents().removeIf(a -> id.equals(String.valueOf(a.getBotId())));
                case "workflow" -> removed[0] = draft.getWorkflows().removeIf(w -> id.equals(w.getFlowId()));
                default -> throw new IllegalArgumentException("type 只能是 skill / knowledge / tool / agent / workflow");
            }
            if (!removed[0]) {
                throw new IllegalArgumentException("草稿中没有这项能力：" + type + " " + id);
            }
            return "移除 " + type + " " + id;
        });
        return AgentBuilderToolCallback.ok("已移除", null);
    }

    static String setModel(String sessionId, AgentBuildSessionStore store, AgentBuilderCatalog catalog,
            JSONObject args) {
        Long modelId = args.getLong("modelId");
        AgentBuilderCatalog.ModelItem item = modelId == null ? null : catalog.findModel(modelId).orElse(null);
        if (item == null) {
            throw new IllegalArgumentException("modelId 不在模型目录中");
        }
        store.mutateDraft(sessionId, "set_model", draft -> {
            draft.setModel(toDraftModel(item));
            return "切换模型为 " + item.name();
        });
        return AgentBuilderToolCallback.ok("已设置模型 " + item.name(), null);
    }

    static AgentBuilderDraft.Model toDraftModel(AgentBuilderCatalog.ModelItem item) {
        AgentBuilderDraft.Model model = new AgentBuilderDraft.Model();
        model.setModelId(item.modelId());
        model.setModelDomain(item.domain());
        model.setModelName(item.name());
        return model;
    }

    static String setRunModes(String sessionId, AgentBuildSessionStore store, JSONObject args) {
        store.mutateDraft(sessionId, "set_run_modes", draft -> {
            AgentBuilderDraft.RunModes modes = draft.getRunModes() == null ? new AgentBuilderDraft.RunModes() : draft.getRunModes();
            if (args.containsKey("chat")) {
                modes.setChat(Boolean.TRUE.equals(args.getBoolean("chat")));
            }
            JSONObject schedule = args.getJSONObject("schedule");
            if (schedule != null) {
                if (schedule.containsKey("enabled")) {
                    modes.getSchedule().setEnabled(Boolean.TRUE.equals(schedule.getBoolean("enabled")));
                }
                if (schedule.containsKey("description")) {
                    modes.getSchedule().setDescription(StringUtils.left(StringUtils.trimToNull(schedule.getString("description")), 200));
                }
            }
            JSONObject wecom = args.getJSONObject("wecom");
            if (wecom != null && wecom.containsKey("enabled")) {
                modes.getWecom().setEnabled(Boolean.TRUE.equals(wecom.getBoolean("enabled")));
            }
            draft.setRunModes(modes);
            return "更新运行方式";
        });
        return AgentBuilderToolCallback.ok("已更新运行方式（定时与企业微信目前只记录在草稿中，平台暂不自动执行）", null);
    }

    static String setApprovals(String sessionId, AgentBuildSessionStore store, JSONObject args) {
        JSONArray array = args.getJSONArray("approvals");
        if (array == null) {
            throw new IllegalArgumentException("缺少 approvals");
        }
        List<AgentBuilderDraft.Approval> approvals = new ArrayList<>();
        for (int i = 0; i < array.size() && approvals.size() < MAX_APPROVALS; i++) {
            JSONObject item = array.getJSONObject(i);
            String action = item == null ? null : StringUtils.trimToNull(item.getString("action"));
            if (action == null) {
                continue;
            }
            AgentBuilderDraft.Approval approval = new AgentBuilderDraft.Approval();
            approval.setAction(StringUtils.left(action, 200));
            approval.setReason(StringUtils.left(StringUtils.trimToNull(item.getString("reason")), 300));
            approvals.add(approval);
        }
        store.mutateDraft(sessionId, "set_approvals", draft -> {
            draft.setApprovals(approvals);
            return "设置 " + approvals.size() + " 项需人工确认的操作";
        });
        return AgentBuilderToolCallback.ok("已设置人工确认规则", null);
    }

    private static String requireText(JSONObject args, String key, int max) {
        String value = StringUtils.trimToNull(args.getString(key));
        if (value == null) {
            throw new IllegalArgumentException("缺少 " + key);
        }
        if (value.length() > max) {
            throw new IllegalArgumentException(key + " 超过 " + max + " 字");
        }
        return value;
    }

    private static String optionalText(JSONObject args, String key, int max) {
        String value = StringUtils.trimToNull(args.getString(key));
        if (value != null && value.length() > max) {
            throw new IllegalArgumentException(key + " 超过 " + max + " 字");
        }
        return value;
    }

    private static List<Long> longList(JSONObject args, String key) {
        JSONArray array = args.getJSONArray(key);
        if (array == null || array.isEmpty()) {
            throw new IllegalArgumentException("缺少 " + key);
        }
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            Long id = array.getLong(i);
            if (id != null && !ids.contains(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    private static List<String> stringList(JSONObject args, String key) {
        JSONArray array = args.getJSONArray(key);
        if (array == null || array.isEmpty()) {
            throw new IllegalArgumentException("缺少 " + key);
        }
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            String id = StringUtils.trimToNull(array.getString(i));
            if (id != null && !ids.contains(id)) {
                ids.add(id);
            }
        }
        return ids;
    }
}
