package com.example.foodify.api;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Component;

@Component
public class Json {
    private final ObjectMapper mapper;
    public Json(ObjectMapper mapper) { this.mapper=mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS); }
    public String write(Object value) {
        try { return mapper.writeValueAsString(value); } catch(Exception e) { throw new IllegalStateException(e); }
    }
    public JsonNode read(String value) {
        try { return mapper.readTree(value); } catch(Exception e) { throw ApiError.bad("INVALID_JSON"); }
    }
}
