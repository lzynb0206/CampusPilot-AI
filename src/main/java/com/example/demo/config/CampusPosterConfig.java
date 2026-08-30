package com.example.demo.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "agent.campus.poster")
public class CampusPosterConfig {
    private boolean enabled = true;
    private int canvasWidth = 1080;
    private int canvasHeight = 1440;
    private String logoResourceDirectory = "poster-templates/logos";
    private boolean backgroundQualityReviewEnabled = true;
    private int maxBackgroundAttempts = 3;
    private int minimumBackgroundScore = 72;
}
