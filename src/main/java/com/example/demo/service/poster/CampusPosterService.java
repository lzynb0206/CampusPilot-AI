package com.example.demo.service.poster;

import com.example.demo.agent.campus.CampusGoalParser;
import com.example.demo.agent.campus.CampusPosterPromptBuilder;
import com.example.demo.agent.campus.CampusPosterSpec;
import com.example.demo.config.CampusPosterConfig;
import com.example.demo.service.ai.AlibabaAiService;
import com.example.demo.service.ai.PosterBackgroundReview;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class CampusPosterService {
    private final AlibabaAiService aiService;
    private final CampusPosterRenderer renderer;
    private final CampusPosterPromptBuilder promptBuilder;
    private final CampusGoalParser goalParser;
    private final CampusPosterConfig config;

    public CampusPosterService(
            AlibabaAiService aiService,
            CampusPosterRenderer renderer,
            CampusPosterPromptBuilder promptBuilder,
            CampusGoalParser goalParser,
            CampusPosterConfig config) {
        this.aiService = aiService;
        this.renderer = renderer;
        this.promptBuilder = promptBuilder;
        this.goalParser = goalParser;
        this.config = config;
    }

    public byte[] generate(CampusPosterSpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("当前没有可生成海报的活动信息");
        }
        int attempts = config.isBackgroundQualityReviewEnabled()
                ? clamp(config.getMaxBackgroundAttempts(), 1, 4) : 1;
        String lastReason = "未执行视觉质检";
        for (int attempt = 1; attempt <= attempts; attempt++) {
            byte[] background = aiService.generatePosterBackground(spec.backgroundPrompt());
            if (!config.isBackgroundQualityReviewEnabled()) {
                return renderer.render(background, spec);
            }

            PosterBackgroundReview review;
            try {
                review = aiService.reviewPosterBackground(background);
            } catch (Exception exception) {
                throw new IllegalStateException(
                        "海报背景质检暂时不可用，已停止发送未经检查的图片", exception);
            }
            lastReason = review.reason();
            log.info(
                    "海报背景质检 attempt={} textDetected={} qualityScore={} reason={}",
                    attempt,
                    review.textDetected(),
                    review.qualityScore(),
                    review.reason());
            if (review.passes(clamp(config.getMinimumBackgroundScore(), 0, 100))) {
                return renderer.render(background, spec);
            }
        }
        throw new IllegalStateException(
                "连续%d次背景质检未通过（%s），已停止发送以避免乱码或低质海报"
                        .formatted(attempts, lastReason));
    }

    public byte[] generateFromGoal(String goalDescription) {
        CampusPosterSpec spec = promptBuilder.build(goalParser.parse(goalDescription));
        if (spec == null) {
            throw new IllegalStateException("校园活动海报功能当前未启用");
        }
        return generate(spec);
    }

    private int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
