package com.example.demo.service.routing;

import com.example.demo.config.AiConfig;
import com.example.demo.config.RagConfig;
import com.example.demo.agent.campus.CampusGoalParser;
import com.example.demo.model.ActionType;
import com.example.demo.model.IntentResult;
import com.example.demo.model.MessageRouteResult;
import com.example.demo.model.MessageRouteType;
import com.example.demo.model.ReplyMode;
import com.example.demo.rag.KeywordRagService;
import com.example.demo.service.ai.AlibabaAiService;
import com.example.demo.skill.BotSkill;
import com.example.demo.skill.CampusVenueUpdateSkill;
import com.example.demo.skill.CampusPlanningAgentSkill;
import com.example.demo.skill.SkillRegistry;
import com.example.demo.tool.ToolCallingEngine;
import com.example.demo.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageRouterTests {
    @Test
    void skillHasPriorityOverRagAndLlm() {
        StubAiService aiService = new StubAiService();
        MessageRouter router = router(
                new RagConfig(),
                List.of(new FixedSkill("每日简报", "Skill直接回复")),
                aiService);

        MessageRouteResult result = router.route(
                "生成每日简报，并介绍一下RAG", ReplyMode.TEXT);

        assertEquals(MessageRouteType.SKILL, result.routeType());
        assertEquals("Skill直接回复", result.content());
        assertEquals(0, aiService.intentCalls.get());
        assertEquals(0, aiService.chatCalls.get());
    }

    @Test
    void ragEnhancesPromptBeforeCallingLlm() {
        StubAiService aiService = new StubAiService();
        MessageRouter router = router(new RagConfig(), List.of(), aiService);

        MessageRouteResult result = router.route("RAG是什么？", ReplyMode.TEXT);

        assertEquals(MessageRouteType.RAG, result.routeType());
        assertEquals(0, aiService.intentCalls.get());
        assertEquals(1, aiService.chatCalls.get());
        assertTrue(aiService.lastPrompt.contains("<retrieved_knowledge>"));
        assertTrue(aiService.lastPrompt.contains("RAG 是 Retrieval-Augmented Generation"));
    }

    @Test
    void disabledRagFallsBackToDirectLlmRoute() {
        RagConfig config = new RagConfig();
        config.setEnabled(false);
        StubAiService aiService = new StubAiService();
        MessageRouter router = router(config, List.of(), aiService);

        MessageRouteResult result = router.route("RAG是什么？", ReplyMode.TEXT);

        assertEquals(MessageRouteType.LLM, result.routeType());
        assertEquals(1, aiService.intentCalls.get());
        assertEquals(1, aiService.chatCalls.get());
        assertFalse(aiService.lastPrompt.contains("<retrieved_knowledge>"));
        assertEquals("RAG是什么？", aiService.lastPrompt);
    }

    @Test
    void unmatchedImageRequestKeepsOriginalImageGenerationRoute() {
        StubAiService aiService = new StubAiService();
        aiService.nextIntent = new IntentResult(
                ActionType.IMAGE_GENERATION, ReplyMode.TEXT, "雨中的西湖", "");
        MessageRouter router = router(new RagConfig(), List.of(), aiService);

        MessageRouteResult result = router.route("生成一张雨中的西湖", ReplyMode.TEXT);

        assertEquals(MessageRouteType.LLM, result.routeType());
        assertEquals(ActionType.IMAGE_GENERATION, result.action());
        assertEquals("雨中的西湖", result.content());
        assertEquals(0, aiService.chatCalls.get());
    }

    @Test
    void explicitCampusPosterRequestUsesPosterGenerationRoute() {
        StubAiService aiService = new StubAiService();
        aiService.nextIntent = new IntentResult(
                ActionType.IMAGE_GENERATION, ReplyMode.TEXT, "校园技术活动海报", "");
        MessageRouter router = router(new RagConfig(), List.of(), aiService);

        MessageRouteResult result = router.route("生成一张校园活动海报", ReplyMode.TEXT);

        assertEquals(ActionType.IMAGE_GENERATION, result.action());
        assertEquals("campus_poster_generation", result.routeDetail());
        assertEquals(0, aiService.intentCalls.get());
        assertEquals(0, aiService.chatCalls.get());
    }

    @Test
    void rejectsWeatherClassificationWhenMessageOnlyContainsACity() {
        StubAiService aiService = new StubAiService();
        aiService.nextIntent = new IntentResult(
                ActionType.WEATHER, ReplyMode.TEXT,
                "我是在南京信息工程大学明德楼举行", "南京");
        MessageRouter router = router(new RagConfig(), List.of(), aiService);

        MessageRouteResult result = router.route(
                "我是在南京信息工程大学 明德楼举行", ReplyMode.TEXT);

        assertEquals(ActionType.CHAT, result.action());
        assertEquals("chat", result.routeDetail());
        assertEquals("我是在南京信息工程大学 明德楼举行", aiService.lastPrompt);
        assertFalse(aiService.lastPrompt.contains("请查询“南京”的当前天气"));
    }

    @Test
    void campusVenueSupplementIsHandledBeforeIntentClassification() {
        StubAiService aiService = new StubAiService();
        MessageRouter router = router(
                new RagConfig(), List.of(new CampusVenueUpdateSkill()), aiService);

        MessageRouteResult result = router.route(
                "我是在南京信息工程大学 明德楼举行", ReplyMode.TEXT);

        assertEquals(MessageRouteType.SKILL, result.routeType());
        assertEquals("campus_venue_update", result.routeDetail());
        assertTrue(result.content().contains("南京信息工程大学明德楼"));
        assertFalse(result.content().contains("天气"));
        assertEquals(0, aiService.intentCalls.get());
        assertEquals(0, aiService.chatCalls.get());
    }

    @Test
    void conversationIdConnectsCampusFollowUpsBeforeOtherRoutes() {
        StubAiService aiService = new StubAiService();
        Clock clock = Clock.fixed(Instant.parse("2026-08-27T05:30:00Z"), ZoneId.of("UTC"));
        CampusConversationService conversations = new CampusConversationService(
                new EchoPlanningSkill(),
                new CampusConversationUpdateParser(new CampusGoalParser(
                        clock.withZone(ZoneId.of("Asia/Shanghai")))),
                clock,
                Duration.ofHours(2));
        MessageRouter router = new MessageRouter(
                new SkillRegistry(List.of()),
                new KeywordRagService(new RagConfig()),
                aiService,
                conversations);

        router.route("wechat-user-1", "帮我策划一次校园技术活动", ReplyMode.TEXT);
        MessageRouteResult result = router.route(
                "wechat-user-1", "改到西区食堂三楼，人数改80", ReplyMode.TEXT);

        assertEquals(MessageRouteType.SKILL, result.routeType());
        assertEquals("campus_conversation_update", result.routeDetail());
        assertTrue(result.content().contains("活动场地：西区食堂三楼"));
        assertTrue(result.content().contains("参与人数：80人"));
        assertEquals(0, aiService.intentCalls.get());
        assertEquals(0, aiService.chatCalls.get());
    }

    @Test
    void posterRetryUsesTheSavedCampusPlanInsteadOfRag() {
        StubAiService aiService = new StubAiService();
        Clock clock = Clock.fixed(Instant.parse("2026-08-27T05:30:00Z"), ZoneId.of("UTC"));
        CampusConversationService conversations = new CampusConversationService(
                new EchoPlanningSkill(),
                new CampusConversationUpdateParser(new CampusGoalParser(
                        clock.withZone(ZoneId.of("Asia/Shanghai")))),
                clock,
                Duration.ofHours(2));
        MessageRouter router = new MessageRouter(
                new SkillRegistry(List.of()),
                new KeywordRagService(new RagConfig()),
                aiService,
                conversations);

        router.route("wechat-user-1", "帮我策划一次校园技术活动", ReplyMode.TEXT);
        MessageRouteResult result = router.route(
                "wechat-user-1", "根据方案生成活动海报", ReplyMode.TEXT);

        assertEquals(ActionType.IMAGE_GENERATION, result.action());
        assertEquals("campus_poster_generation", result.routeDetail());
        assertTrue(result.content().contains("<campus_activity_context>"));
        assertTrue(result.content().contains("活动名称：校园技术活动"));
        assertEquals(0, aiService.intentCalls.get());
        assertEquals(0, aiService.chatCalls.get());
    }

    @Test
    void ordinaryFollowUpReceivesTheSavedCampusContext() {
        StubAiService aiService = new StubAiService();
        Clock clock = Clock.fixed(Instant.parse("2026-08-27T05:30:00Z"), ZoneId.of("UTC"));
        CampusConversationService conversations = new CampusConversationService(
                new EchoPlanningSkill(),
                new CampusConversationUpdateParser(new CampusGoalParser(
                        clock.withZone(ZoneId.of("Asia/Shanghai")))),
                clock,
                Duration.ofHours(2));
        RagConfig ragConfig = new RagConfig();
        ragConfig.setEnabled(false);
        MessageRouter router = new MessageRouter(
                new SkillRegistry(List.of()),
                new KeywordRagService(ragConfig),
                aiService,
                conversations);

        router.route("wechat-user-1", "帮我策划一次校园技术活动", ReplyMode.TEXT);
        router.route("wechat-user-1", "这个活动还有什么风险？", ReplyMode.TEXT);

        assertTrue(aiService.lastPrompt.contains("<campus_activity_context>"));
        assertTrue(aiService.lastPrompt.contains("活动名称：校园技术活动"));
        assertTrue(aiService.lastPrompt.contains("这个活动还有什么风险？"));
    }

    private MessageRouter router(
            RagConfig ragConfig,
            List<BotSkill> skills,
            StubAiService aiService) {
        return new MessageRouter(
                new SkillRegistry(skills),
                new KeywordRagService(ragConfig),
                aiService);
    }

    private record FixedSkill(String keyword, String reply) implements BotSkill {
        @Override
        public String name() {
            return "fixed_skill";
        }

        @Override
        public String description() {
            return "固定回复测试Skill";
        }

        @Override
        public List<String> keywords() {
            return List.of(keyword);
        }

        @Override
        public String execute(String userMessage) {
            return reply;
        }
    }

    private static class EchoPlanningSkill extends CampusPlanningAgentSkill {
        EchoPlanningSkill() {
            super(null, null);
        }

        @Override
        public boolean matches(String userMessage) {
            return userMessage.contains("帮我策划") && userMessage.contains("活动");
        }

        @Override
        public String execute(String userMessage) {
            return userMessage;
        }
    }

    private static class StubAiService extends AlibabaAiService {
        private final AtomicInteger intentCalls = new AtomicInteger();
        private final AtomicInteger chatCalls = new AtomicInteger();
        private IntentResult nextIntent;
        private String lastPrompt = "";

        StubAiService() {
            super(new AiConfig(), new ToolCallingEngine(new ToolRegistry(List.of())),
                    new RestTemplate());
        }

        @Override
        public IntentResult recognizeIntent(String text, ReplyMode defaultReplyMode) {
            intentCalls.incrementAndGet();
            return nextIntent == null
                    ? new IntentResult(ActionType.CHAT, defaultReplyMode, text, "")
                    : nextIntent;
        }

        @Override
        public String chatWithTools(String userPrompt) {
            chatCalls.incrementAndGet();
            lastPrompt = userPrompt;
            return "LLM回复";
        }
    }
}
