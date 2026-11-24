package com.iflytek.astron.console.hub.dto.agentbuilder;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Draft agent configuration edited by the agent builder. Nothing here takes effect until the user
 * confirms creation; then it is converted to a regular bot.
 */
@Data
@NoArgsConstructor
public class AgentBuilderDraft {

    private String name;
    private String description;
    /** Bot type key from {@code /bot/type-list}; defaults to 24 (other). */
    private Integer botType;
    private String avatar;
    private String prologue;
    private List<String> inputExamples = new ArrayList<>();
    /** System instructions (the bot prompt). */
    private String instructions;
    private Model model;
    private List<Skill> skills = new ArrayList<>();
    private List<Knowledge> knowledge = new ArrayList<>();
    private List<Tool> tools = new ArrayList<>();
    /** Legacy: kept so older drafts still load and publish; new drafts attach agents instead. */
    private List<Workflow> workflows = new ArrayList<>();
    /** Agents the main agent routes to by intent ({@code call_agent_<botId>} tools). */
    private List<SubAgent> agents = new ArrayList<>();
    private RunModes runModes = new RunModes();
    private List<Approval> approvals = new ArrayList<>();
    /** Builder's notes: open questions, assumptions, what to verify. */
    private String buildNote;
    private List<Change> changes = new ArrayList<>();

    @Data
    @NoArgsConstructor
    public static class Model {
        private Long modelId;
        private String modelDomain;
        private String modelName;
    }

    /**
     * A skill is either picked from the skill library ({@code source=library}, {@code skillId} set) or
     * written by the builder ({@code source=draft}, {@code content} holds SKILL.md). Draft skills are
     * saved to the skill library only when the agent is created.
     */
    @Data
    @NoArgsConstructor
    public static class Skill {
        private Long skillId;
        private String name;
        private String description;
        private String folderName;
        private String content;
        private String source;
    }

    @Data
    @NoArgsConstructor
    public static class Knowledge {
        private Long id;
        private String name;
    }

    @Data
    @NoArgsConstructor
    public static class Tool {
        private String toolId;
        private String name;
        private String description;
        private String icon;
    }

    @Data
    @NoArgsConstructor
    public static class Workflow {
        private String flowId;
        private String name;
        private String description;
    }

    @Data
    @NoArgsConstructor
    public static class SubAgent {
        private Integer botId;
        private String name;
        private String description;
        private String avatar;
    }

    @Data
    @NoArgsConstructor
    public static class RunModes {
        private Boolean chat = Boolean.TRUE;
        private Schedule schedule = new Schedule();
        private Wecom wecom = new Wecom();
    }

    @Data
    @NoArgsConstructor
    public static class Schedule {
        private Boolean enabled = Boolean.FALSE;
        /** Human-readable schedule, e.g. "每个工作日 9:00". Recorded only; not executed yet. */
        private String description;
    }

    @Data
    @NoArgsConstructor
    public static class Wecom {
        private Boolean enabled = Boolean.FALSE;
    }

    @Data
    @NoArgsConstructor
    public static class Approval {
        private String action;
        private String reason;
    }

    @Data
    @NoArgsConstructor
    public static class Change {
        private Long at;
        /** Builder tool name, or {@code manual} / {@code undo}. */
        private String tool;
        private String summary;
    }
}
