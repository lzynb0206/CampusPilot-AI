package com.example.demo.agent.campus;

import java.util.List;

public record CampusAgentRun(
        CampusAgentRunStatus status,
        CampusEventGoal goal,
        CampusAgentPlan plan,
        List<CampusTaskExecution> taskExecutions,
        CampusEvaluationReport evaluation,
        int revisionCount) {

    public CampusAgentRun {
        if (status == null || goal == null || plan == null || evaluation == null) {
            throw new IllegalArgumentException("Agent运行结果字段不完整");
        }
        if (revisionCount < 0) {
            throw new IllegalArgumentException("自动修订次数不能小于0");
        }
        taskExecutions = taskExecutions == null ? List.of() : List.copyOf(taskExecutions);
    }

    public CampusTaskExecution execution(String taskId) {
        return taskExecutions.stream()
                .filter(execution -> execution.taskId().equals(taskId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("找不到任务执行记录：" + taskId));
    }
}

