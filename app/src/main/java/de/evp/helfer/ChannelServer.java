package de.evp.helfer;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import poc.Core;

/**
 * Sicherer Kanal zu Kadri: lauscht nur auf 127.0.0.1:47899. Übernommen aus dem geprüften Test-Server der
 * Gerätetests (PocServer) ohne dessen Test-Teile (Dummy-Daten, Fremd-Listener-Simulation, Probe-Port).
 *
 * Endpunkte (nur POST): /p1/pair/m0, /p1/pair/m2 (Kopplung, SPAKE2), /p1/ch/hs (Noise-KK-Handshake),
 * /p1/ch/rec (verschlüsselter Record). Inhalt eines Records sind ausschließlich die Steuerbefehle aus
 * {@link ControlCommands} (Steuerung; ab Phase C2 optional Start/Status/Abbruch direkter Downloads); es gibt keinen
 * Klartext-Weg und keinen festen Schlüssel. Mediendaten laufen nie durch diesen Kanal.
 *
 * Der Rust-Kern ist prozessweit; {@link #CORE} schützt ihn. Unter dieser Sperre laufen nur Kernaufrufe – das
 * Ausführen eines Befehls (Helligkeit, Lautstärke) geschieht außerhalb. {@link #coreOwner} nennt den Server,
 * dessen Kern gerade geladen ist; eine noch laufende Anfrage eines beendeten Servers berührt ihn nicht mehr.
 *
 * Ohne Android-Typen (Desktop-JVM-Tests mit derselben Klasse).
 */
public final class ChannelServer {
    public interface Host {
        long now();                                   // monotone Millisekunden (Android: elapsedRealtime)
        void event(String line);                      // nur Ereignisarten, nie Inhalte oder Schlüssel
        /** Speichert Registrierungsänderungen dauerhaft; wirft, wenn das nicht gelang. */
        void persist(ChannelServer from) throws Exception;
    }

    public static final int PORT = 47899;
    public static final long PAIR_TTL_MS = 120_000;
    static final Pattern EXT_ORIGIN = Pattern.compile("(chrome-extension|moz-extension)://[a-z0-9-]{1,64}");
    public static final Object CORE = new Object();
    public static ChannelServer coreOwner;            // guarded by CORE

    private final Host host;
    private final ControlCommands.Device device;
    private final ControlCommands.DownloadControl downloads;   // null: Download-Befehle werden abgelehnt
    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16));
    private ServerSocket server;
    private Thread acceptThread;

    public ChannelServer(Host host, ControlCommands.Device device) { this(host, device, null); }

    /** Phase C2: zusätzlich Download-Befehle (dlstart/dlstatus/dlcancel) über denselben verschlüsselten Kanal. */
    public ChannelServer(Host host, ControlCommands.Device device, ControlCommands.DownloadControl downloads) {
        this.host = host;
        this.device = device;
        this.downloads = downloads;
    }

    /** Bindet vor der Bereitschaftsmeldung; ein belegter Port meldet false (kein Ausweichen). */
    public synchronized boolean start() {
        try {
            ServerSocket s = new ServerSocket();
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 16);
            server = s;
        } catch (Exception e) {
            stop();
            return false;
        }
        final ServerSocket ss = server;
        Thread t = new Thread(() -> {
            while (!ss.isClosed()) {
                try {
                    Socket s = ss.accept();
                    long acceptedAt = host.now();   // die Lesefrist beginnt hier, nicht erst beim freien Worker
                    try { pool.execute(() -> handle(s, acceptedAt)); }
                    catch (RejectedExecutionException full) { close(s); }
                } catch (Exception closed) { return; }
            }
        }, "evp-kanal");
        acceptThread = t;
        t.start();
        return true;
    }

    /** Erst zurück, wenn der Annahme-Thread beendet ist: Android gibt den Port erst dann wirklich frei. */
    public synchronized void stop() {
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        pool.shutdownNow();
        Thread t = acceptThread;
        acceptThread = null;
        if (t != null && t != Thread.currentThread()) {
            try { t.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private static void close(Socket s) { try { s.close(); } catch (Exception ignored) {} }

    /** Mit CORE gehalten aufrufen. */
    private void owned() {
        if (coreOwner != this) throw new SecurityException("StaleServer");
    }

    private static void reply(Socket s, int code, byte[] body) throws Exception {
        String head = "HTTP/1.1 " + code + " " + (code == 200 ? "OK" : "Error") + "\r\n"
            + "Content-Type: application/octet-stream\r\n"
            + "Cache-Control: no-store\r\n"
            + "Content-Length: " + body.length + "\r\n"
            + "Connection: close\r\n\r\n";
        OutputStream out = s.getOutputStream();
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    /** Schutz gegen Webseiten (keine Authentisierung – die leistet allein der Kanal). */
    static String gate(HttpRequestReader.Req r) {
        if ("OPTIONS".equals(r.method)) return "preflight";
        if (!"POST".equals(r.method)) return "method";
        if (!("127.0.0.1:" + PORT).equals(r.headers.get("host"))) return "host";
        if (!"application/octet-stream".equals(r.headers.get("content-type"))) return "content-type";
        if (!"2".equals(r.headers.get("x-kadri-helper"))) return "custom-header";
        String origin = r.headers.get("origin");
        if (origin != null && !EXT_ORIGIN.matcher(origin).matches()) return "origin";
        return null;
    }

    private void handle(Socket s, long acceptedAt) {
        String path = "?";
        try {
            s.setTcpNoDelay(true);
            HttpRequestReader.Req r = HttpRequestReader.read(s, acceptedAt, host::now);
            path = r.path;
            String why = gate(r);
            if (why != null) {
                host.event("GATE_REJECT " + why);
                reply(s, 403, new byte[0]);
                return;
            }
            reply(s, 200, route(r.path, r.body));
        } catch (SecurityException e) {
            host.event("REJECT " + path + " " + e.getMessage());
            try { reply(s, "UnknownSession".equals(e.getMessage()) ? 404 : 400, new byte[0]); } catch (Exception ignored) {}
        } catch (Exception e) {
            host.event("ERROR " + path + " " + e.getClass().getSimpleName());
            try { reply(s, 500, new byte[0]); } catch (Exception ignored) {}
        } finally {
            close(s);
        }
    }

    /** Jede Route speichert vor der Antwort (neue/abgelaufene/bestätigte Registrierung ist auf Disk, bevor Kadri es erfährt). */
    private byte[] route(String path, byte[] body) throws Exception {
        long now = host.now();
        switch (path) {
            case "/p1/pair/m0":
                synchronized (CORE) { owned(); return Core.windowOnM0(body, now); }
            case "/p1/pair/m2": {
                byte[] r;
                synchronized (CORE) { owned(); r = Core.windowOnM2(body, now); }
                host.persist(this);   // kein M3 (und damit kein Pin bei Kadri), solange nicht gespeichert
                host.event("PAIRED_PENDING");
                return Arrays.copyOfRange(r, 16, r.length);
            }
            case "/p1/ch/hs": {
                byte[] hs2;
                synchronized (CORE) { owned(); hs2 = Core.hostHandshake(body, now); }
                host.persist(this);
                host.event("HANDSHAKE");
                return hs2;
            }
            case "/p1/ch/rec": {
                byte[] opened;
                synchronized (CORE) { owned(); opened = Core.hostOpen(body, now); }
                host.persist(this);
                byte[] sid = Arrays.copyOfRange(opened, 0, 16);
                // Nur Steuer-Records (Art 1); Medien- oder Ende-Records gibt es in diesem Kanal nicht.
                String answer = opened.length > 17 && opened[16] == 1
                    ? ControlCommands.handle(Arrays.copyOfRange(opened, 17, opened.length), device, downloads)
                    : ControlCommands.REJECTED;
                host.event(ControlCommands.REJECTED.equals(answer) ? "REC_REJECTED" : "REC");
                synchronized (CORE) {
                    owned();
                    return Core.hostSealControl(sid, answer.getBytes(StandardCharsets.UTF_8), host.now());
                }
            }
            default:
                throw new SecurityException("UnknownPath");
        }
    }
}
