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
    private static final BigDecimal RESERVE_RATIO = new BigDecimal("0.15");
    private static final List<BudgetCap> OPERATIONAL_CAPS = List.of(
            new BudgetCap("场地与设备", "场地、投影、扩音及网络费用上限", "0.50"),
            new BudgetCap("嘉宾与交通", "嘉宾市内交通或必要服务费用上限", "0.25"),
            new BudgetCap("宣传与报名", "海报、二维码物料及报名服务费用上限", "0.15"),
            new BudgetCap("现场保障", "清洁、无障碍和临时补给费用上限", "0.10"));
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String name() {
        return "allocate_event_budget";
    }

    @Override
    public String description() {
        return "根据物资Tool的具体数量清单分配活动预算，逐项输出数量、单位、单价控制上限、小计、"
                + "价格口径和核验要求。所有金额是预算上限，不代表真实报价。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "total_budget", Map.of(
                                "type", "number", "exclusiveMinimum", 0,
                                "description", "活动总预算，人民币元，最多两位小数"),
                        "participant_count", Map.of(
                                "type", "integer", "minimum", 1,
                                "description", "预计参与人数"),
                        "supply_items", Map.of(
                                "type", "array", "minItems", 1,
                                "description", "estimate_event_supplies返回的items数组")),
                "required", List.of("total_budget", "participant_count", "supply_items"),
                "additionalProperties", false);
    }

    @Override
    public String execute(JsonNode arguments) {
        BigDecimal totalBudget = requireBudget(arguments);
        int participants = requireParticipantCount(arguments);
        List<Map<String, Object>> items = copyAndValidateSupplyItems(
                arguments.path("supply_items"), totalBudget);
        BigDecimal supplyTotal = total(items);
        BigDecimal availableAfterSupplies = totalBudget.subtract(supplyTotal);
        if (availableAfterSupplies.signum() < 0) {
            throw new IllegalArgumentException(
                    "总预算低于基本物资控制上限，至少需要" + supplyTotal.toPlainString() + "元");
        }

        BigDecimal desiredReserve = totalBudget.multiply(RESERVE_RATIO)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal reserve = desiredReserve.min(
                availableAfterSupplies.multiply(new BigDecimal("0.25"))
                        .setScale(2, RoundingMode.HALF_UP));
        BigDecimal operationalTotal = availableAfterSupplies.subtract(reserve);
        addOperationalCaps(items, operationalTotal, totalBudget);
        addBudgetItem(items, "应急备用金", "不可预见支出备用金", BigDecimal.ONE, "项",
                reserve, "RESERVE", "按总预算15%和剩余可用额度取较小值", false,
                totalBudget);

        BigDecimal allocatedTotal = total(items);
        if (allocatedTotal.compareTo(totalBudget) != 0) {
            throw new IllegalStateException("预算分项合计与总预算不一致");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("currency", "CNY");
        result.put("pricing_status", "PLANNING_CAP_NOT_QUOTE");
        result.put("quote_obtained", false);
        result.put("total_budget", totalBudget);
        result.put("participant_count", participants);
        result.put("per_capita_budget", totalBudget.divide(
                BigDecimal.valueOf(participants), 2, RoundingMode.HALF_UP));
        result.put("supply_cap_total", supplyTotal);
        result.put("items", List.copyOf(items));
        result.put("category_summaries", summarizeCategories(items));
        result.put("allocated_total", allocatedTotal);
        result.put("unallocated", totalBudget.subtract(allocatedTotal));
        result.put("basis", "具体物资使用内部预算控制上限；其余项目只是可支出的最高额度，均未取得商家报价");
        result.put("verification_steps", List.of(
                "场地和设备至少取得一个可核验报价；条件允许时比较三家",
                "逐项用真实报价替换unit_price_cap，保留报价日期和供应方",
                "确认学校可报销科目、票据类型和审批人",
                "任何项目超出控制上限时先调整方案，不得隐藏超支"));
        return toJson(result);
    }

    private List<Map<String, Object>> copyAndValidateSupplyItems(
            JsonNode supplyItems, BigDecimal totalBudget) {
        if (!supplyItems.isArray() || supplyItems.isEmpty()) {
            throw new IllegalArgumentException(
                    "预算工具需要先调用estimate_event_supplies并传入supply_items");
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (JsonNode sourceItem : supplyItems) {
            String category = requiredText(sourceItem, "category");
            String itemName = requiredText(sourceItem, "item_name");
            String unit = requiredText(sourceItem, "unit");
            String source = requiredText(sourceItem, "source");
            BigDecimal quantity = positiveDecimal(sourceItem, "quantity");
            BigDecimal unitCap = nonNegativeDecimal(sourceItem, "planning_unit_cap");
            BigDecimal declaredSubtotal = nonNegativeDecimal(sourceItem, "planned_subtotal");
            BigDecimal calculatedSubtotal = quantity.multiply(unitCap)
                    .setScale(2, RoundingMode.HALF_UP);
            if (calculatedSubtotal.compareTo(declaredSubtotal) != 0) {
                throw new IllegalArgumentException("物资小计与数量×单价上限不一致：" + itemName);
            }
            if (declaredSubtotal.compareTo(totalBudget) > 0) {
                throw new IllegalArgumentException("单项物资控制上限超过总预算：" + itemName);
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("category", category);
            item.put("item_name", itemName);
            item.put("quantity", quantity);
            item.put("unit", unit);
            item.put("unit_price_cap", unitCap);
            item.put("amount", declaredSubtotal);
            item.put("pricing_type", "INTERNAL_PLANNING_CAP");
            item.put("source", source);
            item.put("requires_verification", true);
            item.put("rationale", requiredText(sourceItem, "rationale"));
            item.put("ratio", ratio(declaredSubtotal, totalBudget));
            items.add(item);
        }
        return items;
    }

    private void addOperationalCaps(
            List<Map<String, Object>> items,
            BigDecimal operationalTotal,
            BigDecimal totalBudget) {
        BigDecimal allocated = BigDecimal.ZERO.setScale(2);
        for (int index = 0; index < OPERATIONAL_CAPS.size(); index++) {
            BudgetCap cap = OPERATIONAL_CAPS.get(index);
            BigDecimal amount = index == OPERATIONAL_CAPS.size() - 1
                    ? operationalTotal.subtract(allocated)
                    : operationalTotal.multiply(new BigDecimal(cap.weight()))
                            .setScale(2, RoundingMode.HALF_UP);
            allocated = allocated.add(amount);
            addBudgetItem(items, cap.category(), cap.itemName(), BigDecimal.ONE, "项",
                    amount, "BUDGET_CAP_NOT_QUOTE",
                    "由物资和备用金扣除后的余额按规划权重分配，不是市场价格", true,
                    totalBudget);
        }
    }

    private void addBudgetItem(
            List<Map<String, Object>> items,
            String category,
            String itemName,
            BigDecimal quantity,
            String unit,
            BigDecimal unitPriceCap,
            String pricingType,
            String source,
            boolean requiresVerification,
            BigDecimal totalBudget) {
        Map<String, Object> item = new LinkedHashMap<>();
        BigDecimal amount = quantity.multiply(unitPriceCap).setScale(2, RoundingMode.HALF_UP);
        item.put("category", category);
        item.put("item_name", itemName);
        item.put("quantity", quantity);
        item.put("unit", unit);
        item.put("unit_price_cap", unitPriceCap);
        item.put("amount", amount);
        item.put("pricing_type", pricingType);
        item.put("source", source);
        item.put("requires_verification", requiresVerification);
        item.put("rationale", source);
        item.put("ratio", ratio(amount, totalBudget));
        items.add(item);
    }

    private List<Map<String, Object>> summarizeCategories(List<Map<String, Object>> items) {
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        for (Map<String, Object> item : items) {
            totals.merge((String) item.get("category"),
                    (BigDecimal) item.get("amount"), BigDecimal::add);
        }
        return totals.entrySet().stream()
                .map(entry -> Map.<String, Object>of(
                        "category", entry.getKey(), "amount", entry.getValue()))
                .toList();
    }

    private BigDecimal total(List<Map<String, Object>> items) {
        return items.stream()
                .map(item -> (BigDecimal) item.get("amount"))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal ratio(BigDecimal amount, BigDecimal totalBudget) {
        return amount.divide(totalBudget, 4, RoundingMode.HALF_UP);
    }

    private BigDecimal requireBudget(JsonNode arguments) {
        JsonNode budgetNode = arguments.get("total_budget");
        if (budgetNode == null || !budgetNode.isNumber()) {
            throw new IllegalArgumentException("预算工具缺少数字参数：total_budget");
        }
        BigDecimal budget = budgetNode.decimalValue();
        if (budget.signum() <= 0 || budget.compareTo(MAX_BUDGET) > 0) {
            throw new IllegalArgumentException("total_budget 必须大于0且不超过支持范围");
        }
        if (budget.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("total_budget 最多保留两位小数");
        }
        return budget.setScale(2, RoundingMode.UNNECESSARY);
    }

    private int requireParticipantCount(JsonNode arguments) {
        JsonNode node = arguments.get("participant_count");
        if (node == null || !node.isIntegralNumber()) {
            throw new IllegalArgumentException("预算工具缺少整数参数：participant_count");
        }
        int value = node.intValue();
        if (value <= 0 || value > MAX_PARTICIPANTS) {
            throw new IllegalArgumentException("participant_count 必须在1到100000之间");
        }
        return value;
    }

    private String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText().trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("物资条目缺少字段：" + field);
        }
        return value;
    }

    private BigDecimal positiveDecimal(JsonNode node, String field) {
        BigDecimal value = nonNegativeDecimal(node, field);
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(field + " 必须大于0");
        }
        return value;
    }

    private BigDecimal nonNegativeDecimal(JsonNode node, String field) {
        JsonNode valueNode = node.get(field);
        if (valueNode == null || !valueNode.isNumber()) {
            throw new IllegalArgumentException("物资条目缺少数字字段：" + field);
        }
        BigDecimal value = valueNode.decimalValue();
        if (value.signum() < 0) {
            throw new IllegalArgumentException(field + " 不能小于0");
        }
        return value;
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成预算工具结果", exception);
        }
    }

    private record BudgetCap(String category, String itemName, String weight) {
    }
}
