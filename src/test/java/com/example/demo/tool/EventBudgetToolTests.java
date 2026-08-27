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
    private final EventSupplyEstimateTool supplyTool = new EventSupplyEstimateTool();
    private final EventBudgetTool tool = new EventBudgetTool();

    @Test
    void allocatesDetailedWholeBudgetWithoutPretendingPlanningCapsAreQuotes() throws Exception {
        JsonNode result = executeWithSupplies("2000", 50);

        assertTrue(result.path("success").asBoolean());
        assertEquals("PLANNING_CAP_NOT_QUOTE", result.path("pricing_status").asText());
        assertFalse(result.path("quote_obtained").asBoolean());
        assertDecimalEquals("2000.00", result.path("allocated_total").decimalValue());
        assertDecimalEquals("0.00", result.path("unallocated").decimalValue());
        assertDecimalEquals("40.00", result.path("per_capita_budget").decimalValue());
        assertEquals(10, result.path("items").size());

        JsonNode water = result.path("items").get(0);
        assertEquals("瓶装水", water.path("item_name").asText());
        assertDecimalEquals("55", water.path("quantity").decimalValue());
        assertEquals("瓶", water.path("unit").asText());
        assertDecimalEquals("2.00", water.path("unit_price_cap").decimalValue());
        assertDecimalEquals("110.00", water.path("amount").decimalValue());
        assertTrue(water.path("requires_verification").asBoolean());

        JsonNode reserve = result.path("items").get(9);
        assertEquals("应急备用金", reserve.path("category").asText());
        assertDecimalEquals("300.00", reserve.path("amount").decimalValue());
        assertEquals(4, result.path("verification_steps").size());
    }

    @Test
    void keepsEveryLineAndRoundedTotalMathematicallyConsistent() throws Exception {
        JsonNode result = executeWithSupplies("500.01", 3);
        BigDecimal itemTotal = BigDecimal.ZERO;
        for (JsonNode item : result.path("items")) {
            BigDecimal calculated = item.path("quantity").decimalValue()
                    .multiply(item.path("unit_price_cap").decimalValue())
                    .setScale(2);
            assertDecimalEquals(item.path("amount").decimalValue().toPlainString(), calculated);
            itemTotal = itemTotal.add(item.path("amount").decimalValue());
        }

        assertDecimalEquals("500.01", itemTotal);
        assertEquals(0, result.path("total_budget").decimalValue().compareTo(itemTotal));
    }

    @Test
    void requiresSupplyToolOutputAndRejectsInvalidInputs() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> execute("{\"total_budget\":2000,\"participant_count\":50}"));
        assertThrows(IllegalArgumentException.class,
                () -> execute("{\"total_budget\":2000,\"participant_count\":50,\"supply_items\":[]}"));
        assertThrows(IllegalArgumentException.class,
                () -> execute("{\"total_budget\":0,\"participant_count\":50,\"supply_items\":[{}]}"));
        assertThrows(IllegalArgumentException.class,
                () -> execute("{\"total_budget\":2000,\"participant_count\":0,\"supply_items\":[{}]}"));
        assertThrows(IllegalArgumentException.class,
                () -> execute("{\"total_budget\":12.345,\"participant_count\":5,\"supply_items\":[{}]}"));
    }

    @Test
    void exposesStrictFunctionCallingSchema() {
        assertEquals("allocate_event_budget", tool.name());
        assertEquals("object", tool.parametersSchema().get("type"));
        assertFalse((Boolean) tool.parametersSchema().get("additionalProperties"));
    }

    private JsonNode executeWithSupplies(String totalBudget, int participants) throws Exception {
        JsonNode supplies = objectMapper.readTree(supplyTool.execute(objectMapper.createObjectNode()
                .put("participant_count", participants)));
        JsonNode arguments = objectMapper.createObjectNode()
                .put("total_budget", new BigDecimal(totalBudget))
                .put("participant_count", participants)
                .set("supply_items", supplies.path("items"));
        return objectMapper.readTree(tool.execute(arguments));
    }

    private JsonNode execute(String arguments) throws Exception {
        return objectMapper.readTree(tool.execute(objectMapper.readTree(arguments)));
    }

    private void assertDecimalEquals(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
