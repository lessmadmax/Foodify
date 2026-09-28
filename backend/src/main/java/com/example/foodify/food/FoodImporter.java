package com.example.foodify.food;
import com.example.foodify.api.Json;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.math.BigDecimal;
import java.util.*;

/** Imports normalized JSONL; provenance and units must be verified before running. */
@Component
public class FoodImporter implements ApplicationRunner {
    private final String file;private final JdbcTemplate db;private final Json json;private final TransactionTemplate tx;
    public FoodImporter(@Value("${app.import-foods:}") String file,JdbcTemplate db,Json json,TransactionTemplate tx){this.file=file;this.db=db;this.json=json;this.tx=tx;}
    @Override public void run(ApplicationArguments args) throws Exception {
        if(file.isBlank())return;
        try(var lines=Files.lines(Path.of(file))){
            tx.executeWithoutResult(t->lines.filter(l->!l.isBlank()).forEach(line->{
                var row=json.read(line);
                for(String s:List.of("id","name","source","sourceVersion"))if(!row.path(s).isTextual()||row.path(s).asText().isBlank())throw new IllegalArgumentException("Missing "+s);
                if(!row.path("basisGrams").isNumber()||row.path("basisGrams").decimalValue().signum()<=0)throw new IllegalArgumentException("Invalid basisGrams");
                var n=new ArrayList<BigDecimal>();
                for(String s:List.of("kcal","carbs","protein","fat")){
                    var v=row.path(s);if(v.isNull()||v.isMissingNode())n.add(null);
                    else{if(!v.isNumber()||v.decimalValue().signum()<0)throw new IllegalArgumentException("Invalid "+s);n.add(v.decimalValue());}
                }
                String id=row.get("id").asText();
                var values=new Object[]{row.get("name").asText(),row.path("aliases").asText(""),row.get("basisGrams").decimalValue(),n.get(0),n.get(1),n.get(2),n.get(3),row.get("source").asText(),row.get("sourceVersion").asText(),id};
                if(db.update("UPDATE foods SET name=?,aliases=?,basis_grams=?,kcal=?,carbs=?,protein=?,fat=?,source=?,source_version=? WHERE id=?",values)==0)
                    db.update("INSERT INTO foods(name,aliases,basis_grams,kcal,carbs,protein,fat,source,source_version,id) VALUES(?,?,?,?,?,?,?,?,?,?)",values);
            }));
        }
    }
}
