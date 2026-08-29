package com.example.demo.agent.campus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class CampusGoalParser {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String DEFAULT_EVENT_NAME = "校园主题活动";
    private static final int DEFAULT_PARTICIPANT_COUNT = 50;
    private static final BigDecimal DEFAULT_BUDGET = new BigDecimal("2000");
    private static final Pattern DATE = Pattern.compile(
            "(?<!\\d)(\\d{4})\\s*[年./-]\\s*(\\d{1,2})\\s*[月./-]\\s*(\\d{1,2})\\s*日?");
    private static final Pattern CITY = Pattern.compile(
            "(?:在|地点\\s*[=：:]\\s*)([\\p{IsHan}]{2,20}?)(?:市)?(?:举办|举行|开展|组织)");
    private static final Pattern SCHOOL = Pattern.compile(
            "(?:学校|院校)\\s*[=：:]\\s*([^，。；;]{2,60})");
    private static final Pattern DECLARED_SCHOOL = Pattern.compile(
            "(?:我(?:们)?(?:是?在|是|来自|就读于)|来自|就读于)\\s*"
                    + "([\\p{IsHan}A-Za-z0-9]{2,30}?(?:大学|学院|学校)"
                    + "(?:[\\p{IsHan}A-Za-z0-9]{0,12}校区)?)");
    private static final Pattern PLANNING_AT_SCHOOL = Pattern.compile(
            "(?:在|于)\\s*"
                    + "([\\p{IsHan}A-Za-z0-9]{2,30}?(?:大学|学院|学校)"
                    + "(?:[\\p{IsHan}A-Za-z0-9]{0,12}校区)?)"
                    + "(?=举办|举行|开展|组织|校内|[，。；;])");
    private static final Pattern VENUE = Pattern.compile(
            "(?:活动)?(?:场地|地点)\\s*[=：:]\\s*([^，。；;]{1,80})");
    private static final Pattern START_TIME = Pattern.compile(
            "(?:开始时间|活动时间|时间)\\s*[=：:]?\\s*(\\d{1,2})(?:[:：点时](\\d{1,2})?)?");
    private static final Pattern NEXT_WEEKDAY = Pattern.compile("下周([一二三四五六日天])");
    private static final Pattern PARTICIPANT_COUNT = Pattern.compile(
            "(?<!\\d)(\\d{1,6}|[零〇一二两三四五六七八九十百千万]{1,8})\\s*(?:人|位)(?:参加|参与|规模)?");
    private static final Pattern BUDGET = Pattern.compile(
            "预算(?:为|是|约|大约|不超过|控制在)?\\s*[=：:]?\\s*(\\d+(?:\\.\\d+)?)\\s*(万)?\\s*(?:元|人民币)");
    private static final Pattern EXPLICIT_EVENT_NAME = Pattern.compile(
            "(?:活动名称|活动主题|名称|主题)\\s*[=：:]\\s*([^，。；;]{2,60})");
    private static final Pattern EVENT_NAME_AFTER_BUDGET = Pattern.compile(
            "(?:元|人民币)\\s*的?\\s*([^，。；;]{2,60}?(?:分享会|运动会|活动|讲座|比赛|论坛|晚会|展览|沙龙|会议|团建|市集|演出))");
    private static final Pattern EVENT_NAME_AFTER_PLANNING = Pattern.compile(
            "(?:策划|规划|组织|举办)(?:一场|一次|一个)?\\s*"
                    + "([^，。；;\\d]{2,40}?(?:分享会|运动会|活动|讲座|比赛|论坛|晚会|展览|沙龙|会议|团建|市集|演出))");
    private final Clock clock;

    @Autowired
    public CampusGoalParser() {
        this(Clock.system(BUSINESS_ZONE));
    }

    public CampusGoalParser(Clock clock) {
        this.clock = clock;
    }

    public CampusEventGoal parse(String rawGoal) {
        if (rawGoal == null || rawGoal.isBlank()) {
            throw new IllegalArgumentException("最终目标不能为空");
        }

        String goal = rawGoal.trim();
        List<String> validationIssues = new ArrayList<>();
        String eventName = extractEventName(goal);
        LocalDate eventDate = extractDate(goal, validationIssues);
        String city = extractCity(goal);
        String school = extractSchool(goal);
        String venue = firstGroup(VENUE, goal);
        LocalTime startTime = extractStartTime(goal, validationIssues);
        Integer participantCount = extractParticipantCount(goal, validationIssues);
        BigDecimal budget = extractBudget(goal);

        List<String> missingFields = new ArrayList<>();
        addMissing(missingFields, eventName, "活动名称");
        addMissing(missingFields, eventDate, "活动日期");
        addMissing(missingFields, city, "举办城市");
        addMissing(missingFields, participantCount, "参与人数");
        addMissing(missingFields, budget, "总预算");

        // 信息不全时仍先产出可用通用方案；这些默认值会在成品中明确标注，
        // 用户后续补充真实信息后可以再精细化。日期和城市不虚构，留作待补充项。
        if (eventName == null) {
            eventName = DEFAULT_EVENT_NAME;
        }
        if (participantCount == null) {
            participantCount = DEFAULT_PARTICIPANT_COUNT;
        }
        if (budget == null) {
            budget = DEFAULT_BUDGET;
        }

        return new CampusEventGoal(
                goal,
                eventName,
                eventDate,
                city,
                school,
                venue,
                startTime,
                participantCount,
                budget,
                missingFields,
                validationIssues);
    }

    private LocalDate extractDate(String goal, List<String> validationIssues) {
        Matcher matcher = DATE.matcher(goal);
        if (matcher.find()) {
            int year = Integer.parseInt(matcher.group(1));
            int month = Integer.parseInt(matcher.group(2));
            int day = Integer.parseInt(matcher.group(3));
            try {
                return LocalDate.of(year, month, day);
            } catch (DateTimeException exception) {
                validationIssues.add("活动日期无效：" + matcher.group());
                return null;
            }
        }
        LocalDate today = LocalDate.now(clock);
        if (goal.contains("后天")) {
            return today.plusDays(2);
        }
        if (goal.contains("明天")) {
            return today.plusDays(1);
        }
        if (goal.contains("今天") || goal.contains("今日")) {
            return today;
        }
        Matcher nextWeekday = NEXT_WEEKDAY.matcher(goal);
        if (nextWeekday.find()) {
            LocalDate nextMonday = today.with(TemporalAdjusters.previousOrSame(
                    java.time.DayOfWeek.MONDAY)).plusWeeks(1);
            return nextMonday.plusDays(chineseWeekday(nextWeekday.group(1)) - 1L);
        }
        return null;
    }

    private String extractCity(String goal) {
        String value = firstGroup(CITY, goal);
        if (value == null || value.length() > 8
                || value.contains("大学") || value.contains("学院") || value.contains("学校")
                || value.endsWith("楼") || value.endsWith("馆") || value.endsWith("厅")) {
            return null;
        }
        return value;
    }

    private String extractSchool(String goal) {
        String explicit = firstGroup(SCHOOL, goal);
        if (explicit != null) {
            return explicit;
        }
        String declared = firstGroup(DECLARED_SCHOOL, goal);
        return declared != null ? declared : firstGroup(PLANNING_AT_SCHOOL, goal);
    }

    private LocalTime extractStartTime(String goal, List<String> validationIssues) {
        Matcher matcher = START_TIME.matcher(goal);
        if (!matcher.find()) {
            return null;
        }
        int hour = Integer.parseInt(matcher.group(1));
        int minute = matcher.group(2) == null || matcher.group(2).isBlank()
                ? 0 : Integer.parseInt(matcher.group(2));
        try {
            return LocalTime.of(hour, minute);
        } catch (DateTimeException exception) {
            validationIssues.add("活动开始时间无效：" + matcher.group());
            return null;
        }
    }

    private int chineseWeekday(String value) {
        return switch (value) {
            case "一" -> 1;
            case "二" -> 2;
            case "三" -> 3;
            case "四" -> 4;
            case "五" -> 5;
            case "六" -> 6;
            case "日", "天" -> 7;
            default -> throw new IllegalArgumentException("不支持的星期：" + value);
        };
    }

    private String extractEventName(String goal) {
        String explicitName = firstGroup(EXPLICIT_EVENT_NAME, goal);
        if (explicitName != null) {
            return explicitName;
        }
        String afterBudget = firstGroup(EVENT_NAME_AFTER_BUDGET, goal);
        return afterBudget != null ? afterBudget : firstGroup(EVENT_NAME_AFTER_PLANNING, goal);
    }

    private BigDecimal extractBudget(String goal) {
        Matcher matcher = BUDGET.matcher(goal);
        if (!matcher.find()) {
            return null;
        }
        BigDecimal amount = new BigDecimal(matcher.group(1));
        return matcher.group(2) == null ? amount : amount.multiply(BigDecimal.valueOf(10_000));
    }

    private Integer extractParticipantCount(String goal, List<String> validationIssues) {
        String value = firstGroup(PARTICIPANT_COUNT, goal);
        if (value == null) {
            return null;
        }
        try {
            return value.chars().allMatch(Character::isDigit)
                    ? Integer.valueOf(value) : parseChineseInteger(value);
        } catch (IllegalArgumentException exception) {
            validationIssues.add("参与人数无效：" + value);
            return null;
        }
    }

    private int parseChineseInteger(String value) {
        int total = 0;
        int section = 0;
        int number = 0;
        for (char character : value.toCharArray()) {
            int digit = chineseDigit(character);
            if (digit >= 0) {
                number = digit;
                continue;
            }
            int unit = chineseUnit(character);
            if (unit == 10_000) {
                section = (section + number) * unit;
                total += section;
                section = 0;
                number = 0;
            } else if (unit > 0) {
                section += (number == 0 ? 1 : number) * unit;
                number = 0;
            } else {
                throw new IllegalArgumentException("不支持的中文数字");
            }
        }
        int result = total + section + number;
        if (result <= 0) {
            throw new IllegalArgumentException("人数必须大于0");
        }
        return result;
    }

    private int chineseDigit(char character) {
        return switch (character) {
            case '零', '〇' -> 0;
            case '一' -> 1;
            case '二', '两' -> 2;
            case '三' -> 3;
            case '四' -> 4;
            case '五' -> 5;
            case '六' -> 6;
            case '七' -> 7;
            case '八' -> 8;
            case '九' -> 9;
            default -> -1;
        };
    }

    private int chineseUnit(char character) {
        return switch (character) {
            case '十' -> 10;
            case '百' -> 100;
            case '千' -> 1_000;
            case '万' -> 10_000;
            default -> -1;
        };
    }

    private String firstGroup(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private void addMissing(List<String> missingFields, Object value, String fieldName) {
        if (value == null) {
            missingFields.add(fieldName);
        }
    }
}
