package com.example.foodify.api;
import org.springframework.web.bind.annotation.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.security.Principal;
@RestController @RequestMapping("/api/v1/chat")
public class ChatController {
    private final ChatService chat;
    public ChatController(ChatService chat){this.chat=chat;}
    @GetMapping public Object history(Principal p){return chat.history(p.getName());}
    @PostMapping public Object send(Principal p,@RequestBody JsonNode body){return chat.send(p.getName(),body.path("requestKey").asText(),body.path("message").asText());}
    @DeleteMapping public void clear(Principal p){chat.clear(p.getName());}
}
