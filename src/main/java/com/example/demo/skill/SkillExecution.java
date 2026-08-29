package com.example.demo.skill;

import com.example.demo.agent.campus.CampusPosterSpec;

public record SkillExecution(
        String skillName,
        String matchedKeyword,
        String reply,
        String imagePrompt,
        CampusPosterSpec posterSpec) {

    public SkillExecution(
            String skillName,
            String matchedKeyword,
            String reply,
            String imagePrompt) {
        this(skillName, matchedKeyword, reply, imagePrompt, null);
    }

    public SkillExecution(String skillName, String matchedKeyword, String reply) {
        this(skillName, matchedKeyword, reply, null);
    }
}
