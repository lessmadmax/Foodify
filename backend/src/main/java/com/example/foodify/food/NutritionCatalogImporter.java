package com.example.foodify.food;

import com.example.foodify.api.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.util.*;

/** Explicit offline import; a failed batch rolls back without touching meal snapshots. */
@Component
public class NutritionCatalogImporter implements ApplicationRunner {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final Json json;
    private final String file;
    private final boolean exit;
    private final ConfigurableApplicationContext context;
    public NutritionCatalogImporter(JdbcTemplate db, TransactionTemplate tx, Json json,
            @Value("${app.import-catalog:}") String file,
            @Value("${app.import-exit:false}") boolean exit, ConfigurableApplicationContext context) {
        this.db=db; this.tx=tx; this.json=json; this.file=file; this.exit=exit; this.context=context;
    }
    @Override public void run(ApplicationArguments args) throws Exception {
        if(file.isBlank()) return;
        importFile(Path.of(file));
        System.out.println("NUTRITION_IMPORT " + db.queryForMap("SELECT COUNT(*) AS foods, SUM(CASE WHEN searchable=TRUE THEN 1 ELSE 0 END) AS searchable FROM foods"));
        if(exit) SpringApplication.exit(context);
    }
    public void importFile(Path path) throws Exception {
        try(var lines=Files.lines(path)) {
            tx.executeWithoutResult(status -> {
                lines.filter(l -> !l.isBlank()).forEach(line -> insert(json.read(line)));
                // Denormalized current-value cache keeps existing clients and meal snapshots compatible.
                var current=db.queryForList("SELECT f.id,v.basis_amount,v.basis_unit,v.kcal,v.carbs,v.protein,v.fat,v.source,v.source_date FROM foods f JOIN food_nutrition_versions v ON v.id=f.current_version_id");
                db.batchUpdate("UPDATE foods SET basis_grams=?,basis_unit=?,kcal=?,carbs=?,protein=?,fat=?,source=?,source_version=? WHERE id=?",current,500,(ps,r)->{
                    ps.setObject(1,r.get("basis_amount")==null?java.math.BigDecimal.ONE:r.get("basis_amount"));
                    ps.setString(2,r.get("basis_unit")==null?"unknown":r.get("basis_unit").toString());
                    int col=3; for(String key:List.of("kcal","carbs","protein","fat","source","source_date","id")) ps.setObject(col++,r.get(key));
                });
            });
        }
    }
    private boolean exists(String table,String id) {
        return db.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE id=?",Integer.class,id)>0;
    }
    private Object value(JsonNode n,String key) { return n.path(key).isNumber()?n.path(key).decimalValue():null; }
    private void insert(JsonNode n) {
        String id=n.path("id").asText();
        switch(n.path("type").asText()) {
            case "dataset" -> {
                if(!exists("nutrition_datasets",id)) db.update("INSERT INTO nutrition_datasets VALUES(?,?,?,?,?)",id,n.path("filename").asText(),n.path("encoding").asText(),n.path("rowCount").asInt(),System.currentTimeMillis());
            }
            case "food" -> {
                String name=n.path("name").asText();
                if(!exists("foods",id)) db.update("INSERT INTO foods(id,name,aliases,basis_grams,source,source_version) VALUES(?,?,?,1,'','')",id,name,n.path("aliases").asText());
                db.update("UPDATE foods SET name=?,aliases=?,category=?,original_code=?,manufacturer=?,current_version_id=?,searchable=? WHERE id=?",name,n.path("aliases").asText(),n.path("category").asText(),n.path("originalCode").asText(),n.path("manufacturer").asText(),n.path("currentVersionId").asText(),n.path("searchable").asBoolean(),id);
                String alias=n.path("aliases").asText();
                if(db.queryForObject("SELECT COUNT(*) FROM food_aliases WHERE food_id=? AND alias=?",Integer.class,id,alias)==0)
                    db.update("INSERT INTO food_aliases VALUES(?,?)",id,alias);
            }
            case "version" -> {
                if(!exists("food_nutrition_versions",id)) {
                    for(String key:List.of("kcal","carbs","protein","fat"))
                        if(n.path(key).isNumber() && n.path(key).decimalValue().signum()<0) throw new IllegalArgumentException("Negative nutrient");
                    if(n.path("basisAmount").isNumber() && n.path("basisAmount").decimalValue().signum()<=0) throw new IllegalArgumentException("Invalid basis");
                    db.update("INSERT INTO food_nutrition_versions VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",id,n.path("foodId").asText(),n.path("name").asText(),value(n,"basisAmount"),n.path("basisUnit").isNull()?null:n.path("basisUnit").asText(),value(n,"kcal"),value(n,"carbs"),value(n,"protein"),value(n,"fat"),n.path("source").asText(),n.path("sourceDate").asText(),n.path("rawBasis").asText());
                    var portion=n.path("portion");
                    if(portion.isObject()) db.update("INSERT INTO food_portions VALUES(?,?,?,?)",id,"1회 참고량",value(portion,"amount"),portion.path("unit").asText());
                }
            }
            case "sourceRow" -> {
                String dataset=n.path("datasetId").asText(); int row=n.path("rowNumber").asInt();
                if(db.queryForObject("SELECT COUNT(*) FROM nutrition_source_rows WHERE dataset_id=? AND row_number=?",Integer.class,dataset,row)==0)
                    db.update("INSERT INTO nutrition_source_rows VALUES(?,?,?,?)",dataset,row,n.path("versionId").asText(),n.path("raw").toString());
            }
            default -> throw new IllegalArgumentException("Unknown catalog record");
        }
    }
}
