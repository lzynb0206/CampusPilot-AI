package com.example.demo.agent.campus;

import java.util.List;

public record CampusAgentCheckpoint(
        int version,
        String runId,
        String rawGoal,
        List<CampusTaskExecution> taskExecutions,
        int revisionCount) {

    public CampusAgentCheckpoint {
        if (version < 1 || runId == null || runId.isBlank()
                || rawGoal == null || rawGoal.isBlank() || revisionCount < 0) {
            throw new IllegalArgumentException("Agent检查点字段不完整");
        }
        taskExecutions = taskExecutions == null ? List.of() : List.copyOf(taskExecutions);
    }
}
