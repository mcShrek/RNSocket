import java.util.List;
import java.util.Objects;
//conatine rfür missing Chunks oder eben keine

public final class AckEvent {
    public enum Kind { ACK, NO_ACK }

    public final Kind kind;
    public final List<Integer> missing; // nur bei NO_ACK,// sonst null

    private AckEvent(Kind kind, List<Integer> missing) {
        this.kind = kind;
        this.missing = missing;
    }

    public static AckEvent ack() {
        return new AckEvent(Kind.ACK, List.of());
    }

     public static AckEvent noAck(List<Integer> missing) {
        Objects.requireNonNull(missing, "missing");
        return new AckEvent(Kind.NO_ACK, List.copyOf(missing));
    }
}
