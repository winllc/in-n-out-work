package com.winllc.innoutwork.rest;

import com.winllc.innoutwork.service.InvalidRequestException;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The JSON answer to an {@link InvalidRequestException}, for the APIs that throw one: its status and
 * {@code {"error": "...", "message": "..."}}, as the license refusals are shaped. Used from controller-local
 * exception handlers, which Spring prefers to the application-wide ExceptionController (an HTML error page).
 */
public final class ApiErrors {

    private ApiErrors() {
    }

    public static ResponseEntity<Map<String, String>> of(InvalidRequestException e) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", e.error());
        body.put("message", e.getMessage());
        return ResponseEntity.status(e.status()).body(body);
    }
}
