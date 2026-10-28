package de.evp.pakepoc;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

import poc.Core;

/**
 * TEST HARNESS ONLY – never shipped, never on a phone. Hosts the unchanged
 * {@link PocServer}, {@link HttpRequestReader} and {@link SnapshotWriter} of the
 * device-tested Android test app on a desktop JVM (Linux build of the same Rust
 * core over JNI), so Kadri's integration tests talk to exactly the server code
 * that ran on the phone. Only {@code PocService} (Android lifecycle, Keystore)
 * is replaced by this class, mirroring its logic.
 *
 * Differences to the app, all test-only: the throwaway identity is stored in
 * clear in {@code stateDir/identity.bin} (no Keystore on a desktop JVM), and
 * persistence failures can be injected.
 *
 * Control: one command per stdin line; replies on stdout.
 *   open | revoke | restart | reset-identity | rogue-on | rogue-off |
 *   fail-persist on|off | stats | quit
 * "open" prints "CODE <8 digits>": the code goes only to the controlling test.
 * Usage: java -Djava.library.path=LIB -cp CLASSES de.evp.pakepoc.DesktopHelperHost STATE_DIR
 */
public final class DesktopHelperHost implements PocServer.Host {
    private final File identityFile, registryFile;
    private final SnapshotWriter writer;
    private PocServer server;
    private volatile boolean failPersist;

    DesktopHelperHost(File stateDir) {
        identityFile = new File(stateDir, "identity.bin");
        registryFile = new File(stateDir, "registrations.bin");
        writer = new SnapshotWriter(this::write);
    }

    @Override public long now() { return System.nanoTime() / 1_000_000; }

    @Override public void event(String line) { out("EVENT " + line); }

    @Override public void persist(PocServer from) throws Exception {
        if (from.rogue) return;
        writer.persist(() -> {
            synchronized (PocServer.CORE) {
                return PocServer.coreOwner == from ? Core.hostExportRegistrations() : null;
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
        synchronized (PocServer.CORE) { PocServer.coreOwner = null; }
        server = null;
    }

    private synchronized String startGenuine() throws Exception {
        stopServer();
        byte[] id;
        if (identityFile.exists()) id = Files.readAllBytes(identityFile.toPath());
        else if (registryFile.exists()) return "ERROR_INCONSISTENT";   // same rule as the app (review F2)
        else { id = Core.identityGenerate(); Files.write(identityFile.toPath(), id); }
        byte[] regs = registryFile.exists() ? Files.readAllBytes(registryFile.toPath()) : new byte[0];
        PocServer s = new PocServer(this, false, null);
        String pub;
        try {
            synchronized (PocServer.CORE) {
                pub = Core.hostInit(id, regs, now(), true);
                PocServer.coreOwner = s;
            }
        } finally {
            Arrays.fill(id, (byte) 0);
        }
        persist(s);
        server = s;
        return server.start() ? "READY " + pub.substring(0, 8) : "PORT_BUSY";
    }

    private synchronized String rogueOn() {
        stopServer();
        byte[] eph = Core.identityGenerate();
        PocServer s = new PocServer(this, true, null);
        synchronized (PocServer.CORE) {
            Core.hostInit(eph, new byte[0], now(), true);
            PocServer.coreOwner = s;
        }
        Arrays.fill(eph, (byte) 0);
        server = s;
        return server.start() ? "ROGUE" : "PORT_BUSY";
    }

    private synchronized String rogueOff() throws Exception {
        String seen = server != null && server.rogue ? " plaintextHits=" + server.roguePlaintextHits : "";
        return startGenuine() + seen;
    }

    private synchronized String open() {
        if (server == null || server.rogue) return "NO_SERVER";
        synchronized (PocServer.CORE) {
            if (PocServer.coreOwner != server) return "NO_SERVER";
            return "CODE " + Core.windowOpen(null, now(), PocServer.PAIR_TTL_MS);
        }
    }

    /** Same contract as PocService.revokeAll: success only once saved; otherwise the server stops. */
    private synchronized String revoke() {
        if (server == null || server.rogue) return "NO_SERVER";
        PocServer s = server;
        synchronized (PocServer.CORE) { Core.hostRevokeAll(); }
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
        synchronized (PocServer.CORE) {
            return "STATS " + (PocServer.coreOwner == null ? "no-core" : Core.hostStats(now()));
        }
    }

    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("state dir");
        DesktopHelperHost h = new DesktopHelperHost(dir);
        out(h.startGenuine());
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        for (String line; (line = in.readLine()) != null; ) {
            String reply;
            try {
                switch (line.trim()) {
                    case "open": reply = h.open(); break;
                    case "revoke": reply = h.revoke(); break;
                    case "restart": reply = h.startGenuine(); break;
                    case "reset-identity": reply = h.resetIdentity(); break;
                    case "rogue-on": reply = h.rogueOn(); break;
                    case "rogue-off": reply = h.rogueOff(); break;
                    case "fail-persist on": h.failPersist = true; reply = "OK"; break;
                    case "fail-persist off": h.failPersist = false; reply = "OK"; break;
                    case "stats": reply = h.stats(); break;
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
