package com.example.demo.skill;

import com.example.demo.agent.campus.CampusPosterSpec;

public record SkillOutput(
        String reply,
        String imagePrompt,
        CampusPosterSpec posterSpec) {

    public SkillOutput(String reply, String imagePrompt) {
        this(reply, imagePrompt, null);
    }

    public SkillOutput {
        if (reply == null || reply.isBlank()) {
            throw new IllegalArgumentException("Skill回复不能为空");
        }
        reply = reply.trim();
        imagePrompt = imagePrompt == null || imagePrompt.isBlank()
                ? null : imagePrompt.trim();
        if (posterSpec != null && imagePrompt == null) {
            imagePrompt = posterSpec.backgroundPrompt();
        }
    }

    public static SkillOutput text(String reply) {
        return new SkillOutput(reply, null);
    }

    public static SkillOutput poster(String reply, CampusPosterSpec posterSpec) {
        return new SkillOutput(reply, null, posterSpec);
    }
}
