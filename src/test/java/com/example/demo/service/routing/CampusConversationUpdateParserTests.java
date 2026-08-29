package com.example.demo.service.routing;

import com.example.demo.agent.campus.CampusGoalParser;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class CampusConversationUpdateParserTests {
    private final CampusConversationUpdateParser parser = new CampusConversationUpdateParser(
            new CampusGoalParser(Clock.fixed(
                    Instant.parse("2026-08-27T05:30:00Z"),
                    ZoneId.of("Asia/Shanghai"))));

    @Test
    void extractsAnySchoolAndSpecificCampusVenueWithoutTreatingItAsACity() {
        CampusConversationUpdate update = parser.parse(
                "我是在南京信息工程大学 明德楼举行");

        assertEquals("南京信息工程大学", update.school());
        assertEquals("南京信息工程大学明德楼", update.venue());
        assertNull(update.city());
    }

    @Test
    void extractsAnotherSchoolAndDifferentVenue() {
        CampusConversationUpdate update = parser.parse("换到北京大学百周年纪念讲堂");

        assertEquals("北京大学", update.school());
        assertEquals("北京大学百周年纪念讲堂", update.venue());
    }

    @Test
    void extractsSchoolAndVenueWhenBothAreChangedTogether() {
        CampusConversationUpdate update = parser.parse(
                "学校改成清华大学，场地改到大礼堂");

        assertEquals("清华大学", update.school());
        assertEquals("大礼堂", update.venue());
    }

    @Test
    void extractsMultipleFollowUpFieldsFromNaturalLanguage() {
        CampusConversationUpdate update = parser.parse(
                "人数改80，预算改成3000元，下周三，开始时间改成15点30");

        assertEquals(80, update.participantCount());
        assertEquals(new BigDecimal("3000"), update.budget());
        assertEquals(LocalDate.of(2026, 9, 2), update.eventDate());
        assertEquals(LocalTime.of(15, 30), update.startTime());
    }

    @Test
    void doesNotInventAnUpdateFromAQuestionAboutOtherSchools() {
        CampusConversationUpdate update = parser.parse("那别的学校呢？");

        assertFalse(update.hasChanges());
    }

    @Test
    void treatsSchoolSelfIntroductionAsSchoolOnlyUpdate() {
        CampusConversationUpdate update = parser.parse("我是南京信息工程大学的");

        assertEquals("南京信息工程大学", update.school());
        assertNull(update.venue());
    }
}
