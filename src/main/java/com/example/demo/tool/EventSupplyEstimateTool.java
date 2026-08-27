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
public class EventSupplyEstimateTool implements BotTool {
    private static final int MAX_PARTICIPANTS = 100_000;
    private static final String PRICE_SOURCE =
            "CampusPilot教学用内部预算控制上限v1；不是商家报价，采购前必须询价核实";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String name() {
        return "estimate_event_supplies";
    }

    @Override
    public String description() {
        return "根据活动人数生成带10%数量缓冲的具体物资清单，包含数量、单位、内部单价控制上限和小计。"
                + "控制上限只用于预算规划，不冒充真实市场价格或商家报价。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "participant_count", Map.of(
                                "type", "integer", "minimum", 1,
                                "description", "预计参与人数"),
                        "hot_weather", Map.of(
                                "type", "boolean",
                                "description", "预报最高温达到30℃时传true，每人按两瓶水规划")),
                "required", List.of("participant_count"),
                "additionalProperties", false);
    }

    @Override
    public String execute(JsonNode arguments) {
        int participants = requireParticipantCount(arguments);
        boolean hotWeather = arguments.path("hot_weather").asBoolean(false);
        int bufferedAttendance = (participants * 11 + 9) / 10;
        List<Map<String, Object>> items = new ArrayList<>();
        addItem(items, "饮水", "瓶装水", bufferedAttendance * (hotWeather ? 2 : 1), "瓶",
                "2.00", hotWeather ? "高温场景每人2瓶并含10%余量" : "每人1瓶并含10%余量");
        addItem(items, "签到物料", "空白姓名贴或胸牌", bufferedAttendance, "个",
                "0.50", "参与者每人1个并含10%余量");
        addItem(items, "流程物料", "纸质流程单备用份", Math.max(10, (participants + 9) / 10), "份",
                "0.50", "主流程使用二维码，纸质版仅供工作人员和临时备用");
        addItem(items, "导视物料", "可复用方向指示牌", Math.max(2, (participants + 24) / 25), "块",
                "15.00", "入口、签到处及主要转弯点布置");
        addItem(items, "现场耗材", "胶带、记号笔和扎带组合包", Math.max(1, (participants + 49) / 50), "套",
                "60.00", "按每50人1套规划，优先复用库存");

        BigDecimal totalCap = items.stream()
                .map(item -> (BigDecimal) item.get("planned_subtotal"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("status", "INTERNAL_PLANNING_CAP");
        result.put("participant_count", participants);
        result.put("buffered_attendance", bufferedAttendance);
        result.put("quantity_buffer_percent", 10);
        result.put("hot_weather", hotWeather);
        result.put("items", List.copyOf(items));
        result.put("planned_supply_cap_total", totalCap);
        result.put("source", PRICE_SOURCE);
        result.put("quote_obtained", false);
        result.put("zero_cost_actions", List.of(
                "提前借用电脑、投影转接头、翻页笔、插线板和麦克风并逐项测试",
                "报名、签到和反馈默认使用二维码，减少打印",
                "先盘点社团或学院库存，再决定实际采购数量"));
        result.put("notice", "数量是可执行的备货建议；单价是内部控制上限，必须以真实询价替换。");
        return toJson(result);
    }

    private void addItem(
            List<Map<String, Object>> items,
            String category,
            String itemName,
            int quantity,
            String unit,
            String unitPriceCap,
            String rationale) {
        BigDecimal price = new BigDecimal(unitPriceCap).setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal subtotal = price.multiply(BigDecimal.valueOf(quantity)).setScale(2);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("category", category);
        item.put("item_name", itemName);
        item.put("quantity", quantity);
        item.put("unit", unit);
        item.put("planning_unit_cap", price);
        item.put("planned_subtotal", subtotal);
        item.put("pricing_type", "INTERNAL_PLANNING_CAP");
        item.put("source", PRICE_SOURCE);
        item.put("requires_verification", true);
        item.put("rationale", rationale);
        items.add(item);
    }

    private int requireParticipantCount(JsonNode arguments) {
        JsonNode countNode = arguments.get("participant_count");
        if (countNode == null || !countNode.isIntegralNumber()) {
            throw new IllegalArgumentException("物资工具缺少整数参数：participant_count");
        }
        int participants = countNode.intValue();
        if (participants <= 0 || participants > MAX_PARTICIPANTS) {
            throw new IllegalArgumentException("participant_count 必须在1到100000之间");
        }
        return participants;
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成活动物资清单", exception);
        }
    }
}
