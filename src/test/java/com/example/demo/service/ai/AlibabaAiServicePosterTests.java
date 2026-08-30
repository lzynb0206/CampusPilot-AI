package com.example.demo.service.ai;

import com.example.demo.config.AiConfig;
import com.example.demo.tool.ToolCallingEngine;
import com.example.demo.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AlibabaAiServicePosterTests {
    @Test
    void requestsVerticalPosterWithoutPromptRewriting() {
        AiConfig config = new AiConfig();
        config.setApiKey("test-key");
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AlibabaAiService service = new AlibabaAiService(
                config,
                new ToolCallingEngine(new ToolRegistry(List.of())),
                restTemplate);

        server.expect(jsonPath("$.model").value("wan2.7-image-pro"))
                .andExpect(jsonPath("$.parameters.size").value("1728*2368"))
                .andExpect(jsonPath("$.parameters.n").value(1))
                .andExpect(jsonPath("$.parameters.watermark").value(false))
                .andExpect(jsonPath("$.parameters.thinking_mode").value(true))
                .andExpect(jsonPath("$.parameters.prompt_extend").doesNotExist())
                .andExpect(jsonPath("$.parameters.negative_prompt").doesNotExist())
                .andRespond(withSuccess("""
                        {"output":{"choices":[{"message":{"content":[
                          {"image":"https://example.test/poster.png"}
                        ]}}]}}
                        """, MediaType.APPLICATION_JSON));
        byte[] expected = new byte[]{1, 2, 3, 4};
        server.expect(request -> {
        }).andRespond(withSuccess(expected, MediaType.IMAGE_PNG));

        byte[] actual = service.generatePosterBackground("测试校园海报背景提示词");

        assertArrayEquals(expected, actual);
        server.verify();
    }

    @Test
    void detectsPseudoTextAndScoresBackgroundQuality() {
        AiConfig config = new AiConfig();
        config.setApiKey("test-key");
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AlibabaAiService service = new AlibabaAiService(
                config,
                new ToolCallingEngine(new ToolRegistry(List.of())),
                restTemplate);

        server.expect(jsonPath("$.model").value("qwen3-vl-flash"))
                .andExpect(jsonPath("$.messages[0].content").value(
                        org.hamcrest.Matchers.containsString("伪汉字")))
                .andExpect(jsonPath("$.messages[1].content[0].image_url.url").value(
                        org.hamcrest.Matchers.startsWith("data:image/png;base64,")))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"content":"{\\\"textDetected\\\":true,\\\"qualityScore\\\":48,\\\"reason\\\":\\\"中央存在伪汉字且画面俗套\\\"}"}}]}
                        """, MediaType.APPLICATION_JSON));

        PosterBackgroundReview review = service.reviewPosterBackground(new byte[]{1, 2, 3});

        assertTrue(review.textDetected());
        assertEquals(48, review.qualityScore());
        assertEquals("中央存在伪汉字且画面俗套", review.reason());
        server.verify();
    }
}
