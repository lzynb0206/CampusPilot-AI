package com.example.demo.agent.campus;

import com.example.demo.config.ConcurrencyConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;

@Slf4j
@Service
public class CampusAgentOrchestrator {
    private static final int CHECKPOINT_VERSION = 1;
    private static final int MAX_REVISIONS = 2;
    private static final String EVALUATOR_TASK = "evaluate_completeness";
    private static final String ASSEMBLY_TASK = "assemble_proposal";

    private final CampusGoalParser goalParser;
    private final CampusTaskPlanner taskPlanner;
    private final CampusTaskRunner taskRunner;
    private final CampusPlanEvaluator evaluator;
    private final ExecutorService taskExecutor;
    private final CampusAgentCheckpointStore checkpointStore;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CampusAgentOrchestrator(
            CampusGoalParser goalParser,
            CampusTaskPlanner taskPlanner,
            CampusTaskRunner taskRunner,
            CampusPlanEvaluator evaluator) {
        this(goalParser, taskPlanner, taskRunner, evaluator,
                ForkJoinPool.commonPool(), CampusAgentCheckpointStore.noop());
    }

    @Autowired
    public CampusAgentOrchestrator(
            CampusGoalParser goalParser,
            CampusTaskPlanner taskPlanner,
            CampusTaskRunner taskRunner,
            CampusPlanEvaluator evaluator,
            @Qualifier(ConcurrencyConfig.APPLICATION_TASK_EXECUTOR)
            ExecutorService taskExecutor,
            CampusAgentCheckpointStore checkpointStore) {
        this.goalParser = goalParser;
        this.taskPlanner = taskPlanner;
        this.taskRunner = taskRunner;
        this.evaluator = evaluator;
        this.taskExecutor = taskExecutor;
        this.checkpointStore = checkpointStore;
    }

    public CampusAgentRun run(String finalGoal) {
        return runInternal(runIdFor(finalGoal), finalGoal, true);
    }

    public CampusAgentRun resume(String runId) {
        CampusAgentCheckpoint checkpoint = checkpointStore.load(runId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "找不到可续跑的校园Agent任务：" + runId));
        return runInternal(runId, checkpoint.rawGoal(), true);
    }

    private CampusAgentRun runInternal(
            String runId, String finalGoal, boolean loadCheckpoint) {
        CampusEventGoal goal = goalParser.parse(finalGoal);
        CampusAgentPlan plan = taskPlanner.createPlan(goal);
        Map<String, CampusTaskExecution> executions = new LinkedHashMap<>();
        Map<String, JsonNode> outputs = new LinkedHashMap<>();
        Map<String, Integer> attempts = new HashMap<>();
        Optional<CampusAgentCheckpoint> checkpoint = loadCheckpoint
                ? checkpointStore.load(runId) : Optional.empty();
        int resumedTaskCount = restoreCheckpoint(
                checkpoint, goal, plan, executions, outputs, attempts);
        if (resumedTaskCount > 0) {
            log.info("Campus Agent恢复检查点 runId={} restoredTasks={}",
                    runId, resumedTaskCount);
        }
        int totalRevisions = checkpoint.map(CampusAgentCheckpoint::revisionCount).orElse(0);

        if (!isSuccessful(executions.get("resolve_constraints"))) {
            AgentTask constraintsTask = task(plan, "resolve_constraints");
            executeTask(constraintsTask, goal, executions, outputs, attempts);
        }
        if (!goal.isReadyForExecution()) {
            CampusEvaluationReport report = evaluator.evaluateGoal(goal);
            blockRemainingTasks(plan, executions, "等待用户补充或修正活动约束");
            recordEvaluation(report, executions, outputs, 1);
            checkpointStore.delete(runId);
            return new CampusAgentRun(
                    CampusAgentRunStatus.NEEDS_INPUT,
                    runId,
                    goal,
                    plan,
                    orderedExecutions(plan, executions),
                    report,
                    totalRevisions,
                    resumedTaskCount);
        }

        saveCheckpoint(runId, goal.rawGoal(), executions, totalRevisions);
        executeOperationalTasks(
                runId, totalRevisions, plan, goal, executions, outputs, attempts);
        CampusEvaluationReport report = evaluator.evaluate(
                goal, orderedExecutions(plan, executions));
        int revisionsThisRun = 0;
        while (!report.passed() && revisionsThisRun < MAX_REVISIONS) {
            Set<String> retryTaskIds = retryableTaskIds(report);
            if (retryTaskIds.isEmpty()) {
                break;
            }
            revisionsThisRun++;
            totalRevisions++;
            invalidateTasks(plan, retryTaskIds, executions, outputs);
            executeOperationalTasks(
                    runId, totalRevisions, plan, goal, executions, outputs, attempts);
            report = evaluator.evaluate(goal, orderedExecutions(plan, executions));
        }

        recordEvaluation(report, executions, outputs, totalRevisions + 1);
        CampusAgentRunStatus status;
        if (report.passed()) {
            AgentTask assemblyTask = task(plan, ASSEMBLY_TASK);
            executeTask(assemblyTask, goal, executions, outputs, attempts);
            CampusTaskExecution assembly = executions.get(ASSEMBLY_TASK);
            if (assembly.status() == CampusTaskStatus.SUCCEEDED) {
                status = CampusAgentRunStatus.COMPLETED;
                checkpointStore.delete(runId);
            } else {
                report = appendAssemblyFailure(report);
                recordEvaluation(report, executions, outputs, totalRevisions + 1);
                status = CampusAgentRunStatus.FAILED;
                saveCheckpoint(runId, goal.rawGoal(), executions, totalRevisions);
            }
        } else {
            executions.put(ASSEMBLY_TASK, blocked(
                    ASSEMBLY_TASK, "Evaluator未通过，不能汇总最终成品"));
            status = CampusAgentRunStatus.FAILED;
            saveCheckpoint(runId, goal.rawGoal(), executions, totalRevisions);
        }

        return new CampusAgentRun(
                status,
                runId,
                goal,
                plan,
                orderedExecutions(plan, executions),
                report,
                totalRevisions,
                resumedTaskCount);
    }

    private void executeOperationalTasks(
            String runId,
            int revisionCount,
            CampusAgentPlan plan,
            CampusEventGoal goal,
            Map<String, CampusTaskExecution> executions,
            Map<String, JsonNode> outputs,
            Map<String, Integer> attempts) {
        Set<String> remaining = new LinkedHashSet<>();
        for (AgentTask task : plan.tasks()) {
            if (!EVALUATOR_TASK.equals(task.id()) && !ASSEMBLY_TASK.equals(task.id())
                    && !isSuccessful(executions.get(task.id()))) {
                remaining.add(task.id());
            }
        }

        while (!remaining.isEmpty()) {
            blockTasksWithFailedDependencies(plan, remaining, executions, outputs);
            if (remaining.isEmpty()) {
                return;
            }

            List<AgentTask> readyLayer = plan.tasks().stream()
                    .filter(task -> remaining.contains(task.id()))
                    .filter(task -> task.dependsOn().stream()
                            .allMatch(dependency -> isSuccessful(executions.get(dependency))))
                    .toList();
            if (readyLayer.isEmpty()) {
                for (String taskId : List.copyOf(remaining)) {
                    executions.put(taskId, blocked(taskId, "任务依赖图无法继续推进"));
                    outputs.remove(taskId);
                }
                return;
            }

            long layerStartedAt = System.nanoTime();
            log.info("Campus Agent并行层开始 runId={} tasks={}", runId,
                    readyLayer.stream().map(AgentTask::id).toList());
            executeTaskLayer(readyLayer, goal, executions, outputs, attempts);
            readyLayer.forEach(task -> remaining.remove(task.id()));
            saveCheckpoint(runId, goal.rawGoal(), executions, revisionCount);
            long elapsedMilliseconds = (System.nanoTime() - layerStartedAt) / 1_000_000;
            log.info("Campus Agent并行层完成 runId={} durationMs={}",
                    runId, elapsedMilliseconds);
        }
    }

    private void blockTasksWithFailedDependencies(
            CampusAgentPlan plan,
            Set<String> remaining,
            Map<String, CampusTaskExecution> executions,
            Map<String, JsonNode> outputs) {
        boolean changed;
        do {
            changed = false;
            for (AgentTask task : plan.tasks()) {
                if (!remaining.contains(task.id())) {
                    continue;
                }
                List<String> failedDependencies = task.dependsOn().stream()
                        .filter(dependency -> executions.containsKey(dependency)
                                && !isSuccessful(executions.get(dependency)))
                        .toList();
                if (!failedDependencies.isEmpty()) {
                    executions.put(task.id(), blocked(task.id(),
                            "依赖任务未成功：" + String.join(",", failedDependencies)));
                    outputs.remove(task.id());
                    remaining.remove(task.id());
                    changed = true;
                }
            }
        } while (changed);
    }

    private void executeTaskLayer(
            List<AgentTask> tasks,
            CampusEventGoal goal,
            Map<String, CampusTaskExecution> executions,
            Map<String, JsonNode> outputs,
            Map<String, Integer> attempts) {
        Map<String, JsonNode> outputSnapshot = Map.copyOf(outputs);
        List<ScheduledTask> scheduled = new ArrayList<>();
        for (AgentTask task : tasks) {
            int currentAttempt = attempts.merge(task.id(), 1, Integer::sum);
            Future<TaskResult> future = taskExecutor.submit(
                    () -> runTask(task, goal, outputSnapshot, currentAttempt));
            scheduled.add(new ScheduledTask(task, currentAttempt, future));
        }

        for (ScheduledTask scheduledTask : scheduled) {
            TaskResult result;
            try {
                result = scheduledTask.future().get();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                result = failedTaskResult(
                        scheduledTask.task(), scheduledTask.attempt(),
                        "Agent任务层执行被中断");
            } catch (ExecutionException exception) {
                result = failedTaskResult(
                        scheduledTask.task(), scheduledTask.attempt(),
                        "Agent并行任务执行失败");
            }
            mergeTaskResult(result, executions, outputs);
        }
    }

    private void executeTask(
            AgentTask task,
            CampusEventGoal goal,
            Map<String, CampusTaskExecution> executions,
            Map<String, JsonNode> outputs,
            Map<String, Integer> attempts) {
        int currentAttempt = attempts.merge(task.id(), 1, Integer::sum);
        TaskResult result = runTask(task, goal, Map.copyOf(outputs), currentAttempt);
        mergeTaskResult(result, executions, outputs);
    }

    private TaskResult runTask(
            AgentTask task,
            CampusEventGoal goal,
            Map<String, JsonNode> outputs,
            int currentAttempt) {
        try {
            JsonNode output = taskRunner.execute(
                    task, new CampusExecutionContext(goal, outputs));
            if (output == null || output.isMissingNode()) {
                throw new IllegalStateException("任务没有返回有效输出");
            }
            return new TaskResult(new CampusTaskExecution(
                    task.id(), CampusTaskStatus.SUCCEEDED, currentAttempt, output, null), output);
        } catch (RuntimeException exception) {
            String error = exception instanceof IllegalArgumentException
                    ? exception.getMessage()
                    : "任务执行失败，未使用未经验证的结果";
            return failedTaskResult(task, currentAttempt, error);
        }
    }

    private TaskResult failedTaskResult(AgentTask task, int attempt, String error) {
        return new TaskResult(new CampusTaskExecution(
                task.id(), CampusTaskStatus.FAILED, attempt, null, error), null);
    }

    private void mergeTaskResult(
            TaskResult result,
            Map<String, CampusTaskExecution> executions,
            Map<String, JsonNode> outputs) {
        CampusTaskExecution execution = result.execution();
        executions.put(execution.taskId(), execution);
        if (result.output() == null) {
            outputs.remove(execution.taskId());
        } else {
            outputs.put(execution.taskId(), result.output());
        }
    }

    private void recordEvaluation(
            CampusEvaluationReport report,
            Map<String, CampusTaskExecution> executions,
            Map<String, JsonNode> outputs,
            int attempts) {
        JsonNode output = evaluationOutput(report);
        outputs.put(EVALUATOR_TASK, output);
        executions.put(EVALUATOR_TASK, new CampusTaskExecution(
                EVALUATOR_TASK,
                CampusTaskStatus.SUCCEEDED,
                attempts,
                output,
                null));
    }

    private JsonNode evaluationOutput(CampusEvaluationReport report) {
        ObjectNode output = objectMapper.createObjectNode();
        output.put("passed", report.passed());
        ArrayNode issues = output.putArray("issues");
        for (CampusEvaluationIssue issue : report.issues()) {
            ObjectNode value = issues.addObject();
            value.put("code", issue.code());
            value.put("severity", issue.severity().name());
            value.put("task_id", issue.taskId());
            value.put("message", issue.message());
            value.put("retryable", issue.retryable());
        }
        return output;
    }

    private Set<String> retryableTaskIds(CampusEvaluationReport report) {
        Set<String> taskIds = new LinkedHashSet<>();
        for (CampusEvaluationIssue issue : report.issues()) {
            if (issue.severity() == CampusIssueSeverity.ERROR && issue.retryable()) {
                taskIds.add(issue.taskId());
            }
        }
        return taskIds;
    }

    private void invalidateTasks(
            CampusAgentPlan plan,
            Set<String> initiallyInvalid,
            Map<String, CampusTaskExecution> executions,
            Map<String, JsonNode> outputs) {
        Set<String> invalidated = new LinkedHashSet<>(initiallyInvalid);
        boolean changed;
        do {
            changed = false;
            for (AgentTask task : plan.tasks()) {
                if (!EVALUATOR_TASK.equals(task.id()) && !ASSEMBLY_TASK.equals(task.id())
                        && task.dependsOn().stream().anyMatch(invalidated::contains)
                        && invalidated.add(task.id())) {
                    changed = true;
                }
            }
        } while (changed);
        for (String taskId : invalidated) {
            executions.remove(taskId);
            outputs.remove(taskId);
        }
        executions.remove(EVALUATOR_TASK);
        outputs.remove(EVALUATOR_TASK);
        executions.remove(ASSEMBLY_TASK);
        outputs.remove(ASSEMBLY_TASK);
    }

    private void blockRemainingTasks(
            CampusAgentPlan plan,
            Map<String, CampusTaskExecution> executions,
            String reason) {
        for (AgentTask task : plan.tasks()) {
            if (!executions.containsKey(task.id()) && !EVALUATOR_TASK.equals(task.id())) {
                executions.put(task.id(), blocked(task.id(), reason));
            }
        }
    }

    private List<CampusTaskExecution> orderedExecutions(
            CampusAgentPlan plan,
            Map<String, CampusTaskExecution> executions) {
        List<CampusTaskExecution> ordered = new ArrayList<>();
        for (AgentTask task : plan.tasks()) {
            CampusTaskExecution execution = executions.get(task.id());
            if (execution != null) {
                ordered.add(execution);
            }
        }
        return List.copyOf(ordered);
    }

    private CampusEvaluationReport appendAssemblyFailure(CampusEvaluationReport report) {
        List<CampusEvaluationIssue> issues = new ArrayList<>(report.issues());
        issues.add(new CampusEvaluationIssue(
                "ASSEMBLY_FAILED",
                CampusIssueSeverity.ERROR,
                ASSEMBLY_TASK,
                "结构化策划书汇总失败",
                false));
        return new CampusEvaluationReport(issues);
    }

    private int restoreCheckpoint(
            Optional<CampusAgentCheckpoint> checkpoint,
            CampusEventGoal goal,
            CampusAgentPlan plan,
            Map<String, CampusTaskExecution> executions,
            Map<String, JsonNode> outputs,
            Map<String, Integer> attempts) {
        if (checkpoint.isEmpty()
                || checkpoint.get().version() != CHECKPOINT_VERSION
                || !normalizeGoal(checkpoint.get().rawGoal())
                        .equals(normalizeGoal(goal.rawGoal()))) {
            return 0;
        }

        Set<String> validTaskIds = new LinkedHashSet<>();
        plan.tasks().forEach(task -> validTaskIds.add(task.id()));
        int restored = 0;
        for (CampusTaskExecution execution : checkpoint.get().taskExecutions()) {
            if (!validTaskIds.contains(execution.taskId())) {
                continue;
            }
            attempts.merge(execution.taskId(), execution.attempts(), Math::max);
            if (execution.status() == CampusTaskStatus.SUCCEEDED
                    && execution.output() != null
                    && !EVALUATOR_TASK.equals(execution.taskId())
                    && !ASSEMBLY_TASK.equals(execution.taskId())) {
                executions.put(execution.taskId(), execution);
                outputs.put(execution.taskId(), execution.output());
                restored++;
            }
        }
        return restored;
    }

    private void saveCheckpoint(
            String runId,
            String rawGoal,
            Map<String, CampusTaskExecution> executions,
            int revisionCount) {
        checkpointStore.save(new CampusAgentCheckpoint(
                CHECKPOINT_VERSION,
                runId,
                rawGoal,
                new ArrayList<>(executions.values()),
                revisionCount));
    }

    private String runIdFor(String finalGoal) {
        String normalized = normalizeGoal(finalGoal);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("最终目标不能为空");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成Agent任务编号", exception);
        }
    }

    private String normalizeGoal(String goal) {
        return goal == null ? "" : goal.trim().replaceAll("\\s+", "");
    }

    private AgentTask task(CampusAgentPlan plan, String taskId) {
        return plan.tasks().stream()
                .filter(task -> task.id().equals(taskId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("计划缺少任务：" + taskId));
    }

    private CampusTaskExecution blocked(String taskId, String reason) {
        return new CampusTaskExecution(
                taskId, CampusTaskStatus.BLOCKED, 0, null, reason);
    }

    private boolean isSuccessful(CampusTaskExecution execution) {
        return execution != null && execution.status() == CampusTaskStatus.SUCCEEDED;
    }

    private record ScheduledTask(
            AgentTask task,
            int attempt,
            Future<TaskResult> future) {
    }

    private record TaskResult(CampusTaskExecution execution, JsonNode output) {
    }
}
