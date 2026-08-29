package com.example.demo.skill;

public record SkillOutput(String reply, String imagePrompt) {
    public SkillOutput {
        if (reply == null || reply.isBlank()) {
            throw new IllegalArgumentException("Skill回复不能为空");
        }
        reply = reply.trim();
        imagePrompt = imagePrompt == null || imagePrompt.isBlank()
                ? null : imagePrompt.trim();
    }

    public static SkillOutput text(String reply) {
        return new SkillOutput(reply, null);
    }
}
