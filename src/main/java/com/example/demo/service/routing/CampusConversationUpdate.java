package com.example.demo.service.routing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

public record CampusConversationUpdate(
        String eventName,
        LocalDate eventDate,
        String city,
        String school,
        String venue,
        LocalTime startTime,
        Integer participantCount,
        BigDecimal budget) {

    public boolean hasChanges() {
        return eventName != null || eventDate != null || city != null || school != null
                || venue != null || startTime != null || participantCount != null || budget != null;
    }
}
