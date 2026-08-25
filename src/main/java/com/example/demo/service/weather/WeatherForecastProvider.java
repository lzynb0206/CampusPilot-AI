package com.example.demo.service.weather;

import com.example.demo.model.DailyWeatherForecast;

import java.time.LocalDate;
import java.util.Optional;

public interface WeatherForecastProvider {
    Optional<DailyWeatherForecast> getDailyForecast(String location, LocalDate targetDate);
}

