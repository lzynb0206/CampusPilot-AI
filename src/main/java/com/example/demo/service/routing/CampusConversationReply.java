package com.example.demo.service.routing;

import com.example.demo.agent.campus.CampusPosterSpec;

public record CampusConversationReply(
        String content,
        String detail,
        String imagePrompt,
        CampusPosterSpec posterSpec) {

    public CampusConversationReply(
            String content,
            String detail,
            String imagePrompt) {
        this(content, detail, imagePrompt, null);
    }

    public CampusConversationReply(String content, String detail) {
        this(content, detail, null);
    }
}
