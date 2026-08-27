package com.example.demo.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CampusVenueUpdateSkillTests {
    private final CampusVenueUpdateSkill skill = new CampusVenueUpdateSkill();

    @Test
    void treatsUniversityBuildingAsVenueUpdateInsteadOfWeatherRequest() {
        String message = "我是在南京信息工程大学 明德楼举行";

        assertTrue(skill.matches(message));
        String reply = skill.execute(message);
        assertTrue(reply.contains("活动地点按“南京信息工程大学明德楼”更新"));
        assertFalse(reply.contains("天气"));
    }

    @Test
    void leavesExplicitWeatherQuestionToWeatherRoute() {
        assertFalse(skill.matches("我在南京信息工程大学明德楼举行，天气会不会下雨"));
    }
}
