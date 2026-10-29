package dev.epochex.trading;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.io.IOException;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class DurableGatewayTest {
    @TempDir Path dir;
    private CommandRequest sell(String key, long price, long size) {
        return CommandRequest.place(1, key, "ASK", "GTC", price, size);
    }
    @Test void concurrentRetriesProduceOneOrderAndOriginalReceipt() throws Exception {
        try (DurableGateway gateway = new DurableGateway(dir.resolve("c.wal"), 128)) {
            ExecutorService callers = Executors.newFixedThreadPool(8);
            try {
                List<Future<Receipt>> receipts = new ArrayList<>();
                for (int i = 0; i < 48; i++) receipts.add(callers.submit(() -> gateway.submit(sell("one", 100, 10)).get()));
                Receipt original = receipts.get(0).get(10, TimeUnit.SECONDS);
                for (Future<Receipt> receipt : receipts) assertEquals(original, receipt.get());
                assertEquals(10, gateway.book().get().askVolumes()[0]);
                assertTrue(gateway.balancesConserved().get());
                assertEquals(1, gateway.commandCount().get());
            } finally { callers.shutdownNow(); }
        }
    }
    @Test void conflictingKeyDoesNotPoisonNextCommand() throws Exception {
        try (DurableGateway gateway = new DurableGateway(dir.resolve("c.wal"), 16)) {
            gateway.submit(sell("one", 100, 10)).get();
            ExecutionException conflict = assertThrows(ExecutionException.class, () -> gateway.submit(sell("one", 101, 10)).get());
            assertInstanceOf(DurableGateway.Conflict.class, conflict.getCause());
            assertEquals("SUCCESS", gateway.submit(sell("two", 102, 10)).get().result());
            assertEquals(2, gateway.commandCount().get());
        }
    }
    @Test void restoresBookAndOriginalReceiptAfterPartialFillAndCancel() throws Exception {
        Path path = dir.resolve("c.wal");
        Receipt sell;
        BookSnapshot before;
        try (DurableGateway gateway = new DurableGateway(path, 16)) {
            sell = gateway.submit(sell("sell", 100, 10)).get();
            gateway.submit(CommandRequest.place(2, "buy", "BID", "IOC", 100, 4)).get();
            assertEquals(6, gateway.book().get().askVolumes()[0]);
            assertEquals("MATCHING_UNKNOWN_ORDER_ID", gateway.submit(CommandRequest.cancel(2, "not-mine", sell.orderId())).get().result());
            assertEquals("SUCCESS", gateway.submit(CommandRequest.cancel(1, "cancel", sell.orderId())).get().result());
            gateway.submit(sell("keep", 110, 7)).get();
            assertTrue(gateway.balancesConserved().get());
            before = gateway.book().get();
        }
        try (DurableGateway gateway = new DurableGateway(path, 16)) {
            assertEquals(sell, gateway.submit(sell("sell", 100, 10)).get());
            assertArrayEquals(before.askPrices(), gateway.book().get().askPrices());
            assertArrayEquals(before.askVolumes(), gateway.book().get().askVolumes());
            assertEquals(5, gateway.commandCount().get());
            assertTrue(gateway.balancesConserved().get());
        }
    }
    @Test void rejectedRiskCommandHasStableReceiptAcrossRestart() throws Exception {
        Path path = dir.resolve("c.wal");
        CommandRequest impossible = CommandRequest.place(1, "too-much", "BID", "GTC", 1_000_000, 1_000_000);
        Receipt rejected;
        try (DurableGateway gateway = new DurableGateway(path, 16)) {
            rejected = gateway.submit(impossible).get();
            assertEquals("RISK_NSF", rejected.result());
        }
        try (DurableGateway gateway = new DurableGateway(path, 16)) {
            assertEquals(rejected, gateway.submit(impossible).get());
            assertEquals(1, gateway.commandCount().get());
            assertTrue(gateway.balancesConserved().get());
        }
    }
    @Test void idempotencyNamespaceIsPerUser() throws Exception {
        try (DurableGateway gateway = new DurableGateway(dir.resolve("c.wal"), 16)) {
            assertEquals(1, gateway.submit(sell("same", 100, 10)).get().sequence());
            assertEquals(2, gateway.submit(CommandRequest.place(2, "same", "BID", "GTC", 90, 10)).get().sequence());
        }
    }
    @Test void validatesInputBeforeWriting() throws Exception {
        try (DurableGateway gateway = new DurableGateway(dir.resolve("c.wal"), 16)) {
            assertThrows(IllegalArgumentException.class, () -> gateway.submit(sell("", 100, 1)));
            assertThrows(IllegalArgumentException.class, () -> gateway.submit(sell("negative", -1, 1)));
            assertThrows(IllegalArgumentException.class, () -> gateway.submit(CommandRequest.place(3, "unknown", "BID", "GTC", 100, 1)));
            assertEquals(0, gateway.commandCount().get());
        }
    }
    @Test void recoversDurableIntentEvenWhenNoReceiptWasCreated() throws Exception {
        Path path = dir.resolve("intent.wal");
        // State of the filesystem after force() and before calling the engine.
        try (FileJournal journal = new FileJournal(path)) {
            journal.append(new ObjectMapper().writeValueAsBytes(new DurableGateway.Event(1, 1, sell("lost-response", 100, 10))));
        }
        try (DurableGateway gateway = new DurableGateway(path, 16)) {
            Receipt recovered = gateway.submit(sell("lost-response", 100, 10)).get();
            assertEquals(new Receipt(1, 1, "SUCCESS"), recovered);
            assertEquals(10, gateway.book().get().askVolumes()[0]);
            assertEquals(1, gateway.commandCount().get());
        }
    }
    @Test void rejectsOverflowWithoutRecordingOrLosingQueuedWork() throws Exception {
        try (DurableGateway gateway = new DurableGateway(dir.resolve("queue.wal"), 1)) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            CompletableFuture<Void> blocked = gateway.enqueue(() -> {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test latch timed out");
                return null;
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            CompletableFuture<Receipt> admitted = gateway.submit(sell("admitted", 100, 10));
            try {
                ExecutionException rejected = assertThrows(ExecutionException.class, () -> gateway.submit(sell("overflow", 100, 10)).get());
                assertInstanceOf(DurableGateway.Unavailable.class, rejected.getCause());
            } finally { release.countDown(); }
            blocked.get();
            assertEquals("SUCCESS", admitted.get().result());
            assertEquals(1, gateway.commandCount().get());
        }
    }
    @Test void uncertainExecutionFailsClosed() throws Exception {
        try (DurableGateway gateway = new DurableGateway(dir.resolve("failed.wal"), 16)) {
            assertThrows(ExecutionException.class, () -> gateway.enqueue(() -> { throw new IOException("uncertain write outcome"); }).get());
            ExecutionException rejected = assertThrows(ExecutionException.class, () -> gateway.submit(sell("later", 100, 10)).get());
            assertInstanceOf(DurableGateway.Unavailable.class, rejected.getCause());
        }
    }
    @Test void rejectsSequenceGapInsteadOfSilentlyReplayingIt() throws Exception {
        Path path = dir.resolve("gap.wal");
        try (FileJournal journal = new FileJournal(path)) {
            journal.append(new ObjectMapper().writeValueAsBytes(new DurableGateway.Event(1, 2, sell("gap", 100, 10))));
        }
        assertThrows(IllegalStateException.class, () -> new DurableGateway(path, 16));
        // A failed recovery must release ownership of the journal.
        try (FileJournal ignored = new FileJournal(path)) {}
    }
}
