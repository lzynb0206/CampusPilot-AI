package com.example.demo.service.poster;

import com.example.demo.agent.campus.CampusPosterLayout;
import com.example.demo.agent.campus.CampusPosterSpec;
import com.example.demo.config.CampusPosterConfig;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CampusPosterRendererTests {
    @Test
    void rendersExactTextLayoutOverAnAiBackground() throws Exception {
        CampusPosterConfig config = new CampusPosterConfig();
        config.setCanvasWidth(600);
        config.setCanvasHeight(800);
        CampusPosterRenderer renderer = new CampusPosterRenderer(config);
        byte[] background = gradientBackground(720, 960);
        CampusPosterSpec spec = new CampusPosterSpec(
                "全新无字背景",
                CampusPosterLayout.EDITORIAL,
                "技术分享",
                "校园人工智能技术与创新实践分享会",
                "南京信息工程大学",
                "2026年9月20日",
                "15:30开始",
                "南京信息工程大学·明德楼报告厅",
                "欢迎报名参加");

        byte[] poster = renderer.render(background, spec);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(poster));

        assertEquals(600, image.getWidth());
        assertEquals(800, image.getHeight());
        assertTrue(poster.length > 20_000);
        assertNotEquals(image.getRGB(10, 10), image.getRGB(300, 720));
    }

    @Test
    void rejectsInvalidBackgroundBytes() {
        CampusPosterRenderer renderer = new CampusPosterRenderer(new CampusPosterConfig());
        CampusPosterSpec spec = new CampusPosterSpec(
                "无字背景", CampusPosterLayout.CINEMATIC, "校园演出", "迎新晚会",
                "校园活动", "日期待定", "时间待定", "校内·具体场地待定", "欢迎报名参加");

        assertThrows(IllegalArgumentException.class,
                () -> renderer.render(new byte[]{1, 2, 3}, spec));
    }

    private byte[] gradientBackground(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        for (int y = 0; y < height; y++) {
            float ratio = y / (float) height;
            graphics.setColor(new Color(
                    (int) (30 + 80 * ratio),
                    (int) (80 + 60 * ratio),
                    (int) (150 + 70 * ratio)));
            graphics.drawLine(0, y, width, y);
        }
        graphics.dispose();
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        }
    }
}
