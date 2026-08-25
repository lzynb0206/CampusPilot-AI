package com.example.demo.agent.campus;

public record CampusEvaluationIssue(
        String code,
        CampusIssueSeverity severity,
        String taskId,
        String message,
        boolean retryable) {

    public CampusEvaluationIssue {
        if (code == null || code.isBlank() || severity == null
                || taskId == null || taskId.isBlank()
                || message == null || message.isBlank()) {
            throw new IllegalArgumentException("Evaluator问题字段不完整");
        }
        code = code.trim();
        taskId = taskId.trim();
        message = message.trim();
    }
}

