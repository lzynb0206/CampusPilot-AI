package com.example.demo.tool;

import com.example.demo.model.DailyWeatherForecast;
import com.example.demo.service.weather.WeatherForecastProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class EventWeatherAssessmentTool implements BotTool {
    static final int MAX_FORECAST_DAYS_AHEAD = 14;
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Pattern WIND_SCALE_NUMBER = Pattern.compile("\\d+");
    private static final List<String> ADVERSE_WEATHER_WORDS = List.of(
            "雨", "雪", "雷", "冰雹", "台风", "沙尘", "雾", "霾");

    private final WeatherForecastProvider forecastProvider;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public EventWeatherAssessmentTool(WeatherForecastProvider forecastProvider) {
        this(forecastProvider, Clock.system(BUSINESS_ZONE));
    }

    EventWeatherAssessmentTool(WeatherForecastProvider forecastProvider, Clock clock) {
        this.forecastProvider = forecastProvider;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "assess_event_weather";
    }

    @Override
    public String description() {
        return "评估指定城市和活动日期的天气可查询性。未来14天内尝试获取心知天气逐日预报；"
                + "日期太远时不编造天气，而是生成复查日期和室内备用方案。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "location", Map.of(
                                "type", "string",
                                "description", "活动举办城市或区县"),
                        "event_date", Map.of(
                                "type", "string",
                                "format", "date",
                                "description", "活动日期，格式为 YYYY-MM-DD")),
                "required", List.of("location", "event_date"),
                "additionalProperties", false);
    }

    @Override
    public String execute(JsonNode arguments) {
        String location = requireLocation(arguments);
        LocalDate eventDate = requireEventDate(arguments);
        LocalDate today = LocalDate.now(clock);
        if (eventDate.isBefore(today)) {
            throw new IllegalArgumentException("event_date 不能早于今天");
        }

        long daysUntilEvent = ChronoUnit.DAYS.between(today, eventDate);
        Map<String, Object> result = baseResult(location, eventDate, today, daysUntilEvent);
        if (daysUntilEvent > MAX_FORECAST_DAYS_AHEAD) {
            result.put("status", "TOO_EARLY");
            result.put("forecast_available", false);
            result.put("recheck_on", eventDate.minusDays(MAX_FORECAST_DAYS_AHEAD).toString());
            result.put("message", "活动日期超出逐日天气预报范围，当前不查询也不推测具体天气。");
            result.put("recommendations", defaultBackupPlan());
            return toJson(result);
        }

        Optional<DailyWeatherForecast> forecast;
        try {
            forecast = forecastProvider.getDailyForecast(location, eventDate);
        } catch (RuntimeException exception) {
            result.put("status", "QUERY_FAILED");
            result.put("forecast_available", false);
            result.put("recheck_on", recommendedRecheckDate(today, eventDate).toString());
            result.put("message", "天气接口暂时不可用或尚未配置，未生成虚构天气数据。");
            result.put("recommendations", defaultBackupPlan());
            return toJson(result);
        }

        if (forecast.isEmpty()) {
            result.put("status", "RECHECK_REQUIRED");
            result.put("forecast_available", false);
            result.put("recheck_on", recommendedRecheckDate(today, eventDate).toString());
            result.put("message", "天气接口返回的数据未覆盖活动日期，可能受当前账号预报天数权限限制。");
            result.put("recommendations", defaultBackupPlan());
            return toJson(result);
        }

        DailyWeatherForecast value = forecast.get();
        List<String> recommendations = recommendationsFor(value);
        result.put("status", "FORECAST_AVAILABLE");
        result.put("forecast_available", true);
        result.put("risk_level", riskLevel(value));
        result.put("forecast", forecastFields(value));
        result.put("recheck_on", finalCheckDate(today, eventDate).toString());
        result.put("message", "已取得目标日期逐日预报；天气会变化，活动前仍需复查。");
        result.put("recommendations", recommendations);
        return toJson(result);
    }

    private Map<String, Object> baseResult(
            String location, LocalDate eventDate, LocalDate today, long daysUntilEvent) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("location", location);
        result.put("event_date", eventDate.toString());
        result.put("assessment_date", today.toString());
        result.put("days_until_event", daysUntilEvent);
        result.put("final_check_on", finalCheckDate(today, eventDate).toString());
        result.put("source", "心知天气逐日预报 API；最多未来15天，实际返回范围取决于账号权限");
        return result;
    }

    private Map<String, Object> forecastFields(DailyWeatherForecast forecast) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("location", forecast.location());
        fields.put("date", forecast.date().toString());
        fields.put("text_day", forecast.textDay());
        fields.put("text_night", forecast.textNight());
        fields.put("high_celsius", forecast.highCelsius());
        fields.put("low_celsius", forecast.lowCelsius());
        fields.put("rainfall_millimeters", forecast.rainfallMillimeters());
        fields.put("humidity_percent", forecast.humidityPercent());
        fields.put("wind_direction", forecast.windDirection());
        fields.put("wind_scale", forecast.windScale());
        fields.put("last_update", forecast.lastUpdate() == null
                ? null : forecast.lastUpdate().toString());
        return fields;
    }

    private List<String> recommendationsFor(DailyWeatherForecast forecast) {
        List<String> recommendations = new ArrayList<>();
        if (hasAdverseWeather(forecast)) {
            recommendations.add("保留室内备用场地，并在通知中写明天气切换方案。");
        }
        if (forecast.highCelsius() != null && forecast.highCelsius() >= 35) {
            recommendations.add("准备饮水、防暑物资并减少高温时段的室外环节。");
        }
        if (forecast.lowCelsius() != null && forecast.lowCelsius() <= 5) {
            recommendations.add("提醒参与者保暖，并缩短低温环境下的室外等待时间。");
        }
        if (maximumWindScale(forecast.windScale()) >= 6) {
            recommendations.add("取消易受大风影响的布置，固定展架并优先转入室内。");
        }
        if (recommendations.isEmpty()) {
            recommendations.add("当前预报未显示明显高风险，但仍需在活动前一天复查。");
        }
        return List.copyOf(recommendations);
    }

    private String riskLevel(DailyWeatherForecast forecast) {
        boolean severeTemperature = forecast.highCelsius() != null && forecast.highCelsius() >= 35
                || forecast.lowCelsius() != null && forecast.lowCelsius() <= 0;
        if (severeTemperature || maximumWindScale(forecast.windScale()) >= 6) {
            return "HIGH";
        }
        return hasAdverseWeather(forecast) ? "MEDIUM" : "LOW";
    }

    private boolean hasAdverseWeather(DailyWeatherForecast forecast) {
        String weatherText = (safeText(forecast.textDay()) + safeText(forecast.textNight()))
                .toLowerCase(Locale.ROOT);
        boolean adverseText = ADVERSE_WEATHER_WORDS.stream().anyMatch(weatherText::contains);
        boolean recordedRainfall = forecast.rainfallMillimeters() != null
                && forecast.rainfallMillimeters().signum() > 0;
        return adverseText || recordedRainfall;
    }

    private int maximumWindScale(String windScale) {
        if (!StringUtils.hasText(windScale)) {
            return 0;
        }
        Matcher matcher = WIND_SCALE_NUMBER.matcher(windScale);
        int maximum = 0;
        while (matcher.find()) {
            maximum = Math.max(maximum, Integer.parseInt(matcher.group()));
        }
        return maximum;
    }

    private List<String> defaultBackupPlan() {
        return List.of(
                "预留可容纳全部参与者的室内备用场地。",
                "在活动前14天、3天和1天设置天气复查节点。",
                "报名通知中注明天气变化时的场地切换和通知方式。");
    }

    private LocalDate recommendedRecheckDate(LocalDate today, LocalDate eventDate) {
        LocalDate candidate = eventDate.minusDays(2);
        return candidate.isBefore(today) ? today : candidate;
    }

    private LocalDate finalCheckDate(LocalDate today, LocalDate eventDate) {
        LocalDate candidate = eventDate.minusDays(1);
        return candidate.isBefore(today) ? today : candidate;
    }

    private String requireLocation(JsonNode arguments) {
        String location = arguments.path("location").asText().trim();
        if (!StringUtils.hasText(location)) {
            throw new IllegalArgumentException("天气评估工具缺少 location 参数");
        }
        if (location.length() > 80) {
            throw new IllegalArgumentException("location 长度超过限制");
        }
        return location;
    }

    private LocalDate requireEventDate(JsonNode arguments) {
        String dateText = arguments.path("event_date").asText().trim();
        try {
            return LocalDate.parse(dateText);
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("event_date 必须是有效的 YYYY-MM-DD 日期", exception);
        }
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }

    private String toJson(Map<String, Object> result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成活动天气评估结果", exception);
        }
    }
}
