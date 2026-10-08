package com.example.foodify.api;

import com.example.foodify.ai.OpenAiClient;
import com.example.foodify.meal.MealService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;

@Service
public class ChatService {
    private final JdbcTemplate db; private final TransactionTemplate tx;
    private final MealService meals; private final Json json; private final OpenAiClient ai;
    public ChatService(JdbcTemplate db,TransactionTemplate tx,MealService meals,Json json,OpenAiClient ai) {
        this.db=db;this.tx=tx;this.meals=meals;this.json=json;this.ai=ai;
    }
    private void recover(String member) {
        db.update("UPDATE chat_turns SET status='FAILED',error_code='CHAT_INTERRUPTED' WHERE member_id=? AND status='RUNNING' AND updated_at<?",member,System.currentTimeMillis()-180000);
    }
    public List<Map<String,Object>> history(String member) {
        recover(member);
        var rows=db.queryForList("SELECT * FROM chat_turns WHERE member_id=? ORDER BY created_at DESC,id DESC LIMIT 50",member);
        Collections.reverse(rows);
        return rows.stream().map(this::view).toList();
    }
    private Map<String,Object> view(Map<String,Object> row) {
        var out=new LinkedHashMap<String,Object>();
        for(String key:List.of("id","request_key","question","answer","status","error_code","created_at")) out.put(key,row.get(key));
        var e=json.read((String)row.get("evidence"));
        out.put("from",e.path("from").asText());out.put("to",e.path("to").asText());
        out.put("asOf",e.path("asOf").asLong());out.put("incompleteMeals",e.path("week").path("incompleteMeals").asInt());
        return out;
    }
    public Map<String,Object> evidence(String member) {
        var today=LocalDate.now(ZoneId.of("Asia/Seoul"));var from=today.minusDays(6);
        var details=new ArrayList<Map<String,Object>>();
        var rows=db.queryForList("SELECT eaten_at,status,items FROM meals WHERE member_id=? AND eaten_at>=? AND eaten_at<? ORDER BY eaten_at DESC LIMIT 30",member,from.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli(),today.plusDays(1).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli());
        for(var row:rows) {
            var foods=new ArrayList<Map<String,Object>>();
            if("COMPLETE".equals(row.get("status"))) for(var item:json.read((String)row.get("items"))) {
                if(foods.size()==10)break;
                foods.add(Map.of("name",item.path("name").asText(),"nutrition",item.path("nutrition")));
            }
            details.add(Map.of("time",row.get("eaten_at"),"status",row.get("status"),"foods",foods));
            if(json.write(details).getBytes(java.nio.charset.StandardCharsets.UTF_8).length>40000) {details.removeLast();break;}
        }
        return Map.of("from",from.toString(),"to",today.toString(),"asOf",System.currentTimeMillis(),
            "today",meals.summary(member,today,today),"week",meals.summary(member,from,today),
            "recentMeals",details,"detailLimit","최근 30끼, 한 끼당 10개 음식, 전체 상세 40KB까지. 집계는 기간 내 전체 완료 기록.",
            "coverageNotice","식사 누락 여부는 확인되지 않습니다. 기록이 없다는 것은 섭취량이 0이라는 뜻이 아닙니다.");
    }
    public Map<String,Object> send(String member,String key,String question) {
        if(key==null||key.isBlank()||key.length()>100||question==null||question.isBlank()||question.length()>2000)throw ApiError.bad("INVALID_CHAT_MESSAGE");
        AiConsent.require(db,member);
        String id=tx.execute(s->{
            db.queryForMap("SELECT id FROM members WHERE id=? FOR UPDATE",member);recover(member);
            var existing=db.queryForList("SELECT * FROM chat_turns WHERE member_id=? AND request_key=?",member,key);
            if(!existing.isEmpty()) {
                var row=existing.getFirst();
                if(!question.equals(row.get("question")))throw ApiError.conflict("CHAT_REQUEST_MISMATCH");
                if("COMPLETE".equals(row.get("status")))return (String)row.get("id");
            }
            if(db.queryForObject("SELECT COUNT(*) FROM chat_turns WHERE member_id=? AND status='RUNNING'",Integer.class,member)>0)throw ApiError.conflict("CHAT_BUSY");
            long now=System.currentTimeMillis();
            if(!existing.isEmpty()) {
                String retry=(String)existing.getFirst().get("id");
                db.update("UPDATE chat_turns SET status='RUNNING',updated_at=?,error_code=NULL WHERE id=?",now,retry);return retry;
            }
            String fresh=UUID.randomUUID().toString();
            db.update("INSERT INTO chat_turns(id,member_id,request_key,question,status,evidence,created_at,updated_at) VALUES(?,?,?,?,'RUNNING','{}',?,?)",fresh,member,key,question,now,now);return fresh;
        });
        var stored=db.queryForMap("SELECT * FROM chat_turns WHERE id=? AND member_id=?",id,member);
        if("COMPLETE".equals(stored.get("status")))return view(stored);
        try {
            var snapshot=tx.execute(s->evidence(member));
            db.update("UPDATE chat_turns SET evidence=? WHERE id=?",json.write(snapshot),id);
            var history=db.queryForList("SELECT question,answer FROM chat_turns WHERE member_id=? AND status='COMPLETE' ORDER BY created_at DESC,id DESC LIMIT 10",member);
            Collections.reverse(history);
            AiConsent.require(db,member);
            String answer=ai.chat(json.write(snapshot),history,question);
            db.update("UPDATE chat_turns SET answer=?,status='COMPLETE',updated_at=? WHERE id=? AND status='RUNNING'",answer,System.currentTimeMillis(),id);
        }catch(Exception e) {
            String code=e instanceof ApiError a?a.code:"CHAT_FAILED";
            db.update("UPDATE chat_turns SET status='FAILED',error_code=?,updated_at=? WHERE id=? AND status='RUNNING'",code,System.currentTimeMillis(),id);
            if(e instanceof ApiError a)throw a;
            throw ApiError.conflict("CHAT_FAILED");
        }
        return view(db.queryForMap("SELECT * FROM chat_turns WHERE id=? AND member_id=?",id,member));
    }
    public void clear(String member) {
        tx.executeWithoutResult(s->{
            db.queryForMap("SELECT id FROM members WHERE id=? FOR UPDATE",member);recover(member);
            if(db.queryForObject("SELECT COUNT(*) FROM chat_turns WHERE member_id=? AND status='RUNNING'",Integer.class,member)>0)throw ApiError.conflict("CHAT_BUSY");
            db.update("DELETE FROM chat_turns WHERE member_id=?",member);
        });
    }
}
