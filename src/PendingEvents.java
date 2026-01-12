import java.util.Map;
import java.util.concurrent.*;
//falls ack for register kommt
public final class PendingEvents {

    private final Map<Long, CompletableFuture<AckEvent>> waiters = new ConcurrentHashMap<>();
    private final Map<Long, AckEvent> early = new ConcurrentHashMap<>();


    public void onEvent(long seq, AckEvent ev) {
        CompletableFuture<AckEvent> f = waiters.remove(seq);
        if (f != null) {
            f.complete(ev);
        } else {
            // Event kam bevor jemand register gemacht hat
            early.put(seq, ev);
        }
    }


    public CompletableFuture<AckEvent> register(long seq) {
        AckEvent ev = early.remove(seq);
        CompletableFuture<AckEvent> f = new CompletableFuture<>();

        if (ev != null) {
            f.complete(ev);
            return f;
        }

        CompletableFuture<AckEvent> prev = waiters.put(seq, f);
        if (prev != null && !prev.isDone()) {
            prev.completeExceptionally(new IllegalStateException("Duplicate waiter for seq=" + seq));
        }
        return f;
    }

    public void cancel(long seq) {
        CompletableFuture<AckEvent> f = waiters.remove(seq);
        if (f != null) f.cancel(false);
        early.remove(seq);
    }
}

