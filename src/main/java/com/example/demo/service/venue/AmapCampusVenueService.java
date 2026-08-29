package com.example.demo.service.venue;

import com.example.demo.config.AmapConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class AmapCampusVenueService implements CampusVenueSearchProvider {
    private static final List<String> VENUE_SIGNALS = List.of(
            "楼", "馆", "厅", "中心", "教室", "会议室", "讲堂", "礼堂", "剧场",
            "体育场", "操场", "球场", "广场", "草坪");
    private static final List<String> EXCLUDED_SIGNALS = List.of(
            "停车场", "公交站", "地铁站", "宿舍", "公寓", "食堂", "餐厅", "超市",
            "商店", "银行", "ATM", "快递", "驿站", "浴室", "宾馆", "酒店");
    private static final List<String> GATE_SUFFIXES = List.of(
            "东门", "西门", "南门", "北门", "校门", "入口", "出口");

    private final AmapConfig config;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AmapCampusVenueService(AmapConfig config, RestTemplate restTemplate) {
        this.config = config;
        this.restTemplate = restTemplate;
    }

    @Override
    public CampusVenueSearchResult search(
            String school,
            String city,
            CampusVenuePreference preference) {
        if (!StringUtils.hasText(config.getApiKey())) {
            return CampusVenueSearchResult.notConfigured();
        }
        if (!StringUtils.hasText(school)) {
            return new CampusVenueSearchResult(
                    CampusVenueSearchStatus.SCHOOL_NOT_FOUND,
                    "AMAP", school, null, null, List.of(), "请先提供学校名称。");
        }

        CampusVenuePreference resolvedPreference = preference == null
                ? CampusVenuePreference.GENERAL : preference;
        try {
            SchoolLocation schoolLocation = geocodeSchool(school, city);
            if (schoolLocation == null) {
                return new CampusVenueSearchResult(
                        CampusVenueSearchStatus.SCHOOL_NOT_FOUND,
                        "AMAP", school, null, null, List.of(),
                        "高德地图未能定位该学校，请补充城市或具体校区名称。");
            }

            Map<String, ScoredCandidate> uniqueCandidates = new LinkedHashMap<>();
            collectCandidates(uniqueCandidates, school, schoolLocation.location(),
                    school, resolvedPreference);
            collectCandidates(uniqueCandidates, school, schoolLocation.location(),
                    resolvedPreference.searchKeyword(), resolvedPreference);

            List<CampusVenueCandidate> candidates = uniqueCandidates.values().stream()
                    .sorted(Comparator.comparingInt(ScoredCandidate::score).reversed()
                            .thenComparingInt(candidate -> distanceOrMaximum(
                                    candidate.candidate().distanceMeters())))
                    .limit(config.normalizedMaxCandidates())
                    .map(ScoredCandidate::candidate)
                    .toList();
            CampusVenueSearchStatus status = candidates.isEmpty()
                    ? CampusVenueSearchStatus.NO_MATCH : CampusVenueSearchStatus.AVAILABLE;
            String message = candidates.isEmpty()
                    ? "已定位学校，但地图中没有检索到适合当前活动类型的明确场地 POI。"
                    : "候选点来自高德地图 POI；容量、设备、开放时间和校内归属仍需人工确认。";
            return new CampusVenueSearchResult(
                    status,
                    "AMAP",
                    school,
                    schoolLocation.address(),
                    schoolLocation.location(),
                    candidates,
                    message);
        } catch (RestClientException | IllegalStateException exception) {
            log.warn("高德校园场地查询失败 school={} reason={}", school, exception.getMessage());
            return new CampusVenueSearchResult(
                    CampusVenueSearchStatus.ERROR,
                    "AMAP", school, null, null, List.of(),
                    "地图服务暂时不可用，已改用通用场地类型推荐。");
        }
    }

    private SchoolLocation geocodeSchool(String school, String city) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString(config.getGeocodeApiUrl())
                .queryParam("key", config.getApiKey())
                .queryParam("address", school)
                .queryParam("output", "JSON");
        if (StringUtils.hasText(city)) {
            builder.queryParam("city", city);
        }
        JsonNode root = requestJson(builder.build().encode().toUri(), "学校定位");
        JsonNode geocode = root.path("geocodes").path(0);
        String location = text(geocode, "location");
        if (!validLocation(location)) {
            return null;
        }
        return new SchoolLocation(location, text(geocode, "formatted_address"));
    }

    private void collectCandidates(
            Map<String, ScoredCandidate> target,
            String school,
            String schoolLocation,
            String keyword,
            CampusVenuePreference preference) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString(config.getPlaceAroundApiUrl())
                .queryParam("key", config.getApiKey())
                .queryParam("location", schoolLocation)
                .queryParam("keywords", keyword)
                .queryParam("radius", config.normalizedSearchRadiusMeters())
                .queryParam("sortrule", "weight")
                .queryParam("page_size", 25)
                .queryParam("page_num", 1)
                .queryParam("output", "JSON");
        JsonNode root = requestJson(builder.build().encode().toUri(), "校园周边地点搜索");
        for (JsonNode poi : root.path("pois")) {
            ScoredCandidate scored = toCandidate(poi, school, preference);
            if (scored == null) {
                continue;
            }
            String identity = StringUtils.hasText(scored.candidate().id())
                    ? scored.candidate().id() : scored.candidate().name();
            target.merge(identity, scored,
                    (current, incoming) -> incoming.score() > current.score() ? incoming : current);
        }
    }

    private ScoredCandidate toCandidate(
            JsonNode poi,
            String school,
            CampusVenuePreference preference) {
        String name = text(poi, "name");
        if (!StringUtils.hasText(name) || !containsAny(name, VENUE_SIGNALS)
                || containsAnyIgnoreCase(name, EXCLUDED_SIGNALS)
                || GATE_SUFFIXES.stream().anyMatch(name::endsWith)) {
            return null;
        }
        Integer distance = integer(text(poi, "distance"));
        if (distance != null && distance > config.normalizedSearchRadiusMeters()) {
            return null;
        }

        String address = text(poi, "address");
        String type = text(poi, "type");
        String location = text(poi, "location");
        String searchable = name + " " + nullToEmpty(address) + " " + nullToEmpty(type);
        boolean schoolAffiliated = containsNormalized(searchable, school);
        int score = preference.matches(searchable) ? 100 : 40;
        if (schoolAffiliated) {
            score += 60;
        }
        if (distance != null) {
            score += Math.max(0, 30 - distance / 100);
        }
        CampusVenueCandidate candidate = new CampusVenueCandidate(
                text(poi, "id"),
                name,
                address,
                location,
                distance,
                type,
                mapUrl(text(poi, "id"), location, name),
                !schoolAffiliated);
        return new ScoredCandidate(candidate, score);
    }

    private JsonNode requestJson(URI uri, String operation) {
        String response = restTemplate.getForObject(uri, String.class);
        try {
            JsonNode root = objectMapper.readTree(response);
            if (root == null) {
                throw new IllegalStateException(operation + "没有返回数据");
            }
            if (!"1".equals(root.path("status").asText())) {
                throw new IllegalStateException(operation + "失败："
                        + root.path("info").asText("UNKNOWN_ERROR"));
            }
            return root;
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("无法解析高德地图响应", exception);
        }
    }

    private String mapUrl(String poiId, String location, String name) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString("https://uri.amap.com/marker")
                .queryParam("src", "campus-pilot-ai")
                .queryParam("callnative", 0);
        if (StringUtils.hasText(poiId)) {
            builder.queryParam("poiid", poiId);
        } else if (validLocation(location)) {
            builder.queryParam("position", location)
                    .queryParam("name", name)
                    .queryParam("coordinate", "gaode");
        } else {
            return null;
        }
        return builder.build().encode().toUriString();
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isValueNode() && StringUtils.hasText(value.asText())
                ? value.asText().trim() : null;
    }

    private Integer integer(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private boolean validLocation(String location) {
        return StringUtils.hasText(location)
                && location.matches("-?\\d+(?:\\.\\d+)?,-?\\d+(?:\\.\\d+)?");
    }

    private boolean containsNormalized(String value, String expected) {
        if (!StringUtils.hasText(value) || !StringUtils.hasText(expected)) {
            return false;
        }
        return value.replaceAll("\\s+", "")
                .contains(expected.replaceAll("\\s+", ""));
    }

    private boolean containsAny(String value, List<String> signals) {
        return signals.stream().anyMatch(value::contains);
    }

    private boolean containsAnyIgnoreCase(String value, List<String> signals) {
        String normalized = value.toUpperCase();
        return signals.stream().map(String::toUpperCase).anyMatch(normalized::contains);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static int distanceOrMaximum(Integer distance) {
        return distance == null ? Integer.MAX_VALUE : distance;
    }

    private record SchoolLocation(String location, String address) {
    }

    private record ScoredCandidate(CampusVenueCandidate candidate, int score) {
    }
}
