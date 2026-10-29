package dev.epochex.trading;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
public class TradingController {
    record Order(@Min(1) @Max(2) long uid, @NotBlank @Size(max=128) String key,
                 @NotBlank String side, @NotBlank String orderType, @Min(1) @Max(1_000_000) long price,
                 @Min(1) @Max(1_000_000) long size) {}
    record Cancel(@Min(1) @Max(2) long uid, @NotBlank @Size(max=128) String key) {}
    private final DurableGateway gateway;
    public TradingController(DurableGateway gateway) { this.gateway = gateway; }
    @PostMapping("/orders")
    public Receipt place(@Valid @RequestBody Order request) {
        return gateway.submit(CommandRequest.place(request.uid(), request.key(), request.side(), request.orderType(), request.price(), request.size())).join();
    }
    @PostMapping("/orders/{orderId}/cancel")
    public Receipt cancel(@PathVariable long orderId, @Valid @RequestBody Cancel request) {
        return gateway.submit(CommandRequest.cancel(request.uid(), request.key(), orderId)).join();
    }
    @GetMapping("/book") public BookSnapshot book() { return gateway.book().join(); }
    @GetMapping("/status") public Map<String, Object> status() {
        return Map.of("commands", gateway.commandCount().join(), "balancesConserved", gateway.balancesConserved().join(),
                "mode", "single-process-demo", "symbol", EngineSession.SYMBOL);
    }
}
