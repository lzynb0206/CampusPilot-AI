package com.example.demo.service.venue;

import java.util.List;

public enum CampusVenuePreference {
    GENERAL("教学楼", List.of(
            "教学楼", "教室", "报告厅", "讲堂", "会议室", "活动中心", "图书馆", "楼", "厅")),
    PERFORMANCE("礼堂", List.of(
            "礼堂", "报告厅", "讲堂", "剧场", "音乐厅", "活动中心", "体育馆", "厅")),
    SPORTS("体育场", List.of(
            "体育场", "体育馆", "操场", "足球场", "篮球场", "球场", "广场", "草坪"));

    private final String searchKeyword;
    private final List<String> relevanceSignals;

    CampusVenuePreference(String searchKeyword, List<String> relevanceSignals) {
        this.searchKeyword = searchKeyword;
        this.relevanceSignals = relevanceSignals;
    }

    public String searchKeyword() {
        return searchKeyword;
    }

    public boolean matches(String value) {
        return value != null && relevanceSignals.stream().anyMatch(value::contains);
    }
}
