package com.example.foodify.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.dao.DuplicateKeyException;
import java.util.Map;

public class ApiError extends RuntimeException {
    public final HttpStatus status;
    public final String code;
    public ApiError(HttpStatus status, String code) { super(code); this.status=status; this.code=code; }
    public static ApiError bad(String code) { return new ApiError(HttpStatus.BAD_REQUEST, code); }
    public static ApiError missing() { return new ApiError(HttpStatus.NOT_FOUND,"NOT_FOUND"); }
    public static ApiError conflict(String code) { return new ApiError(HttpStatus.CONFLICT,code); }
    @RestControllerAdvice
    public static class Handler {
        @ExceptionHandler(ApiError.class)
        public org.springframework.http.ResponseEntity<?> handle(ApiError e) {
            return org.springframework.http.ResponseEntity.status(e.status).body(Map.of("code",e.code));
        }
        @ExceptionHandler({MethodArgumentNotValidException.class,HttpMessageNotReadableException.class,IllegalArgumentException.class})
        @ResponseStatus(HttpStatus.BAD_REQUEST)
        public Map<String,String> invalid(Exception e) { return Map.of("code","INVALID_INPUT"); }
        @ExceptionHandler(DuplicateKeyException.class) @ResponseStatus(HttpStatus.CONFLICT)
        public Map<String,String> duplicate() { return Map.of("code","ALREADY_EXISTS"); }
        @ExceptionHandler(MaxUploadSizeExceededException.class) @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
        public Map<String,String> large() { return Map.of("code","IMAGE_TOO_LARGE"); }
    }
}
