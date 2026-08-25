package com.example.demo.agent.campus;

import com.fasterxml.jackson.databind.JsonNode;

public record CampusTaskExecution(
        String taskId,
        CampusTaskStatus status,
        int attempts,
        JsonNode output,
        String error) {

    public CampusTaskExecution {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("任务执行记录缺少taskId");
        }
        if (status == null) {
            throw new IllegalArgumentException("任务执行记录缺少状态");
        }
        if (attempts < 0) {
            throw new IllegalArgumentException("任务尝试次数不能小于0");
        }
        taskId = taskId.trim();
        error = error == null || error.isBlank() ? null : error.trim();
    }
}

