package com.example.demo.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventSupplyEstimateToolTests {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EventSupplyEstimateTool tool = new EventSupplyEstimateTool();

    @Test
    void createsConcreteBufferedSupplyListWithClearlyLabelledPlanningCaps() throws Exception {
        JsonNode result = execute("{\"participant_count\":50}");

        assertTrue(result.path("success").asBoolean());
        assertEquals(55, result.path("buffered_attendance").asInt());
        assertEquals(10, result.path("quantity_buffer_percent").asInt());
        assertEquals(5, result.path("items").size());
        assertFalse(result.path("quote_obtained").asBoolean());
        assertTrue(result.path("source").asText().contains("不是商家报价"));

        JsonNode water = result.path("items").get(0);
        assertEquals("瓶装水", water.path("item_name").asText());
        assertEquals(55, water.path("quantity").asInt());
        assertDecimalEquals("2.00", water.path("planning_unit_cap").decimalValue());
        assertDecimalEquals("110.00", water.path("planned_subtotal").decimalValue());
        assertTrue(water.path("requires_verification").asBoolean());
    }

    @Test
    void doublesWaterForHotWeather() throws Exception {
        JsonNode result = execute("{\"participant_count\":50,\"hot_weather\":true}");

        assertEquals(110, result.path("items").get(0).path("quantity").asInt());
    }

    @Test
    void rejectsInvalidParticipantCount() {
        assertThrows(IllegalArgumentException.class,
                () -> execute("{\"participant_count\":0}"));
        assertThrows(IllegalArgumentException.class,
                () -> execute("{\"participant_count\":100001}"));
    }

    private JsonNode execute(String arguments) throws Exception {
        return objectMapper.readTree(tool.execute(objectMapper.readTree(arguments)));
    }

    private void assertDecimalEquals(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
