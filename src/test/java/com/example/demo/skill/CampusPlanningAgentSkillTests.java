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
import com.example.demo.service.routing.CampusConversationService;
import com.example.demo.service.routing.CampusConversationUpdateParser;
import com.example.demo.tool.BotTool;
import com.example.demo.tool.EventBudgetTool;
import com.example.demo.tool.EventSupplyEstimateTool;
import com.example.demo.tool.ToolCallingEngine;
import com.example.demo.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
        assertTrue(execution.imagePrompt().contains("竖版中文活动海报"));
        assertTrue(execution.imagePrompt().contains("校园AI技术分享会"));
        assertTrue(markdown.contains("# 校园AI技术分享会活动方案"));
        assertTrue(markdown.contains("## 二、通用校园规则"));
        assertTrue(markdown.contains("本校细则（有的话再补充）"));
        assertFalse(markdown.contains("`TEMPLATE`"));
        assertTrue(markdown.contains("## 四、天气与备用方案"));
        assertTrue(markdown.contains("2026-09-06"));
        assertTrue(markdown.contains("## 七、预算方案"));
        assertTrue(markdown.contains("¥2000.00"));
        assertFalse(markdown.contains("Evaluator"));
        assertFalse(markdown.contains("Agent任务编号"));
        assertFalse(markdown.contains("执行记录"));
        assertTrue(markdown.length() < 20_000, "策划书不应异常膨胀");
    }

    @Test
    void returnsGenericPlanWithoutBlockingOnMissingDetails() {
        AtomicInteger weatherCalls = new AtomicInteger();
        SkillRegistry registry = new SkillRegistry(List.of(skill(weatherCalls)));

        SkillExecution execution = registry.route("帮我策划一次校园技术活动").orElseThrow();

        assertEquals(0, weatherCalls.get());
        assertTrue(execution.reply().contains("# 校园技术活动方案"));
        assertTrue(execution.reply().contains("先给你一份可直接修改的校园通用方案"));
        assertTrue(execution.reply().contains("推荐区域：**教学楼"));
        assertTrue(execution.reply().contains("本校细则（有的话再补充）"));
        assertFalse(execution.reply().contains("需要补充信息"));
    }

    @Test
    void doesNotHijackNonCampusTravelPlanningRequest() {
        SkillRegistry registry = new SkillRegistry(List.of(skill(new AtomicInteger())));

        assertFalse(registry.route("帮我策划上海三日游").isPresent());
    }

    @Test
    void acceptsCompleteActivityGoalWithoutExplicitCampusWord() {
        CampusPlanningAgentSkill skill = skill(new AtomicInteger());

        assertTrue(skill.matches(
                "帮我策划一场明天在苏州举办、50人参加、预算2000元的技术分享会"));
    }

    @Test
    void executesScreenshotGoalThroughAgentWithRelativeDateAndDetailedBudget() {
        Clock clock = Clock.fixed(
                Instant.parse("2026-08-27T05:45:00Z"),
                ZoneId.of("Asia/Shanghai"));
        SkillRegistry registry = new SkillRegistry(List.of(skill(
                new AtomicInteger(), new CampusGoalParser(clock))));

        SkillExecution execution = registry.route(
                "帮我策划一场明天在苏州举办 五十人参加 预算2000元的技术分享会")
                .orElseThrow();
        String markdown = execution.reply();

        assertEquals("campus_planning_agent", execution.skillName());
        assertTrue(markdown.contains("# 技术分享会活动方案"));
        assertTrue(markdown.contains("| 日期 | 2026-08-28 |"));
        assertTrue(markdown.contains("| 饮水 | 瓶装水 | 55 | 瓶 | ¥2.00 | ¥110.00"));
        assertTrue(markdown.contains("单价是前期预算控制上限"));
        assertFalse(markdown.contains("是否取得真实报价"));
        assertFalse(markdown.contains("2024年8月9日"));
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
        assertTrue(result.content().contains("活动方案"));
        assertTrue(result.imagePrompt().contains("校园AI技术分享会"));
        assertEquals(1, weatherCalls.get());
        assertEquals(0, aiService.calls.get());
    }

    @Test
    void recommendsCampusAreaBeforeAskingForExactVenue() {
        SkillRegistry registry = new SkillRegistry(List.of(skill(new AtomicInteger())));

        String reply = registry.route("帮我策划一次校园趣味运动会").orElseThrow().reply();

        assertTrue(reply.contains("推荐区域：**操场或体育场"));
        assertTrue(reply.contains("具体楼号、教室号或场地名称可后续补充"));
        assertTrue(reply.contains("分组趣味项目与轮换比赛"));
        assertTrue(reply.contains("裁判与计分"));
        assertTrue(reply.contains("| 建议时长 | 2小时30分钟 |"));
        assertFalse(reply.contains("技术主题分享"));
    }

    @Test
    void rendersUserProvidedSchoolVenueAndStartTimeInTheUpdatedPlan() {
        SkillRegistry registry = new SkillRegistry(List.of(skill(new AtomicInteger())));

        String reply = registry.route(
                "帮我策划一次校园AI分享会，学校：南京信息工程大学，活动场地：明德楼，"
                        + "开始时间：15:30，50人参加，预算2000元")
                .orElseThrow().reply();

        assertTrue(reply.contains("| 位置 | 南京信息工程大学·明德楼 |"));
        assertTrue(reply.contains("推荐区域：**南京信息工程大学·明德楼"));
        assertTrue(reply.contains("建议时段：15:30—17:30"));
        assertTrue(reply.contains("已采用你在当前活动会话中补充的开始时间"));
        assertTrue(reply.contains("确认该场地的具体房间号、预约时段和设备开放情况"));
    }

    @Test
    void screenshotVenueFollowUpRegeneratesTheSavedPlanWithoutCallingWeather() {
        AtomicInteger weatherCalls = new AtomicInteger();
        CampusPlanningAgentSkill planningSkill = skill(weatherCalls);
        CampusConversationService conversations = new CampusConversationService(
                planningSkill,
                new CampusConversationUpdateParser(new CampusGoalParser()),
                120);
        MessageRouter router = new MessageRouter(
                new SkillRegistry(List.of(planningSkill)),
                new KeywordRagService(new RagConfig()),
                new FailIfCalledAiService(),
                conversations);

        router.route("wechat-user-1", "帮我策划一次校园技术活动", ReplyMode.TEXT);
        MessageRouteResult updated = router.route(
                "wechat-user-1", "我是在南京信息工程大学 明德楼举行", ReplyMode.TEXT);

        assertEquals("campus_conversation_update", updated.routeDetail());
        assertTrue(updated.content().contains("| 位置 | 南京信息工程大学明德楼 |"));
        assertTrue(updated.content().contains("推荐区域：**南京信息工程大学明德楼"));
        assertFalse(updated.content().contains("南京当前天气"));
        assertEquals(0, weatherCalls.get());
    }

    private CampusPlanningAgentSkill skill(AtomicInteger weatherCalls) {
        return skill(weatherCalls, new CampusGoalParser());
    }

    private CampusPlanningAgentSkill skill(
            AtomicInteger weatherCalls, CampusGoalParser goalParser) {
        ToolRegistry toolRegistry = new ToolRegistry(List.of(
                new EventBudgetTool(),
                new EventSupplyEstimateTool(),
                new FixedWeatherTool(weatherCalls)));
        DefaultCampusTaskRunner taskRunner = new DefaultCampusTaskRunner(
                new KeywordRagService(new RagConfig()), toolRegistry);
        CampusAgentOrchestrator orchestrator = new CampusAgentOrchestrator(
                goalParser,
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
            LocalDate eventDate = LocalDate.parse(arguments.path("event_date").asText());
            return """
                    {"success":true,"location":"苏州","event_date":"%s",
                     "status":"TOO_EARLY","forecast_available":false,
                     "recheck_on":"%s","final_check_on":"%s",
                     "message":"测试环境未调用真实天气接口，没有把实况冒充预报。",
                     "source":"测试天气工具",
                     "recommendations":["预留室内场地","在复查日期重新调用真实预报"]}
                    """.formatted(
                    eventDate,
                    eventDate.minusDays(14),
                    eventDate.minusDays(1));
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
