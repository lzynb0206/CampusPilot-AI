package com.example.demo.service.routing;

import com.example.demo.model.ActionType;
import com.example.demo.model.IntentResult;
import com.example.demo.model.MessageRouteResult;
import com.example.demo.model.MessageRouteType;
import com.example.demo.model.ReplyMode;
import com.example.demo.rag.KeywordRagService;
import com.example.demo.rag.RagContext;
import com.example.demo.service.ai.AlibabaAiService;
import com.example.demo.skill.SkillExecution;
import com.example.demo.skill.SkillRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Optional;

@Slf4j
@Service
public class MessageRouter {
    private final SkillRegistry skillRegistry;
    private final KeywordRagService ragService;
    private final AlibabaAiService aiService;
    private final CampusConversationService campusConversationService;

    @Autowired
    public MessageRouter(
            SkillRegistry skillRegistry,
            KeywordRagService ragService,
            AlibabaAiService aiService,
            CampusConversationService campusConversationService) {
        this.skillRegistry = skillRegistry;
        this.ragService = ragService;
        this.aiService = aiService;
        this.campusConversationService = campusConversationService;
    }

    public MessageRouter(
            SkillRegistry skillRegistry,
            KeywordRagService ragService,
            AlibabaAiService aiService) {
        this(skillRegistry, ragService, aiService, null);
    }

    public MessageRouteResult route(String userMessage, ReplyMode defaultReplyMode) {
        return route(null, userMessage, defaultReplyMode);
    }

    public MessageRouteResult route(
            String conversationId, String userMessage, ReplyMode defaultReplyMode) {
        if (!StringUtils.hasText(userMessage)) {
            throw new IllegalArgumentException("消息内容不能为空");
        }

        ReplyMode directReplyMode = resolveDirectReplyMode(userMessage, defaultReplyMode);
        CampusConversationContext activeCampusContext = null;
        if (campusConversationService != null && StringUtils.hasText(conversationId)) {
            Optional<CampusConversationReply> conversationReply =
                    campusConversationService.handle(conversationId, userMessage);
            if (conversationReply.isPresent()) {
                CampusConversationReply reply = conversationReply.get();
                log.info("消息路由命中校园活动会话 detail={}", reply.detail());
                return new MessageRouteResult(
                        MessageRouteType.SKILL,
                        ActionType.CHAT,
                        directReplyMode,
                        reply.content(),
                        reply.detail(),
                        reply.imagePrompt());
            }
            activeCampusContext = campusConversationService.contextFor(conversationId).orElse(null);
        }
        Optional<SkillExecution> skillExecution = skillRegistry.route(userMessage);
        if (skillExecution.isPresent()) {
            SkillExecution execution = skillExecution.get();
            log.info("消息路由命中Skill skill={}", execution.skillName());
            return new MessageRouteResult(
                    MessageRouteType.SKILL,
                    ActionType.CHAT,
                    directReplyMode,
                    execution.reply(),
                    execution.skillName(),
                    execution.imagePrompt());
        }

        if (isCampusPosterRequest(userMessage) && hasExplicitImageGenerationSignal(userMessage)) {
            log.info("消息路由命中校园活动海报生成指令");
            return new MessageRouteResult(
                    MessageRouteType.LLM,
                    ActionType.IMAGE_GENERATION,
                    ReplyMode.TEXT,
                    withCampusContext(activeCampusContext, userMessage),
                    "campus_poster_generation");
        }

        Optional<RagContext> ragContext = ragService.retrieve(userMessage);
        if (ragContext.isPresent()) {
            String augmentedPrompt = ragService.buildAugmentedPrompt(
                    userMessage, ragContext.get());
            augmentedPrompt = withCampusContext(activeCampusContext, augmentedPrompt);
            String reply = aiService.chatWithTools(augmentedPrompt);
            String documentIds = ragContext.get().hits().stream()
                    .map(hit -> hit.document().id())
                    .reduce((left, right) -> left + "," + right)
                    .orElse("");
            log.info("消息路由命中RAG documents={}", documentIds);
            return new MessageRouteResult(
                    MessageRouteType.RAG,
                    ActionType.CHAT,
                    directReplyMode,
                    reply,
                    documentIds);
        }

        IntentResult intent = aiService.recognizeIntent(userMessage, defaultReplyMode);
        if (intent.action() == ActionType.WEATHER && !hasExplicitWeatherSignal(userMessage)) {
            log.warn("路由器拒绝无天气词的WEATHER误判");
            intent = new IntentResult(ActionType.CHAT, intent.replyMode(), userMessage, "");
        }
        if (intent.action() == ActionType.IMAGE_GENERATION) {
            log.info("消息路由进入LLM生图意图");
            String imagePrompt = shouldUseCampusContextForImage(userMessage)
                    ? withCampusContext(activeCampusContext, intent.content())
                    : intent.content();
            return new MessageRouteResult(
                    MessageRouteType.LLM,
                    ActionType.IMAGE_GENERATION,
                    ReplyMode.TEXT,
                    imagePrompt,
                    isCampusPosterRequest(userMessage)
                            ? "campus_poster_generation" : "image_generation");
        }

        String weatherLocation = intent.location();
        if (intent.action() == ActionType.WEATHER && !StringUtils.hasText(weatherLocation)
                && activeCampusContext != null && StringUtils.hasText(activeCampusContext.city())) {
            weatherLocation = activeCampusContext.city();
        }
        String prompt = intent.action() == ActionType.WEATHER
                ? "请查询“" + weatherLocation + "”的当前天气。用户原始问题：" + intent.content()
                : intent.content();
        prompt = withCampusContext(activeCampusContext, prompt);
        String reply = aiService.chatWithTools(prompt);
        log.info("消息路由进入LLM兜底 action={}", intent.action());
        return new MessageRouteResult(
                MessageRouteType.LLM,
                ActionType.CHAT,
                intent.replyMode(),
                reply,
                intent.action().name().toLowerCase(Locale.ROOT));
    }

    private ReplyMode resolveDirectReplyMode(String message, ReplyMode fallback) {
        String lower = message.toLowerCase(Locale.ROOT);
        ReplyMode result = lower.contains("语音") || lower.contains("朗读")
                || lower.contains("说出来") || lower.contains("读出来")
                ? ReplyMode.VOICE
                : fallback;
        if (lower.contains("文字回复") || lower.contains("用文字") || lower.contains("打字")) {
            result = ReplyMode.TEXT;
        }
        return result;
    }

    private boolean hasExplicitWeatherSignal(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("天气") || lower.contains("气温") || lower.contains("温度")
                || lower.contains("下雨") || lower.contains("降雨") || lower.contains("雨天")
                || lower.contains("几度") || lower.contains("冷不冷") || lower.contains("热不热");
    }

    private String withCampusContext(CampusConversationContext context, String prompt) {
        if (context == null) {
            return prompt;
        }
        return """
                <campus_activity_context>
                %s
                </campus_activity_context>
                上面是同一微信联系人当前保存的校园活动信息，用于理解“这个活动、那里、原方案”等指代。
                如果当前消息与活动无关，直接回答当前消息，不要强行关联活动。
                <current_message>
                %s
                </current_message>
                """.formatted(context.summary(), prompt);
    }

    private boolean shouldUseCampusContextForImage(String message) {
        String normalized = message.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        return normalized.contains("这个活动") || normalized.contains("本次活动")
                || normalized.contains("活动海报") || normalized.contains("按这个")
                || normalized.contains("根据方案") || normalized.contains("原方案");
    }

    private boolean isCampusPosterRequest(String message) {
        String normalized = message.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        return normalized.contains("海报")
                && (normalized.contains("活动") || normalized.contains("校园")
                || normalized.contains("方案"));
    }

    private boolean hasExplicitImageGenerationSignal(String message) {
        String normalized = message.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        boolean requestsCreation = normalized.contains("生成") || normalized.contains("制作")
                || normalized.contains("设计") || normalized.contains("画一张")
                || normalized.contains("帮我画");
        return requestsCreation && (normalized.contains("海报") || normalized.contains("图片")
                || normalized.contains("插画"));
    }
}
