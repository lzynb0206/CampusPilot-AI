package com.example.demo.agent.campus;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class CampusProposalMarkdownRenderer {
    public String render(CampusAgentRun run) {
        return switch (run.status()) {
            case NEEDS_INPUT -> renderInvalidInput(run);
            case FAILED -> renderFailure(run);
            case COMPLETED -> renderPlan(run);
        };
    }

    private String renderInvalidInput(CampusAgentRun run) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# 请修正活动信息\n\n")
                .append("你提供的明确信息中有无效值，修正后就能直接生成方案：\n\n");
        for (String issue : run.goal().validationIssues()) {
            markdown.append("- ").append(issue).append("\n");
        }
        markdown.append("\n例如：2026年9月20日、50人、预算2000元的校园AI分享会。\n");
        return markdown.toString().trim();
    }

    private String renderFailure(CampusAgentRun run) {
        return "# 活动方案暂时无法生成\n\n"
                + "部分方案数据暂时无法获取。请直接重新发送原需求，我会重新生成。";
    }

    private String renderPlan(CampusAgentRun run) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# ").append(planTitle(run.goal().eventName())).append("\n\n");
        appendAssumptions(markdown, run);
        appendOverview(markdown, run, output(run, "design_agenda"));
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
        appendActionChecklist(markdown, run);
        return markdown.toString().trim();
    }

    private void appendAssumptions(StringBuilder markdown, CampusAgentRun run) {
        if (run.goal().missingFields().isEmpty()) {
            return;
        }
        markdown.append("> 先给你一份可直接修改的校园通用方案。")
                .append("未提供的信息不会卡住方案：");
        boolean needsSeparator = false;
        if (wasMissing(run, "活动名称")) {
            markdown.append("名称暂用“校园主题活动”");
            needsSeparator = true;
        }
        if (wasMissing(run, "参与人数")) {
            markdown.append(needsSeparator ? "、" : "").append("人数暂按50人规划");
            needsSeparator = true;
        }
        if (wasMissing(run, "总预算")) {
            markdown.append(needsSeparator ? "、" : "").append("预算暂按2000元规划");
            needsSeparator = true;
        }
        if (wasMissing(run, "活动日期")) {
            markdown.append(needsSeparator ? "、" : "").append("日期之后补充");
            needsSeparator = true;
        }
        if (wasMissing(run, "举办城市")) {
            markdown.append(needsSeparator ? "、" : "").append("校区之后补充");
        }
        markdown.append("。\n\n");
    }

    private void appendOverview(
            StringBuilder markdown, CampusAgentRun run, JsonNode agenda) {
        markdown.append("## 一、活动概况\n\n")
                .append("| 项目 | 内容 |\n")
                .append("| --- | --- |\n")
                .append("| 活动名称 | ").append(tableText(run.goal().eventName())).append(" |\n")
                .append("| 日期 | ").append(run.goal().eventDate() == null
                        ? "待补充" : run.goal().eventDate()).append(" |\n")
                .append("| 位置 | ").append(tableText(planLocation(run.goal()))).append(" |\n")
                .append("| 预计人数 | ").append(run.goal().participantCount()).append("人")
                .append(wasMissing(run, "参与人数") ? "（通用假设）" : "").append(" |\n")
                .append("| 总预算 | ¥").append(money(run.goal().budget()))
                .append(wasMissing(run, "总预算") ? "（通用假设）" : "").append(" |\n")
                .append("| 建议时长 | ").append(durationText(
                        agenda.path("duration_minutes").asInt(120))).append(" |\n\n");
    }

    private void appendRules(StringBuilder markdown, JsonNode rules) {
        markdown.append("## 二、通用校园规则\n\n")
                .append("先按大多数学校通用的做法执行：\n\n")
                .append("- 由班级、社团或院系负责人提交活动方案和安全预案，获得同意后再发布。\n")
                .append("- 场地容量要覆盖参与人数，消防通道保持畅通，提前试用投影、扩音、电源和网络。\n")
                .append("- 预算列清数量、单价和小计；采购前确认报销范围、票据要求和审批人。\n")
                .append("- 报名只收集必要信息；拍照、录像或收集联系方式时要提前说明。\n")
                .append("- 安排现场总负责人、秩序人员和紧急联系人，准备中止活动和疏散方案。\n\n");
        boolean hasVerifiedRule = false;
        for (JsonNode document : rules.path("documents")) {
            if (!"VERIFIED".equals(document.path("status").asText())) {
                continue;
            }
            if (!hasVerifiedRule) {
                markdown.append("### 本校具体规则（额外补充）\n\n");
                hasVerifiedRule = true;
            }
            markdown.append("- **").append(safeText(document.path("title").asText())).append("：**")
                    .append(safeText(document.path("content").asText()))
                    .append("（来源：").append(safeText(document.path("source").asText())).append("）\n");
        }
        if (hasVerifiedRule) {
            markdown.append("\n");
            return;
        }
        markdown.append("### 本校细则（有的话再补充）\n\n")
                .append("后续只需核对由哪个部门审批、需提前几天申请、场地开放时间、")
                .append("报销票据和设备使用限制。这些不影响先使用本方案。\n\n");
    }

    private String planLocation(CampusEventGoal goal) {
        if (goal.venue() != null) {
            if (goal.school() == null || goal.venue().contains(goal.school())) {
                return goal.venue();
            }
            return goal.school() + "·" + goal.venue();
        }
        if (goal.school() != null) {
            return goal.school() + "（具体场地待补充）";
        }
        return goal.city() == null ? "本校（具体校区待补充）" : goal.city() + "校内";
    }

    private void appendVenue(StringBuilder markdown, JsonNode venue) {
        markdown.append("## 三、场地方案\n\n")
                .append("- **推荐区域：**").append(safeText(venue.path("recommended_area").asText())).append("\n")
                .append("- **选择理由：**").append(safeText(venue.path("recommendation_reason").asText())).append("\n")
                .append("- 最低建议容量：").append(venue.path("minimum_capacity").asInt()).append("人\n")
                .append("- 说明：").append(safeText(venue.path("notice").asText())).append("\n\n")
                .append("候选场地：\n\n");
        if (venue.path("candidate_venues").isArray()
                && !venue.path("candidate_venues").isEmpty()) {
            markdown.append("| 地点 | 距学校定位点 | 地图地址 | 导航 |\n")
                    .append("| --- | ---: | --- | --- |\n");
            for (JsonNode candidate : venue.path("candidate_venues")) {
                markdown.append("| ").append(tableText(candidate.path("name").asText()))
                        .append(" | ").append(distanceText(candidate.path("distance_meters")))
                        .append(" | ").append(tableText(candidate.path("address").asText("待核对")))
                        .append(" | ");
                String mapUrl = candidate.path("map_url").asText();
                if (mapUrl.isBlank()) {
                    markdown.append("—");
                } else {
                    markdown.append("[高德地图](").append(mapUrl).append(")");
                }
                markdown.append(" |\n");
            }
            markdown.append("\n> 地图只能证明 POI 和位置存在，不能证明容量、设备、开放时段或预约成功。\n");
        } else {
            appendStringList(markdown, venue.path("candidate_types"));
        }
        markdown.append("\n场地要求：\n\n");
        appendStringList(markdown, venue.path("requirements"));
        markdown.append("\n");
    }

    private String distanceText(JsonNode distance) {
        return distance.isNumber() ? distance.asInt() + "米" : "待核对";
    }

    private void appendWeather(StringBuilder markdown, JsonNode weather) {
        markdown.append("## 四、天气与备用方案\n\n")
                .append("- 复查时间：").append(weather.path("recheck_on").asText("活动前7天")).append("\n")
                .append("- 最终检查：").append(weather.path("final_check_on").asText("活动前1天")).append("\n")
                .append("- 说明：").append(safeText(weather.path("message").asText())).append("\n\n");
        if (weather.path("forecast_available").asBoolean()) {
            JsonNode forecast = weather.path("forecast");
            markdown.append("| 白天 | 夜间 | 最高温 | 最低温 | 降雨量 | 风力 |\n")
                    .append("| --- | --- | --- | --- | --- | --- |\n")
                    .append("| ").append(tableText(forecast.path("text_day").asText("未知")))
                    .append(" | ").append(tableText(forecast.path("text_night").asText("未知")))
                    .append(" | ").append(numberOrUnknown(forecast.path("high_celsius"))).append("℃")
                    .append(" | ").append(numberOrUnknown(forecast.path("low_celsius"))).append("℃")
                    .append(" | ").append(numberOrUnknown(forecast.path("rainfall_millimeters"))).append("mm")
                    .append(" | ").append(tableText(forecast.path("wind_scale").asText("未知"))).append(" |\n\n");
        }
        markdown.append("应对建议：\n\n");
        appendStringList(markdown, weather.path("recommendations"));
        markdown.append("\n");
    }

    private void appendAgenda(StringBuilder markdown, JsonNode agenda) {
        markdown.append("## 五、活动流程\n\n")
                .append("- 建议时段：").append(agenda.path("proposed_start_time").asText())
                .append("—").append(agenda.path("proposed_end_time").asText()).append("\n")
                .append("- 说明：").append(safeText(agenda.path("time_basis").asText())).append("\n\n")
                .append("| 建议时间 | 环节 | 责任岗位 |\n")
                .append("| --- | --- | --- |\n");
        for (JsonNode item : agenda.path("items")) {
            markdown.append("| ").append(item.path("start_time").asText())
                    .append("—").append(item.path("end_time").asText())
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
                    .append(" | ").append(tableText(role.path("responsibility").asText())).append(" |\n");
        }
        markdown.append("\n> 同一成员可在时间不冲突时兼任。\n\n");
    }

    private void appendBudget(StringBuilder markdown, JsonNode supplies, JsonNode budget) {
        markdown.append("## 七、预算方案\n\n")
                .append("- 总预算：¥").append(money(budget.path("total_budget").decimalValue())).append("\n")
                .append("- 人均预算：¥").append(money(budget.path("per_capita_budget").decimalValue())).append("\n")
                .append("- 价格说明：").append(safeText(supplies.path("source").asText())).append("\n\n")
                .append("> 下表单价是前期预算控制上限，实际采购前再询价。\n\n")
                .append("| 类别 | 项目 | 数量 | 单位 | 单价上限 | 小计 |\n")
                .append("| --- | --- | ---: | --- | ---: | ---: |\n");
        for (JsonNode item : budget.path("items")) {
            markdown.append("| ").append(tableText(item.path("category").asText()))
                    .append(" | ").append(tableText(item.path("item_name").asText()))
                    .append(" | ").append(decimal(item.path("quantity").decimalValue()))
                    .append(" | ").append(tableText(item.path("unit").asText()))
                    .append(" | ¥").append(money(item.path("unit_price_cap").decimalValue()))
                    .append(" | ¥").append(money(item.path("amount").decimalValue())).append(" |\n");
        }
        markdown.append("| 合计 | — | — | — | — | ¥")
                .append(money(budget.path("allocated_total").decimalValue())).append(" |\n\n")
                .append("### 省预算做法\n\n");
        appendStringList(markdown, supplies.path("zero_cost_actions"));
        markdown.append("\n### 采购前确认\n\n");
        appendStringList(markdown, budget.path("verification_steps"));
        markdown.append("\n");
    }

    private void appendMaterials(StringBuilder markdown, JsonNode materials) {
        markdown.append("## 八、宣传与报名\n\n")
                .append("### 群通知草稿\n\n")
                .append(safeText(materials.path("announcement").asText())).append("\n\n")
                .append("### 报名表字段\n\n");
        appendStringList(markdown, materials.path("registration_fields"));
        markdown.append("\n### 个人信息提示\n\n")
                .append(safeText(materials.path("privacy_notice").asText())).append("\n\n");
    }

    private void appendRisks(StringBuilder markdown, JsonNode risks) {
        markdown.append("## 九、风险与应急预案\n\n")
                .append("| 风险 | 等级 | 应对措施 |\n")
                .append("| --- | --- | --- |\n");
        for (JsonNode risk : risks.path("items")) {
            markdown.append("| ").append(tableText(risk.path("description").asText()))
                    .append(" | ").append(riskLevel(risk.path("level").asText()))
                    .append(" | ").append(tableText(risk.path("mitigation").asText())).append(" |\n");
        }
        markdown.append("\n");
    }

    private void appendActionChecklist(StringBuilder markdown, CampusAgentRun run) {
        JsonNode weather = output(run, "research_weather");
        markdown.append("## 十、落地前再补充\n\n")
                .append("下面信息不影响先使用方案，实际落地时再补齐：\n\n")
                .append("- [ ] 根据本校要求确认审批部门和提前申请天数。\n")
                .append(run.goal().venue() == null
                        ? "- [ ] 在推荐区域内确认具体楼号、教室号或场地名称。\n"
                        : "- [ ] 确认该场地的具体房间号、预约时段和设备开放情况。\n")
                .append("- [ ] 获取真实报价，复核预算与报销要求。\n")
                .append("- [ ] 在").append(weather.path("recheck_on").asText("活动前7天"))
                .append("复查天气，活动前1天最终确认。\n")
                .append("- [ ] 确认日期、开始时间、负责人姓名和紧急联系方式。\n")
                .append("- [ ] 审核宣传文案后再发布。\n\n");
    }

    private JsonNode output(CampusAgentRun run, String taskId) {
        JsonNode value = run.execution(taskId).output();
        if (value == null) {
            throw new IllegalStateException("任务没有可渲染输出：" + taskId);
        }
        return value;
    }

    private void appendStringList(StringBuilder markdown, JsonNode values) {
        for (JsonNode value : values) {
            markdown.append("- ").append(safeText(value.asText())).append("\n");
        }
    }

    private boolean wasMissing(CampusAgentRun run, String field) {
        return run.goal().missingFields().contains(field);
    }

    private String planTitle(String eventName) {
        String name = safeText(eventName);
        return name.endsWith("活动") ? name + "方案" : name + "活动方案";
    }

    private String riskLevel(String level) {
        return switch (level) {
            case "HIGH" -> "高";
            case "LOW" -> "低";
            default -> "中";
        };
    }

    private String durationText(int minutes) {
        int hours = minutes / 60;
        int remainingMinutes = minutes % 60;
        if (hours == 0) {
            return remainingMinutes + "分钟";
        }
        return remainingMinutes == 0
                ? hours + "小时"
                : hours + "小时" + remainingMinutes + "分钟";
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
