import java.util.Objects;
//eindeutiger schlüssel
public class SenderSeqId {

    public final int srcIp;
    public final int srcPort;
    public final long seq;

    public SenderSeqId(int srcIp, int srcPort, long seq ){
        this.srcIp = srcIp;
        this.srcPort = srcPort;
        this.seq = seq;

    }

    @Override
    public boolean equals(Object obj) {
       if (this == obj) return true;
       if(!(obj instanceof SenderSeqId)) return false;
       SenderSeqId other = (SenderSeqId) obj;
        return srcIp == other.srcIp && srcPort == other.srcPort && seq == other.seq;


    }
    @Override public int hashCode() {
        return Objects.hash(srcIp, srcPort, seq);
    }
}
