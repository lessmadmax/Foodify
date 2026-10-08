package com.example.foodify.ai;
import com.example.foodify.api.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WeightEstimateTest {
    private final ObjectMapper json=new ObjectMapper();
    private com.fasterxml.jackson.databind.JsonNode item(String grams,String low,String high,String assumptions) throws Exception {
        return json.readTree("{\"grams\":"+grams+",\"uncertainty\":\"크기 미확인\",\"weightEstimate\":{\"lowerGrams\":"+low+",\"upperGrams\":"+high+",\"assumptions\":"+assumptions+"}}");
    }
    @Test void acceptsRangeAndUnknown() throws Exception {
        WeightEstimate.validate(item("200","150","250","[\"보통 크기 1개\"]"));
        WeightEstimate.validate(item("null","null","null","[]"));
    }
    @Test void rejectsInconsistentRange() throws Exception {
        var a=item("200","220","250","[\"가정\"]");
        assertThrows(ApiError.class,()->WeightEstimate.validate(a));
        var b=item("null","100","200","[]");
        assertThrows(ApiError.class,()->WeightEstimate.validate(b));
    }
    @Test void requiresAssumptionsForNumbers() throws Exception {
        var a=item("200","150","250","[]");
        assertThrows(ApiError.class,()->WeightEstimate.validate(a));
    }
}
