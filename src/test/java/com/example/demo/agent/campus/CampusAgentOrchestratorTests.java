package com.example.demo.agent.campus;

import com.example.demo.config.RagConfig;
import com.example.demo.rag.KeywordRagService;
import com.example.demo.tool.BotTool;
import com.example.demo.tool.EventBudgetTool;
import com.example.demo.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CampusAgentOrchestratorTests {
    @Test
    void executesPlanEndToEndAndTreatsPendingWeatherAsWarning() {
        CampusAgentOrchestrator orchestrator = orchestrator(defaultRunner());

        CampusAgentRun run = orchestrator.run(
                "帮我策划一场2026年9月20日在苏州举行、50人参加、预算2000元的校园AI技术分享会。");

        assertEquals(CampusAgentRunStatus.COMPLETED, run.status());
        assertEquals(11, run.taskExecutions().size());
        assertTrue(run.taskExecutions().stream()
                .allMatch(execution -> execution.status() == CampusTaskStatus.SUCCEEDED));
        assertTrue(run.evaluation().passed());
        assertTrue(run.evaluation().issues().stream()
                .anyMatch(issue -> issue.code().equals("WEATHER_PENDING")));
        assertTrue(run.evaluation().issues().stream()
                .anyMatch(issue -> issue.code().equals("RULES_REQUIRE_VERIFICATION")));
        assertEquals("STRUCTURED_DRAFT",
                run.execution("assemble_proposal").output().path("format").asText());
        assertEquals(0, run.revisionCount());
    }

    @Test
    void stopsBeforeExternalTasksWhenGoalIsIncomplete() {
        AtomicInteger toolCalls = new AtomicInteger();
        CampusTaskRunner runner = defaultRunner(new CountingFixedTool(
                "assess_event_weather", toolCalls, weatherTooEarlyResult()));
        CampusAgentOrchestrator orchestrator = orchestrator(runner);

        CampusAgentRun run = orchestrator.run("帮我策划一次校园技术活动");

        assertEquals(CampusAgentRunStatus.NEEDS_INPUT, run.status());
        assertFalse(run.evaluation().passed());
        assertEquals(CampusTaskStatus.SUCCEEDED,
                run.execution("resolve_constraints").status());
        assertEquals(CampusTaskStatus.BLOCKED,
                run.execution("research_weather").status());
        assertEquals(0, toolCalls.get());
        assertTrue(run.evaluation().issues().stream()
                .anyMatch(issue -> issue.code().equals("MISSING_CONSTRAINT")));
    }

    @Test
    void evaluatorRetriesInvalidBudgetAndThenCompletes() {
        DefaultCampusTaskRunner delegate = defaultRunner();
        AtomicInteger budgetExecutions = new AtomicInteger();
        CampusTaskRunner flakyRunner = (task, context) -> {
            if (task.id().equals("allocate_budget")
                    && budgetExecutions.incrementAndGet() == 1) {
                var invalidBudget = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                        .objectNode();
                invalidBudget.put("total_budget", 2000);
                invalidBudget.putArray("items").addObject()
                        .put("category", "错误分项")
                        .put("amount", 1999);
                invalidBudget.put("allocated_total", 1999);
                invalidBudget.put("unallocated", 1);
                return invalidBudget;
            }
            return delegate.execute(task, context);
        };
        CampusAgentOrchestrator orchestrator = orchestrator(flakyRunner);

        CampusAgentRun run = orchestrator.run(
                "帮我策划一场2026年9月20日在苏州举行、50人参加、预算2000元的校园AI技术分享会。");

        assertEquals(CampusAgentRunStatus.COMPLETED, run.status());
        assertTrue(run.evaluation().passed());
        assertEquals(1, run.revisionCount());
        assertEquals(2, run.execution("allocate_budget").attempts());
        assertEquals(2, budgetExecutions.get());
        assertEquals(0, run.execution("allocate_budget").output()
                .path("unallocated").decimalValue().signum());
    }

    private CampusAgentOrchestrator orchestrator(CampusTaskRunner runner) {
        return new CampusAgentOrchestrator(
                new CampusGoalParser(),
                new CampusTaskPlanner(),
                runner,
                new CampusPlanEvaluator());
    }

    private DefaultCampusTaskRunner defaultRunner() {
        return defaultRunner(new CountingFixedTool(
                "assess_event_weather", new AtomicInteger(), weatherTooEarlyResult()));
    }

    private DefaultCampusTaskRunner defaultRunner(BotTool weatherTool) {
        ToolRegistry toolRegistry = new ToolRegistry(List.of(
                new EventBudgetTool(), weatherTool));
        return new DefaultCampusTaskRunner(
                new KeywordRagService(new RagConfig()), toolRegistry);
    }

    private String weatherTooEarlyResult() {
        return """
                {"success":true,"location":"苏州","event_date":"2026-09-20",
                 "status":"TOO_EARLY","forecast_available":false,
                 "recheck_on":"2026-09-06","final_check_on":"2026-09-19",
                 "message":"活动日期超出预报范围","recommendations":["预留室内场地"]}
                """;
    }

    private static class CountingFixedTool implements BotTool {
        private final String name;
        private final AtomicInteger calls;
        private final String result;

        private CountingFixedTool(String name, AtomicInteger calls, String result) {
            this.name = name;
            this.calls = calls;
            this.result = result;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "返回固定天气评估结果";
        }

        @Override
        public Map<String, Object> parametersSchema() {
            return Map.of("type", "object");
        }

        @Override
        public String execute(JsonNode arguments) {
            calls.incrementAndGet();
            return result;
        }
    }
}
