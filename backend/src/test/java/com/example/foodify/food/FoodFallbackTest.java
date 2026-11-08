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
    void insertRaw(String id,String name,String major,String representative,int kcal,int row) {
        insert(id,name,major,representative,100,kcal,row);
        db.update("UPDATE foods SET category='원재료성 식품' WHERE id=?",id);
    }
    void insertRaw(String id,String name,String major,String representative,String state,int kcal,int row) {
        insertRaw(id,name,major,representative,kcal,row);
        db.update("UPDATE nutrition_source_rows SET raw_json=? WHERE version_id=?",json.write(Map.of("식품대분류명",major,"대표식품명",representative,"식품세분류명",state)),"v-"+id);
    }
    @Test void rawIngredientsPreferFreshAndHonorStateWords() {
        insertRaw("banana-fresh","바나나_생것","과일류","바나나","생것",77,20);
        insertRaw("banana-dry","바나나_말린것","과일류","바나나","말린것",314,21);
        insertRaw("egg-fresh","달걀_생것","난류","달걀","생것",146,22);
        insertRaw("egg-boiled","달걀_삶은것","난류","달걀","삶은것",155,23);
        insertRaw("potato-baked","감자_구운것(팬)","감자 및 전분류","감자","구운것(팬)",120,24);
        insertRaw("peanut-dry","땅콩_말린것","견과 및 종실류","땅콩","말린것",520,25);
        insertRaw("peanut-roast2","땅콩_볶은것","견과 및 종실류","땅콩","볶은것",567,26);
        insertRaw("sweet-potato-1","고구마_생것","감자 및 전분류","고구마","생것",131,27);
        insertRaw("sweet-potato-2","고구마_찐것","감자 및 전분류","고구마","찐것",147,28);
        insertRaw("sweet-potato-leaf","고구마_줄기_생것","채소류","고구마","생것",23,29);
        var banana=foods.search("바나나").getFirst();
        assertThat(banana.get("kcal")).isEqualTo(new BigDecimal("77.0000"));
        assertThat(banana.get("rawState")).isEqualTo("생것");
        assertThat(banana.get("name")).isEqualTo("바나나(생것)");
        var dried=foods.search("말린 바나나").getFirst();
        assertThat(dried.get("kcal")).isEqualTo(new BigDecimal("314.0000"));
        assertThat(dried.get("id")).isNotEqualTo(banana.get("id"));
        assertThat(categories.majorOf(dried.get("id").toString())).isEqualTo("과일류");
        assertThat(foods.search("삶은달걀").getFirst().get("kcal")).isEqualTo(new BigDecimal("155.0000"));
        assertThat(foods.search("구운 감자").getFirst().get("memberIds").toString()).contains("potato-baked");
        // No fresh peanuts: every state; "생땅콩" has no matching state so raw matching is skipped.
        assertThat(foods.search("땅콩").getFirst().get("rawState")).isEqualTo("ALL");
        assertThat(foods.search("생땅콩").stream().map(r->r.get("samplePolicy")).toList()).doesNotContain("RAW_REPRESENTATIVE");
        // Same representative in two majors: the one with more items wins.
        var sweet=foods.search("고구마").getFirst();
        assertThat(sweet.get("majorCategory")).isEqualTo("감자 및 전분류");
        assertThat(sweet.get("memberIds").toString()).contains("sweet-potato-1").doesNotContain("sweet-potato-leaf","sweet-potato-2");
        var result=foods.calculate(json.read(json.write(Map.of("name","말린 바나나","grams",10))));
        assertThat(result.get("status")).isEqualTo("COMPLETE");
        assertThat(((Map<?,?>)result.get("nutrition")).get("kcal")).isEqualTo(new BigDecimal("31.40"));
    }
    @Test void bareIngredientNameUsesRawRepresentativeMedianAndNutDensity() {
        insert("bread","기타빵_카라멜아몬드","빵 및 과자류","기타빵",100,450,10);
        insertRaw("almond-roast","아몬드_볶은것","견과 및 종실류","아몬드",594,11);
        insertRaw("almond-dry","아몬드_말린것","견과 및 종실류","아몬드",581,12);
        insertRaw("almond-oil","아몬드유","유지류","아몬드유",884,13);
        insertRaw("peanut-roast","땅콩_볶은것","견과 및 종실류","땅콩",567,14);
        insert("peanut-side","땅콩조림","반찬류","조림",100,300,15);
        var almond=foods.search("아몬드");
        assertThat(almond).hasSize(1);
        assertThat(almond.getFirst().get("samplePolicy")).isEqualTo("RAW_REPRESENTATIVE");
        assertThat(almond.getFirst().get("memberIds").toString()).contains("almond-roast","almond-dry").doesNotContain("almond-oil","bread");
        assertThat(almond.getFirst().get("kcal")).isEqualTo(new BigDecimal("587.5000"));
        assertThat(almond.getFirst().get("majorCategory")).isEqualTo("견과 및 종실류");
        String id=almond.getFirst().get("id").toString();
        assertThat(categories.majorOf(id)).isEqualTo("견과 및 종실류");
        assertThat(DensityGroups.of(categories.majorOf(id)).id()).isEqualTo("NUT");
        assertThat(foods.search("땅콩").getFirst().get("memberIds").toString()).contains("peanut-roast").doesNotContain("peanut-side");
        var result=foods.calculate(json.read(json.write(Map.of("name","아몬드","grams",10))));
        assertThat(result.get("status")).isEqualTo("COMPLETE");
        assertThat(((Map<?,?>)result.get("nutrition")).get("kcal")).isEqualTo(new BigDecimal("58.75"));
        // Exact names and dish names containing the ingredient keep their own matches.
        assertThat(foods.search("아몬드_볶은것").getFirst().get("id")).isEqualTo("almond-roast");
        assertThat(foods.search("카라멜아몬드").getFirst().get("id")).isEqualTo("bread");
    }
    @Test void duplicatesInSourceRowsDoNotIncreaseSampleCount() {
        db.update("INSERT INTO nutrition_source_rows SELECT dataset_id,99,version_id,raw_json FROM nutrition_source_rows WHERE row_number=1");
        assertThat(foods.search("무청김치").getFirst().get("sampleCount")).isEqualTo(2);
    }
    @Test void arVolumeUsesMajorCategoryDensityAndSurvivesUnchangedSave() {
        assertThat(categories.majorOf("rice")).isEqualTo("밥류");
        assertThat(categories.majorOf("a")).isEqualTo("김치류");
        assertThat(DensityGroups.of("밥류").density()).isEqualByComparingTo("0.73");
        assertThat(DensityGroups.of("없는분류").id()).isEqualTo("DEFAULT");
        db.update("INSERT INTO members(id,email,password_hash,goals,created_at) VALUES('ar-user','ar@test.invalid','unused','{}',0)");
        db.update("INSERT INTO meals VALUES('ar-meal','ar-user','test',0,'INCOMPLETE',0,'[]','{}',0)");
        var result=meals.update("ar-user","ar-meal",0,json.read("[{\"name\":\"김치볶음밥\",\"grams\":250,\"foodId\":\"rice\",\"arMeasurement\":{\"volumeMl\":100,\"qualityHold\":true,\"holdReasons\":[\"기준면 불안정\"]}}]"));
        var item=((com.fasterxml.jackson.databind.JsonNode)result.get("items")).get(0);
        assertThat(item.path("grams").decimalValue()).isEqualByComparingTo("73.0");
        assertThat(item.path("weightMeasurement").path("densityGroup").asText()).isEqualTo("RICE");
        assertThat(item.path("weightMeasurement").path("qualityHold").asBoolean()).isTrue();
        assertThat(item.path("nutrition").path("kcal").decimalValue()).isEqualByComparingTo("146.00");
        assertThat(result.get("status")).isEqualTo("COMPLETE");
        var again=meals.update("ar-user","ar-meal",1,json.read("[{\"name\":\"김치볶음밥\",\"grams\":73.0,\"foodId\":\"rice\"}]"));
        assertThat(((com.fasterxml.jackson.databind.JsonNode)again.get("items")).get(0).path("weightMeasurement").path("method").asText()).isEqualTo("AR_VOLUME");
        var edited=meals.update("ar-user","ar-meal",2,json.read("[{\"name\":\"김치볶음밥\",\"grams\":90,\"foodId\":\"rice\"}]"));
        assertThat(((com.fasterxml.jackson.databind.JsonNode)edited.get("items")).get(0).has("weightMeasurement")).isFalse();
        assertThatThrownBy(()->meals.update("ar-user","ar-meal",3,json.read("[{\"name\":\"김치볶음밥\",\"foodId\":\"rice\",\"arMeasurement\":{\"volumeMl\":-1}}]"))).hasMessageContaining("INVALID_AR_VOLUME");
    }
    @Test void areaHeightWeightUsesAreaTimesHeightTimesCategoryDensity() {
        db.update("INSERT INTO members(id,email,password_hash,goals,created_at) VALUES('ah-user','ah@test.invalid','unused','{}',0)");
        db.update("INSERT INTO meals VALUES('ah-meal','ah-user','test',0,'INCOMPLETE',0,'[]','{}',0)");
        var preview=meals.areaHeightPreview("김치볶음밥","rice",20,2.5);
        assertThat(preview.get("volumeMl")).isEqualTo(50.0);
        assertThat((BigDecimal)preview.get("grams")).isEqualByComparingTo("36.5");
        assertThat(preview.get("densityGroup")).isEqualTo("RICE");
        var result=meals.update("ah-user","ah-meal",0,json.read("[{\"name\":\"김치볶음밥\",\"foodId\":\"rice\",\"arMeasurement\":{\"method\":\"AREA_HEIGHT\",\"areaCm2\":20,\"heightCm\":2.5,\"heightSamples\":[2.2,2.5,3.1],\"distanceMm\":300,\"depthVolumeMl\":12.3}}]"));
        var item=((com.fasterxml.jackson.databind.JsonNode)result.get("items")).get(0);
        assertThat(item.path("grams").decimalValue()).isEqualByComparingTo("36.5");
        var m=item.path("weightMeasurement");
        assertThat(m.path("method").asText()).isEqualTo("AREA_HEIGHT");
        assertThat(m.path("volumeMl").asDouble()).isEqualTo(50.0);
        assertThat(m.path("depthVolumeMl").asDouble()).isEqualTo(12.3);
        assertThat(m.path("heightSamples").size()).isEqualTo(3);
        assertThat(m.path("volumeValidated").asBoolean()).isFalse();
        var again=meals.update("ah-user","ah-meal",1,json.read("[{\"name\":\"김치볶음밥\",\"grams\":36.5,\"foodId\":\"rice\"}]"));
        assertThat(((com.fasterxml.jackson.databind.JsonNode)again.get("items")).get(0).path("weightMeasurement").path("method").asText()).isEqualTo("AREA_HEIGHT");
        assertThatThrownBy(()->meals.update("ah-user","ah-meal",2,json.read("[{\"name\":\"김치볶음밥\",\"foodId\":\"rice\",\"arMeasurement\":{\"method\":\"AREA_HEIGHT\",\"areaCm2\":20,\"heightCm\":40}}]"))).hasMessageContaining("INVALID_AR_HEIGHT");
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
