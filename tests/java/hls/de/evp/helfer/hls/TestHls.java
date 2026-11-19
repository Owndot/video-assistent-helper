package de.evp.helfer.hls;

import com.sun.net.httpserver.HttpServer;
import de.evp.helfer.net.LocalTestClients;
import de.evp.helfer.net.SafeHttpClient;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * P4a JVM-Tests: strenger Parser (Profil "unverschlüsseltes VOD"), RFC-3986-Auflösung gegen die finale Adresse, Plan
 * mit Budgets und Fingerabdruck, DASH nur erkennen, und Abrufe über den echten SafeHttpClient gegen einen lokalen
 * Server (keine Cookies/Referer/Authorization, Weiterleitungen, gesperrte Ziele, Größen, Abbruch).
 *   java TestHls FIXTURES_DIR
 */
public final class TestHls {
    static int passed, failed;

    static void check(String name, boolean ok, Object detail) {
        if (ok) { passed++; System.out.println("OK   " + name); }
        else { failed++; System.out.println("FAIL " + name + " – " + detail); }
    }

    static String reject(String playlist) {
        try {
            HlsPlaylist.parse(playlist.getBytes(StandardCharsets.UTF_8), "https://cdn.example/a/b/list.m3u8?tok=SECRET");
            return "accepted";
        } catch (HlsException e) {
            return e.reason;
        }
    }

    static final String MEDIA_HEAD = "#EXTM3U\n#EXT-X-VERSION:6\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n";
    static String media(String body) { return MEDIA_HEAD + body + "#EXTINF:4.0,\ns1.ts\n#EXT-X-ENDLIST\n"; }

    public static void main(String[] args) throws Exception {
        File fx = new File(args[0]);
        uris();
        negatives();
        fixtures(fx);
        network(fx);
        System.out.println("ERGEBNIS: " + passed + " von " + (passed + failed));
        System.exit(failed == 0 ? 0 : 1);
    }

    static void uris() throws Exception {
        String base = "http://a/b/c/d;p?q";
        // RFC 3986 5.4.1 (normale Beispiele) und 5.4.2 (Auswahl)
        String[][] t = {
            {"g", "http://a/b/c/g"}, {"./g", "http://a/b/c/g"}, {"g/", "http://a/b/c/g/"}, {"/g", "http://a/g"},
            {"//g/x", "http://g/x"}, {"?y", "http://a/b/c/d;p?y"}, {"g?y", "http://a/b/c/g?y"}, {";x", "http://a/b/c/;x"},
            {"g;x", "http://a/b/c/g;x"}, {".", "http://a/b/c/"}, {"./", "http://a/b/c/"}, {"..", "http://a/b/"},
            {"../g", "http://a/b/g"}, {"../..", "http://a/"}, {"../../g", "http://a/g"}, {"../../../g", "http://a/g"},
            {"/./g", "http://a/g"}, {"g.", "http://a/b/c/g."}, {"g..", "http://a/b/c/g.."}, {"./../g", "http://a/b/g"},
            {"g/../h", "http://a/b/c/h"}, {"https://other.example/x.ts", "https://other.example/x.ts"},
        };
        boolean all = true;
        StringBuilder bad = new StringBuilder();
        for (String[] c : t) {
            String r = HlsUri.resolve(base, c[0]);
            if (!r.equals(c[1])) { all = false; bad.append(c[0]).append("→").append(r).append(' '); }
        }
        check("URI: RFC-3986-Beispiele (5.4.1/5.4.2) aufgelöst", all, bad);
        check("URI: Segment erbt das Query-Token der Liste nicht",
            HlsUri.resolve("https://cdn.example/v/list.m3u8?tok=SECRET", "seg1.ts").equals("https://cdn.example/v/seg1.ts"), "");
        check("URI: eigenes Query des Segments bleibt exakt",
            HlsUri.resolve("https://cdn.example/v/list.m3u8?tok=A", "seg1.ts?sig=x%2Fy&a=1&a=2").equals("https://cdn.example/v/seg1.ts?sig=x%2Fy&a=1&a=2"), "");
        String[] badRefs = {"file:///etc/passwd", "data:video/mp2t;base64,AAAA", "javascript:alert(1)", "ftp://h/x.ts",
            "https://user:pw@h/x.ts", "seg.ts#frag", "seg 1.ts", "segä.ts", "//", "http:x.ts"};
        List<String> accepted = new ArrayList<>();
        for (String r : badRefs) {
            try { HlsUri.resolve("https://cdn.example/v/list.m3u8", r); accepted.add(r); } catch (HlsException e) { /* erwartet */ }
        }
        check("URI: Schemata außer http(s), Userinfo, Fragment, Leer-/Nicht-ASCII-Zeichen abgelehnt", accepted.isEmpty(), accepted);
        StringBuilder longRef = new StringBuilder("a");
        while (longRef.length() <= HlsUri.MAX_URI_CHARS) longRef.append('a');
        boolean longRejected = false;
        try { HlsUri.resolve("https://cdn.example/v/l.m3u8", longRef.toString()); } catch (HlsException e) { longRejected = "uri-length".equals(e.reason); }
        check("URI: überlange Adresse abgelehnt", longRejected, "");
    }

    static void negatives() {
        Object[][] cases = {
            {"live (kein ENDLIST)", MEDIA_HEAD + "#EXTINF:4.0,\ns1.ts\n", "live"},
            {"EVENT ohne ENDLIST", "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-PLAYLIST-TYPE:EVENT\n#EXTINF:4,\na.ts\n", "live"},
            {"AES-128", media("#EXT-X-KEY:METHOD=AES-128,URI=\"k.key\"\n"), "encrypted"},
            {"SAMPLE-AES", media("#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://x\",KEYFORMAT=\"com.apple.streamingkeydelivery\"\n"), "encrypted"},
            {"unbekannte Schlüsselmethode", media("#EXT-X-KEY:METHOD=FOO-CTR,URI=\"k\"\n"), "encrypted"},
            {"METHOD=NONE mit Zusatzattributen", media("#EXT-X-KEY:METHOD=NONE,URI=\"k\"\n"), "encrypted"},
            {"SESSION-KEY in Masterliste", "#EXTM3U\n#EXT-X-SESSION-KEY:METHOD=AES-128,URI=\"k\"\n#EXT-X-STREAM-INF:BANDWIDTH=1\nv.m3u8\n", "encrypted"},
            {"Discontinuity", media("#EXTINF:4.0,\ns0.ts\n#EXT-X-DISCONTINUITY\n"), "discontinuity"},
            {"Byte-Range", media("#EXT-X-BYTERANGE:1000@0\n"), "byterange"},
            {"Init mit Byte-Range", MEDIA_HEAD + "#EXT-X-MAP:URI=\"i.mp4\",BYTERANGE=\"700@0\"\n#EXTINF:4,\ns.m4s\n#EXT-X-ENDLIST\n", "byterange"},
            {"Init-Wechsel", MEDIA_HEAD + "#EXT-X-MAP:URI=\"i.mp4\"\n#EXTINF:4,\na.m4s\n#EXT-X-MAP:URI=\"j.mp4\"\n#EXTINF:4,\nb.m4s\n#EXT-X-ENDLIST\n", "init-change"},
            {"LL-HLS PART", media("#EXT-X-PART:DURATION=1.0,URI=\"p.ts\"\n"), "low-latency"},
            {"LL-HLS SERVER-CONTROL", "#EXTM3U\n#EXT-X-SERVER-CONTROL:CAN-BLOCK-RELOAD=YES\n#EXT-X-TARGETDURATION:4\n#EXTINF:4,\na.ts\n#EXT-X-ENDLIST\n", "low-latency"},
            {"PRELOAD-HINT", media("#EXT-X-PRELOAD-HINT:TYPE=PART,URI=\"p\"\n"), "low-latency"},
            {"GAP", media("#EXT-X-GAP\n"), "gap"},
            {"DATERANGE (Interstitial)", media("#EXT-X-DATERANGE:ID=\"ad\",CLASS=\"com.apple.hls.interstitial\",START-DATE=\"2020-01-01T00:00:00Z\"\n"), "daterange"},
            {"I-Frame-only", "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-I-FRAMES-ONLY\n#EXTINF:4,\na.ts\n#EXT-X-ENDLIST\n", "iframes-only"},
            {"Variablen (DEFINE)", "#EXTM3U\n#EXT-X-DEFINE:NAME=\"x\",VALUE=\"y\"\n#EXT-X-TARGETDURATION:4\n#EXTINF:4,\n{$x}.ts\n#EXT-X-ENDLIST\n", "variables"},
            {"Content-Steering", "#EXTM3U\n#EXT-X-CONTENT-STEERING:SERVER-URI=\"s.json\"\n#EXT-X-STREAM-INF:BANDWIDTH=1\nv.m3u8\n", "content-steering"},
            {"unbekanntes Tag", media("#EXT-X-CUE-OUT:30\n"), "unknown-tag"},
            {"Master- und Medientags gemischt", "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-STREAM-INF:BANDWIDTH=1\nv.m3u8\n#EXTINF:4,\na.ts\n#EXT-X-ENDLIST\n", "mixed-playlist"},
            {"fehlendes #EXTM3U", "#EXT-X-TARGETDURATION:4\n#EXTINF:4,\na.ts\n#EXT-X-ENDLIST\n", "not-m3u8"},
            {"BOM", "﻿" + media(""), "encoding"},
            {"NUL-Byte", media("#EXTINF:4,\na\u0000.ts\n"), "encoding"},
            {"EXTINF negativ", MEDIA_HEAD + "#EXTINF:-4,\na.ts\n#EXT-X-ENDLIST\n", "extinf"},
            {"EXTINF NaN", MEDIA_HEAD + "#EXTINF:NaN,\na.ts\n#EXT-X-ENDLIST\n", "extinf"},
            {"EXTINF länger als TARGETDURATION", MEDIA_HEAD + "#EXTINF:9.0,\na.ts\n#EXT-X-ENDLIST\n", "extinf-target"},
            {"EXTINF ohne URI", MEDIA_HEAD + "#EXTINF:4,\n#EXT-X-ENDLIST\n", "extinf-without-uri"},
            {"URI ohne EXTINF", MEDIA_HEAD + "a.ts\n#EXT-X-ENDLIST\n", "uri-without-tag"},
            {"doppelte TARGETDURATION", MEDIA_HEAD + "#EXT-X-TARGETDURATION:4\n#EXTINF:4,\na.ts\n#EXT-X-ENDLIST\n", "targetduration"},
            {"Segment nach ENDLIST", MEDIA_HEAD + "#EXTINF:4,\na.ts\n#EXT-X-ENDLIST\n#EXTINF:4,\nb.ts\n", "after-endlist"},
            {"Segment-URI file:", MEDIA_HEAD + "#EXTINF:4,\nfile:///sdcard/x.ts\n#EXT-X-ENDLIST\n", "uri-scheme"},
            {"STREAM-INF ohne URI", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\n", "stream-inf-without-uri"},
            {"STREAM-INF ohne BANDWIDTH", "#EXTM3U\n#EXT-X-STREAM-INF:RESOLUTION=1x1\nv.m3u8\n", "bandwidth"},
            {"doppeltes Attribut", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1,BANDWIDTH=2\nv.m3u8\n", "attributes-duplicate"},
            {"kaputte Attributliste", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1,CODECS=\"avc1\nv.m3u8\n", "attributes"},
            {"unbekannte AUDIO-Gruppe", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1,AUDIO=\"x\"\nv.m3u8\n", "audio-group"},
            {"alternative Videospuren", "#EXTM3U\n#EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID=\"v\",NAME=\"a\",URI=\"a.m3u8\"\n#EXT-X-STREAM-INF:BANDWIDTH=1\nv.m3u8\n", "video-rendition"},
        };
        for (Object[] c : cases) {
            String r = reject((String) c[1]);
            check("Abgelehnt: " + c[0] + " (" + c[2] + ")", c[2].equals(r), r);
        }
        StringBuilder big = new StringBuilder(MEDIA_HEAD);
        while (big.length() <= HlsPlaylist.MAX_MANIFEST_BYTES) big.append("# Kommentar Kommentar Kommentar Kommentar Kommentar\n");
        check("Abgelehnt: Manifest über 2 MiB", "manifest-size".equals(reject(big.toString())), "");
        StringBuilder many = new StringBuilder("#EXTM3U\n");
        for (int i = 0; i <= HlsPlaylist.MAX_VARIANTS; i++) many.append("#EXT-X-STREAM-INF:BANDWIDTH=").append(1000 + i).append("\nv").append(i).append(".m3u8\n");
        check("Abgelehnt: mehr als 64 Varianten", "variants-limit".equals(reject(many.toString())), "");
        StringBuilder segs = new StringBuilder(MEDIA_HEAD.replace("TARGETDURATION:4", "TARGETDURATION:1"));
        for (int i = 0; i <= HlsPlaylist.MAX_SEGMENTS; i++) segs.append("#EXTINF:1,\n").append(i).append(".ts\n");
        segs.append("#EXT-X-ENDLIST\n");
        check("Abgelehnt: mehr als 20.000 Segmente in einer Liste", "segments-limit".equals(reject(segs.toString())), "");
        StringBuilder line = new StringBuilder(MEDIA_HEAD + "#");
        while (line.length() < HlsPlaylist.MAX_LINE_CHARS + 200) line.append('x');
        check("Abgelehnt: überlange Zeile", "line-length".equals(reject(line + "\n")), "");
        check("Abgelehnt: ungültiges UTF-8", rejectBytes(new byte[] {'#', 'E', 'X', 'T', 'M', '3', 'U', '\n', (byte) 0xC3, (byte) 0x28}).equals("encoding"), "");
        check("Erlaubt: METHOD=NONE ohne weitere Attribute", "accepted".equals(reject(media("#EXT-X-KEY:METHOD=NONE\n"))), "");
        check("Erlaubt: Kommentare, PROGRAM-DATE-TIME, CRLF", "accepted".equals(reject(media("# hi\r\n#EXT-X-PROGRAM-DATE-TIME:2020-01-01T00:00:00Z\r\n"))), "");
        check("Codecs: H.264 + AAC-LC ok; HEVC, HE-AAC, AC-3, AV1 abgelehnt; Opus nur mit Nachweis",
            "ok".equals(HlsPlaylist.codecClass("avc1.4d401f,mp4a.40.2", false)) && "codec".equals(HlsPlaylist.codecClass("hvc1.1.6.L93.B0,mp4a.40.2", false))
                && "codec".equals(HlsPlaylist.codecClass("avc1.4d401f,mp4a.40.5", false)) && "codec".equals(HlsPlaylist.codecClass("avc1.4d401f,ac-3", false))
                && "codec".equals(HlsPlaylist.codecClass("av01.0.04M.08", false)) && "codec".equals(HlsPlaylist.codecClass("avc1.4d401f,opus", false))
                && "ok".equals(HlsPlaylist.codecClass("avc1.4d401f,opus", true)) && "unknown".equals(HlsPlaylist.codecClass(null, false)), "");
        check("DASH nur erkannt (ohne XML-Parser), HLS erkannt",
            "dash".equals(HlsPlan.classify("application/dash+xml", "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><MPD>".getBytes(StandardCharsets.ISO_8859_1)))
                && "dash".equals(HlsPlan.classify("text/xml", "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\">".getBytes(StandardCharsets.ISO_8859_1)))
                && "hls".equals(HlsPlan.classify("text/plain", "#EXTM3U\n".getBytes(StandardCharsets.ISO_8859_1)))
                && "unknown".equals(HlsPlan.classify("video/mp4", new byte[] {0, 0, 0, 24, 'f', 't', 'y', 'p'})), "");
    }

    static String rejectBytes(byte[] b) {
        try { HlsPlaylist.parse(b, "https://cdn.example/l.m3u8"); return "accepted"; } catch (HlsException e) { return e.reason; }
    }

    static HlsPlaylist.Parsed parseFile(File f, String url) throws Exception {
        return HlsPlaylist.parse(Files.readAllBytes(f.toPath()), url);
    }

    static void fixtures(File fx) throws Exception {
        String[][] expect = {   // Name, Optionen, Videosegmente, Audiosegmente (0 = Ton in der Variante), Init
            {"ts_muxed", "1", "5", "0", "no"}, {"fmp4_muxed", "1", "5", "0", "yes"}, {"ts_sep", "1", "5", "6", "no"},
            {"fmp4_sep", "1", "5", "6", "yes"}, {"ts_sep_offset", "1", "5", "5", "no"}, {"fmp4_sep_offset", "1", "5", "5", "yes"},
            {"ts_multi", "2", "5", "6", "no"},
        };
        for (String[] e : expect) {
            File dir = new File(fx, e[0]);
            String base = "https://cdn.example/fx/" + e[0] + "/";
            HlsPlaylist.Parsed p = parseFile(new File(dir, "master.m3u8"), base + "master.m3u8?tok=SECRET");
            List<HlsPlan.Option> opts = HlsPlan.options(p.master, new byte[] {1, 2, 3}, false);
            HlsPlan.Option o = opts.get(0);
            String vName = o.variant.uri.substring(base.length());
            HlsPlaylist.Media v = parseFile(new File(dir, vName), o.variant.uri).media;
            HlsPlaylist.Media a = null;
            if (o.audio != null) a = parseFile(new File(dir, o.audio.uri.substring(base.length())), o.audio.uri).media;
            HlsPlan.Plan plan = HlsPlan.build(v, a, o.variant.bandwidth);
            boolean ok = opts.size() == Integer.parseInt(e[1]) && v.segments.size() == Integer.parseInt(e[2])
                && (a == null ? 0 : a.segments.size()) == Integer.parseInt(e[3]) && (v.mapUri != null) == "yes".equals(e[4])
                && Math.abs(v.totalSeconds - 20.0) < 0.01 && plan.fingerprint.length() == 64
                && plan.videoResources.stream().noneMatch(x -> x.contains("tok=")) && !o.describe().contains("cdn.example")
                && !o.describe().contains(".m3u8");
            check("Fixture " + e[0] + ": Optionen, Segmente, Init, Dauer, keine Token-Vererbung, Option ohne Adresse", ok,
                opts.size() + " opts, v=" + v.segments.size() + " a=" + (a == null ? 0 : a.segments.size()) + " map=" + v.mapUri + " " + o.describe());
        }
        HlsPlaylist.Master m = parseFile(new File(fx, "ts_multi/master.m3u8"), "https://cdn.example/m.m3u8").master;
        List<HlsPlan.Option> o1 = HlsPlan.options(m, new byte[] {1}, false), o2 = HlsPlan.options(m, new byte[] {2}, false);
        check("Qualitätsauswahl: höchste zuerst; IDs je Plan-Generation verschieden",
            o1.get(0).variant.height == 180 && o1.get(1).variant.height == 90 && !o1.get(0).id.equals(o2.get(0).id), o1.get(0).describe());
        HlsPlaylist.Master op = parseFile(new File(fx, "fmp4_opus_sep/master.m3u8"), "https://cdn.example/o.m3u8").master;
        // ffmpeg schreibt hier kein CODECS: die Option ist "unknown" – der Remuxer muss die tatsächlichen Spuren prüfen
        // (Opus ohne Nachweis ablehnen); mit angegebenem Opus-CODECS wird die Variante gar nicht angeboten.
        String opusMaster = "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"x\",URI=\"a.m3u8\"\n#EXT-X-STREAM-INF:BANDWIDTH=1,CODECS=\"avc1.4d400c,opus\",AUDIO=\"a\"\nv.m3u8\n";
        boolean opusRejected;
        try { HlsPlan.options(HlsPlaylist.parse(opusMaster.getBytes(StandardCharsets.UTF_8), "https://cdn.example/o.m3u8").master, new byte[] {1}, false); opusRejected = false; }
        catch (HlsException e) { opusRejected = "no-supported-variant".equals(e.reason); }
        check("Opus: angegebenes Opus ohne Nachweis nicht angeboten; fehlendes CODECS → Klasse 'unknown' (Prüfung nach Extraktion)",
            opusRejected && "unknown".equals(HlsPlan.options(op, new byte[] {1}, false).get(0).codecClass), op.variants.get(0).codecs);
        HlsPlaylist.Media v20 = parseFile(new File(fx, "ts_sep/v0.m3u8"), "https://cdn.example/v0.m3u8").media;
        String shortAudio = MEDIA_HEAD + "#EXTINF:4,\na.ts\n#EXT-X-ENDLIST\n";
        boolean mismatch;
        try { HlsPlan.build(v20, HlsPlaylist.parse(shortAudio.getBytes(StandardCharsets.UTF_8), "https://cdn.example/a.m3u8").media, 1000); mismatch = false; }
        catch (HlsException e) { mismatch = "av-duration-mismatch".equals(e.reason); }
        check("Plan: Ton deutlich kürzer als Bild → abgelehnt (kein stilles Teil-Ergebnis)", mismatch, "");
        boolean tooBig;
        try { HlsPlan.build(v20, null, Integer.MAX_VALUE); tooBig = false; } catch (HlsException e) { tooBig = false; }
        StringBuilder longList = new StringBuilder(MEDIA_HEAD.replace("TARGETDURATION:4", "TARGETDURATION:10"));
        for (int i = 0; i < 17000; i++) longList.append("#EXTINF:10,\n").append(i).append(".ts\n");
        longList.append("#EXT-X-ENDLIST\n");
        HlsPlaylist.Media longMedia = HlsPlaylist.parse(longList.toString().getBytes(StandardCharsets.UTF_8), "https://cdn.example/l.m3u8").media;
        try { HlsPlan.build(longMedia, null, 20_000_000); tooBig = false; } catch (HlsException e) { tooBig = "size-limit".equals(e.reason); }
        boolean segLimit;
        try { HlsPlan.build(longMedia, longMedia, 1000); segLimit = false; } catch (HlsException e) { segLimit = "segments-limit".equals(e.reason); }
        check("Plan: geschätzte 16-GiB-Grenze und 20.000 Segmente inkl. Audio greifen", tooBig && segLimit, tooBig + " " + segLimit);
    }

    /** Lokaler Server: liefert Fixtures, protokolliert sicherheitsrelevante Kopfzeilen, Sonderpfade für Negativtests. */
    static void network(File fx) throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        Map<String, String> headerLeaks = new ConcurrentHashMap<>();
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = srv.getAddress().getPort();
        srv.createContext("/", x -> {
            String path = x.getRequestURI().getRawPath(), q = x.getRequestURI().getRawQuery();
            seen.add(path + (q == null ? "" : "?" + q));
            for (String h : new String[] {"Cookie", "Authorization", "Referer", "Origin"})
                if (x.getRequestHeaders().containsKey(h)) headerLeaks.put(h, path);
            byte[] body = null;
            int code = 200;
            if (path.equals("/start/master.m3u8")) {   // Weiterleitung: Auflösung muss gegen die finale Adresse erfolgen
                x.getResponseHeaders().set("Location", "/real/ts_sep/master.m3u8");
                x.sendResponseHeaders(302, -1); x.close(); return;
            }
            if (path.equals("/evil/master.m3u8")) body = ("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nhttp://10.0.0.1:" + port + "/v.m3u8\n").getBytes(StandardCharsets.UTF_8);
            if (path.equals("/redirect-private/seg.ts")) { x.getResponseHeaders().set("Location", "http://169.254.169.254/latest"); x.sendResponseHeaders(302, -1); x.close(); return; }
            if (path.equals("/redirect-file/seg.ts")) { x.getResponseHeaders().set("Location", "file:///etc/passwd"); x.sendResponseHeaders(302, -1); x.close(); return; }
            if (path.equals("/big/seg.ts")) body = new byte[300_000];
            if (path.equals("/chunked-plus1/seg.ts")) {   // genau ein Byte zu viel, ohne Content-Length
                x.sendResponseHeaders(200, 0);
                try (OutputStream o = x.getResponseBody()) { o.write(new byte[100_001]); } catch (IOException ignored) { }
                x.close(); return;
            }
            if (path.equals("/chunked-big/seg.ts")) {   // ohne Content-Length: Grenze muss beim Lesen greifen
                x.sendResponseHeaders(200, 0);
                try (OutputStream o = x.getResponseBody()) { for (int i = 0; i < 30; i++) o.write(new byte[10_000]); } catch (IOException ignored) { }
                x.close(); return;
            }
            if (path.equals("/liar/seg.ts")) { x.sendResponseHeaders(200, 5000); x.getResponseBody().write(new byte[100]); x.close(); return; }
            if (path.equals("/slow/seg.ts")) {
                x.sendResponseHeaders(200, 2_000_000);
                try (OutputStream o = x.getResponseBody()) { for (int i = 0; i < 200; i++) { o.write(new byte[10_000]); o.flush(); Thread.sleep(20); } }
                catch (Exception ignored) { }
                x.close(); return;
            }
            if (path.equals("/gone/seg.ts")) code = 404;
            if (body == null && code == 200 && path.startsWith("/real/")) {
                File f = new File(fx, path.substring("/real/".length()));
                if (f.isFile() && f.getCanonicalPath().startsWith(fx.getCanonicalPath())) body = Files.readAllBytes(f.toPath()); else code = 404;
            }
            if (body == null) { x.sendResponseHeaders(code == 200 ? 404 : code, -1); x.close(); return; }
            x.sendResponseHeaders(200, body.length);
            try (OutputStream o = x.getResponseBody()) { o.write(body); }
        });
        srv.start();
        File work = Files.createTempDirectory("p4a-net").toFile();
        try {
            SafeHttpClient http = LocalTestClients.loopback(port);
            HlsFetch f = new HlsFetch(http, new SafeHttpClient.Cancellation());
            String b = "http://127.0.0.1:" + port;
            HlsFetch.Text t = f.manifest(b + "/start/master.m3u8?tok=SECRET");
            check("Netz: finale Adresse nach Weiterleitung übernommen", t.finalUrl.equals(b + "/real/ts_sep/master.m3u8"), t.finalUrl);
            HlsPlaylist.Master m = HlsPlaylist.parse(t.body, t.finalUrl).master;
            HlsPlan.Option o = HlsPlan.options(m, new byte[] {9}, false).get(0);
            check("Netz: relative Medienlisten gegen die finale Adresse aufgelöst, ohne geerbtes Token",
                o.variant.uri.equals(b + "/real/ts_sep/v0.m3u8") && o.audio.uri.equals(b + "/real/ts_sep/v1.m3u8"), o.variant.uri);
            HlsFetch.Text vt = f.manifest(o.variant.uri), at = f.manifest(o.audio.uri);
            HlsPlan.Plan plan = HlsPlan.build(HlsPlaylist.parse(vt.body, vt.finalUrl).media, HlsPlaylist.parse(at.body, at.finalUrl).media, o.variant.bandwidth);
            long total = 0;
            int i = 0;
            List<String> all = new ArrayList<>(plan.videoResources);
            all.addAll(plan.audioResources);
            for (String u : all) total += f.resource(u, new File(work, "r" + (i++)), HlsPlan.MAX_SEGMENT_BYTES);
            long expected = 0;
            for (File x : new File(fx, "ts_sep").listFiles()) if (x.getName().endsWith(".ts")) expected += x.length();
            check("Netz: alle 11 Ressourcen einzeln über SafeHttpClient geladen, Größe exakt", total == expected && all.size() == 11, total + " vs " + expected);
            check("Netz: kein Cookie, Authorization, Referer oder Origin an irgendeiner Anfrage", headerLeaks.isEmpty(), headerLeaks);
            check("Netz: das Token der Startadresse erscheint in keiner Folgeanfrage",
                seen.stream().filter(s -> s.contains("tok=")).count() == 1, seen);
            expectFail("Netz: Medienliste auf privatem Netz (10.0.0.1) gesperrt", () -> {
                HlsFetch.Text e = f.manifest(b + "/evil/master.m3u8");
                f.manifest(HlsPlaylist.parse(e.body, e.finalUrl).master.variants.get(0).uri);
            }, "NetBlocked");
            expectFail("Netz: Weiterleitung auf Link-Local-Metadatenadresse gesperrt", () -> f.resource(b + "/redirect-private/seg.ts", new File(work, "x1"), 1 << 20), "NetBlocked");
            expectFail("Netz: Weiterleitung auf file: gesperrt", () -> f.resource(b + "/redirect-file/seg.ts", new File(work, "x2"), 1 << 20), "NetBlocked");
            expectFail("Netz: Segment über der Grenze abgebrochen", () -> f.resource(b + "/big/seg.ts", new File(work, "x3"), 100_000), "segment-size");
            expectFail("Netz: Segment ohne Content-Length über der Grenze abgebrochen", () -> f.resource(b + "/chunked-big/seg.ts", new File(work, "x3b"), 100_000), "segment-size");
            expectFail("Netz: genau ein Byte über der Grenze (ohne Content-Length) abgelehnt", () -> f.resource(b + "/chunked-plus1/seg.ts", new File(work, "x3c"), 100_000), "segment-size");
            expectFail("Netz: angekündigte Länge ≠ geliefert", () -> f.resource(b + "/liar/seg.ts", new File(work, "x4"), 1 << 20), "io");
            expectFail("Netz: HTTP 404 als neutraler Grund, keine Wiederholung", () -> f.resource(b + "/gone/seg.ts", new File(work, "x5"), 1 << 20), "http_404");
            check("Netz: genau ein Abruf des fehlenden Segments", seen.stream().filter(s -> s.startsWith("/gone/")).count() == 1, seen);
            SafeHttpClient.Cancellation c = new SafeHttpClient.Cancellation();
            HlsFetch fc = new HlsFetch(http, c);
            long t0 = System.nanoTime();
            new Thread(() -> { try { Thread.sleep(300); } catch (InterruptedException ignored) { } c.cancel(); }).start();
            expectFail("Abbruch: laufender Segmentabruf endet sofort mit 'cancelled'", () -> fc.resource(b + "/slow/seg.ts", new File(work, "x6"), 4_000_000), "cancelled");
            check("Abbruch: innerhalb von 2 s statt Vollabruf (4 s)", (System.nanoTime() - t0) / 1_000_000 < 2000, (System.nanoTime() - t0) / 1_000_000 + " ms");
            expectFail("Strenge Produktionsregeln: Loopback-Fixture ist gesperrt", () -> new HlsFetch(LocalTestClients.strict(), null).manifest(b + "/real/ts_sep/master.m3u8"), "NetBlocked");
        } finally {
            srv.stop(0);
            for (File x : work.listFiles()) x.delete();
            work.delete();
        }
    }

    interface Body { void run() throws Exception; }

    static void expectFail(String name, Body b, String want) {
        try {
            b.run();
            check(name, false, "kein Fehler");
        } catch (HlsException e) {
            check(name, e.reason.equals(want), e.reason);
        } catch (Exception e) {
            check(name, e.getClass().getSimpleName().contains(want), e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
