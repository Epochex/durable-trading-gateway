package dev.epochex.trading;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.concurrent.CompletionException;

@RestControllerAdvice
public class TradingErrors {
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) { return response(400, error.getMessage()); }
    @ExceptionHandler(CompletionException.class)
    ResponseEntity<Map<String, String>> command(CompletionException error) {
        Throwable cause = error.getCause();
        if (cause instanceof DurableGateway.Conflict) return response(409, cause.getMessage());
        return response(503, "Command unavailable; retry using the same uid and key");
    }
    private ResponseEntity<Map<String, String>> response(int status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
