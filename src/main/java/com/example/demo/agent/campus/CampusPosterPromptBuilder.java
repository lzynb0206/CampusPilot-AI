package com.example.demo.agent.campus;

import com.example.demo.config.CampusPosterConfig;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
public class CampusPosterPromptBuilder {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy年M月d日");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final List<String> SPORTS_SIGNALS = List.of(
            "运动", "跑步", "球赛", "趣味运动", "体育", "户外");
    private static final List<String> PERFORMANCE_SIGNALS = List.of(
            "晚会", "演出", "音乐", "舞蹈", "文艺", "戏剧");
    private static final List<String> TECHNOLOGY_SIGNALS = List.of(
            "AI", "人工智能", "技术", "科技", "编程", "创新", "开发");

    private final CampusPosterConfig config;

    public CampusPosterPromptBuilder(CampusPosterConfig config) {
        this.config = config;
    }

    public CampusPosterSpec build(CampusAgentRun run) {
        if (!config.isEnabled() || run == null
                || run.status() != CampusAgentRunStatus.COMPLETED) {
            return null;
        }
        return build(run.goal());
    }

    public CampusPosterSpec build(CampusEventGoal goal) {
        if (!config.isEnabled() || goal == null) {
            return null;
        }
        String eventName = posterText(goal.eventName(), "校园主题活动", 60);
        String date = goal.eventDate() == null
                ? "日期待定" : goal.eventDate().format(DATE_FORMAT);
        String time = goal.startTime() == null
                ? "时间待定" : goal.startTime().format(TIME_FORMAT) + "开始";
        String location = posterLocation(goal);
        PosterTheme theme = posterTheme(goal.rawGoal());
        String composition = theme.layout() == CampusPosterLayout.CINEMATIC
                ? "画面上方和中央保留低细节的标题安全区，下方保留较暗、平整的信息安全区"
                : "画面上方保留简洁品牌安全区，中上部保留大面积低细节标题区，下方保留平整信息区";
        String backgroundPrompt = """
                生成一张全新的竖版校园活动海报背景，画面比例约3:4，只生成背景，不生成成品海报文字。
                采用%s。%s。
                每次使用不同的光影、纹理、抽象元素和空间构图，保持同类视觉语言但不要复制固定背景。
                背景要精致、有层次、适合手机端发布，同时让后续程序叠加的中文标题和活动信息清楚易读。
                禁止出现任何中文、英文、字母、数字、伪文字、乱码、Logo、校徽、二维码、网址、电话、
                水印、边框样机、标题占位符、信息图标或可识别的学校名称。只输出完整无字背景图。
                """.formatted(theme.style(), composition).trim();

        return new CampusPosterSpec(
                backgroundPrompt,
                theme.layout(),
                theme.categoryLabel(),
                eventName,
                posterText(goal.school(), "校园活动", 50),
                date,
                time,
                location,
                "欢迎报名参加");
    }

    private String posterLocation(CampusEventGoal goal) {
        if (goal.venue() != null) {
            if (goal.school() == null || goal.venue().contains(goal.school())) {
                return posterText(goal.venue(), "具体场地待定", 80);
            }
            return posterText(goal.school() + "·" + goal.venue(), "具体场地待定", 80);
        }
        if (goal.school() != null) {
            return posterText(goal.school() + "·具体场地待定", "具体场地待定", 80);
        }
        if (goal.city() != null) {
            return posterText(goal.city() + "校内·具体场地待定", "具体场地待定", 80);
        }
        return "校内·具体场地待定";
    }

    private PosterTheme posterTheme(String rawGoal) {
        if (containsAny(rawGoal, SPORTS_SIGNALS)) {
            return new PosterTheme(
                    CampusPosterLayout.CINEMATIC,
                    "运动赛事",
                    "活力运动视觉：橙色与翠绿色为主色，抽象速度轨迹、开阔校园空间和充满力量的光影");
        }
        if (containsAny(rawGoal, PERFORMANCE_SIGNALS)) {
            return new PosterTheme(
                    CampusPosterLayout.CINEMATIC,
                    "校园演出",
                    "校园演出视觉：深紫与暖金为主色，抽象舞台光束、流动声波和艺术笔触，热烈但不浮夸");
        }
        if (containsAnyIgnoreCase(rawGoal, TECHNOLOGY_SIGNALS)) {
            return new PosterTheme(
                    CampusPosterLayout.EDITORIAL,
                    "技术分享",
                    "未来科技视觉：深蓝与电光紫为主色，抽象网格、柔和发光线条和现代校园建筑光影，专业而年轻");
        }
        return new PosterTheme(
                CampusPosterLayout.EDITORIAL,
                "校园活动",
                "现代校园视觉：明亮蓝绿配色，简洁几何层次、自然光和清新的校园空间，友好而有组织感");
    }

    private boolean containsAny(String value, List<String> signals) {
        return value != null && signals.stream().anyMatch(value::contains);
    }

    private boolean containsAnyIgnoreCase(String value, List<String> signals) {
        if (value == null) {
            return false;
        }
        String normalized = value.toLowerCase();
        return signals.stream().map(String::toLowerCase).anyMatch(normalized::contains);
    }

    private String posterText(String value, String fallback, int maximumLength) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = value.replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("[<>]", "")
                .replaceAll("\\s+", " ")
                .trim();
        return normalized.length() <= maximumLength
                ? normalized : normalized.substring(0, maximumLength);
    }

    private record PosterTheme(
            CampusPosterLayout layout,
            String categoryLabel,
            String style) {
    }
}
