package com.example.demo.service.poster;

import com.example.demo.agent.campus.CampusGoalParser;
import com.example.demo.agent.campus.CampusPosterPromptBuilder;
import com.example.demo.config.AiConfig;
import com.example.demo.config.CampusPosterConfig;
import com.example.demo.service.ai.AlibabaAiService;
import com.example.demo.service.ai.PosterBackgroundReview;
import com.example.demo.tool.ToolCallingEngine;
import com.example.demo.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CampusPosterServiceTests {
    @Test
    void generatesANewBackgroundThenAppliesDeterministicTextLayout() throws Exception {
        CampusPosterConfig config = new CampusPosterConfig();
        config.setCanvasWidth(600);
        config.setCanvasHeight(800);
        CampusPosterRenderer renderer = new CampusPosterRenderer(config);
        StubAiService aiService = new StubAiService(
                background(),
                new PosterBackgroundReview(false, 91, "构图干净精致"));
        CampusPosterService service = new CampusPosterService(
                aiService,
                renderer,
                new CampusPosterPromptBuilder(config),
                new CampusGoalParser(),
                config);

        byte[] result = service.generateFromGoal(
                "请策划校园活动，活动名称：校园AI技术分享会，活动日期：2026-09-20，"
                        + "学校：南京信息工程大学，活动场地：明德楼，开始时间：15:30");

        assertTrue(aiService.prompt.contains("供设计师后续排版的背景板"));
        assertFalse(aiService.prompt.contains("校园AI技术分享会"));
        BufferedImage poster = ImageIO.read(new ByteArrayInputStream(result));
        assertEquals(600, poster.getWidth());
        assertEquals(800, poster.getHeight());
    }

    @Test
    void retriesUntilTheBackgroundHasNoTextAndPassesQualityReview() throws Exception {
        CampusPosterConfig config = new CampusPosterConfig();
        config.setCanvasWidth(600);
        config.setCanvasHeight(800);
        CampusPosterRenderer renderer = new CampusPosterRenderer(config);
        StubAiService aiService = new StubAiService(
                background(),
                new PosterBackgroundReview(true, 80, "中央存在伪文字"),
                new PosterBackgroundReview(false, 88, "背景干净且层次清楚"));
        CampusPosterService service = new CampusPosterService(
                aiService,
                renderer,
                new CampusPosterPromptBuilder(config),
                new CampusGoalParser(),
                config);

        byte[] poster = service.generateFromGoal("策划南京信息工程大学校园技术分享会");

        assertEquals(2, aiService.generationCount);
        assertTrue(poster.length > 20_000);
    }

    @Test
    void refusesToSendABackgroundThatFailsEveryQualityReview() throws Exception {
        CampusPosterConfig config = new CampusPosterConfig();
        config.setMaxBackgroundAttempts(2);
        CampusPosterRenderer renderer = new CampusPosterRenderer(config);
        StubAiService aiService = new StubAiService(
                background(),
                new PosterBackgroundReview(true, 60, "含有乱码"),
                new PosterBackgroundReview(false, 45, "构图粗糙"));
        CampusPosterService service = new CampusPosterService(
                aiService,
                renderer,
                new CampusPosterPromptBuilder(config),
                new CampusGoalParser(),
                config);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.generateFromGoal("策划南京信息工程大学校园技术分享会"));

        assertEquals(2, aiService.generationCount);
        assertTrue(error.getMessage().contains("停止发送"));
    }

    private byte[] background() throws Exception {
        BufferedImage image = new BufferedImage(720, 960, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(33, 55, 120));
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.dispose();
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        }
    }

    private static class StubAiService extends AlibabaAiService {
        private final byte[] background;
        private final Deque<PosterBackgroundReview> reviews;
        private String prompt;
        private int generationCount;

        StubAiService(byte[] background, PosterBackgroundReview... reviews) {
            super(new AiConfig(), new ToolCallingEngine(new ToolRegistry(List.of())),
                    new RestTemplate());
            this.background = background;
            this.reviews = new ArrayDeque<>(Arrays.asList(reviews));
        }

        @Override
        public byte[] generatePosterBackground(String prompt) {
            this.prompt = prompt;
            generationCount++;
            return background;
        }

        @Override
        public PosterBackgroundReview reviewPosterBackground(byte[] imageBytes) {
            return reviews.removeFirst();
        }
    }
}
