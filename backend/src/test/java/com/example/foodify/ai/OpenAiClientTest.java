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
    @Test void incompleteResponseIsNotAccepted(){response="{\"status\":\"incomplete\",\"output\":[]}";assertEquals("OPENAI_INCOMPLETE",assertThrows(ApiError.class,()->client(2).analyze(List.of(new byte[]{1}))).code);}
}
