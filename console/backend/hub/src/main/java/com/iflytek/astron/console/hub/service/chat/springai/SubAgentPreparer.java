package com.iflytek.astron.console.hub.service.chat.springai;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.iflytek.astron.console.commons.entity.user.UserInfo;
import com.iflytek.astron.console.commons.constant.ResponseEnum;
import com.iflytek.astron.console.commons.entity.bot.ChatBotBase;
import com.iflytek.astron.console.commons.entity.bot.ChatBotMarket;
import com.iflytek.astron.console.commons.entity.dataset.BotDatasetMaas;
import com.iflytek.astron.console.commons.enums.bot.BotTypeEnum;
import com.iflytek.astron.console.commons.mapper.bot.ChatBotBaseMapper;
import com.iflytek.astron.console.commons.mapper.bot.ChatBotMarketMapper;
import com.iflytek.astron.console.commons.service.bot.ChatBotDataService;
import com.iflytek.astron.console.commons.service.data.DatasetDataService;
import com.iflytek.astron.console.hub.enums.ConfigTypeEnum;
import com.iflytek.astron.console.hub.service.bot.PersonalityConfigService;
import com.iflytek.astron.console.toolkit.entity.table.model.Model;
import com.iflytek.astron.console.toolkit.entity.vo.LLMInfoVo;
import com.iflytek.astron.console.toolkit.mapper.model.ModelMapper;
import com.iflytek.astron.console.toolkit.service.model.ModelService;
import com.iflytek.astron.console.toolkit.service.skill.SkillEnrichmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Agents attached to an agent ({@code chat_bot_base.agents}): save-time rules, the 「添加智能体」
 * candidate list, and runtime preparation into {@link PreparedSubAgent}s.
 *
 * <p>
 * Rules: a referenced agent must be a published instruction-type agent in the same scope,
 * references must not form a cycle, and a reference chain has at most {@link #MAX_DEPTH} levels
 * (e.g. 办公助手 → 客服助手 → 文案写作助手). Referenced agents run their published version.
 * </p>
 *
 * <p>
 * Everything that needs request context (model lookup, skill enrichment) happens in
 * {@link #prepare}, on the request thread; the resulting tools later run on the model's tool
 * threads.
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubAgentPreparer {

    public static final int MAX_AGENTS = 10;
    /** Maximum reference levels, e.g. 2 allows 办公助手 → 客服助手 → 文案写作助手. */
    public static final int MAX_DEPTH = 2;
    /** Upper bound of sub-agents prepared for one chat turn, across all levels. */
    public static final int MAX_RUNTIME_AGENTS = 20;
    /** Experts picked from the library per turn when auto team is on. */
    public static final int MAX_AUTO_POOL = 6;
    /** Agents shown in the picker. */
    private static final int MAX_DISPLAY_BOTS = 200;
    /**
     * Agents read for the reference graph; far above any real space, only guards against runaway reads.
     */
    private static final int MAX_GRAPH_BOTS = 5000;
    /** Same statuses the config page and 发布管理 show as 已发布. */
    private static final Set<Integer> PUBLISHED_STATUSES = Set.of(1, 2, 4);
    private static final String LOCK_PREFIX = "agent:references:lock:";
    private static final long LOCK_WAIT_SECONDS = 5;
    private static final long LOCK_LEASE_SECONDS = 30;
    /** Placeholder id for an agent that is being created and has no id yet. */
    private static final Integer NEW_AGENT_ID = -1;
    static final String TOOL_PREFIX = "call_agent_";

    private final ChatBotBaseMapper chatBotBaseMapper;
    private final ChatBotMarketMapper chatBotMarketMapper;
    private final ChatBotDataService chatBotDataService;
    private final PersonalityConfigService personalityConfigService;
    private final ModelService modelService;
    private final SkillEnrichmentService skillEnrichmentService;
    private final DatasetDataService datasetDataService;
    private final ModelMapper modelMapper;
    private final RedissonClient redissonClient;

    // ------------------------------------------------------------------ save-time rules

    /**
     * Save-time check, to be called inside the transaction that saves the agents. Holds a per-space
     * lock until that transaction completes, so two concurrent saves cannot each pass against the old
     * references and together form a cycle or a chain over {@link #MAX_DEPTH} levels.
     *
     * @return {@code null} when valid, otherwise the error to return
     */
    public ResponseEnum validateAgents(String uid, Long spaceId, List<Integer> botIds, Integer selfBotId) {
        if (botIds == null || botIds.isEmpty()) {
            return null;
        }
        if (botIds.size() > MAX_AGENTS) {
            log.warn("Too many sub-agents, uid={}, count={}", uid, botIds.size());
            return ResponseEnum.BOT_BELONG_ERROR;
        }
        if (!lockReferences(uid, spaceId)) {
            return ResponseEnum.BOT_AGENT_BUSY;
        }
        for (Integer botId : botIds) {
            if (botId == null || Objects.equals(botId, selfBotId)) {
                log.warn("Sub-agent rejected: self or empty, selfBotId={}, botId={}", selfBotId, botId);
                return ResponseEnum.BOT_BELONG_ERROR;
            }
            ChatBotBase base = chatBotBaseMapper.selectById(botId);
            if (base == null || Objects.equals(base.getIsDelete(), 1)
                    || chatBotBaseMapper.checkBotPermission(botId, uid, spaceId) <= 0) {
                log.warn("Sub-agent not available, uid={}, spaceId={}, botId={}", uid, spaceId, botId);
                return ResponseEnum.BOT_AGENT_NOT_AVAILABLE;
            }
            if (!Objects.equals(base.getVersion(), BotTypeEnum.SYSTEM_BOT.getType())) {
                log.warn("Sub-agent must be an instruction-type agent, botId={}", botId);
                return ResponseEnum.BOT_BELONG_ERROR;
            }
        }
        Graph graph = loadGraph(uid, spaceId);
        Map<Integer, List<Integer>> edges = graph.edgesWithout(selfBotId);
        List<Integer> above = selfBotId == null ? List.of() : longestPathTo(selfBotId, edges);
        for (Integer botId : botIds) {
            if (!graph.published().contains(botId)) {
                log.warn("Sub-agent rejected: not published, selfBotId={}, botId={}", selfBotId, botId);
                return ResponseEnum.BOT_AGENT_NOT_PUBLISHED;
            }
            // A new agent (selfBotId == null) is not referenced by anyone, so it cannot close a cycle.
            List<Integer> path = selfBotId == null ? List.of() : pathTo(botId, selfBotId, edges);
            if (!path.isEmpty()) {
                log.warn("Sub-agent rejected: cycle, selfBotId={}, botId={}, path={}", selfBotId, botId,
                        describe(path, graph.names()));
                return ResponseEnum.BOT_AGENT_CYCLE;
            }
            List<Integer> chain = chainThrough(above, selfBotId, botId, edges);
            if (chain.size() - 1 > MAX_DEPTH) {
                log.warn("Sub-agent rejected: depth, selfBotId={}, botId={}, levels={}, chain={}", selfBotId, botId,
                        chain.size() - 1, describe(chain, graph.names()));
                return ResponseEnum.BOT_AGENT_DEPTH;
            }
        }
        return null;
    }

    /**
     * Per-space (or per-user for personal agents) lock released when the surrounding transaction
     * completes. Without a transaction it is released by its lease.
     */
    private boolean lockReferences(String uid, Long spaceId) {
        String key = LOCK_PREFIX + (spaceId != null ? "space:" + spaceId : "user:" + uid);
        RLock lock = redissonClient.getLock(key);
        try {
            if (!lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS)) {
                log.warn("Sub-agent references busy, key={}", key);
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    unlockQuietly(lock, key);
                }
            });
        } else {
            log.warn("Sub-agent references locked without a transaction; released by lease, key={}", key);
        }
        return true;
    }

    private static void unlockQuietly(RLock lock, String key) {
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (Exception e) {
            log.warn("Sub-agent references unlock failed, key={}, errorType={}", key, e.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------ picker

    /** An agent that can be shown in the 「添加智能体」 picker. */
    public record BotRef(Integer botId, String name) {}

    /**
     * @param selectable whether it can be attached to the current agent
     * @param reason why not, when {@code selectable} is false
     */
    public record Candidate(Integer botId, String name, String description, String avatar, String modelName,
            LocalDateTime updateTime, boolean published, boolean hasKnowledge, int skillCount, int toolCount,
            int mcpCount, int agentCount, List<String> usedBy, boolean selectable, String reason) {}

    /**
     * @param usedBy agents that already use the current agent as a sub-agent (they cannot be added
     *        back)
     * @param level reference levels above the current agent (0 when nobody uses it)
     * @param maxDepth maximum reference levels ({@link #MAX_DEPTH})
     */
    public record CandidateList(List<BotRef> usedBy, int level, int maxDepth, List<Candidate> candidates) {}

    /** Instruction-type agents in the caller's scope, each marked selectable or not with a reason. */
    public CandidateList listCandidates(String uid, Long spaceId, Integer selfBotId) {
        List<ChatBotBase> bots = displayBots(uid, spaceId);
        Graph graph = loadGraph(uid, spaceId);
        Map<Integer, List<String>> parentsByChild = new HashMap<>();
        graph.edges()
                .forEach((parent, children) -> children.forEach(child -> parentsByChild
                        .computeIfAbsent(child, k -> new ArrayList<>())
                        .add(graph.names().getOrDefault(parent, "智能体 " + parent))));
        List<BotRef> selfUsedBy = new ArrayList<>();
        if (selfBotId != null) {
            graph.edges().forEach((parent, children) -> {
                if (!Objects.equals(parent, selfBotId) && children.contains(selfBotId)) {
                    selfUsedBy.add(new BotRef(parent, graph.names().get(parent)));
                }
            });
        }
        Map<Long, String> modelNames = modelNames(bots);
        Map<Integer, List<Integer>> edges = graph.edgesWithout(selfBotId);
        List<Integer> above = selfBotId == null ? List.of() : longestPathTo(selfBotId, edges);
        List<Candidate> candidates = new ArrayList<>();
        for (ChatBotBase bot : bots) {
            boolean published = graph.published().contains(bot.getId());
            String reason = null;
            if (Objects.equals(bot.getId(), selfBotId)) {
                reason = "当前正在编辑的智能体";
            } else {
                List<Integer> path = selfBotId == null ? List.of() : pathTo(bot.getId(), selfBotId, edges);
                if (!path.isEmpty()) {
                    reason = (path.size() == 2 ? "它已引用当前智能体" : "它已间接引用当前智能体")
                            + "（" + describe(path, graph.names()) + "），添加后会相互调用";
                } else if (!published) {
                    reason = "未发布，发布后才能被引用";
                } else {
                    List<Integer> chain = chainThrough(above, selfBotId, bot.getId(), edges);
                    if (chain.size() - 1 > MAX_DEPTH) {
                        reason = "添加后引用会达到 " + (chain.size() - 1) + " 层（" + describe(chain, graph.names())
                                + "），最多 " + MAX_DEPTH + " 层";
                    }
                }
            }
            candidates.add(new Candidate(
                    bot.getId(),
                    bot.getBotName(),
                    StringUtils.defaultString(bot.getBotDesc()),
                    bot.getAvatar(),
                    bot.getModelId() == null ? bot.getModel() : modelNames.getOrDefault(bot.getModelId(), bot.getModel()),
                    bot.getUpdateTime(),
                    published,
                    Objects.equals(bot.getSupportDocument(), 1),
                    jsonArraySize(bot.getSkills()),
                    jsonArraySize(bot.getTools()),
                    jsonArraySize(bot.getMcpServerUrls()),
                    graph.edges().getOrDefault(bot.getId(), List.of()).size(),
                    parentsByChild.getOrDefault(bot.getId(), List.of()),
                    reason == null,
                    reason));
        }
        int level = above.isEmpty() ? 0 : above.size() - 1;
        log.info(
                "Sub-agent candidates listed, uid={}, spaceId={}, selfBotId={}, total={}, selectable={}, level={}, selfUsedBy={}",
                uid, spaceId, selfBotId, candidates.size(), candidates.stream().filter(Candidate::selectable).count(),
                level, selfUsedBy.size());
        return new CandidateList(selfUsedBy, level, MAX_DEPTH, candidates);
    }

    /** Agents shown in the picker, with the columns it displays. */
    private List<ChatBotBase> displayBots(String uid, Long spaceId) {
        LambdaQueryWrapper<ChatBotBase> wrapper = scope(new LambdaQueryWrapper<ChatBotBase>()
                .select(ChatBotBase::getId, ChatBotBase::getBotName, ChatBotBase::getBotDesc, ChatBotBase::getAvatar,
                        ChatBotBase::getModel, ChatBotBase::getModelId, ChatBotBase::getUpdateTime,
                        ChatBotBase::getSupportDocument, ChatBotBase::getSkills, ChatBotBase::getTools,
                        ChatBotBase::getMcpServerUrls),
                uid, spaceId);
        wrapper.orderByDesc(ChatBotBase::getUpdateTime).last("LIMIT " + MAX_DISPLAY_BOTS);
        return chatBotBaseMapper.selectList(wrapper);
    }

    /** Same scope as {@code checkBotPermission}: the space's agents, or the user's personal agents. */
    private static LambdaQueryWrapper<ChatBotBase> scope(LambdaQueryWrapper<ChatBotBase> wrapper, String uid,
            Long spaceId) {
        wrapper.eq(ChatBotBase::getIsDelete, 0).eq(ChatBotBase::getVersion, BotTypeEnum.SYSTEM_BOT.getType());
        if (spaceId != null) {
            wrapper.eq(ChatBotBase::getSpaceId, spaceId);
        } else {
            wrapper.isNull(ChatBotBase::getSpaceId).eq(ChatBotBase::getUid, uid);
        }
        return wrapper;
    }

    // ------------------------------------------------------------------ reference graph

    /**
     * All references between instruction-type agents in scope.
     *
     * @param edges agent id -> ids it references
     * @param published ids whose market row is published
     */
    private record Graph(Map<Integer, List<Integer>> edges, Map<Integer, String> names, Set<Integer> published) {

        /** Edges without {@code selfBotId}'s own references (they are being replaced). */
        Map<Integer, List<Integer>> edgesWithout(Integer selfBotId) {
            if (selfBotId == null || !edges.containsKey(selfBotId)) {
                return edges;
            }
            Map<Integer, List<Integer>> copy = new HashMap<>(edges);
            copy.remove(selfBotId);
            return copy;
        }
    }

    /** Reads every agent in scope (only id / name / agents), not just the most recent page. */
    private Graph loadGraph(String uid, Long spaceId) {
        LambdaQueryWrapper<ChatBotBase> wrapper = scope(new LambdaQueryWrapper<ChatBotBase>()
                .select(ChatBotBase::getId, ChatBotBase::getBotName, ChatBotBase::getAgents), uid, spaceId);
        wrapper.last("LIMIT " + (MAX_GRAPH_BOTS + 1));
        List<ChatBotBase> bots = chatBotBaseMapper.selectList(wrapper);
        if (bots.size() > MAX_GRAPH_BOTS) {
            log.warn("Sub-agent graph truncated, uid={}, spaceId={}, limit={}", uid, spaceId, MAX_GRAPH_BOTS);
        }
        Map<Integer, List<Integer>> edges = new HashMap<>();
        Map<Integer, String> names = new HashMap<>();
        for (ChatBotBase bot : bots) {
            names.put(bot.getId(), bot.getBotName());
            List<Integer> children = parseBotIds(bot.getAgents());
            if (!children.isEmpty()) {
                edges.put(bot.getId(), children);
            }
        }
        return new Graph(edges, names, publishedIds(names.keySet()));
    }

    private Set<Integer> publishedIds(Set<Integer> botIds) {
        Set<Integer> published = new HashSet<>();
        List<Integer> ids = new ArrayList<>(botIds);
        for (int from = 0; from < ids.size(); from += 500) {
            List<Integer> chunk = ids.subList(from, Math.min(ids.size(), from + 500));
            chatBotMarketMapper.selectList(new LambdaQueryWrapper<ChatBotMarket>()
                    .select(ChatBotMarket::getBotId)
                    .in(ChatBotMarket::getBotId, chunk)
                    .in(ChatBotMarket::getBotStatus, PUBLISHED_STATUSES)
                    .eq(ChatBotMarket::getIsDelete, 0))
                    .forEach(m -> published.add(m.getBotId()));
        }
        return published;
    }

    private static boolean isPublished(ChatBotMarket market) {
        // Set.of(...).contains(null) throws, so check the status for null first.
        return market != null && !Objects.equals(market.getIsDelete(), 1) && market.getBotStatus() != null
                && PUBLISHED_STATUSES.contains(market.getBotStatus());
    }

    /** Shortest path {@code from -> ... -> target} along sub-agent edges, or empty if unreachable. */
    static List<Integer> pathTo(Integer from, Integer target, Map<Integer, List<Integer>> edges) {
        Map<Integer, Integer> previous = new HashMap<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        Set<Integer> seen = new HashSet<>();
        queue.add(from);
        seen.add(from);
        while (!queue.isEmpty()) {
            Integer current = queue.poll();
            for (Integer next : edges.getOrDefault(current, List.of())) {
                if (Objects.equals(next, target)) {
                    List<Integer> path = new ArrayList<>();
                    path.add(target);
                    for (Integer node = current; node != null; node = previous.get(node)) {
                        path.add(0, node);
                    }
                    return path;
                }
                if (next != null && seen.add(next)) {
                    previous.put(next, current);
                    queue.add(next);
                }
            }
        }
        return List.of();
    }

    /**
     * The longest reference chain that would pass through the current agent after attaching
     * {@code botId}: the longest chain of agents above it, the agent itself, then the longest chain
     * below {@code botId}. Its node count minus one is the number of levels.
     */
    private static List<Integer> chainThrough(List<Integer> above, Integer selfBotId, Integer botId,
            Map<Integer, List<Integer>> edges) {
        List<Integer> chain = new ArrayList<>();
        if (above.isEmpty()) {
            chain.add(selfBotId == null ? NEW_AGENT_ID : selfBotId);
        } else {
            chain.addAll(above);
        }
        chain.addAll(longestPathFrom(botId, edges, new HashMap<>(), new HashSet<>()));
        return chain;
    }

    /** Longest chain {@code from -> ... -> leaf}; stops at nodes already on the path (no cycles). */
    private static List<Integer> longestPathFrom(Integer from, Map<Integer, List<Integer>> edges,
            Map<Integer, List<Integer>> memo, Set<Integer> onPath) {
        List<Integer> cached = memo.get(from);
        if (cached != null) {
            return cached;
        }
        onPath.add(from);
        List<Integer> best = List.of();
        for (Integer next : edges.getOrDefault(from, List.of())) {
            if (next == null || onPath.contains(next)) {
                continue;
            }
            List<Integer> candidate = longestPathFrom(next, edges, memo, onPath);
            if (candidate.size() > best.size()) {
                best = candidate;
            }
        }
        onPath.remove(from);
        List<Integer> path = new ArrayList<>();
        path.add(from);
        path.addAll(best);
        memo.put(from, path);
        return path;
    }

    /** Longest chain {@code root -> ... -> target} of agents that reference {@code target}. */
    static List<Integer> longestPathTo(Integer target, Map<Integer, List<Integer>> edges) {
        Map<Integer, List<Integer>> reverse = new HashMap<>();
        edges.forEach((parent, children) -> children.forEach(
                child -> reverse.computeIfAbsent(child, k -> new ArrayList<>()).add(parent)));
        List<Integer> path = new ArrayList<>(longestPathFrom(target, reverse, new HashMap<>(), new HashSet<>()));
        Collections.reverse(path);
        return path;
    }

    private static String describe(List<Integer> path, Map<Integer, String> names) {
        return String.join(" → ", path.stream()
                .map(id -> Objects.equals(id, NEW_AGENT_ID) ? "新智能体" : names.getOrDefault(id, "智能体 " + id))
                .toList());
    }

    private Map<Long, String> modelNames(List<ChatBotBase> bots) {
        Set<Long> ids = new HashSet<>();
        for (ChatBotBase bot : bots) {
            if (bot.getModelId() != null) {
                ids.add(bot.getModelId());
            }
        }
        Map<Long, String> names = new HashMap<>();
        if (ids.isEmpty()) {
            return names;
        }
        try {
            for (Model model : modelMapper.selectBatchIds(ids)) {
                names.put(model.getId(), model.getName());
            }
        } catch (Exception e) {
            log.warn("Sub-agent candidate model names skipped, errorType={}", e.getClass().getSimpleName());
        }
        return names;
    }

    private static int jsonArraySize(String json) {
        if (StringUtils.isBlank(json)) {
            return 0;
        }
        try {
            JSONArray array = JSON.parseArray(json.trim());
            return array == null ? 0 : array.size();
        } catch (Exception e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ runtime

    /**
     * Prepares the agents referenced by the agent the user talks to, and their own agents down to
     * {@link #MAX_DEPTH} levels, at most {@link #MAX_RUNTIME_AGENTS} in total. Unpublished agents are
     * skipped; published ones run their published prompt and model.
     *
     * @param agentsJson saved {@code agents} JSON of the main agent (or the debug request's)
     * @param parentBotId main agent id (excluded if listed); may be null in debug
     * @param scopeUid owner used for permission and skill scope (the main agent's owner)
     * @param scopeSpaceId space used for permission and skill scope
     * @param requestUid user chatting now (model lookups are scoped to the authenticated user)
     * @param requestSpaceId space of the current request
     * @param publishedForOthers true when a published agent is used by someone other than its owner
     */
    /**
     * Set while {@link #prepareForUser} runs: models are resolved for this user instead of the
     * request's.
     */
    private static final ThreadLocal<UserInfo> JOB_USER = new ThreadLocal<>();

    /**
     * Like {@link #prepare(String, Integer, String, Long, String, Long, boolean)} for server-side jobs
     * that act for a user without an HTTP request (the scheduled A-share review). {@code user} must be
     * loaded from the database from server-side state.
     */
    public List<PreparedSubAgent> prepareForUser(String agentsJson, String scopeUid, Long scopeSpaceId,
            UserInfo user, Long requestSpaceId, boolean publishedForOthers) {
        JOB_USER.set(user);
        try {
            return prepare(agentsJson, null, scopeUid, scopeSpaceId, user.getUid(), requestSpaceId,
                    publishedForOthers);
        } finally {
            JOB_USER.remove();
        }
    }

    public List<PreparedSubAgent> prepare(String agentsJson, Integer parentBotId, String scopeUid, Long scopeSpaceId,
            String requestUid, Long requestSpaceId, boolean publishedForOthers) {
        return prepare(agentsJson, false, null, parentBotId, scopeUid, scopeSpaceId, requestUid, requestSpaceId,
                publishedForOthers);
    }

    /**
     * Like {@link #prepare(String, Integer, String, Long, String, Long, boolean)}; with
     * {@code autoTeam} it also offers up to {@link #MAX_AUTO_POOL} published experts of the space that
     * match {@code userText}, so the lead can assemble a team per question. Library experts follow the
     * same rules as attached ones (published, no cycle, at most {@link #MAX_DEPTH} levels).
     */
    public List<PreparedSubAgent> prepare(String agentsJson, boolean autoTeam, String userText, Integer parentBotId,
            String scopeUid, Long scopeSpaceId, String requestUid, Long requestSpaceId, boolean publishedForOthers) {
        Set<Integer> ancestors = new HashSet<>();
        if (parentBotId != null) {
            ancestors.add(parentBotId);
        }
        int[] budget = {MAX_RUNTIME_AGENTS};
        // 直接回复用户 applies to the chat lead's own members only: their answer is the lead's reply.
        Set<Integer> direct = parseDirectBotIds(agentsJson);
        List<PreparedSubAgent> prepared = new ArrayList<>();
        prepareLevel(agentsJson, parentBotId, scopeUid, scopeSpaceId, requestUid, requestSpaceId, publishedForOthers,
                1, ancestors, budget)
                .forEach(agent -> prepared.add(direct.contains(agent.botId()) ? agent.asDirect() : agent));
        if (autoTeam && budget[0] > 0) {
            Set<Integer> taken = new HashSet<>(parseBotIds(agentsJson));
            List<Integer> pool = pickLibraryExperts(userText, parentBotId, scopeUid, scopeSpaceId, taken);
            if (!pool.isEmpty()) {
                JSONArray json = new JSONArray();
                pool.forEach(id -> json.add(new JSONObject().fluentPut("botId", id)));
                prepareLevel(json.toJSONString(), parentBotId, scopeUid, scopeSpaceId, requestUid, requestSpaceId,
                        publishedForOthers, 1, ancestors, budget)
                        .forEach(agent -> prepared.add(agent.asLibraryExpert()));
            }
        }
        if (!prepared.isEmpty()) {
            log.info("Sub-agent tree prepared, parentBotId={}, total={}, autoTeam={}, library={}, direct={}",
                    parentBotId, MAX_RUNTIME_AGENTS - budget[0], autoTeam,
                    prepared.stream().filter(PreparedSubAgent::fromLibrary).map(PreparedSubAgent::botId).toList(),
                    prepared.stream().filter(PreparedSubAgent::direct).map(PreparedSubAgent::botId).toList());
        }
        return prepared;
    }

    /**
     * Published experts of the scope that the lead may call. When there are more than
     * {@link #MAX_AUTO_POOL}, the ones whose name and description overlap most with the question are
     * kept (ties: most recently updated first); the lead makes the actual choice.
     */
    private List<Integer> pickLibraryExperts(String userText, Integer parentBotId, String scopeUid, Long scopeSpaceId,
            Set<Integer> taken) {
        if (StringUtils.isBlank(userText)) {
            return List.of();
        }
        Set<String> question = bigrams(userText);
        record Scored(Integer botId, String name, int score) {}
        List<Scored> scored = new ArrayList<>();
        for (Candidate c : listCandidates(scopeUid, scopeSpaceId, parentBotId).candidates()) {
            if (!c.selectable() || taken.contains(c.botId())) {
                continue;
            }
            Set<String> expert = bigrams(c.name() + " " + c.description());
            int score = 0;
            for (String gram : expert) {
                if (question.contains(gram)) {
                    score++;
                }
            }
            scored.add(new Scored(c.botId(), c.name(), score));
        }
        // Stable sort keeps the candidates' update-time order for equal scores.
        scored.sort((a, b) -> Integer.compare(b.score(), a.score()));
        List<Scored> top = scored.subList(0, Math.min(MAX_AUTO_POOL, scored.size()));
        log.info("Library experts picked, parentBotId={}, candidates={}, picked={}", parentBotId, scored.size(),
                top.stream().map(t -> t.name() + ":" + t.score()).toList());
        return top.stream().map(Scored::botId).toList();
    }

    /** Character bigrams of letters, digits and CJK characters, used for cheap relevance. */
    static Set<String> bigrams(String text) {
        String normalized = StringUtils.defaultString(text)
                .toLowerCase()
                .replaceAll("[^\\p{IsHan}\\p{L}\\p{N}]+", " ");
        Set<String> grams = new HashSet<>();
        for (String part : normalized.split(" ")) {
            for (int i = 0; i + 1 < part.length(); i++) {
                grams.add(part.substring(i, i + 2));
            }
        }
        return grams;
    }

    /** A referenced agent resolved at one level, before its own agents are prepared. */
    private record Resolved(PreparedSubAgent agent, ChatBotBase base) {}

    /**
     * Prepares one level breadth-first: every agent of this level is resolved before any agent of the
     * next level, so the shared {@code budget} is not used up by the first agent's descendants.
     * {@code ancestors} guards against cycles in data saved before the save-time checks existed.
     */
    private List<PreparedSubAgent> prepareLevel(String agentsJson, Integer parentBotId, String scopeUid,
            Long scopeSpaceId, String requestUid, Long requestSpaceId, boolean publishedForOthers, int level,
            Set<Integer> ancestors, int[] budget) {
        List<Integer> ids = parseBotIds(agentsJson);
        if (ids.size() > MAX_AGENTS) {
            // Saved configs are capped at save time; this guards debug requests, whose list comes from the
            // client.
            log.warn("Sub-agent list truncated, parentBotId={}, level={}, requested={}, kept={}", parentBotId, level,
                    ids.size(), MAX_AGENTS);
            ids = ids.subList(0, MAX_AGENTS);
        }
        List<Resolved> resolved = new ArrayList<>();
        for (Integer botId : ids) {
            if (ancestors.contains(botId)) {
                log.warn("Sub-agent skipped: cycle, botId={}, parentBotId={}, level={}", botId, parentBotId, level);
                continue;
            }
            if (budget[0] <= 0) {
                log.warn("Sub-agent skipped: runtime budget of {} used up, botId={}, parentBotId={}, level={}",
                        MAX_RUNTIME_AGENTS, botId, parentBotId, level);
                break;
            }
            try {
                Optional<Resolved> one = resolveOne(botId, scopeUid, scopeSpaceId, requestUid, requestSpaceId,
                        publishedForOthers);
                if (one.isPresent()) {
                    resolved.add(one.get());
                    budget[0]--;
                }
            } catch (Exception e) {
                log.warn("Sub-agent skipped, botId={}, parentBotId={}, errorType={}, msg={}", botId, parentBotId,
                        e.getClass().getSimpleName(), e.getMessage());
            }
        }
        List<PreparedSubAgent> prepared = new ArrayList<>();
        for (Resolved one : resolved) {
            List<PreparedSubAgent> children = List.of();
            Integer botId = one.agent().botId();
            if (!parseBotIds(one.base().getAgents()).isEmpty()) {
                if (level < MAX_DEPTH) {
                    Set<Integer> path = new HashSet<>(ancestors);
                    path.add(botId);
                    // Its own agents are checked against its own owner / space.
                    children = prepareLevel(one.base().getAgents(), botId, one.base().getUid(),
                            one.base().getSpaceId(), requestUid, requestSpaceId, publishedForOthers, level + 1, path,
                            budget);
                } else {
                    log.warn("Sub-agent references beyond {} levels are not called, botId={}", MAX_DEPTH, botId);
                }
            }
            prepared.add(one.agent().withSubAgents(children));
        }
        if (!prepared.isEmpty()) {
            log.info("Sub-agents prepared, parentBotId={}, level={}, agents={}", parentBotId, level,
                    prepared.stream().map(PreparedSubAgent::botId).toList());
        }
        return prepared;
    }

    /** Resolves one published agent with its published prompt / model; its own agents come later. */
    private Optional<Resolved> resolveOne(Integer botId, String scopeUid, Long scopeSpaceId, String requestUid,
            Long requestSpaceId, boolean publishedForOthers) {
        if (chatBotBaseMapper.checkBotPermission(botId, scopeUid, scopeSpaceId) <= 0) {
            log.warn("Sub-agent not accessible at runtime, botId={}, scopeUid={}, scopeSpaceId={}", botId, scopeUid,
                    scopeSpaceId);
            return Optional.empty();
        }
        ChatBotBase base = chatBotDataService.findById(botId).orElse(null);
        if (base == null || Objects.equals(base.getIsDelete(), 1)
                || !Objects.equals(base.getVersion(), BotTypeEnum.SYSTEM_BOT.getType())) {
            return Optional.empty();
        }
        ChatBotMarket market = chatBotDataService.findMarketBotByBotId(botId);
        if (!isPublished(market)) {
            log.warn("Sub-agent skipped: not published, botId={}, marketStatus={}", botId,
                    market == null ? null : market.getBotStatus());
            return Optional.empty();
        }
        // Published version: prompt / model / tool switches / knowledge flag from the market row, like
        // BotChatServiceImpl#getBotConfiguration; skills, plugins, MCP and knowledge bases from the agent.
        String prompt = personalityConfigService.getChatPrompt(botId.longValue(), market.getPrompt(),
                ConfigTypeEnum.MARKET);

        LLMInfoVo llmInfoVo = null;
        String sparkModelName = null;
        if (market.getModelId() != null) {
            UserInfo jobUser = JOB_USER.get();
            if (jobUser != null) {
                llmInfoVo = publishedForOthers
                        ? modelService.getRuntimeModelDetailForPublishedBotForUser(market.getModelId(), jobUser,
                                market.getUid())
                        : modelService.getRuntimeModelDetailForUser(market.getModelId(), jobUser, requestSpaceId);
            } else {
                llmInfoVo = publishedForOthers
                        ? modelService.getRuntimeModelDetailForPublishedBot(market.getModelId(), requestUid,
                                market.getUid())
                        : modelService.getRuntimeModelDetail(market.getModelId(), requestUid, requestSpaceId);
            }
            if (llmInfoVo == null) {
                log.warn("Sub-agent model unavailable, botId={}, modelId={}", botId, market.getModelId());
                return Optional.empty();
            }
        } else {
            sparkModelName = market.getModel();
        }

        List<Long> datasetIds = new ArrayList<>();
        if (Objects.equals(market.getSupportDocument(), 1)) {
            List<BotDatasetMaas> datasets = datasetDataService.findMaasDatasetsByBotIdAndIsAct(botId, 1);
            if (datasets != null) {
                for (BotDatasetMaas dataset : datasets) {
                    try {
                        datasetIds.add(Long.parseLong(dataset.getDatasetIndex()));
                    } catch (NumberFormatException ignored) {
                        // Remote (non-local) datasets are not retrievable here
                    }
                }
            }
        }

        PreparedSubAgent agent = new PreparedSubAgent(
                botId,
                StringUtils.defaultIfBlank(market.getBotName(), StringUtils.defaultIfBlank(base.getBotName(),
                        "agent-" + botId)),
                StringUtils.defaultString(StringUtils.defaultIfBlank(market.getBotDesc(), base.getBotDesc())),
                TOOL_PREFIX + botId,
                prompt,
                llmInfoVo,
                sparkModelName,
                market.getOpenedTool(),
                base.getMcpServerUrls(),
                enrichSkills(base.getSkills(), scopeUid, scopeSpaceId),
                base.getTools(),
                base.getWorkflows(),
                datasetIds,
                List.of(),
                false,
                // Like agents / auto team, the switch is read from the agent itself (no re-publish needed).
                !Objects.equals(base.getDeepThinking(), 0),
                false,
                AgentToolCallbackResolver.parseBuiltinTools(base.getBuiltinTools()));
        return Optional.of(new Resolved(agent, base));
    }

    /**
     * Team-lead instructions appended to the agent's system prompt: when to call experts, running
     * independent parts in parallel, review, keeping disagreements visible and delivering files. The
     * agent's own 个性化设置 come first, so rules written there take precedence.
     */
    public static String routingPrompt(List<PreparedSubAgent> agents) {
        if (agents == null || agents.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\n## 专家团\n")
                .append("你是负责人。先判断用户意图，再决定要不要找专家：\n")
                .append("- 问题属于某位专家的职责范围时，调用对应工具交给他；都不匹配时由你直接回答。\n")
                .append("- 任务能拆成互不依赖的部分时，在同一次回复里同时调用多位专家（会并行执行）；")
                .append("有先后依赖的（例如先核实事实再写稿）再按顺序调用。\n")
                .append("- 调用时把用户问题、必要的上下文和你希望他产出的内容写进 query。专家之间能通过共享区看到彼此的结论。\n")
                .append("- 涉及数字、合同条款、对外承诺等关键结论时，可以请另一位相关专家复核：query 以「复核：」开头并附上要复核的内容。\n")
                .append("- 只有一位专家产出了成品（文案、稿件、邮件、答复等）时，原样交付，不要改写、润色或复述，最多补一句说明。\n")
                .append("- 汇总多位专家的结果时：先给结论或成品，再按专家列出要点；专家意见不一致或有人标出【分歧】时，")
                .append("单独列出「分歧与待确认」，写明各方观点和依据，不要擅自取舍。\n")
                .append("- 需要交付文档、表格或演示文稿时，调用 export_file 生成文件，并把下载链接给用户。\n");
        if (agents.stream().anyMatch(PreparedSubAgent::direct)) {
            sb.append("- 标有「直接回复」的成员：这一步只调用他一位时，他的回答会直接发给用户并结束本轮，")
                    .append("你看不到结果、也不能再补充。需要和其他成员的结果合并，或拿到他的结果后还要继续处理时，")
                    .append("在同一次回复里把要调用的成员一起调用。\n");
        }
        List<PreparedSubAgent> attached = agents.stream().filter(a -> !a.fromLibrary()).toList();
        List<PreparedSubAgent> library = agents.stream().filter(PreparedSubAgent::fromLibrary).toList();
        if (!attached.isEmpty()) {
            sb.append("### 固定成员\n");
            attached.forEach(agent -> appendAgent(sb, agent));
        }
        if (!library.isEmpty()) {
            sb.append("### 专家库（按本次问题从空间已发布的专家里挑出的候选，按需选用，只选和任务相关的）\n");
            library.forEach(agent -> appendAgent(sb, agent));
        }
        return sb.toString();
    }

    private static void appendAgent(StringBuilder sb, PreparedSubAgent agent) {
        sb.append("- ")
                .append(agent.name())
                .append("（工具 ")
                .append(agent.toolName())
                .append(agent.direct() ? "，直接回复" : "")
                .append("）");
        if (StringUtils.isNotBlank(agent.description())) {
            sb.append("：").append(StringUtils.abbreviate(StringUtils.normalizeSpace(agent.description()), 200));
        }
        sb.append('\n');
    }

    /** Ids of the attached agents marked 直接回复用户 ({@code "direct": true}). */
    static Set<Integer> parseDirectBotIds(String agentsJson) {
        if (StringUtils.isBlank(agentsJson)) {
            return Set.of();
        }
        Set<Integer> ids = new HashSet<>();
        try {
            JSONArray array = JSON.parseArray(agentsJson.trim());
            for (int i = 0; i < array.size(); i++) {
                JSONObject item = array.getJSONObject(i);
                if (item != null && item.getInteger("botId") != null && item.getBooleanValue("direct")) {
                    ids.add(item.getInteger("botId"));
                }
            }
        } catch (Exception e) {
            return Set.of();
        }
        return ids;
    }

    public static List<Integer> parseBotIds(String agentsJson) {
        if (StringUtils.isBlank(agentsJson)) {
            return List.of();
        }
        Set<Integer> ids = new LinkedHashSet<>();
        try {
            JSONArray array = JSON.parseArray(agentsJson.trim());
            for (int i = 0; i < array.size(); i++) {
                JSONObject item = array.getJSONObject(i);
                Integer botId = item == null ? null : item.getInteger("botId");
                if (botId != null) {
                    ids.add(botId);
                }
            }
        } catch (Exception e) {
            log.warn("Invalid agents json, bodyBytes={}, errorType={}",
                    agentsJson.getBytes(StandardCharsets.UTF_8).length, e.getClass().getSimpleName());
            return List.of();
        }
        return new ArrayList<>(ids);
    }

    private List<JSONObject> enrichSkills(String skillsJson, String uid, Long spaceId) {
        if (StringUtils.isBlank(skillsJson)) {
            return List.of();
        }
        try {
            JSONArray array = JSON.parseArray(skillsJson);
            skillEnrichmentService.enrichSkillEntries(array, uid, spaceId);
            List<JSONObject> result = new ArrayList<>();
            for (int i = 0; i < array.size(); i++) {
                JSONObject skill = array.getJSONObject(i);
                if (skill != null && StringUtils.isNotBlank(skill.getString("downloadUrl"))) {
                    result.add(skill);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Sub-agent skills skipped, errorType={}", e.getClass().getSimpleName());
            return List.of();
        }
    }
}
