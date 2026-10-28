package de.evp.helfer;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import poc.Core;

/**
 * TESTGERÜST – nie ausgeliefert. Betreibt den echten {@link ChannelServer} mit den echten {@link ControlCommands},
 * {@link HttpRequestReader} und {@link SnapshotWriter} der Helper-App auf einem Desktop-JVM (Linux-Build desselben
 * Rust-Kerns über JNI). Ersetzt ist nur das Android-Gerät: Helligkeit und Lautstärke sind eine Nachbildung, deren
 * Zustand der Test abfragen kann; die Identität liegt hier unverschlüsselt in STATE_DIR (kein Keystore am Desktop).
 *
 * Steuerung: ein Befehl je stdin-Zeile, Antwort auf stdout.
 *   open | list | revoke <reg-id-hex> | revoke-all | restart | reset-identity | device | stats |
 *   rogue-on | rogue-off | fail-persist on|off | quit
 * "open" gibt "CODE <8 Ziffern>" aus – der Code geht nur an den steuernden Test.
 * Aufruf: java -Djava.library.path=LIB -cp CLASSES de.evp.helfer.ChannelHarness STATE_DIR
 */
public final class ChannelHarness implements ChannelServer.Host {
    /** Nachgebildetes Gerät: dieselben Befehle, dieselbe Status-JSON wie HelferService. */
    static final class FakeDevice implements ControlCommands.Device {
        int step = 6, max = 15, beats, ends, brights, vols;
        float bright = 0.5f, systemBright = 0.5f;
        boolean overriding;
        @Override public synchronized void bright(float level) { brights++; beats++; overriding = true; bright = Math.max(0.01f, Math.min(1f, level)); }
        @Override public synchronized void vol(float level) { vols++; step = Math.round(Math.max(0f, Math.min(1f, level)) * max); }
        @Override public synchronized void beat() { beats++; }
        @Override public synchronized void end() { ends++; overriding = false; bright = systemBright; }
        @Override public synchronized String status() { return ControlCommands.statusJson("harness", true, false, step, max, bright); }
        synchronized String describe() {
            return String.format(Locale.ROOT, "DEVICE bright=%.4f overriding=%b volStep=%d beats=%d ends=%d brights=%d vols=%d", bright, overriding, step, beats, ends, brights, vols);
        }
    }

    private final File identityFile, registryFile;
    private final SnapshotWriter writer;
    private final FakeDevice device = new FakeDevice();
    private ChannelServer server;
    private Rogue rogue;
    private volatile boolean failPersist;

    ChannelHarness(File stateDir) {
        identityFile = new File(stateDir, "identity.bin");
        registryFile = new File(stateDir, "registrations.bin");
        writer = new SnapshotWriter(this::write);
    }

    @Override public long now() { return System.nanoTime() / 1_000_000; }
    @Override public void event(String line) { out("EVENT " + line); }

    @Override public void persist(ChannelServer from) throws Exception {
        writer.persist(() -> {
            synchronized (ChannelServer.CORE) {
                return ChannelServer.coreOwner == from ? Core.hostExportRegistrations() : null;
            }
        });
    }

    private void write(byte[] snapshot) throws Exception {
        if (failPersist) throw new java.io.IOException("persistence failure (injected)");
        File tmp = new File(registryFile.getPath() + ".tmp");
        Files.write(tmp.toPath(), snapshot);
        Files.move(tmp.toPath(), registryFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static synchronized void out(String line) {
        System.out.println(line);
        System.out.flush();
    }

    private synchronized void stopServer() {
        if (server != null) server.stop();
        if (rogue != null) rogue.close();
        rogue = null;
        synchronized (ChannelServer.CORE) { ChannelServer.coreOwner = null; }
        server = null;
    }

    /**
     * Startet; ist der Port noch belegt, bis zu 3 s mit neuen Instanzen erneut versuchen. Auf dem JDK gibt ein
     * geschlossener Socket, auf dem ein anderer Thread in accept() blockiert, den Port verzögert frei.
     */
    private ChannelServer startServer(ChannelServer first) throws InterruptedException {
        ChannelServer s = first;
        for (int i = 0; i < 30; i++) {
            if (s.start()) return s;
            Thread.sleep(100);
            ChannelServer n = new ChannelServer(this, device);
            synchronized (ChannelServer.CORE) { if (ChannelServer.coreOwner == s) ChannelServer.coreOwner = n; }
            s = n;
        }
        return null;
    }

    private synchronized String startGenuine() throws Exception {
        stopServer();
        byte[] id;
        if (identityFile.exists()) id = Files.readAllBytes(identityFile.toPath());
        else if (registryFile.exists()) return "ERROR_INCONSISTENT";   // dieselbe Regel wie die App
        else { id = Core.identityGenerate(); Files.write(identityFile.toPath(), id); }
        byte[] regs = registryFile.exists() ? Files.readAllBytes(registryFile.toPath()) : new byte[0];
        ChannelServer s = new ChannelServer(this, device);
        String pub;
        try {
            synchronized (ChannelServer.CORE) {
                pub = Core.hostInit(id, regs, now(), true);
                ChannelServer.coreOwner = s;
            }
        } finally {
            Arrays.fill(id, (byte) 0);
        }
        persist(s);
        server = startServer(s);
        return server != null ? "READY " + pub.substring(0, 8) : "PORT_BUSY";
    }

    private synchronized String open() {
        if (server == null) return "NO_SERVER";
        synchronized (ChannelServer.CORE) {
            if (ChannelServer.coreOwner != server) return "NO_SERVER";
            return "CODE " + Core.windowOpen(null, now(), ChannelServer.PAIR_TTL_MS);
        }
    }

    private synchronized String list() {
        if (server == null) return "NO_SERVER";
        byte[] b;
        synchronized (ChannelServer.CORE) { b = Core.hostExportRegistrations(); }
        StringBuilder sb = new StringBuilder("REGS");
        for (int at = 0; at + 57 <= b.length; at += 57) {
            sb.append(' ').append(hex(Arrays.copyOfRange(b, at, at + 16))).append(b[at + 48] == 1 ? ":confirmed" : ":pending");
        }
        return sb.toString();
    }

    /** Gleicher Vertrag wie HelferService.revoke: Erfolg erst nach dem Speichern, sonst Kanal aus. */
    private synchronized String revoke(byte[] id) {
        if (server == null) return "NO_SERVER";
        ChannelServer s = server;
        synchronized (ChannelServer.CORE) { if (id == null) Core.hostRevokeAll(); else Core.hostRevoke(id); }
        try {
            persist(s);
            return "REVOKED saved";
        } catch (Exception e) {
            stopServer();
            return "REVOKE_NOT_DURABLE server stopped";
        }
    }

    private synchronized String resetIdentity() throws Exception {
        stopServer();
        writer.delete(() -> Files.deleteIfExists(registryFile.toPath()));
        Files.deleteIfExists(identityFile.toPath());
        return startGenuine();
    }

    private String stats() {
        synchronized (ChannelServer.CORE) {
            return "STATS " + (ChannelServer.coreOwner == null ? "no-core" : Core.hostStats(now()));
        }
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x & 0xff));
        return sb.toString();
    }

    static byte[] unhex(String s) {
        if (!s.matches("[0-9a-f]{32}")) throw new IllegalArgumentException("registration id");
        byte[] b = new byte[16];
        for (int i = 0; i < 16; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    /**
     * Fremder Dienst auf demselben Port (Kanal beendet): nimmt Anfragen an, zählt Klartext-Treffer (Befehlsnamen,
     * alter Schlüssel-Header) und antwortet gefälscht. Kadri muss das als fremde Identität erkennen.
     */
    private static final class Rogue implements AutoCloseable {
        static final String[] MARKERS = {"\"op\"", "bright", "status", "beat", "x-evp-key", "47811"};
        final ServerSocket ss;
        final Map<String, Integer> seen = new TreeMap<>();
        volatile int plaintextHits;

        Rogue() throws Exception {
            ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), ChannelServer.PORT), 16);
            Thread t = new Thread(() -> {
                while (!ss.isClosed()) {
                    try (Socket s = ss.accept()) {
                        s.setSoTimeout(2000);
                        InputStream in = s.getInputStream();
                        ByteArrayOutputStream got = new ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        try { for (int n; got.size() < 70000 && (n = in.read(buf)) > 0; ) { got.write(buf, 0, n); if (in.available() == 0 && complete(got.toByteArray())) break; } }
                        catch (java.net.SocketTimeoutException done) { /* genug gelesen */ }
                        String text = new String(got.toByteArray(), StandardCharsets.ISO_8859_1);
                        String line = text.split("\r\n", 2)[0];
                        synchronized (seen) { seen.merge(line, 1, Integer::sum); }
                        String lower = text.toLowerCase(Locale.ROOT);
                        for (String m : MARKERS) if (lower.contains(m)) plaintextHits++;
                        byte[] forged = "{\"ok\":true,\"bright\":1}".getBytes(StandardCharsets.UTF_8);
                        OutputStream o = s.getOutputStream();
                        o.write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: " + forged.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                        o.write(forged);
                    } catch (Exception closed) { if (ss.isClosed()) return; }
                }
            }, "rogue");
            t.start();
        }

        static boolean complete(byte[] b) {
            String t = new String(b, StandardCharsets.ISO_8859_1);
            int h = t.indexOf("\r\n\r\n");
            if (h < 0) return false;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)content-length:\\s*(\\d+)").matcher(t.substring(0, h));
            return !m.find() || b.length - h - 4 >= Integer.parseInt(m.group(1));
        }

        @Override public void close() { try { ss.close(); } catch (Exception ignored) {} }
    }

    private synchronized String rogueOn() throws Exception {
        stopServer();
        for (int i = 0; i < 30 && rogue == null; i++) {
            try { rogue = new Rogue(); } catch (java.net.BindException busy) { Thread.sleep(100); }
        }
        return rogue != null ? "ROGUE" : "PORT_BUSY";
    }

    private synchronized String rogueOff() throws Exception {
        String seen = rogue == null ? "" : " plaintextHits=" + rogue.plaintextHits + " seen=" + rogue.seen;
        return startGenuine() + seen;
    }

    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("state dir");
        ChannelHarness h = new ChannelHarness(dir);
        out(h.startGenuine());
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        for (String line; (line = in.readLine()) != null; ) {
            String cmd = line.trim(), reply;
            try {
                if (cmd.startsWith("revoke ")) reply = h.revoke(unhex(cmd.substring(7).trim()));
                else switch (cmd) {
                    case "open": reply = h.open(); break;
                    case "list": reply = h.list(); break;
                    case "revoke-all": reply = h.revoke(null); break;
                    case "restart": reply = h.startGenuine(); break;
                    case "reset-identity": reply = h.resetIdentity(); break;
                    case "device": reply = h.device.describe(); break;
                    case "stats": reply = h.stats(); break;
                    case "rogue-on": reply = h.rogueOn(); break;
                    case "rogue-off": reply = h.rogueOff(); break;
                    case "fail-persist on": h.failPersist = true; reply = "OK"; break;
                    case "fail-persist off": h.failPersist = false; reply = "OK"; break;
                    case "quit": h.stopServer(); out("BYE"); return;
                    default: reply = "UNKNOWN";
                }
            } catch (Exception e) {
                reply = "ERROR " + e.getClass().getSimpleName();
            }
            out(reply);
        }
        h.stopServer();
    }
}
