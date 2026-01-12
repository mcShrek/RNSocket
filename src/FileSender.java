import Connect.Protokoll;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
//sendet dateien
public class FileSender {

    private final DatagramSocket socket;


    private final int localIpInt;
    private final int localPort;




    private static final int CHUNK_SIZE = Protokoll.CHUNK_SIZE;     // 1500 - 20 - 8 - 62 - 150
    private static final int FRAME_CHUNKS = Protokoll.FRAME_CHUNKS;      // 128 chunks pro frame
    private static final int ACK_TIMEOUT_MS = Protokoll.ACK_TIMEOUT_MS;
    private static final int MAX_RETRIES = Protokoll.MAX_RETRIES;

    public FileSender(DatagramSocket socket,
                      int localIpInt, int localPort) {
        this.socket = socket;
        this.localIpInt = localIpInt;
        this.localPort = localPort;

    }


    public void sendFileInfoOnce(InetAddress nextHopAddr, int nextHopPort,
                                 int finalDestIpInt, int finalDestPort,
                                 long seq, String filename,int totalChunks) throws IOException {

        byte[] payload = filename.getBytes(StandardCharsets.UTF_8);

        Packet pkt = buildPacket(MessageType.FILE_INFO, seq, payload,
                 0, totalChunks, finalDestIpInt, finalDestPort);

        byte[] bytes = pkt.toBytes();
        socket.send(new DatagramPacket(bytes, bytes.length, nextHopAddr, nextHopPort));
    }

//    public void sendMissingChunks(InetAddress nextHopAddr, int nextHopPort,
//                                  int finalDestIpInt, int finalDestPort,
//                                  long frameSeq,
//                                  List<byte[]> chunks,
//                                  int totalChunks,
//                                  Set<Integer> chunkIds) throws IOException {
//        int sent = 0;
//        for (int chunkId : chunkIds) {
//            byte[] chunkPayload = chunks.get(chunkId);
//
//            Packet chunkPkt = buildPacket(MessageType.FILE_CHUNK, frameSeq, chunkPayload,
//                    chunkId, totalChunks, finalDestIpInt,finalDestPort);
//
//            byte[] bytes = chunkPkt.toBytes();
//            socket.send(new DatagramPacket(bytes, bytes.length, nextHopAddr, nextHopPort));
//
//            sent++;
//            if (sent % 16 == 0) { // alle 16 Pakete kurz pausieren
//                try { Thread.sleep(1); } catch (InterruptedException ignored) {}
//            }
//        }
//    }

    public void sendMissingChunks(InetAddress nextHopAddr, int nextHopPort,
                                  int finalDestIpInt, int finalDestPort,
                                  long fileSeq,
                                  List<byte[]> chunks,
                                  int totalChunks,
                                  Set<Integer> chunkIds) throws IOException {
        //int sent = 0;

        for (int chunkId : chunkIds) {
            byte[] chunkPayload = chunks.get(chunkId);

            Packet chunkPkt = buildPacket(MessageType.FILE_CHUNK, fileSeq, chunkPayload,
                    chunkId, totalChunks, finalDestIpInt, finalDestPort);

            byte[] bytes = chunkPkt.toBytes();
            socket.send(new DatagramPacket(bytes, bytes.length, nextHopAddr, nextHopPort));

            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

//            sent++;
//            if ((sent % 16) == 0) {                 // alle 16 Pakete kurz "Luft holen"
//                try {
//                    Thread.sleep(1);
//                }            // 1 ms
//                catch (InterruptedException ignored) {
//                }
//            }
        }
    }

    private Packet buildPacket(MessageType type, long seq, byte[] payload, long chunkId, long chunkLength, int destIpInt, int destPort) {
        Header h = new Header();
        h.setType(type);
        h.setSequenceNumber(seq);

        h.setDestinationIp(destIpInt);
        h.setSourceIp(localIpInt);

        h.setDestinationPort(destPort);
        h.setSourcePort(localPort);

        h.setPayloadLength(payload.length);
        h.setChunkId(chunkId);
        h.setChunkLength(chunkLength);

        h.setTtl(Protokoll.TTL_DEFAULT);
        h.setChecksum(Header.computeChecksum(payload));

        return new Packet(h, payload);
    }


}

