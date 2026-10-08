package com.example.foodify.food;
import com.example.foodify.api.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.math.*;
import java.util.*;

@Service
public class FoodService {
    private final JdbcTemplate db;
    public FoodService(JdbcTemplate db) { this.db=db; }
    public List<Map<String,Object>> search(String query) {
        if(query==null || query.isBlank() || query.length()>100) return List.of();
        var terms=FoodSearchTerms.stages(query);
        for(int stage=0;stage<terms.size();stage++) {
            String term=terms.get(stage).replace("!","!!").replace("%","!%").replace("_","!_");
            String name=normalizedColumn("name"), aliases=normalizedColumn("aliases"), alias=normalizedColumn("alias");
            var rows=db.queryForList("SELECT * FROM foods WHERE searchable=TRUE AND basis_unit='g' AND ("+name+" LIKE ? ESCAPE '!' OR "+aliases+" LIKE ? ESCAPE '!' OR id IN (SELECT food_id FROM food_aliases WHERE "+alias+" LIKE ? ESCAPE '!')) ORDER BY CASE WHEN "+name+"=? THEN 0 ELSE 1 END, CASE WHEN category='음식' THEN 0 ELSE 1 END, CASE WHEN kcal IS NOT NULL AND carbs IS NOT NULL AND protein IS NOT NULL AND fat IS NOT NULL THEN 0 ELSE 1 END, name LIMIT 20","%"+term+"%","%"+term+"%","%"+term+"%",terms.get(stage));
            if(!rows.isEmpty()) {
                for(var row:rows) { row.put("searchTerm",terms.get(stage)); row.put("searchFallback",stage>0); }
                return rows;
            }
        }
        return List.of();
    }
    private static String normalizedColumn(String column) {
        // Column names are internal constants; user terms remain SQL parameters.
        return "LOWER(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE("+column+",' ',''),'_',''),'-',''),CHAR(9),''),'　',''))";
    }
    public Map<String,Object> calculate(JsonNode input) {
        String name=input.path("name").asText("").trim();
        if(name.isBlank() || name.length()>200) throw ApiError.bad("INVALID_FOOD_NAME");
        var result=new LinkedHashMap<String,Object>();
        result.put("name",name); result.put("foodId",input.path("foodId").asText(""));
        result.put("grams",null); result.put("nutrition",null); result.put("source",null);
        // Completion depends on calculation evidence, not a user confirmation flag.
        result.put("status","NEEDS_REVIEW");
        if(!input.path("grams").isNumber()) return result;
        BigDecimal grams=input.get("grams").decimalValue();
        if(grams.signum()<=0 || grams.compareTo(new BigDecimal("10000"))>0) throw ApiError.bad("INVALID_GRAMS");
        result.put("grams",grams);
        var rows=db.queryForList("SELECT * FROM foods WHERE id=?",input.path("foodId").asText(""));
        if(rows.isEmpty()) return result;
        var food=rows.getFirst(); result.put("source",food);
        if(!"g".equals(food.get("basis_unit")) || !Boolean.TRUE.equals(food.get("searchable"))) return result;
        var nutrients=new LinkedHashMap<String,Object>();
        for(String nutrient:List.of("kcal","carbs","protein","fat")) {
            if(food.get(nutrient)==null) return result;
            nutrients.put(nutrient,scale((BigDecimal)food.get(nutrient),grams,(BigDecimal)food.get("basis_grams")));
        }
        result.put("nutrition",nutrients);
        result.put("status","COMPLETE");
        return result;
    }
    public static BigDecimal scale(BigDecimal value,BigDecimal grams,BigDecimal basis) {
        if(basis.signum()<=0) throw ApiError.bad("INVALID_BASIS");
        return value.multiply(grams).divide(basis,2,RoundingMode.HALF_UP);
    }
}
