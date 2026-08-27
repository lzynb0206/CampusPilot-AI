package com.example.demo.skill;

import com.example.demo.agent.campus.CampusAgentOrchestrator;
import com.example.demo.agent.campus.CampusGoalParser;
import com.example.demo.agent.campus.CampusPlanEvaluator;
import com.example.demo.agent.campus.CampusProposalMarkdownRenderer;
import com.example.demo.agent.campus.CampusTaskPlanner;
import com.example.demo.agent.campus.DefaultCampusTaskRunner;
import com.example.demo.config.AiConfig;
import com.example.demo.config.RagConfig;
import com.example.demo.model.ActionType;
import com.example.demo.model.IntentResult;
import com.example.demo.model.MessageRouteResult;
import com.example.demo.model.MessageRouteType;
import com.example.demo.model.ReplyMode;
import com.example.demo.rag.KeywordRagService;
import com.example.demo.service.ai.AlibabaAiService;
import com.example.demo.service.routing.MessageRouter;
import com.example.demo.tool.BotTool;
import com.example.demo.tool.EventBudgetTool;
import com.example.demo.tool.ToolCallingEngine;
import com.example.demo.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CampusPlanningAgentSkillTests {
    private static final String COMPLETE_GOAL =
            "帮我策划一场2026年9月20日在苏州举行、50人参加、预算2000元的校园AI技术分享会。";

    @Test
    void routesCompleteCampusGoalAndReturnsVerifiedMarkdownDeliverable() {
        AtomicInteger weatherCalls = new AtomicInteger();
        CampusPlanningAgentSkill skill = skill(weatherCalls);
        SkillRegistry registry = new SkillRegistry(List.of(skill));

        SkillExecution execution = registry.route(COMPLETE_GOAL).orElseThrow();
        String markdown = execution.reply();

        assertEquals("campus_planning_agent", execution.skillName());
        assertEquals(1, weatherCalls.get());
        assertTrue(markdown.contains("# 校园AI技术分享会完整活动策划书"));
        assertTrue(markdown.contains("## 二、校园规定与知识依据"));
        assertTrue(markdown.contains("`TEMPLATE`"));
        assertTrue(markdown.contains("不能冒充所在学校的正式制度"));
        assertTrue(markdown.contains("## 四、天气评估与备用方案"));
        assertTrue(markdown.contains("2026-09-06"));
        assertTrue(markdown.contains("## 七、预算方案"));
        assertTrue(markdown.contains("¥2000.00"));
        assertTrue(markdown.contains("## 十、Evaluator检查结果"));
        assertTrue(markdown.contains("是否通过：是"));
        assertTrue(markdown.contains("## 十二、Agent执行记录"));
        assertTrue(markdown.contains("真实审批、场地预约和材料发布尚未执行"));
        assertTrue(markdown.length() < 20_000, "策划书不应异常膨胀");
    }

    @Test
    void asksForMissingConstraintsWithoutCallingExternalTool() {
        AtomicInteger weatherCalls = new AtomicInteger();
        SkillRegistry registry = new SkillRegistry(List.of(skill(weatherCalls)));

        SkillExecution execution = registry.route("帮我策划一次校园技术活动").orElseThrow();

        assertEquals(0, weatherCalls.get());
        assertTrue(execution.reply().contains("需要补充信息"));
        assertTrue(execution.reply().contains("活动日期"));
        assertTrue(execution.reply().contains("城市"));
        assertTrue(execution.reply().contains("参与人数"));
        assertTrue(execution.reply().contains("总预算"));
    }

    @Test
    void doesNotHijackNonCampusTravelPlanningRequest() {
        SkillRegistry registry = new SkillRegistry(List.of(skill(new AtomicInteger())));

        assertFalse(registry.route("帮我策划上海三日游").isPresent());
    }

    @Test
    void recognizesValidResumeCommandOnly() {
        CampusPlanningAgentSkill skill = skill(new AtomicInteger());

        assertTrue(skill.matches("继续校园任务 0123456789abcdef"));
        assertFalse(skill.matches("继续校园任务 unknown"));
    }

    @Test
    void messageRouterDeliversCampusAgentResultWithoutCallingLlm() {
        AtomicInteger weatherCalls = new AtomicInteger();
        FailIfCalledAiService aiService = new FailIfCalledAiService();
        MessageRouter router = new MessageRouter(
                new SkillRegistry(List.of(skill(weatherCalls))),
                new KeywordRagService(new RagConfig()),
                aiService);

        MessageRouteResult result = router.route(COMPLETE_GOAL, ReplyMode.TEXT);

        assertEquals(MessageRouteType.SKILL, result.routeType());
        assertEquals(ActionType.CHAT, result.action());
        assertEquals("campus_planning_agent", result.routeDetail());
        assertTrue(result.content().contains("完整活动策划书"));
        assertEquals(1, weatherCalls.get());
        assertEquals(0, aiService.calls.get());
    }

    private CampusPlanningAgentSkill skill(AtomicInteger weatherCalls) {
        ToolRegistry toolRegistry = new ToolRegistry(List.of(
                new EventBudgetTool(),
                new FixedWeatherTool(weatherCalls)));
        DefaultCampusTaskRunner taskRunner = new DefaultCampusTaskRunner(
                new KeywordRagService(new RagConfig()), toolRegistry);
        CampusAgentOrchestrator orchestrator = new CampusAgentOrchestrator(
                new CampusGoalParser(),
                new CampusTaskPlanner(),
                taskRunner,
                new CampusPlanEvaluator());
        return new CampusPlanningAgentSkill(
                orchestrator,
                new CampusProposalMarkdownRenderer());
    }

    private static class FixedWeatherTool implements BotTool {
        private final AtomicInteger calls;

        private FixedWeatherTool(AtomicInteger calls) {
            this.calls = calls;
        }

        @Override
        public String name() {
            return "assess_event_weather";
        }

        @Override
        public String description() {
            return "测试用的活动日期天气评估";
        }

        @Override
        public Map<String, Object> parametersSchema() {
            return Map.of("type", "object");
        }

        @Override
        public String execute(JsonNode arguments) {
            calls.incrementAndGet();
            return """
                    {"success":true,"location":"苏州","event_date":"2026-09-20",
                     "status":"TOO_EARLY","forecast_available":false,
                     "recheck_on":"2026-09-06","final_check_on":"2026-09-19",
                     "message":"活动日期超出逐日预报范围，当前没有把实况冒充预报。",
                     "source":"心知天气逐日预报API",
                     "recommendations":["预留室内场地","在复查日期重新调用真实预报"]}
                    """;
        }
    }

    private static class FailIfCalledAiService extends AlibabaAiService {
        private final AtomicInteger calls = new AtomicInteger();

        private FailIfCalledAiService() {
            super(new AiConfig(), new ToolCallingEngine(new ToolRegistry(List.of())),
                    new RestTemplate());
        }

        @Override
        public IntentResult recognizeIntent(String text, ReplyMode defaultReplyMode) {
            calls.incrementAndGet();
            throw new AssertionError("校园Agent Skill命中后不应调用LLM意图识别");
        }

        @Override
        public String chatWithTools(String userPrompt) {
            calls.incrementAndGet();
            throw new AssertionError("校园Agent Skill命中后不应调用LLM聊天兜底");
        }
    }
}
