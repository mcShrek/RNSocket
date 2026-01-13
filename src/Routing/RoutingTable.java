package Routing;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
//tabelle speichert Next-Hop/Distance und lastUpdatedMs
public class RoutingTable {

    public static final int INF = 255; // Poison Reverse uses max uint8

    private final NodeId self;
    private final Map<NodeId, RoutingEntry> table = new ConcurrentHashMap<>();

    //ich selber
    public RoutingTable(NodeId self, long nowMs) {
        this.self = self;
        table.put(self, new RoutingEntry(self, self, 0, nowMs));
    }

    //public NodeId self() { return self; }

    //gibt Eintraag zurück
    public Optional<RoutingEntry> get(NodeId dest) {
        return Optional.ofNullable(table.get(dest));
    }
    //kopie zum iterien
    public Collection<RoutingEntry> snapshot() {
        return new ArrayList<>(table.values());
    }

//    public boolean contains(NodeId dest) {
//        return table.containsKey(dest);
//    }
    //updates in der tabelle
    public boolean applyRoutingUpdate(NodeId sender, List<ReceivedRoute> received, long nowMs) {
        boolean changed = false;

        for (ReceivedRoute rr : received) {
            NodeId dest = rr.destination;

            if (dest.equals(self)) continue;

            int receivedDist = rr.distance;

            int newDist;
            if (receivedDist >= INF) {
                newDist = INF;
            } else {

                newDist = Math.min(INF, receivedDist + 1);
            }

            RoutingEntry current = table.get(dest);

            if (current == null) {
                //noch keine, dann dazu
                if (newDist < INF) {
                    table.put(dest, new RoutingEntry(dest, sender, newDist, nowMs));
                    changed = true;
                }
                continue;
            }

            int curDist = current.distance();
            NodeId curHop = current.nextHop();


            //gleicher nextHop
            if (curHop.equals(sender)) {

                if (curDist != newDist) {
                    current.update(sender, newDist, nowMs);
                    changed = true;
                } else {
                    // still refresh timestamp
                    current.update(sender, curDist, nowMs);
                }

                continue;
            }

            // bessererPfad
            if (newDist < curDist) {
                current.update(sender, newDist, nowMs);
                changed = true;

            }

        }

        return changed;
    }

    //schaut ob knoten als nächster benutzt wird dann poisened
    public boolean poisonRoutesVia(NodeId neighbor, long nowMs) {
        boolean changed = false;

        for (RoutingEntry re : table.values()) {
            if (re.destination().equals(self)) continue;
            if (re.nextHop().equals(neighbor)) {
                if (re.distance() != INF) {
                    re.update(neighbor, INF, nowMs);
                    changed = true;
                } else {
                    re.update(neighbor, INF, nowMs);
                }
            }
        }

        return changed;
    }

    //routingUpdateErstellen
    public List<ReceivedRoute> exportForNeighbor(NodeId neighbor) {
        List<ReceivedRoute> out = new ArrayList<>();
        for (RoutingEntry re : table.values()) {
            NodeId dest = re.destination();
            if (dest.equals(neighbor)) {
                continue;//fix um NAchbarn sich nicht selber zusenden
            }

//            int advertised = re.nextHop().equals(neighbor) ? INF : re.distance();
//            out.add(new ReceivedRoute(dest, advertised));

            if (re.nextHop().equals(neighbor)) continue;

            out.add(new ReceivedRoute(dest, re.distance()));
        }
        return out;
    }


    public static final class ReceivedRoute {
        public final NodeId destination;
        public final int distance; // 0..255

        public ReceivedRoute(NodeId destination, int distance) {
            this.destination = destination;
            this.distance = distance;
        }
    }

    //entfern abgelaufenden
    public boolean purgeExpiredPoisoned(long nowMs, long purgeAfterMs) {
        int before = table.size();


        table.entrySet().removeIf(e -> {
            NodeId dest = e.getKey();
            RoutingEntry re = e.getValue();

            if (dest.equals(self)) return false;                 // self-route nie löschen
            if (re.distance() != INF) return false;              // nur poisoned
            return (nowMs - re.lastUpdatedMs()) >= purgeAfterMs;
        });

        return table.size() != before;
    }

    //nachbarn verarbeiten
    public boolean ensureDirectNeighborRoute(NodeId neighbor, long nowMs) {
        if (neighbor.equals(self)) return false;

        RoutingEntry cur = table.get(neighbor);
        if (cur == null) {
            table.put(neighbor, new RoutingEntry(neighbor, neighbor, 1, nowMs));
            return true;
        }

        // Wenn Route existiert, aber nicht direkt oder poisoned: reparieren
        if (!cur.nextHop().equals(neighbor) || cur.distance() != 1) {
            cur.update(neighbor, 1, nowMs);
            return true;
        }

        // nur Timestamp auffrischen
        cur.update(neighbor, 1, nowMs);
        return false; // “changed” im Sinne der Tabelle: nein
    }


}
