package com.example.demo.agent.campus;

import com.example.demo.config.RagConfig;
import com.example.demo.rag.KeywordRagService;
import com.example.demo.service.venue.CampusVenueCandidate;
import com.example.demo.service.venue.CampusVenueSearchResult;
import com.example.demo.service.venue.CampusVenueSearchStatus;
import com.example.demo.tool.EventBudgetTool;
import com.example.demo.tool.EventSupplyEstimateTool;
import com.example.demo.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DefaultCampusTaskRunnerVenueTests {
    @Test
    void usesMapCandidatesWhenSchoolIsKnownAndVenueIsOpen() {
        DefaultCampusTaskRunner runner = new DefaultCampusTaskRunner(
                new KeywordRagService(new RagConfig()),
                new ToolRegistry(List.of(new EventBudgetTool(), new EventSupplyEstimateTool())),
                (school, city, preference) -> new CampusVenueSearchResult(
                        CampusVenueSearchStatus.AVAILABLE,
                        "AMAP",
                        school,
                        "南京市浦口区宁六路219号",
                        "118.717315,32.207273",
                        List.of(new CampusVenueCandidate(
                                "B001", "明德楼", "南京信息工程大学校内",
                                "118.716000,32.207000", 120,
                                "科教文化服务", "https://uri.amap.com/marker?poiid=B001",
                                false)),
                        "测试地图候选"));
        CampusEventGoal goal = new CampusGoalParser().parse(
                "帮我策划一场校园AI分享会，学校：南京信息工程大学，50人参加，预算2000元");
        AgentTask task = new AgentTask(
                "match_venue", "分析并匹配活动场地", TaskCapability.TOOL,
                List.of(), "候选场地");

        JsonNode result = runner.execute(
                task, new CampusExecutionContext(goal, Map.of()));

        assertEquals("MAP_CANDIDATES", result.path("status").asText());
        assertEquals("明德楼", result.path("recommended_area").asText());
        assertEquals("明德楼", result.path("candidate_venues").path(0).path("name").asText());
        assertEquals(120, result.path("candidate_venues").path(0)
                .path("distance_meters").asInt());
        assertFalse(result.path("candidate_venues").path(0)
                .path("capacity_verified").asBoolean());
    }
}
