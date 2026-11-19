package de.evp.helfer;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Steuerbefehle des sicheren Kanals (nur Steuerung, keine URLs, Cookies oder Medien).
 *
 * Erlaubt sind genau diese Anfragen (JSON im entschlüsselten Steuer-Record, höchstens 256 Byte):
 *   {"op":"status"}  {"op":"beat"}  {"op":"end"}  {"op":"vol"}  {"op":"vol","v":0…1}  {"op":"bright","v":0.004…1}
 * Alles andere – weitere Felder, doppelte Felder, andere Befehle, falsche Typen, Werte außerhalb des Bereichs,
 * ungültiges UTF-8 – wird abgelehnt (nicht gekürzt, nicht geraten). Die Antwort ist die bisherige Status-JSON
 * des Helpers bzw. {"ok":true} für "end"; der Player in Kadri bleibt dadurch unverändert.
 *
 * HLS (P4b, nur mit {@link HlsControl}; alte Helfer lehnen ab):
 *   {"op":"hlsplan","v":1,"url":"…","bind":{"player":"…","gen":N,"rev":N}}
 *   {"op":"hlsstart","v":1,"id":"<32 hex>","planId":"<32 hex>","option":"<24 hex>","bind":{…}[,"name":"…"]}
 *
 * Download-Befehle (Phase C2, nur wenn ein {@link DownloadControl} angebunden ist, sonst abgelehnt):
 *   {"op":"dlstart","id":"<32 hex>","url":"http(s)://…"[,"name":"…"]}  {"op":"dlstatus","id":"…"}  {"op":"dlcancel","id":"…"}
 * dlstart darf bis {@link #MAX_DOWNLOAD_REQUEST} Byte groß sein (die URL); alle anderen Befehle bleiben bei
 * {@link #MAX_REQUEST}. Keine Header, Cookies oder Zugangsdaten: jedes weitere Feld wird abgelehnt. Die URL nur aus
 * RFC-3986-Zeichen (keine Leerzeichen, Anführungszeichen, Backslashes, Steuerzeichen), der Name ohne
 * Anführungszeichen, Backslashes und Steuerzeichen. Die Antworten nennen nie die URL.
 *
 * Ohne Android-Typen, damit dieselbe Prüfung auch auf dem Desktop-JVM getestet wird.
 */
public final class ControlCommands {
    /** Was die Befehle am Gerät auslösen (HelferService; im Test eine Nachbildung). */
    public interface Device {
        void bright(float level);   // Helligkeit setzen und Lebenszeichen
        void vol(float level);      // Medienlautstärke setzen
        void beat();                // Lebenszeichen (Watchdog: Helligkeit sonst nach 30 s zurück)
        void end();                 // Helligkeit sofort zurückgeben
        String status();            // bisherige Status-JSON
    }

    /** Direkte Downloads (HelferService: Mp4Downloads). Antworten sind JSON ohne URL. */
    public interface DownloadControl {
        String start(String id, String url, String name);
        String status(String id);
        String cancel(String id);
    }

    /**
     * P4b: HLS-Downloads (nur wenn angebunden, sonst abgelehnt). {@code peer}: Kennung der authentifizierten
     * Kanal-Sitzung (vom Kanal, nie aus der Anfrage); {@code bind}: Kadris Bindung "player|gen|rev". Antworten ohne URL.
     */
    public interface HlsControl {
        String plan(byte[] peer, String bind, String url);
        String start(byte[] peer, String bind, String id, String planId, String option, String name);
        boolean knows(String id);
        String status(String id);
        String cancel(String id);
    }

    public static final int MAX_REQUEST = 256;
    public static final int MAX_DOWNLOAD_REQUEST = 4096;
    public static final int MAX_URL = 2048;
    public static final int MAX_NAME = 120;
    public static final String REJECTED = "{\"ok\":false}";

    private ControlCommands() {}

    /** Antwort auf eine Anfrage; ungültige Anfragen → {@link #REJECTED}, ohne das Gerät zu berühren. */
    public static String handle(byte[] request, Device device) { return handle(request, device, null); }

    /** Wie {@link #handle(byte[], Device)}; Download-Befehle nur mit {@code downloads} (sonst abgelehnt). */
    public static String handle(byte[] request, Device device, DownloadControl downloads) { return handle(request, device, downloads, null, null); }

    /**
     * Wie oben, zusätzlich HLS ({@code hls} null: hlsplan/hlsstart abgelehnt wie bei einem alten Helfer). dlstatus und
     * dlcancel gelten für beide Arten: eine Kennung gehört genau einer Art (eine HLS-Kennung startet nie einen MP4-Auftrag
     * und umgekehrt).
     */
    public static String handle(byte[] request, Device device, DownloadControl downloads, HlsControl hls, byte[] peer) {
        Request r;
        try { r = parse(request); }
        catch (IllegalArgumentException invalid) { return REJECTED; }
        if (r.op.startsWith("hls")) {
            if (hls == null || downloads == null || peer == null) return REJECTED;
            if (r.op.equals("hlsplan")) return hls.plan(peer, r.bind, r.url);
            if (hls.knows(r.id)) return hls.start(peer, r.bind, r.id, r.planId, r.option, r.name);
            if (!downloads.status(r.id).contains("\"state\":\"unknown\"")) return "{\"ok\":false,\"id\":\"" + r.id + "\",\"state\":\"rejected\",\"reason\":\"id_in_use\"}";
            return hls.start(peer, r.bind, r.id, r.planId, r.option, r.name);
        }
        if (r.op.startsWith("dl")) {
            if (downloads == null) return REJECTED;
            boolean isHls = hls != null && hls.knows(r.id);
            switch (r.op) {
                case "dlstart": return isHls ? hls.status(r.id) : downloads.start(r.id, r.url, r.name);
                case "dlstatus": return isHls ? hls.status(r.id) : downloads.status(r.id);
                case "dlcancel": return isHls ? hls.cancel(r.id) : downloads.cancel(r.id);
                default: return REJECTED;   // unerreichbar
            }
        }
        switch (r.op) {
            case "status": return device.status();
            case "beat": device.beat(); return device.status();
            case "end": device.end(); return "{\"ok\":true}";
            case "vol": if (r.hasValue) device.vol((float) r.value); return device.status();
            case "bright": device.bright((float) r.value); return device.status();
            default: return REJECTED;   // unerreichbar: parse() lässt nur die obigen zu
        }
    }

    /**
     * Die bisherige Status-JSON (Feldnamen und Bedeutung unverändert, damit Kadris Player sie wie gewohnt liest).
     * Downloads, DASH und Proxy sind über den Kanal noch nicht verfügbar und stehen deshalb auf false.
     */
    public static String statusJson(String appVersion, boolean overlay, boolean write, int volStep, int volMax, float bright) {
        return statusJson(appVersion, overlay, write, volStep, volMax, bright, null);
    }

    /**
     * Wie oben; {@code hlsOpus} nicht null: der Helfer kann HLS-VOD speichern (P4b) – dann zusätzlich
     * {@code "hls":{"v":1,"profiles":["ts-avc-aac","fmp4-avc-aac"],"opus":<bool>}}. Alte Helfer melden nichts.
     */
    public static String statusJson(String appVersion, boolean overlay, boolean write, int volStep, int volMax, float bright, Boolean hlsOpus) {
        String base = statusJsonBase(appVersion, overlay, write, volStep, volMax, bright);
        if (hlsOpus == null) return base;
        return base.substring(0, base.length() - 1) + ",\"hls\":{\"v\":1,\"profiles\":[\"ts-avc-aac\",\"fmp4-avc-aac\"],\"opus\":" + hlsOpus + "}}";
    }

    private static String statusJsonBase(String appVersion, boolean overlay, boolean write, int volStep, int volMax, float bright) {
        String app = appVersion == null ? "?" : appVersion.replaceAll("[^0-9A-Za-z.+-]", "");
        if (app.length() > 32) app = app.substring(0, 32);
        int max = Math.max(volMax, 0), step = Math.max(0, Math.min(volStep, max));
        float b = Float.isNaN(bright) ? 0f : Math.max(0f, Math.min(1f, bright));
        return String.format(java.util.Locale.US,
            "{\"ok\":true,\"v\":3,\"dl\":false,\"dash\":false,\"proxy\":false,\"app\":\"%s\",\"overlay\":%b,\"write\":%b,\"vol\":%.4f,\"volStep\":%d,\"volMax\":%d,\"bright\":%.4f}",
            app, overlay, write, max > 0 ? (float) step / max : 0f, step, max, b);
    }

    static final class Request {
        final String op; final boolean hasValue; final double value;
        final String id, url, name;   // nur Download-Befehle
        String planId, option, bind;  // nur HLS
        Request(String op, boolean hasValue, double value) { this(op, hasValue, value, null, null, null); }
        Request(String op, boolean hasValue, double value, String id, String url, String name) {
            this.op = op; this.hasValue = hasValue; this.value = value; this.id = id; this.url = url; this.name = name;
        }
    }

    /** Strenge Prüfung; wirft IllegalArgumentException bei jeder Abweichung. */
    static Request parse(byte[] raw) {
        if (raw == null || raw.length == 0 || raw.length > MAX_DOWNLOAD_REQUEST) throw new IllegalArgumentException("size");
        String s;
        try {
            s = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException e) { throw new IllegalArgumentException("utf-8"); }
        Parser p = new Parser(s);
        String op = null; boolean hasValue = false; double value = 0;
        String id = null, url = null, name = null, planId = null, option = null, bind = null;
        p.expect('{');
        boolean first = true;
        while (true) {
            p.ws();
            if (p.peek() == '}') { if (first) throw new IllegalArgumentException("empty"); p.pos++; break; }
            if (!first) { p.expect(','); p.ws(); }
            String key = p.key();
            p.ws(); p.expect(':'); p.ws();
            if ("op".equals(key)) {
                if (op != null) throw new IllegalArgumentException("duplicate op");
                op = p.string();
            } else if ("v".equals(key)) {
                if (hasValue) throw new IllegalArgumentException("duplicate v");
                value = p.number(); hasValue = true;
            } else if ("id".equals(key)) {
                if (id != null) throw new IllegalArgumentException("duplicate id");
                id = p.jobId();
            } else if ("url".equals(key)) {
                if (url != null) throw new IllegalArgumentException("duplicate url");
                url = p.url();
            } else if ("name".equals(key)) {
                if (name != null) throw new IllegalArgumentException("duplicate name");
                name = p.name();
            } else if ("planId".equals(key)) {
                if (planId != null) throw new IllegalArgumentException("duplicate planId");
                planId = p.hex(32);
            } else if ("option".equals(key)) {
                if (option != null) throw new IllegalArgumentException("duplicate option");
                option = p.hex(24);
            } else if ("bind".equals(key)) {
                if (bind != null) throw new IllegalArgumentException("duplicate bind");
                bind = p.bind();
            } else throw new IllegalArgumentException("field");
            first = false;
        }
        p.ws();
        if (p.pos != s.length()) throw new IllegalArgumentException("trailing");
        if (op == null) throw new IllegalArgumentException("op missing");
        if (op.equals("hlsplan") || op.equals("hlsstart")) {
            // Versioniert: genau v:1. hlsplan {op,v,url,bind}; hlsstart {op,v,id,planId,option,bind[,name]}
            if (!hasValue || value != 1.0 || bind == null) throw new IllegalArgumentException("hls fields");
            Request r;
            if (op.equals("hlsplan")) {
                if (url == null || id != null || name != null || planId != null || option != null) throw new IllegalArgumentException("hls fields");
                r = new Request(op, true, 1, null, url, null);
            } else {
                if (url != null || id == null || planId == null || option == null) throw new IllegalArgumentException("hls fields");
                if (raw.length > 1024) throw new IllegalArgumentException("size");
                r = new Request(op, true, 1, id, null, name);
            }
            r.planId = planId; r.option = option; r.bind = bind;
            return r;
        }
        if (planId != null || option != null || bind != null) throw new IllegalArgumentException("hls field");
        boolean download = op.equals("dlstart") || op.equals("dlstatus") || op.equals("dlcancel");
        if (!download && (id != null || url != null || name != null)) throw new IllegalArgumentException("download field");
        if (download) {
            if (hasValue || id == null) throw new IllegalArgumentException("download fields");
            if (op.equals("dlstart") ? url == null : (url != null || name != null)) throw new IllegalArgumentException("download fields");
            if (!op.equals("dlstart") && raw.length > MAX_REQUEST) throw new IllegalArgumentException("size");
            return new Request(op, false, 0, id, url, name);
        }
        if (raw.length > MAX_REQUEST) throw new IllegalArgumentException("size");
        switch (op) {
            case "status": case "beat": case "end":
                if (hasValue) throw new IllegalArgumentException("unexpected v");
                break;
            case "vol":
                if (hasValue && !(value >= 0.0 && value <= 1.0)) throw new IllegalArgumentException("range");
                break;
            case "bright":
                if (!hasValue || !(value >= 0.004 && value <= 1.0)) throw new IllegalArgumentException("range");
                break;
            default: throw new IllegalArgumentException("op");
        }
        return new Request(op, hasValue, value);
    }

    /** Kleiner JSON-Leser nur für diese Form: Objekt mit Strings ohne Escapes und Zahlen. */
    private static final class Parser {
        final String s; int pos;
        Parser(String s) { this.s = s; }
        char peek() { if (pos >= s.length()) throw new IllegalArgumentException("end"); return s.charAt(pos); }
        void ws() { while (pos < s.length() && " \t\r\n".indexOf(s.charAt(pos)) >= 0) pos++; }
        void expect(char c) { ws(); if (peek() != c) throw new IllegalArgumentException("expected " + c); pos++; }
        String string() {
            expect('"');
            int start = pos;
            while (peek() != '"') {
                char c = s.charAt(pos);
                if (c < 'a' || c > 'z') throw new IllegalArgumentException("string");   // nur Kleinbuchstaben, keine Escapes
                if (pos - start >= 16) throw new IllegalArgumentException("string length");
                pos++;
            }
            String out = s.substring(start, pos);
            pos++;
            return out;
        }
        /** Feldname: nur ASCII-Buchstaben (z. B. planId), keine Escapes, höchstens 16 Zeichen. */
        String key() {
            expect('"');
            int start = pos;
            while (peek() != '"') {
                char c = s.charAt(pos);
                if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'))) throw new IllegalArgumentException("key");
                if (pos - start >= 16) throw new IllegalArgumentException("key length");
                pos++;
            }
            pos++;
            return s.substring(start, pos - 1);
        }
        /** Genau {@code n} Kleinbuchstaben-Hexziffern (Plan- und Optionskennung des Helfers). */
        String hex(int n) {
            expect('"');
            int start = pos;
            while (peek() != '"') {
                char c = s.charAt(pos);
                if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) throw new IllegalArgumentException("hex");
                if (pos - start >= n) throw new IllegalArgumentException("hex length");
                pos++;
            }
            if (pos - start != n) throw new IllegalArgumentException("hex length");
            pos++;
            return s.substring(start, pos - 1);
        }
        /**
         * Kadris Bindung {"player":"[A-Za-z0-9_-]{1,64}","gen":N,"rev":N} (N ganzzahlig 0…999999999), genau diese drei
         * Felder in beliebiger Reihenfolge, je einmal → "player|gen|rev".
         */
        String bind() {
            expect('{');
            String player = null;
            long gen = -1, rev = -1;
            for (int i = 0; i < 3; i++) {
                if (i > 0) expect(',');
                ws();
                String key = key();
                ws(); expect(':'); ws();
                if ("player".equals(key) && player == null) player = token(64);
                else if ("gen".equals(key) && gen < 0) gen = integer();
                else if ("rev".equals(key) && rev < 0) rev = integer();
                else throw new IllegalArgumentException("bind field");
                ws();
            }
            expect('}');
            return player + "|" + gen + "|" + rev;
        }
        String token(int max) {
            expect('"');
            int start = pos;
            while (peek() != '"') {
                char c = s.charAt(pos);
                if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-')) throw new IllegalArgumentException("token");
                if (pos - start >= max) throw new IllegalArgumentException("token length");
                pos++;
            }
            if (pos == start) throw new IllegalArgumentException("token empty");
            pos++;
            return s.substring(start, pos - 1);
        }
        long integer() {
            int start = pos;
            if (peek() == '0') pos++;
            else if (peek() >= '1' && peek() <= '9') { while (pos < s.length() && Character.isDigit(s.charAt(pos))) { pos++; if (pos - start > 9) throw new IllegalArgumentException("integer"); } }
            else throw new IllegalArgumentException("integer");
            if (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) throw new IllegalArgumentException("integer");
            return Long.parseLong(s.substring(start, pos));
        }
        /** Auftragskennung des Browsers: genau 32 Kleinbuchstaben-Hexziffern. */
        String jobId() {
            expect('"');
            int start = pos;
            while (peek() != '"') {
                char c = s.charAt(pos);
                if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) throw new IllegalArgumentException("id");
                pos++;
            }
            if (pos - start != 32) throw new IllegalArgumentException("id length");
            pos++;
            return s.substring(start, pos - 1);
        }
        /** URL aus RFC-3986-Zeichen (unreserved, reserved, '%'); http/https prüft die Netzwerkschicht. */
        String url() {
            expect('"');
            int start = pos;
            while (peek() != '"') {
                char c = s.charAt(pos);
                boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || "-._~:/?#[]@!$&'()*+,;=%".indexOf(c) >= 0;
                if (!ok) throw new IllegalArgumentException("url char");
                if (pos - start >= MAX_URL) throw new IllegalArgumentException("url length");
                pos++;
            }
            if (pos == start) throw new IllegalArgumentException("url empty");
            pos++;
            return s.substring(start, pos - 1);
        }
        /** Anzeigename: keine Anführungszeichen, Backslashes oder Steuerzeichen (keine Escapes), höchstens 120 Zeichen. */
        String name() {
            expect('"');
            int start = pos;
            while (peek() != '"') {
                char c = s.charAt(pos);
                if (c == '\\' || c < 0x20 || (c >= 0x7f && c < 0xa0) || c == 0x2028 || c == 0x2029) throw new IllegalArgumentException("name char");
                if (pos - start >= MAX_NAME) throw new IllegalArgumentException("name length");
                pos++;
            }
            pos++;
            return s.substring(start, pos - 1);
        }
        /** JSON-Zahl ohne Exponent und ohne Vorzeichen: 0, 1, 0.5 … (höchstens 8 Nachkommastellen). */
        double number() {
            int start = pos;
            if (peek() == '0') pos++;
            else if (peek() >= '1' && peek() <= '9') { while (pos < s.length() && Character.isDigit(s.charAt(pos)) && pos - start < 3) pos++; }
            else throw new IllegalArgumentException("number");
            if (pos < s.length() && s.charAt(pos) == '.') {
                pos++;
                int digits = 0;
                while (pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') { pos++; if (++digits > 8) throw new IllegalArgumentException("precision"); }
                if (digits == 0) throw new IllegalArgumentException("number");
            }
            if (pos < s.length() && (s.charAt(pos) >= '0' && s.charAt(pos) <= '9')) throw new IllegalArgumentException("number");
            return Double.parseDouble(s.substring(start, pos));
        }
    }
}
