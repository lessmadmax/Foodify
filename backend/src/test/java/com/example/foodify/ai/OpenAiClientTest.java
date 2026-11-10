package com.example.foodify.ai;
import com.example.foodify.api.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class OpenAiClientTest {
    @Autowired JdbcTemplate db;@Autowired TransactionTemplate tx;@Autowired Json json;
    HttpServer server;final AtomicReference<String> request=new AtomicReference<>();String response;
    @BeforeEach void start() throws Exception {
        db.update("DELETE FROM ai_budget");
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        response="{\"status\":\"completed\",\"usage\":{\"input_tokens\":10,\"output_tokens\":5},\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"{\\\"items\\\":[{\\\"name\\\":\\\"밥\\\",\\\"grams\\\":120,\\\"ingredients\\\":[],\\\"uncertainty\\\":\\\"확인 필요\\\"}]}\"}]}]}";
        var root=json.read(response);
        var content=(com.fasterxml.jackson.databind.node.ObjectNode)root.path("output").get(0).path("content").get(0);
        var item=json.read(content.path("text").asText());
        ((com.fasterxml.jackson.databind.node.ObjectNode)item.path("items").get(0)).set("weightEstimate",json.read("{\"lowerGrams\":90,\"upperGrams\":150,\"assumptions\":[\"보통 크기 공기밥 일부로 가정\"]}"));
        content.put("text",json.write(item)); response=json.write(root);
        server.createContext("/responses",exchange->{request.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));byte[] bytes=response.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});server.start();
    }
    @AfterEach void stop(){server.stop(0);}
    @Test void sendsArEvidenceAndPreservesUnknownWeight() {
        response=response.replace("120", "null").replace("90", "null").replace("150", "null");
        var result=client(2).analyze(List.of(new byte[]{1}),java.util.Map.of("method","ar_assisted_photo","ar",java.util.Map.of("sceneMedianDepthMm",650)));
        assertTrue(result.path("items").get(0).path("grams").isNull());
        assertTrue(request.get().contains("sceneMedianDepthMm"));
        assertTrue(request.get().contains("음식 높이·부피·중량이 아니며"));
    }
    @Test void sendsDeviceCandidatesAsEvidenceWithoutForcingSingleDish() {
        var evidence=java.util.Map.<String,Object>of("method","gallery_photos","foodRecognition",java.util.Map.of("model","Food-101-93M","predictions",List.of(java.util.Map.of("imageIndex",0,"candidates",List.of(java.util.Map.of("label","hamburger","score",0.85))))));
        client(2).analyze(List.of(new byte[]{1}),evidence);
        assertTrue(request.get().contains("Food-101-93M"));
        assertTrue(request.get().contains("hamburger"));
        assertTrue(request.get().contains("사진에 여러 음식이 있으면 각각 구분"));
        assertTrue(request.get().contains("후보 하나를 모든 음식에 적용하지 마세요"));
    }
    @Test void lowScoreCandidatesAreLeftOutAndMarkedOutOfClass() {
        var evidence=java.util.Map.<String,Object>of("method","gallery_photos","foodRecognition",java.util.Map.of("model","Food-101-93M","predictions",List.of(java.util.Map.of("imageIndex",0,"candidates",List.of(java.util.Map.of("label","foie_gras","score",0.11))))));
        client(2).analyze(List.of(new byte[]{1}),evidence);
        assertFalse(request.get().contains("foie_gras"));
        assertTrue(request.get().contains("LOW_TOP_SCORE"));
        assertTrue(request.get().contains("101종 분류 밖일 가능성이 크니"));
    }
    @Test void asksForCategoryNameWhenRawKindIsUncertain() {
        client(2).analyze(List.of(new byte[]{1}));
        assertTrue(request.get().contains("확실하지 않으면 '견과류'"));
        assertTrue(request.get().contains("코팅·당·양념이 보이면 이름에 드러내세요"));
        assertEquals("v4-category-names",db.queryForObject("SELECT prompt_version FROM ai_usage ORDER BY created_at DESC LIMIT 1",String.class));
    }
    OpenAiClient client(int limit){return new OpenAiClient("test-key","test-model","http://127.0.0.1:"+server.getAddress().getPort(),limit,json,db,tx);}
    @Test void chatSendsRolesAndFreshEvidenceWithoutExternalStorage(){
        response=json.write(java.util.Map.of("status","completed","output",List.of(java.util.Map.of("content",List.of(java.util.Map.of("type","output_text","text","{\"answer\":\"저녁 안내\"}"))))));
        assertEquals("저녁 안내",client(3).chat("fresh-records",List.of(java.util.Map.of("question","첫 질문","answer","이전 답변")),"후속 질문"));
        var sent=json.read(request.get());assertFalse(sent.path("store").asBoolean());
        assertEquals("developer",sent.path("input").get(0).path("role").asText());
        assertEquals("assistant",sent.path("input").get(2).path("role").asText());
        assertTrue(request.get().contains("fresh-records"));assertTrue(request.get().contains("후속 질문"));
    }
    @Test void sendsImagesAndStrictSchemaWithoutStorage() throws Exception {
        var result=client(2).analyze(List.of(new byte[]{1,2,3}));assertEquals("밥",result.path("items").get(0).path("name").asText());
        assertEquals(90,result.path("items").get(0).path("weightEstimate").path("lowerGrams").asInt());
        var sent=new ObjectMapper().readTree(request.get());assertFalse(sent.get("store").asBoolean());assertTrue(sent.path("text").path("format").path("strict").asBoolean());
        assertTrue(sent.path("input").get(0).path("content").get(1).path("image_url").asText().startsWith("data:image/jpeg;base64,"));
        assertThrows(ApiError.class,()->client(1).analyze(List.of(new byte[]{1})));
    }
    String output(String text){return json.write(java.util.Map.of("status","completed","usage",java.util.Map.of("input_tokens",1,"output_tokens",1),"output",List.of(java.util.Map.of("type","message","content",List.of(java.util.Map.of("type","output_text","text",text))))));}
    @Test void heightAsksOnlyForMeanHeightWithMeasuredArea() {
        response=output("{\"heightCm\":2.5,\"assumptions\":[\"밥이 약 2.5cm 쌓임\"]}");
        var result=client(5).estimateHeight(new byte[]{1},"쌀밥",300,0.1,40,30,20.2);
        assertEquals(2.5,result.path("heightCm").asDouble());
        var sent=json.read(request.get());
        assertEquals("foodify_ar_height",sent.path("text").path("format").path("name").asText());
        String prompt=sent.path("input").get(0).path("content").get(0).path("text").asText();
        assertTrue(prompt.contains("평균 높이(cm)만") && prompt.contains("20.2cm²") && prompt.contains("30.0cm"));
        response=output("{\"heightCm\":45,\"assumptions\":[]}");
        assertEquals("AI_INVALID_OUTPUT",assertThrows(ApiError.class,()->client(5).estimateHeight(new byte[]{1},"쌀밥",300,0.1,40,30,20.2)).code);
    }
    @Test void incompleteResponseIsNotAccepted(){response="{\"status\":\"incomplete\",\"output\":[]}";assertEquals("OPENAI_INCOMPLETE",assertThrows(ApiError.class,()->client(2).analyze(List.of(new byte[]{1}))).code);}
}
