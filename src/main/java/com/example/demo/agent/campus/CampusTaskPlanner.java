package com.example.demo.agent.campus;

import java.util.List;

public class CampusTaskPlanner {
    public CampusAgentPlan createPlan(CampusEventGoal goal) {
        if (goal == null) {
            throw new IllegalArgumentException("活动目标不能为空");
        }

        List<AgentTask> tasks = List.of(
                task("resolve_constraints", "确认活动约束", TaskCapability.LLM, List.of(),
                        "结构化活动信息、明确假设和待确认项"),
                task("retrieve_campus_rules", "检索校园活动规定", TaskCapability.RAG,
                        List.of("resolve_constraints"), "与本次活动相关的审批、场地和安全规则及来源"),
                task("research_weather", "查询天气并准备备用方案", TaskCapability.TOOL,
                        List.of("resolve_constraints"), "活动日期天气信息、查询限制和室内备用方案"),
                task("match_venue", "分析并匹配活动场地", TaskCapability.TOOL,
                        List.of("resolve_constraints", "retrieve_campus_rules"), "场地需求、候选场地和选择理由"),
                task("design_agenda", "设计活动流程", TaskCapability.LLM,
                        List.of("resolve_constraints", "retrieve_campus_rules"), "包含签到、主体环节和收尾的时间表"),
                task("plan_staffing", "设计人员分工", TaskCapability.LLM,
                        List.of("design_agenda"), "岗位、人数、职责和到岗时间"),
                task("allocate_budget", "计算并分配预算", TaskCapability.TOOL,
                        List.of("match_venue", "design_agenda", "plan_staffing"), "费用明细、合计、人均成本和备用金"),
                task("generate_materials", "生成宣传与报名材料", TaskCapability.SKILL,
                        List.of("design_agenda"), "宣传文案、群通知和报名表字段"),
                task("assess_risks", "生成风险和应急预案", TaskCapability.SKILL,
                        List.of("retrieve_campus_rules", "research_weather", "match_venue", "design_agenda"),
                        "风险等级、影响、预防措施和应急负责人"),
                task("evaluate_completeness", "检查方案完整性和冲突", TaskCapability.EVALUATOR,
                        List.of("allocate_budget", "generate_materials", "assess_risks"),
                        "预算、规则、时间和章节完整性检查报告"),
                task("assemble_proposal", "汇总完整活动策划书", TaskCapability.LLM,
                        List.of("evaluate_completeness"), "可直接审阅的完整校园活动策划书"));
        return new CampusAgentPlan(goal, tasks);
    }

    private AgentTask task(
            String id,
            String title,
            TaskCapability capability,
            List<String> dependsOn,
            String expectedOutput) {
        return new AgentTask(id, title, capability, dependsOn, expectedOutput);
    }
}

