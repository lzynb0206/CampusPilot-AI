package com.example.demo.skill;

import com.example.demo.agent.campus.CampusAgentOrchestrator;
import com.example.demo.agent.campus.CampusAgentRun;
import com.example.demo.agent.campus.CampusPosterPromptBuilder;
import com.example.demo.agent.campus.CampusProposalMarkdownRenderer;
import com.example.demo.config.CampusPosterConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CampusPlanningAgentSkill implements BotSkill {
    private static final Pattern RESUME_COMMAND = Pattern.compile(
            "继续校园任务\\s*([a-fA-F0-9]{16})");
    private static final Pattern DATE_SIGNAL = Pattern.compile(
            "(?:今天|今日|明天|后天|\\d{4}\\s*[年./-]\\s*\\d{1,2}\\s*[月./-]\\s*\\d{1,2})");
    private static final Pattern PARTICIPANT_SIGNAL = Pattern.compile(
            "(?:\\d{1,6}|[零〇一二两三四五六七八九十百千万]{1,8})\\s*(?:人|位)");
    private static final Pattern BUDGET_SIGNAL = Pattern.compile(
            "预算[^，。；;]{0,20}\\d+(?:\\.\\d+)?\\s*(?:万)?\\s*(?:元|人民币)");
    private static final List<String> CAMPUS_SIGNALS = List.of("校园", "校内", "学校");
    private static final List<String> PLANNING_SIGNALS = List.of("策划", "规划", "组织", "举办");
    private static final List<String> EVENT_SIGNALS = List.of(
            "活动", "分享会", "讲座", "比赛", "论坛", "晚会", "沙龙", "团建", "会议",
            "运动会", "市集", "演出");

    private final CampusAgentOrchestrator orchestrator;
    private final CampusProposalMarkdownRenderer renderer;
    private final CampusPosterPromptBuilder posterPromptBuilder;

    @Autowired
    public CampusPlanningAgentSkill(
            CampusAgentOrchestrator orchestrator,
            CampusProposalMarkdownRenderer renderer,
            CampusPosterPromptBuilder posterPromptBuilder) {
        this.orchestrator = orchestrator;
        this.renderer = renderer;
        this.posterPromptBuilder = posterPromptBuilder;
    }

    public CampusPlanningAgentSkill(
            CampusAgentOrchestrator orchestrator,
            CampusProposalMarkdownRenderer renderer) {
        this(orchestrator, renderer,
                new CampusPosterPromptBuilder(new CampusPosterConfig()));
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
        boolean isPlanningRequest = containsAny(normalized, PLANNING_SIGNALS)
                && containsAny(normalized, EVENT_SIGNALS);
        boolean explicitCampus = containsAny(normalized, CAMPUS_SIGNALS);
        boolean completeActivityGoal = DATE_SIGNAL.matcher(normalized).find()
                && PARTICIPANT_SIGNAL.matcher(normalized).find()
                && BUDGET_SIGNAL.matcher(normalized).find();
        return isPlanningRequest && (explicitCampus || completeActivityGoal);
    }

    @Override
    public String execute(String userMessage) {
        return executePlan(userMessage).reply();
    }

    @Override
    public SkillOutput executeWithArtifacts(String userMessage) {
        if (orchestrator == null || renderer == null) {
            return SkillOutput.text(execute(userMessage));
        }
        return executePlan(userMessage);
    }

    private SkillOutput executePlan(String userMessage) {
        Matcher resume = RESUME_COMMAND.matcher(userMessage == null ? "" : userMessage);
        CampusAgentRun run;
        if (resume.find()) {
            run = orchestrator.resume(resume.group(1).toLowerCase(Locale.ROOT));
        } else {
            run = orchestrator.run(userMessage);
        }
        return SkillOutput.poster(renderer.render(run), posterPromptBuilder.build(run));
    }

    private boolean containsAny(String value, List<String> signals) {
        return signals.stream().anyMatch(value::contains);
    }
}
