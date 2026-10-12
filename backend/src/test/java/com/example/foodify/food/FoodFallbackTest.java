package com.example.foodify.food;
import com.example.foodify.api.Json;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @Transactional
class FoodFallbackTest {
    @Autowired FoodService foods; @Autowired FoodCategoryService categories;
    @Autowired JdbcTemplate db; @Autowired Json json;
    @Autowired com.example.foodify.meal.MealService meals;
    @BeforeEach void seed() {
        categories.invalidate();
        db.update("DELETE FROM nutrition_source_rows"); db.update("DELETE FROM food_portions");
        db.update("DELETE FROM food_nutrition_versions"); db.update("DELETE FROM food_aliases"); db.update("DELETE FROM foods");
        db.update("INSERT INTO nutrition_datasets VALUES('category-test','test','utf-8',10,0)");
        insert("a","배추김치","김치류","배추김치",100,20,1);
        insert("b","갓김치","김치류","갓김치",200,80,2);
        insert("rice","김치볶음밥","밥류","볶음밥",100,200,3);
        insert("stew","김치찌개","찌개류","김치찌개",100,100,4);
    }
    @AfterEach void clearCache() { categories.invalidate(); }
    void insert(String id,String name,String major,String representative,int basis,int kcal,int row) {
        db.update("INSERT INTO foods(id,name,aliases,basis_grams,kcal,carbs,protein,fat,source,source_version,category) VALUES(?,?,?,?,?,10,2,1,'test','v1','음식')",id,name,name,basis,kcal);
        db.update("INSERT INTO food_nutrition_versions(id,food_id,name,basis_amount,basis_unit,kcal,source,source_date,raw_basis) VALUES(?,?,?,?,'g',?,'test','2026','100g')","v-"+id,id,name,basis,kcal);
        db.update("INSERT INTO nutrition_source_rows VALUES('category-test',?,?,?)",row,"v-"+id,json.write(Map.of("식품대분류명",major,"대표식품명",representative)));
    }
    @Test void unseenKimchiNamesUseCategoryMedianNotPinnedFood() {
        for(String name:new String[]{"무청김치","처음보는채소김치"}) {
            var matches=foods.search(name);
            assertThat(matches).hasSize(1);
            var category=matches.getFirst();
            assertThat(category.get("name")).isEqualTo("김치류");
            assertThat(category.get("sampleCount")).isEqualTo(2);
            assertThat(category.get("kcal")).isEqualTo(new BigDecimal("30.0000"));
            var result=foods.calculate(json.read(json.write(Map.of("name",name,"grams",50))));
            assertThat(result.get("name")).isEqualTo(name);
            assertThat(result.get("status")).isEqualTo("COMPLETE");
            assertThat(((Map<?,?>)result.get("nutrition")).get("kcal")).isEqualTo(new BigDecimal("15.00"));
            assertThat(((Map<?,?>)foods.calculate(json.read(json.write(result))).get("source")).get("fallbackNotice")).isNotNull();
        }
    }
    @Test void exactAndVerifiedAliasTakePriority() {
        db.update("UPDATE foods SET name='무청김치' WHERE id='a'");
        assertThat(foods.search("무청 김치").getFirst().get("id")).isEqualTo("a");
        db.update("UPDATE foods SET name='배추김치' WHERE id='a'");
        db.update("INSERT INTO food_aliases VALUES('b','무청김치')");
        assertThat(foods.search("무청김치").getFirst().get("id")).isEqualTo("b");
    }
    @Test void usesOtherCategoriesWithoutAddingRulesAndAvoidsSubstringDishes() {
        assertThat(foods.search("버섯볶음밥").getFirst().get("name")).isEqualTo("볶음밥");
        assertThat(foods.search("무청김치찌개").getFirst().get("name")).isEqualTo("김치찌개");
        assertThat(foods.search("무청김치").getFirst().get("memberIds").toString()).doesNotContain("rice","stew");
        assertThat(foods.search("미확인메뉴")).isEmpty();
    }
    @Test void excludesInvalidRowsAndPrefersGeneralFoodOverBrands() {
        insert("brand","브랜드김치","김치류","김치",100,900,5);
        db.update("UPDATE foods SET manufacturer='브랜드' WHERE id='brand'");
        assertThat(foods.search("무청김치").getFirst().get("sampleCount")).isEqualTo(2);
        db.update("UPDATE foods SET fat=NULL WHERE id='b'");
        assertThat(foods.search("무청김치").getFirst().get("sampleCount")).isEqualTo(1);
        db.update("UPDATE foods SET basis_unit='ml' WHERE id IN ('a','brand')");
        assertThat(categories.fallback("무청김치")).isNull();
    }
    @Test void requiresWeightAndRejectsUnrelatedCategoryIds() {
        assertThat(foods.calculate(json.read("{\"name\":\"무청김치\",\"grams\":null}")).get("nutrition")).isNull();
        String unrelated=foods.search("버섯볶음밥").getFirst().get("id").toString();
        var result=foods.calculate(json.read(json.write(Map.of("name","무청김치","grams",50,"foodId",unrelated))));
        assertThat(result.get("status")).isEqualTo("NEEDS_REVIEW");
        var manual=foods.calculate(json.read("{\"name\":\"무청김치\",\"foodId\":\"b\",\"grams\":50}"));
        assertThat(manual.get("foodId")).isEqualTo("b");
        assertThat(((Map<?,?>)manual.get("source")).containsKey("fallbackNotice")).isFalse();
    }
    @Test void duplicatesInSourceRowsDoNotIncreaseSampleCount() {
        db.update("INSERT INTO nutrition_source_rows SELECT dataset_id,99,version_id,raw_json FROM nutrition_source_rows WHERE row_number=1");
        assertThat(foods.search("무청김치").getFirst().get("sampleCount")).isEqualTo(2);
    }
    @Test void explicitSaveUpgradesOldServerMarkedFallbackButNotManualSelection() {
        db.update("INSERT INTO members(id,email,password_hash,goals,created_at) VALUES('category-user','category@test.invalid','unused','{}',0)");
        String previous="[{\"name\":\"무청김치\",\"grams\":50,\"foodId\":\"a\",\"source\":{\"matchType\":\"CATEGORY_FALLBACK\"}}]";
        db.update("INSERT INTO meals VALUES('category-meal','category-user','test',0,'INCOMPLETE',0,?,'{}',0)",previous);
        var result=meals.update("category-user","category-meal",0,json.read(previous));
        var items=(com.fasterxml.jackson.databind.JsonNode)result.get("items");
        assertThat(items.get(0).path("foodId").asText()).startsWith("category:");
        assertThat(items.get(0).path("source").path("sampleCount").asInt()).isEqualTo(2);
        var manual=meals.update("category-user","category-meal",1,json.read("[{\"name\":\"무청김치\",\"grams\":50,\"foodId\":\"b\"}]"));
        assertThat(((com.fasterxml.jackson.databind.JsonNode)manual.get("items")).get(0).path("foodId").asText()).isEqualTo("b");
    }
}
