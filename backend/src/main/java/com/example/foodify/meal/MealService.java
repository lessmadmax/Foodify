package com.example.foodify.meal;
import com.example.foodify.api.*;
import com.example.foodify.food.FoodService;
import com.example.foodify.storage.PhotoStore;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service
public class MealService {
    private final JdbcTemplate db; private final Json json; private final FoodService foods;
    public MealService(JdbcTemplate db,Json json,FoodService foods) {this.db=db;this.json=json;this.foods=foods;}
    public Map<String,Object> get(String member,String id) {
        var rows=db.queryForList("SELECT * FROM meals WHERE member_id=? AND id=?",member,id);
        if(rows.isEmpty()) throw ApiError.missing();
        var row=rows.getFirst(); row.put("items",json.read((String)row.get("items")));
        row.put("capture_info",json.read((String)row.get("capture_info")));
        row.put("photos",db.queryForList("SELECT id FROM photos WHERE meal_id=?",id));
        row.put("analyses",db.queryForList("SELECT id,status,error_code FROM analyses WHERE meal_id=? ORDER BY created_at DESC",id));
        return row;
    }
    public List<Map<String,Object>> list(String member) {
        return db.queryForList("SELECT id FROM meals WHERE member_id=? ORDER BY eaten_at DESC LIMIT 100",String.class,member).stream().map(id->get(member,id)).toList();
    }
    @Transactional
    public Map<String,Object> create(String member,String key,long eatenAt,String capture,List<String> photos) {
        // Serialize requests by member, including equal idempotency keys.
        lockMember(member);
        var existing=db.queryForList("SELECT id FROM meals WHERE member_id=? AND request_key=?",String.class,member,key);
        if(!existing.isEmpty()) return get(member,existing.getFirst());
        String id=UUID.randomUUID().toString();long now=System.currentTimeMillis();
        db.update("INSERT INTO meals VALUES(?,?,?,?,?,?,?,?,?)",id,member,key,eatenAt,"QUEUED",0,"[]",capture,now);
        for(String photo:photos) db.update("INSERT INTO photos VALUES(?,?,?)",UUID.randomUUID().toString(),id,photo);
        newJob(id,0);
        return get(member,id);
    }
    private String newJob(String id,long version) {
        String job=UUID.randomUUID().toString();long now=System.currentTimeMillis();
        db.update("INSERT INTO analyses(id,meal_id,expected_version,status,error_code,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",job,id,version,"QUEUED",null,now,now);return job;
    }
    @Transactional
    public Map<String,Object> update(String member,String id,long version,JsonNode items) {
        lockMember(member); lockMeal(id); var meal=get(member,id);
        if(((Number)meal.get("version")).longValue()!=version) throw ApiError.conflict("STALE_VERSION");
        if(!items.isArray() || items.size()>50) throw ApiError.bad("INVALID_ITEMS");
        var computed=new ArrayList<Map<String,Object>>();
        items.forEach(item->computed.add(foods.calculate(item)));
        boolean complete=!computed.isEmpty()&&computed.stream().allMatch(i->"COMPLETE".equals(i.get("status")));
        db.update("UPDATE meals SET items=?,version=version+1,status=? WHERE id=?",json.write(computed),complete?"COMPLETE":"INCOMPLETE",id);
        db.update("UPDATE analyses SET status='SUPERSEDED',updated_at=? WHERE meal_id=? AND status IN ('QUEUED','RUNNING','BUDGET_WAIT')",System.currentTimeMillis(),id);
        return get(member,id);
    }
    @Transactional
    public Map<String,Object> retry(String member,String id) {
        lockMember(member);lockMeal(id);var meal=get(member,id);
        if(List.of("QUEUED","RUNNING").contains(meal.get("status"))) throw ApiError.conflict("ANALYSIS_ACTIVE");
        long next=((Number)meal.get("version")).longValue()+1;
        db.update("UPDATE analyses SET status='SUPERSEDED' WHERE meal_id=? AND status='BUDGET_WAIT'",id);
        db.update("UPDATE meals SET status='QUEUED',version=? WHERE id=?",next,id);
        return Map.of("id",newJob(id,next));
    }
    @Transactional
    public void delete(String member,String id) {
        lockMember(member); get(member,id);queuePhotos(id);db.update("DELETE FROM meals WHERE id=?",id);
    }
    private void queuePhotos(String meal) {
        for(String key:db.queryForList("SELECT storage_key FROM photos WHERE meal_id=?",String.class,meal)) db.update("INSERT INTO photo_deletions VALUES(?)",key);
    }
    @Transactional
    public void deleteMember(String member) {
        lockMember(member);
        db.queryForList("SELECT id FROM meals WHERE member_id=?",String.class,member).forEach(this::queuePhotos);
        db.update("DELETE FROM members WHERE id=?",member);
    }
    public void lockMember(String member) {
        if(db.queryForList("SELECT id FROM members WHERE id=? FOR UPDATE",String.class,member).isEmpty()) throw ApiError.missing();
    }
    private void lockMeal(String id) { db.queryForList("SELECT id FROM meals WHERE id=? FOR UPDATE",String.class,id); }
    public Map<String,Object> summary(String member,LocalDate from,LocalDate to) {
        if(from.isAfter(to)||from.plusDays(31).isBefore(to)) throw ApiError.bad("DATE_RANGE");
        ZoneId zone=ZoneId.of("Asia/Seoul");
        var rows=db.queryForList("SELECT status,items FROM meals WHERE member_id=? AND eaten_at>=? AND eaten_at<?",member,from.atStartOfDay(zone).toInstant().toEpochMilli(),to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli());
        var totals=new LinkedHashMap<String,java.math.BigDecimal>();
        for(String n:List.of("kcal","carbs","protein","fat")) totals.put(n,java.math.BigDecimal.ZERO);
        int complete=0;
        for(var row:rows) if("COMPLETE".equals(row.get("status"))) {
            complete++;
            for(JsonNode item:json.read((String)row.get("items"))) for(String n:totals.keySet()) totals.put(n,totals.get(n).add(item.path("nutrition").path(n).decimalValue()));
        }
        var goal=json.read(db.queryForObject("SELECT goals FROM members WHERE id=?",String.class,member));
        var comparison=new LinkedHashMap<String,Object>();
        long days=java.time.temporal.ChronoUnit.DAYS.between(from,to)+1;
        for(String n:totals.keySet()) if(goal.path(n).isNumber()&&goal.path(n).decimalValue().signum()>0) {
            var target=goal.path(n).decimalValue().multiply(java.math.BigDecimal.valueOf(days));
            comparison.put(n,Map.of("target",target,"difference",totals.get(n).subtract(target),"ratio",totals.get(n).divide(target,3,java.math.RoundingMode.HALF_UP)));
        }
        return Map.of("from",from.toString(),"to",to.toString(),"totals",totals,"completedMeals",complete,"incompleteMeals",rows.size()-complete,"comparison",comparison);
    }
}
