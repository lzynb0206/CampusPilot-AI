package com.example.demo.agent.campus;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
public class CampusProposalMarkdownRenderer {
    public String render(CampusAgentRun run) {
        return switch (run.status()) {
            case NEEDS_INPUT -> renderMissingInput(run);
            case FAILED -> renderFailure(run);
            case COMPLETED -> renderCompleted(run);
        };
    }

    private String renderMissingInput(CampusAgentRun run) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# CampusPilot 需要补充信息\n\n")
                .append("我已经拆解了任务，但不会在关键信息缺失时调用外部工具或编造方案。\n\n")
                .append("请补充：\n\n");
        for (String field : run.goal().missingFields()) {
            markdown.append("- ").append(field).append("\n");
        }
        for (String issue : run.goal().validationIssues()) {
            markdown.append("- 修正：").append(issue).append("\n");
        }
        markdown.append("\n推荐按下面格式重新发送一句话：\n\n")
                .append("> 帮我策划一场2026年9月20日在苏州举行、50人参加、预算2000元的校园AI技术分享会。\n");
        return markdown.toString().trim();
    }

    private String renderFailure(CampusAgentRun run) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# CampusPilot 执行未完成\n\n")
                .append("Evaluator检查未通过，系统没有把不完整结果包装成最终策划书。\n\n")
                .append("- 任务编号：`").append(run.runId()).append("`\n")
                .append("- 续跑命令：`继续校园任务 ").append(run.runId()).append("`\n\n")
                .append("## 任务状态\n\n");
        appendTaskTable(markdown, run.taskExecutions());
        markdown.append("\n## 待处理问题\n\n");
        for (CampusEvaluationIssue issue : run.evaluation().issues()) {
            markdown.append("- [").append(issue.severity()).append("] ")
                    .append(issue.message()).append("（任务：")
                    .append(issue.taskId()).append("）\n");
        }
        return markdown.toString().trim();
    }

    private String renderCompleted(CampusAgentRun run) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# ").append(safeText(run.goal().eventName()))
                .append("完整活动策划书\n\n")
                .append("> 生成状态：Agent已完成任务拆解、工具执行和Evaluator检查。")
                .append("真实审批、场地预约和材料发布尚未执行。\n\n");
        appendOverview(markdown, run);
        appendRules(markdown, output(run, "retrieve_campus_rules"));
        appendVenue(markdown, output(run, "match_venue"));
        appendWeather(markdown, output(run, "research_weather"));
        appendAgenda(markdown, output(run, "design_agenda"));
        appendStaffing(markdown, output(run, "plan_staffing"));
        appendBudget(markdown,
                output(run, "estimate_supplies"),
                output(run, "allocate_budget"));
        appendMaterials(markdown, output(run, "generate_materials"));
        appendRisks(markdown, output(run, "assess_risks"));
        appendEvaluation(markdown, run);
        appendActionChecklist(markdown, run);
        appendExecutionSummary(markdown, run);
        return markdown.toString().trim();
    }

    private void appendOverview(StringBuilder markdown, CampusAgentRun run) {
        markdown.append("## 一、活动概况\n\n")
                .append("| 项目 | 内容 |\n")
                .append("| --- | --- |\n")
                .append("| Agent任务编号 | `").append(run.runId()).append("` |\n")
                .append("| 活动名称 | ").append(tableText(run.goal().eventName())).append(" |\n")
                .append("| 日期 | ").append(run.goal().eventDate()).append(" |\n")
                .append("| 城市 | ").append(tableText(run.goal().city())).append(" |\n")
                .append("| 预计人数 | ").append(run.goal().participantCount()).append("人 |\n")
                .append("| 总预算 | ¥").append(money(run.goal().budget())).append(" |\n")
                .append("| 本次断点恢复 | ").append(run.resumedTaskCount()).append("项已完成任务 |\n")
                .append("| 当前性质 | 策划草案，待校内审批及场地确认 |\n\n");
    }

    private void appendRules(StringBuilder markdown, JsonNode rules) {
        markdown.append("## 二、校园规定与知识依据\n\n")
                .append("> 当前知识库中的校园规定为演示模板，不能冒充所在学校的正式制度。\n\n");
        for (JsonNode document : rules.path("documents")) {
            markdown.append("### ").append(safeText(document.path("title").asText())).append("\n\n")
                    .append("- 可信状态：`").append(document.path("status").asText()).append("`\n")
                    .append("- 来源：").append(safeText(document.path("source").asText())).append("\n")
                    .append("- 使用要求：")
                    .append(document.path("requires_verification").asBoolean()
                            ? "待按本校正式制度确认" : "可按来源引用")
                    .append("\n\n")
                    .append(safeText(document.path("content").asText())).append("\n\n");
        }
    }

    private void appendVenue(StringBuilder markdown, JsonNode venue) {
        markdown.append("## 三、场地方案\n\n")
                .append("- 最低建议容量：").append(venue.path("minimum_capacity").asInt()).append("人\n")
                .append("- 预约状态：")
                .append(venue.path("booking_completed").asBoolean() ? "已确认" : "尚未预约")
                .append("\n")
                .append("- 说明：").append(safeText(venue.path("notice").asText())).append("\n\n")
                .append("候选场地类型：\n\n");
        appendStringList(markdown, venue.path("candidate_types"));
        markdown.append("\n场地要求：\n\n");
        appendStringList(markdown, venue.path("requirements"));
        markdown.append("\n");
    }

    private void appendWeather(StringBuilder markdown, JsonNode weather) {
        markdown.append("## 四、天气评估与备用方案\n\n")
                .append("- 状态：`").append(weather.path("status").asText()).append("`\n")
                .append("- 活动日期：").append(weather.path("event_date").asText()).append("\n")
                .append("- 复查日期：").append(weather.path("recheck_on").asText("待确认")).append("\n")
                .append("- 最终检查：").append(weather.path("final_check_on").asText("待确认")).append("\n")
                .append("- 数据说明：").append(safeText(weather.path("message").asText())).append("\n")
                .append("- 来源：").append(safeText(weather.path("source").asText("心知天气逐日预报API")))
                .append("\n\n");
        if (weather.path("forecast_available").asBoolean()) {
            JsonNode forecast = weather.path("forecast");
            markdown.append("| 白天 | 夜间 | 最高温 | 最低温 | 降雨量 | 风力 |\n")
                    .append("| --- | --- | --- | --- | --- | --- |\n")
                    .append("| ").append(tableText(forecast.path("text_day").asText("未知")))
                    .append(" | ").append(tableText(forecast.path("text_night").asText("未知")))
                    .append(" | ").append(numberOrUnknown(forecast.path("high_celsius"))).append("℃")
                    .append(" | ").append(numberOrUnknown(forecast.path("low_celsius"))).append("℃")
                    .append(" | ").append(numberOrUnknown(forecast.path("rainfall_millimeters"))).append("mm")
                    .append(" | ").append(tableText(forecast.path("wind_scale").asText("未知")))
                    .append(" |\n\n");
        }
        markdown.append("应对建议：\n\n");
        appendStringList(markdown, weather.path("recommendations"));
        markdown.append("\n");
    }

    private void appendAgenda(StringBuilder markdown, JsonNode agenda) {
        markdown.append("## 五、活动流程\n\n")
                .append("- 建议时段：").append(agenda.path("proposed_start_time").asText())
                .append("—").append(agenda.path("proposed_end_time").asText()).append("\n")
                .append("- 确认状态：`").append(agenda.path("schedule_status").asText()).append("`\n")
                .append("- 说明：").append(safeText(agenda.path("time_basis").asText())).append("\n\n")
                .append("| 建议时间 | 相对时间 | 环节 | 责任岗位 |\n")
                .append("| --- | --- | --- | --- |\n");
        for (JsonNode item : agenda.path("items")) {
            markdown.append("| ").append(item.path("start_time").asText())
                    .append("—").append(item.path("end_time").asText())
                    .append(" | 第").append(item.path("start_minute").asInt())
                    .append("—").append(item.path("end_minute").asInt()).append("分钟")
                    .append(" | ").append(tableText(item.path("activity").asText()))
                    .append(" | ").append(tableText(item.path("owner_role").asText())).append(" |\n");
        }
        markdown.append("\n");
    }

    private void appendStaffing(StringBuilder markdown, JsonNode staffing) {
        markdown.append("## 六、人员分工\n\n")
                .append("| 岗位 | 人数 | 职责 |\n")
                .append("| --- | ---: | --- |\n");
        for (JsonNode role : staffing.path("roles")) {
            markdown.append("| ").append(tableText(role.path("role").asText()))
                    .append(" | ").append(role.path("count").asInt())
                    .append(" | ").append(tableText(role.path("responsibility").asText()))
                    .append(" |\n");
        }
        markdown.append("\n> 岗位合计为").append(staffing.path("total_assignments").asInt())
                .append("个工作分配，同一成员可在不冲突的时段兼任。\n\n");
    }

    private void appendBudget(StringBuilder markdown, JsonNode supplies, JsonNode budget) {
        markdown.append("## 七、预算方案\n\n")
                .append("- 总预算：¥").append(money(budget.path("total_budget").decimalValue())).append("\n")
                .append("- 人均预算：¥").append(money(budget.path("per_capita_budget").decimalValue())).append("\n")
                .append("- 物资数量缓冲：").append(supplies.path("quantity_buffer_percent").asInt()).append("%\n")
                .append("- 价格状态：`").append(budget.path("pricing_status").asText()).append("`\n")
                .append("- 是否取得真实报价：")
                .append(budget.path("quote_obtained").asBoolean() ? "是" : "否").append("\n")
                .append("- 价格来源：").append(safeText(supplies.path("source").asText())).append("\n\n")
                .append("> 下表的“单价”都是内部预算控制上限，不是市场价格或商家报价。")
                .append("真实执行必须询价并替换。\n\n")
                .append("| 类别 | 具体项目 | 数量 | 单位 | 单价控制上限 | 小计 | 价格口径 |\n")
                .append("| --- | --- | ---: | --- | ---: | ---: | --- |\n");
        for (JsonNode item : budget.path("items")) {
            markdown.append("| ").append(tableText(item.path("category").asText()))
                    .append(" | ").append(tableText(item.path("item_name").asText()))
                    .append(" | ").append(decimal(item.path("quantity").decimalValue()))
                    .append(" | ").append(tableText(item.path("unit").asText()))
                    .append(" | ¥").append(money(item.path("unit_price_cap").decimalValue()))
                    .append(" | ¥").append(money(item.path("amount").decimalValue()))
                    .append(" | `").append(item.path("pricing_type").asText()).append("` |\n");
        }
        markdown.append("| 合计 | — | — | — | — | ¥")
                .append(money(budget.path("allocated_total").decimalValue())).append(" | — |\n\n")
                .append("### 零成本优先动作\n\n");
        appendStringList(markdown, supplies.path("zero_cost_actions"));
        markdown.append("\n### 询价与报销核验\n\n");
        appendStringList(markdown, budget.path("verification_steps"));
        markdown.append("\n> ").append(safeText(budget.path("basis").asText())).append("\n\n");
    }

    private void appendMaterials(StringBuilder markdown, JsonNode materials) {
        markdown.append("## 八、宣传与报名材料\n\n")
                .append("### 群通知草稿\n\n")
                .append(safeText(materials.path("announcement").asText())).append("\n\n")
                .append("### 报名表字段\n\n");
        appendStringList(markdown, materials.path("registration_fields"));
        markdown.append("\n### 个人信息提示\n\n")
                .append(safeText(materials.path("privacy_notice").asText())).append("\n\n")
                .append("发布状态：`").append(materials.path("publication_status").asText()).append("`\n\n");
    }

    private void appendRisks(StringBuilder markdown, JsonNode risks) {
        markdown.append("## 九、风险与应急预案\n\n")
                .append("| 风险 | 等级 | 说明 | 应对措施 |\n")
                .append("| --- | --- | --- | --- |\n");
        for (JsonNode risk : risks.path("items")) {
            markdown.append("| `").append(risk.path("code").asText()).append("`")
                    .append(" | ").append(tableText(risk.path("level").asText()))
                    .append(" | ").append(tableText(risk.path("description").asText()))
                    .append(" | ").append(tableText(risk.path("mitigation").asText()))
                    .append(" |\n");
        }
        markdown.append("\n");
    }

    private void appendEvaluation(StringBuilder markdown, CampusAgentRun run) {
        markdown.append("## 十、Evaluator检查结果\n\n")
                .append("- 是否通过：").append(run.evaluation().passed() ? "是" : "否").append("\n")
                .append("- 自动修订次数：").append(run.revisionCount()).append("\n\n");
        if (run.evaluation().issues().isEmpty()) {
            markdown.append("未发现错误或警告。\n\n");
            return;
        }
        for (CampusEvaluationIssue issue : run.evaluation().issues()) {
            markdown.append("- [").append(issue.severity()).append("] ")
                    .append(issue.message()).append("（")
                    .append(issue.code()).append("）\n");
        }
        markdown.append("\n");
    }

    private void appendActionChecklist(StringBuilder markdown, CampusAgentRun run) {
        JsonNode weather = output(run, "research_weather");
        markdown.append("## 十一、执行前待办清单\n\n")
                .append("- [ ] 用本校正式文件替换演示规则并完成审批。\n")
                .append("- [ ] 确认真实场地、档期、容量和设备。\n")
                .append("- [ ] 获取真实报价并复核预算明细与报销要求。\n")
                .append("- [ ] 在").append(weather.path("recheck_on").asText("建议日期"))
                .append("复查天气，并在活动前一天最终确认。\n")
                .append("- [ ] 确认具体开始时间、负责人姓名和紧急联系方式。\n")
                .append("- [ ] 审核宣传文案后再发布，未经确认不得自动发送。\n\n");
    }

    private void appendExecutionSummary(StringBuilder markdown, CampusAgentRun run) {
        markdown.append("## 十二、Agent执行记录\n\n");
        appendTaskTable(markdown, run.taskExecutions());
    }

    private void appendTaskTable(
            StringBuilder markdown, List<CampusTaskExecution> executions) {
        markdown.append("| 任务 | 状态 | 尝试次数 |\n")
                .append("| --- | --- | ---: |\n");
        for (CampusTaskExecution execution : executions) {
            markdown.append("| `").append(execution.taskId()).append("`")
                    .append(" | ").append(execution.status())
                    .append(" | ").append(execution.attempts()).append(" |\n");
        }
    }

    private JsonNode output(CampusAgentRun run, String taskId) {
        JsonNode output = run.execution(taskId).output();
        if (output == null) {
            throw new IllegalStateException("任务没有可渲染输出：" + taskId);
        }
        return output;
    }

    private void appendStringList(StringBuilder markdown, JsonNode values) {
        for (JsonNode value : values) {
            markdown.append("- ").append(safeText(value.asText())).append("\n");
        }
    }

    private String numberOrUnknown(JsonNode value) {
        return value.isNumber() ? decimal(value.decimalValue()) : "未知";
    }

    private String money(BigDecimal value) {
        return value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private String decimal(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private String tableText(String value) {
        return safeText(value).replace("|", "\\|")
                .replace('\n', ' ')
                .replace('\r', ' ');
    }

    private String safeText(String value) {
        return value == null || value.isBlank() ? "待确认" : value.trim();
    }
}
