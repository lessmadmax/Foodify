package com.example.foodify.goal;

import com.example.foodify.api.ApiError;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/** General adult maintenance estimate, not a clinical prescription. */
public final class GoalCalculator {
    private GoalCalculator() {}
    public static Map<String,Object> calculate(JsonNode input) {
        if (!input.path("age").isIntegralNumber() || !input.path("age").canConvertToInt()) throw ApiError.bad("INVALID_PROFILE");
        int age = input.path("age").intValue();
        if (age < 19 || age > 78 || !input.path("generalAdultConfirmed").asBoolean(false)) throw ApiError.bad("GOAL_SCOPE_CONFIRMATION_REQUIRED");
        double height = number(input, "heightCm", 100, 250);
        double weight = number(input, "weightKg", 25, 350);
        String sex = input.path("sex").asText();
        if (!sex.equals("MALE") && !sex.equals("FEMALE")) throw ApiError.bad("INVALID_PROFILE");
        String activity = input.path("activityLevel").asText();
        double factor = switch(activity) {
            case "SEDENTARY" -> 1.2;
            case "LIGHT" -> 1.375;
            case "MODERATE" -> 1.55;
            case "ACTIVE" -> 1.725;
            default -> throw ApiError.bad("INVALID_PROFILE");
        };
        double resting = 10 * weight + 6.25 * height - 5 * age + (sex.equals("MALE") ? 5 : -161);
        if (resting <= 0) throw ApiError.bad("INVALID_PROFILE");
        long kcal = Math.round(resting * factor);
        var result = new LinkedHashMap<String,Object>();
        result.put("kcal", kcal);
        result.put("carbs", rounded(kcal * .50 / 4));
        result.put("protein", rounded(kcal * .20 / 4));
        result.put("fat", rounded(kcal * .30 / 9));
        result.put("profile", Map.of("age", age, "sex", sex, "heightCm", height, "weightKg", weight, "activityLevel", activity, "generalAdultConfirmed", true));
        result.put("calculation", Map.of("version", "mifflin-maintenance-v1", "activityFactor", factor, "restingKcal", rounded(resting), "macroPercent", Map.of("carbs",50,"protein",20,"fat",30), "note", "만 19~78세 일반 성인의 체중 유지 참고 추정치. 탄·단·지 50:20:30은 앱 기본 배분입니다. 임신·수유·질환별 식사 처방에는 별도 기준이 필요합니다."));
        return result;
    }
    private static double number(JsonNode input, String name, double min, double max) {
        JsonNode n = input.path(name);
        if (!n.isNumber() || !Double.isFinite(n.asDouble()) || n.asDouble() < min || n.asDouble() > max) throw ApiError.bad("INVALID_PROFILE");
        return n.asDouble();
    }
    private static BigDecimal rounded(double value) {return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP);}
}
