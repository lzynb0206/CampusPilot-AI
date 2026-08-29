package com.example.demo.service.venue;

import java.util.List;

public record CampusVenueSearchResult(
        CampusVenueSearchStatus status,
        String provider,
        String school,
        String schoolAddress,
        String schoolLocation,
        List<CampusVenueCandidate> candidates,
        String message) {

    public CampusVenueSearchResult {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    public static CampusVenueSearchResult notConfigured() {
        return new CampusVenueSearchResult(
                CampusVenueSearchStatus.NOT_CONFIGURED,
                "AMAP",
                null,
                null,
                null,
                List.of(),
                "未配置高德 Web 服务 Key，已改用通用场地类型推荐。");
    }
}
