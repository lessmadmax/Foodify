package com.example.foodify.food;

import com.example.foodify.api.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class NutritionCatalogTest {
    @Autowired NutritionCatalogImporter importer;
    @Autowired FoodService foods;
    @Autowired JdbcTemplate db;
    @Autowired Json json;
    @TempDir Path temp;
    private Path fixture() throws Exception {
        Path path=temp.resolve("catalog.jsonl");
        Files.writeString(path,"""
            {"type":"dataset","id":"test-dataset","filename":"sample.csv","encoding":"utf-8","rowCount":1}
            {"type":"food","id":"catalog-test","name":"시험밥","aliases":"시험 밥","category":"음식","originalCode":"D1","manufacturer":"","currentVersionId":"test-version","searchable":true}
            {"type":"version","id":"test-version","foodId":"catalog-test","name":"시험밥","basisAmount":100,"basisUnit":"g","rawBasis":"100g","kcal":150,"carbs":30,"protein":4,"fat":2,"source":"시험자료","sourceDate":"2026-09-28","portion":{"amount":200,"unit":"g"}}
            {"type":"sourceRow","datasetId":"test-dataset","rowNumber":2,"versionId":"test-version","raw":{"식품명":"시험밥"}}
            """);
        return path;
    }
    @Test void repeatImportPreservesCountsAndCalculatesSnapshot() throws Exception {
        Path file=fixture(); importer.importFile(file); importer.importFile(file);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM nutrition_source_rows WHERE dataset_id='test-dataset'",Integer.class)).isEqualTo(1);
        assertThat(foods.search("시험 밥")).hasSize(1);
        var result=foods.calculate(json.read("{\"name\":\"시험밥\",\"foodId\":\"catalog-test\",\"grams\":200,\"confirmed\":true}"));
        assertThat(result.get("status")).isEqualTo("COMPLETE");
        assertThat(result.get("nutrition").toString()).contains("kcal=300.00");
        assertThat(result.get("source").toString()).contains("test-version");
        db.update("UPDATE foods SET kcal=200 WHERE id='catalog-test'");
        assertThat(result.get("nutrition").toString()).contains("kcal=300.00");
    }
    @Test void volumeAndMissingValuesRequireReview() throws Exception {
        importer.importFile(fixture());
        db.update("UPDATE foods SET basis_unit='ml' WHERE id='catalog-test'");
        assertThat(foods.search("시험밥")).isEmpty();
        var input=json.read("{\"name\":\"시험밥\",\"foodId\":\"catalog-test\",\"grams\":200,\"confirmed\":true}");
        assertThat(foods.calculate(input).get("status")).isEqualTo("NEEDS_REVIEW");
        db.update("UPDATE foods SET basis_unit='g',fat=NULL WHERE id='catalog-test'");
        assertThat(foods.calculate(input).get("nutrition")).isNull();
    }
    @Test void badRecordRollsBackEntireImport() throws Exception {
        Path path=fixture(); Files.writeString(path,"{\"type\":\"invalid\"}\n",StandardOpenOption.APPEND);
        assertThatThrownBy(()->importer.importFile(path)).isInstanceOf(IllegalArgumentException.class);
    }
}
