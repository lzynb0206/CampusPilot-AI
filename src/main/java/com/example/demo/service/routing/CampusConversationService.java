package com.example.demo.service.routing;

import com.example.demo.skill.CampusPlanningAgentSkill;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
        if (RENDER_COMMANDS.contains(normalized)) {
            CampusConversationState touched = current.touch(now);
            sessions.put(conversationId, touched);
            return Optional.of(render(touched, "campus_conversation_render"));
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
        return new CampusConversationReply(planningSkill.execute(state.canonicalGoal()), detail);
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
}
