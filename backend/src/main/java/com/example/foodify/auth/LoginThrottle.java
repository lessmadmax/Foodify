package com.example.foodify.auth;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/** Single-instance limit; reverse-proxy IPs are intentionally not trusted from arbitrary headers. */
public class LoginThrottle extends OncePerRequestFilter {
    private record Bucket(long minute,int count){}
    private final ConcurrentHashMap<String,Bucket> attempts=new ConcurrentHashMap<>();
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
        String path=req.getRequestURI();
        if(path.equals("/api/v1/auth/login")||path.equals("/api/v1/auth/signup")) {
            long minute=System.currentTimeMillis()/60000;
            attempts.entrySet().removeIf(e->e.getValue().minute()<minute);
            Bucket b=attempts.compute(req.getRemoteAddr(),(k,v)->new Bucket(minute,v==null?1:v.count()+1));
            if(b.count()>20){res.setStatus(429);res.setContentType("application/json");res.getWriter().write("{\"code\":\"AUTH_RATE_LIMIT\"}");return;}
        }
        chain.doFilter(req,res);
    }
}
