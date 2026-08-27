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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

    @Test
    void runsIndependentTasksInSameDependencyLayerConcurrently() {
        DefaultCampusTaskRunner delegate = defaultRunner();
        CountDownLatch bothStarted = new CountDownLatch(2);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximumActive = new AtomicInteger();
        Set<String> parallelLayer = Set.of("retrieve_campus_rules", "research_weather");
        CampusTaskRunner runner = (task, context) -> {
            if (parallelLayer.contains(task.id())) {
                int current = active.incrementAndGet();
                maximumActive.accumulateAndGet(current, Math::max);
                bothStarted.countDown();
                try {
                    try {
                        assertTrue(bothStarted.await(2, TimeUnit.SECONDS),
                                "同一依赖层的任务没有并行启动");
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("并行测试被中断", exception);
                    }
                    return delegate.execute(task, context);
                } finally {
                    active.decrementAndGet();
                }
            }
            return delegate.execute(task, context);
        };

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CampusAgentOrchestrator orchestrator = new CampusAgentOrchestrator(
                    new CampusGoalParser(),
                    new CampusTaskPlanner(),
                    runner,
                    new CampusPlanEvaluator(),
                    executor,
                    CampusAgentCheckpointStore.noop());

            CampusAgentRun run = orchestrator.run(
                    "帮我策划一场2026年9月20日在苏州举行、50人参加、预算2000元的校园AI技术分享会。");

            assertEquals(CampusAgentRunStatus.COMPLETED, run.status());
            assertEquals(2, maximumActive.get());
        }
    }

    @Test
    void resumesSuccessfulTasksFromCheckpointAndSkipsRepeatedWeatherCall() {
        AtomicBoolean budgetAvailable = new AtomicBoolean(false);
        AtomicInteger weatherCalls = new AtomicInteger();
        DefaultCampusTaskRunner delegate = defaultRunner(new CountingFixedTool(
                "assess_event_weather", weatherCalls, weatherTooEarlyResult()));
        CampusTaskRunner resumableRunner = (task, context) -> {
            if (task.id().equals("allocate_budget") && !budgetAvailable.get()) {
                throw new IllegalStateException("模拟预算服务暂时不可用");
            }
            return delegate.execute(task, context);
        };
        InMemoryCheckpointStore checkpointStore = new InMemoryCheckpointStore();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CampusAgentOrchestrator orchestrator = new CampusAgentOrchestrator(
                    new CampusGoalParser(),
                    new CampusTaskPlanner(),
                    resumableRunner,
                    new CampusPlanEvaluator(),
                    executor,
                    checkpointStore);
            String goal = "帮我策划一场2026年9月20日在苏州举行、50人参加、预算2000元的校园AI技术分享会。";

            CampusAgentRun failed = orchestrator.run(goal);
            assertEquals(CampusAgentRunStatus.FAILED, failed.status());
            assertTrue(checkpointStore.load(failed.runId()).isPresent());
            assertEquals(1, weatherCalls.get());

            budgetAvailable.set(true);
            CampusAgentRun resumed = orchestrator.run(goal);

            assertEquals(CampusAgentRunStatus.COMPLETED, resumed.status());
            assertTrue(resumed.resumedTaskCount() >= 8);
            assertEquals(1, weatherCalls.get(), "已成功的天气任务不应重复调用");
            assertFalse(checkpointStore.load(resumed.runId()).isPresent(),
                    "完成后应删除检查点");
        }
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

    private static class InMemoryCheckpointStore implements CampusAgentCheckpointStore {
        private CampusAgentCheckpoint checkpoint;

        @Override
        public synchronized Optional<CampusAgentCheckpoint> load(String runId) {
            return checkpoint != null && checkpoint.runId().equals(runId)
                    ? Optional.of(checkpoint) : Optional.empty();
        }

        @Override
        public synchronized void save(CampusAgentCheckpoint checkpoint) {
            this.checkpoint = checkpoint;
        }

        @Override
        public synchronized void delete(String runId) {
            if (checkpoint != null && checkpoint.runId().equals(runId)) {
                checkpoint = null;
            }
        }
    }
}
