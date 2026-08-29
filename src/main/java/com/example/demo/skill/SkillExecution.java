package com.example.demo.skill;

public record SkillExecution(
        String skillName,
        String matchedKeyword,
        String reply,
        String imagePrompt) {

    public SkillExecution(String skillName, String matchedKeyword, String reply) {
        this(skillName, matchedKeyword, reply, null);
    }
}
