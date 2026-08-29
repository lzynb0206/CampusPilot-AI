package com.example.demo.service.routing;

import com.example.demo.skill.CampusPlanningAgentSkill;
import com.example.demo.skill.SkillOutput;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CampusConversationService {
    private static final Set<String> RESET_COMMANDS = Set.of(
            "重新策划", "重新开始", "重置方案", "清除方案", "清空方案",
            "清除当前方案", "结束策划", "取消策划", "忘掉这个方案");
    private static final Set<String> RENDER_COMMANDS = Set.of(
            "按这个给我方案", "按这些给我方案", "更新方案", "重新生成方案", "给我完整方案");
    private static final List<String> EARLIER_CONTEXT_SIGNALS = List.of(
            "刚刚", "刚才", "之前", "前面", "上面", "原方案", "原来的");
    private static final List<String> PLAN_REFERENCE_SIGNALS = List.of(
            "这个活动", "本次活动", "刚才的活动", "之前的活动", "当前方案");
    private static final List<String> FACT_QUESTION_SIGNALS = List.of(
            "哪里", "哪儿", "在哪", "什么时间", "什么时候", "哪天", "几号",
            "多少人", "多少预算", "叫什么", "是什么");
    private static final List<String> QUESTION_SIGNALS = List.of(
            "吗", "呢", "哪里", "哪儿", "哪个", "什么", "多少", "怎么", "如何", "记得");
    private static final List<String> EXPLICIT_UPDATE_SIGNALS = List.of(
            "改为", "改成", "改到", "换为", "换成", "换到", "调整为", "调整到",
            "活动名称：", "活动名称:", "活动主题：", "活动主题:",
            "学校改", "场地改", "地点改", "人数改", "预算改", "经费改", "开始时间改");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy年M月d日");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final CampusPlanningAgentSkill planningSkill;
    private final CampusConversationUpdateParser updateParser;
    private final Clock clock;
    private final Duration ttl;
    private final Map<String, CampusConversationState> sessions = new ConcurrentHashMap<>();

    @Autowired
    public CampusConversationService(
            CampusPlanningAgentSkill planningSkill,
            CampusConversationUpdateParser updateParser,
            @Value("${agent.campus.conversation-ttl-minutes:120}") long ttlMinutes) {
        this(planningSkill, updateParser, Clock.systemUTC(), Duration.ofMinutes(ttlMinutes));
    }

    CampusConversationService(
            CampusPlanningAgentSkill planningSkill,
            CampusConversationUpdateParser updateParser,
            Clock clock,
            Duration ttl) {
        this.planningSkill = planningSkill;
        this.updateParser = updateParser;
        this.clock = clock;
        this.ttl = ttl.isNegative() || ttl.isZero() ? Duration.ofMinutes(120) : ttl;
    }

    public Optional<CampusConversationReply> handle(String conversationId, String message) {
        if (!StringUtils.hasText(conversationId) || !StringUtils.hasText(message)) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        removeExpired(now);
        String normalized = normalize(message);

        if (RESET_COMMANDS.contains(normalized)) {
            sessions.remove(conversationId);
            return Optional.of(new CampusConversationReply(
                    "已清除当前活动方案。你可以直接发送新的活动需求，我会先给出通用方案。",
                    "campus_conversation_reset"));
        }

        if (planningSkill.matches(message)) {
            CampusConversationState state = CampusConversationState.start(
                    message, updateParser.parse(message), now);
            sessions.put(conversationId, state);
            return Optional.of(render(state, "campus_conversation_start"));
        }

        CampusConversationState current = sessions.get(conversationId);
        if (current == null) {
            return Optional.empty();
        }
        if (isRecallQuestion(message)) {
            CampusConversationState touched = current.touch(now);
            sessions.put(conversationId, touched);
            return Optional.of(new CampusConversationReply(
                    renderRecall(touched), "campus_conversation_recall"));
        }
        if (RENDER_COMMANDS.contains(normalized)) {
            CampusConversationState touched = current.touch(now);
            sessions.put(conversationId, touched);
            return Optional.of(render(touched, "campus_conversation_render"));
        }
        if (looksLikeQuestion(message) && !containsAny(normalized, EXPLICIT_UPDATE_SIGNALS)) {
            return Optional.empty();
        }

        CampusConversationUpdate update = updateParser.parse(message);
        if (!update.hasChanges()) {
            return Optional.empty();
        }
        CampusConversationState merged = current.merge(update, now);
        sessions.put(conversationId, merged);
        return Optional.of(render(merged, "campus_conversation_update"));
    }

    public Optional<CampusConversationContext> contextFor(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        CampusConversationState state = sessions.get(conversationId);
        if (state == null) {
            return Optional.empty();
        }
        if (expired(state, now)) {
            sessions.remove(conversationId, state);
            return Optional.empty();
        }
        CampusConversationState touched = state.touch(now);
        sessions.put(conversationId, touched);
        return Optional.of(new CampusConversationContext(
                touched.canonicalGoal(), touched.city(), touched.eventDate()));
    }

    boolean hasActiveSession(String conversationId) {
        CampusConversationState state = sessions.get(conversationId);
        return state != null && !expired(state, clock.instant());
    }

    private CampusConversationReply render(CampusConversationState state, String detail) {
        SkillOutput output = planningSkill.executeWithArtifacts(state.canonicalGoal());
        return new CampusConversationReply(
                output.reply(), detail, output.imagePrompt(), output.posterSpec());
    }

    private void removeExpired(Instant now) {
        sessions.entrySet().removeIf(entry -> expired(entry.getValue(), now));
    }

    private boolean expired(CampusConversationState state, Instant now) {
        return state.updatedAt().plus(ttl).isBefore(now);
    }

    private String normalize(String value) {
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s，。；;!！?？]", "");
    }

    private boolean isRecallQuestion(String message) {
        String normalized = normalize(message);
        boolean explicitRecall = normalized.contains("还记得")
                || normalized.contains("记得吗") || normalized.contains("记不记得");
        boolean earlierQuestion = containsAny(normalized, EARLIER_CONTEXT_SIGNALS)
                && looksLikeQuestion(message);
        boolean savedFactQuestion = containsAny(normalized, PLAN_REFERENCE_SIGNALS)
                && containsAny(normalized, FACT_QUESTION_SIGNALS);
        return explicitRecall || earlierQuestion || savedFactQuestion;
    }

    private boolean looksLikeQuestion(String message) {
        String normalized = normalize(message);
        return message.contains("?") || message.contains("？")
                || containsAny(normalized, QUESTION_SIGNALS);
    }

    private boolean containsAny(String value, List<String> signals) {
        return signals.stream().anyMatch(value::contains);
    }

    private String renderRecall(CampusConversationState state) {
        String eventName = hasText(state.eventName()) ? state.eventName() : "校园活动（名称待定）";
        String date = state.eventDate() == null
                ? "尚未确定" : state.eventDate().format(DATE_FORMAT);
        String time = state.startTime() == null
                ? "尚未确定" : state.startTime().format(TIME_FORMAT);
        String location = recallLocation(state);
        String participants = state.participantCount() == null
                ? "尚未确定" : state.participantCount() + "人";
        String budget = state.budget() == null
                ? "尚未确定" : "¥" + state.budget().stripTrailingZeros().toPlainString();
        return """
                记得。你刚才策划的是「%s」，当前保存的信息如下：

                - 日期：%s
                - 时间：%s
                - 地点：%s
                - 人数：%s
                - 预算：%s

                如果具体楼宇或教室还没确定，我可以继续根据学校、活动类型和人数推荐候选场地。
                """.formatted(eventName, date, time, location, participants, budget).trim();
    }

    private String recallLocation(CampusConversationState state) {
        if (hasText(state.venue())) {
            if (!hasText(state.school()) || state.venue().contains(state.school())) {
                return state.venue();
            }
            return state.school() + "·" + state.venue();
        }
        if (hasText(state.school())) {
            return state.school() + "（具体楼宇或教室尚未确定）";
        }
        if (hasText(state.city())) {
            return state.city() + "校内（具体学校和场地尚未确定）";
        }
        return "尚未确定";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
