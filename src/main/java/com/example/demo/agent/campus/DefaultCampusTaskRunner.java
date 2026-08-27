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

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DefaultCampusTaskRunner implements CampusTaskRunner {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
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
            case "design_agenda" -> designAgenda(context.goal());
            case "plan_staffing" -> planStaffing(context.goal());
            case "estimate_supplies" -> estimateSupplies(context);
            case "allocate_budget" -> allocateBudget(context);
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
        putNullable(result, "school", goal.school());
        putNullable(result, "venue", goal.venue());
        putNullable(result, "start_time", goal.startTime() == null
                ? null : goal.startTime().format(TIME_FORMAT));
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
        if (goal.eventDate() == null || goal.city() == null) {
            ObjectNode result = objectMapper.createObjectNode();
            result.put("status", "GENERAL_PLAN");
            result.put("forecast_available", false);
            putNullable(result, "event_date",
                    goal.eventDate() == null ? null : goal.eventDate().toString());
            result.put("recheck_on", "活动前7天");
            result.put("final_check_on", "活动前1天");
            result.put("message", "日期或城市尚未提供，先给出通用备用方案，不虚构具体天气。");
            result.put("source", "通用活动风险检查清单");
            result.set("recommendations", stringArray(List.of(
                    "室外活动同时预留教学楼大教室或体育馆作备用场地",
                    "活动前7天查看天气，前1天决定是否切换场地",
                    "高温、降雨或大风时优先转入室内")));
            return result;
        }
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
        VenueRecommendation recommendation = recommendVenue(goal.rawGoal());
        if (goal.venue() != null) {
            String exactVenue = displayVenue(goal.school(), goal.venue());
            recommendation = new VenueRecommendation(
                    exactVenue,
                    "已采用你在当前活动会话中补充的具体场地",
                    List.of(exactVenue),
                    recommendation.requirements());
            result.put("status", "USER_PROVIDED");
        }
        result.put("recommended_area", recommendation.area());
        result.put("recommendation_reason", recommendation.reason());
        result.put("exact_venue_needed", goal.venue() == null);
        result.set("candidate_types", stringArray(recommendation.candidateTypes()));
        result.set("requirements", stringArray(recommendation.requirements().stream()
                .map(requirement -> requirement.replace("{capacity}", String.valueOf(minimumCapacity)))
                .toList()));
        result.put("notice", goal.venue() == null
                ? "先确定到校园区域和场地类型即可，具体楼号、教室号或场地名称可后续补充。"
                : "场地已更新；落地前再确认具体房间号、预约时段和设备开放情况。");
        return result;
    }

    private String displayVenue(String school, String venue) {
        if (school == null || venue.contains(school)) {
            return venue;
        }
        return school + "·" + venue;
    }

    private VenueRecommendation recommendVenue(String rawGoal) {
        if (isSportsOrOutdoor(rawGoal)) {
            return new VenueRecommendation(
                    "操场或体育场",
                    "活动需要较大的开放空间和人员流动区域",
                    List.of("操场或体育场", "体育馆", "大型室外广场"),
                    List.of(
                            "至少容纳{capacity}人，比赛区与观众区分开",
                            "地面平整防滑，设置边界标识、饮水点和急救点",
                            "准备扩音、计时、记分和基础运动器材",
                            "预留体育馆或大型室内场地作恶劣天气备用"));
        }
        if (isPerformance(rawGoal)) {
            return new VenueRecommendation(
                    "礼堂或大学生活动中心",
                    "活动需要舞台、扩音、灯光和观众席",
                    List.of("校内礼堂", "大学生活动中心多功能厅", "小剧场"),
                    List.of(
                            "至少容纳{capacity}人，观众席与后台动线分开",
                            "提供舞台、灯光、扩音、电源和化妆候场区",
                            "提前完成节目彩排、音频测试和电力负载检查",
                            "消防通道和安全出口保持畅通"));
        }
        return new VenueRecommendation(
                "教学楼",
                "分享、讲座和一般室内活动便于使用投影、扩音和网络",
                List.of("教学楼大教室", "校内报告厅", "可调整桌椅的会议室"),
                List.of(
                        "至少容纳{capacity}人",
                        "提供投影、扩音、电源和稳定网络",
                        "消防通道保持畅通",
                        "确认开放时间、审批人和设备使用限制"));
    }

    private JsonNode designAgenda(CampusEventGoal goal) {
        LocalTime proposedStart = goal.startTime() == null
                ? LocalTime.of(14, 0) : goal.startTime();
        ObjectNode result = objectMapper.createObjectNode();
        int durationMinutes = isSportsOrOutdoor(goal.rawGoal()) || isPerformance(goal.rawGoal())
                ? 150 : 120;
        result.put("duration_minutes", durationMinutes);
        result.put("schedule_status", "PROPOSED_TIME_PENDING_CONFIRMATION");
        result.put("proposed_start_time", proposedStart.format(TIME_FORMAT));
        result.put("proposed_end_time", proposedStart.plusMinutes(durationMinutes).format(TIME_FORMAT));
        result.put("time_basis", goal.startTime() == null
                ? "建议14:00开始，具体时段可在场地确认后调整"
                : "已采用你在当前活动会话中补充的开始时间");
        ArrayNode items = result.putArray("items");
        if (isSportsOrOutdoor(goal.rawGoal())) {
            addAgendaItem(items, proposedStart, 0, 20, "签到、分组与器材检查", "签到组和器材组");
            addAgendaItem(items, proposedStart, 20, 35, "热身、规则说明与安全提示", "主持人和裁判");
            addAgendaItem(items, proposedStart, 35, 95, "分组趣味项目与轮换比赛", "裁判组");
            addAgendaItem(items, proposedStart, 95, 125, "决赛项目与团队挑战", "裁判组和秩序组");
            addAgendaItem(items, proposedStart, 125, 140, "成绩汇总与颁奖", "计分组和主持人");
            addAgendaItem(items, proposedStart, 140, 150, "合影、整理器材与有序离场", "总负责人");
            return result;
        }
        if (isPerformance(goal.rawGoal())) {
            addAgendaItem(items, proposedStart, 0, 20, "观众入场与舞台最终检查", "前台组和舞台组");
            addAgendaItem(items, proposedStart, 20, 30, "主持开场与安全提示", "主持人");
            addAgendaItem(items, proposedStart, 30, 90, "上半场节目演出", "舞台组和演出人员");
            addAgendaItem(items, proposedStart, 90, 105, "中场互动与设备检查", "主持人和技术组");
            addAgendaItem(items, proposedStart, 105, 140, "下半场节目演出", "舞台组和演出人员");
            addAgendaItem(items, proposedStart, 140, 150, "谢幕、合影与有序离场", "总负责人");
            return result;
        }
        addAgendaItem(items, proposedStart, 0, 15, "签到与设备检查", "签到负责人和技术支持");
        addAgendaItem(items, proposedStart, 15, 25, "主持开场与安全提示", "主持人");
        addAgendaItem(items, proposedStart, 25, 75, "技术主题分享", "分享嘉宾和技术支持");
        addAgendaItem(items, proposedStart, 75, 95, "问答与互动", "主持人和分享嘉宾");
        addAgendaItem(items, proposedStart, 95, 110, "自由交流与反馈收集", "引导人员");
        addAgendaItem(items, proposedStart, 110, 120, "总结、合影与有序离场", "总负责人");
        return result;
    }

    private JsonNode planStaffing(CampusEventGoal goal) {
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode roles = result.putArray("roles");
        addRole(roles, "总负责人", 1, "统筹审批、进度和突发决策");
        addRole(roles, "主持人", 1, "控制流程与时间");
        addRole(roles, "签到与引导", 2, "签到、指引和人数核对");
        if (isSportsOrOutdoor(goal.rawGoal())) {
            addRole(roles, "裁判与计分", 3, "说明规则、执行裁判并汇总成绩");
            addRole(roles, "器材与场地", 2, "布置项目、管理器材和补给点");
            addRole(roles, "安全与急救", Math.max(2, (goal.participantCount() + 49) / 50),
                    "维护秩序、处理轻微伤情并联络校医");
            addRole(roles, "摄影与记录", 1, "经参与者知情后记录活动");
            result.put("total_assignments", totalRoleCount(roles));
            return result;
        }
        if (isPerformance(goal.rawGoal())) {
            addRole(roles, "舞台与催场", 2, "节目衔接、道具上下场和后台秩序");
            addRole(roles, "灯光音响", 2, "控制灯光、麦克风、音乐和投影");
            addRole(roles, "秩序与安全", Math.max(2, (goal.participantCount() + 49) / 50),
                    "巡查观众席、出入口和消防通道");
            addRole(roles, "摄影与记录", 1, "经参与者知情后记录活动");
            result.put("total_assignments", totalRoleCount(roles));
            return result;
        }
        addRole(roles, "技术支持", 1, "投影、扩音和网络保障");
        addRole(roles, "秩序与安全", Math.max(2, (goal.participantCount() + 49) / 50),
                "通道巡查、秩序维护和应急联络");
        addRole(roles, "摄影与记录", 1, "经参与者知情后记录活动");
        result.put("total_assignments", totalRoleCount(roles));
        return result;
    }

    private JsonNode estimateSupplies(CampusExecutionContext context) {
        JsonNode weather = context.requiredOutput("research_weather");
        boolean hotWeather = weather.path("forecast_available").asBoolean()
                && weather.path("forecast").path("high_celsius").asInt() >= 30;
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("participant_count", context.goal().participantCount());
        arguments.put("hot_weather", hotWeather);
        return executeJsonTool("estimate_event_supplies", arguments);
    }

    private JsonNode allocateBudget(CampusExecutionContext context) {
        CampusEventGoal goal = context.goal();
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("total_budget", goal.budget());
        arguments.put("participant_count", goal.participantCount());
        arguments.set("supply_items", context.requiredOutput("estimate_supplies").path("items"));
        return executeJsonTool("allocate_event_budget", arguments);
    }

    private JsonNode generateMaterials(CampusEventGoal goal) {
        ObjectNode result = objectMapper.createObjectNode();
        String date = goal.eventDate() == null ? "待定日期" : goal.eventDate().toString();
        String location = goal.venue() != null
                ? displayVenue(goal.school(), goal.venue())
                : goal.school() != null ? goal.school()
                : goal.city() == null ? "校内" : goal.city() + "校内";
        result.put("announcement", "【" + goal.eventName() + "】计划于" + date
                + "在" + location + "举办，预计" + goal.participantCount()
                + "人参加。具体时间和地点确定后另行通知。");
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
                    "本校具体审批细则尚未补充",
                    "先按通用规则准备方案，提交前再核对本校审批部门和时限");
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
                "当前单价是内部控制上限且尚未取得真实报价",
                "按预算表逐项询价，用供应方、日期和报价替换控制上限");
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
        copyOutput(sections, "supplies", context, "estimate_supplies");
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
            ArrayNode items,
            LocalTime proposedStart,
            int startMinute,
            int endMinute,
            String activity,
            String ownerRole) {
        ObjectNode item = items.addObject();
        item.put("start_minute", startMinute);
        item.put("end_minute", endMinute);
        item.put("start_time", proposedStart.plusMinutes(startMinute).format(TIME_FORMAT));
        item.put("end_time", proposedStart.plusMinutes(endMinute).format(TIME_FORMAT));
        item.put("activity", activity);
        item.put("owner_role", ownerRole);
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

    private boolean containsAny(String value, String... signals) {
        if (value == null) {
            return false;
        }
        for (String signal : signals) {
            if (value.contains(signal)) {
                return true;
            }
        }
        return false;
    }

    private boolean isSportsOrOutdoor(String rawGoal) {
        return containsAny(rawGoal, "运动", "跑步", "球赛", "趣味运动", "市集", "户外");
    }

    private boolean isPerformance(String rawGoal) {
        return containsAny(rawGoal, "晚会", "演出", "音乐", "舞蹈", "文艺");
    }

    private record VenueRecommendation(
            String area,
            String reason,
            List<String> candidateTypes,
            List<String> requirements) {
    }
}
