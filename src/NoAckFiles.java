import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
//baut payloads für  noAcks
public final class NoAckFiles {

    private NoAckFiles() {}

    public static byte[] build(long seq, List<Integer> missingChunkIds) {
        //hart gecodet
        if (missingChunkIds.size() > 256) {
            throw new IllegalArgumentException("too many Chunks missing");
        }

        ByteBuffer buf = ByteBuffer.allocate(4 + 2 + 4 * missingChunkIds.size()).order(ByteOrder.BIG_ENDIAN);
        buf.putInt((int) seq);
        buf.putShort((short) missingChunkIds.size());
        for (long id : missingChunkIds) {
            buf.putInt((int) id);
        }
        return buf.array();
    }

    public static Parsed parse(byte[] payload) {
        ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        long seq = Integer.toUnsignedLong(buf.getInt());
        int count = Short.toUnsignedInt(buf.getShort());

        List<Integer> missing = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            missing.add(buf.getInt());
        }
        return new Parsed(seq, missing);
    }

    public static final class Parsed {
        public final long seq;
        public final List<Integer> missingChunkIds;

        public Parsed(long seq, List<Integer> missingChunkIds) {
            this.seq = seq;
            this.missingChunkIds = missingChunkIds;
        }
    }
}
