package com.example.demo.service.routing;

public record CampusConversationReply(
        String content,
        String detail,
        String imagePrompt) {

    public CampusConversationReply(String content, String detail) {
        this(content, detail, null);
    }
}
