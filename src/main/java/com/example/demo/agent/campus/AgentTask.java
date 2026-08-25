package com.example.demo.agent.campus;

import java.util.List;

public record AgentTask(
        String id,
        String title,
        TaskCapability capability,
        List<String> dependsOn,
        String expectedOutput) {

    public AgentTask {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("任务ID不能为空");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("任务标题不能为空");
        }
        if (capability == null) {
            throw new IllegalArgumentException("任务能力类型不能为空");
        }
        if (expectedOutput == null || expectedOutput.isBlank()) {
            throw new IllegalArgumentException("任务预期输出不能为空");
        }
        id = id.trim();
        title = title.trim();
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        expectedOutput = expectedOutput.trim();
    }
}

