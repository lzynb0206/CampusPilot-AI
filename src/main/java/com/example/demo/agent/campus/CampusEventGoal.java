package com.example.demo.agent.campus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record CampusEventGoal(
        String rawGoal,
        String eventName,
        LocalDate eventDate,
        String city,
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
        return missingFields.isEmpty() && validationIssues.isEmpty();
    }

    private static String normalizeOptionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

