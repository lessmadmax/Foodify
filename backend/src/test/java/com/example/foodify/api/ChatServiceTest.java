package com.example.foodify.api;
import com.example.foodify.ai.OpenAiClient;
import com.example.foodify.auth.AuthService;
import com.example.foodify.meal.MealService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest
class ChatServiceTest {
 @Autowired JdbcTemplate db; @Autowired TransactionTemplate tx; @Autowired MealService meals;
 @Autowired AuthService auth; @Autowired AiConsent consent; @Autowired Json json;
 String member, other; OpenAiClient ai; ChatService chat;
 @BeforeEach void setup(){
   member=(String)auth.signup(UUID.randomUUID()+"@example.com","safe-password-123").get("memberId");
   other=(String)auth.signup(UUID.randomUUID()+"@example.com","safe-password-123").get("memberId");
   consent.accept(member,AiConsent.VERSION);ai=mock(OpenAiClient.class);
   when(ai.chat(anyString(),anyList(),anyString())).thenReturn("기록을 참고한 답변입니다.");
   chat=new ChatService(db,tx,meals,json,ai);
 }
 @Test void latestRecordsHistoryIsolationAndIdempotency(){
   String id=UUID.randomUUID().toString();
   db.update("INSERT INTO meals(id,member_id,request_key,eaten_at,status,version,items,capture_info,created_at) VALUES(?,?,?,?,'COMPLETE',0,?,'{}',?)",id,member,id,System.currentTimeMillis(),"[{\"name\":\"밥\",\"nutrition\":{\"kcal\":100,\"carbs\":20,\"protein\":3,\"fat\":1}}]",System.currentTimeMillis());
   var first=chat.send(member,"one","오늘은 어때?");
   assertEquals("COMPLETE",first.get("status"));
   assertEquals(first.get("id"),chat.send(member,"one","오늘은 어때?").get("id"));
   verify(ai,times(1)).chat(anyString(),anyList(),anyString());
   assertTrue(chat.history(other).isEmpty());
   db.update("UPDATE meals SET items=? WHERE id=?","[{\"name\":\"밥\",\"nutrition\":{\"kcal\":200,\"carbs\":40,\"protein\":6,\"fat\":2}}]",id);
   when(ai.chat(anyString(),anyList(),eq("그럼 저녁은?"))).thenAnswer(call->{
     assertEquals(200,json.read(call.getArgument(0,String.class)).path("today").path("totals").path("kcal").asInt());
     List<Map<String,Object>> history=call.getArgument(1);assertEquals(1,history.size());assertEquals("오늘은 어때?",history.getFirst().get("question"));return "저녁 안내";});
   chat.send(member,"two","그럼 저녁은?");assertEquals(2,chat.history(member).size());
   chat.clear(other);assertEquals(2,chat.history(member).size());
   chat.clear(member);assertTrue(chat.history(member).isEmpty());
 }
 @Test void errorsRetryConsentAndStaleLease(){
   when(ai.chat(anyString(),anyList(),anyString())).thenThrow(ApiError.conflict("OPENAI_UNAVAILABLE"));
   assertThrows(ApiError.class,()->chat.send(member,"retry","질문"));
   assertEquals("FAILED",chat.history(member).getFirst().get("status"));
   doReturn("재시도 답변").when(ai).chat(anyString(),anyList(),anyString());
   chat.send(member,"retry","질문");assertEquals(1,chat.history(member).size());
   assertEquals("CHAT_REQUEST_MISMATCH",assertThrows(ApiError.class,()->chat.send(member,"retry","다른 질문")).code);
   db.update("UPDATE chat_turns SET status='RUNNING' WHERE member_id=?",member);
   assertEquals("CHAT_BUSY",assertThrows(ApiError.class,()->chat.send(member,"new","새 질문")).code);
   assertThrows(ApiError.class,()->chat.clear(member));
   db.update("UPDATE chat_turns SET updated_at=0 WHERE member_id=?",member);
   assertEquals("FAILED",chat.history(member).getFirst().get("status"));
   consent.revoke(member);clearInvocations(ai);
   assertEquals("AI_CONSENT_REQUIRED",assertThrows(ApiError.class,()->chat.send(member,"new","질문")).code);
   verifyNoInteractions(ai);
 }
 @Test void emptyDataAndAccountDeletion(){
   assertEquals(0,json.read(json.write(chat.evidence(member))).path("today").path("completedMeals").asInt());
   assertThrows(ApiError.class,()->chat.send(member,"k"," "));
   chat.send(member,"one","기록이 없는데?");
   meals.deleteMember(member);
   assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM chat_turns WHERE member_id=?",Integer.class,member));
 }
}
