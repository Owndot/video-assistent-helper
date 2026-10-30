package de.evp.helfer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import poc.Core;
import poc.TestInitiator;

/**
 * JVM-Prüfungen des Steuerkanals der Helper-App auf dem Desktop: echter {@link ChannelServer}, echte
 * {@link ControlCommands}, echter Rust-Kern (Helper-Seite über JNI), Kadri-Seite über den Test-Initiator
 * derselben Bibliothek. Ersetzt sind nur das Gerät (Nachbildung) und der Speicher (Byte-Array).
 * Ohne Test-Framework: jede Prüfung wirft bei Fehlern, main endet dann mit Code ≠ 0.
 * Aufruf: tests/java/channel/run.sh
 */
public final class ChannelChecks {
    private static int passed;

    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    static long now() { return System.nanoTime() / 1_000_000; }

    // ------------------------------------------------------------ Befehle (ohne JNI)

    static final String[] VALID = {
        "{\"op\":\"status\"}", "{\"op\":\"beat\"}", "{\"op\":\"end\"}", "{\"op\":\"vol\"}",
        "{\"op\":\"vol\",\"v\":0}", "{\"op\":\"vol\",\"v\":1}", "{\"op\":\"vol\",\"v\":0.5}", "{\"v\":0.25,\"op\":\"vol\"}",
        "{\"op\":\"bright\",\"v\":0.004}", "{\"op\":\"bright\",\"v\":1}", "{\"op\":\"bright\",\"v\":1.0}",
        " { \"op\" : \"bright\" , \"v\" : 0.75 } ",
    };

    static final String[] INVALID = {
        "", "{}", "[]", "null", "\"status\"", "{\"op\":\"status\"", "{\"op\":\"status\"}x", "{\"op\":\"status\"}{}",
        "{\"op\":\"ping\"}", "{\"op\":\"STATUS\"}", "{\"op\":\"stat\\u0075s\"}", "{\"op\":\"status\",\"op\":\"status\"}",
        "{\"op\":\"vol\",\"v\":0.5,\"v\":0.5}", "{\"op\":\"status\",\"v\":1}", "{\"op\":\"beat\",\"v\":0}", "{\"op\":\"end\",\"v\":0}",
        "{\"op\":\"bright\"}", "{\"op\":\"bright\",\"v\":0}", "{\"op\":\"bright\",\"v\":0.003}", "{\"op\":\"bright\",\"v\":1.0001}",
        "{\"op\":\"bright\",\"v\":-0.5}", "{\"op\":\"bright\",\"v\":1e0}", "{\"op\":\"bright\",\"v\":\"0.5\"}", "{\"op\":\"bright\",\"v\":.5}",
        "{\"op\":\"bright\",\"v\":0.}", "{\"op\":\"bright\",\"v\":01}", "{\"op\":\"bright\",\"v\":0.123456789}", "{\"op\":\"bright\",\"v\":NaN}",
        "{\"op\":\"bright\",\"v\":true}", "{\"op\":\"bright\",\"v\":null}", "{\"op\":\"vol\",\"v\":2}", "{\"op\":\"vol\",\"v\":1000}",
        "{\"op\":\"status\",\"url\":\"https://x\"}", "{\"op\":\"status\",\"url\":\"abc\"}", "{\"url\":\"abc\",\"op\":\"status\"}", "{\"op\":\"status\",\"cookie\":\"a=b\"}", "{\"op\":\"status\",\"x\":\"abc\"}", "{\"op\":\"status\",\"cookie\":\"abc\"}", "{\"op\":\"dl\"}", "{\"op\":\"pair\"}",
        "{\"op\":\"status\",}", "{,\"op\":\"status\"}", "{\"op\" \"status\"}", "{'op':'status'}", "{\"op\":status}",
        "{\"op\":\"abcdefghijklmnopq\"}", "ï»¿{\"op\":\"status\"}",
    };

    static final class FakeDevice implements ControlCommands.Device {
        int brights, vols, beats, ends;
        float bright = -1, vol = -1;
        @Override public synchronized void bright(float level) { brights++; bright = level; }
        @Override public synchronized void vol(float level) { vols++; vol = level; }
        @Override public synchronized void beat() { beats++; }
        @Override public synchronized void end() { ends++; }
        @Override public synchronized String status() { return ControlCommands.statusJson("1.2.3", true, false, 5, 15, bright < 0 ? 0.5f : bright); }
        synchronized int touches() { return brights + vols + beats + ends; }
    }

    // ------------------------------------------------------------ Download-Befehle (Phase C2)

    static final String ID = "0123456789abcdef0123456789abcdef";

    static final String[] VALID_DL = {
        "{\"op\":\"dlstart\",\"id\":\"" + ID + "\",\"url\":\"https://media.example/v.mp4?sig=a%2Fb&x=1#t\"}",
        "{\"op\":\"dlstart\",\"id\":\"" + ID + "\",\"url\":\"http://a.example/v.mp4\",\"name\":\"Urlaub 2026 – Teil 1\"}",
        "{\"url\":\"https://a.example/x\",\"name\":\"\",\"id\":\"" + ID + "\",\"op\":\"dlstart\"}",
        "{\"op\":\"dlstatus\",\"id\":\"" + ID + "\"}",
        "{\"op\":\"dlcancel\",\"id\":\"" + ID + "\"}",
    };

    static final String[] INVALID_DL = {
        "{\"op\":\"dlstart\",\"id\":\"" + ID + "\"}",                                              // ohne URL
        "{\"op\":\"dlstart\",\"url\":\"https://a.example/x\"}",                                      // ohne Kennung
        "{\"op\":\"dlstart\",\"id\":\"" + ID.toUpperCase() + "\",\"url\":\"https://a.example/x\"}",   // Großbuchstaben
        "{\"op\":\"dlstart\",\"id\":\"" + ID.substring(1) + "\",\"url\":\"https://a.example/x\"}",    // 31 Zeichen
        "{\"op\":\"dlstart\",\"id\":\"" + ID + "0\",\"url\":\"https://a.example/x\"}",              // 33 Zeichen
        "{\"op\":\"dlstart\",\"id\":\"" + ID + "\",\"url\":\"\"}",                                 // leere URL
        "{\"op\":\"dlstart\",\"id\":\"" + ID + "\",\"url\":\"https://a.example/a b\"}",             // Leerzeichen
        "{\"op\":\"dlstart\",\"id\":\"" + ID + "\",\"url\":\"https://a.example/a\\\"b\"}",        // Escape
        "{\"op\":\"dlstart\",\"id\":\"" + ID + "\",\"url\":\"https://a.example/\u00e4\"}",          // Nicht-ASCII
        "{\"op\":\"dlstart\",\"id\":\"" + ID + "\",\"url\":\"https://a.example/<x>\"}",             // < >
        "{\"op\":\"dlstart\",\"id\":\"" + ID + "\",\"url\":\"https://a.example/x\",\"cookie\":\"a=b\"}",
        "{\"op\":\"dlstart\",\"id\":\"" + ID + "\",\"url\":\"https://a.example/x\",\"cookie\":\"abc\"}",
        "{\"op\":\"dlstart\",\"id\":\"" + ID + "\",\"url\":\"https://a.example/x\",\"authorization\":\"abc\"}",
        "{\"op\":\"dlstatus\",\"id\":\"" + ID + "\",\"x\":\"abc\"}",
        "{\"op\":\"dlstart\",\"id\":\"" + ID + "\",\"url\":\"https://a.example/x\",\"headers\":\"Authorization: x\"}",
        "{\"op\":\"dlstart\",\"id\":\"" + ID + "\",\"url\":\"https://a.example/x\",\"referer\":\"https://a.example/\"}",
        "{\"op\":\"dlstart\",\"id\":\"" + ID + "\",\"url\":\"https://a.example/x\",\"v\":1}",
        "{\"op\":\"dlstart\",\"id\":\"" + ID + "\",\"id\":\"" + ID + "\",\"url\":\"https://a.example/x\"}",
        "{\"op\":\"dlstart\",\"id\":\"" + ID + "\",\"url\":\"https://a.example/x\",\"url\":\"https://b.example/x\"}",
        "{\"op\":\"dlstart\",\"id\":\"" + ID + "\",\"url\":\"https://a.example/x\",\"name\":\"a\",\"name\":\"b\"}",
        "{\"op\":\"dlstart\",\"id\":\"" + ID + "\",\"url\":\"https://a.example/x\",\"name\":\"a\\nb\"}",   // Escape im Namen
        "{\"op\":\"dlstart\",\"id\":\"" + ID + "\",\"url\":\"https://a.example/x\",\"name\":\"a\u0001b\"}",
        "{\"op\":\"dlstart\",\"id\":\"" + ID + "\",\"url\":\"https://a.example/x\",\"name\":\"a\u2028b\"}",
        "{\"op\":\"dlstart\",\"id\":\"" + ID + "\",\"url\":\"https://a.example/x\",\"name\":\"" + "n".repeat(121) + "\"}",
        "{\"op\":\"dlstart\",\"id\":\"" + ID + "\",\"url\":\"https://a.example/" + "x".repeat(2031) + "\"}",   // URL mit 2049 Zeichen
        "{\"op\":\"dlstatus\"}",
        "{\"op\":\"dlstatus\",\"id\":\"" + ID + "\",\"url\":\"https://a.example/x\"}",
        "{\"op\":\"dlcancel\",\"id\":\"" + ID + "\",\"name\":\"x\"}",
        "{\"op\":\"dlcancel\",\"id\":\"" + ID + "\",\"v\":0}",
        "{\"op\":\"status\",\"id\":\"" + ID + "\"}",                                               // Download-Feld bei Steuerbefehl
        "{\"op\":\"bright\",\"v\":0.5,\"name\":\"x\"}",
        "{\"op\":\"dlpause\",\"id\":\"" + ID + "\"}",
        "{\"op\":\"dlstart\",\"id\":" + "1234" + ",\"url\":\"https://a.example/x\"}",
    };

    static final class FakeDownloads implements ControlCommands.DownloadControl {
        final java.util.List<String> calls = new java.util.ArrayList<>();
        @Override public synchronized String start(String id, String url, String name) { calls.add("start " + id + " " + url + " " + name); return "{\"ok\":true,\"id\":\"" + id + "\",\"state\":\"running\"}"; }
        @Override public synchronized String status(String id) { calls.add("status " + id); return "{\"ok\":true,\"id\":\"" + id + "\",\"state\":\"done\"}"; }
        @Override public synchronized String cancel(String id) { calls.add("cancel " + id); return "{\"ok\":true,\"id\":\"" + id + "\",\"state\":\"cancelled\"}"; }
    }

    static String dl(String json, FakeDevice d, ControlCommands.DownloadControl dl) {
        return ControlCommands.handle(json.getBytes(StandardCharsets.UTF_8), d, dl);
    }

    static void downloadCommands() {
        // Ohne angebundene Downloads (Phase-2-Zustand): jeder dl-Befehl abgelehnt.
        for (String s : VALID_DL) {
            FakeDevice d = new FakeDevice();
            check(ControlCommands.REJECTED.equals(ControlCommands.handle(s.getBytes(StandardCharsets.UTF_8), d)), "accepted without handler: " + s);
            check(ControlCommands.REJECTED.equals(dl(s, d, null)), "accepted with null handler: " + s);
            check(d.touches() == 0, "device touched");
        }
        // Mit Downloads: gültige Befehle erreichen genau die Download-Steuerung, nie das Gerät.
        FakeDevice d = new FakeDevice();
        FakeDownloads f = new FakeDownloads();
        check(dl(VALID_DL[0], d, f).contains("\"state\":\"running\""), "dlstart answer");
        check(f.calls.get(0).equals("start " + ID + " https://media.example/v.mp4?sig=a%2Fb&x=1#t null"), f.calls.get(0));
        dl(VALID_DL[1], d, f);
        check(f.calls.get(1).equals("start " + ID + " http://a.example/v.mp4 Urlaub 2026 – Teil 1"), f.calls.get(1));
        dl(VALID_DL[2], d, f);
        check(f.calls.get(2).equals("start " + ID + " https://a.example/x "), "empty name: " + f.calls.get(2));
        check(dl(VALID_DL[3], d, f).contains("done") && f.calls.get(3).equals("status " + ID), "dlstatus");
        check(dl(VALID_DL[4], d, f).contains("cancelled") && f.calls.get(4).equals("cancel " + ID), "dlcancel");
        check(d.touches() == 0, "device touched by download commands");
        int calls = f.calls.size();
        for (String s : INVALID_DL) {
            check(ControlCommands.REJECTED.equals(dl(s, d, f)), "accepted: " + s);
        }
        check(f.calls.size() == calls && d.touches() == 0, "invalid download command reached a handler");
        // Steuerbefehle bleiben unverändert (auch mit angebundenen Downloads) und berühren die Downloads nicht.
        for (String s : VALID) dl(s, new FakeDevice(), f);
        for (String s : INVALID) check(ControlCommands.REJECTED.equals(dl(s, new FakeDevice(), f)), "accepted with handler: " + s);
        check(f.calls.size() == calls, "control command reached downloads");
        // Größen: dlstart bis 4096 Byte, alle anderen bei 256 Byte.
        String longUrl = "https://a.example/" + "x".repeat(2048 - 18);
        String okBig = "{\"op\":\"dlstart\",\"id\":\"" + ID + "\",\"url\":\"" + longUrl + "\",\"name\":\"" + "n".repeat(120) + "\"}";
        check(longUrl.length() == ControlCommands.MAX_URL && !ControlCommands.REJECTED.equals(dl(okBig, d, f)), "max dlstart rejected");
        String padded = "{\"op\":\"dlstatus\",\"id\":\"" + ID + "\"" + " ".repeat(200) + "}";
        check(padded.length() > ControlCommands.MAX_REQUEST && ControlCommands.REJECTED.equals(dl(padded, d, f)), "oversize dlstatus accepted");
        String paddedStatus = "{\"op\":\"status\"" + " ".repeat(300) + "}";
        check(ControlCommands.REJECTED.equals(dl(paddedStatus, d, f)), "oversize status accepted with handler");
        byte[] huge = ("{\"op\":\"dlstart\",\"id\":\"" + ID + "\",\"url\":\"https://a.example/x\"" + " ".repeat(4096) + "}").getBytes(StandardCharsets.UTF_8);
        check(ControlCommands.REJECTED.equals(ControlCommands.handle(huge, d, f)), "oversize dlstart accepted");
        passed++;
    }

    static void strictParser() {
        for (String s : VALID) ControlCommands.parse(s.getBytes(StandardCharsets.UTF_8));
        for (String s : INVALID) {
            FakeDevice d = new FakeDevice();
            check(ControlCommands.REJECTED.equals(ControlCommands.handle(s.getBytes(StandardCharsets.UTF_8), d)), "accepted: " + s);
            check(d.touches() == 0, "device touched by: " + s);
        }
        // Größe, ungültiges UTF-8, null
        byte[] big = ("{\"op\":\"status\"" + " ".repeat(250) + "}").getBytes(StandardCharsets.UTF_8);
        check(big.length > ControlCommands.MAX_REQUEST, "oversize sample");
        check(ControlCommands.REJECTED.equals(ControlCommands.handle(big, new FakeDevice())), "oversize accepted");
        check(ControlCommands.REJECTED.equals(ControlCommands.handle(new byte[] {'{', '"', (byte) 0xff, '"', '}'}, new FakeDevice())), "bad utf-8 accepted");
        check(ControlCommands.REJECTED.equals(ControlCommands.handle(null, new FakeDevice())), "null accepted");
        passed++;
    }

    static void commandsReachTheDevice() {
        FakeDevice d = new FakeDevice();
        String r = ControlCommands.handle("{\"op\":\"bright\",\"v\":0.75}".getBytes(StandardCharsets.UTF_8), d);
        check(d.brights == 1 && d.bright == 0.75f && r.contains("\"bright\":0.7500"), "bright");
        ControlCommands.handle("{\"op\":\"vol\",\"v\":0.25}".getBytes(StandardCharsets.UTF_8), d);
        check(d.vols == 1 && d.vol == 0.25f, "vol");
        ControlCommands.handle("{\"op\":\"vol\"}".getBytes(StandardCharsets.UTF_8), d);
        check(d.vols == 1, "vol without v only reads");
        ControlCommands.handle("{\"op\":\"beat\"}".getBytes(StandardCharsets.UTF_8), d);
        check(d.beats == 1, "beat");
        check("{\"ok\":true}".equals(ControlCommands.handle("{\"op\":\"end\"}".getBytes(StandardCharsets.UTF_8), d)) && d.ends == 1, "end");
        passed++;
    }

    static void statusJsonIsFixedAndSanitised() {
        String s = ControlCommands.statusJson("1.0\",\"dl\":true,\"x\":\"" + "9".repeat(64), false, true, 20, 15, Float.NaN);
        check(s.startsWith("{\"ok\":true,\"v\":3,\"dl\":false,\"dash\":false,\"proxy\":false,\"app\":\""), "prefix: " + s);
        check(s.indexOf("\"dl\"") == s.lastIndexOf("\"dl\""), "injected field: " + s);
        String app = s.substring(s.indexOf("\"app\":\"") + 7, s.indexOf('"', s.indexOf("\"app\":\"") + 7));
        check(app.length() <= 32 && app.matches("[0-9A-Za-z.+-]*"), "app sanitised: " + app);
        check(s.contains("\"volStep\":15,\"volMax\":15") && s.contains("\"vol\":1.0000") && s.contains("\"bright\":0.0000"), "clamped: " + s);
        check(!s.contains("NaN") && !s.contains("url") && !s.contains("cookie"), "no foreign content: " + s);
        passed++;
    }

    // ------------------------------------------------------------ Kanal (echter Kern über JNI)

    static final class Host implements ChannelServer.Host {
        final SnapshotWriter writer;
        final AtomicReference<byte[]> disk = new AtomicReference<>(new byte[0]);
        volatile boolean failPersist;
        Host() { writer = new SnapshotWriter(b -> { if (failPersist) throw new java.io.IOException("injected"); disk.set(b.clone()); }); }
        @Override public long now() { return ChannelChecks.now(); }
        @Override public void event(String line) { }
        @Override public void persist(ChannelServer from) throws Exception {
            writer.persist(() -> { synchronized (ChannelServer.CORE) { return ChannelServer.coreOwner == from ? Core.hostExportRegistrations() : null; } });
        }
    }

    static Host host;
    static FakeDevice device;
    static ChannelServer server;
    static byte[] identity;

    /** Startet einen Helper; bei {@code fresh} mit neuer Identität und leerer Registrierung. */
    static void start(boolean fresh) throws Exception { start(fresh, null); }

    static void start(boolean fresh, ControlCommands.DownloadControl downloads) throws Exception {
        if (server != null) server.stop();
        if (fresh || identity == null) { identity = Core.identityGenerate(); host = new Host(); }
        device = new FakeDevice();
        ChannelServer s = new ChannelServer(host, device, downloads);
        synchronized (ChannelServer.CORE) {
            Core.hostInit(identity.clone(), host.disk.get(), now(), true);
            ChannelServer.coreOwner = s;
        }
        for (int i = 0; i < 30 && !s.start(); i++) {   // der JDK gibt den Port verzögert frei
            Thread.sleep(100);
            ChannelServer n = new ChannelServer(host, device, downloads);
            synchronized (ChannelServer.CORE) { ChannelServer.coreOwner = n; }
            s = n;
        }
        server = s;
        host.persist(s);
    }

    static final class Http { int code; byte[] body; }

    static Http http(String method, String path, byte[] body, Map<String, String> headers) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + ChannelServer.PORT + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(5000);
        c.setReadTimeout(5000);
        c.setUseCaches(false);
        for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
        if (body != null) {
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream o = c.getOutputStream()) { o.write(body); }
        }
        Http r = new Http();
        r.code = c.getResponseCode();
        try (InputStream in = r.code == 200 ? c.getInputStream() : c.getErrorStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (in != null) { byte[] b = new byte[4096]; for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n); }
            r.body = out.toByteArray();
        }
        c.disconnect();
        return r;
    }

    static Map<String, String> hdr(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    static Http raw(String path, byte[] body) throws Exception {
        return http("POST", path, body, hdr("Content-Type", "application/octet-stream", "X-Kadri-Helper", "2"));
    }

    static byte[] post(String path, byte[] body) throws Exception {
        Http r = raw(path, body);
        if (r.code != 200) throw new IllegalStateException("HTTP " + r.code);
        return r.body;
    }

    static String openWindow() {
        synchronized (ChannelServer.CORE) { return Core.windowOpen(null, now(), ChannelServer.PAIR_TTL_MS); }
    }

    /** registration_id(16) || helper_public(32) */
    static byte[] pair(byte[] kadri, String code) throws Exception {
        long h = TestInitiator.pairStart(kadri, code, now(), 60_000);
        try {
            byte[] m1 = post("/p1/pair/m0", TestInitiator.m0(h));
            byte[] m3 = post("/p1/pair/m2", TestInitiator.onM1(h, m1, now()));
            return TestInitiator.onM3(h, m3, now());
        } finally {
            TestInitiator.free(h);
        }
    }

    static long connect(byte[] kadri, byte[] pin) throws Exception {
        long h = TestInitiator.handshakeStart(kadri, Arrays.copyOfRange(pin, 16, 48), Arrays.copyOf(pin, 16));
        TestInitiator.finish(h, post("/p1/ch/hs", TestInitiator.hs1(h)));
        return h;
    }

    static String request(long h, String json) throws Exception {
        byte[] r = TestInitiator.open(h, post("/p1/ch/rec", TestInitiator.sealControl(h, json.getBytes(StandardCharsets.UTF_8))));
        check(r[0] == 1, "control reply");
        return new String(r, 1, r.length - 1, StandardCharsets.UTF_8);
    }

    static String regs() {
        byte[] b;
        synchronized (ChannelServer.CORE) { b = Core.hostExportRegistrations(); }
        StringBuilder sb = new StringBuilder();
        for (int at = 0; at + 57 <= b.length; at += 57) sb.append(ChannelHarness.hex(Arrays.copyOfRange(b, at, at + 16))).append(b[at + 48] == 1 ? "+" : "?").append(' ');
        return sb.toString().trim();
    }

    static String id(byte[] pin) { return ChannelHarness.hex(Arrays.copyOf(pin, 16)); }

    static void pairingAndControlCommands() throws Exception {
        start(true);
        byte[] kadri = Core.identityGenerate();
        byte[] pin = pair(kadri, openWindow());
        check(regs().equals(id(pin) + "?"), "pending after pairing: " + regs());
        check(host.disk.get().length == 57, "pending registration saved before M3");
        long h = connect(kadri, pin);
        String st = request(h, "{\"op\":\"status\"}");
        check(st.startsWith("{\"ok\":true,\"v\":3,\"dl\":false,\"dash\":false,\"proxy\":false,"), "status: " + st);
        check(regs().equals(id(pin) + "+"), "confirmed after first record: " + regs());
        check(host.disk.get()[48] == 1, "confirmation saved");
        String b = request(h, "{\"op\":\"bright\",\"v\":0.8}");
        check(device.brights == 1 && device.bright == 0.8f && b.contains("\"bright\":0.8000"), "bright: " + b);
        request(h, "{\"op\":\"vol\",\"v\":0.4}");
        check(device.vols == 1 && device.vol == 0.4f, "vol");
        request(h, "{\"op\":\"beat\"}");
        check(device.beats == 1, "beat");
        check("{\"ok\":true}".equals(request(h, "{\"op\":\"end\"}")) && device.ends == 1, "end");
        // Ungültiger Befehl: abgelehnt, Gerät unberührt, Sitzung bleibt nutzbar.
        int before = device.touches();
        check(ControlCommands.REJECTED.equals(request(h, "{\"op\":\"status\",\"url\":\"https://example.invalid/\"}")), "foreign field rejected");
        check(ControlCommands.REJECTED.equals(request(h, "{\"op\":\"bright\",\"v\":7}")), "out of range rejected");
        check(device.touches() == before, "device untouched by rejected commands");
        check(request(h, "{\"op\":\"status\"}").startsWith("{\"ok\":true"), "session survives a rejected command");
        // Phase-2-Server ohne Downloads: dl-Befehle über den echten Kanal abgelehnt.
        check(ControlCommands.REJECTED.equals(request(h, VALID_DL[0])), "dlstart without handler accepted");
        check(ControlCommands.REJECTED.equals(request(h, VALID_DL[3])), "dlstatus without handler accepted");
        TestInitiator.free(h);
        passed++;
    }

    /** Download-Befehle über den echten verschlüsselten Kanal (Noise KK), nur mit angebundener Download-Steuerung. */
    static void downloadCommandsOverTheChannel() throws Exception {
        FakeDownloads f = new FakeDownloads();
        start(true, f);
        byte[] kadri = Core.identityGenerate();
        byte[] pin = pair(kadri, openWindow());
        long h = connect(kadri, pin);
        check(request(h, "{\"op\":\"status\"}").contains("\"dl\":false"), "status unchanged");
        check(request(h, VALID_DL[0]).equals("{\"ok\":true,\"id\":\"" + ID + "\",\"state\":\"running\"}"), "dlstart");
        check(request(h, VALID_DL[3]).contains("\"state\":\"done\""), "dlstatus");
        check(request(h, VALID_DL[4]).contains("\"state\":\"cancelled\""), "dlcancel");
        for (String s : INVALID_DL) {
            check(ControlCommands.REJECTED.equals(request(h, s)), "accepted over channel: " + s);
        }
        check(f.calls.size() == 3, "handler calls: " + f.calls);
        check(device.touches() == 0, "device touched by download commands");
        // Die längste zulässige URL passt durch den Kanal.
        String longUrl = "https://a.example/" + "x".repeat(2048 - 18);
        check(request(h, "{\"op\":\"dlstart\",\"id\":\"" + ID + "\",\"url\":\"" + longUrl + "\"}").contains("running"), "max URL over channel");
        check(f.calls.get(3).equals("start " + ID + " " + longUrl + " null"), "max URL intact");
        // Klartext an den Record-Endpunkt erreicht die Downloads nie.
        Http plain = raw("/p1/ch/rec", VALID_DL[0].getBytes(StandardCharsets.UTF_8));
        check(plain.code != 200 || !new String(plain.body, StandardCharsets.UTF_8).contains("running"), "plaintext accepted");
        check(f.calls.size() == 4, "plaintext reached downloads");
        TestInitiator.free(h);
        passed++;
    }

    static void wrongCodeBurnsTheWindow() throws Exception {
        start(true);
        String code = openWindow();
        String wrong = code.charAt(0) == '0' ? "1" + code.substring(1) : "0" + code.substring(1);
        byte[] kadri = Core.identityGenerate();
        boolean failed = false;
        try { pair(kadri, wrong); } catch (Exception expected) { failed = true; }
        check(failed, "wrong code paired");
        failed = false;
        try { pair(kadri, code); } catch (Exception expected) { failed = true; }
        check(failed, "window must be burnt after one wrong attempt");
        check(regs().isEmpty(), "no registration from a failed pairing");
        passed++;
    }

    /** Rohe HTTP-Anfrage (HttpURLConnection lässt Host und Origin nicht frei setzen); gibt den Statuscode zurück. */
    static int rawStatus(String method, String headers, byte[] body) throws Exception {
        try (Socket s = new Socket("127.0.0.1", ChannelServer.PORT)) {
            s.setSoTimeout(5000);
            OutputStream o = s.getOutputStream();
            o.write((method + " /p1/pair/m0 HTTP/1.1\r\n" + headers + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            o.write(body);
            o.flush();
            byte[] head = new byte[12];
            int n = 0;
            for (int r; n < 12 && (r = s.getInputStream().read(head, n, 12 - n)) > 0; ) n += r;
            return Integer.parseInt(new String(head, 9, 3, StandardCharsets.ISO_8859_1));
        }
    }

    static void gateRefusesWebLikeRequests() throws Exception {
        start(true);
        byte[] m0 = new byte[64];
        String host = "Host: 127.0.0.1:47899\r\n", ct = "Content-Type: application/octet-stream\r\n", kh = "X-Kadri-Helper: 2\r\n";
        check(rawStatus("POST", host + ct + kh + "Origin: chrome-extension://abcdefghijklmnop\r\n", m0) == 400, "control: extension origin passes the gate");
        check(rawStatus("POST", host + ct + kh, m0) == 400, "control: no origin passes the gate");
        check(rawStatus("GET", host + kh, new byte[0]) == 403, "GET");
        check(rawStatus("POST", host + "Content-Type: text/plain\r\n" + kh, m0) == 403, "simple content type");
        check(rawStatus("POST", host + ct, m0) == 403, "missing custom header");
        check(rawStatus("POST", host + ct + "X-Kadri-Helper: 1\r\n", m0) == 403, "wrong custom header");
        check(rawStatus("POST", host + ct + kh + "Origin: https://evil.example\r\n", m0) == 403, "web origin");
        check(rawStatus("POST", host + ct + kh + "Origin: null\r\n", m0) == 403, "null origin");
        check(rawStatus("POST", host + ct + kh + "Origin: chrome-extension://abc.evil.example\r\n", m0) == 403, "origin lookalike");
        check(rawStatus("POST", "Host: localhost:47899\r\n" + ct + kh, m0) == 403, "rebinding host");
        check(rawStatus("POST", "Host: evil.example:47899\r\n" + ct + kh, m0) == 403, "foreign host");
        check(rawStatus("OPTIONS", host + kh, new byte[0]) == 403, "preflight");
        check(raw("/pair", new byte[0]).code == 400, "legacy pairing path");
        check(raw("/status", new byte[0]).code == 400, "legacy status path");
        check(raw("/p1/ch/rec", new byte[40]).code != 200, "garbage record");
        // Alter Port: kein Dienst des Kanals (auf dem Gerät prüft der Instrumentierungstest dasselbe).
        boolean legacy;
        try (Socket s = new Socket("127.0.0.1", 47811)) { legacy = s.isConnected(); } catch (Exception refused) { legacy = false; }
        check(!legacy, "something listens on 47811");
        passed++;
    }

    static void revokeOneKeepsTheOthers() throws Exception {
        start(true);
        byte[] ka = Core.identityGenerate(), kb = Core.identityGenerate();
        byte[] pa = pair(ka, openWindow()), pb = pair(kb, openWindow());
        long ha = connect(ka, pa), hb = connect(kb, pb);
        request(ha, "{\"op\":\"status\"}");
        request(hb, "{\"op\":\"status\"}");
        synchronized (ChannelServer.CORE) { Core.hostRevoke(Arrays.copyOf(pa, 16)); }
        host.persist(server);
        check(regs().equals(id(pb) + "+"), "only B remains: " + regs());
        check(host.disk.get().length == 57 && Arrays.equals(Arrays.copyOf(host.disk.get(), 16), Arrays.copyOf(pb, 16)), "revocation saved");
        // A: laufende Sitzung beendet (404), neuer Handshake abgelehnt; B unverändert.
        check(raw("/p1/ch/rec", TestInitiator.sealControl(ha, "{\"op\":\"status\"}".getBytes(StandardCharsets.UTF_8))).code == 404, "A's session ended");
        long ha2 = TestInitiator.handshakeStart(ka, Arrays.copyOfRange(pa, 16, 48), Arrays.copyOf(pa, 16));
        check(raw("/p1/ch/hs", TestInitiator.hs1(ha2)).code == 400, "A cannot reconnect");
        TestInitiator.free(ha2);
        check(request(hb, "{\"op\":\"status\"}").startsWith("{\"ok\":true"), "B keeps its session");
        // Unbekannte Id: kein Effekt; falsche Länge: Fehler, kein Effekt.
        synchronized (ChannelServer.CORE) { Core.hostRevoke(new byte[16]); }
        check(regs().equals(id(pb) + "+"), "unknown id is a no-op");
        boolean threw = false;
        try { synchronized (ChannelServer.CORE) { Core.hostRevoke(new byte[15]); } } catch (RuntimeException expected) { threw = true; }
        check(threw && regs().equals(id(pb) + "+"), "malformed id rejected without effect");
        // Erneute Kopplung von A ist möglich (neue Registrierung).
        byte[] pa2 = pair(ka, openWindow());
        check(!Arrays.equals(Arrays.copyOf(pa2, 16), Arrays.copyOf(pa, 16)), "new registration id");
        long ha3 = connect(ka, pa2);
        check(request(ha3, "{\"op\":\"status\"}").startsWith("{\"ok\":true"), "re-paired A works");
        synchronized (ChannelServer.CORE) { Core.hostRevokeAll(); }
        host.persist(server);
        check(regs().isEmpty() && host.disk.get().length == 0, "revoke all saved");
        check(raw("/p1/ch/rec", TestInitiator.sealControl(hb, "{\"op\":\"status\"}".getBytes(StandardCharsets.UTF_8))).code == 404, "B's session ended");
        TestInitiator.free(ha); TestInitiator.free(hb); TestInitiator.free(ha3);
        passed++;
    }

    static void restartKeepsRegistrationsButNotSessions() throws Exception {
        start(true);
        byte[] k = Core.identityGenerate();
        byte[] p = pair(k, openWindow());
        long h = connect(k, p);
        request(h, "{\"op\":\"status\"}");
        start(false);
        check(regs().equals(id(p) + "+"), "registration survives restart: " + regs());
        check(raw("/p1/ch/rec", TestInitiator.sealControl(h, "{\"op\":\"status\"}".getBytes(StandardCharsets.UTF_8))).code == 404, "old session gone");
        long h2 = connect(k, p);
        check(request(h2, "{\"op\":\"status\"}").startsWith("{\"ok\":true"), "reconnect without re-pairing");
        // Neue Helper-Identität (Zurücksetzen): der alte Pin passt nicht mehr, keine stille Annahme.
        start(true);
        long h3 = TestInitiator.handshakeStart(k, Arrays.copyOfRange(p, 16, 48), Arrays.copyOf(p, 16));
        check(raw("/p1/ch/hs", TestInitiator.hs1(h3)).code == 400, "reset helper must not accept the old registration");
        TestInitiator.free(h); TestInitiator.free(h2); TestInitiator.free(h3);
        passed++;
    }

    static void unsavedPairingIsNeverConfirmed() throws Exception {
        start(true);
        host.failPersist = true;
        String code = openWindow();
        long h = TestInitiator.pairStart(Core.identityGenerate(), code, now(), 60_000);
        byte[] m1 = post("/p1/pair/m0", TestInitiator.m0(h));
        check(raw("/p1/pair/m2", TestInitiator.onM1(h, m1, now())).code == 500, "no M3 without durable registration");
        TestInitiator.free(h);
        host.failPersist = false;
        passed++;
    }

    /** Ein beendeter Server (nicht mehr coreOwner) darf den Kern nicht mehr erreichen – auch nicht mit gültigen Daten. */
    static void staleServerCannotTouchTheCore() throws Exception {
        start(true);
        String code = openWindow();
        long h = TestInitiator.pairStart(Core.identityGenerate(), code, now(), 60_000);
        byte[] m0 = TestInitiator.m0(h);
        synchronized (ChannelServer.CORE) { ChannelServer.coreOwner = null; }
        check(raw("/p1/pair/m0", m0).code == 400, "stale server must not reach the core");
        synchronized (ChannelServer.CORE) { ChannelServer.coreOwner = server; }
        check(raw("/p1/pair/m0", m0).code == 200, "control: the owning server answers the same M0");
        TestInitiator.free(h);
        passed++;
    }

    public static void main(String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> { e.printStackTrace(); System.exit(1); });
        strictParser();
        downloadCommands();
        commandsReachTheDevice();
        statusJsonIsFixedAndSanitised();
        pairingAndControlCommands();
        downloadCommandsOverTheChannel();
        wrongCodeBurnsTheWindow();
        gateRefusesWebLikeRequests();
        revokeOneKeepsTheOthers();
        restartKeepsRegistrationsButNotSessions();
        unsavedPairingIsNeverConfirmed();
        staleServerCannotTouchTheCore();
        if (server != null) server.stop();
        System.out.println("ChannelChecks: " + passed + " checks passed");
        System.exit(0);
    }
}
