package com.example.demo.service.weather;

import com.example.demo.config.WeatherConfig;
import com.example.demo.model.DailyWeatherForecast;
import com.example.demo.model.WeatherInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

@Service
public class WeatherService implements WeatherForecastProvider {
    private final WeatherConfig config;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    @Autowired
    public WeatherService(WeatherConfig config, RestTemplate restTemplate) {
        this.config = config;
        this.objectMapper = new ObjectMapper();
        this.restTemplate = restTemplate;
    }

    public WeatherInfo getCurrentWeather(String location) {
        if (!StringUtils.hasText(config.getApiKey())) {
            throw new IllegalStateException("请先配置 SENIVERSE_API_KEY");
        }
        if (!StringUtils.hasText(location)) {
            throw new IllegalArgumentException("请告诉我要查询哪个城市，例如：北京天气怎么样");
        }

        HttpClientErrorException.NotFound lastNotFound = null;
        for (String candidate : locationCandidates(location)) {
            try {
                return requestCurrentWeather(candidate);
            } catch (HttpClientErrorException.NotFound exception) {
                lastNotFound = exception;
            }
        }
        throw new IllegalArgumentException(
                "找不到地点“" + location + "”，请只输入具体城市或区县，例如：张家港天气怎么样。",
                lastNotFound);
    }

    @Override
    public Optional<DailyWeatherForecast> getDailyForecast(
            String location, LocalDate targetDate) {
        requireConfiguredApiKey();
        if (!StringUtils.hasText(location)) {
            throw new IllegalArgumentException("天气预报地点不能为空");
        }
        if (targetDate == null) {
            throw new IllegalArgumentException("天气预报日期不能为空");
        }

        HttpClientErrorException.NotFound lastNotFound = null;
        for (String candidate : locationCandidates(location)) {
            try {
                return requestDailyForecast(candidate, targetDate);
            } catch (HttpClientErrorException.NotFound exception) {
                lastNotFound = exception;
            }
        }
        throw new IllegalArgumentException(
                "找不到地点“" + location + "”，请只输入具体城市或区县。", lastNotFound);
    }

    private WeatherInfo requestCurrentWeather(String location) {
        URI uri = UriComponentsBuilder.fromUriString(config.getApiUrl())
                .queryParam("key", config.getApiKey())
                .queryParam("location", location)
                .queryParam("language", "zh-Hans")
                .queryParam("unit", "c")
                .build()
                .encode()
                .toUri();
        String response = restTemplate.getForObject(uri, String.class);

        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode result = root.path("results").path(0);
            if (result.isMissingNode() || result.isEmpty()) {
                throw new IllegalStateException("心知天气没有返回该地区的数据");
            }
            String city = result.path("location").path("name").asText(location);
            String weather = result.path("now").path("text").asText("未知");
            String temperature = result.path("now").path("temperature").asText("未知");
            String lastUpdateText = result.path("last_update").asText();
            OffsetDateTime lastUpdate = StringUtils.hasText(lastUpdateText)
                    ? OffsetDateTime.parse(lastUpdateText)
                    : null;
            return new WeatherInfo(city, weather, temperature, lastUpdate);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("无法解析心知天气响应", exception);
        }
    }

    private Optional<DailyWeatherForecast> requestDailyForecast(
            String location, LocalDate targetDate) {
        URI uri = UriComponentsBuilder.fromUriString(config.getForecastApiUrl())
                .queryParam("key", config.getApiKey())
                .queryParam("location", location)
                .queryParam("language", "zh-Hans")
                .queryParam("unit", "c")
                .queryParam("start", 0)
                .build()
                .encode()
                .toUri();
        String response = restTemplate.getForObject(uri, String.class);
        return parseDailyForecast(response, location, targetDate);
    }

    Optional<DailyWeatherForecast> parseDailyForecast(
            String response, String requestedLocation, LocalDate targetDate) {
        try {
            JsonNode result = objectMapper.readTree(response).path("results").path(0);
            if (result.isMissingNode() || result.isEmpty()) {
                throw new IllegalStateException("心知天气没有返回该地区的逐日预报数据");
            }
            String city = result.path("location").path("name").asText(requestedLocation);
            String lastUpdateText = result.path("last_update").asText();
            OffsetDateTime lastUpdate = StringUtils.hasText(lastUpdateText)
                    ? OffsetDateTime.parse(lastUpdateText)
                    : null;
            for (JsonNode daily : result.path("daily")) {
                if (targetDate.toString().equals(daily.path("date").asText())) {
                    return Optional.of(new DailyWeatherForecast(
                            city,
                            targetDate,
                            optionalText(daily, "text_day"),
                            optionalText(daily, "text_night"),
                            optionalInteger(daily, "high"),
                            optionalInteger(daily, "low"),
                            optionalDecimal(daily, "rainfall"),
                            optionalInteger(daily, "humidity"),
                            optionalText(daily, "wind_direction"),
                            optionalText(daily, "wind_scale"),
                            lastUpdate));
                }
            }
            return Optional.empty();
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("无法解析心知天气逐日预报响应", exception);
        }
    }

    private String optionalText(JsonNode node, String field) {
        String value = node.path(field).asText();
        return StringUtils.hasText(value) ? value : null;
    }

    private Integer optionalInteger(JsonNode node, String field) {
        String value = optionalText(node, field);
        return value == null ? null : Integer.valueOf(value);
    }

    private BigDecimal optionalDecimal(JsonNode node, String field) {
        String value = optionalText(node, field);
        return value == null ? null : new BigDecimal(value);
    }

    private void requireConfiguredApiKey() {
        if (!StringUtils.hasText(config.getApiKey())) {
            throw new IllegalStateException("请先配置 SENIVERSE_API_KEY");
        }
    }

    private Set<String> locationCandidates(String location) {
        String normalized = location.replaceAll("\\s+", "")
                .replaceFirst("^中国", "")
                .replaceAll("(天气|气温|温度)$", "");
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add(normalized);

        String[] administrativeParts = normalized.split("[省市州]");
        if (administrativeParts.length > 1) {
            String lastPart = administrativeParts[administrativeParts.length - 1]
                    .replaceFirst("[市区县]$", "");
            if (StringUtils.hasText(lastPart)) {
                candidates.add(lastPart);
            }
        }

        String withoutSuffix = normalized.replaceFirst("[市区县]$", "");
        candidates.add(withoutSuffix);
        addSuffixCandidate(candidates, withoutSuffix, 3);
        addSuffixCandidate(candidates, withoutSuffix, 2);
        addSuffixCandidate(candidates, withoutSuffix, 4);
        candidates.removeIf(value -> !StringUtils.hasText(value));
        return candidates;
    }

    private void addSuffixCandidate(Set<String> candidates, String location, int length) {
        if (location.length() > length) {
            candidates.add(location.substring(location.length() - length));
        }
    }
}
