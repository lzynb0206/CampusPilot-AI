package com.example.demo.agent.campus;

import com.example.demo.config.CampusPosterConfig;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
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

        String prompt = new CampusPosterPromptBuilder(new CampusPosterConfig()).build(goal);

        assertTrue(prompt.contains("未来科技模板"));
        assertTrue(prompt.contains("主标题：「校园AI技术分享会」"));
        assertTrue(prompt.contains("日期：「2026年9月20日」"));
        assertTrue(prompt.contains("时间：「15:30开始」"));
        assertTrue(prompt.contains("地点：「南京信息工程大学·明德楼」"));
        assertTrue(prompt.contains("不得增加虚构信息"));
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

        String prompt = new CampusPosterPromptBuilder(new CampusPosterConfig()).build(goal);

        assertTrue(prompt.contains("活力运动模板"));
        assertTrue(prompt.contains("日期：「日期待定」"));
        assertTrue(prompt.contains("时间：「时间待定」"));
        assertTrue(prompt.contains("地点：「南京信息工程大学·具体场地待定」"));
        assertFalse(prompt.contains("14:00"));
    }

    @Test
    void canDisableAutomaticPosterGeneration() {
        CampusPosterConfig config = new CampusPosterConfig();
        config.setEnabled(false);

        String prompt = new CampusPosterPromptBuilder(config).build(
                new CampusGoalParser().parse("帮我策划一次校园技术活动"));

        assertNull(prompt);
    }
}
