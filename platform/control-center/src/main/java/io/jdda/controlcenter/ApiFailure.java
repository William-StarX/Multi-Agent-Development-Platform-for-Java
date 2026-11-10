package io.jdda.controlcenter;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

class ApiFailure extends RuntimeException {
    final int status;
    final String code;

    ApiFailure(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    static Map<String, Object> body(String code, String message) {
        return Map.of("code", code, "message", message, "traceId", UUID.randomUUID().toString(),
                "retryable", false, "details", Map.of());
    }
}

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(ApiFailure.class)
    ResponseEntity<?> failure(ApiFailure error) {
        return ResponseEntity.status(error.status).body(ApiFailure.body(error.code, error.getMessage()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> malformed(Exception error) {
        return ResponseEntity.badRequest().body(ApiFailure.body("INVALID_PAYLOAD", "Invalid request format."));
    }
}
