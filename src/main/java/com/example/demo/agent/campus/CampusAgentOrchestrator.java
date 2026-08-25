package com.example.demo.agent.campus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class CampusAgentOrchestrator {
    private static final int MAX_REVISIONS = 2;
    private static final String EVALUATOR_TASK = "evaluate_completeness";
    private static final String ASSEMBLY_TASK = "assemble_proposal";

    private final CampusGoalParser goalParser;
    private final CampusTaskPlanner taskPlanner;
    private final CampusTaskRunner taskRunner;
    private final CampusPlanEvaluator evaluator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CampusAgentOrchestrator(
            CampusGoalParser goalParser,
            CampusTaskPlanner taskPlanner,
            CampusTaskRunner taskRunner,
            CampusPlanEvaluator evaluator) {
        this.goalParser = goalParser;
        this.taskPlanner = taskPlanner;
        this.taskRunner = taskRunner;
        this.evaluator = evaluator;
    }

    public CampusAgentRun run(String finalGoal) {
        CampusEventGoal goal = goalParser.parse(finalGoal);
        CampusAgentPlan plan = taskPlanner.createPlan(goal);
        Map<String, CampusTaskExecution> executions = new LinkedHashMap<>();
        Map<String, JsonNode> outputs = new LinkedHashMap<>();
        Map<String, Integer> attempts = new HashMap<>();

        AgentTask constraintsTask = task(plan, "resolve_constraints");
        executeTask(constraintsTask, goal, executions, outputs, attempts);
        if (!goal.isReadyForExecution()) {
            CampusEvaluationReport report = evaluator.evaluateGoal(goal);
            blockRemainingTasks(plan, executions, "等待用户补充或修正活动约束");
            recordEvaluation(report, executions, outputs, 1);
            return new CampusAgentRun(
                    CampusAgentRunStatus.NEEDS_INPUT,
                    goal,
                    plan,
                    orderedExecutions(plan, executions),
                    report,
                    0);
        }

        executeOperationalTasks(plan, goal, executions, outputs, attempts);
        CampusEvaluationReport report = evaluator.evaluate(
                goal, orderedExecutions(plan, executions));
        int revisions = 0;
        while (!report.passed() && revisions < MAX_REVISIONS) {
            Set<String> retryTaskIds = retryableTaskIds(report);
            if (retryTaskIds.isEmpty()) {
                break;
            }
            revisions++;
            invalidateTasks(plan, retryTaskIds, executions, outputs);
            executeOperationalTasks(plan, goal, executions, outputs, attempts);
            report = evaluator.evaluate(goal, orderedExecutions(plan, executions));
        }

        recordEvaluation(report, executions, outputs, revisions + 1);
        CampusAgentRunStatus status;
        if (report.passed()) {
            AgentTask assemblyTask = task(plan, ASSEMBLY_TASK);
            executeTask(assemblyTask, goal, executions, outputs, attempts);
            CampusTaskExecution assembly = executions.get(ASSEMBLY_TASK);
            if (assembly.status() == CampusTaskStatus.SUCCEEDED) {
                status = CampusAgentRunStatus.COMPLETED;
            } else {
                report = appendAssemblyFailure(report);
                recordEvaluation(report, executions, outputs, revisions + 1);
                status = CampusAgentRunStatus.FAILED;
            }
        } else {
            executions.put(ASSEMBLY_TASK, blocked(
                    ASSEMBLY_TASK, "Evaluator未通过，不能汇总最终成品"));
            status = CampusAgentRunStatus.FAILED;
        }

        return new CampusAgentRun(
                status,
                goal,
                plan,
                orderedExecutions(plan, executions),
                report,
                revisions);
    }

    private void executeOperationalTasks(
            CampusAgentPlan plan,
            CampusEventGoal goal,
            Map<String, CampusTaskExecution> executions,
            Map<String, JsonNode> outputs,
            Map<String, Integer> attempts) {
        for (AgentTask task : plan.tasks()) {
            if (EVALUATOR_TASK.equals(task.id()) || ASSEMBLY_TASK.equals(task.id())) {
                continue;
            }
            CampusTaskExecution existing = executions.get(task.id());
            if (existing != null && existing.status() == CampusTaskStatus.SUCCEEDED) {
                continue;
            }
            List<String> unavailableDependencies = task.dependsOn().stream()
                    .filter(dependency -> !isSuccessful(executions.get(dependency)))
                    .toList();
            if (!unavailableDependencies.isEmpty()) {
                executions.put(task.id(), blocked(
                        task.id(), "依赖任务未成功：" + String.join(",", unavailableDependencies)));
                continue;
            }
            executeTask(task, goal, executions, outputs, attempts);
        }
    }

    private void executeTask(
            AgentTask task,
            CampusEventGoal goal,
            Map<String, CampusTaskExecution> executions,
            Map<String, JsonNode> outputs,
            Map<String, Integer> attempts) {
        int currentAttempt = attempts.merge(task.id(), 1, Integer::sum);
        try {
            JsonNode output = taskRunner.execute(
                    task, new CampusExecutionContext(goal, outputs));
            if (output == null || output.isMissingNode()) {
                throw new IllegalStateException("任务没有返回有效输出");
            }
            outputs.put(task.id(), output);
            executions.put(task.id(), new CampusTaskExecution(
                    task.id(), CampusTaskStatus.SUCCEEDED, currentAttempt, output, null));
        } catch (RuntimeException exception) {
            outputs.remove(task.id());
            String error = exception instanceof IllegalArgumentException
                    ? exception.getMessage()
                    : "任务执行失败，未使用未经验证的结果";
            executions.put(task.id(), new CampusTaskExecution(
                    task.id(), CampusTaskStatus.FAILED, currentAttempt, null, error));
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
}

