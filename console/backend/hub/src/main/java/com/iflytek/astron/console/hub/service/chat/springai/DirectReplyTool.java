package com.iflytek.astron.console.hub.service.chat.springai;

import org.springframework.ai.tool.ToolCallback;

/**
 * A tool whose answer can be streamed to the user as the turn's final reply. When a model response
 * asks for exactly one such tool, {@link BlankToolCallFilteringToolCallingManager} switches it to
 * direct mode before the call and, if the user received the answer, ends the turn with Spring AI's
 * {@code returnDirect} so the model is not called again to repeat it.
 */
interface DirectReplyTool extends ToolCallback {

    /** Configured as 直接回复用户. */
    boolean directReply();

    /** The next call streams its answer to the user while it is generated. */
    void startDirectReply();

    /**
     * Leaves direct mode.
     *
     * @return {@code true} when (part of) the answer already reached the user, so the turn must end
     */
    boolean finishDirectReply();
}
