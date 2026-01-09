//package Routing;
//import java.util.List;
//
//public class RoutingTableTestMain {
//    public static void main(String[] args) {
//        long now = System.currentTimeMillis();
//
//        NodeId self = new NodeId(1, 1000);
//        NodeId n1   = new NodeId(2, 2000); // neighbor 1
//        NodeId n2   = new NodeId(3, 3000); // neighbor 2
//
//        NodeId destA = new NodeId(10, 9999);
//        NodeId destB = new NodeId(11, 9999);
//
//        RoutingTable rt = new RoutingTable(self, now);
//
//        System.out.println("== initial table ==");
//        print(rt);
//
//        // 1) Neighbor n1 says: "I can reach destA with distance 0"
//        // => we should store destA via n1 with distance 1
//        boolean changed1 = rt.applyRoutingUpdate(
//                n1,
//                List.of(new RoutingTable.ReceivedRoute(destA, 0)),
//                now + 10
//        );
//        System.out.println("\n== after update from n1 (destA dist0) changed=" + changed1 + " ==");
//        print(rt);
//
//        // 2) Neighbor n2 says: "I can reach destA with distance 0" too
//        // => our current is dist 1 via n1, newDist also 1 via n2 -> not strictly better, ignore
//        boolean changed2 = rt.applyRoutingUpdate(
//                n2,
//                List.of(new RoutingTable.ReceivedRoute(destA, 0)),
//                now + 20
//        );
//        System.out.println("\n== after update from n2 (destA dist0) changed=" + changed2 + " ==");
//        print(rt);
//
//        // 3) Neighbor n2 says: "I can reach destB with distance 2"
//        // => we store destB via n2 with distance 3
//        boolean changed3 = rt.applyRoutingUpdate(
//                n2,
//                List.of(new RoutingTable.ReceivedRoute(destB, 2)),
//                now + 30
//        );
//        System.out.println("\n== after update from n2 (destB dist2) changed=" + changed3 + " ==");
//        print(rt);
//
//        // 4) Better path: n1 says destB distance 0 => we should update destB to distance 1 via n1
//        boolean changed4 = rt.applyRoutingUpdate(
//                n1,
//                List.of(new RoutingTable.ReceivedRoute(destB, 0)),
//                now + 40
//        );
//        System.out.println("\n== after better path from n1 (destB dist0) changed=" + changed4 + " ==");
//        print(rt);
//
//        // 5) Poison reverse test: n1 poisons destB => we should set distance=255 for destB via n1
//        boolean changed5 = rt.applyRoutingUpdate(
//                n1,
//                List.of(new RoutingTable.ReceivedRoute(destB, RoutingTable.INF)),
//                now + 50
//        );
//        System.out.println("\n== after poison from n1 (destB INF) changed=" + changed5 + " ==");
//        print(rt);
//
//        // 6) Neighbor death: remove all routes via n1
//        boolean changed6 = rt.removeRoutesVia(n1);
//        System.out.println("\n== after n1 died (remove routes via n1) changed=" + changed6 + " ==");
//        print(rt);
//
//        // 7) Split horizon / poison reverse on export:
//        // When exporting for n2, any route whose nextHop == n2 is advertised as INF.
//        System.out.println("\n== export for neighbor n2 ==");
//        for (RoutingTable.ReceivedRoute r : rt.exportForNeighbor(n2)) {
//            System.out.println("advertise dest=" + r.destination + " dist=" + r.distance);
//        }
//    }
//
//    private static void print(RoutingTable rt) {
//        for (RoutingEntry e : rt.snapshot()) {
//            System.out.println("dest=" + e.destination() +
//                    " nextHop=" + e.nextHop() +
//                    " dist=" + e.distance());
//        }
//    }
//}
//
