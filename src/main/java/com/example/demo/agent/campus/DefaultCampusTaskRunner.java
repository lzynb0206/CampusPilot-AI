package com.example.demo.agent.campus;

import com.example.demo.rag.KeywordRagService;
import com.example.demo.rag.KnowledgeStatus;
import com.example.demo.rag.RagHit;
import com.example.demo.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DefaultCampusTaskRunner implements CampusTaskRunner {
    private static final List<String> RULE_QUERIES = List.of(
            "校园活动 活动审批 活动申请",
            "校园活动 活动场地 消防通道 用电安全",
            "校园活动 活动预算 活动经费 经费报销",
            "校园活动 活动报名 个人信息 隐私保护");

    private final KeywordRagService ragService;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DefaultCampusTaskRunner(
            KeywordRagService ragService,
            ToolRegistry toolRegistry) {
        this.ragService = ragService;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public JsonNode execute(AgentTask task, CampusExecutionContext context) {
        return switch (task.id()) {
            case "resolve_constraints" -> resolveConstraints(context.goal());
            case "retrieve_campus_rules" -> retrieveRules(context.goal());
            case "research_weather" -> assessWeather(context.goal());
            case "match_venue" -> matchVenue(context.goal());
            case "design_agenda" -> designAgenda();
            case "plan_staffing" -> planStaffing(context.goal());
            case "allocate_budget" -> allocateBudget(context.goal());
            case "generate_materials" -> generateMaterials(context.goal());
            case "assess_risks" -> assessRisks(context);
            case "assemble_proposal" -> assembleStructuredProposal(context);
            default -> throw new IllegalArgumentException("没有任务执行器：" + task.id());
        };
    }

    private JsonNode resolveConstraints(CampusEventGoal goal) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("raw_goal", goal.rawGoal());
        putNullable(result, "event_name", goal.eventName());
        putNullable(result, "event_date", goal.eventDate() == null
                ? null : goal.eventDate().toString());
        putNullable(result, "city", goal.city());
        if (goal.participantCount() == null) {
            result.putNull("participant_count");
        } else {
            result.put("participant_count", goal.participantCount());
        }
        if (goal.budget() == null) {
            result.putNull("budget");
        } else {
            result.put("budget", goal.budget());
        }
        result.set("missing_fields", stringArray(goal.missingFields()));
        result.set("validation_issues", stringArray(goal.validationIssues()));
        result.put("ready_for_execution", goal.isReadyForExecution());
        return result;
    }

    private JsonNode retrieveRules(CampusEventGoal goal) {
        Map<String, RagHit> uniqueHits = new LinkedHashMap<>();
        for (String query : RULE_QUERIES) {
            ragService.retrieve(goal.rawGoal() + " " + query).ifPresent(context -> {
                for (RagHit hit : context.hits()) {
                    uniqueHits.putIfAbsent(hit.document().id(), hit);
                }
            });
        }

        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode documents = result.putArray("documents");
        for (RagHit hit : uniqueHits.values()) {
            ObjectNode document = documents.addObject();
            document.put("id", hit.document().id());
            document.put("title", hit.document().title());
            document.put("content", hit.document().content());
            document.put("source", hit.document().source());
            document.put("status", hit.document().status().name());
            document.put("score", hit.score());
            document.put("requires_verification",
                    hit.document().status() == KnowledgeStatus.TEMPLATE);
        }
        result.put("document_count", documents.size());
        result.put("notice", "TEMPLATE资料仅作演示参考，必须按本校正式制度确认。");
        return result;
    }

    private JsonNode assessWeather(CampusEventGoal goal) {
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("location", goal.city());
        arguments.put("event_date", goal.eventDate().toString());
        return executeJsonTool("assess_event_weather", arguments);
    }

    private JsonNode matchVenue(CampusEventGoal goal) {
        int minimumCapacity = Math.max(
                goal.participantCount(),
                (goal.participantCount() * 11 + 9) / 10);
        ObjectNode result = objectMapper.createObjectNode();
        result.put("status", "RECOMMENDATION_ONLY");
        result.put("minimum_capacity", minimumCapacity);
        result.put("booking_completed", false);
        result.set("candidate_types", stringArray(List.of(
                "校内报告厅",
                "配备投影的大教室",
                "可调整桌椅的会议室")));
        result.set("requirements", stringArray(List.of(
                "至少容纳" + minimumCapacity + "人",
                "提供投影、扩音、电源和稳定网络",
                "消防通道保持畅通",
                "确认开放时间、审批人和设备使用限制")));
        result.put("notice", "未连接真实场地预约系统，候选场地和档期需人工确认。");
        return result;
    }

    private JsonNode designAgenda() {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("duration_minutes", 120);
        result.put("time_basis", "相对活动开始时间；具体开始时间待确认");
        ArrayNode items = result.putArray("items");
        addAgendaItem(items, 0, 15, "签到与设备检查");
        addAgendaItem(items, 15, 25, "主持开场与安全提示");
        addAgendaItem(items, 25, 75, "AI主题分享");
        addAgendaItem(items, 75, 95, "问答与互动");
        addAgendaItem(items, 95, 110, "自由交流与反馈收集");
        addAgendaItem(items, 110, 120, "总结、合影与有序离场");
        return result;
    }

    private JsonNode planStaffing(CampusEventGoal goal) {
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode roles = result.putArray("roles");
        addRole(roles, "总负责人", 1, "统筹审批、进度和突发决策");
        addRole(roles, "主持人", 1, "控制流程与时间");
        addRole(roles, "签到与引导", 2, "签到、指引和人数核对");
        addRole(roles, "技术支持", 1, "投影、扩音和网络保障");
        addRole(roles, "秩序与安全", Math.max(2, (goal.participantCount() + 49) / 50),
                "通道巡查、秩序维护和应急联络");
        addRole(roles, "摄影与记录", 1, "经参与者知情后记录活动");
        result.put("total_assignments", totalRoleCount(roles));
        return result;
    }

    private JsonNode allocateBudget(CampusEventGoal goal) {
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("total_budget", goal.budget());
        arguments.put("participant_count", goal.participantCount());
        return executeJsonTool("allocate_event_budget", arguments);
    }

    private JsonNode generateMaterials(CampusEventGoal goal) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("announcement", "【" + goal.eventName() + "】计划于"
                + goal.eventDate() + "在" + goal.city() + "举办，预计" + goal.participantCount()
                + "人参加。具体校内场地和开始时间将在审批确认后通知。");
        result.set("registration_fields", stringArray(List.of(
                "姓名",
                "学院或部门",
                "联系方式（仅用于活动变更通知）",
                "是否同意活动拍摄")));
        result.put("privacy_notice", "只收集组织活动所必需的信息，限制访问人员，"
                + "保存期限和删除方式需按本校制度确认。");
        result.put("publication_status", "DRAFT_NOT_PUBLISHED");
        return result;
    }

    private JsonNode assessRisks(CampusExecutionContext context) {
        JsonNode rules = context.requiredOutput("retrieve_campus_rules");
        JsonNode weather = context.requiredOutput("research_weather");
        JsonNode venue = context.requiredOutput("match_venue");
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode risks = result.putArray("items");
        boolean hasTemplates = false;
        for (JsonNode document : rules.path("documents")) {
            if (document.path("requires_verification").asBoolean()) {
                hasTemplates = true;
                break;
            }
        }
        if (hasTemplates) {
            addRisk(risks, "RULES_UNVERIFIED", "HIGH",
                    "当前规则包含演示模板", "提交前替换为本校正式制度并完成审批");
        }
        if (!weather.path("forecast_available").asBoolean()) {
            addRisk(risks, "WEATHER_PENDING", "MEDIUM",
                    "目标日期尚无可用预报", "按recheck_on复查并保留室内备用场地");
        } else if (!"LOW".equals(weather.path("risk_level").asText())) {
            addRisk(risks, "ADVERSE_WEATHER", weather.path("risk_level").asText("MEDIUM"),
                    "预报显示天气风险", "按天气工具建议调整场地和流程");
        }
        if (!venue.path("booking_completed").asBoolean()) {
            addRisk(risks, "VENUE_UNCONFIRMED", "HIGH",
                    "尚未完成真实场地预约", "人工确认档期、容量、设备和审批结果");
        }
        addRisk(risks, "BUDGET_PRICE_CHANGE", "MEDIUM",
                "当前预算是比例建议而非真实报价", "采购前取得报价并保留应急备用金");
        addRisk(risks, "PERSONAL_DATA", "MEDIUM",
                "报名会收集联系方式", "遵循必要性原则并限制数据访问");
        result.put("risk_count", risks.size());
        return result;
    }

    private JsonNode assembleStructuredProposal(CampusExecutionContext context) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("format", "STRUCTURED_DRAFT");
        result.put("ready_for_markdown_rendering", true);
        ObjectNode sections = result.putObject("sections");
        copyOutput(sections, "constraints", context, "resolve_constraints");
        copyOutput(sections, "rules", context, "retrieve_campus_rules");
        copyOutput(sections, "weather", context, "research_weather");
        copyOutput(sections, "venue", context, "match_venue");
        copyOutput(sections, "agenda", context, "design_agenda");
        copyOutput(sections, "staffing", context, "plan_staffing");
        copyOutput(sections, "budget", context, "allocate_budget");
        copyOutput(sections, "materials", context, "generate_materials");
        copyOutput(sections, "risks", context, "assess_risks");
        copyOutput(sections, "evaluation", context, "evaluate_completeness");
        return result;
    }

    private JsonNode executeJsonTool(String toolName, JsonNode arguments) {
        try {
            return objectMapper.readTree(toolRegistry.execute(toolName, arguments.toString()));
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("无法解析工具输出：" + toolName, exception);
        }
    }

    private void addAgendaItem(
            ArrayNode items, int startMinute, int endMinute, String activity) {
        ObjectNode item = items.addObject();
        item.put("start_minute", startMinute);
        item.put("end_minute", endMinute);
        item.put("activity", activity);
    }

    private void addRole(ArrayNode roles, String role, int count, String responsibility) {
        ObjectNode item = roles.addObject();
        item.put("role", role);
        item.put("count", count);
        item.put("responsibility", responsibility);
    }

    private int totalRoleCount(ArrayNode roles) {
        int total = 0;
        for (JsonNode role : roles) {
            total += role.path("count").asInt();
        }
        return total;
    }

    private void addRisk(
            ArrayNode risks, String code, String level, String description, String mitigation) {
        ObjectNode risk = risks.addObject();
        risk.put("code", code);
        risk.put("level", level);
        risk.put("description", description);
        risk.put("mitigation", mitigation);
    }

    private void copyOutput(
            ObjectNode sections,
            String sectionName,
            CampusExecutionContext context,
            String taskId) {
        sections.set(sectionName, context.requiredOutput(taskId).deepCopy());
    }

    private ArrayNode stringArray(List<String> values) {
        ArrayNode array = objectMapper.createArrayNode();
        values.forEach(array::add);
        return array;
    }

    private void putNullable(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }
}

