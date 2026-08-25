package com.example.demo.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventBudgetToolTests {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EventBudgetTool tool = new EventBudgetTool();

    @Test
    void allocatesWholeBudgetAndCalculatesPerCapitaAmount() throws Exception {
        JsonNode result = execute("{\"total_budget\":2000,\"participant_count\":50}");

        assertTrue(result.path("success").asBoolean());
        assertDecimalEquals("2000.00", result.path("allocated_total").decimalValue());
        assertDecimalEquals("0.00", result.path("unallocated").decimalValue());
        assertDecimalEquals("40.00", result.path("per_capita_budget").decimalValue());
        assertEquals(6, result.path("items").size());
        assertEquals("应急备用金", result.path("items").get(5).path("category").asText());
        assertDecimalEquals("300.00", result.path("items").get(5).path("amount").decimalValue());
    }

    @Test
    void keepsRoundedLineItemsEqualToDecimalTotal() throws Exception {
        JsonNode result = execute("{\"total_budget\":100.01,\"participant_count\":3}");
        BigDecimal itemTotal = BigDecimal.ZERO;
        for (JsonNode item : result.path("items")) {
            itemTotal = itemTotal.add(item.path("amount").decimalValue());
        }

        assertDecimalEquals("100.01", itemTotal);
        assertEquals(0, result.path("total_budget").decimalValue().compareTo(itemTotal));
    }

    @Test
    void rejectsInvalidBudgetAndParticipantCount() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> execute("{\"total_budget\":0,\"participant_count\":50}"));
        assertThrows(IllegalArgumentException.class,
                () -> execute("{\"total_budget\":2000,\"participant_count\":0}"));
        assertThrows(IllegalArgumentException.class,
                () -> execute("{\"total_budget\":12.345,\"participant_count\":5}"));
    }

    @Test
    void exposesStrictFunctionCallingSchema() {
        assertEquals("allocate_event_budget", tool.name());
        assertEquals("object", tool.parametersSchema().get("type"));
        assertFalse((Boolean) tool.parametersSchema().get("additionalProperties"));
    }

    private JsonNode execute(String arguments) throws Exception {
        return objectMapper.readTree(tool.execute(objectMapper.readTree(arguments)));
    }

    private void assertDecimalEquals(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
