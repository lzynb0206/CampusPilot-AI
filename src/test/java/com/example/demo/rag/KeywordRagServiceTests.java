package com.example.demo.rag;

import com.example.demo.config.RagConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeywordRagServiceTests {
    @Test
    void sameQuestionMatchesWhenEnabledAndMissesWhenDisabled() {
        RagConfig config = new RagConfig();
        KeywordRagService service = new KeywordRagService(config);

        assertTrue(service.retrieve("RAG是什么，它有什么作用？").isPresent());

        config.setEnabled(false);
        assertFalse(service.retrieve("RAG是什么，它有什么作用？").isPresent());
    }

    @Test
    void buildsPromptWithRetrievedKnowledgeAndOriginalQuestion() {
        KeywordRagService service = new KeywordRagService(new RagConfig());
        RagContext context = service.retrieve("微信语音为什么需要SILK转WAV？").orElseThrow();

        String prompt = service.buildAugmentedPrompt(
                "微信语音为什么需要SILK转WAV？", context);

        assertTrue(prompt.contains("微信语音处理链路"));
        assertTrue(prompt.contains("silk-wasm"));
        assertTrue(prompt.contains("<user_question>"));
    }

    @Test
    void unrelatedQuestionDoesNotCreateContext() {
        KeywordRagService service = new KeywordRagService(new RagConfig());

        assertFalse(service.retrieve("给我讲一个睡前故事").isPresent());
    }

    @Test
    void campusGoalRetrievesClearlyMarkedTemplateInsteadOfFakeSchoolPolicy() {
        KeywordRagService service = new KeywordRagService(new RagConfig());

        RagContext context = service.retrieve("帮我策划校园AI技术分享会并检查活动审批").orElseThrow();
        RagHit approvalHit = context.hits().stream()
                .filter(hit -> hit.document().id().equals("campus-activity-approval-template"))
                .findFirst()
                .orElseThrow();
        String prompt = service.buildAugmentedPrompt("策划校园活动", context);

        assertEquals(KnowledgeStatus.TEMPLATE, approvalHit.document().status());
        assertTrue(approvalHit.document().source().contains("待替换"));
        assertTrue(prompt.contains("可信状态：TEMPLATE"));
        assertTrue(prompt.contains("不得把 TEMPLATE 描述成用户所在学校的真实规定"));
    }
}
