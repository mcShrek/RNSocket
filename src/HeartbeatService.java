import Connect.Protokoll;
import Routing.NeighborTable;
import Routing.NodeId;
import Routing.RoutingTable;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.List;


//handlen des heartbeats
public class HeartbeatService {

    private final DatagramSocket socket;
    private final NodeId self;
    private final NeighborTable neighborTable;
    private final RoutingTable routingTable;
    private final SequenceNumberG seqGen;
    private static final long ROUTING_UPDATE_INTERVAL_MS = Protokoll.ROUTING_UPDATE_INTERVAL_MS;
    private static final long POISON_PURGE_AFTER_MS = Protokoll.POISON_DELETE_AFTER_MS;
    private long lastRoutingUpdateMs =0;

    private final Runnable onRoutingChanged;

    private volatile boolean running = false;
    private Thread thread;

    public HeartbeatService(DatagramSocket socket, NodeId self, NeighborTable neighborTable, RoutingTable routingTable, SequenceNumberG seqGen, Runnable onRoutingChanged) {
        this.socket = socket;
        this.self = self;
        this.neighborTable = neighborTable;
        this.routingTable = routingTable;
        this.seqGen = seqGen;
        this.onRoutingChanged = onRoutingChanged;
    }

    public void start() {
        if (running) return;
        running = true;

        thread = new Thread(() -> {
            while (running && !socket.isClosed()) {
                try {
                    sendHeartbeats();


                    long now = System.currentTimeMillis();

                    handleDeaths(now);

                    boolean purged = routingTable.purgeExpiredPoisoned(now, POISON_PURGE_AFTER_MS);
                    if (purged) onRoutingChanged.run();


                    if (now - lastRoutingUpdateMs >= ROUTING_UPDATE_INTERVAL_MS) {
                        lastRoutingUpdateMs = now;
                        onRoutingChanged.run();
                    }

                    Thread.sleep(Protokoll.HEARTBEAT_INTERVAL_MS);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }, "heartbeat-service");

        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
    }

    private void sendHeartbeats() throws IOException {
        for (NeighborTable.NeighborEntry ne : neighborTable.snapshot()) {
            if (!ne.isAlive()) continue;

            byte[] payload = new byte[0];

            Header h = new Header();
            h.setType(MessageType.HEARTBEAT);
            h.setSequenceNumber(seqGen.next());
            h.setDestinationIp(ne.id.ip());
            h.setDestinationPort(ne.id.port());
            h.setSourceIp(self.ip());
            h.setSourcePort(self.port());
            h.setPayloadLength(0);
            h.setChunkId(0);
            h.setChunkLength(0);
            h.setTtl(Protokoll.TTL_DEFAULT);
            h.setChecksum(Header.computeChecksum(payload));

            Packet pkt = new Packet(h, payload);
            byte[] bytes = pkt.toBytes();

            InetAddress addr = Header.intToInetAddress(ne.id.ip());
            DatagramPacket udp = new DatagramPacket(bytes, bytes.length, addr, ne.id.port());
            socket.send(udp);
        }
    }

    private void handleDeaths(long now) {

        List<NodeId> died = neighborTable.detectDeaths(now);

        if (died.isEmpty()) return;

        boolean changed = false;
        for (NodeId dead : died) {
            changed |= routingTable.poisonRoutesVia(dead, now);
        }

        if (changed) onRoutingChanged.run();
    }
}
