package com.example.foodify.auth;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController @RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth) { this.auth=auth; }
    public record Credentials(@NotBlank @Email @Size(max=254) String email,@NotBlank String password) {}
    public record Refresh(@NotBlank @Size(max=200) String refreshToken) {}
    @PostMapping("/signup") public Map<String,Object> signup(@Valid @RequestBody Credentials body) { return auth.signup(body.email(),body.password()); }
    @PostMapping("/login") public Map<String,Object> login(@Valid @RequestBody Credentials body) { return auth.login(body.email(),body.password()); }
    @PostMapping("/refresh") public Map<String,Object> refresh(@Valid @RequestBody Refresh body) { return auth.refresh(body.refreshToken()); }
    @PostMapping("/logout") public void logout(@RequestHeader("Authorization") String token) { auth.logout(token.substring(7)); }
}
