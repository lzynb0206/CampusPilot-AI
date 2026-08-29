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

        server.expect(jsonPath("$.model").value("qwen-image-2.0"))
                .andExpect(jsonPath("$.parameters.size").value("1728*2368"))
                .andExpect(jsonPath("$.parameters.n").value(1))
                .andExpect(jsonPath("$.parameters.prompt_extend").value(false))
                .andExpect(jsonPath("$.parameters.negative_prompt").exists())
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
}
