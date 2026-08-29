package com.example.demo.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "amap")
public class AmapConfig {
    private String apiKey;
    private String geocodeApiUrl = "https://restapi.amap.com/v3/geocode/geo";
    private String placeAroundApiUrl = "https://restapi.amap.com/v5/place/around";
    private int searchRadiusMeters = 2500;
    private int maxCandidates = 6;

    public int normalizedSearchRadiusMeters() {
        return Math.clamp(searchRadiusMeters, 100, 50_000);
    }

    public int normalizedMaxCandidates() {
        return Math.clamp(maxCandidates, 1, 10);
    }
}
