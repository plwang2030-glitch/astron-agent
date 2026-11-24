package com.iflytek.astron.console.hub.service.ashare;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.iflytek.astron.console.hub.entity.ashare.PaperAccount;
import com.iflytek.astron.console.hub.entity.ashare.PaperOrder;
import com.iflytek.astron.console.hub.entity.ashare.TradingLesson;
import com.iflytek.astron.console.hub.mapper.ashare.PaperAccountMapper;
import com.iflytek.astron.console.hub.mapper.ashare.PaperOrderMapper;
import com.iflytek.astron.console.hub.mapper.ashare.TradingLessonMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Lessons the agent learns from reviewed trades. A LESSON must cite filled orders; each later trade
 * that supports or contradicts it moves its confidence {@code (support+1)/(support+contradict+2)}.
 * It becomes ACTIVE (injected into prompts) with support ≥ 2 and confidence ≥ 0.6, and is RETIRED
 * with contradict ≥ 2 and confidence < 0.4. PREFERENCE (what the user wants) is active at once;
 * META records strategy evolution attempts so later proposals learn from earlier ones.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LessonService {

    static final int MAX_LESSONS = 200;
    private static final int ACTIVATE_SUPPORT = 2;
    private static final double ACTIVATE_CONFIDENCE = 0.6;
    private static final int RETIRE_CONTRADICT = 2;
    private static final double RETIRE_CONFIDENCE = 0.4;

    private final TradingLessonMapper lessonMapper;
    private final PaperOrderMapper orderMapper;
    private final PaperAccountMapper accountMapper;

    public List<TradingLesson> list(String uid, String kind, String status, int limit) {
        var q = Wrappers.lambdaQuery(TradingLesson.class).eq(TradingLesson::getUid, uid);
        if (StringUtils.isNotBlank(kind)) {
            q.eq(TradingLesson::getKind, kind.toUpperCase(Locale.ROOT));
        }
        if (StringUtils.isNotBlank(status)) {
            q.eq(TradingLesson::getStatus, status.toUpperCase(Locale.ROOT));
        } else {
            q.ne(TradingLesson::getStatus, TradingLesson.RETIRED);
        }
        q.orderByDesc(TradingLesson::getConfidence)
                .orderByDesc(TradingLesson::getUpdateTime)
                .last("LIMIT " + Math.max(1, Math.min(limit, 50)));
        return lessonMapper.selectList(q);
    }

    /** Lessons shown in every prompt: active lessons, preferences and the latest evolution records. */
    public List<TradingLesson> forContext(String uid) {
        List<TradingLesson> out = new ArrayList<>();
        out.addAll(lessonMapper.selectList(Wrappers.lambdaQuery(TradingLesson.class)
                .eq(TradingLesson::getUid, uid)
                .eq(TradingLesson::getKind, TradingLesson.PREFERENCE)
                .eq(TradingLesson::getStatus, TradingLesson.ACTIVE)
                .orderByDesc(TradingLesson::getUpdateTime)
                .last("LIMIT 5")));
        out.addAll(lessonMapper.selectList(Wrappers.lambdaQuery(TradingLesson.class)
                .eq(TradingLesson::getUid, uid)
                .eq(TradingLesson::getKind, TradingLesson.LESSON)
                .eq(TradingLesson::getStatus, TradingLesson.ACTIVE)
                .orderByDesc(TradingLesson::getConfidence)
                .last("LIMIT 8")));
        out.addAll(lessonMapper.selectList(Wrappers.lambdaQuery(TradingLesson.class)
                .eq(TradingLesson::getUid, uid)
                .eq(TradingLesson::getKind, TradingLesson.META)
                .orderByDesc(TradingLesson::getId)
                .last("LIMIT 3")));
        return out;
    }

    public long countCandidates(String uid) {
        return lessonMapper.selectCount(Wrappers.lambdaQuery(TradingLesson.class)
                .eq(TradingLesson::getUid, uid)
                .eq(TradingLesson::getStatus, TradingLesson.CANDIDATE));
    }

    /**
     * Adds a LESSON (needs evidence: the user's filled order ids) or a PREFERENCE.
     *
     * @throws IllegalArgumentException when the input breaks a rule
     */
    public TradingLesson add(String uid, String kind, String content, String tags, List<Long> evidence) {
        String k = StringUtils.upperCase(StringUtils.trimToEmpty(kind), Locale.ROOT);
        if (!TradingLesson.LESSON.equals(k) && !TradingLesson.PREFERENCE.equals(k)) {
            throw new IllegalArgumentException("kind 只能是 LESSON 或 PREFERENCE（META 由策略进化自动记录）");
        }
        String text = StringUtils.trimToEmpty(content);
        if (text.length() < 6 || text.length() > 300) {
            throw new IllegalArgumentException("content 需为 6–300 字的一条可执行经验");
        }
        long total = lessonMapper.selectCount(Wrappers.lambdaQuery(TradingLesson.class)
                .eq(TradingLesson::getUid, uid)
                .ne(TradingLesson::getStatus, TradingLesson.RETIRED));
        if (total >= MAX_LESSONS) {
            throw new IllegalArgumentException("经验条目已达 " + MAX_LESSONS + " 条上限，请先 retire 过时的");
        }
        boolean exists = lessonMapper.selectCount(Wrappers.lambdaQuery(TradingLesson.class)
                .eq(TradingLesson::getUid, uid)
                .eq(TradingLesson::getContent, text)
                .ne(TradingLesson::getStatus, TradingLesson.RETIRED)) > 0;
        if (exists) {
            throw new IllegalArgumentException("已有相同的经验，请用 feedback 给它加证据");
        }
        List<Long> ids = TradingLesson.LESSON.equals(k) ? requireFilledOrders(uid, evidence) : List.of();
        TradingLesson lesson = new TradingLesson();
        lesson.setUid(uid);
        lesson.setKind(k);
        lesson.setContent(text);
        lesson.setTags(StringUtils.abbreviate(StringUtils.trimToNull(tags), 255));
        lesson.setEvidence(ids.isEmpty() ? null : JSON.toJSONString(ids));
        lesson.setSupport(ids.size());
        lesson.setContradict(0);
        if (TradingLesson.PREFERENCE.equals(k)) {
            lesson.setConfidence(BigDecimal.ONE);
            lesson.setStatus(TradingLesson.ACTIVE);
        } else {
            applyConfidence(lesson);
            // A new lesson starts as a candidate even with several orders: it must be confirmed later.
            if (TradingLesson.ACTIVE.equals(lesson.getStatus())) {
                lesson.setStatus(TradingLesson.CANDIDATE);
            }
        }
        lessonMapper.insert(lesson);
        log.info("Trading lesson added, uid={}, id={}, kind={}, evidence={}", uid, lesson.getId(), k, ids);
        return lesson;
    }

    /** Counts one more trade for or against a lesson; the same order counts once. */
    public TradingLesson feedback(String uid, Long lessonId, boolean supports, Long orderId) {
        TradingLesson lesson = requireOwn(uid, lessonId);
        if (!TradingLesson.LESSON.equals(lesson.getKind())) {
            throw new IllegalArgumentException("只有 LESSON 需要证据反馈");
        }
        if (TradingLesson.RETIRED.equals(lesson.getStatus())) {
            throw new IllegalArgumentException("这条经验已停用");
        }
        Long id = requireFilledOrders(uid, orderId == null ? List.of() : List.of(orderId)).get(0);
        JSONArray evidence = StringUtils.isBlank(lesson.getEvidence()) ? new JSONArray()
                : JSON.parseArray(lesson.getEvidence());
        String tagged = supports ? String.valueOf(id) : "-" + id;
        for (Object e : evidence) {
            String s = String.valueOf(e);
            if (s.equals(String.valueOf(id)) || s.equals("-" + id)) {
                throw new IllegalArgumentException("订单 " + id + " 已经计入过这条经验");
            }
        }
        evidence.add(supports ? id : tagged);
        lesson.setEvidence(StringUtils.abbreviate(evidence.toJSONString(), 1000));
        if (supports) {
            lesson.setSupport(lesson.getSupport() + 1);
        } else {
            lesson.setContradict(lesson.getContradict() + 1);
        }
        applyConfidence(lesson);
        // Null lets MySQL's ON UPDATE refresh update_time.
        lesson.setUpdateTime(null);
        lessonMapper.updateById(lesson);
        log.info("Trading lesson feedback, uid={}, id={}, supports={}, orderId={}, status={}, confidence={}", uid,
                lessonId, supports, id, lesson.getStatus(), lesson.getConfidence());
        return lesson;
    }

    public TradingLesson retire(String uid, Long lessonId) {
        TradingLesson lesson = requireOwn(uid, lessonId);
        lesson.setStatus(TradingLesson.RETIRED);
        lesson.setUpdateTime(null);
        lessonMapper.updateById(lesson);
        return lesson;
    }

    /**
     * Records a strategy evolution attempt (adopted, rejected, searched or rolled back).
     *
     * @param tags comma-separated markers, e.g. {@code AUTO_SEARCH,AUTO_SEARCH:2026-W48}
     */
    public void recordMeta(String uid, String content, String evidence, String tags) {
        TradingLesson lesson = new TradingLesson();
        lesson.setUid(uid);
        lesson.setKind(TradingLesson.META);
        lesson.setContent(StringUtils.abbreviate(content, 1000));
        lesson.setEvidence(evidence);
        lesson.setTags(StringUtils.abbreviate(tags, 255));
        lesson.setSupport(0);
        lesson.setContradict(0);
        lesson.setConfidence(BigDecimal.ONE);
        lesson.setStatus(TradingLesson.ACTIVE);
        lessonMapper.insert(lesson);
    }

    /** A META lesson carries exactly this tag. */
    public boolean hasMetaTag(String uid, String tag) {
        return lessonMapper.selectList(Wrappers.lambdaQuery(TradingLesson.class)
                .select(TradingLesson::getTags)
                .eq(TradingLesson::getUid, uid)
                .eq(TradingLesson::getKind, TradingLesson.META)
                .like(TradingLesson::getTags, tag))
                .stream()
                .anyMatch(l -> List.of(StringUtils.defaultString(l.getTags()).split(",")).contains(tag));
    }

    /** META lessons tagged {@code tag} created since {@code day}. */
    public long countMetaTagSince(String uid, String tag, java.time.LocalDate day) {
        return lessonMapper.selectList(Wrappers.lambdaQuery(TradingLesson.class)
                .select(TradingLesson::getTags)
                .eq(TradingLesson::getUid, uid)
                .eq(TradingLesson::getKind, TradingLesson.META)
                .like(TradingLesson::getTags, tag)
                .ge(TradingLesson::getCreateTime, day.atStartOfDay()))
                .stream()
                .filter(l -> List.of(StringUtils.defaultString(l.getTags()).split(",")).contains(tag))
                .count();
    }

    static void applyConfidence(TradingLesson lesson) {
        int s = lesson.getSupport();
        int c = lesson.getContradict();
        double conf = (s + 1.0) / (s + c + 2.0);
        lesson.setConfidence(BigDecimal.valueOf(conf).setScale(2, RoundingMode.HALF_UP));
        if (c >= RETIRE_CONTRADICT && conf < RETIRE_CONFIDENCE) {
            lesson.setStatus(TradingLesson.RETIRED);
        } else if (s >= ACTIVATE_SUPPORT && conf >= ACTIVATE_CONFIDENCE) {
            lesson.setStatus(TradingLesson.ACTIVE);
        } else {
            lesson.setStatus(TradingLesson.CANDIDATE);
        }
    }

    private TradingLesson requireOwn(String uid, Long lessonId) {
        TradingLesson lesson = lessonId == null ? null : lessonMapper.selectById(lessonId);
        if (lesson == null || !Objects.equals(lesson.getUid(), uid)) {
            throw new IllegalArgumentException("没有这条经验：" + lessonId);
        }
        return lesson;
    }

    private List<Long> requireFilledOrders(String uid, List<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            throw new IllegalArgumentException("LESSON 需要 evidence：引用至少 1 个已成交订单的 id");
        }
        PaperAccount account = accountMapper.selectOne(Wrappers.lambdaQuery(PaperAccount.class)
                .eq(PaperAccount::getUid, uid));
        if (account == null) {
            throw new IllegalArgumentException("还没有模拟账户和成交记录");
        }
        Long accountId = account.getId();
        List<Long> ids = orderIds.stream().filter(Objects::nonNull).distinct().limit(10).toList();
        List<PaperOrder> orders = orderMapper.selectBatchIds(ids);
        List<Long> valid = orders.stream()
                .filter(o -> Objects.equals(o.getAccountId(), accountId) && PaperOrder.FILLED.equals(o.getStatus()))
                .map(PaperOrder::getId)
                .toList();
        if (valid.size() != ids.size()) {
            List<Long> bad = new ArrayList<>(ids);
            bad.removeAll(valid);
            throw new IllegalArgumentException("这些订单不存在或未成交，不能作为证据：" + bad);
        }
        return valid;
    }
}
