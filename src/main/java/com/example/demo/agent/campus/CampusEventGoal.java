package com.example.demo.agent.campus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record CampusEventGoal(
        String rawGoal,
        String eventName,
        LocalDate eventDate,
        String city,
        String school,
        String venue,
        LocalTime startTime,
        Integer participantCount,
        BigDecimal budget,
        List<String> missingFields,
        List<String> validationIssues) {

    public CampusEventGoal {
        if (rawGoal == null || rawGoal.isBlank()) {
            throw new IllegalArgumentException("最终目标不能为空");
        }
        rawGoal = rawGoal.trim();
        eventName = normalizeOptionalText(eventName);
        city = normalizeOptionalText(city);
        school = normalizeOptionalText(school);
        venue = normalizeOptionalText(venue);
        if (participantCount != null && participantCount <= 0) {
            throw new IllegalArgumentException("参与人数必须大于0");
        }
        if (budget != null && budget.signum() <= 0) {
            throw new IllegalArgumentException("预算必须大于0");
        }
        missingFields = missingFields == null ? List.of() : List.copyOf(missingFields);
        validationIssues = validationIssues == null ? List.of() : List.copyOf(validationIssues);
    }

    public boolean isReadyForExecution() {
        // 缺少信息不应阻断通用方案；只有用户明确给出了无效值时才需要先修正。
        return validationIssues.isEmpty();
    }

    private static String normalizeOptionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
