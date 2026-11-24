package com.iflytek.astron.console.hub.service.chat.springai;

import com.alibaba.fastjson2.JSONObject;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Request-scoped context shared with tool callbacks during a single agent chat turn: collects tool
 * traces, forwards progress notices, and carries the turn's deadline and cancellation so nested
 * sub-agent calls stop when the turn ends.
 */
public class ChatToolContext {

    private final String userId;
    private final List<JSONObject> traceEntries = new ArrayList<>();
    private volatile Consumer<JSONObject> progressListener;
    /** Epoch millis by which tool work (sub-agents) must finish; {@code Long.MAX_VALUE} = none. */
    private volatile long deadlineMillis = Long.MAX_VALUE;
    private final Sinks.One<Boolean> cancelSink = Sinks.one();
    private volatile boolean cancelled;
    private volatile Consumer<JSONObject> teamListener;
    private volatile Consumer<String> answerListener;
    private volatile boolean answerDelivered;
    /** Background turns allow sub-agents much longer than an interactive turn. */
    private volatile boolean longRunning;
    private final List<BoardNote> board = new ArrayList<>();

    /** A note on the team's shared board for this turn. */
    public record BoardNote(String author, String text, boolean result) {}

    /** Notes kept on the board; older ones are dropped. */
    private static final int MAX_BOARD_NOTES = 30;

    public ChatToolContext(String userId) {
        this.userId = userId;
    }

    public String getUserId() {
        return userId;
    }

    /** The agent the user is chatting with (the team lead for expert calls); may be {@code null}. */
    private volatile Integer botId;

    public Integer getBotId() {
        return botId;
    }

    public void setBotId(Integer botId) {
        this.botId = botId;
    }

    /** Receives "tool started" notices so the client can show what the turn is waiting on. */
    public void setProgressListener(Consumer<JSONObject> progressListener) {
        this.progressListener = progressListener;
    }

    /** Pushes a progress notice to the client; it is not part of the saved trace. */
    public void emitProgress(JSONObject entry) {
        Consumer<JSONObject> listener = progressListener;
        if (listener != null && entry != null) {
            listener.accept(entry);
        }
    }

    /**
     * Receives expert-team progress events (start / partial output / finish of each sub-agent call).
     */
    public void setTeamListener(Consumer<JSONObject> teamListener) {
        this.teamListener = teamListener;
    }

    public void emitTeam(JSONObject event) {
        Consumer<JSONObject> listener = teamListener;
        if (listener != null && event != null) {
            listener.accept(event);
        }
    }

    /** Receives answer text that a 直接回复用户 expert streams as the turn's reply. */
    public void setAnswerListener(Consumer<String> answerListener) {
        this.answerListener = answerListener;
    }

    /** Streams reply text to the user on behalf of the lead (direct reply). */
    public void emitAnswer(String text) {
        Consumer<String> listener = answerListener;
        if (listener != null && text != null && !text.isEmpty()) {
            answerDelivered = true;
            listener.accept(text);
        }
    }

    /**
     * An expert's answer was streamed to the user as the reply, so its returned copy must not be sent
     * again.
     */
    public boolean isAnswerDelivered() {
        return answerDelivered;
    }

    public boolean isLongRunning() {
        return longRunning;
    }

    public void setLongRunning(boolean longRunning) {
        this.longRunning = longRunning;
    }

    /** Adds a note to the shared board; {@code result} marks an expert's final answer. */
    public synchronized void postNote(String author, String text, boolean result) {
        if (text == null || text.isBlank()) {
            return;
        }
        board.add(new BoardNote(author, text, result));
        while (board.size() > MAX_BOARD_NOTES) {
            board.remove(0);
        }
    }

    public synchronized List<BoardNote> boardNotes() {
        return List.copyOf(board);
    }

    public void setDeadlineMillis(long deadlineMillis) {
        this.deadlineMillis = deadlineMillis;
    }

    /** Milliseconds left before the deadline (may be negative). */
    public long remainingMillis() {
        long deadline = deadlineMillis;
        return deadline == Long.MAX_VALUE ? Long.MAX_VALUE : deadline - System.currentTimeMillis();
    }

    /** Marks the turn as over (finished, stopped by the user, failed or timed out). Idempotent. */
    public void cancel() {
        cancelled = true;
        cancelSink.tryEmitValue(Boolean.TRUE);
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /** Emits once {@link #cancel()} is called, also for late subscribers. */
    public Mono<Boolean> cancelSignal() {
        return cancelSink.asMono();
    }

    public synchronized void addTrace(JSONObject entry) {
        if (entry != null) {
            traceEntries.add(entry);
        }
    }

    /** Returns the collected entries and clears the buffer so the loop can emit them per round. */
    public synchronized List<JSONObject> drainTrace() {
        List<JSONObject> copy = new ArrayList<>(traceEntries);
        traceEntries.clear();
        return copy;
    }
}
