package com.example.demo.service.routing;

import com.example.demo.agent.campus.CampusGoalParser;
import com.example.demo.skill.CampusPlanningAgentSkill;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CampusConversationServiceTests {
    @Test
    void mergesFollowUpsIntoTheSameUsersPlanAndKeepsUsersIsolated() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-27T05:30:00Z"));
        CampusConversationService service = service(clock, Duration.ofHours(2));

        CampusConversationReply initial = service.handle(
                "user-a", "帮我策划一次校园技术活动").orElseThrow();
        CampusConversationReply venue = service.handle(
                "user-a", "我是在南京信息工程大学 明德楼举行").orElseThrow();
        CampusConversationReply details = service.handle(
                "user-a", "人数改80，预算改成3000元，下周三，开始时间改成15点30")
                .orElseThrow();

        assertTrue(initial.content().contains("活动名称：校园技术活动"));
        assertTrue(venue.content().contains("学校：南京信息工程大学"));
        assertTrue(venue.content().contains("活动场地：南京信息工程大学明德楼"));
        assertTrue(details.content().contains("参与人数：80人"));
        assertTrue(details.content().contains("预算：3000元"));
        assertTrue(details.content().contains("活动日期：2026-09-02"));
        assertTrue(details.content().contains("开始时间：15:30"));
        assertTrue(service.handle("user-b", "改到西区食堂三楼").isEmpty());
        assertFalse(service.hasActiveSession("user-b"));
    }

    @Test
    void resetsAndExpiresAUsersPlan() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-27T05:30:00Z"));
        CampusConversationService service = service(clock, Duration.ofMinutes(30));

        service.handle("user-a", "帮我策划一次校园技术活动").orElseThrow();
        assertTrue(service.hasActiveSession("user-a"));
        service.handle("user-a", "清除方案").orElseThrow();
        assertFalse(service.hasActiveSession("user-a"));

        service.handle("user-a", "帮我策划一次校园技术活动").orElseThrow();
        clock.advance(Duration.ofMinutes(31));
        assertTrue(service.handle("user-a", "人数改80").isEmpty());
        assertFalse(service.hasActiveSession("user-a"));
    }

    private CampusConversationService service(Clock clock, Duration ttl) {
        CampusGoalParser goalParser = new CampusGoalParser(clock.withZone(ZoneId.of("Asia/Shanghai")));
        return new CampusConversationService(
                new EchoPlanningSkill(),
                new CampusConversationUpdateParser(goalParser),
                clock,
                ttl);
    }

    private static class EchoPlanningSkill extends CampusPlanningAgentSkill {
        EchoPlanningSkill() {
            super(null, null);
        }

        @Override
        public boolean matches(String userMessage) {
            return userMessage.contains("帮我策划") && userMessage.contains("活动");
        }

        @Override
        public String execute(String userMessage) {
            return userMessage;
        }
    }

    private static class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
