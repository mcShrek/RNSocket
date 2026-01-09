package Routing;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
//liest rotuing updates
public final class RoutingUpdateCodec {

    private RoutingUpdateCodec() {}

    public static byte[] encode(List<RoutingTable.ReceivedRoute> routes) {
        if (routes.size() > 65535) throw new IllegalArgumentException("Too many routes");

        int entryCount = routes.size();
        int payloadLen = 2 + entryCount * 7;

        ByteBuffer buf = ByteBuffer.allocate(payloadLen).order(ByteOrder.BIG_ENDIAN);
        buf.putShort((short) entryCount);

        for (RoutingTable.ReceivedRoute r : routes) {
            buf.putInt(r.destination.ip());
            buf.putShort((short) r.destination.port());
            buf.put((byte) (r.distance & 0xFF));
        }

        return buf.array();
    }

    public static List<RoutingTable.ReceivedRoute> decode(byte[] payload) {
        ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        if (buf.remaining() < 2) throw new IllegalArgumentException("ROUTING_UPDATE payload too short");

        int entryCount = Short.toUnsignedInt(buf.getShort());
        int expected = 2 + entryCount * 7;
        if (payload.length != expected) {
            throw new IllegalArgumentException("ROUTING_UPDATE length mismatch. expected=" + expected + " got=" + payload.length);
        }

        List<RoutingTable.ReceivedRoute> routes = new ArrayList<>(entryCount);
        for (int i = 0; i < entryCount; i++) {
            int ip = buf.getInt();
            int port = Short.toUnsignedInt(buf.getShort());
            int dist = Byte.toUnsignedInt(buf.get());
            routes.add(new RoutingTable.ReceivedRoute(new NodeId(ip, port), dist));
        }

        return routes;
    }
}

