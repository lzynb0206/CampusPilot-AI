package com.example.demo.agent.campus;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

public record CampusExecutionContext(
        CampusEventGoal goal,
        Map<String, JsonNode> outputs) {

    public CampusExecutionContext {
        if (goal == null) {
            throw new IllegalArgumentException("执行上下文缺少活动目标");
        }
        outputs = outputs == null ? Map.of() : Map.copyOf(outputs);
    }

    public JsonNode requiredOutput(String taskId) {
        JsonNode output = outputs.get(taskId);
        if (output == null) {
            throw new IllegalStateException("缺少依赖任务输出：" + taskId);
        }
        return output;
    }
}

