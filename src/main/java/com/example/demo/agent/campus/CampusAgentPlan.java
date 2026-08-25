package com.example.demo.agent.campus;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record CampusAgentPlan(CampusEventGoal goal, List<AgentTask> tasks) {
    public CampusAgentPlan {
        if (goal == null) {
            throw new IllegalArgumentException("活动目标不能为空");
        }
        if (tasks == null || tasks.size() < 3) {
            throw new IllegalArgumentException("Agent计划至少需要3个子任务");
        }
        tasks = List.copyOf(tasks);
        validateTaskGraph(tasks);
    }

    private static void validateTaskGraph(List<AgentTask> tasks) {
        Set<String> seenTaskIds = new HashSet<>();
        for (AgentTask task : tasks) {
            if (task == null) {
                throw new IllegalArgumentException("Agent计划不能包含空任务");
            }
            if (!seenTaskIds.add(task.id())) {
                throw new IllegalArgumentException("任务ID重复：" + task.id());
            }
            for (String dependency : task.dependsOn()) {
                if (!seenTaskIds.contains(dependency)) {
                    throw new IllegalArgumentException(
                            "任务依赖不存在或顺序错误：" + task.id() + " -> " + dependency);
                }
            }
        }
    }
}

