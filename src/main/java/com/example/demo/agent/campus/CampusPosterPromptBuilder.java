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

    public String build(CampusAgentRun run) {
        if (!config.isEnabled() || run == null
                || run.status() != CampusAgentRunStatus.COMPLETED) {
            return null;
        }
        return build(run.goal());
    }

    public String build(CampusEventGoal goal) {
        if (!config.isEnabled() || goal == null) {
            return null;
        }
        String eventName = posterText(goal.eventName(), "校园主题活动", 60);
        String date = goal.eventDate() == null
                ? "日期待定" : goal.eventDate().format(DATE_FORMAT);
        String time = goal.startTime() == null
                ? "时间待定" : goal.startTime().format(TIME_FORMAT) + "开始";
        String location = posterLocation(goal);
        String style = posterStyle(goal.rawGoal());

        return """
                生成一张精致、可直接在校园社群发布的竖版中文活动海报，画面比例3:4。
                采用%s。视觉层级清晰，标题醒目，信息区留有充足呼吸感，手机屏幕上易读。

                海报必须逐字、清晰、完整呈现以下中文，不得改写，不得增加虚构信息：
                主标题：「%s」
                日期：「%s」
                时间：「%s」
                地点：「%s」
                行动文案：「欢迎报名参加」

                如果字段中含“待定”，必须原样保留“待定”，不要自行编造日期、时间、楼号或教室号。
                不要生成二维码、校徽、品牌Logo、主办方名称、赞助商、网址、电话号码或报名截止时间。
                不要出现英文乱码、错别字、重复文字、微小难辨文字、水印、边框裁切或拥挤排版。
                只输出完整海报画面，不要展示样机、手机、画框、设计软件界面或制作过程。
                """.formatted(style, eventName, date, time, location);
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

    private String posterStyle(String rawGoal) {
        if (containsAny(rawGoal, SPORTS_SIGNALS)) {
            return "活力运动模板：橙色与翠绿色为主色，动感几何图形、速度线和开阔校园空间，青春有力量";
        }
        if (containsAny(rawGoal, PERFORMANCE_SIGNALS)) {
            return "校园演出模板：深紫与暖金为主色，舞台光束、优雅渐变和艺术感构图，热烈但不浮夸";
        }
        if (containsAnyIgnoreCase(rawGoal, TECHNOLOGY_SIGNALS)) {
            return "未来科技模板：深蓝与电光紫为主色，抽象数字网格、柔和发光线条和现代校园剪影，专业而年轻";
        }
        return "现代校园模板：明亮蓝绿配色，简洁几何色块、自然光和清新的校园氛围，友好而有组织感";
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
}
