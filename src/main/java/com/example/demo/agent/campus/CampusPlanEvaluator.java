package com.example.demo.agent.campus;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class CampusPlanEvaluator {
    private static final Set<String> OPERATIONAL_TASKS = Set.of(
            "resolve_constraints",
            "retrieve_campus_rules",
            "research_weather",
            "match_venue",
            "design_agenda",
            "plan_staffing",
            "estimate_supplies",
            "allocate_budget",
            "generate_materials",
            "assess_risks");
    private static final Set<String> WEATHER_STATUSES = Set.of(
            "TOO_EARLY", "RECHECK_REQUIRED", "QUERY_FAILED", "FORECAST_AVAILABLE");

    public CampusEvaluationReport evaluateGoal(CampusEventGoal goal) {
        List<CampusEvaluationIssue> issues = new ArrayList<>();
        for (String field : goal.missingFields()) {
            issues.add(error(
                    "MISSING_CONSTRAINT",
                    "resolve_constraints",
                    "缺少必要信息：" + field,
                    false));
        }
        for (String validationIssue : goal.validationIssues()) {
            issues.add(error(
                    "INVALID_CONSTRAINT",
                    "resolve_constraints",
                    validationIssue,
                    false));
        }
        return new CampusEvaluationReport(issues);
    }

    public CampusEvaluationReport evaluate(
            CampusEventGoal goal,
            List<CampusTaskExecution> executions) {
        List<CampusEvaluationIssue> issues = new ArrayList<>(evaluateGoal(goal).issues());
        Map<String, CampusTaskExecution> byTaskId = new LinkedHashMap<>();
        for (CampusTaskExecution execution : executions) {
            byTaskId.put(execution.taskId(), execution);
        }

        for (String taskId : OPERATIONAL_TASKS) {
            CampusTaskExecution execution = byTaskId.get(taskId);
            if (execution == null || execution.status() != CampusTaskStatus.SUCCEEDED
                    || execution.output() == null) {
                boolean retryable = execution != null
                        && execution.status() == CampusTaskStatus.FAILED;
                issues.add(error(
                        "TASK_NOT_SUCCESSFUL",
                        taskId,
                        "任务未成功完成：" + taskId,
                        retryable));
            }
        }
        if (hasOperationalErrors(issues)) {
            return new CampusEvaluationReport(issues);
        }

        checkRules(byTaskId.get("retrieve_campus_rules").output(), issues);
        checkWeather(goal, byTaskId.get("research_weather").output(), issues);
        checkVenue(goal, byTaskId.get("match_venue").output(), issues);
        checkNonEmptyArray(byTaskId, "design_agenda", "items", "AGENDA_EMPTY", issues);
        checkNonEmptyArray(byTaskId, "plan_staffing", "roles", "STAFFING_EMPTY", issues);
        checkSupplies(byTaskId.get("estimate_supplies").output(), issues);
        checkBudget(goal, byTaskId.get("allocate_budget").output(), issues);
        checkMaterials(byTaskId.get("generate_materials").output(), issues);
        checkNonEmptyArray(byTaskId, "assess_risks", "items", "RISKS_EMPTY", issues);
        return new CampusEvaluationReport(issues);
    }

    private void checkRules(JsonNode output, List<CampusEvaluationIssue> issues) {
        JsonNode documents = output.path("documents");
        if (!documents.isArray() || documents.isEmpty()) {
            issues.add(error("RAG_EMPTY", "retrieve_campus_rules",
                    "没有检索到校园规则资料", true));
            return;
        }
        boolean containsTemplate = false;
        for (JsonNode document : documents) {
            if (document.path("source").asText().isBlank()
                    || document.path("status").asText().isBlank()) {
                issues.add(error("RAG_SOURCE_MISSING", "retrieve_campus_rules",
                        "RAG资料缺少来源或可信状态", true));
            }
            if ("TEMPLATE".equals(document.path("status").asText())) {
                containsTemplate = true;
                if (!document.path("requires_verification").asBoolean()) {
                    issues.add(error("TEMPLATE_NOT_MARKED", "retrieve_campus_rules",
                            "演示模板没有标记为待确认", true));
                }
            }
        }
        if (containsTemplate) {
            issues.add(warning("RULES_REQUIRE_VERIFICATION", "retrieve_campus_rules",
                    "当前校园规则包含演示模板，提交前必须替换为本校正式资料"));
        }
    }

    private void checkWeather(
            CampusEventGoal goal, JsonNode output, List<CampusEvaluationIssue> issues) {
        String status = output.path("status").asText();
        boolean forecastAvailable = output.path("forecast_available").asBoolean();
        if (!goal.eventDate().toString().equals(output.path("event_date").asText())) {
            issues.add(error("WEATHER_DATE_MISMATCH", "research_weather",
                    "天气评估日期与活动目标日期不一致", true));
        }
        if (!WEATHER_STATUSES.contains(status)) {
            issues.add(error("WEATHER_STATUS_INVALID", "research_weather",
                    "天气评估缺少有效状态", true));
            return;
        }
        if (!forecastAvailable && output.has("forecast")) {
            issues.add(error("WEATHER_HALLUCINATION", "research_weather",
                    "天气不可用时不应包含具体预报", true));
        }
        if (!forecastAvailable && output.path("recheck_on").asText().isBlank()) {
            issues.add(error("WEATHER_RECHECK_MISSING", "research_weather",
                    "天气不可用时必须给出复查日期", true));
        }
        if (!forecastAvailable) {
            issues.add(warning("WEATHER_PENDING", "research_weather",
                    "目标日期暂无可用天气预报，已保留复查节点和备用方案"));
        }
    }

    private void checkVenue(
            CampusEventGoal goal, JsonNode output, List<CampusEvaluationIssue> issues) {
        if (output.path("minimum_capacity").asInt() < goal.participantCount()) {
            issues.add(error("VENUE_CAPACITY_TOO_SMALL", "match_venue",
                    "建议场地容量小于参与人数", true));
        }
        if (!output.path("booking_completed").asBoolean()) {
            issues.add(warning("VENUE_REQUIRES_CONFIRMATION", "match_venue",
                    "当前只生成场地需求，尚未完成真实预约"));
        }
    }

    private void checkBudget(
            CampusEventGoal goal, JsonNode output, List<CampusEvaluationIssue> issues) {
        if (!output.path("items").isArray() || output.path("items").isEmpty()) {
            issues.add(error("BUDGET_ITEMS_EMPTY", "allocate_budget",
                    "预算分项不能为空", true));
            return;
        }
        BigDecimal expected = goal.budget();
        BigDecimal declaredTotal = output.path("total_budget").decimalValue();
        BigDecimal allocatedTotal = output.path("allocated_total").decimalValue();
        BigDecimal itemTotal = BigDecimal.ZERO;
        for (JsonNode item : output.path("items")) {
            itemTotal = itemTotal.add(item.path("amount").decimalValue());
            if (item.path("item_name").asText().isBlank()
                    || !item.path("quantity").isNumber()
                    || item.path("unit").asText().isBlank()
                    || !item.path("unit_price_cap").isNumber()
                    || item.path("pricing_type").asText().isBlank()
                    || item.path("source").asText().isBlank()
                    || !item.has("requires_verification")) {
                issues.add(error("BUDGET_DETAIL_INCOMPLETE", "allocate_budget",
                        "预算条目缺少名称、数量、单位、单价上限、价格口径或来源", true));
            }
            BigDecimal calculated = item.path("quantity").decimalValue()
                    .multiply(item.path("unit_price_cap").decimalValue())
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            if (calculated.compareTo(item.path("amount").decimalValue()) != 0) {
                issues.add(error("BUDGET_LINE_MISMATCH", "allocate_budget",
                        "预算条目小计不等于数量×单价上限", true));
            }
        }
        if (expected.compareTo(declaredTotal) != 0
                || expected.compareTo(allocatedTotal) != 0
                || expected.compareTo(itemTotal) != 0) {
            issues.add(error("BUDGET_TOTAL_MISMATCH", "allocate_budget",
                    "预算总额、已分配金额和分项合计不一致", true));
        }
        if (output.path("unallocated").decimalValue().compareTo(BigDecimal.ZERO) != 0) {
            issues.add(error("BUDGET_UNALLOCATED", "allocate_budget",
                    "预算存在未分配金额", true));
        }
        if (!"PLANNING_CAP_NOT_QUOTE".equals(output.path("pricing_status").asText())
                || output.path("quote_obtained").asBoolean(true)) {
            issues.add(error("BUDGET_PRICE_STATUS_INVALID", "allocate_budget",
                    "预算必须明确标记为规划控制上限且尚未取得报价", true));
        }
    }

    private void checkSupplies(JsonNode output, List<CampusEvaluationIssue> issues) {
        if (!"INTERNAL_PLANNING_CAP".equals(output.path("status").asText())
                || output.path("source").asText().isBlank()
                || output.path("quote_obtained").asBoolean(true)
                || !output.path("items").isArray()
                || output.path("items").isEmpty()) {
            issues.add(error("SUPPLY_ESTIMATE_INVALID", "estimate_supplies",
                    "物资清单缺少控制上限状态、来源、报价状态或具体条目", true));
        }
    }

    private void checkMaterials(JsonNode output, List<CampusEvaluationIssue> issues) {
        if (output.path("announcement").asText().isBlank()
                || !output.path("registration_fields").isArray()
                || output.path("registration_fields").isEmpty()
                || output.path("privacy_notice").asText().isBlank()) {
            issues.add(error("MATERIALS_INCOMPLETE", "generate_materials",
                    "宣传文案、报名字段或隐私说明不完整", true));
        }
    }

    private void checkNonEmptyArray(
            Map<String, CampusTaskExecution> executions,
            String taskId,
            String field,
            String code,
            List<CampusEvaluationIssue> issues) {
        JsonNode value = executions.get(taskId).output().path(field);
        if (!value.isArray() || value.isEmpty()) {
            issues.add(error(code, taskId, "任务输出缺少有效的" + field, true));
        }
    }

    private boolean hasOperationalErrors(List<CampusEvaluationIssue> issues) {
        return issues.stream().anyMatch(issue -> issue.severity() == CampusIssueSeverity.ERROR
                && "TASK_NOT_SUCCESSFUL".equals(issue.code()));
    }

    private CampusEvaluationIssue error(
            String code, String taskId, String message, boolean retryable) {
        return new CampusEvaluationIssue(
                code, CampusIssueSeverity.ERROR, taskId, message, retryable);
    }

    private CampusEvaluationIssue warning(String code, String taskId, String message) {
        return new CampusEvaluationIssue(
                code, CampusIssueSeverity.WARNING, taskId, message, false);
    }
}
