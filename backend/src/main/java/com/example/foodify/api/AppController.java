package com.example.foodify.api;
import com.example.foodify.ai.OpenAiClient;
import com.example.foodify.goal.GoalCalculator;
import com.example.foodify.auth.AuthService;
import com.example.foodify.meal.MealService;
import com.example.foodify.food.FoodService;
import com.example.foodify.storage.PhotoStore;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.*;
import java.security.Principal;
import java.time.*;
import java.util.*;

@RestController @RequestMapping("/api/v1")
public class AppController {
    private final JdbcTemplate db;private final MealService meals;private final FoodService foods;private final PhotoStore photos;private final Json json;private final OpenAiClient ai;
    public AppController(JdbcTemplate db,MealService meals,FoodService foods,PhotoStore photos,Json json,OpenAiClient ai) {this.db=db;this.meals=meals;this.foods=foods;this.photos=photos;this.json=json;this.ai=ai;}
    @GetMapping("/me") public Map<String,Object> me(Principal p) {
        var row=db.queryForMap("SELECT id,email,goals FROM members WHERE id=?",p.getName());
        var goals=json.read((String)row.get("goals"));
        if(goals.isObject()) ((com.fasterxml.jackson.databind.node.ObjectNode)goals).remove("purpose");
        row.put("goals",goals);return row;
    }
    @PostMapping("/me/goals/preview") public Object previewGoals(@RequestBody JsonNode body) {return GoalCalculator.calculate(body);}
    @PutMapping("/me/goals") public Object goals(Principal p,@RequestBody JsonNode body) {
        var validated=GoalCalculator.calculate(body);
        db.update("UPDATE members SET goals=? WHERE id=?",json.write(validated),p.getName());return validated;
    }
    @DeleteMapping("/me") public void deleteMe(Principal p) {meals.deleteMember(p.getName());}
    @org.springframework.beans.factory.annotation.Autowired private AiConsent consentService;
    @GetMapping("/me/ai-consent") public Object consent(Principal p) {return consentService.get(p.getName());}
    @PutMapping("/me/ai-consent") public Object acceptConsent(Principal p,@RequestBody JsonNode body) {
        if(!body.path("accepted").asBoolean(false)) throw ApiError.bad("AI_CONSENT_REQUIRED");
        return consentService.accept(p.getName(),body.path("version").asText());
    }
    @DeleteMapping("/me/ai-consent") public Object revokeConsent(Principal p) {return consentService.revoke(p.getName());}
    @GetMapping("/foods") public Object foods(@RequestParam String query) {return foods.search(query);}
    @GetMapping("/meals") public Object list(Principal p) {return meals.list(p.getName());}
    @GetMapping("/meals/{id}") public Object meal(Principal p,@PathVariable String id) {return meals.get(p.getName(),id);}
    @PostMapping(value="/meals",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Object create(Principal p,@RequestHeader("Idempotency-Key") String key,@RequestParam long eatenAt,@RequestParam(defaultValue="{}") String captureInfo,@RequestPart("images") List<MultipartFile> images) throws Exception {
        AiConsent.require(db,p.getName());
        if(key.isBlank()||key.length()>100||images.isEmpty()||images.size()>3||eatenAt<=0||eatenAt>System.currentTimeMillis()+86400000) throw ApiError.bad("INVALID_UPLOAD");
        if(captureInfo.length()>10000 || !json.read(captureInfo).isObject()) throw ApiError.bad("INVALID_CAPTURE");
        captureInfo = json.write(com.example.foodify.ai.CaptureEvidence.normalize(json.read(captureInfo),images.size()));
        var saved=new ArrayList<String>();
        try {
            for(var file:images) saved.add(photos.save(file));
            var result=meals.create(p.getName(),key,eatenAt,captureInfo,saved);
            // Files from a replay are not referenced by the original meal.
            for(String s:saved) if(db.queryForObject("SELECT COUNT(*) FROM photos WHERE storage_key=?",Integer.class,s)==0) photos.delete(s);
            return result;
        } catch(Exception e) {for(String s:saved) if(db.queryForObject("SELECT COUNT(*) FROM photos WHERE storage_key=?",Integer.class,s)==0) photos.delete(s);throw e;}
    }
    @PatchMapping("/meals/{id}") public Object update(Principal p,@PathVariable String id,@RequestBody JsonNode body) {
        if(!body.path("version").isIntegralNumber()) throw ApiError.bad("VERSION_REQUIRED");
        return meals.update(p.getName(),id,body.get("version").asLong(),body.path("items"));
    }
    @DeleteMapping("/meals/{id}") public void delete(Principal p,@PathVariable String id) {meals.delete(p.getName(),id);}
    @PostMapping("/meals/{id}/analyses") public Object retry(Principal p,@PathVariable String id) {AiConsent.require(db,p.getName());return meals.retry(p.getName(),id);}
    @GetMapping("/analyses/{id}") public Object analysis(Principal p,@PathVariable String id) {
        var rows=db.queryForList("SELECT a.* FROM analyses a JOIN meals m ON m.id=a.meal_id WHERE a.id=? AND m.member_id=?",id,p.getName());if(rows.isEmpty()) throw ApiError.missing();return rows.getFirst();
    }
    @GetMapping("/photos/{id}") public ResponseEntity<byte[]> photo(Principal p,@PathVariable String id) throws Exception {
        var keys=db.queryForList("SELECT p.storage_key FROM photos p JOIN meals m ON m.id=p.meal_id WHERE p.id=? AND m.member_id=?",String.class,id,p.getName());
        if(keys.isEmpty()) throw ApiError.missing();
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).cacheControl(CacheControl.noStore()).body(photos.read(keys.getFirst()));
    }
    @GetMapping("/nutrition/summary") public Object summary(Principal p,@RequestParam LocalDate from,@RequestParam LocalDate to) {return meals.summary(p.getName(),from,to);}
    private String evidence(String member,LocalDate from,LocalDate to) {
        var revisions=db.queryForList("SELECT id,version,status FROM meals WHERE member_id=? AND eaten_at>=? AND eaten_at<? ORDER BY id",member,from.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli(),to.plusDays(1).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli());
        var stored=json.read(db.queryForObject("SELECT goals FROM members WHERE id=?",String.class,member));
        var goals=new LinkedHashMap<String,Object>();
        for(String key:List.of("kcal","carbs","protein","fat","calculation")) if(stored.has(key)) goals.put(key,stored.get(key));
        return json.write(Map.of("summary",meals.summary(member,from,to),"revisions",revisions,"goals",goals));
    }
    @PostMapping("/feedback") public Object feedback(Principal p,@RequestBody JsonNode body) {
        AiConsent.require(db,p.getName());
        LocalDate from=LocalDate.parse(body.path("from").asText()),to=LocalDate.parse(body.path("to").asText());
        String evidence=evidence(p.getName(),from,to),id=UUID.randomUUID().toString();
        var content=ai.feedback(evidence);
        db.update("INSERT INTO feedback VALUES(?,?,?,?,?,?,?)",id,p.getName(),from.toString(),to.toString(),AuthService.hash(evidence),json.write(content),System.currentTimeMillis());
        return getFeedback(p,id);
    }
    @GetMapping("/feedback/{id}") public Object getFeedback(Principal p,@PathVariable String id) {
        var rows=db.queryForList("SELECT * FROM feedback WHERE id=? AND member_id=?",id,p.getName());if(rows.isEmpty()) throw ApiError.missing();var row=rows.getFirst();
        row.put("stale",!row.get("snapshot_hash").equals(AuthService.hash(evidence(p.getName(),LocalDate.parse((String)row.get("from_date")),LocalDate.parse((String)row.get("to_date"))))));
        row.put("content",json.read((String)row.get("content")));return row;
    }
}
