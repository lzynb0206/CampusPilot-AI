package com.example.demo.service.poster;

import com.example.demo.agent.campus.CampusPosterLayout;
import com.example.demo.agent.campus.CampusPosterSpec;
import com.example.demo.config.CampusPosterConfig;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Component
public class CampusPosterRenderer {
    private static final int MIN_CANVAS_EDGE = 600;
    private static final int MAX_CANVAS_EDGE = 3000;

    static {
        System.setProperty("java.awt.headless", "true");
    }

    private final CampusPosterConfig config;

    public CampusPosterRenderer(CampusPosterConfig config) {
        this.config = config;
    }

    public byte[] render(byte[] backgroundBytes, CampusPosterSpec spec) {
        if (backgroundBytes == null || backgroundBytes.length == 0) {
            throw new IllegalArgumentException("海报背景图片不能为空");
        }
        if (spec == null) {
            throw new IllegalArgumentException("海报内容不能为空");
        }
        BufferedImage background;
        try {
            background = ImageIO.read(new ByteArrayInputStream(backgroundBytes));
        } catch (IOException exception) {
            throw new IllegalArgumentException("无法读取海报背景图片", exception);
        }
        if (background == null) {
            throw new IllegalArgumentException("海报背景不是有效图片");
        }

        int width = clamp(config.getCanvasWidth(), MIN_CANVAS_EDGE, MAX_CANVAS_EDGE);
        int height = clamp(config.getCanvasHeight(), MIN_CANVAS_EDGE, MAX_CANVAS_EDGE);
        BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = canvas.createGraphics();
        try {
            applyQuality(graphics);
            drawCover(graphics, background, width, height);
            drawReadabilityLayers(graphics, width, height, spec.layout());
            drawHeader(graphics, width, height, spec);
            drawTitle(graphics, width, height, spec);
            drawInformationCard(graphics, width, height, spec);
        } finally {
            graphics.dispose();
        }

        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(canvas, "png", output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("海报图片编码失败", exception);
        }
    }

    private void applyQuality(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                RenderingHints.VALUE_STROKE_PURE);
    }

    private void drawCover(Graphics2D graphics, BufferedImage source, int width, int height) {
        double scale = Math.max(
                width / (double) source.getWidth(),
                height / (double) source.getHeight());
        int scaledWidth = (int) Math.ceil(source.getWidth() * scale);
        int scaledHeight = (int) Math.ceil(source.getHeight() * scale);
        int x = (width - scaledWidth) / 2;
        int y = (height - scaledHeight) / 2;
        graphics.drawImage(source, x, y, scaledWidth, scaledHeight, null);
    }

    private void drawReadabilityLayers(
            Graphics2D graphics, int width, int height, CampusPosterLayout layout) {
        Color top = layout == CampusPosterLayout.CINEMATIC
                ? new Color(9, 10, 28, 190) : new Color(5, 20, 38, 185);
        graphics.setPaint(new GradientPaint(
                0, 0, top,
                0, height * 0.56f, new Color(8, 14, 28, 12)));
        graphics.fillRect(0, 0, width, (int) (height * 0.62));

        graphics.setPaint(new GradientPaint(
                0, height * 0.45f, new Color(5, 10, 24, 0),
                0, height, new Color(5, 10, 24, 225)));
        graphics.fillRect(0, (int) (height * 0.42), width, (int) (height * 0.58));
    }

    private void drawHeader(Graphics2D graphics, int width, int height, CampusPosterSpec spec) {
        int margin = scale(width, 0.065);
        int top = scale(height, 0.055);
        int logoSize = scale(width, 0.085);
        int schoolX = margin;
        BufferedImage logo = loadSchoolLogo(spec.school());
        if (logo != null) {
            graphics.setColor(new Color(255, 255, 255, 232));
            graphics.fillRoundRect(margin, top, logoSize, logoSize,
                    scale(width, 0.018), scale(width, 0.018));
            drawContainedImage(graphics, logo, margin + 9, top + 9, logoSize - 18, logoSize - 18);
            schoolX += logoSize + scale(width, 0.025);
        }

        int pillHorizontal = scale(width, 0.026);
        Font categoryFont = font(Font.BOLD, scale(width, 0.026));
        graphics.setFont(categoryFont);
        FontMetrics categoryMetrics = graphics.getFontMetrics();
        int categoryWidth = categoryMetrics.stringWidth(spec.categoryLabel()) + pillHorizontal * 2;
        int categoryHeight = scale(width, 0.057);
        int categoryX = width - margin - categoryWidth;
        int categoryY = top + (logoSize - categoryHeight) / 2;
        Color accent = accent(spec.layout());
        graphics.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 225));
        graphics.fillRoundRect(categoryX, categoryY, categoryWidth, categoryHeight,
                categoryHeight, categoryHeight);
        graphics.setColor(new Color(7, 15, 29));
        graphics.drawString(spec.categoryLabel(), categoryX + pillHorizontal,
                categoryY + (categoryHeight - categoryMetrics.getHeight()) / 2
                        + categoryMetrics.getAscent());

        int schoolMaxWidth = Math.max(scale(width, 0.24),
                categoryX - schoolX - scale(width, 0.025));
        Font schoolFont = fitSingleLineFont(
                graphics, spec.school(), schoolMaxWidth,
                scale(width, 0.035), scale(width, 0.023), Font.BOLD);
        graphics.setFont(schoolFont);
        FontMetrics schoolMetrics = graphics.getFontMetrics();
        String schoolLabel = schoolMetrics.stringWidth(spec.school()) <= schoolMaxWidth
                ? spec.school()
                : ellipsize(spec.school(), schoolMetrics, schoolMaxWidth);
        graphics.setColor(Color.WHITE);
        drawShadowedString(graphics, schoolLabel, schoolX,
                top + (logoSize - schoolMetrics.getHeight()) / 2 + schoolMetrics.getAscent(),
                Color.WHITE, scale(width, 0.003));
    }

    private void drawTitle(Graphics2D graphics, int width, int height, CampusPosterSpec spec) {
        int margin = scale(width, 0.065);
        int titleTop = scale(height,
                spec.layout() == CampusPosterLayout.CINEMATIC ? 0.205 : 0.195);
        int maxWidth = width - margin * 2;
        int maxHeight = scale(height, 0.35);
        TextBlock title = fitTextBlock(
                graphics,
                spec.eventName(),
                maxWidth,
                maxHeight,
                scale(width, 0.12),
                scale(width, 0.055),
                2,
                3,
                Font.BOLD);

        int lineHeight = title.lineHeight();
        int totalHeight = title.lines().size() * lineHeight;
        int y = titleTop + Math.max(0, (maxHeight - totalHeight) / 2);
        graphics.setFont(title.font());
        FontMetrics metrics = graphics.getFontMetrics();
        for (String line : title.lines()) {
            int x = spec.layout() == CampusPosterLayout.CINEMATIC
                    ? (width - metrics.stringWidth(line)) / 2 : margin;
            int baseline = y + metrics.getAscent();
            drawShadowedString(graphics, line, x, baseline, Color.WHITE,
                    scale(width, 0.006));
            y += lineHeight;
        }

        Color accent = accent(spec.layout());
        int ruleY = Math.min(titleTop + maxHeight + scale(height, 0.018), scale(height, 0.61));
        graphics.setColor(accent);
        graphics.fillRoundRect(
                spec.layout() == CampusPosterLayout.CINEMATIC
                        ? (width - scale(width, 0.18)) / 2 : margin,
                ruleY,
                scale(width, 0.18),
                scale(height, 0.006),
                scale(height, 0.006),
                scale(height, 0.006));
    }

    private void drawInformationCard(
            Graphics2D graphics, int width, int height, CampusPosterSpec spec) {
        int margin = scale(width, 0.065);
        int cardX = margin;
        int cardY = scale(height, 0.68);
        int cardWidth = width - margin * 2;
        int cardHeight = scale(height, 0.255);
        int radius = scale(width, 0.035);
        graphics.setColor(new Color(5, 12, 27, 205));
        graphics.fillRoundRect(cardX, cardY, cardWidth, cardHeight, radius, radius);
        graphics.setColor(new Color(255, 255, 255, 48));
        graphics.setStroke(new BasicStroke(Math.max(1f, scale(width, 0.002))));
        graphics.drawRoundRect(cardX, cardY, cardWidth, cardHeight, radius, radius);

        int innerX = cardX + scale(width, 0.045);
        int innerRight = cardX + cardWidth - scale(width, 0.045);
        int top = cardY + scale(height, 0.035);
        int columnGap = scale(width, 0.055);
        int columnWidth = (innerRight - innerX - columnGap) / 2;
        drawFact(graphics, "日期", spec.date(), innerX, top, columnWidth, width);
        drawFact(graphics, "时间", spec.time(), innerX + columnWidth + columnGap,
                top, columnWidth, width);

        int separatorY = cardY + scale(height, 0.112);
        graphics.setColor(new Color(255, 255, 255, 45));
        graphics.fillRect(innerX, separatorY, innerRight - innerX, 1);

        Font labelFont = font(Font.PLAIN, scale(width, 0.024));
        graphics.setFont(labelFont);
        graphics.setColor(new Color(255, 255, 255, 165));
        graphics.drawString("地点", innerX, cardY + scale(height, 0.151));

        int locationX = innerX + scale(width, 0.095);
        int locationWidth = innerRight - locationX;
        TextBlock location = fitTextBlock(
                graphics,
                spec.location(),
                locationWidth,
                scale(height, 0.085),
                scale(width, 0.036),
                scale(width, 0.024),
                1,
                2,
                Font.BOLD);
        graphics.setFont(location.font());
        FontMetrics locationMetrics = graphics.getFontMetrics();
        int locationY = cardY + scale(height, 0.133) + locationMetrics.getAscent();
        for (String line : location.lines()) {
            graphics.setColor(Color.WHITE);
            graphics.drawString(line, locationX, locationY);
            locationY += location.lineHeight();
        }

        drawCallToAction(
                graphics, spec.callToAction(), innerRight, cardY, cardHeight, width, spec.layout());
    }

    private void drawFact(
            Graphics2D graphics,
            String label,
            String value,
            int x,
            int y,
            int maxWidth,
            int canvasWidth) {
        Font labelFont = font(Font.PLAIN, scale(canvasWidth, 0.022));
        graphics.setFont(labelFont);
        FontMetrics labelMetrics = graphics.getFontMetrics();
        graphics.setColor(new Color(255, 255, 255, 160));
        graphics.drawString(label, x, y + labelMetrics.getAscent());

        Font valueFont = fitSingleLineFont(
                graphics, value, maxWidth,
                scale(canvasWidth, 0.038), scale(canvasWidth, 0.025), Font.BOLD);
        graphics.setFont(valueFont);
        FontMetrics valueMetrics = graphics.getFontMetrics();
        graphics.setColor(Color.WHITE);
        graphics.drawString(value, x,
                y + labelMetrics.getHeight() + scale(canvasWidth, 0.008) + valueMetrics.getAscent());
    }

    private void drawCallToAction(
            Graphics2D graphics,
            String text,
            int right,
            int cardY,
            int cardHeight,
            int width,
            CampusPosterLayout layout) {
        Font buttonFont = font(Font.BOLD, scale(width, 0.025));
        graphics.setFont(buttonFont);
        FontMetrics metrics = graphics.getFontMetrics();
        int horizontal = scale(width, 0.025);
        int buttonWidth = metrics.stringWidth(text) + horizontal * 2;
        int buttonHeight = scale(width, 0.055);
        int x = right - buttonWidth;
        int y = cardY + cardHeight - buttonHeight - scale(width, 0.028);
        Color accent = accent(layout);
        graphics.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 235));
        graphics.fillRoundRect(x, y, buttonWidth, buttonHeight, buttonHeight, buttonHeight);
        graphics.setColor(new Color(4, 13, 26));
        graphics.drawString(text, x + horizontal,
                y + (buttonHeight - metrics.getHeight()) / 2 + metrics.getAscent());
    }

    private BufferedImage loadSchoolLogo(String school) {
        if (school == null || school.isBlank() || "校园活动".equals(school)) {
            return null;
        }
        String safeName = school.replaceAll("[\\\\/:*?\"<>|]", "").trim();
        if (safeName.isEmpty()) {
            return null;
        }
        String directory = config.getLogoResourceDirectory().replaceAll("^/+|/+$", "");
        for (String extension : List.of(".png", ".jpg", ".jpeg")) {
            ClassPathResource resource = new ClassPathResource(
                    directory + "/" + safeName + extension);
            if (!resource.exists()) {
                continue;
            }
            try (InputStream input = resource.getInputStream()) {
                return ImageIO.read(input);
            } catch (IOException ignored) {
                return null;
            }
        }
        return null;
    }

    private void drawContainedImage(
            Graphics2D graphics, BufferedImage image, int x, int y, int width, int height) {
        double scale = Math.min(
                width / (double) image.getWidth(),
                height / (double) image.getHeight());
        int targetWidth = (int) Math.round(image.getWidth() * scale);
        int targetHeight = (int) Math.round(image.getHeight() * scale);
        graphics.drawImage(image,
                x + (width - targetWidth) / 2,
                y + (height - targetHeight) / 2,
                targetWidth,
                targetHeight,
                null);
    }

    private TextBlock fitTextBlock(
            Graphics2D graphics,
            String text,
            int maxWidth,
            int maxHeight,
            int preferredSize,
            int minimumSize,
            int preferredLineLimit,
            int maxLines,
            int style) {
        for (int lineLimit = preferredLineLimit; lineLimit <= maxLines; lineLimit++) {
            for (int size = preferredSize; size >= minimumSize; size -= 2) {
                Font candidate = font(style, size);
                graphics.setFont(candidate);
                FontMetrics metrics = graphics.getFontMetrics();
                List<String> lines = wrap(text, metrics, maxWidth);
                int lineHeight = (int) Math.ceil(metrics.getHeight() * 1.12);
                if (lines.size() <= lineLimit && lines.size() * lineHeight <= maxHeight) {
                    return new TextBlock(candidate, lines, lineHeight);
                }
            }
        }
        Font fallback = font(style, minimumSize);
        graphics.setFont(fallback);
        FontMetrics metrics = graphics.getFontMetrics();
        List<String> lines = new ArrayList<>(wrap(text, metrics, maxWidth));
        if (lines.size() > maxLines) {
            lines = new ArrayList<>(lines.subList(0, maxLines));
            lines.set(maxLines - 1, ellipsize(lines.get(maxLines - 1), metrics, maxWidth));
        }
        return new TextBlock(fallback, lines, (int) Math.ceil(metrics.getHeight() * 1.12));
    }

    private Font fitSingleLineFont(
            Graphics2D graphics,
            String text,
            int maxWidth,
            int preferredSize,
            int minimumSize,
            int style) {
        for (int size = preferredSize; size >= minimumSize; size--) {
            Font candidate = font(style, size);
            graphics.setFont(candidate);
            if (graphics.getFontMetrics().stringWidth(text) <= maxWidth) {
                return candidate;
            }
        }
        return font(style, minimumSize);
    }

    private List<String> wrap(String value, FontMetrics metrics, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        value.codePoints().forEach(codePoint -> {
            String character = new String(Character.toChars(codePoint));
            if ("\n".equals(character)) {
                if (!current.isEmpty()) {
                    lines.add(current.toString());
                    current.setLength(0);
                }
                return;
            }
            String candidate = current + character;
            if (!current.isEmpty() && metrics.stringWidth(candidate) > maxWidth) {
                lines.add(current.toString());
                current.setLength(0);
            }
            current.append(character);
        });
        if (!current.isEmpty()) {
            lines.add(current.toString());
        }
        return lines.isEmpty() ? List.of("") : lines;
    }

    private String ellipsize(String value, FontMetrics metrics, int maxWidth) {
        String result = value;
        while (!result.isEmpty() && metrics.stringWidth(result + "…") > maxWidth) {
            result = result.substring(0, result.length() - 1);
        }
        return result + "…";
    }

    private void drawShadowedString(
            Graphics2D graphics,
            String text,
            int x,
            int baseline,
            Color color,
            int shadowOffset) {
        graphics.setComposite(AlphaComposite.SrcOver);
        graphics.setColor(new Color(0, 0, 0, 150));
        graphics.drawString(text, x + shadowOffset, baseline + shadowOffset);
        graphics.setColor(color);
        graphics.drawString(text, x, baseline);
    }

    private Font font(int style, int size) {
        return new Font(Font.SANS_SERIF, style, Math.max(10, size));
    }

    private Color accent(CampusPosterLayout layout) {
        return layout == CampusPosterLayout.CINEMATIC
                ? new Color(255, 196, 87) : new Color(94, 234, 212);
    }

    private int scale(int value, double factor) {
        return (int) Math.round(value * factor);
    }

    private int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private record TextBlock(Font font, List<String> lines, int lineHeight) {
    }
}
