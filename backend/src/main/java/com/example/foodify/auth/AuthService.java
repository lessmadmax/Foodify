package com.example.foodify.auth;

import com.example.foodify.api.ApiError;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.http.HttpStatus;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

@Service
public class AuthService {
    private final JdbcTemplate db;
    private final BCryptPasswordEncoder encoder=new BCryptPasswordEncoder(12);
    private final SecureRandom random=new SecureRandom();
    public AuthService(JdbcTemplate db) { this.db=db; }
    public static String hash(String s) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    private String token() { byte[] b=new byte[32]; random.nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    @Transactional
    public Map<String,Object> signup(String email,String password) {
        validatePassword(password);
        String id=UUID.randomUUID().toString();
        db.update("INSERT INTO members(id,email,password_hash,goals,created_at) VALUES(?,?,?,?,?)",id,email.toLowerCase(Locale.ROOT).trim(),encoder.encode(password),"{}",System.currentTimeMillis());
        return issue(id);
    }
    public Map<String,Object> login(String email,String password) {
        validatePassword(password);
        var rows=db.queryForList("SELECT id,password_hash FROM members WHERE email=?",email.toLowerCase(Locale.ROOT).trim());
        if(rows.isEmpty() || !encoder.matches(password,(String)rows.getFirst().get("password_hash"))) throw unauthorized();
        return issue((String)rows.getFirst().get("id"));
    }
    private void validatePassword(String password) {
        if(password==null || password.length()<10 || password.getBytes(StandardCharsets.UTF_8).length>72) throw ApiError.bad("PASSWORD_LENGTH");
    }
    private Map<String,Object> issue(String member) {
        String access=token(),refresh=token(); long now=System.currentTimeMillis();
        db.update("INSERT INTO sessions VALUES(?,?,?,?,?,?)",UUID.randomUUID().toString(),member,hash(access),hash(refresh),now+900000,now+2592000000L);
        return Map.of("accessToken",access,"refreshToken",refresh,"expiresIn",900,"memberId",member);
    }
    @Transactional
    public Map<String,Object> refresh(String token) {
        var rows=db.queryForList("SELECT id,member_id FROM sessions WHERE refresh_hash=? AND refresh_expires>? FOR UPDATE",hash(token),System.currentTimeMillis());
        if(rows.isEmpty()) throw unauthorized();
        db.update("DELETE FROM sessions WHERE id=?",rows.getFirst().get("id"));
        return issue((String)rows.getFirst().get("member_id"));
    }
    public String member(String token) {
        var ids=db.queryForList("SELECT member_id FROM sessions WHERE access_hash=? AND access_expires>?",String.class,hash(token),System.currentTimeMillis());
        return ids.isEmpty()?null:ids.getFirst();
    }
    public void logout(String access) { db.update("DELETE FROM sessions WHERE access_hash=?",hash(access)); }
    public static ApiError unauthorized() { return new ApiError(HttpStatus.UNAUTHORIZED,"UNAUTHORIZED"); }
}
