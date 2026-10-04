package com.example.foodify.goal;

import com.example.foodify.api.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class GoalCalculatorTest {
    private ObjectNode input() {
        return new ObjectMapper().createObjectNode().put("age",30).put("sex","MALE")
            .put("heightCm",180).put("weightKg",80).put("activityLevel","SEDENTARY")
            .put("generalAdultConfirmed",true);
    }
    @Test void maintenanceAndMacrosAreDeterministic() {
        var result=GoalCalculator.calculate(input());
        assertEquals(2136L,result.get("kcal"));
        assertEquals(new BigDecimal("267.0"),result.get("carbs"));
        assertEquals(new BigDecimal("106.8"),result.get("protein"));
        assertEquals(new BigDecimal("71.2"),result.get("fat"));
        assertFalse(result.containsKey("purpose"));
    }
    @Test void sexAndActivityAffectEstimate() {
        assertEquals(1937L,GoalCalculator.calculate(input().put("sex","FEMALE")).get("kcal"));
        assertEquals(2759L,GoalCalculator.calculate(input().put("activityLevel","MODERATE")).get("kcal"));
    }
    @Test void rejectsUnsupportedOrMissingProfile() {
        assertThrows(ApiError.class,()->GoalCalculator.calculate(input().put("age",18)));
        assertThrows(ApiError.class,()->GoalCalculator.calculate(input().put("age",79)));
        assertThrows(ApiError.class,()->GoalCalculator.calculate(input().put("age",30.5)));
        assertThrows(ApiError.class,()->GoalCalculator.calculate(input().put("weightKg",-1)));
        assertThrows(ApiError.class,()->GoalCalculator.calculate(input().put("heightCm","180")));
        assertThrows(ApiError.class,()->GoalCalculator.calculate(input().put("sex","OTHER")));
        assertThrows(ApiError.class,()->GoalCalculator.calculate(input().put("activityLevel","UNKNOWN")));
        assertThrows(ApiError.class,()->GoalCalculator.calculate(input().put("generalAdultConfirmed",false)));
    }
}
