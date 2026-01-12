package Connect;

public class Protokoll {
    private Protokoll(){}

    public static final int MTU = 1500;
    public static final int IP_HEADER = 20;
    public static final int UDP_HEADER = 8;
    public static final int PROTOKOLL_HEADER = 62;
    public static final int VPN_BUFFER = 150;


    public static final int CHUNK_SIZE = MTU - IP_HEADER - UDP_HEADER - PROTOKOLL_HEADER - VPN_BUFFER;


    public static final int FRAME_SIZE = 128;
    public static final long FRAME_TIMEOUT_MS = 6000;//test

    public static final int FRAME_CHUNKS = 128;

    public static final int ACK_TIMEOUT_MS = 3000; //zum Testen ab un zu auf 8 vlt in lange reihe mehr standard 3
    public static final int MAX_RETRIES = 3;

    public static final short TTL_DEFAULT = 64;
    public static final long HEARTBEAT_INTERVAL_MS = 3000;
    public static final long ROUTING_UPDATE_INTERVAL_MS = 10_000; // z.B. alle 10s
    public static final long POISON_DELETE_AFTER_MS = HEARTBEAT_INTERVAL_MS * 3L; // Spec: hb * 3
    public static final long NEIGHBOR_DEAD_AFTER_MS = (HEARTBEAT_INTERVAL_MS * 2) + 1000;

    public static final long SEEN_TTL_MS = 800_000;         //  Minuten bsi file info gelöscht wird
    public static final long SEEN_PURGE_INTERVAL_MS = 30_000; // alle 30s maximal einmal bereinigen

}
