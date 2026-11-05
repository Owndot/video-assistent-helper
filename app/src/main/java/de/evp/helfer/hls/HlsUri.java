package de.evp.helfer.hls;

import java.util.Locale;

/**
 * P4a: Auflösung relativer HLS-Adressen nach RFC 3986, Abschnitt 5.2 – gegen die tatsächliche finale Antwortadresse
 * (nach Weiterleitungen), nicht gegen die ursprünglich angefragte. Query-Teile werden nie "geerbt" (nur wenn die
 * Referenz selbst leer ist, gilt die Basis samt Query, wie im RFC). java.net.URI#resolve folgt RFC 2396 und löst z. B.
 * "?x=1" falsch auf; deshalb eigene, kleine Implementierung. Nur http/https, kein Userinfo, kein Fragment, begrenzte
 * Länge, keine Steuer- oder Leerzeichen. Netzgrenzen (DNS, private Netze, Ports) prüft danach SafeHttpClient.
 */
public final class HlsUri {
    public static final int MAX_URI_CHARS = 4096;

    private HlsUri() {}

    /** Zerlegte Adresse; null-Felder = Bestandteil fehlt (leer ≠ fehlt, RFC 3986). */
    static final class Parts {
        String scheme, authority, path = "", query, fragment;
    }

    static Parts split(String s) {
        Parts p = new Parts();
        int i = 0, n = s.length();
        int colon = s.indexOf(':');
        int firstDelim = firstOf(s, "/?#", 0);
        if (colon > 0 && (firstDelim < 0 || colon < firstDelim) && s.substring(0, colon).matches("[A-Za-z][A-Za-z0-9+.-]*")) {
            p.scheme = s.substring(0, colon).toLowerCase(Locale.ROOT);
            i = colon + 1;
        }
        if (s.startsWith("//", i)) {
            int end = firstOf(s, "/?#", i + 2);
            if (end < 0) end = n;
            p.authority = s.substring(i + 2, end);
            i = end;
        }
        int endPath = firstOf(s, "?#", i);
        if (endPath < 0) endPath = n;
        p.path = s.substring(i, endPath);
        i = endPath;
        if (i < n && s.charAt(i) == '?') {
            int end = s.indexOf('#', i);
            if (end < 0) end = n;
            p.query = s.substring(i + 1, end);
            i = end;
        }
        if (i < n && s.charAt(i) == '#') p.fragment = s.substring(i + 1);
        return p;
    }

    private static int firstOf(String s, String chars, int from) {
        for (int i = from; i < s.length(); i++) if (chars.indexOf(s.charAt(i)) >= 0) return i;
        return -1;
    }

    static String removeDotSegments(String path) {
        String in = path;
        StringBuilder res = new StringBuilder();
        // RFC 3986 5.2.4, Puffer-Variante
        java.util.List<String> segs = new java.util.ArrayList<>();
        while (!in.isEmpty()) {
            if (in.startsWith("../")) in = in.substring(3);
            else if (in.startsWith("./")) in = in.substring(2);
            else if (in.startsWith("/./")) in = in.substring(2);
            else if (in.equals("/.")) in = "/";
            else if (in.startsWith("/../")) { in = in.substring(3); if (!segs.isEmpty()) segs.remove(segs.size() - 1); }
            else if (in.equals("/..")) { in = "/"; if (!segs.isEmpty()) segs.remove(segs.size() - 1); }
            else if (in.equals(".") || in.equals("..")) in = "";
            else {
                int start = in.startsWith("/") ? 1 : 0;
                int next = in.indexOf('/', start);
                if (next < 0) next = in.length();
                segs.add(in.substring(0, next));
                in = in.substring(next);
            }
        }
        for (String s : segs) res.append(s);
        return res.toString();
    }

    private static String merge(Parts base, String refPath) {
        if (base.authority != null && base.path.isEmpty()) return "/" + refPath;
        int slash = base.path.lastIndexOf('/');
        return (slash >= 0 ? base.path.substring(0, slash + 1) : "") + refPath;
    }

    /** Löst {@code ref} gegen {@code base} auf (RFC 3986 5.2.2) und prüft das Ergebnis; wirft bei jeder Abweichung. */
    public static String resolve(String base, String ref) throws HlsException {
        check(base, "base");
        if (ref == null || ref.isEmpty() || ref.length() > MAX_URI_CHARS) throw new HlsException("uri-length");
        for (int i = 0; i < ref.length(); i++) {
            char c = ref.charAt(i);
            if (c <= 0x20 || c == 0x7f || c > 0x7e) throw new HlsException("uri-char");
        }
        Parts b = split(base), r = split(ref), t = new Parts();
        if (r.scheme != null) {
            t.scheme = r.scheme; t.authority = r.authority; t.path = removeDotSegments(r.path); t.query = r.query;
        } else {
            if (r.authority != null) {
                t.authority = r.authority; t.path = removeDotSegments(r.path); t.query = r.query;
            } else {
                if (r.path.isEmpty()) {
                    t.path = b.path;
                    t.query = r.query != null ? r.query : b.query;   // nur bei leerer Referenz gilt die Basis-Query
                } else {
                    t.path = r.path.startsWith("/") ? removeDotSegments(r.path) : removeDotSegments(merge(b, r.path));
                    t.query = r.query;
                }
                t.authority = b.authority;
            }
            t.scheme = b.scheme;
        }
        t.fragment = r.fragment;
        if (t.fragment != null) throw new HlsException("uri-fragment");
        StringBuilder sb = new StringBuilder();
        sb.append(t.scheme).append(':');
        if (t.authority != null) sb.append("//").append(t.authority);
        sb.append(t.path);
        if (t.query != null) sb.append('?').append(t.query);
        String out = sb.toString();
        check(out, "result");
        return out;
    }

    /** Nur absolute http(s)-Adressen mit Host, ohne Userinfo/Fragment, begrenzt und ohne Steuerzeichen. */
    public static void check(String u, String what) throws HlsException {
        if (u == null || u.isEmpty() || u.length() > MAX_URI_CHARS) throw new HlsException("uri-length");
        for (int i = 0; i < u.length(); i++) {
            char c = u.charAt(i);
            if (c <= 0x20 || c == 0x7f || c > 0x7e) throw new HlsException("uri-char");
        }
        Parts p = split(u);
        if (!"http".equals(p.scheme) && !"https".equals(p.scheme)) throw new HlsException("uri-scheme");
        if (p.authority == null || p.authority.isEmpty()) throw new HlsException("uri-host");
        if (p.authority.indexOf('@') >= 0) throw new HlsException("uri-userinfo");
        if (p.fragment != null) throw new HlsException("uri-fragment");
        if (p.path.isEmpty() || p.path.charAt(0) != '/') throw new HlsException("uri-path");
    }
}
