package com.example.foodify.food;
import com.example.foodify.api.Json;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @Transactional
class FoodSearchTest {
    @Autowired FoodService foods; @Autowired JdbcTemplate db; @Autowired Json json;
    @BeforeEach void seed() {
        db.update("DELETE FROM food_aliases"); db.update("DELETE FROM foods");
        db.update("INSERT INTO foods(id,name,aliases,basis_grams,kcal,carbs,protein,fat,source,source_version,category) VALUES('burger','햄버거_치즈버거','치즈버거',100,250,20,15,10,'test','v1','음식')");
        db.update("INSERT INTO foods(id,name,aliases,basis_grams,kcal,carbs,protein,fat,source,source_version,category) VALUES('bulgogi','불고기 버거','불고기 버거',100,250,20,15,10,'test','v1','음식')");
    }
    @Test void normalizesSpacingBothWays() {
        assertThat(foods.search("불고기버거").getFirst().get("id")).isEqualTo("bulgogi");
        assertThat(foods.search("불고기 버거").getFirst().get("searchFallback")).isEqualTo(false);
    }
    @Test void fallsBackSpecificBeforeGeneric() {
        assertThat(FoodSearchTerms.stages("소고기 치즈 햄버거")).isEqualTo(List.of("소고기치즈햄버거","치즈버거","햄버거"));
        var result=foods.search("소고기 치즈 햄버거");
        assertThat(result.getFirst().get("id")).isEqualTo("burger");
        assertThat(result.getFirst().get("searchFallback")).isEqualTo(true);
        db.update("UPDATE foods SET name='햄버거_일반',aliases='햄버거' WHERE id='burger'");
        assertThat(foods.search("소고기 치즈 햄버거").getFirst().get("searchTerm")).isEqualTo("햄버거");
    }
    @Test void missingAndWildcardsStayEmpty() {
        assertThat(foods.search("%" )).isEmpty(); assertThat(foods.search("   ")).isEmpty();
        assertThat(foods.search("존재하지않는음식")).isEmpty();
    }
    @Test void completesWithoutConfirmationButRequiresEvidence() {
        assertThat(foods.calculate(json.read("{\"name\":\"버거\",\"foodId\":\"burger\",\"grams\":200}")).get("status")).isEqualTo("COMPLETE");
        assertThat(foods.calculate(json.read("{\"name\":\"버거\",\"foodId\":\"burger\",\"grams\":null}")).get("status")).isEqualTo("NEEDS_REVIEW");
    }
}
