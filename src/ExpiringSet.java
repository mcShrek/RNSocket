import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Map;
//duplikatenerkennung
public final class ExpiringSet<K> {

    private final ConcurrentHashMap<K, Long> seenAt = new ConcurrentHashMap<>();
    private final long ttlMs;
    private final long purgeIntervalMs;
    private final AtomicLong lastPurgeMs = new AtomicLong(0);

    public ExpiringSet(long ttlMs, long purgeIntervalMs) {
        if (ttlMs <= 0) throw new IllegalArgumentException("ttlMs must be > 0");
        if (purgeIntervalMs <= 0) throw new IllegalArgumentException("purgeIntervalMs must be > 0");
        this.ttlMs = ttlMs;
        this.purgeIntervalMs = purgeIntervalMs;
    }

    /**
     * @return true, wenn das Key "neu" ist (nicht gesehen oder abgelaufen),
     *         false, wenn es innerhalb TTL schon gesehen wurde (Duplicate).
     */
    public boolean markIfNew(K key, long nowMs) {
        purgeMaybe(nowMs);

        final long[] old = new long[1];
        old[0] = -1;

        seenAt.compute(key, (k, prev) -> {
            if (prev == null) {
                old[0] = -1;
                return nowMs;
            }
            old[0] = prev;
            if (nowMs - prev >= ttlMs) {
                // abgelaufen -> als "neu" behandeln und Timestamp erneuern
                return nowMs;
            }
            // noch gültig -> Duplicate, Timestamp NICHT updaten (optional)
            return prev;
        });

        return old[0] == -1 || (nowMs - old[0] >= ttlMs);
    }

    public void purgeNow(long nowMs) {
        seenAt.entrySet().removeIf(e -> nowMs - e.getValue() >= ttlMs);
        lastPurgeMs.set(nowMs);
    }

    private void purgeMaybe(long nowMs) {
        long last = lastPurgeMs.get();
        if (nowMs - last < purgeIntervalMs) return;
        if (lastPurgeMs.compareAndSet(last, nowMs)) {
            seenAt.entrySet().removeIf(e -> nowMs - e.getValue() >= ttlMs);
        }
    }

    public int size() {
        return seenAt.size();
    }
}

