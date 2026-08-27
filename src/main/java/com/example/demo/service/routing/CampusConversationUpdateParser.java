package com.example.demo.service.routing;

import com.example.demo.agent.campus.CampusEventGoal;
import com.example.demo.agent.campus.CampusGoalParser;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CampusConversationUpdateParser {
    private static final Pattern PARTICIPANT_UPDATE = Pattern.compile(
            "(?:参与人数|预计人数|人数|规模)\\s*(?:改(?:为|成|到)?|调整为|调整到|是|为|约|[=：:])?\\s*"
                    + "(\\d{1,6}|[零〇一二两三四五六七八九十百千万]{1,8})\\s*(?:人|位)?");
    private static final Pattern BUDGET_UPDATE = Pattern.compile(
            "(?:预算|经费)\\s*(?:改(?:为|成|到)?|调整为|调整到|是|为|约|[=：:])?\\s*"
                    + "(\\d+(?:\\.\\d+)?)\\s*(万)?\\s*(?:元|人民币)?");
    private static final Pattern EVENT_NAME_UPDATE = Pattern.compile(
            "(?:活动名称|活动主题|名称|主题)\\s*(?:改为|改成|换成|是|为|[=：:])\\s*([^，。；;!?]{2,60})");
    private static final Pattern SCHOOL_UPDATE = Pattern.compile(
            "(?:学校|院校)\\s*(?:改为|改成|换为|换成|是|为|[=：:])\\s*"
                    + "([\\p{IsHan}A-Za-z0-9]{2,40}?(?:大学|学院|学校)(?:[\\p{IsHan}A-Za-z0-9]{0,12}校区)?)");
    private static final Pattern CITY_UPDATE = Pattern.compile(
            "(?:举办城市|城市)\\s*(?:改为|改成|换成|是|为|[=：:])\\s*([\\p{IsHan}]{2,8}?)(?:市)?(?:[，。；;!?]|$)");
    private static final Pattern TIME_UPDATE = Pattern.compile(
            "(?:开始时间|活动时间|时间)\\s*(?:改(?:为|成|到)?|调整为|调整到|是|为|[=：:])?\\s*"
                    + "(\\d{1,2})(?:[:：点时](\\d{1,2})?)?");
    private static final Pattern VENUE_FIELD = Pattern.compile(
            "(?:活动)?(?:场地|地点)\\s*(?:改为|改成|改到|换为|换成|换到|调整为|是|为)?"
                    + "\\s*[=：:]?\\s*([^，。；;!?]{1,80})");
    private static final Pattern MOVE_TO_VENUE = Pattern.compile(
            "(?:改到|换到|移到|安排在|定在|我(?:们)?(?:是)?在|在)\\s*"
                    + "([^，。；;!?]{1,80}?)(?=举行|举办|开展|进行|[，。；;!?]|$)");
    private static final Pattern EVENT_AT_VENUE = Pattern.compile(
            "^\\s*(?:我(?:们)?(?:是)?在)?\\s*([^，。；;!?]{1,80}?)(?:举行|举办|开展|进行)\\s*$");
    private static final Pattern SCHOOL_NAME = Pattern.compile(
            "([\\p{IsHan}A-Za-z0-9]{2,30}?(?:大学|学院|学校)(?:[\\p{IsHan}A-Za-z0-9]{0,12}校区)?)");
    private static final List<String> VENUE_SIGNALS = List.of(
            "大学", "学院", "学校", "校区", "楼", "馆", "厅", "教室", "会议室", "实验室",
            "礼堂", "讲堂", "中心", "操场", "体育场", "草坪", "食堂", "广场");
    private static final List<String> QUESTION_SIGNALS = List.of(
            "什么", "哪里", "哪儿", "哪个", "别的", "其他", "吗", "呢", "？", "?");

    private final CampusGoalParser goalParser;

    public CampusConversationUpdateParser(CampusGoalParser goalParser) {
        this.goalParser = goalParser;
    }

    public CampusConversationUpdate parse(String message) {
        String text = message == null ? "" : message.trim();
        if (text.isEmpty()) {
            return emptyUpdate();
        }
        CampusEventGoal parsed = goalParser.parse(text);
        String venue = extractVenue(text);
        String school = extractSchool(text, venue);
        if (venue != null && venue.equals(school)) {
            venue = null;
        }
        return new CampusConversationUpdate(
                supplied(parsed, "活动名称") ? parsed.eventName() : extractEventName(text),
                supplied(parsed, "活动日期") ? parsed.eventDate() : null,
                supplied(parsed, "举办城市") ? parsed.city() : extractCity(text),
                school,
                venue,
                parsed.startTime() != null ? parsed.startTime() : extractTime(text),
                supplied(parsed, "参与人数") ? parsed.participantCount() : extractParticipant(text),
                supplied(parsed, "总预算") ? parsed.budget() : extractBudget(text));
    }

    private boolean supplied(CampusEventGoal goal, String field) {
        return !goal.missingFields().contains(field);
    }

    private String extractEventName(String text) {
        return firstGroup(EVENT_NAME_UPDATE, text);
    }

    private String extractCity(String text) {
        return firstGroup(CITY_UPDATE, text);
    }

    private Integer extractParticipant(String text) {
        String value = firstGroup(PARTICIPANT_UPDATE, text);
        if (value == null) {
            return null;
        }
        CampusEventGoal parsed = goalParser.parse(value + "人");
        return supplied(parsed, "参与人数") ? parsed.participantCount() : null;
    }

    private BigDecimal extractBudget(String text) {
        Matcher matcher = BUDGET_UPDATE.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        BigDecimal amount = new BigDecimal(matcher.group(1));
        return matcher.group(2) == null ? amount : amount.multiply(BigDecimal.valueOf(10_000));
    }

    private LocalTime extractTime(String text) {
        Matcher matcher = TIME_UPDATE.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        int hour = Integer.parseInt(matcher.group(1));
        int minute = matcher.group(2) == null || matcher.group(2).isBlank()
                ? 0 : Integer.parseInt(matcher.group(2));
        if (hour > 23 || minute > 59) {
            return null;
        }
        return LocalTime.of(hour, minute);
    }

    private String extractVenue(String text) {
        String candidate = firstGroup(VENUE_FIELD, text);
        if (!isVenueCandidate(candidate)) {
            candidate = firstGroup(MOVE_TO_VENUE, text);
        }
        if (!isVenueCandidate(candidate)) {
            candidate = firstGroup(EVENT_AT_VENUE, text);
        }
        if (!isVenueCandidate(candidate) && text.length() <= 60 && !containsAny(text, QUESTION_SIGNALS)) {
            candidate = text;
        }
        candidate = cleanVenue(candidate);
        return isVenueCandidate(candidate) ? candidate : null;
    }

    private String extractSchool(String text, String venue) {
        String explicit = firstGroup(SCHOOL_UPDATE, text);
        if (explicit != null && !containsAny(explicit, QUESTION_SIGNALS)) {
            return explicit;
        }
        String candidate = venue == null ? cleanVenue(text) : venue;
        if (containsAny(candidate, QUESTION_SIGNALS)) {
            return null;
        }
        Matcher matcher = SCHOOL_NAME.matcher(candidate);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private boolean isVenueCandidate(String candidate) {
        return candidate != null && !candidate.isBlank()
                && !containsAny(candidate, QUESTION_SIGNALS)
                && containsAny(candidate, VENUE_SIGNALS);
    }

    private String cleanVenue(String value) {
        if (value == null) {
            return null;
        }
        return value.trim()
                .replaceFirst("^(?:改为|改成|改到|换为|换成|换到|移到|我(?:们)?(?:是)?在|活动(?:安排)?在|安排在|定在|在)", "")
                .replaceFirst("(?:举行|举办|开展|进行)$", "")
                .replaceAll("^[：:=\\s]+|[：:=\\s，。；;]+$", "")
                .replaceAll("\\s+", "")
                .trim();
    }

    private boolean containsAny(String value, List<String> signals) {
        return value != null && signals.stream().anyMatch(value::contains);
    }

    private String firstGroup(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private CampusConversationUpdate emptyUpdate() {
        return new CampusConversationUpdate(null, null, null, null, null, null, null, null);
    }
}
