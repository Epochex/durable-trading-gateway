package dev.epochex.trading;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import java.nio.file.Path;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GatewayHttpTest {
    @TempDir Path dir;
    DurableGateway gateway;
    MockMvc mvc;
    @BeforeEach void setup() throws Exception {
        gateway = new DurableGateway(dir.resolve("http.wal"), 16);
        mvc = MockMvcBuilders.standaloneSetup(new TradingController(gateway)).setControllerAdvice(new TradingErrors()).build();
    }
    @AfterEach void close() throws Exception { gateway.close(); }
    @Test void acceptsOrderAndRejectsConflictingRetry() throws Exception {
        String body = "{\"uid\":1,\"key\":\"request-1\",\"side\":\"ASK\",\"orderType\":\"GTC\",\"price\":100,\"size\":10}";
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.orderId").value(1)).andExpect(jsonPath("$.result").value("SUCCESS"));
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.sequence").value(1));
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(body.replace("100", "101")))
            .andExpect(status().isConflict());
        mvc.perform(get("/book")).andExpect(status().isOk()).andExpect(jsonPath("$.askVolumes[0]").value(10));
    }
    @Test void invalidRequestDoesNotWrite() throws Exception {
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content("{\"uid\":1}"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/status")).andExpect(status().isOk()).andExpect(jsonPath("$.commands").value(0));
    }
}
