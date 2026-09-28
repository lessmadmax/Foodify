package com.example.foodify.ai;
import com.example.foodify.api.*;
import com.example.foodify.food.FoodService;
import com.example.foodify.storage.PhotoStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

@Component @ConditionalOnProperty(name="app.worker-enabled",havingValue="true")
public class AnalysisWorker {
    @Configuration @EnableScheduling static class Scheduling {}
    private final JdbcTemplate db; private final TransactionTemplate tx; private final OpenAiClient ai; private final PhotoStore photos; private final Json json; private final FoodService foods;
    public AnalysisWorker(JdbcTemplate db,TransactionTemplate tx,OpenAiClient ai,PhotoStore photos,Json json,FoodService foods) {this.db=db;this.tx=tx;this.ai=ai;this.photos=photos;this.json=json;this.foods=foods;}
    @Scheduled(fixedDelay=3000)
    public void tick() {
        cleanPhotos();
        long monthStart=java.time.LocalDate.now(java.time.ZoneOffset.UTC).withDayOfMonth(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
        db.update("UPDATE analyses SET status='QUEUED' WHERE status='BUDGET_WAIT' AND updated_at<?",monthStart);
        // A lease longer than the API timeout recovers interrupted single-worker jobs.
        db.update("UPDATE analyses SET status='QUEUED' WHERE status='RUNNING' AND updated_at<?",System.currentTimeMillis()-180000);
        var jobs=db.queryForList("SELECT * FROM analyses WHERE status='QUEUED' AND updated_at<=? ORDER BY created_at LIMIT 1",System.currentTimeMillis());
        if(jobs.isEmpty()) return;
        var job=jobs.getFirst();String id=(String)job.get("id"),meal=(String)job.get("meal_id");long version=((Number)job.get("expected_version")).longValue();
        if(db.update("UPDATE analyses SET status='RUNNING',attempts=attempts+1,updated_at=? WHERE id=? AND status='QUEUED'",System.currentTimeMillis(),id)!=1) return;
        db.update("UPDATE meals SET status='RUNNING' WHERE id=? AND version=?",meal,version);
        try {
            var bytes=new ArrayList<byte[]>();
            for(String key:db.queryForList("SELECT storage_key FROM photos WHERE meal_id=?",String.class,meal)) bytes.add(photos.read(key));
            var result=ai.analyze(bytes);
            var items=new ArrayList<Map<String,Object>>();
            for(var item:result.path("items")) {
                var output=new LinkedHashMap<String,Object>();
                output.put("name",item.path("name").asText());output.put("grams",item.get("grams"));
                output.put("foodId","");output.put("confirmed",false);output.put("nutrition",null);output.put("status","NEEDS_REVIEW");
                output.put("ingredients",item.get("ingredients"));output.put("uncertainty",item.path("uncertainty").asText());
                output.put("candidates",foods.search(item.path("name").asText()));items.add(output);
            }
            suggestMatches(items);
            finish(id,meal,version,json.write(items),"USER_REVIEW_REQUIRED");
        } catch(Exception e) {
            String error=e instanceof ApiError ae?ae.code:"ANALYSIS_FAILED";
            boolean temporary=error.equals("OPENAI_UNAVAILABLE")||error.equals("OPENAI_HTTP_429")||error.startsWith("OPENAI_HTTP_5");
            int attempts=((Number)job.get("attempts")).intValue()+1;
            if(temporary&&attempts<3) {
                db.update("UPDATE analyses SET status='QUEUED',error_code=?,updated_at=? WHERE id=? AND status='RUNNING'",error,System.currentTimeMillis()+5000L*attempts*attempts,id);
            }else if(error.equals("AI_BUDGET_REACHED")) {
                db.update("UPDATE analyses SET status='BUDGET_WAIT',error_code=?,updated_at=? WHERE id=? AND status='RUNNING'",error,System.currentTimeMillis(),id);
                db.update("UPDATE meals SET status='INCOMPLETE' WHERE id=? AND version=?",meal,version);
            }else finish(id,meal,version,null,error);
        }
    }
    @SuppressWarnings("unchecked")
    private void suggestMatches(List<Map<String,Object>> items) {
        var evidence=new ArrayList<Map<String,Object>>();
        for(int i=0;i<items.size();i++) {var item=items.get(i);if(!((List<?>)item.get("candidates")).isEmpty())evidence.add(Map.of("index",i,"name",item.get("name"),"ingredients",item.get("ingredients"),"candidates",item.get("candidates")));}
        if(evidence.isEmpty())return;
        try {
            var choices=ai.chooseCandidates(evidence);
            for(var choice:choices.path("selections")) {
                int index=choice.path("index").asInt(-1);if(index<0||index>=items.size())continue;
                String foodId=choice.path("foodId").asText("");
                var candidates=(List<Map<String,Object>>)items.get(index).get("candidates");
                if(candidates.stream().anyMatch(c->foodId.equals(c.get("id"))))items.get(index).put("foodId",foodId);
            }
        }catch(ApiError ignored){ /* Candidate search remains available even if reranking cannot run. */ }
    }
    private void finish(String id,String meal,long version,String items,String error) {
        tx.executeWithoutResult(s->{
            var rows=db.queryForList("SELECT version FROM meals WHERE id=? FOR UPDATE",meal);if(rows.isEmpty()) return;
            if(((Number)rows.getFirst().get("version")).longValue()!=version) {
                db.update("UPDATE analyses SET status='SUPERSEDED',updated_at=? WHERE id=?",System.currentTimeMillis(),id);return;
            }
            if(items!=null) db.update("UPDATE meals SET items=?,status='INCOMPLETE',version=version+1 WHERE id=?",items,meal);
            else db.update("UPDATE meals SET status='INCOMPLETE',version=version+1 WHERE id=?",meal);
            db.update("UPDATE analyses SET status='INCOMPLETE',error_code=?,updated_at=? WHERE id=?",error,System.currentTimeMillis(),id);
        });
    }
    private void cleanPhotos() {
        for(String key:db.queryForList("SELECT storage_key FROM photo_deletions",String.class)) try {photos.delete(key);db.update("DELETE FROM photo_deletions WHERE storage_key=?",key);} catch(Exception ignored) { /* Durable retry on the next tick, including S3 failures. */ }
    }
}
