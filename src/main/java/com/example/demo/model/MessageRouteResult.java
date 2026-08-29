package com.example.demo.model;

import com.example.demo.agent.campus.CampusPosterSpec;

public record MessageRouteResult(
        MessageRouteType routeType,
        ActionType action,
        ReplyMode replyMode,
        String content,
        String routeDetail,
        String imagePrompt,
        CampusPosterSpec posterSpec) {

    public MessageRouteResult(
            MessageRouteType routeType,
            ActionType action,
            ReplyMode replyMode,
            String content,
            String routeDetail,
            String imagePrompt) {
        this(routeType, action, replyMode, content, routeDetail, imagePrompt, null);
    }

    public MessageRouteResult(
            MessageRouteType routeType,
            ActionType action,
            ReplyMode replyMode,
            String content,
            String routeDetail) {
        this(routeType, action, replyMode, content, routeDetail, null, null);
    }
}
