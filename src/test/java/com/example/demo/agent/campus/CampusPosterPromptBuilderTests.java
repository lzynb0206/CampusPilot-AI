package com.example.demo.agent.campus;

import com.example.demo.config.CampusPosterConfig;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CampusPosterPromptBuilderTests {
    @Test
    void buildsTechnologyPosterWithOnlyConfirmedEventFacts() {
        CampusEventGoal goal = new CampusEventGoal(
                "校园AI技术分享会",
                "校园AI技术分享会",
                LocalDate.of(2026, 9, 20),
                "南京",
                "南京信息工程大学",
                "明德楼",
                LocalTime.of(15, 30),
                80,
                new BigDecimal("3000"),
                List.of(),
                List.of());

        CampusPosterSpec spec = new CampusPosterPromptBuilder(
                new CampusPosterConfig()).build(goal);

        assertEquals(CampusPosterLayout.EDITORIAL, spec.layout());
        assertEquals("技术分享", spec.categoryLabel());
        assertEquals("校园AI技术分享会", spec.eventName());
        assertEquals("南京信息工程大学", spec.school());
        assertEquals("2026年9月20日", spec.date());
        assertEquals("15:30开始", spec.time());
        assertEquals("南京信息工程大学·明德楼", spec.location());
        assertTrue(spec.backgroundPrompt().contains("未来科技视觉"));
        assertTrue(spec.backgroundPrompt().contains("只生成背景"));
        assertTrue(spec.backgroundPrompt().contains("每次使用不同"));
        assertFalse(spec.backgroundPrompt().contains("校园AI技术分享会"));
    }

    @Test
    void preservesPendingFieldsInsteadOfInventingPosterDetails() {
        CampusEventGoal goal = new CampusEventGoal(
                "校园趣味运动会",
                "校园趣味运动会",
                null,
                null,
                "南京信息工程大学",
                null,
                null,
                50,
                new BigDecimal("2000"),
                List.of("活动日期", "举办城市"),
                List.of());

        CampusPosterSpec spec = new CampusPosterPromptBuilder(
                new CampusPosterConfig()).build(goal);

        assertEquals(CampusPosterLayout.CINEMATIC, spec.layout());
        assertEquals("运动赛事", spec.categoryLabel());
        assertEquals("日期待定", spec.date());
        assertEquals("时间待定", spec.time());
        assertEquals("南京信息工程大学·具体场地待定", spec.location());
        assertTrue(spec.backgroundPrompt().contains("活力运动视觉"));
        assertFalse(spec.backgroundPrompt().contains("14:00"));
    }

    @Test
    void canDisableAutomaticPosterGeneration() {
        CampusPosterConfig config = new CampusPosterConfig();
        config.setEnabled(false);

        CampusPosterSpec spec = new CampusPosterPromptBuilder(config).build(
                new CampusGoalParser().parse("帮我策划一次校园技术活动"));

        assertNull(spec);
    }
}
