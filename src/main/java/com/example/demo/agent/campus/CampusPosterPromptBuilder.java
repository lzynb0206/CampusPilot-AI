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
                ? "上方10%保持安静，中央20%到58%为低细节主标题留白区，下方68%到96%为平整深色信息区"
                : "上方10%保持安静，中上部20%到58%为大面积低细节主标题留白区，下方68%到96%为平整信息区";
        String backgroundPrompt = """
                创作一张全新的3:4竖版高端校园活动视觉底图，只是供设计师后续排版的背景板，绝不是成品海报。
                艺术指导：%s。版式约束：%s。
                采用克制的色彩、真实材质感、细腻光影与清晰空间层次；视觉焦点放在标题留白区外围，
                不使用廉价素材堆叠、赛博朋克道路网格、发光地球、舞台大屏、建筑招牌或对称模板感。
                每次改变光线方向、材质组合、抽象形态和局部构图，保持专业系列感但不得复刻固定背景。
                全图必须完全无字：禁止中文、英文、字母、数字、伪汉字、类似文字的笔画组合、Logo、
                校徽、二维码、网址、电话、水印、招牌、屏幕UI、标题占位符和任何可阅读符号。
                留白区不要出现高对比主体。只输出一张完成度高、可直接叠加排版的纯背景图。
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
                    "高级运动品牌视觉，以炭黑、暖橙和少量酸性绿为主，运动轨迹转化为大尺度抽象切面，"
                            + "结合磨砂纸张、织物纤维和自然侧光，动感强但画面克制");
        }
        if (containsAny(rawGoal, PERFORMANCE_SIGNALS)) {
            return new PosterTheme(
                    CampusPosterLayout.CINEMATIC,
                    "校园演出",
                    "当代文化演出视觉，以墨紫、酒红和哑光金为主，半透明绸缎、柔焦光束、"
                            + "手工纸纤维和抽象声波形成有呼吸感的层次，艺术但不浮夸");
        }
        if (containsAnyIgnoreCase(rawGoal, TECHNOLOGY_SIGNALS)) {
            return new PosterTheme(
                    CampusPosterLayout.EDITORIAL,
                    "技术分享",
                    "国际设计杂志式科技视觉，以午夜蓝、群青、银灰和少量青绿色为主，"
                            + "非对称的半透明玻璃切片、精细微结构、柔和体积光与高级颗粒质感，"
                            + "现代、理性、年轻，避免俗套霓虹赛博朋克");
        }
        return new PosterTheme(
                CampusPosterLayout.EDITORIAL,
                "校园活动",
                "现代编辑设计视觉，以雾蓝、苔绿、米白和少量暖黄为主，抽象校园空间、"
                        + "自然日光、柔和纸张肌理和简洁几何层次，清新、可信、有设计感");
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
