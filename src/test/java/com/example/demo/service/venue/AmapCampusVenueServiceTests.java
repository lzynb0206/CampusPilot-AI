package com.example.demo.service.venue;

import com.example.demo.config.AmapConfig;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AmapCampusVenueServiceTests {
    @Test
    void locatesSchoolFiltersNonVenuesAndRanksSpecificCampusPois() {
        AmapConfig config = new AmapConfig();
        config.setApiKey("test-key");
        config.setMaxCandidates(4);
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AmapCampusVenueService service = new AmapCampusVenueService(config, restTemplate);

        server.expect(request -> assertEquals(
                        "/v3/geocode/geo", request.getURI().getPath()))
                .andRespond(withSuccess("""
                        {"status":"1","info":"OK","geocodes":[{
                          "formatted_address":"江苏省南京市浦口区宁六路219号南京信息工程大学",
                          "location":"118.717315,32.207273"
                        }]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(request -> assertEquals(
                        "/v5/place/around", request.getURI().getPath()))
                .andRespond(withSuccess("""
                        {"status":"1","info":"OK","pois":[
                          {"id":"school","name":"南京信息工程大学","distance":"0",
                           "location":"118.717315,32.207273","address":"宁六路219号"},
                          {"id":"mingde","name":"南京信息工程大学(西苑)-明德楼","distance":"120",
                           "location":"118.716000,32.207000","address":"宁六路219号",
                           "type":"科教文化服务;学校;高等院校"},
                          {"id":"gate","name":"南京信息工程大学东门","distance":"90",
                           "location":"118.718000,32.207000","address":"宁六路219号"}
                        ]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(request -> assertEquals(
                        "/v5/place/around", request.getURI().getPath()))
                .andRespond(withSuccess("""
                        {"status":"1","info":"OK","pois":[
                          {"id":"wende","name":"文德楼","distance":"180",
                           "location":"118.715000,32.206000","address":"南京信息工程大学校内",
                           "type":"科教文化服务;学校;高等院校"},
                          {"id":"hotel","name":"校园宾馆综合楼","distance":"220",
                           "location":"118.714000,32.205000","address":"宁六路",
                           "type":"住宿服务"}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        CampusVenueSearchResult result = service.search(
                "南京信息工程大学", "南京", CampusVenuePreference.GENERAL);

        assertEquals(CampusVenueSearchStatus.AVAILABLE, result.status());
        assertEquals("118.717315,32.207273", result.schoolLocation());
        assertEquals(2, result.candidates().size());
        assertEquals("南京信息工程大学(西苑)-明德楼", result.candidates().get(0).name());
        assertEquals("文德楼", result.candidates().get(1).name());
        assertTrue(result.candidates().get(0).mapUrl().contains("uri.amap.com/marker"));
        assertFalse(result.candidates().get(1).schoolAffiliationNeedsVerification());
        server.verify();
    }

    @Test
    void returnsSafeFallbackWithoutMakingRequestsWhenKeyIsMissing() {
        AmapCampusVenueService service = new AmapCampusVenueService(
                new AmapConfig(), new RestTemplate());

        CampusVenueSearchResult result = service.search(
                "南京信息工程大学", null, CampusVenuePreference.GENERAL);

        assertEquals(CampusVenueSearchStatus.NOT_CONFIGURED, result.status());
        assertTrue(result.candidates().isEmpty());
    }
}
