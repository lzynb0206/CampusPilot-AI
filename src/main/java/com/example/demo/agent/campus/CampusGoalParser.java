package com.example.demo.agent.campus;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CampusGoalParser {
    private static final Pattern DATE = Pattern.compile(
            "(?<!\\d)(\\d{4})\\s*[年./-]\\s*(\\d{1,2})\\s*[月./-]\\s*(\\d{1,2})\\s*日?");
    private static final Pattern CITY = Pattern.compile(
            "(?:在|地点\\s*[=：:]\\s*)([\\p{IsHan}]{2,20}?)(?:市)?(?:举办|举行|开展|组织)");
    private static final Pattern PARTICIPANT_COUNT = Pattern.compile(
            "(?<!\\d)(\\d{1,6})\\s*(?:人|位)(?:参加|参与|规模)?");
    private static final Pattern BUDGET = Pattern.compile(
            "预算(?:为|是|约|大约|不超过|控制在)?\\s*[=：:]?\\s*(\\d+(?:\\.\\d+)?)\\s*(万)?\\s*(?:元|人民币)");
    private static final Pattern EXPLICIT_EVENT_NAME = Pattern.compile(
            "(?:活动名称|活动主题|名称|主题)\\s*[=：:]\\s*([^，。；;]{2,60})");
    private static final Pattern EVENT_NAME_AFTER_BUDGET = Pattern.compile(
            "(?:元|人民币)\\s*的?\\s*([^，。；;]{2,60}?(?:分享会|活动|讲座|比赛|论坛|晚会|展览|沙龙|会议|团建))");

    public CampusEventGoal parse(String rawGoal) {
        if (rawGoal == null || rawGoal.isBlank()) {
            throw new IllegalArgumentException("最终目标不能为空");
        }

        String goal = rawGoal.trim();
        List<String> validationIssues = new ArrayList<>();
        String eventName = extractEventName(goal);
        LocalDate eventDate = extractDate(goal, validationIssues);
        String city = firstGroup(CITY, goal);
        Integer participantCount = extractInteger(PARTICIPANT_COUNT, goal);
        BigDecimal budget = extractBudget(goal);

        List<String> missingFields = new ArrayList<>();
        addMissing(missingFields, eventName, "活动名称");
        addMissing(missingFields, eventDate, "活动日期");
        addMissing(missingFields, city, "举办城市");
        addMissing(missingFields, participantCount, "参与人数");
        addMissing(missingFields, budget, "总预算");

        return new CampusEventGoal(
                goal,
                eventName,
                eventDate,
                city,
                participantCount,
                budget,
                missingFields,
                validationIssues);
    }

    private LocalDate extractDate(String goal, List<String> validationIssues) {
        Matcher matcher = DATE.matcher(goal);
        if (!matcher.find()) {
            return null;
        }
        int year = Integer.parseInt(matcher.group(1));
        int month = Integer.parseInt(matcher.group(2));
        int day = Integer.parseInt(matcher.group(3));
        try {
            return LocalDate.of(year, month, day);
        } catch (DateTimeException exception) {
            validationIssues.add("活动日期无效：" + matcher.group());
            return null;
        }
    }

    private String extractEventName(String goal) {
        String explicitName = firstGroup(EXPLICIT_EVENT_NAME, goal);
        if (explicitName != null) {
            return explicitName;
        }
        return firstGroup(EVENT_NAME_AFTER_BUDGET, goal);
    }

    private BigDecimal extractBudget(String goal) {
        Matcher matcher = BUDGET.matcher(goal);
        if (!matcher.find()) {
            return null;
        }
        BigDecimal amount = new BigDecimal(matcher.group(1));
        return matcher.group(2) == null ? amount : amount.multiply(BigDecimal.valueOf(10_000));
    }

    private Integer extractInteger(Pattern pattern, String value) {
        String result = firstGroup(pattern, value);
        return result == null ? null : Integer.valueOf(result);
    }

    private String firstGroup(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private void addMissing(List<String> missingFields, Object value, String fieldName) {
        if (value == null) {
            missingFields.add(fieldName);
        }
    }
}

