package com.example.demo.service.poster;

import com.example.demo.agent.campus.CampusGoalParser;
import com.example.demo.agent.campus.CampusPosterPromptBuilder;
import com.example.demo.config.AiConfig;
import com.example.demo.config.CampusPosterConfig;
import com.example.demo.service.ai.AlibabaAiService;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CampusPosterServiceTests {
    @Test
    void generatesANewBackgroundThenAppliesDeterministicTextLayout() throws Exception {
        CampusPosterConfig config = new CampusPosterConfig();
        config.setCanvasWidth(600);
        config.setCanvasHeight(800);
        CampusPosterRenderer renderer = new CampusPosterRenderer(config);
        StubAiService aiService = new StubAiService(background());
        CampusPosterService service = new CampusPosterService(
                aiService,
                renderer,
                new CampusPosterPromptBuilder(config),
                new CampusGoalParser());

        byte[] result = service.generateFromGoal(
                "请策划校园活动，活动名称：校园AI技术分享会，活动日期：2026-09-20，"
                        + "学校：南京信息工程大学，活动场地：明德楼，开始时间：15:30");

        assertTrue(aiService.prompt.contains("只生成背景"));
        assertFalse(aiService.prompt.contains("校园AI技术分享会"));
        BufferedImage poster = ImageIO.read(new ByteArrayInputStream(result));
        assertEquals(600, poster.getWidth());
        assertEquals(800, poster.getHeight());
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
        private String prompt;

        StubAiService(byte[] background) {
            super(new AiConfig(), new ToolCallingEngine(new ToolRegistry(List.of())),
                    new RestTemplate());
            this.background = background;
        }

        @Override
        public byte[] generatePosterBackground(String prompt) {
            this.prompt = prompt;
            return background;
        }
    }
}
