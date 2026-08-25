package com.example.demo.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class EventBudgetTool implements BotTool {
    private static final BigDecimal MAX_BUDGET = new BigDecimal("100000000");
    private static final int MAX_PARTICIPANTS = 100_000;
    private static final List<BudgetCategory> CATEGORIES = List.of(
            new BudgetCategory("场地与设备", new BigDecimal("0.25")),
            new BudgetCategory("活动物料", new BigDecimal("0.20")),
            new BudgetCategory("餐饮饮水", new BigDecimal("0.20")),
            new BudgetCategory("宣传与报名", new BigDecimal("0.10")),
            new BudgetCategory("嘉宾与交通", new BigDecimal("0.10")),
            new BudgetCategory("应急备用金", new BigDecimal("0.15")));

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String name() {
        return "allocate_event_budget";
    }

    @Override
    public String description() {
        return "根据活动总预算和参与人数生成确定性的建议预算分配，精确计算分项合计和人均预算。"
                + "结果是策划建议，不代表真实报价或学校报销标准。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "total_budget", Map.of(
                                "type", "number",
                                "exclusiveMinimum", 0,
                                "description", "活动总预算，单位为人民币元，最多保留两位小数"),
                        "participant_count", Map.of(
                                "type", "integer",
                                "minimum", 1,
                                "description", "预计参与人数")),
                "required", List.of("total_budget", "participant_count"),
                "additionalProperties", false);
    }

    @Override
    public String execute(JsonNode arguments) {
        BigDecimal totalBudget = requireBudget(arguments);
        int participantCount = requireParticipantCount(arguments);
        List<Map<String, Object>> items = allocate(totalBudget);
        BigDecimal allocatedTotal = items.stream()
                .map(item -> (BigDecimal) item.get("amount"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (allocatedTotal.compareTo(totalBudget) != 0) {
            throw new IllegalStateException("预算分配合计与总预算不一致");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("currency", "CNY");
        result.put("total_budget", totalBudget);
        result.put("participant_count", participantCount);
        result.put("per_capita_budget", totalBudget.divide(
                BigDecimal.valueOf(participantCount), 2, RoundingMode.HALF_UP));
        result.put("items", items);
        result.put("allocated_total", allocatedTotal);
        result.put("unallocated", totalBudget.subtract(allocatedTotal));
        result.put("basis", "CampusPilot MVP 固定比例建议，需按真实场地报价和学校财务制度调整");
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成预算工具结果", exception);
        }
    }

    private List<Map<String, Object>> allocate(BigDecimal totalBudget) {
        List<Map<String, Object>> items = new ArrayList<>();
        BigDecimal allocated = BigDecimal.ZERO.setScale(2);
        for (int index = 0; index < CATEGORIES.size(); index++) {
            BudgetCategory category = CATEGORIES.get(index);
            BigDecimal amount = index == CATEGORIES.size() - 1
                    ? totalBudget.subtract(allocated)
                    : totalBudget.multiply(category.ratio()).setScale(2, RoundingMode.HALF_UP);
            allocated = allocated.add(amount);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("category", category.name());
            item.put("ratio", category.ratio());
            item.put("amount", amount);
            items.add(item);
        }
        return List.copyOf(items);
    }

    private BigDecimal requireBudget(JsonNode arguments) {
        JsonNode budgetNode = arguments.get("total_budget");
        if (budgetNode == null || !budgetNode.isNumber()) {
            throw new IllegalArgumentException("预算工具缺少数字参数：total_budget");
        }
        BigDecimal budget = budgetNode.decimalValue();
        if (budget.signum() <= 0) {
            throw new IllegalArgumentException("total_budget 必须大于0");
        }
        if (budget.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("total_budget 最多保留两位小数");
        }
        if (budget.compareTo(MAX_BUDGET) > 0) {
            throw new IllegalArgumentException("total_budget 超出支持范围");
        }
        return budget.setScale(2, RoundingMode.UNNECESSARY);
    }

    private int requireParticipantCount(JsonNode arguments) {
        JsonNode countNode = arguments.get("participant_count");
        if (countNode == null || !countNode.isIntegralNumber()) {
            throw new IllegalArgumentException("预算工具缺少整数参数：participant_count");
        }
        int participantCount = countNode.intValue();
        if (participantCount <= 0 || participantCount > MAX_PARTICIPANTS) {
            throw new IllegalArgumentException("participant_count 必须在1到100000之间");
        }
        return participantCount;
    }

    private record BudgetCategory(String name, BigDecimal ratio) {
    }
}
