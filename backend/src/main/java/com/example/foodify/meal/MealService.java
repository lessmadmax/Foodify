package com.example.foodify.meal;
import com.example.foodify.api.*;
import com.example.foodify.food.DensityGroups;
import com.example.foodify.food.FoodCategoryService;
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
    @org.springframework.beans.factory.annotation.Value("${app.worker-enabled:true}")
    private boolean workerEnabled;
    private final JdbcTemplate db; private final Json json; private final FoodService foods; private final FoodCategoryService categories;
    public MealService(JdbcTemplate db,Json json,FoodService foods,FoodCategoryService categories) {this.db=db;this.json=json;this.foods=foods;this.categories=categories;}
    /**
     * AR weight: the app sends only the measured volume; the server picks the density from the
     * matched food's official major category (DensityGroups) and computes grams.
     */
    public static final int HEIGHT_CALLS=3;
    public static final double MAX_AREA_CM2=2000, MAX_HEIGHT_CM=30;
    /** Food id, official major category and density group used for an AR weight of this food. */
    private Map<String,Object> densityOf(com.fasterxml.jackson.databind.node.ObjectNode input) {
        var resolved=foods.calculate(input);
        String foodId=Objects.toString(resolved.get("foodId"),"");
        String major=categories.majorOf(foodId);
        var out=new LinkedHashMap<String,Object>();
        out.put("foodId",foodId); out.put("majorCategory",major); out.put("group",DensityGroups.of(major));
        return out;
    }
    private static java.math.BigDecimal gramsOf(double volume,DensityGroups.Group group) {
        return java.math.BigDecimal.valueOf(volume).multiply(group.density()).setScale(1,java.math.RoundingMode.HALF_UP).max(new java.math.BigDecimal("0.1"));
    }
    /** Preview for the AR screen: same food matching and density as the meal save. */
    public Map<String,Object> areaHeightPreview(String name,String foodId,double areaCm2,double heightCm) {
        var input=(com.fasterxml.jackson.databind.node.ObjectNode)json.read(json.write(Map.of("name",name,"foodId",foodId==null?"":foodId)));
        var d=densityOf(input);
        var group=(DensityGroups.Group)d.get("group");
        double volume=Math.round(areaCm2*heightCm*10)/10.0;
        var out=new LinkedHashMap<String,Object>();
        out.put("method","AREA_HEIGHT"); out.put("volumeMl",volume); out.put("grams",gramsOf(volume,group));
        out.put("foodId",d.get("foodId")); out.put("majorCategory",d.get("majorCategory"));
        out.put("densityGroup",group.id()); out.put("densityGroupLabel",group.label()); out.put("densityGPerMl",group.density());
        return out;
    }
    private static double positive(JsonNode n,double max,String error) {
        double v=n.asDouble(Double.NaN);
        if(!n.isNumber() || !(v>0 && v<=max)) throw ApiError.bad(error);
        return v;
    }
    private Map<String,Object> applyArMeasurement(JsonNode ar,com.fasterxml.jackson.databind.node.ObjectNode input) {
        // AREA_HEIGHT (current): top-view area (AR scale) × AI mean height. AR_VOLUME: legacy depth volume.
        boolean areaHeight="AREA_HEIGHT".equals(ar.path("method").asText()) || ar.has("heightCm");
        double area=Double.NaN,height=Double.NaN,volume;
        if(areaHeight) {
            area=positive(ar.path("areaCm2"),MAX_AREA_CM2,"INVALID_AR_AREA");
            height=positive(ar.path("heightCm"),MAX_HEIGHT_CM,"INVALID_AR_HEIGHT");
            volume=Math.round(area*height*10)/10.0;
        } else volume=ar.path("volumeMl").asDouble(Double.NaN);
        if(!(volume>0 && volume<=5000)) throw ApiError.bad("INVALID_AR_VOLUME");
        var d=densityOf(input);
        String foodId=(String)d.get("foodId"), major=(String)d.get("majorCategory");
        var group=(DensityGroups.Group)d.get("group");
        var grams=gramsOf(volume,group);
        input.put("grams",grams); input.put("foodId",foodId);
        var reasons=new ArrayList<String>();
        for(var r:ar.path("holdReasons")) if(r.isTextual() && reasons.size()<10) reasons.add(r.asText().substring(0,Math.min(200,r.asText().length())));
        var m=new LinkedHashMap<String,Object>();
        m.put("method",areaHeight?"AREA_HEIGHT":"AR_VOLUME"); m.put("volumeMl",volume);
        if(areaHeight) {
            m.put("areaCm2",area); m.put("heightCm",height);
            var samples=new ArrayList<Double>();
            for(var s:ar.path("heightSamples")) if(s.isNumber() && samples.size()<HEIGHT_CALLS) samples.add(s.asDouble());
            m.put("heightSamples",samples);
            if(ar.path("distanceMm").isNumber()) m.put("distanceMm",ar.path("distanceMm").asDouble());
            // Depth-based volume is kept only as a reference for later comparison.
            if(ar.path("depthVolumeMl").isNumber() && ar.path("depthVolumeMl").asDouble()>0) m.put("depthVolumeMl",ar.path("depthVolumeMl").asDouble());
            m.put("heightSource","OpenAI 평균 높이 "+HEIGHT_CALLS+"회 중앙값");
        }
        m.put("qualityHold",ar.path("qualityHold").asBoolean(false));
        m.put("holdReasons",reasons); m.put("majorCategory",major); m.put("densityGroup",group.id()); m.put("densityGroupLabel",group.label());
        m.put("densityGPerMl",group.density()); m.put("densityBasis",group.basis()); m.put("densitySource","FAO/INFOODS Density Database v2.0");
        m.put("volumeValidated",false); m.put("measuredAt",System.currentTimeMillis());
        return m;
    }
    public Map<String,Object> get(String member,String id) {
        var rows=db.queryForList("SELECT * FROM meals WHERE member_id=? AND id=?",member,id);
        if(rows.isEmpty()) throw ApiError.missing();
        var row=rows.getFirst(); row.put("items",json.read((String)row.get("items")));
        row.put("capture_info",json.read((String)row.get("capture_info")));
        row.put("photos",db.queryForList("SELECT id FROM photos WHERE meal_id=?",id));
        row.put("analyses",db.queryForList("SELECT id,status,error_code FROM analyses WHERE meal_id=? ORDER BY created_at DESC",id));
        row.put("analysisEnabled",workerEnabled);
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
        var previous=(JsonNode)meal.get("items");
        items.forEach(item->{
            var calculationInput=item.deepCopy();
            // Upgrade the previous server-marked single-food fallback on explicit save.
            // Trust only stored evidence, not a client-supplied fallback flag.
            for(var old:previous) if(old.path("name").asText().equals(item.path("name").asText()) &&
                old.path("foodId").asText().equals(item.path("foodId").asText()) &&
                "CATEGORY_FALLBACK".equals(old.path("source").path("matchType").asText()) &&
                !item.path("foodId").asText().startsWith("category:") && calculationInput.isObject()) {
                ((com.fasterxml.jackson.databind.node.ObjectNode)calculationInput).put("foodId",""); break;
            }
            Map<String,Object> measurement=null;
            if(item.path("arMeasurement").isObject() && calculationInput.isObject())
                measurement=applyArMeasurement(item.get("arMeasurement"),(com.fasterxml.jackson.databind.node.ObjectNode)calculationInput);
            var calculated=foods.calculate(calculationInput);
            if(measurement!=null) {calculated.put("weightMeasurement",measurement);computed.add(calculated);return;}
            // Preserve server-generated evidence only for an unambiguous unchanged food/weight.
            var matching=new ArrayList<JsonNode>();
            for(var old:previous) if(old.path("name").asText().equals(item.path("name").asText()) &&
                ((old.path("grams").isNull() && item.path("grams").isNull()) ||
                 (old.path("grams").isNumber() && item.path("grams").isNumber() && old.path("grams").decimalValue().compareTo(item.path("grams").decimalValue())==0))) matching.add(old);
            if(matching.size()==1) for(String field:List.of("weightEstimate","uncertainty","weightMeasurement")) {
                if(matching.getFirst().has(field)) calculated.put(field,matching.getFirst().get(field));
            }
            computed.add(calculated);
        });
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
