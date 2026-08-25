package com.example.demo.service.weather;

import com.example.demo.config.WeatherConfig;
import com.example.demo.model.DailyWeatherForecast;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeatherServiceForecastTests {
    private final WeatherService service = new WeatherService(new WeatherConfig());

    @Test
    void parsesTargetDateFromOfficialDailyResponseShape() {
        String response = """
                {
                  "results": [{
                    "location": {"name": "苏州"},
                    "daily": [
                      {"date":"2026-08-27","text_day":"晴","text_night":"多云", "high":"31", "low":"24"},
                      {"date":"2026-08-28","text_day":"小雨","text_night":"阴", "high":"29", "low":"23",
                       "rainfall":"3.5", "humidity":"81", "wind_direction":"东南", "wind_scale":"3"}
                    ],
                    "last_update":"2026-08-25T14:00:00+08:00"
                  }]
                }
                """;

        DailyWeatherForecast forecast = service.parseDailyForecast(
                response, "苏州", LocalDate.of(2026, 8, 28)).orElseThrow();

        assertEquals("苏州", forecast.location());
        assertEquals("小雨", forecast.textDay());
        assertEquals(29, forecast.highCelsius());
        assertEquals(23, forecast.lowCelsius());
        assertEquals(0, new BigDecimal("3.5").compareTo(forecast.rainfallMillimeters()));
        assertEquals(81, forecast.humidityPercent());
        assertEquals("3", forecast.windScale());
    }

    @Test
    void returnsEmptyWhenAccountResponseDoesNotCoverTargetDate() {
        String response = """
                {"results":[{"location":{"name":"苏州"},
                  "daily":[{"date":"2026-08-26","text_day":"晴"}],
                  "last_update":"2026-08-25T14:00:00+08:00"}]}
                """;

        Optional<DailyWeatherForecast> forecast = service.parseDailyForecast(
                response, "苏州", LocalDate.of(2026, 9, 5));

        assertTrue(forecast.isEmpty());
    }
}
