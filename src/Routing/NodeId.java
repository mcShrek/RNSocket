package Routing;

import java.util.Objects;
//Peer zu node
public final class NodeId {
    private final int ip;   // uint32 stored in int
    private final int port; // uint16 stored in int

    public NodeId(int ip, int port) {
        if (port < 0 || port > 65535) throw new IllegalArgumentException("port is non existing");
        this.ip = ip;
        this.port = port;
    }

    public int ip() { return ip; }
    public int port() { return port; }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NodeId)) return false;
        NodeId nodeId = (NodeId) o;
        return ip == nodeId.ip && port == nodeId.port;
    }

    @Override public int hashCode() {
        return Objects.hash(ip, port);
    }


    //Ip und Port lesbar ausgeben
    @Override public String toString() {
        int i = ip;
        return ((i >>> 24) & 0xFF) + "." + ((i >>> 16) & 0xFF) + "." + ((i >>> 8) & 0xFF) + "." + (i & 0xFF)
                + ":" + port;
    }

}

