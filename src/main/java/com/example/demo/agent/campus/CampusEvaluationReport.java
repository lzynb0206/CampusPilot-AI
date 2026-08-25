package com.example.demo.agent.campus;

import java.util.List;

public record CampusEvaluationReport(List<CampusEvaluationIssue> issues) {
    public CampusEvaluationReport {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public boolean passed() {
        return issues.stream().noneMatch(
                issue -> issue.severity() == CampusIssueSeverity.ERROR);
    }
}

