package com.example.demo.skill;

import com.example.demo.agent.campus.CampusAgentOrchestrator;
import com.example.demo.agent.campus.CampusProposalMarkdownRenderer;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CampusPlanningAgentSkill implements BotSkill {
    private static final Pattern RESUME_COMMAND = Pattern.compile(
            "继续校园任务\\s*([a-fA-F0-9]{16})");
    private static final List<String> CAMPUS_SIGNALS = List.of("校园", "校内", "学校");
    private static final List<String> PLANNING_SIGNALS = List.of("策划", "规划", "组织", "举办");
    private static final List<String> EVENT_SIGNALS = List.of(
            "活动", "分享会", "讲座", "比赛", "论坛", "晚会", "沙龙", "团建", "会议");

    private final CampusAgentOrchestrator orchestrator;
    private final CampusProposalMarkdownRenderer renderer;

    public CampusPlanningAgentSkill(
            CampusAgentOrchestrator orchestrator,
            CampusProposalMarkdownRenderer renderer) {
        this.orchestrator = orchestrator;
        this.renderer = renderer;
    }

    @Override
    public String name() {
        return "campus_planning_agent";
    }

    @Override
    public String description() {
        return "接收一句校园活动最终目标，自动拆解任务、调用RAG和工具、检查并生成完整策划书。";
    }

    @Override
    public List<String> keywords() {
        return List.of("继续校园任务", "校园活动策划", "帮我策划", "策划一场", "策划一次");
    }

    @Override
    public boolean matches(String userMessage) {
        String normalized = userMessage == null
                ? "" : userMessage.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        if (normalized.startsWith("继续校园任务")) {
            return RESUME_COMMAND.matcher(userMessage).find();
        }
        return containsAny(normalized, CAMPUS_SIGNALS)
                && containsAny(normalized, PLANNING_SIGNALS)
                && containsAny(normalized, EVENT_SIGNALS);
    }

    @Override
    public String execute(String userMessage) {
        Matcher resume = RESUME_COMMAND.matcher(userMessage == null ? "" : userMessage);
        if (resume.find()) {
            return renderer.render(orchestrator.resume(resume.group(1).toLowerCase(Locale.ROOT)));
        }
        return renderer.render(orchestrator.run(userMessage));
    }

    private boolean containsAny(String value, List<String> signals) {
        return signals.stream().anyMatch(value::contains);
    }
}
