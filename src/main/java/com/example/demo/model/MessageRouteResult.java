package com.example.demo.model;

public record MessageRouteResult(
        MessageRouteType routeType,
        ActionType action,
        ReplyMode replyMode,
        String content,
        String routeDetail,
        String imagePrompt) {

    public MessageRouteResult(
            MessageRouteType routeType,
            ActionType action,
            ReplyMode replyMode,
            String content,
            String routeDetail) {
        this(routeType, action, replyMode, content, routeDetail, null);
    }
}
