package com.iflytek.astron.console.hub.dto.agentbuilder;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Request bodies for {@code /agent-builder}. */
public final class AgentBuilderRequests {

    private AgentBuilderRequests() {}

    @Data
    public static class Attachment {
        @Size(max = 255)
        private String name;
        /** Plain text read on the client (txt / md / csv). Truncated server-side. */
        private String content;
    }

    @Data
    public static class CreateSession {
        @Size(max = 4000)
        private String goal;
        @Valid
        @Size(max = 5)
        private List<Attachment> attachments = new ArrayList<>();
    }

    @Data
    public static class Chat {
        /** Blank means "reply to the pending last user message" (first turn after creation). */
        @Size(max = 4000)
        private String text;
        @Valid
        @Size(max = 5)
        private List<Attachment> attachments = new ArrayList<>();
    }

    @Data
    public static class UpdateDraft {
        @NotNull
        private AgentBuilderDraft draft;
    }

    @Data
    public static class Trial {
        @Size(max = 4000)
        private String text;
        /** Previous trial turns, alternating user / assistant text. */
        @Size(max = 40)
        private List<String> history = new ArrayList<>();
    }
}
