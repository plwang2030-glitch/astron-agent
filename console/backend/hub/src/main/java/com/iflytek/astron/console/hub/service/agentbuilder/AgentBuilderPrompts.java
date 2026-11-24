package com.iflytek.astron.console.hub.service.agentbuilder;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.iflytek.astron.console.hub.dto.agentbuilder.AgentBuilderDraft;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

/** Prompt text for the builder agent and for the agent being built. */
public final class AgentBuilderPrompts {

    private static final int SKILL_PREVIEW_CHARS = 300;

    private AgentBuilderPrompts() {}

    static final String BUILDER_ROLE = """
            你是 SkyLumin 的「构建助手」，通过对话帮用户搭建一个智能体。你用工具修改右侧的智能体草稿；\
            草稿在用户点击「确认创建」之前不会生效，也不会影响任何已有的智能体或工作流。

            ## 工作方式
            1. 先弄清需求。信息不够时，一次只追问 1~3 个最关键的问题（谁在用、输入是什么、要产出什么、要用哪些资料或系统、哪些操作有风险）。\
            用户已经说清楚的不要重复问；用户说"你看着办"时，按常见做法给出合理默认并写进构建备注。
            2. 信息够了就动手，按需调用工具：
               - update_profile：名称、简介、分类、开场白、示例问题；
               - update_instructions：系统指令，写清角色、目标、工作步骤、输出格式、边界和禁止事项；
               - upsert_skill：把可复用的做事方法（尤其是用户给的 SOP）写成 SKILL.md 技能；
               - attach_skills / attach_knowledge / attach_tools：从目录里挑合适的资源挂上；
               - attach_agents：需求涉及多个分工明确的领域，且目录里已有对应的智能体时，把它们添加进来，\
            并在系统指令里写清分派规则（什么问题交给哪个智能体，都不匹配时自己回答）；
               - set_model、set_run_modes（对话 / 定时 / 企业微信）、set_approvals（需要人工确认的操作）；
               - write_build_note：记录假设、待确认问题和试跑时要验证的点。
               每次只调用一个工具，按 基础信息 → 系统指令 → 技能 → 知识库/工具/智能体 → 运行方式与人工确认 → 构建备注 的顺序逐步修改，\
            不要一次并行调用多个工具，这样用户能在右侧实时看到草稿一项项变化。
            3. 改完后用几句话告诉用户改了什么、为什么，并建议下一步，例如在右侧「试运行」用一个真实样例试一下。
            4. 用户上传的 SOP 或文件是最重要的依据，技能和指令要忠实反映其中的步骤和规则，不要自行删减关键约束。

            ## 规则
            - 只能使用下方目录里列出的 id。目录里没有合适的资源时，如实告诉用户需要先到「资源管理」创建，不要编造 id。
            - 工具返回 ok=false 时，按 error 修正参数重试，或向用户说明原因。
            - 定时运行和企业微信目前只记录在草稿里，平台还不会自动执行；涉及时要如实说明。
            - 需要人工确认的操作，目前通过系统指令约束智能体在执行前先征得用户同意。
            - 你不能发布或创建智能体，也不要声称已经发布；只有用户点击「确认创建」才会创建。
            - 回复使用中文 Markdown，简洁，不要复述整份配置。
            """;

    public static String builderSystemPrompt(AgentBuilderDraft draft, AgentBuilderCatalog catalog) {
        return BUILDER_ROLE
                + "\n## 当前草稿\n```json\n" + draftPreview(draft) + "\n```\n"
                + "\n## 可用资源目录\n" + catalog.toPromptText();
    }

    /** Draft JSON for the builder prompt; SKILL.md bodies are shortened and the change log dropped. */
    static String draftPreview(AgentBuilderDraft draft) {
        JSONObject json = AgentBuilderTools.draftForModel(draft);
        JSONArray skills = json.getJSONArray("skills");
        if (skills != null) {
            for (int i = 0; i < skills.size(); i++) {
                JSONObject skill = skills.getJSONObject(i);
                String content = skill == null ? null : skill.getString("content");
                if (content != null && content.length() > SKILL_PREVIEW_CHARS) {
                    skill.put("content", content.substring(0, SKILL_PREVIEW_CHARS) + "…（已截断，需要全文时调用 get_draft）");
                }
            }
        }
        return json.toJSONString(JSONWriter.Feature.PrettyFormat);
    }

    /**
     * Prompt the created agent actually runs with: the instructions plus the human-approval rules. When
     * {@code inlineDraftSkills} is set (trial runs, before draft skills exist in the skill library) the
     * SKILL.md bodies are appended so the trial behaves like the final agent.
     */
    public static String effectivePrompt(AgentBuilderDraft draft, boolean inlineDraftSkills) {
        StringBuilder sb = new StringBuilder(StringUtils.defaultString(draft.getInstructions()).strip());
        List<AgentBuilderDraft.Approval> approvals = draft.getApprovals();
        if (approvals != null && !approvals.isEmpty()) {
            sb.append("\n\n## 需要人工确认的操作\n")
                    .append("执行下列操作前，必须先向用户说明将要做什么，并等待用户明确回复同意后才能继续；用户未同意时不得执行：\n");
            for (AgentBuilderDraft.Approval approval : approvals) {
                sb.append("- ").append(approval.getAction());
                if (StringUtils.isNotBlank(approval.getReason())) {
                    sb.append("（").append(approval.getReason()).append("）");
                }
                sb.append('\n');
            }
        }
        if (inlineDraftSkills && draft.getSkills() != null) {
            List<AgentBuilderDraft.Skill> drafts = draft.getSkills()
                    .stream()
                    .filter(s -> "draft".equals(s.getSource()) && StringUtils.isNotBlank(s.getContent()))
                    .toList();
            if (!drafts.isEmpty()) {
                sb.append("\n\n## 技能\n遇到与下列技能描述相符的任务时，按对应技能的步骤执行。\n");
                for (AgentBuilderDraft.Skill skill : drafts) {
                    sb.append("\n### 技能：").append(skill.getName()).append("\n").append(skill.getContent().strip()).append('\n');
                }
            }
        }
        return sb.toString().strip();
    }
}
