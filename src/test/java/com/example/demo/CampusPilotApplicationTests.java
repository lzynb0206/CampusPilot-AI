package com.example.demo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "wechat.bot.enabled=false")
class CampusPilotApplicationTests {
    @Test
    void contextLoads() {
    }
}
