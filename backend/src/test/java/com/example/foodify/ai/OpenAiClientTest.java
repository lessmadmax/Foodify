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
        server.createContext("/responses",exchange->{request.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));byte[] bytes=response.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});server.start();
    }
    @AfterEach void stop(){server.stop(0);}
    OpenAiClient client(int limit){return new OpenAiClient("test-key","test-model","http://127.0.0.1:"+server.getAddress().getPort(),limit,json,db,tx);}
    @Test void sendsImagesAndStrictSchemaWithoutStorage() throws Exception {
        var result=client(2).analyze(List.of(new byte[]{1,2,3}));assertEquals("밥",result.path("items").get(0).path("name").asText());
        var sent=new ObjectMapper().readTree(request.get());assertFalse(sent.get("store").asBoolean());assertTrue(sent.path("text").path("format").path("strict").asBoolean());
        assertTrue(sent.path("input").get(0).path("content").get(1).path("image_url").asText().startsWith("data:image/jpeg;base64,"));
        assertThrows(ApiError.class,()->client(1).analyze(List.of(new byte[]{1})));
    }
    @Test void incompleteResponseIsNotAccepted(){response="{\"status\":\"incomplete\",\"output\":[]}";assertEquals("OPENAI_INCOMPLETE",assertThrows(ApiError.class,()->client(2).analyze(List.of(new byte[]{1}))).code);}
}
