import Connect.Protokoll;
import Routing.NeighborTable;
import Routing.NodeId;
import Routing.RoutingTable;
import Routing.RoutingUpdateCodec;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;


import static java.lang.System.currentTimeMillis;

public class Peer {

    private final DatagramSocket socket;
    private final NodeId self;

    private final SequenceNumberG seqGen = new SequenceNumberG();

    private final NeighborTable neighborTable = new NeighborTable();
    private final RoutingTable routingTable;

    private final FileReceiver fileReceiver;
    //private final FileSender fileSender;

    // Duplikaterkennung für MSG / FILE_INFO
    private final ExpiringSet<SenderSeqId> seenMsgs = new ExpiringSet<>(Protokoll.SEEN_TTL_MS, Protokoll.SEEN_PURGE_INTERVAL_MS);


    private final HeartbeatService heartbeatService;

    private final PendingEvents pending = new PendingEvents();



    public Peer(int listenPort, InetAddress selfIpOrNull, InetAddress connectIpOrNull) throws Exception {


        InetAddress bindIp;
        if (selfIpOrNull != null) {
            bindIp = selfIpOrNull;
        } /*else if (connectIpOrNull != null) {
            bindIp = determineSelfIpToReach(connectIpOrNull);
        } */ else {
            bindIp = InetAddress.getByName("127.0.0.1"); //loopback
        }


        this.socket = new DatagramSocket(new InetSocketAddress(bindIp, listenPort));


        int selfIpInt = Header.inetAddressToInt(socket.getLocalAddress());
        int selfPort  = socket.getLocalPort(); // wichtig, falls listenPort=0 wäre
        this.self = new NodeId(selfIpInt, selfPort);

        this.routingTable = new RoutingTable(self, System.currentTimeMillis());
        this.fileReceiver = new FileReceiver(socket);

        this.heartbeatService = new HeartbeatService(
                socket, self, neighborTable, routingTable, seqGen, this::broadcastRoutingUpdate
        );
        this.heartbeatService.start();

        System.out.println("Peer start: " + self + " (selfIp=" + bindIp.getHostAddress() + ")");
    }




    //join
    public void connectToNeighbor(InetAddress neighborIp, int neighborPort) throws IOException {
        int ipInt = Header.inetAddressToInt(neighborIp);
        NodeId neighbor = new NodeId(ipInt, neighborPort);

        neighborTable.upsertAlive(neighbor, currentTimeMillis());
        sendHelloTo(neighbor);
        broadcastRoutingUpdate();

        System.out.println("Connect to neighbor: " + neighbor);
    }

    // Receiver Loop


    public void startReceiverLoop() {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[65507];
            System.out.println("Receiver loop running");

            while (!socket.isClosed()) {
                try {
                    DatagramPacket dp = new DatagramPacket(buf, buf.length);
                    socket.receive(dp);
                    Packet received = Packet.fromBytes(dp.getData(), dp.getLength());
                    Header h = received.getHeader();
                    MessageType type = h.getType();
                    boolean forMe = (h.getDestinationIp() == self.ip() && h.getDestinationPort() == self.port());

                    long now = System.currentTimeMillis();
                    int hopIpInt = Header.inetAddressToInt(dp.getAddress());
                    int hopPort = dp.getPort();
                    NodeId hopNeighbor = new NodeId(hopIpInt, hopPort);

                    neighborTable.upsertAlive(hopNeighbor, now);


                    boolean changedDirect = routingTable.ensureDirectNeighborRoute(hopNeighbor, now);
                    if (changedDirect) {
                        broadcastRoutingUpdate();
                    }

                    if (type == MessageType.ACK) {
                        if (forMe) {
                            pending.onEvent(h.getSequenceNumber(), AckEvent.ack());
                        } else {
                            forwardIfNeeded(received);
                        }
                        fileReceiver.tick();
                        continue;
                    }

                    if (type == MessageType.NO_ACK) {
                        if (forMe) {
                            NoAckFiles.Parsed parsed = NoAckFiles.parse(received.getPayload());
                            pending.onEvent(h.getSequenceNumber(), AckEvent.noAck(parsed.missingChunkIds));
                        } else {
                            forwardIfNeeded(received);
                        }
                        fileReceiver.tick();
                        continue;
                    }



                    if (!forMe) {
                        forwardIfNeeded(received);
                        fileReceiver.tick();
                        continue;
                    }

                    if (type == MessageType.MSG) {
                        handleMsg(received, dp);
                    }


                    else if (type == MessageType.FILE_INFO || type == MessageType.FILE_CHUNK) {
                        fileReceiver.handle(received, dp);
                    } else if (type == MessageType.ROUTING_UPDATE) {
                        handleRoutingUpdate(received);
                    } else if (type == MessageType.HELLO) {
                        handleHello(received);
                    } else if (type == MessageType.GOODBYE) {
                        handleGoodbye(received);
                    } else if (type == MessageType.HEARTBEAT) {
                        // nichts zu tun, mach ich oben schon
                    } else {
                        System.out.println("Received undefined packet: " + type);
                    }

                    fileReceiver.tick();
                } catch (SocketTimeoutException timeout) {
                    fileReceiver.tick();
                    continue;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }, "peer-receiver");

        t.setDaemon(true);
        t.start();
    }

    // Console Loop

    public void runConsoleLoop() {
        Scanner sc = new Scanner(System.in);

        System.out.println("""
                Commands:
                  hello
                  rt
                  msg (destIp) (destPort) (text)
                  file (destIp) (destPort) (path)
                  exit
                """);

        while (true) {
            String line = sc.nextLine().trim();
            if (line.equalsIgnoreCase("exit")) break;

            try {
                if (line.equalsIgnoreCase("hello")) {

                    for (NeighborTable.NeighborEntry ne : neighborTable.snapshot()) {
                        if (!ne.isAlive()) continue;
                        sendHelloTo(ne.id);
                    }
                } else if (line.equalsIgnoreCase("rt")) {
                    printRoutingTable();
                } else if (line.startsWith("msg ")) {
                    // msg <destIp> <destPort> <text...>
                    String[] parts = line.split(" ", 4);
                    if (parts.length < 4) {
                        System.out.println("Usage: msg (destIp) (destPort) (text)");
                        continue;
                    }
                    InetAddress destIp = InetAddress.getByName(parts[1]);
                    int destPort = Integer.parseInt(parts[2]);
                    String text = parts[3];

                    NodeId destNode = new NodeId(Header.inetAddressToInt(destIp), destPort);

                    InetSocketAddress nextHop = resolveNextHop(destNode);
                    if (nextHop == null) {
                        System.out.println("No  known route to dest, check RoutingTable=" + destNode);
                        continue;
                    }

                    sendMsgReliable(nextHop.getAddress(), nextHop.getPort(), destIp, destPort, text);

                } else if (line.startsWith("file ")) {

                    String[] parts = line.split(" ", 4);
                    if (parts.length < 4) {
                        System.out.println("Usage: file (destIp) (destPort) (path)");
                        continue;
                    }
                    InetAddress destIp = InetAddress.getByName(parts[1]);
                    int destPort = Integer.parseInt(parts[2]);
                    Path path = Paths.get(parts[3]);

                    NodeId destNode = new NodeId(Header.inetAddressToInt(destIp), destPort);

                    InetSocketAddress nextHop = resolveNextHop(destNode);
                    if (nextHop == null) {
                        System.out.println("No  known route to dest, check RoutingTable=" + destNode);
                        continue;
                    }

                    sendFileReliable(nextHop.getAddress(), nextHop.getPort(), destIp, destPort, path);

                } else {
                    System.out.println("Unknown command.");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }


        try {
            sendGoodbyeToAll();
        } catch (IOException e) {
            e.printStackTrace();
        }

        socket.close();
        heartbeatService.stop();
        System.out.println("Peer stopped.");
    }

    // MSG sending
    private void sendMsgReliable(InetAddress nextHopIp, int nextHopPort,
                                 InetAddress finalDestIp, int finalDestPort,
                                 String text) throws IOException {

        byte[] payload = text.getBytes(StandardCharsets.UTF_8);


        if (payload.length > Protokoll.CHUNK_SIZE) {
            throw new IllegalArgumentException("MSG too large (" + payload.length + " bytes). Max=" + Protokoll.CHUNK_SIZE);
        }

        long seq = seqGen.next();
        int destIpInt = Header.inetAddressToInt(finalDestIp);

        Packet msg = buildPacket(MessageType.MSG, seq, payload, destIpInt, finalDestPort, 0, 0, Protokoll.TTL_DEFAULT);
        byte[] bytes = msg.toBytes();
        DatagramPacket out = new DatagramPacket(bytes, bytes.length, nextHopIp, nextHopPort);

        int attempts = 0;
        while (attempts < Protokoll.MAX_RETRIES) {
            attempts++;

            CompletableFuture<AckEvent> fut = pending.register(seq);
            socket.send(out);

            try {
                AckEvent ev = fut.get(Protokoll.ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (ev.kind == AckEvent.Kind.ACK) {
                    pending.cancel(seq);
                    System.out.println("MSG ACK seq=" + seq);
                    return;
                } else {

                    System.out.println("Message should not get NOAck" + seq);
                }
            } catch (TimeoutException te) {

                System.out.println("MSG timeout seq=" + seq + " (" + attempts + "/" + Protokoll.MAX_RETRIES + ")");
            } catch (Exception e) {
                throw new IOException("MSG wait failed", e);
            }
        }

        pending.cancel(seq);
        System.out.println("MSG give up seq=" + seq);
    }

    private void sendFileReliable(InetAddress nextHopIp, int nextHopPort, InetAddress finalDestIp, int finalDestPort, java.nio.file.Path file) throws IOException {

        byte[] fileBytes = java.nio.file.Files.readAllBytes(file);
        String filename = file.getFileName().toString();

        java.util.List<byte[]> chunks = splitIntoChunks(fileBytes, Protokoll.CHUNK_SIZE);
        int totalChunks = chunks.size();
        int totalFrames = (int) Math.ceil(totalChunks / (double) Protokoll.FRAME_CHUNKS);

        int finalDestIpInt = Header.inetAddressToInt(finalDestIp);

        FileSender fs = new FileSender(socket, self.ip(), self.port());

        System.out.println("Sending file '" + filename + "' bytes=" + fileBytes.length +
                " chunks=" + totalChunks + " frames=" + totalFrames);


        long fileSeq = seqGen.next();


        int attempts = 0;
        while (attempts < Protokoll.MAX_RETRIES) {
            attempts++;


            pending.cancel(fileSeq);
            CompletableFuture<AckEvent> fut = pending.register(fileSeq);

            fs.sendFileInfoOnce(nextHopIp, nextHopPort, finalDestIpInt, finalDestPort,
                    fileSeq, filename, totalChunks);

            try {
                AckEvent ev = fut.get(Protokoll.ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (ev.kind == AckEvent.Kind.ACK) {
                    pending.cancel(fileSeq);
                    System.out.println("FILE_INFO ACK seq=" + fileSeq);
                    break;
                }
            } catch (TimeoutException te) {
                pending.cancel(fileSeq);
                System.out.println("FILE_INFO timeout (attempt " + attempts + "/" + Protokoll.MAX_RETRIES + ")");
            } catch (Exception e) {
                pending.cancel(fileSeq);
                throw new IOException("FILE_INFO wait failed", e);
            }
        }

        if (attempts >= Protokoll.MAX_RETRIES) {
            pending.cancel(fileSeq);
            throw new IOException("FILE_INFO failed (no ACK).");
        }

        for (int frameIndex = 0; frameIndex < totalFrames; frameIndex++) {

            int start = frameIndex * Protokoll.FRAME_CHUNKS;
            int end = Math.min(start + Protokoll.FRAME_CHUNKS, totalChunks);

            java.util.Set<Integer> missing = new LinkedHashSet<>();
            for (int cid = start; cid < end; cid++) missing.add(cid);

            int timeouts = 0;

            while (true) {
                pending.cancel(fileSeq);
                CompletableFuture<AckEvent> fut = pending.register(fileSeq);


                fs.sendMissingChunks(nextHopIp, nextHopPort, finalDestIpInt, finalDestPort,
                        fileSeq, chunks, totalChunks, missing);

                try {
                    AckEvent ev = fut.get(Protokoll.ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);

                    if (ev.kind == AckEvent.Kind.ACK) {
                        pending.cancel(fileSeq);
                        System.out.println("FRAME ACK fileSeq=" + fileSeq + " frameIndex=" + frameIndex);
                        break;
                    }

                    if (ev.kind == AckEvent.Kind.NO_ACK) {
                        missing.clear();
                        missing.addAll(ev.missing);
                        pending.cancel(fileSeq);
                        System.out.println("FRAME NO_ACK fileSeq=" + fileSeq + " frameIndex=" + frameIndex +
                                " missing=" + missing.size());
                        continue;
                    }

                } catch (TimeoutException te) {
                    timeouts++;
                    pending.cancel(fileSeq);
                    System.out.println("FRAME timeout fileSeq=" + fileSeq + " frameIndex=" + frameIndex +
                            " (" + timeouts + "/" + Protokoll.MAX_RETRIES + ")");
                    if (timeouts >= Protokoll.MAX_RETRIES) {
                        throw new IOException("Frame failed after timeouts: fileSeq=" + fileSeq + " frameIndex=" + frameIndex);
                    }
                } catch (Exception e) {
                    pending.cancel(fileSeq);
                    throw new IOException("Frame wait failed", e);
                }
            }
        }

        System.out.println("File transfer done.");
    }


    //  Routing handlers

    private void handleRoutingUpdate(Packet received) {
        try {
            Header h = received.getHeader();
            NodeId sender = new NodeId(h.getSourceIp(), h.getSourcePort());

            List<RoutingTable.ReceivedRoute> routes = RoutingUpdateCodec.decode(received.getPayload());
            boolean changed = routingTable.applyRoutingUpdate(sender, routes, currentTimeMillis());

            if (changed) {
                broadcastRoutingUpdate();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    //als alive eingetragen
    private void handleHello(Packet received) throws IOException {
        Header h = received.getHeader();
        NodeId sender = new NodeId(h.getSourceIp(), h.getSourcePort());


        System.out.println("Hello received: " + sender);
        long now = currentTimeMillis();
        neighborTable.upsertAlive(sender, now);

        // direkte route zu neighbor
        boolean changed = routingTable.ensureDirectNeighborRoute(sender, now);
        if (changed) broadcastRoutingUpdate();
    }

    private void handleGoodbye(Packet received) {
        Header h = received.getHeader();
        NodeId sender = new NodeId(h.getSourceIp(), h.getSourcePort());

        neighborTable.markGoodbye(sender);
        routingTable.poisonRoutesVia(sender, currentTimeMillis());
        broadcastRoutingUpdate();
    }

    //  Broadcast routing update

    private void broadcastRoutingUpdate() {
        for (NeighborTable.NeighborEntry ne : neighborTable.snapshot()) {
            if (!ne.isAlive()) continue;
            try {
                sendRoutingUpdateToNeighbor(ne.id);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void sendRoutingUpdateToNeighbor(NodeId neighbor) throws IOException {
        List<RoutingTable.ReceivedRoute> advertised = routingTable.exportForNeighbor(neighbor);
        byte[] payload = RoutingUpdateCodec.encode(advertised);

        Packet pkt = buildPacket(MessageType.ROUTING_UPDATE, seqGen.next(), payload,
                neighbor.ip(), neighbor.port(), 0, 0, Protokoll.TTL_DEFAULT);

        byte[] bytes = pkt.toBytes();

        InetAddress addr = Header.intToInetAddress(neighbor.ip());
        DatagramPacket udp = new DatagramPacket(bytes, bytes.length, addr, neighbor.port());
        socket.send(udp);
    }

    // HELLO / GOODBYE sending

    private void sendHelloTo(NodeId neighbor) throws IOException {
        byte[] payload = new byte[0];

        Packet pkt = buildPacket(MessageType.HELLO, seqGen.next(), payload,
                neighbor.ip(), neighbor.port(), 0, 0, Protokoll.TTL_DEFAULT);

        byte[] bytes = pkt.toBytes();

        InetAddress addr = Header.intToInetAddress(neighbor.ip());
        DatagramPacket udp = new DatagramPacket(bytes, bytes.length, addr, neighbor.port());
        socket.send(udp);

        System.out.println("Sent HELLO to " + neighbor);
    }

    private void sendGoodbyeToAll() throws IOException {
        byte[] payload = new byte[0];
        for (NeighborTable.NeighborEntry ne : neighborTable.snapshot()) {
            if (!ne.isAlive()) continue;

            Packet pkt = buildPacket(MessageType.GOODBYE, seqGen.next(), payload,
                    ne.id.ip(), ne.id.port(), 0, 0, Protokoll.TTL_DEFAULT);

            byte[] bytes = pkt.toBytes();
            InetAddress addr = Header.intToInetAddress(ne.id.ip());
            DatagramPacket udp = new DatagramPacket(bytes, bytes.length, addr, ne.id.port());
            socket.send(udp);
        }
    }

    //  Forwarding

    private void forwardIfNeeded(Packet received) throws IOException {
        Header h = received.getHeader();

        MessageType t = received.getHeader().getType();
        if (t == MessageType.ROUTING_UPDATE || t == MessageType.HELLO
                || t == MessageType.GOODBYE || t == MessageType.HEARTBEAT) {
            // Routing/Discovery/Keepalive wird nicht multi-hop weitergeleitet
            return;
        }

        // TTL runter
        int ttl = h.getTtl();
        if (ttl <= 0) return;
        int newTtl = ttl - 1;
        if (newTtl <= 0) {
            System.out.println("DROP TTL->0 dest=" + h.getDestinationIp() + ":" + h.getDestinationPort());
            return;
        }

        NodeId dest = new NodeId(h.getDestinationIp(), h.getDestinationPort());
        var opt = routingTable.get(dest);
        if (opt.isEmpty() || opt.get().distance() >= RoutingTable.INF) {
            System.out.println("DROP no-route dest=" + dest);
            return;
        }

        NodeId nextHop = opt.get().nextHop();
        InetAddress nhAddr = Header.intToInetAddress(nextHop.ip());


        Packet fwd = buildPacket(h.getType(), h.getSequenceNumber(), received.getPayload(), h.getDestinationIp(), h.getDestinationPort(), h.getChunkId(), h.getChunkLength(), (short) newTtl
        );

        fwd.getHeader().setSourceIp(h.getSourceIp());
        fwd.getHeader().setSourcePort(h.getSourcePort());
        fwd.getHeader().setChecksum(h.getChecksum());

        byte[] bytes = fwd.toBytes();
        DatagramPacket udp = new DatagramPacket(bytes, bytes.length, nhAddr, nextHop.port());
        socket.send(udp);


    }

    //  Helpers

    private Packet buildPacket(MessageType type, long seq, byte[] payload,
                               int destIpInt, int destPort,
                               long chunkId, long chunkLength, short ttl) {

        Header h = new Header();
        h.setType(type);
        h.setSequenceNumber(seq);

        h.setDestinationIp(destIpInt);
        h.setDestinationPort(destPort);

        h.setSourceIp(self.ip());
        h.setSourcePort(self.port());

        h.setPayloadLength(payload.length);
        h.setChunkId(chunkId);
        h.setChunkLength(chunkLength);

        h.setTtl(ttl);
        h.setChecksum(Header.computeChecksum(payload));

        return new Packet(h, payload);
    }

    private void handleMsg(Packet received, DatagramPacket udp) throws IOException {


        Header h = received.getHeader();
        SenderSeqId id = new SenderSeqId(h.getSourceIp(), h.getSourcePort(), h.getSequenceNumber());

        long now = System.currentTimeMillis();
        boolean first = seenMsgs.markIfNew(id, now);
        if (first) {
            String msg = new String(received.getPayload(), StandardCharsets.UTF_8);
            System.out.println("MSG from " + Header.ipToString(h.getSourceIp()) + ":" + h.getSourcePort() +
                    " seq=" + h.getSequenceNumber() + " text=" + msg);
        } else {
            System.out.println("Duplicate MSG ignored seq=" + h.getSequenceNumber());
        }

        sendAckFor(h, udp.getAddress(), udp.getPort());
    }

    private void sendAckFor(Header original, InetAddress targetAddr, int targetPort) throws IOException {
        byte[] empty = new byte[0];

        Header ack = new Header();
        ack.setType(MessageType.ACK);
        ack.setSequenceNumber(original.getSequenceNumber());

        ack.setDestinationIp(original.getSourceIp());
        ack.setDestinationPort(original.getSourcePort());

        ack.setSourceIp(original.getDestinationIp());
        ack.setSourcePort(original.getDestinationPort());

        ack.setPayloadLength(0);
        ack.setChunkId(0);
        ack.setChunkLength(0);
        ack.setTtl((Protokoll.TTL_DEFAULT));
        ack.setChecksum(Header.computeChecksum(empty));

        Packet ackPkt = new Packet(ack, empty);
        byte[] bytes = ackPkt.toBytes();

        DatagramPacket out = new DatagramPacket(bytes, bytes.length, targetAddr, targetPort);
        socket.send(out);
    }


    private void printRoutingTable() {
        System.out.println("=== RoutingTable @ " + self + " ===");
        for (var e : routingTable.snapshot()) {
            System.out.println("dest=" + e.destination() + " nextHop=" + e.nextHop() + " dist=" + e.distance());
        }
    }
//    private static InetAddress determineSelfIpToReach(InetAddress remote) throws IOException {
//        try (DatagramSocket tmp = new DatagramSocket()) {
//            tmp.connect(remote, 9); // Port egal, es wird nichts gesendet
//            return tmp.getLocalAddress();
//        }
//    }
    private InetSocketAddress resolveNextHop(NodeId finalDest) throws UnknownHostException {

        if (finalDest.ip() == self.ip() && finalDest.port() == self.port()) {
            return null;
        }

        var opt = routingTable.get(finalDest);
        if (opt.isEmpty() || opt.get().distance() >= RoutingTable.INF) {
            return null;
        }

        NodeId nh = opt.get().nextHop();
        InetAddress nhAddr = Header.intToInetAddress(nh.ip());
        return new InetSocketAddress(nhAddr, nh.port());
    }

    private static List<byte[]> splitIntoChunks(byte[] data, int chunkSize) {
        List<byte[]> chunks = new ArrayList<>();
        for (int i = 0; i < data.length; i += chunkSize) {
            int end = Math.min(data.length, i + chunkSize);
            chunks.add(Arrays.copyOfRange(data, i, end));
        }
        return chunks;
    }

public static void main(String[] args) throws Exception {
    int listenPort = Integer.parseInt(args[0]);
    InetAddress selfIp = InetAddress.getByName(args[1]);

    InetAddress bootstrapIp = null;
    int bootstrapPort = -1;
    if (args.length >= 4) {
        bootstrapIp = InetAddress.getByName(args[2]);
        bootstrapPort = Integer.parseInt(args[3]);
    }

    Peer peer = new Peer(listenPort, selfIp, bootstrapIp);
    peer.startReceiverLoop();

    if (bootstrapIp != null) peer.connectToNeighbor(bootstrapIp, bootstrapPort);
    peer.runConsoleLoop();
}

}

