import Connect.Protokoll;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class FileReceiver {

    private static final int FRAME_SIZE = Protokoll.FRAME_SIZE;
    private static final long FRAME_TIMEOUT_MS = Protokoll.FRAME_TIMEOUT_MS;

    private final DatagramSocket socket;

    // Pro Sender (source ip/port) eigener Transfer-State
    private final Map<SenderKey, TransferState> transfers = new ConcurrentHashMap<>();

    // Duplikaterkennung für FILE_INFO (pro Sender+Seq)
    private final ExpiringSet<SenderSeqId> seenFileInfo =
            new ExpiringSet<>(Protokoll.SEEN_TTL_MS, Protokoll.SEEN_PURGE_INTERVAL_MS);
    private static final long COMPLETED_GRACE_MS = 30_000;

    public FileReceiver(DatagramSocket socket) {
        this.socket = socket;
    }

    //fileinfo oder chunk
    public void handle(Packet pkt, DatagramPacket udp) throws IOException {
        Header h = pkt.getHeader();
        SenderKey sender = new SenderKey(h.getSourceIp(), h.getSourcePort());

        if (h.getType() == MessageType.FILE_INFO) {
            handleFileInfo(pkt, udp, sender);
            return;
        }

        if (h.getType() == MessageType.FILE_CHUNK) {
            handleFileChunk(pkt, udp, sender);
            return;
        }
    }

    // Wird im bin.Server-Loop nach jedem Paket aufgerufen: sendet ggf. NO_ACK für abgelaufene Frames für Resend
//    public void tick() {
//        long now = System.currentTimeMillis();
//        for (TransferState ts : transfers.values()) {
//            try {
//                ts.checkFrameTimeoutsAndRequestMissing(now);
//            } catch (IOException e) {
//                e.printStackTrace();
//            }
//        }
//    }

    public void tick() {
        long now = System.currentTimeMillis();

        for (TransferState ts : transfers.values()) {
            try {
                ts.checkFrameTimeoutsAndRequestMissing(now);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        // Fertige Transfers nach Grace-Period entfernen
        transfers.entrySet().removeIf(e -> e.getValue().shouldPurge(now));
    }

    //duplikatenerkennung
//    private void handleFileInfo(Packet pkt, DatagramPacket udp, SenderKey sender) throws IOException {
//        Header h = pkt.getHeader();
//        int totalChunks = (int) h.getChunkLength();
//
//
//        // Duplikaterkennung: (src ip, src port, seq)
//        SenderSeqId infoId = new SenderSeqId(h.getSourceIp(), h.getSourcePort(), h.getSequenceNumber());
//        boolean first = seenFileInfo.markIfNew(infoId, System.currentTimeMillis());
//
//        String filename = new String(pkt.getPayload(), StandardCharsets.UTF_8);
//
//        TransferState ts = transfers.computeIfAbsent(sender, s -> new TransferState(socket, s));
//        if (first) {
//            ts.startNewFile(filename, totalChunks);
//            System.out.println("FILE_INFO from " + sender + ": " + filename + "totalChunks : " + totalChunks);
//        } else {
//            System.out.println("Duplicate FILE_INFO ignored (but ACK again) seq=" + h.getSequenceNumber());
//        }
//
//        sendAckFor(h, udp.getAddress(), udp.getPort());
//    }

    private void handleFileInfo(Packet pkt, DatagramPacket udp, SenderKey sender) throws IOException {
        Header h = pkt.getHeader();

        long fileSeq = h.getSequenceNumber();
        int totalChunks = (int) h.getChunkLength(); // WICHTIG: totalChunks steckt hier drin

        // Duplikaterkennung: (src ip, src port, fileSeq)
        SenderSeqId infoId = new SenderSeqId(h.getSourceIp(), h.getSourcePort(), fileSeq);
        boolean first = seenFileInfo.markIfNew(infoId, System.currentTimeMillis());

        String filename = new String(pkt.getPayload(), StandardCharsets.UTF_8);

        TransferState ts = transfers.computeIfAbsent(sender, s -> new TransferState(socket, s));

        if (ts.totalChunks != null && !ts.chunkData.isEmpty() && Objects.equals(ts.currentFileSeq, fileSeq)) {
            // Transfer läuft schon → NIEMALS resetten, nur ACK senden
            sendAckFor(h, udp.getAddress(), udp.getPort());
            return;
        }

        if (first) {
            ts.startNewFile(fileSeq, filename, totalChunks); //hier unter
            System.out.println("FILE_INFO from " + Header.ipToString(sender.ip) + ": " + filename + " totalChunks=" + totalChunks);
        } else {
            System.out.println("Duplicate FILE_INFO ignored (but ACK again) fileSeq=" + fileSeq);
        }

        // ACK geht auf die gleiche seq (fileSeq)
        sendAckFor(h, udp.getAddress(), udp.getPort());
    }


    //filechunk verarbeiten
//    private void handleFileChunk(Packet pkt, DatagramPacket udp, SenderKey sender) throws IOException {
//        Header h = pkt.getHeader();
//
//
//
//        TransferState ts = transfers.computeIfAbsent(sender, s -> new TransferState(socket, s));
//
//        long frameSeq = h.getSequenceNumber();
//        int chunkId = (int) h.getChunkId();           // global
//        int totalChunks = (int) h.getChunkLength();   // total chunks in file
//
//
//        ts.onChunk(frameSeq, chunkId, totalChunks, pkt.getPayload(), h, udp.getAddress(), udp.getPort());
//    }

    private void handleFileChunk(Packet pkt, DatagramPacket udp, SenderKey sender) throws IOException {
        Header h = pkt.getHeader();

        long fileSeq = h.getSequenceNumber();          // Datei-ID (immer gleich für alle Chunks dieser Datei)
        int chunkId = (int) h.getChunkId();            // globaler Chunk
        int totalChunks = (int) h.getChunkLength();    // Gesamtanzahl Chunks der Datei



        TransferState ts = transfers.computeIfAbsent(sender, s -> new TransferState(socket, s));

        ts.onChunk(
                fileSeq,
                chunkId,
                totalChunks,
                pkt.getPayload(),
                h,
                udp.getAddress(),
                udp.getPort()
        );
    }


    private void sendAckFor(Header original, InetAddress targetAddr, int targetPort) throws IOException {
        byte[] empty = new byte[0];

        Header ack = new Header();
        ack.setType(MessageType.ACK);
        ack.setSequenceNumber(original.getSequenceNumber());

        ack.setDestinationIp(original.getSourceIp());
        ack.setSourceIp(original.getDestinationIp());
        ack.setDestinationPort(original.getSourcePort());
        ack.setSourcePort(original.getDestinationPort());

        ack.setPayloadLength(0);
        ack.setChunkId(0);
        ack.setChunkLength(0);
        ack.setTtl((short) 64);
        ack.setChecksum(Header.computeChecksum(empty));

        Packet ackPkt = new Packet(ack, empty);
        byte[] bytes = ackPkt.toBytes();

        DatagramPacket out = new DatagramPacket(bytes, bytes.length, targetAddr, targetPort);
        socket.send(out);
    }

//    private void sendNoAckForFrame(Header originalFrameHeader, InetAddress targetAddr, int targetPort,
//                                   long frameSeq, List<Integer> missingGlobalChunkIds) throws IOException {
//
//        byte[] payload = NoAckFiles.build(frameSeq, missingGlobalChunkIds);
//
//        Header noAck = new Header();
//        noAck.setType(MessageType.NO_ACK);
//        noAck.setSequenceNumber(frameSeq);
//
//        noAck.setDestinationIp(originalFrameHeader.getSourceIp());
//        noAck.setSourceIp(originalFrameHeader.getDestinationIp());
//        noAck.setDestinationPort(originalFrameHeader.getSourcePort());
//        noAck.setSourcePort(originalFrameHeader.getDestinationPort());
//
//        noAck.setPayloadLength(payload.length);
//        noAck.setChunkId(0);
//        noAck.setChunkLength(0);
//        noAck.setTtl((short) 64);
//        noAck.setChecksum(Header.computeChecksum(payload));
//
//        Packet pkt = new Packet(noAck, payload);
//        byte[] bytes = pkt.toBytes();
//
//        DatagramPacket out = new DatagramPacket(bytes, bytes.length, targetAddr, targetPort);
//        socket.send(out);
//    }

    private void sendNoAckForFrame(Header originalFrameHeader, InetAddress targetAddr, int targetPort,
                                   long fileSeq, List<Integer> missingGlobalChunkIds) throws IOException {

        byte[] payload = NoAckFiles.build(fileSeq, missingGlobalChunkIds);

        Header noAck = new Header();
        noAck.setType(MessageType.NO_ACK);
        noAck.setSequenceNumber(fileSeq);

        noAck.setDestinationIp(originalFrameHeader.getSourceIp());
        noAck.setSourceIp(originalFrameHeader.getDestinationIp());
        noAck.setDestinationPort(originalFrameHeader.getSourcePort());
        noAck.setSourcePort(originalFrameHeader.getDestinationPort());

        noAck.setPayloadLength(payload.length);
        noAck.setChunkId(0);
        noAck.setChunkLength(0);
        noAck.setTtl((short) 64);
        noAck.setChecksum(Header.computeChecksum(payload));

        Packet pkt = new Packet(noAck, payload);
        byte[] bytes = pkt.toBytes();

        DatagramPacket out = new DatagramPacket(bytes, bytes.length, targetAddr, targetPort);
        socket.send(out);
    }


    // --------------------- Helper Data Types ---------------------

    private static final class SenderKey {
        final int ip;
        final int port;

        SenderKey(int ip, int port) { this.ip = ip; this.port = port; }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SenderKey)) return false;
            SenderKey k = (SenderKey) o;
            return ip == k.ip && port == k.port;
        }

        @Override public int hashCode() {
            return Objects.hash(ip, port);
        }

        @Override public String toString() {
            return ip + ":" + port;
        }
    }

    private final class TransferState {
        private final DatagramSocket sock;
        private final SenderKey sender;

        private String filename = "received.bin";
        private Integer totalChunks = null;

        //test neu
        private boolean completed = false;
        private long completedAtMs = 0;
        private Long currentFileSeq = null;   // welche Datei gerade läuft


        // global chunk store (id -> bytes)
        private final Map<Integer, byte[]> chunkData = new ConcurrentHashMap<>();

        // frameSeq -> state
       // private final Map<Long, FrameState> frames = new ConcurrentHashMap<>();
        // frameIndex -> state
        private final Map<Integer, FrameState> frames = new ConcurrentHashMap<>();

        TransferState(DatagramSocket sock, SenderKey sender) {
            this.sock = sock;
            this.sender = sender;
        }

        //neue datei
//        void startNewFile(String originalFilename, int totalChunks) {
//            // neues File: reset states
//            this.filename = "received_ " + originalFilename;
//            this.totalChunks = totalChunks;
//            this.chunkData.clear();
//            this.frames.clear();
//        }


        void startNewFile(long fileSeq, String originalFilename, int totalChunks) {
            this.currentFileSeq = fileSeq;
            this.completed = false;
            this.completedAtMs = 0;

            this.filename = "received_" + originalFilename;
            this.totalChunks = totalChunks;

            this.chunkData.clear();
            this.frames.clear();
        }



//        void onChunk(long fileSeq, int chunkId, int totalChunks,
//                     byte[] data, Header originalHeader, InetAddress addr, int port) throws IOException {
//
//            if (this.totalChunks == null) this.totalChunks = totalChunks;
//
//            // speichern (falls du Duplikate akzeptieren willst, besser putIfAbsent)
//            chunkData.putIfAbsent(chunkId, data);
//
//            int frameIndex = chunkId / FRAME_SIZE;
//
//            //alt FrameState fs = frames.computeIfAbsent(frameIndex, idx -> new FrameState(frameIndex, totalChunks));
//
//
//            FrameState fs = frames.computeIfAbsent(frameIndex, i -> new FrameState(i, totalChunks));
//
//
//
//            fs.lastSeenHeader = originalHeader;
//            fs.lastUdpAddr = addr;
//            fs.lastUdpPort = port;
//
//            fs.markReceived(chunkId);
//
//            if (fs.isComplete()) {
//                long now = System.currentTimeMillis();
//
//                // ACK bei Complete immer schicken (falls ACK verloren ging und Sender retransmitted)
//                // aber LOG nur einmal
//                if (!fs.acked) {
//
//
//                    fs.acked = true;
//                    System.out.println("FRAME complete -> ACK fileSeq=" + fileSeq + " frameIndex=" + frameIndex);
//                }
//
//                // optional: ACK resend drosseln
//                if (now - fs.lastAckSentMs >= 150) {
//                    sendAckFor(originalHeader, addr, port);
//                    fs.lastAckSentMs = now;
//                }
//
//                frames.remove(frameIndex);
//
//                if (isFileComplete()) {
//                    writeFile();
//                }
//            }
//        }

        boolean shouldPurge(long now) {
            return completed && (now - completedAtMs) > COMPLETED_GRACE_MS;
        }

        //hallo
        void onChunk(long fileSeq, int chunkId, int totalChunks,
                     byte[] data, Header originalHeader, InetAddress addr, int port) throws IOException {

            if (this.totalChunks == null) this.totalChunks = totalChunks;

//            // ⭐ NEU: Ignoriere Chunks, wenn die Datei schon komplett ist!
//            if (isFileComplete()) {
//                // Datei ist schon fertig, ignoriere verspätete/duplizierte Chunks
//                return;
//            }

            if (completed && Objects.equals(currentFileSeq, fileSeq)) {
                sendAckFor(originalHeader, addr, port);
                return;
            }

            // Speichern (putIfAbsent ignoriert Duplikate)
            chunkData.putIfAbsent(chunkId, data);

            int frameIndex = chunkId / FRAME_SIZE;

            // ⭐ NEU: Prüfe ob dieser Frame schon als "completed" markiert wurde
            // Wenn ja, ignoriere weitere Chunks für diesen Frame
            FrameState fs = frames.get(frameIndex);

            if (fs != null && fs.acked) {
                // Frame wurde schon ACK'd, ignoriere verspätete Chunks
                return;
            }

            // Frame noch nicht komplett oder noch nicht in Map
            fs = frames.computeIfAbsent(frameIndex, i -> new FrameState(i, totalChunks));

            fs.lastSeenHeader = originalHeader;
            fs.lastUdpAddr = addr;
            fs.lastUdpPort = port;

            fs.markReceived(chunkId);

            if (fs.isComplete()) {
                long now = System.currentTimeMillis();

                if (!fs.acked) {
                    fs.acked = true;
                    System.out.println("FRAME complete -> ACK fileSeq=" + fileSeq + " frameIndex=" + frameIndex);
                }

                if (now - fs.lastAckSentMs >= 150) {
                    sendAckFor(originalHeader, addr, port);
                    fs.lastAckSentMs = now;
                }

                // ⚠️ NICHT ENTFERNEN! Frame bleibt in Map mit acked=true
                // So können wir Duplikate erkennen (siehe oben)
                // frames.remove(frameIndex);  // ← AUSKOMMENTIEREN!

                if (isFileComplete()) {
                    writeFile();
                }
            }
        }


//        void onChunk(long frameSeq, int chunkId, int totalChunks,
//                     byte[] data, Header originalHeader, InetAddress addr, int port) throws IOException {
//
//            // totalChunks einmal setzen (kommt aus header.chunkLength)
//            if (this.totalChunks == null) this.totalChunks = totalChunks;
//
//            // speichern (Duplikate ok: putIfAbsent)
//            chunkData.put(chunkId, data);
//
//            // FrameState erstellen / holen
//            FrameState fs = frames.computeIfAbsent(frameSeq, s -> new FrameState(frameSeq, totalChunks));
//
//            fs.lastSeenHeader = originalHeader; // für NO_ACK header swap
//            fs.lastUdpAddr = addr;
//            fs.lastUdpPort = port;
//
//            fs.markReceived(chunkId);
//
//            // Wenn Frame komplett: ACK
//            if (fs.isComplete()) {
//                sendAckFor(originalHeader, addr, port);
//                fs.acked = true;
//                System.out.println("FRAME complete -> ACK frameSeq=" + frameSeq);
//
//                // Wenn ganze Datei komplett: schreiben
//                if (isFileComplete()) {
//                    writeFile();
//                }
//            }
//        }

        boolean isFileComplete() {
            if (totalChunks == null) return false;
            for (int i = 0; i < totalChunks; i++) {
                if (!chunkData.containsKey(i)) return false;
            }
            return true;
        }

        void writeFile() throws IOException {
            if (totalChunks == null) return;

            System.out.println("All chunks received. Writing file: " + filename);

            // zusammensetzen in Reihenfolge
            ByteArrayOutputStreamSimple out = new ByteArrayOutputStreamSimple();
            for (int i = 0; i < totalChunks; i++) {
                out.write(chunkData.get(i));
            }

            Files.write(Path.of(filename), out.toByteArray());
            System.out.println("Saved file '" + filename + "' bytes=" + out.size());

            completed = true;
            completedAtMs = System.currentTimeMillis();

            chunkData.clear();
            frames.clear();
            //FileReceiver.this.transfers.remove(sender);
        }

//        void checkFrameTimeoutsAndRequestMissing(long now) throws IOException {
//
//
//            for (FrameState fs : frames.values()) {
//                if (fs.acked) continue;
//
//                if (!fs.hasFrameStart()) {
//                    fs.deadlineMs = now + FRAME_TIMEOUT_MS;
//                    continue;
//                }
//
//                if (now >= fs.deadlineMs) {
//                    List<Integer> missing = fs.computeMissing();
//                    if (!missing.isEmpty() && fs.lastSeenHeader != null && fs.lastUdpAddr != null) {
//                        sendNoAckForFrame(fs.lastSeenHeader, fs.lastUdpAddr, fs.lastUdpPort,
//                                fs.frameSeq, missing);
//                        System.out.println("FRAME timeout -> NO_ACK frameSeq=" + fs.frameSeq +
//                                " missing=" + missing.size());
//
//                        // Deadline nach hinten schieben, damit wir nicht spammen
//                        fs.deadlineMs = now + FRAME_TIMEOUT_MS;
//                    }
//                }
//            }
//        }
void checkFrameTimeoutsAndRequestMissing(long now) throws IOException {
    for (FrameState fs : frames.values()) {



        if (fs.receivedBits.isEmpty()) {            //neu
            fs.deadlineMs = now + FRAME_TIMEOUT_MS;
            continue;
        }
        if (fs.acked) continue;

        if (now >= fs.deadlineMs) {
            List<Integer> missing = fs.computeMissing();
            if (!missing.isEmpty() && fs.lastSeenHeader != null && fs.lastUdpAddr != null) {

                long fileSeq = fs.lastSeenHeader.getSequenceNumber();

                if (++fs.noAckCount > 10) { // neu
                    System.out.println("GIVE UP fileSeq=" + fileSeq + " frameIndex=" + fs.frameIndex);
                    frames.remove(fs.frameIndex);
                    continue;
                }


                sendNoAckForFrame(fs.lastSeenHeader, fs.lastUdpAddr, fs.lastUdpPort,
                        fileSeq, missing);

                System.out.println("FRAME timeout -> NO_ACK fileSeq=" + fileSeq +
                        " frameIndex=" + fs.frameIndex + " missing=" + missing.size());

                fs.deadlineMs = now + FRAME_TIMEOUT_MS;
            }
        }
    }
}

    }
    private static final class FrameState {
        final int frameIndex;
        final int totalChunksInFile;

        volatile long deadlineMs;
        volatile int noAckCount=0;

        final BitSet receivedBits = new BitSet(FRAME_SIZE);

        volatile Header lastSeenHeader;
        volatile InetAddress lastUdpAddr;
        volatile int lastUdpPort;

        volatile boolean acked = false;
        volatile long lastAckSentMs = 0;

        FrameState(int frameIndex, int totalChunksInFile) {
            this.frameIndex = frameIndex;
            this.totalChunksInFile = totalChunksInFile;
            this.deadlineMs = System.currentTimeMillis() + FRAME_TIMEOUT_MS;
        }

        int frameStart() {
            return frameIndex * FRAME_SIZE;
        }

        int expectedChunksInThisFrame() {
            int start = frameStart();
            int remaining = totalChunksInFile - start;
            return Math.min(FRAME_SIZE, Math.max(0, remaining));
        }

        void markReceived(int globalChunkId) {
            int idx = globalChunkId - frameStart();
            if (idx >= 0 && idx < FRAME_SIZE) {
                receivedBits.set(idx);
            }
            deadlineMs = System.currentTimeMillis() + FRAME_TIMEOUT_MS;
        }

        boolean isComplete() {
            int expected = expectedChunksInThisFrame();
            for (int i = 0; i < expected; i++) {
                if (!receivedBits.get(i)) return false;
            }
            return true;
        }

        List<Integer> computeMissing() {
            int expected = expectedChunksInThisFrame();
            int start = frameStart();
            List<Integer> missing = new ArrayList<>();
            for (int i = 0; i < expected; i++) {
                if (!receivedBits.get(i)) missing.add(start + i);
            }
            return missing;
        }
    }

    // Simple ByteArrayOutputStream Ersatz (damit du keine Streams jonglieren musst)
    private static final class ByteArrayOutputStreamSimple {
        private byte[] buf = new byte[0];
        private int size = 0;

        void write(byte[] b) {
            if (b == null) return;
            int newSize = size + b.length;
            buf = Arrays.copyOf(buf, newSize);
            System.arraycopy(b, 0, buf, size, b.length);
            size = newSize;
        }

        byte[] toByteArray() { return Arrays.copyOf(buf, size); }
        int size() { return size; }
    }
}

