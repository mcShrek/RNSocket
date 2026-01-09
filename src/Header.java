import Connect.Protokoll;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

//zum speichern der Headerfelder, in bytes und andersherum, valiedieren, sowie Checksumüberprüfung und
//und Ip zu int umwandlung und zurück
public class Header {

    public static final int headerSize = Protokoll.PROTOKOLL_HEADER; //Größe in byte

    private MessageType type;
    private long sequenceNumber;
    private int destinationIp;
    private int sourceIp;
    private int destinationPort;
    private int sourcePort;
    private long payloadLength;

    public MessageType getType() {
        return type;
    }

    public void setType(MessageType type) {
        this.type = type;
    }

    public long getSequenceNumber() {
        return sequenceNumber;
    }

    public void setSequenceNumber(long sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
    }

    public int getDestinationIp() {
        return destinationIp;
    }

    public void setDestinationIp(int destinationIp) {
        this.destinationIp = destinationIp;
    }

    public int getSourceIp() {
        return sourceIp;
    }

    public void setSourceIp(int sourceIp) {
        this.sourceIp = sourceIp;
    }

    public int getDestinationPort() {
        return destinationPort;
    }

    public void setDestinationPort(int destinationPort) {
        this.destinationPort = destinationPort;
    }

    public int getSourcePort() {
        return sourcePort;
    }

    public void setSourcePort(int sourcePort) {
        this.sourcePort = sourcePort;
    }

    public long getPayloadLength() {
        return payloadLength;
    }

    public void setPayloadLength(long payloadLength) {
        this.payloadLength = payloadLength;
    }

    public long getChunkId() {
        return chunkId;
    }

    public void setChunkId(long chunkId) {
        this.chunkId = chunkId;
    }

    public long getChunkLength() {
        return chunkLength;
    }

    public void setChunkLength(long chunkLength) {
        this.chunkLength = chunkLength;
    }

    public short getTtl() {
        return ttl;
    }

    public void setTtl(short ttl) {
        this.ttl = ttl;
    }

    public byte[] getChecksum() {
        return checksum;
    }

    public void setChecksum(byte[] checksum) {
        this.checksum = checksum;
    }

    private long chunkId;
    private long chunkLength;
    private short ttl;
    private byte[] checksum;

    public Header(){
        this.checksum = new byte[32];
    }

    public static int inetAddressToInt(InetAddress address){ //Umwandlung
        byte[] bytes = address.getAddress();
        return ((bytes[0] & 0xFF) << 24) |
                ((bytes[1] & 0xFF) << 16) |
                ((bytes[2] & 0xFF) << 8)  |
                (bytes[3] & 0xFF);
    }
    public static InetAddress intToInetAddress(int ip) throws UnknownHostException {
        byte[] b = new byte[]{
                (byte) ((ip >>> 24) & 0xFF),
                (byte) ((ip >>> 16) & 0xFF),
                (byte) ((ip >>> 8) & 0xFF),
                (byte) (ip & 0xFF)
        };
        return InetAddress.getByAddress(b);
    }

    public byte[] toBytes() {
        ByteBuffer buffer = ByteBuffer.allocate(headerSize).order(ByteOrder.BIG_ENDIAN);
        buffer.put(type.getCode());                        // 1 Byte
        buffer.putInt((int) sequenceNumber);               // 4 Byte
        buffer.putInt(destinationIp);                      // 4 Byte
        buffer.putInt(sourceIp);                           // 4 Byte
        buffer.putShort((short) destinationPort);          // 2 Byte
        buffer.putShort((short) sourcePort);               // 2 Byte
        buffer.putInt((int) payloadLength);                // 4 Byte
        buffer.putInt((int) chunkId);                      // 4 Byte
        buffer.putInt((int) chunkLength);                  // 4 Byte
        buffer.put((byte) ttl);                            // 1 Byte
        buffer.put(checksum);                              // 32 Byte
        return buffer.array();
    }

    public static Header fromBytes(byte[] headerBytes) {
        if (headerBytes.length < headerSize) {
            throw new IllegalArgumentException("Header too short");
        }
        ByteBuffer buffer = ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN);
        Header header = new Header();
        byte typeCode = buffer.get();
        header.type = MessageType.fromCode(typeCode);
        header.sequenceNumber = Integer.toUnsignedLong(buffer.getInt());
        header.destinationIp = buffer.getInt();
        header.sourceIp = buffer.getInt();
        header.destinationPort = Short.toUnsignedInt(buffer.getShort());
        header.sourcePort = Short.toUnsignedInt(buffer.getShort());
        header.payloadLength = Integer.toUnsignedLong(buffer.getInt());
        header.chunkId = Integer.toUnsignedLong(buffer.getInt());
        header.chunkLength = Integer.toUnsignedLong(buffer.getInt());
        header.ttl = (short) (buffer.get() & 0xFF);
        byte[] checksum = new byte[32];
        buffer.get(checksum);
        header.checksum = checksum;
        return header;
    }

    public static byte[] computeChecksum(byte[] payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(payload);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
    public void validate() {
        if (type == null) throw new IllegalArgumentException("Unknown type");

        if (destinationPort < 0 || destinationPort > 65535)
            throw new IllegalArgumentException("dest port out of range: " + destinationPort);

        if (sourcePort < 0 || sourcePort > 65535)
            throw new IllegalArgumentException("src port out of range: " + sourcePort);

        int ttlInt = ttl & 0xFF;
        //eigentlich 64
        if (ttlInt < 0 || ttlInt > 255)
            throw new IllegalArgumentException("ttl out of range: " + ttlInt);

        if (checksum == null || checksum.length != 32)
            throw new IllegalArgumentException("checksum must be 32 bytes");
    }
    public static String ipToString(int ip) throws UnknownHostException {
        return Header.intToInetAddress(ip).getHostAddress();
    }
}
