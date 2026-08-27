package com.example.demo.service.routing;

import java.time.LocalDate;

public record CampusConversationContext(
        String summary,
        String city,
        LocalDate eventDate) {
}
