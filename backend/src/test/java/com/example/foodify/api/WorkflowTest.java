package com.example.foodify.api;
import com.example.foodify.auth.AuthService;
import com.example.foodify.ai.*;
import com.example.foodify.food.FoodService;
import com.example.foodify.storage.PhotoStore;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import java.time.*;
import java.util.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest @AutoConfigureMockMvc
class WorkflowTest {
    @Autowired MockMvc mvc; @Autowired ObjectMapper mapper; @Autowired JdbcTemplate db;
    @Autowired AuthService auth; @Autowired Json json; @Autowired PhotoStore photos;
    @Autowired FoodService foods; @Autowired TransactionTemplate tx; @Autowired OpenAiClient ai;
    @Autowired com.example.foodify.meal.MealService meals;
    String token,other;
    @BeforeEach void setup() {
        token=(String)auth.signup(UUID.randomUUID()+"@example.com","safe-password-123").get("accessToken");
        other=(String)auth.signup(UUID.randomUUID()+"@example.com","safe-password-123").get("accessToken");
        if(db.queryForObject("SELECT COUNT(*) FROM foods WHERE id='test-food'",Integer.class)==0)
            db.update("INSERT INTO foods(id,name,aliases,basis_grams,kcal,carbs,protein,fat,source,source_version) VALUES('test-food','시험 음식','',100,200,30,10,5,'TEST FIXTURE ONLY','test-v1')");
    }
    JsonNode body(String s) throws Exception {return mapper.readTree(s);}
    JsonNode upload(String key) throws Exception {
        var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(10,10,BufferedImage.TYPE_INT_RGB),"jpg",out);
        return body(mvc.perform(multipart("/api/v1/meals").file(new MockMultipartFile("images","meal.jpg","image/jpeg",out.toByteArray()))
            .param("eatenAt",Long.toString(System.currentTimeMillis())).param("consent","true")
            .header("Idempotency-Key",key).header("Authorization","Bearer "+token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    @Test void uploadsAreIdempotentAndOwnerOnly() throws Exception {
        String key=UUID.randomUUID().toString();JsonNode first=upload(key),second=upload(key);
        assertEquals(first.path("id"),second.path("id"));
        mvc.perform(get("/api/v1/meals/"+first.path("id").asText()).header("Authorization","Bearer "+other)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/photos/"+first.path("photos").get(0).path("id").asText()).header("Authorization","Bearer "+other)).andExpect(status().isNotFound());
    }
    @Test void autoGoalsArePreviewedAndSavedForOwnerOnly() throws Exception {
        String profile="{\"age\":30,\"sex\":\"MALE\",\"heightCm\":180,\"weightKg\":80,\"activityLevel\":\"SEDENTARY\",\"generalAdultConfirmed\":true,\"kcal\":1,\"purpose\":\"ignored\"}";
        mvc.perform(post("/api/v1/me/goals/preview").header("Authorization","Bearer "+token).contentType("application/json").content(profile))
            .andExpect(status().isOk()).andExpect(jsonPath("$.kcal").value(2136));
        mvc.perform(get("/api/v1/me").header("Authorization","Bearer "+token)).andExpect(jsonPath("$.goals.kcal").doesNotExist());
        mvc.perform(put("/api/v1/me/goals").header("Authorization","Bearer "+token).contentType("application/json").content(profile))
            .andExpect(status().isOk()).andExpect(jsonPath("$.kcal").value(2136)).andExpect(jsonPath("$.purpose").doesNotExist());
        mvc.perform(get("/api/v1/me").header("Authorization","Bearer "+token)).andExpect(jsonPath("$.goals.profile.age").value(30));
        mvc.perform(get("/api/v1/me").header("Authorization","Bearer "+other)).andExpect(jsonPath("$.goals.kcal").doesNotExist());
        mvc.perform(post("/api/v1/me/goals/preview").contentType("application/json").content(profile)).andExpect(status().isUnauthorized());
    }
    @Test void analysisPreviewHasNutritionButRemainsUnconfirmed() throws Exception {
        db.update("UPDATE analyses SET status='SUPERSEDED' WHERE status='QUEUED'");
        JsonNode meal=upload(UUID.randomUUID().toString());
        var fake=org.mockito.Mockito.mock(OpenAiClient.class);
        org.mockito.Mockito.when(fake.analyze(org.mockito.ArgumentMatchers.anyList())).thenReturn(json.read("{\"items\":[{\"name\":\"시험 음식\",\"grams\":150,\"ingredients\":[],\"uncertainty\":\"사진 추정\"}]}"));
        org.mockito.Mockito.when(fake.chooseCandidates(org.mockito.ArgumentMatchers.any())).thenReturn(json.read("{\"selections\":[{\"index\":0,\"foodId\":\"test-food\"}]}"));
        new AnalysisWorker(db,tx,fake,photos,json,foods).tick();
        var result=meals.get(auth.member(token),meal.path("id").asText());
        var item=((JsonNode)result.get("items")).get(0);
        assertEquals(300,item.path("nutrition").path("kcal").asInt());
        assertFalse(item.path("confirmed").asBoolean());
        assertEquals("INCOMPLETE",result.get("status"));
        assertEquals(false,result.get("analysisEnabled"));
    }
    @Test void correctionRecalculatesAndProtectsVersion() throws Exception {
        JsonNode meal=upload(UUID.randomUUID().toString());String id=meal.path("id").asText();
        String patch="{\"version\":0,\"items\":[{\"name\":\"시험 음식\",\"foodId\":\"test-food\",\"grams\":150,\"confirmed\":true}]}";
        mvc.perform(patch("/api/v1/meals/"+id).header("Authorization","Bearer "+token).contentType("application/json").content(patch)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETE")).andExpect(jsonPath("$.items[0].nutrition.kcal").value(300));
        mvc.perform(patch("/api/v1/meals/"+id).header("Authorization","Bearer "+token).contentType("application/json").content(patch)).andExpect(status().isConflict());
        String today=LocalDate.now(ZoneId.of("Asia/Seoul")).toString();
        mvc.perform(get("/api/v1/nutrition/summary").param("from",today).param("to",today).header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.totals.kcal").value(300));
    }
    @Test void incompleteDoesNotEnterTotalsAndMissingKeyIsExplicit() throws Exception {
        JsonNode meal=upload(UUID.randomUUID().toString());
        new AnalysisWorker(db,tx,ai,photos,json,foods).tick();
        // Worker may process an older queued record first; each invocation is bounded.
        for(int i=0;i<10 && "QUEUED".equals(db.queryForObject("SELECT status FROM analyses WHERE meal_id=?",String.class,meal.path("id").asText()));i++) new AnalysisWorker(db,tx,ai,photos,json,foods).tick();
        assertEquals("OPENAI_NOT_CONFIGURED",db.queryForObject("SELECT error_code FROM analyses WHERE meal_id=?",String.class,meal.path("id").asText()));
    }
    @Test void refreshRotatesAndLogoutRevokes() throws Exception {
        var session=auth.signup(UUID.randomUUID()+"@example.com","safe-password-123");
        String refresh=(String)session.get("refreshToken");var rotated=auth.refresh(refresh);
        assertThrows(ApiError.class,()->auth.refresh(refresh));
        assertNull(auth.member((String)session.get("accessToken")));
        auth.logout((String)rotated.get("accessToken"));assertNull(auth.member((String)rotated.get("accessToken")));
    }
    @Test void deletionRevokesMemberAndPhotos() throws Exception {
        JsonNode meal=upload(UUID.randomUUID().toString());
        mvc.perform(delete("/api/v1/me").header("Authorization","Bearer "+token)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/me").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM photos WHERE meal_id=?",Integer.class,meal.path("id").asText()));
    }
    @Test void nutritionNullIsNotZero() {
        assertEquals("NEEDS_REVIEW",foods.calculate(json.read("{\"name\":\"미상\",\"grams\":100,\"foodId\":\"absent\",\"confirmed\":true}")).get("status"));
        assertEquals(new java.math.BigDecimal("300.00"),FoodService.scale(new java.math.BigDecimal("200"),new java.math.BigDecimal("150"),new java.math.BigDecimal("100")));
    }
    @Test void delayedAnalysisCannotOverwriteUserCorrection() throws Exception {
        db.update("UPDATE analyses SET status='SUPERSEDED' WHERE status='QUEUED'");
        JsonNode meal=upload(UUID.randomUUID().toString());String id=meal.path("id").asText();
        var fake=org.mockito.Mockito.mock(OpenAiClient.class);
        org.mockito.Mockito.when(fake.analyze(org.mockito.ArgumentMatchers.anyList())).thenAnswer(invocation->{
            meals.update(auth.member(token),id,0,json.read("[{\"name\":\"사용자 수정\",\"foodId\":\"test-food\",\"grams\":100,\"confirmed\":true}]"));
            return json.read("{\"items\":[{\"name\":\"다른 음식\",\"grams\":100,\"ingredients\":[],\"uncertainty\":\"\"}]}");
        });
        new AnalysisWorker(db,tx,fake,photos,json,foods).tick();
        var result=meals.get(auth.member(token),id);
        assertEquals("COMPLETE",result.get("status"));assertEquals("사용자 수정",((JsonNode)result.get("items")).get(0).path("name").asText());
        assertEquals("SUPERSEDED",db.queryForObject("SELECT status FROM analyses WHERE meal_id=?",String.class,id));
    }
}
