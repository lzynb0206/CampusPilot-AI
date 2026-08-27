package com.example.demo.agent.campus;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CampusTaskPlannerTests {
    @Test
    void createsMultiStepPlanUsingAllRequiredCapabilityTypes() {
        CampusEventGoal goal = completeGoal();

        CampusAgentPlan plan = new CampusTaskPlanner().createPlan(goal);

        assertEquals(12, plan.tasks().size());
        Set<TaskCapability> capabilities = plan.tasks().stream()
                .map(AgentTask::capability)
                .collect(Collectors.toSet());
        assertTrue(capabilities.containsAll(Set.of(
                TaskCapability.LLM,
                TaskCapability.RAG,
                TaskCapability.TOOL,
                TaskCapability.SKILL,
                TaskCapability.EVALUATOR)));
        assertEquals("assemble_proposal", plan.tasks().getLast().id());
        assertEquals(List.of("evaluate_completeness"), plan.tasks().getLast().dependsOn());
    }

    @Test
    void rejectsDependencyThatHasNotBeenDefined() {
        AgentTask invalidTask = new AgentTask(
                "second", "错误任务", TaskCapability.TOOL, List.of("missing"), "测试输出");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new CampusAgentPlan(completeGoal(), List.of(
                        new AgentTask("first", "第一项", TaskCapability.LLM, List.of(), "输出一"),
                        invalidTask,
                        new AgentTask("third", "第三项", TaskCapability.LLM, List.of("second"), "输出三"))));

        assertTrue(exception.getMessage().contains("依赖不存在"));
    }

    private CampusEventGoal completeGoal() {
        return new CampusEventGoal(
                "策划校园AI分享会",
                "校园AI分享会",
                LocalDate.of(2026, 9, 20),
                "苏州",
                null,
                null,
                null,
                50,
                new BigDecimal("2000"),
                List.of(),
                List.of());
    }
}
