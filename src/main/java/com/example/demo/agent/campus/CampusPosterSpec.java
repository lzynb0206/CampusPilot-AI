package com.example.demo.agent.campus;

public record CampusPosterSpec(
        String backgroundPrompt,
        CampusPosterLayout layout,
        String categoryLabel,
        String eventName,
        String school,
        String date,
        String time,
        String location,
        String callToAction) {

    public CampusPosterSpec {
        if (backgroundPrompt == null || backgroundPrompt.isBlank()) {
            throw new IllegalArgumentException("海报背景提示词不能为空");
        }
        if (layout == null) {
            throw new IllegalArgumentException("海报版式不能为空");
        }
        categoryLabel = fallback(categoryLabel, "校园活动");
        eventName = fallback(eventName, "校园主题活动");
        school = fallback(school, "校园活动");
        date = fallback(date, "日期待定");
        time = fallback(time, "时间待定");
        location = fallback(location, "校内·具体场地待定");
        callToAction = fallback(callToAction, "欢迎报名参加");
        backgroundPrompt = backgroundPrompt.trim();
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
