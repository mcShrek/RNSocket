package Routing;

import Connect.Protokoll;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class NeighborTable {


    public static final long HEARTBEAT_INTERVAL_MS = Protokoll.HEARTBEAT_INTERVAL_MS;
    public static final long DEAD_AFTER_MS = Protokoll.NEIGHBOR_DEAD_AFTER_MS;



    private final Map<NodeId, NeighborEntry> neighbors = new ConcurrentHashMap<>();

    //setzt nachbarn auf alive
    public NeighborEntry upsertAlive(NodeId neighbor, long nowMs) {
        NeighborEntry entry = neighbors.get(neighbor);
        if (entry == null) {
            entry = new NeighborEntry(neighbor, nowMs, true);
            neighbors.put(neighbor, entry);
            return entry;
        }
        entry.lastHeardMs = nowMs;
        entry.alive = true;
        return entry;
    }

    //bentutzr fürn den bootsrap
    public NeighborEntry addKnown(NodeId neighbor, long nowMs) {
        return neighbors.computeIfAbsent(neighbor, n -> new NeighborEntry(n, nowMs, false));
    }
     //bisschen dummm ruft andere methode auf
    public void markHeard(NodeId neighbor, long nowMs) {
        upsertAlive(neighbor, nowMs);
    }


    //kopie zum iterien
    public Collection<NeighborEntry> snapshot() {
        return new ArrayList<>(neighbors.values());
    }

    //guckt ob zeit abegalufen ist
    public List<NodeId> detectDeaths(long nowMs) {
        List<NodeId> diedNow = new ArrayList<>();
        for (NeighborEntry ne : neighbors.values()) {
            if (ne.alive && (nowMs - ne.lastHeardMs) > DEAD_AFTER_MS) {
                ne.alive = false;
                diedNow.add(ne.id);
            }
        }
        return diedNow;
    }

    //setzt tot
    public void markGoodbye(NodeId neighbor) {
        NeighborEntry ne = neighbors.get(neighbor);
        if (ne != null) {
            ne.alive = false;
        }
    }

    public static final class NeighborEntry {
        public final NodeId id;
        volatile long lastHeardMs;
        volatile boolean alive;
        public boolean isAlive() {
            return alive;
        }

        public void markAlive(long now) {
            this.lastHeardMs = now;
            this.alive = true;
        }

        public void markDead() {
            this.alive = false;
        }

        NeighborEntry(NodeId id, long lastHeardMs, boolean alive) {
            this.id = id;
            this.lastHeardMs = lastHeardMs;
            this.alive = alive;
        }

    }

}

