package Routing;
//eintrag
public final class RoutingEntry {
    private final NodeId destination;
    private volatile NodeId nextHop;   // direct neighbor
    private volatile int distance;     // 0..255 (uint8)
    private volatile long lastUpdatedMs;

    public RoutingEntry(NodeId destination, NodeId nextHop, int distance, long nowMs) {
        this.destination = destination;
        this.nextHop = nextHop;
        this.distance = distance;
        this.lastUpdatedMs = nowMs;
    }

    public NodeId destination() { return destination; }
    public NodeId nextHop() { return nextHop; }
    public int distance() { return distance; }
    public long lastUpdatedMs() { return lastUpdatedMs; }

    public void update(NodeId nextHop, int distance, long nowMs) {
        this.nextHop = nextHop;
        this.distance = distance;
        this.lastUpdatedMs = nowMs;
    }
}

