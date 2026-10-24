import de.evp.helfer.MiniServer;
import java.net.ServerSocket;
import java.util.Map;

public class TestPortConflict {
    static final MiniServer.Handler HANDLER = new MiniServer.Handler() {
        public String get(String p, Map<String,String> q) { return "{}"; }
        public String push(Map<String,String> q, byte[] body) { return "{}"; }
    };
    public static void main(String[] args) throws Exception {
        int port;
        try (ServerSocket owner = new ServerSocket(0, 32, java.net.InetAddress.getByName("127.0.0.1"))) {
            port = owner.getLocalPort();
            MiniServer conflict = new MiniServer(port, "test-key", HANDLER);
            try {
                if (conflict.start()) throw new AssertionError("Competing listener reported ready");
            } finally { conflict.stop(); }
        }
        MiniServer first = new MiniServer(port, "first-key", HANDLER);
        MiniServer second = new MiniServer(port, "second-key", HANDLER);
        try {
            if (!first.start()) throw new AssertionError("Free endpoint not acquired");
            if (second.start()) throw new AssertionError("Second helper reported ready");
        } finally { first.stop(); second.stop(); }
        MiniServer retry = new MiniServer(port, "retry-key", HANDLER);
        try {
            if (!retry.start()) throw new AssertionError("Endpoint not released after shutdown");
        } finally { retry.stop(); }
        System.out.println("PASS: port conflict, exclusive ownership, shutdown and retry");
    }
}
