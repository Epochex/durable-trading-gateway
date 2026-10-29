package dev.epochex.trading;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/** Serial durable admission into an initially empty engine; all writes must use this boundary. */
public final class DurableGateway implements AutoCloseable {
    public static final class Conflict extends RuntimeException { Conflict() { super("Key already used for different command"); } }
    public static final class Unavailable extends RuntimeException { Unavailable(String message) { super(message); } }
    record Event(int version, long sequence, CommandRequest request) {}
    private record Scope(long uid, String key) {}
    private record Applied(CommandRequest request, Receipt receipt) {}
    private final Map<Scope, Applied> applied = new HashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private final FileJournal journal;
    private final EngineSession engine;
    private final ThreadPoolExecutor worker;
    private volatile boolean failed;
    private long sequence;

    public DurableGateway(Path path, int capacity) throws Exception {
        if (capacity <= 0) throw new IllegalArgumentException("Positive queue capacity required");
        journal = new FileJournal(path);
        EngineSession created = null;
        try {
            created = new EngineSession();
            engine = created;
            for (byte[] bytes : journal.readAll()) {
                Event event = mapper.readValue(bytes, Event.class);
                event.request().validate();
                if (event.version() != 1 || event.sequence() != sequence + 1 || applied.containsKey(scope(event.request())))
                    throw new IllegalStateException("Invalid WAL version, sequence or duplicate key");
                apply(event);
            }
            worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(capacity),
                runnable -> new Thread(runnable, "durable-trading-writer"), new ThreadPoolExecutor.AbortPolicy());
        } catch (Exception | LinkageError e) {
            if (created != null) created.close();
            journal.close();
            throw e;
        }
    }

    public CompletableFuture<Receipt> submit(CommandRequest request) {
        Objects.requireNonNull(request).validate();
        return enqueue(() -> {
            Applied previous = applied.get(scope(request));
            if (previous != null) {
                if (!previous.request().equals(request)) throw new Conflict();
                return previous.receipt();
            }
            Event event = new Event(1, Math.addExact(sequence, 1), request);
            // Once this durable intent exists, any uncertain result must be recovered by restart.
            journal.append(mapper.writeValueAsBytes(event));
            return apply(event);
        });
    }

    private Receipt apply(Event event) throws Exception {
        CommandRequest request = event.request();
        Receipt receipt = new Receipt(event.sequence(), "PLACE".equals(request.kind()) ? event.sequence() : request.targetOrderId(),
                engine.apply(event.sequence(), request));
        applied.put(scope(request), new Applied(request, receipt));
        sequence = event.sequence();
        return receipt;
    }
    private Scope scope(CommandRequest request) { return new Scope(request.uid(), request.key()); }

    public CompletableFuture<BookSnapshot> book() { return enqueue(engine::book); }
    public CompletableFuture<Boolean> balancesConserved() { return enqueue(engine::balancesConserved); }
    public CompletableFuture<Long> commandCount() { return enqueue(() -> sequence); }

    <T> CompletableFuture<T> enqueue(Callable<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        if (failed) return CompletableFuture.failedFuture(new Unavailable("Restart required after uncertain outcome"));
        try {
            worker.execute(() -> {
                if (failed) { result.completeExceptionally(new Unavailable("Restart required")); return; }
                try { result.complete(action.call()); }
                catch (Conflict e) { result.completeExceptionally(e); }
                catch (Exception | LinkageError e) { failed = true; result.completeExceptionally(e); }
            });
        } catch (RejectedExecutionException e) { result.completeExceptionally(new Unavailable("Admission queue full or gateway closed")); }
        return result;
    }

    @Override public void close() throws Exception {
        worker.shutdown();
        if (!worker.awaitTermination(30, TimeUnit.SECONDS)) throw new IllegalStateException("Writer has not stopped; journal remains owned");
        try { engine.close(); } finally { journal.close(); }
    }
}
