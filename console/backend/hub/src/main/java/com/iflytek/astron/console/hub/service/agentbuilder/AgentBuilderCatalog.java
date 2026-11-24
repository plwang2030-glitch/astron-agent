package com.iflytek.astron.console.hub.service.agentbuilder;

import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Resources the current user can attach to a draft agent. Loaded on the request thread (it depends
 * on request-scoped user / space context) and then used by the builder's tools on the streaming
 * thread to validate IDs the model picks.
 */
public record AgentBuilderCatalog(
        List<SkillItem> skills,
        List<KnowledgeItem> knowledge,
        List<ToolItem> tools,
        List<AgentItem> agents,
        List<ModelItem> models,
        List<BotTypeItem> botTypes) {

    public record SkillItem(Long skillId, String name, String description, String folderName) {}

    public record KnowledgeItem(Long id, String name, String description) {}

    public record ToolItem(String toolId, String name, String description, String icon) {}

    /** Instruction-type agent in the same space; attached agents become call_agent tools. */
    public record AgentItem(Integer botId, String name, String description, String avatar) {}

    public record ModelItem(Long modelId, String name, String domain, String icon) {}

    public record BotTypeItem(Integer typeKey, String typeName) {}

    public static AgentBuilderCatalog empty() {
        return new AgentBuilderCatalog(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public Optional<SkillItem> findSkill(Long skillId) {
        return skills.stream().filter(s -> Objects.equals(s.skillId(), skillId)).findFirst();
    }

    public Optional<KnowledgeItem> findKnowledge(Long id) {
        return knowledge.stream().filter(k -> Objects.equals(k.id(), id)).findFirst();
    }

    public Optional<ToolItem> findTool(String toolId) {
        return tools.stream().filter(t -> StringUtils.equals(t.toolId(), toolId)).findFirst();
    }

    public Optional<AgentItem> findAgent(Integer botId) {
        return agents.stream().filter(a -> Objects.equals(a.botId(), botId)).findFirst();
    }

    public Optional<ModelItem> findModel(Long modelId) {
        return models.stream().filter(m -> Objects.equals(m.modelId(), modelId)).findFirst();
    }

    public boolean hasBotType(Integer typeKey) {
        return botTypes.stream().anyMatch(t -> Objects.equals(t.typeKey(), typeKey));
    }

    /** Compact text listing for the builder's system prompt. */
    public String toPromptText() {
        StringBuilder sb = new StringBuilder();
        sb.append("### 可用模型 (set_model 用 modelId)\n");
        appendLines(sb, models.stream().map(m -> "- modelId=" + m.modelId() + " " + m.name()).toList());
        sb.append("\n### 智能体分类 (update_profile.botType)\n");
        appendLines(sb, botTypes.stream().map(t -> "- " + t.typeKey() + " " + t.typeName()).toList());
        sb.append("\n### 技能库中已有的技能 (attach_skills 用 skillId)\n");
        appendLines(sb, skills.stream()
                .map(s -> "- skillId=" + s.skillId() + " " + s.name() + "：" + shorten(s.description())).toList());
        sb.append("\n### 知识库 (attach_knowledge 用 id)\n");
        appendLines(sb, knowledge.stream()
                .map(k -> "- id=" + k.id() + " " + k.name() + "：" + shorten(k.description())).toList());
        sb.append("\n### 插件工具 (attach_tools 用 toolId)\n");
        appendLines(sb, tools.stream()
                .map(t -> "- toolId=" + t.toolId() + " " + t.name() + "：" + shorten(t.description())).toList());
        sb.append("\n### 本空间已发布的其他智能体，按用户意图把问题分派给它们 (attach_agents 用 botId；未发布的智能体不在此列，需先发布)\n");
        appendLines(sb, agents.stream()
                .map(a -> "- botId=" + a.botId() + " " + a.name() + "：" + shorten(a.description())).toList());
        return sb.toString();
    }

    private static void appendLines(StringBuilder sb, List<String> lines) {
        if (lines.isEmpty()) {
            sb.append("（无）\n");
            return;
        }
        lines.forEach(line -> sb.append(line).append('\n'));
    }

    private static String shorten(String text) {
        String value = StringUtils.normalizeSpace(StringUtils.defaultString(text));
        return StringUtils.abbreviate(value, 80);
    }
}
