package dev.epochex.trading;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;
import java.nio.file.Path;

@SpringBootApplication
public class TradingApplication {
    public static void main(String[] args) { SpringApplication.run(TradingApplication.class, args); }
    @Bean(destroyMethod = "close")
    DurableGateway gateway(@Value("${trading.journal:data/commands.wal}") String journal,
                           @Value("${trading.queue-capacity:1024}") int capacity) throws Exception {
        return new DurableGateway(Path.of(journal), capacity);
    }
}
