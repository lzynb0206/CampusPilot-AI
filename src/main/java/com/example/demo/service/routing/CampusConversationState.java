package com.example.demo.service.routing;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

record CampusConversationState(
        String originalRequest,
        String eventName,
        LocalDate eventDate,
        String city,
        String school,
        String venue,
        LocalTime startTime,
        Integer participantCount,
        BigDecimal budget,
        Instant updatedAt) {

    static CampusConversationState start(
            String originalRequest, CampusConversationUpdate update, Instant now) {
        return new CampusConversationState(
                originalRequest.trim(),
                update.eventName(),
                update.eventDate(),
                update.city(),
                update.school(),
                update.venue(),
                update.startTime(),
                update.participantCount(),
                update.budget(),
                now);
    }

    CampusConversationState merge(CampusConversationUpdate update, Instant now) {
        String nextSchool = prefer(update.school(), school);
        String nextVenue = prefer(update.venue(), venue);
        if (update.school() != null && update.venue() == null
                && school != null && nextVenue != null && nextVenue.contains(school)) {
            nextVenue = nextVenue.replace(school, "");
            if (nextVenue.isBlank()) {
                nextVenue = null;
            }
        }
        return new CampusConversationState(
                originalRequest,
                prefer(update.eventName(), eventName),
                prefer(update.eventDate(), eventDate),
                prefer(update.city(), city),
                nextSchool,
                nextVenue,
                prefer(update.startTime(), startTime),
                prefer(update.participantCount(), participantCount),
                prefer(update.budget(), budget),
                now);
    }

    CampusConversationState touch(Instant now) {
        return new CampusConversationState(
                originalRequest, eventName, eventDate, city, school, venue, startTime,
                participantCount, budget, now);
    }

    String canonicalGoal() {
        StringBuilder goal = new StringBuilder("请策划校园活动，");
        append(goal, "活动名称：", eventName);
        if (eventDate != null) {
            append(goal, "活动日期：", eventDate.toString());
        }
        if (city != null) {
            goal.append("在").append(clean(city)).append("举办，");
        }
        append(goal, "学校：", school);
        append(goal, "活动场地：", venue);
        if (startTime != null) {
            append(goal, "开始时间：", startTime.toString());
        }
        if (participantCount != null) {
            goal.append("参与人数：").append(participantCount).append("人，");
        }
        if (budget != null) {
            goal.append("预算：").append(budget.stripTrailingZeros().toPlainString()).append("元，");
        }
        goal.append("活动类型与原始要求：").append(clean(originalRequest));
        return goal.toString();
    }

    private static void append(StringBuilder target, String label, String value) {
        if (value != null && !value.isBlank()) {
            target.append(label).append(clean(value)).append("，");
        }
    }

    private static String clean(String value) {
        return value.replaceAll("[\\r\\n]+", " ").trim();
    }

    private static <T> T prefer(T update, T current) {
        return update == null ? current : update;
    }
}
