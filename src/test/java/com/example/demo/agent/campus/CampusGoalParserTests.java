package com.example.demo.agent.campus;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CampusGoalParserTests {
    private final CampusGoalParser parser = new CampusGoalParser();

    @Test
    void parsesCompleteOneSentenceGoal() {
        CampusEventGoal goal = parser.parse(
                "帮我策划一场2026年9月20日在苏州举行、50人参加、预算2000元的校园AI技术分享会。");

        assertEquals("校园AI技术分享会", goal.eventName());
        assertEquals(LocalDate.of(2026, 9, 20), goal.eventDate());
        assertEquals("苏州", goal.city());
        assertEquals(50, goal.participantCount());
        assertEquals(new BigDecimal("2000"), goal.budget());
        assertTrue(goal.missingFields().isEmpty());
        assertTrue(goal.validationIssues().isEmpty());
        assertTrue(goal.isReadyForExecution());
    }

    @Test
    void keepsMissingFactsUnknownInsteadOfInventingDefaults() {
        CampusEventGoal goal = parser.parse("帮我策划一次校园技术活动");

        assertNull(goal.eventDate());
        assertNull(goal.city());
        assertNull(goal.participantCount());
        assertNull(goal.budget());
        assertTrue(goal.missingFields().contains("活动日期"));
        assertTrue(goal.missingFields().contains("举办城市"));
        assertTrue(goal.missingFields().contains("参与人数"));
        assertTrue(goal.missingFields().contains("总预算"));
        assertFalse(goal.isReadyForExecution());
    }

    @Test
    void reportsInvalidDateWithoutCrashing() {
        CampusEventGoal goal = parser.parse(
                "活动名称=AI分享会，2026年2月30日在苏州举行，50人参加，预算2000元");

        assertNull(goal.eventDate());
        assertTrue(goal.missingFields().contains("活动日期"));
        assertEquals(1, goal.validationIssues().size());
        assertTrue(goal.validationIssues().getFirst().contains("日期无效"));
        assertFalse(goal.isReadyForExecution());
    }
}

