package com.example.demo.skill;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class CampusVenueUpdateSkill implements BotSkill {
    private static final List<String> WEATHER_SIGNALS = List.of(
            "天气", "气温", "温度", "下雨", "降雨", "雨天", "几度", "冷不冷", "热不热");
    private static final List<String> CAMPUS_VENUE_SIGNALS = List.of(
            "大学", "学校", "学院", "校区", "校园", "教学楼", "教室",
            "报告厅", "礼堂", "操场", "体育馆", "活动中心");
    private static final List<String> EVENT_LOCATION_SIGNALS = List.of(
            "举行", "举办", "开展", "场地", "地点");
    private static final Pattern NAMED_BUILDING = Pattern.compile(
            "[\\p{IsHan}A-Za-z0-9]{1,20}(?:楼|馆|厅|中心)");

    @Override
    public String name() {
        return "campus_venue_update";
    }

    @Override
    public String description() {
        return "识别校园活动场地补充，确认更新到学校、楼宇或场地级别。";
    }

    @Override
    public List<String> keywords() {
        return List.of("举行", "举办", "开展", "场地", "地点");
    }

    @Override
    public boolean matches(String userMessage) {
        String normalized = normalize(userMessage);
        if (containsAny(normalized, WEATHER_SIGNALS)
                || !containsAny(normalized, EVENT_LOCATION_SIGNALS)) {
            return false;
        }
        return containsAny(normalized, CAMPUS_VENUE_SIGNALS)
                || NAMED_BUILDING.matcher(normalized).find();
    }

    @Override
    public String execute(String userMessage) {
        String venue = extractVenue(userMessage);
        return "好的，活动地点按“" + venue + "”更新。\n\n"
                + "- 方案场地：" + venue + "\n"
                + "- 场地准备：提前确认容量、投影、扩音、电源、网络和消防通道\n"
                + "- 后续只需再补充具体教室号和预约时段\n\n"
                + "其他活动流程、人员分工和预算可以保持不变。";
    }

    private String extractVenue(String message) {
        String value = message == null ? "" : message.trim();
        int end = firstSignalIndex(value, List.of("举行", "举办", "开展", "进行"), value.length());
        String beforeEvent = value.substring(0, end);
        int start = beforeEvent.lastIndexOf('在');
        if (start >= 0) {
            beforeEvent = beforeEvent.substring(start + 1);
        } else {
            beforeEvent = beforeEvent.replaceFirst(
                    "^.*?(?:活动)?(?:地点|场地)\\s*(?:是|为|改为|改到)?\\s*[=:：]?\\s*", "");
        }
        String venue = beforeEvent.replaceAll("[，。；;!！?？]+$", "")
                .replaceAll("\\s+", "")
                .trim();
        return venue.isEmpty() ? "你补充的校内场地" : venue;
    }

    private int firstSignalIndex(String value, List<String> signals, int fallback) {
        int result = fallback;
        for (String signal : signals) {
            int index = value.indexOf(signal);
            if (index >= 0 && index < result) {
                result = index;
            }
        }
        return result;
    }

    private boolean containsAny(String value, List<String> signals) {
        return signals.stream().anyMatch(value::contains);
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }
}
