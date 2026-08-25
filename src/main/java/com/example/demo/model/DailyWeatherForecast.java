package com.example.demo.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record DailyWeatherForecast(
        String location,
        LocalDate date,
        String textDay,
        String textNight,
        Integer highCelsius,
        Integer lowCelsius,
        BigDecimal rainfallMillimeters,
        Integer humidityPercent,
        String windDirection,
        String windScale,
        OffsetDateTime lastUpdate) {

    public DailyWeatherForecast {
        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("天气预报地点不能为空");
        }
        if (date == null) {
            throw new IllegalArgumentException("天气预报日期不能为空");
        }
        location = location.trim();
    }
}

