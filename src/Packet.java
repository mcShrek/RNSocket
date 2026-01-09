import java.security.MessageDigest;


//baut packet aus Header und Payload zusammen
public class Packet {
    private Header header;
    private byte[] payload;

    public Packet(Header header, byte[] payload){
        this.header = header;
        this.payload = payload;
    }
    public Header getHeader(){
        return header;
    }

    public byte[] getPayload() {
        return payload;
    }

    public byte[] toBytes() {

        header.setPayloadLength(payload.length);
        byte[] headerBytes = header.toBytes();
        byte[] bytes = new byte[Header.headerSize + payload.length];
        System.arraycopy(headerBytes, 0, bytes, 0, Header.headerSize);
        System.arraycopy(payload, 0, bytes, Header.headerSize, payload.length);
        return bytes;

    }


    public static Packet fromBytes(byte[] data, int length) {
        if (length < Header.headerSize) {
            System.out.println(Header.headerSize);
            throw new IllegalArgumentException("Data too short for header");
        }
        byte[] headerBytes = new byte[Header.headerSize];
        System.arraycopy(data, 0, headerBytes, 0,Header.headerSize);
        Header header = Header.fromBytes(headerBytes);
        header.validate();

        int payloadLength = (int) header.getPayloadLength();
        if (Header.headerSize + payloadLength > length) {
            throw new IllegalArgumentException("Data too short for payload");
        }

        byte[] payload = new byte[payloadLength];
        System.arraycopy(data, Header.headerSize, payload, 0, payloadLength);

        // Checksum prüfen
        byte[] expectedChecksum = header.getChecksum();
        byte[] actualChecksum = Header.computeChecksum(payload);

        if (!MessageDigest.isEqual(expectedChecksum, actualChecksum)) {
            throw new IllegalArgumentException("Checksum mismatch");
        }


        return new Packet(header, payload);
    }
}
