package com.example.foodify.ai;
import com.example.foodify.api.ApiError;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

public final class WeightEstimate {
    private WeightEstimate() {}
    public static Map<String,Object> schema() {
        var number=Map.of("type",List.of("number","null"));
        return Map.of("type","object","additionalProperties",false,"properties",Map.of(
            "lowerGrams",number,"upperGrams",number,
            "assumptions",Map.of("type","array","items",Map.of("type","string"))),
            "required",List.of("lowerGrams","upperGrams","assumptions"));
    }
    public static void validate(JsonNode item) {
        var estimate=item.path("weightEstimate");
        var grams=item.path("grams"); var low=estimate.path("lowerGrams"); var high=estimate.path("upperGrams");
        var assumptions=estimate.path("assumptions");
        if(!estimate.isObject() || !assumptions.isArray() || assumptions.size()>5) invalid();
        for(var assumption:assumptions) if(!assumption.isTextual() || assumption.asText().isBlank() || assumption.asText().length()>300) invalid();
        if(grams.isNull()) {
            if(!low.isNull() || !high.isNull() || item.path("uncertainty").asText().isBlank()) invalid();
        } else {
            if(!positive(grams) || !positive(low) || !positive(high) ||
                low.asDouble()>grams.asDouble() || grams.asDouble()>high.asDouble() || assumptions.isEmpty()) invalid();
        }
    }
    private static boolean positive(JsonNode n) { return n.isNumber() && Double.isFinite(n.asDouble()) && n.asDouble()>0 && n.asDouble()<=10000; }
    private static void invalid() { throw ApiError.bad("AI_INVALID_OUTPUT"); }
}
