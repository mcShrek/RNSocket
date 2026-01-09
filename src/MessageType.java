public enum MessageType {

    ACK((byte) 0x01),
    NO_ACK((byte) 0x02),
    HELLO((byte) 0x03),
    GOODBYE((byte) 0x04),
    MSG((byte) 0x05),
    FILE_CHUNK((byte) 0x06),
    FILE_INFO((byte) 0x07),
    HEARTBEAT((byte) 0x08),
    ROUTING_UPDATE((byte) 0x09);

    private final byte code;

    MessageType(byte code) {
        this.code = code;
    }

    public byte getCode() {
        return code;
    }

    //welcherTyp
    public static MessageType fromCode(byte code) {
        for (MessageType t : values()) {
            if (t.code == code) return t;
        }
        throw new IllegalArgumentException("Unknown MessageType: " + code);
    }



}
