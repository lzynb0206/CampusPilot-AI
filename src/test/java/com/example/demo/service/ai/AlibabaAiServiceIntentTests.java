package com.example.demo.service.ai;

import com.example.demo.config.AiConfig;
import com.example.demo.model.ActionType;
import com.example.demo.model.ReplyMode;
import com.example.demo.tool.ToolCallingEngine;
import com.example.demo.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AlibabaAiServiceIntentTests {
    @Test
    void usesLocalIntentClassificationByDefaultWithoutApiKey() {
        AiConfig config = new AiConfig();
        AlibabaAiService service = new AlibabaAiService(
                config,
                new ToolCallingEngine(new ToolRegistry(List.of())),
                new RestTemplate());

        var weather = service.recognizeIntent("张家港今天天气怎么样", ReplyMode.TEXT);
        var image = service.recognizeIntent("帮我画一张校园海报", ReplyMode.TEXT);
        var chat = service.recognizeIntent("介绍一下Agent", ReplyMode.TEXT);

        assertEquals(ActionType.WEATHER, weather.action());
        assertEquals("张家港", weather.location());
        assertEquals(ActionType.IMAGE_GENERATION, image.action());
        assertEquals(ActionType.CHAT, chat.action());
    }
}
