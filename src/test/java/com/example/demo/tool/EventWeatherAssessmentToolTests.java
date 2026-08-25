package com.example.demo.tool;

import com.example.demo.model.DailyWeatherForecast;
import com.example.demo.service.weather.WeatherForecastProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventWeatherAssessmentToolTests {
    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-08-25T00:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void doesNotCallApiOrInventForecastWhenEventIsTooFarAway() throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        WeatherForecastProvider provider = (location, date) -> {
            providerCalls.incrementAndGet();
            throw new AssertionError("日期太远时不应调用天气API");
        };
        EventWeatherAssessmentTool tool = new EventWeatherAssessmentTool(provider, FIXED_CLOCK);

        JsonNode result = execute(tool, "苏州", "2026-09-20");

        assertEquals("TOO_EARLY", result.path("status").asText());
        assertFalse(result.path("forecast_available").asBoolean());
        assertEquals(26, result.path("days_until_event").asInt());
        assertEquals("2026-09-06", result.path("recheck_on").asText());
        assertEquals(0, providerCalls.get());
        assertTrue(result.path("message").asText().contains("不查询也不推测"));
    }

    @Test
    void returnsRealForecastAndWeatherRisksWithinForecastWindow() throws Exception {
        WeatherForecastProvider provider = (location, date) -> Optional.of(
                new DailyWeatherForecast(
                        "苏州", date, "小雨", "中雨", 30, 24,
                        new BigDecimal("6.2"), 88, "东南", "3-4",
                        OffsetDateTime.parse("2026-08-25T14:00:00+08:00")));
        EventWeatherAssessmentTool tool = new EventWeatherAssessmentTool(provider, FIXED_CLOCK);

        JsonNode result = execute(tool, "苏州", "2026-08-28");

        assertEquals("FORECAST_AVAILABLE", result.path("status").asText());
        assertTrue(result.path("forecast_available").asBoolean());
        assertEquals("MEDIUM", result.path("risk_level").asText());
        assertEquals("小雨", result.at("/forecast/text_day").asText());
        assertTrue(result.path("recommendations").get(0).asText().contains("室内备用场地"));
        assertEquals("2026-08-27", result.path("recheck_on").asText());
    }

    @Test
    void schedulesRecheckWhenFreeAccountDoesNotReturnTargetDate() throws Exception {
        WeatherForecastProvider provider = (location, date) -> Optional.empty();
        EventWeatherAssessmentTool tool = new EventWeatherAssessmentTool(provider, FIXED_CLOCK);

        JsonNode result = execute(tool, "苏州", "2026-09-05");

        assertEquals("RECHECK_REQUIRED", result.path("status").asText());
        assertFalse(result.path("forecast_available").asBoolean());
        assertEquals("2026-09-03", result.path("recheck_on").asText());
        assertTrue(result.path("message").asText().contains("账号预报天数权限"));
    }

    @Test
    void convertsProviderFailureIntoExplicitNonHallucinatedResult() throws Exception {
        WeatherForecastProvider provider = (location, date) -> {
            throw new IllegalStateException("未配置API Key");
        };
        EventWeatherAssessmentTool tool = new EventWeatherAssessmentTool(provider, FIXED_CLOCK);

        JsonNode result = execute(tool, "苏州", "2026-08-28");

        assertEquals("QUERY_FAILED", result.path("status").asText());
        assertFalse(result.path("forecast_available").asBoolean());
        assertTrue(result.path("message").asText().contains("未生成虚构天气数据"));
    }

    @Test
    void rejectsPastOrMalformedDates() {
        WeatherForecastProvider provider = (location, date) -> Optional.empty();
        EventWeatherAssessmentTool tool = new EventWeatherAssessmentTool(provider, FIXED_CLOCK);

        assertThrows(IllegalArgumentException.class,
                () -> execute(tool, "苏州", "2026-08-24"));
        assertThrows(IllegalArgumentException.class,
                () -> execute(tool, "苏州", "2026-02-30"));
    }

    private JsonNode execute(
            EventWeatherAssessmentTool tool, String location, String eventDate) throws Exception {
        String arguments = objectMapper.writeValueAsString(java.util.Map.of(
                "location", location,
                "event_date", eventDate));
        return objectMapper.readTree(tool.execute(objectMapper.readTree(arguments)));
    }
}
