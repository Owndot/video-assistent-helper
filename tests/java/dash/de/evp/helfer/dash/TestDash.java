package de.evp.helfer.dash;

import java.io.File;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * S3a JVM-Tests des DASH-Kerns (de.evp.helfer.dash): gehärtetes XML, Zahlen/Dauern, Adressen (RFC 3986 vs. WHATWG),
 * Vorlagen, Vererbung, Zeitleisten, Grenzen, S3-Profil, vollständiger Plan, Strukturfingerabdruck v1 und die
 * Querprüfung gegen Kadris Erkennungsindex (tests/java/dash/fixtures/kadri-index.tsv, erzeugt mit kadri_index.py).
 *   java TestDash FIXTURES_DIR                 Tests
 *   java TestDash FIXTURES_DIR --dump-keys OUT Adressen für kadri_index.py schreiben
 */
public final class TestDash {
    static int passed, failed;

    static void check(String name, boolean ok, Object detail) {
        if (ok) { passed++; System.out.println("OK   " + name); }
        else { failed++; System.out.println("FAIL " + name + " – " + detail); }
    }

    static final String DOC = "https://cdn.example/v/manifest.mpd?tok=SECRET";

    static DashManifest parse(String xml) throws DashException { return DashManifest.parse(xml.getBytes(StandardCharsets.UTF_8), DOC); }

    static String reason(String xml) {
        try { parse(xml); return "accepted"; } catch (DashException e) { return e.reason; }
    }

    static String reasonBytes(byte[] b) {
        try { DashManifest.parse(b, DOC); return "accepted"; } catch (DashException e) { return e.reason; }
    }

    /** MPD mit einer Periode; body = Inhalt der Periode. */
    static String mpd(String attrs, String body) {
        return "<?xml version=\"1.0\"?><MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\" mediaPresentationDuration=\"PT60S\"" + attrs + ">"
            + "<Period>" + body + "</Period></MPD>";
    }

    static String mpd(String body) { return mpd("", body); }

    /** MPD mit anderer Gesamtdauer. */
    static String mpdDur(String dur, String body) { return mpd(body).replace("mediaPresentationDuration=\"PT60S\"", "mediaPresentationDuration=\"" + dur + "\""); }

    static final String VSET = "<AdaptationSet contentType=\"video\" mimeType=\"video/mp4\" codecs=\"avc1.64001f\">";
    static final String ASET = "<AdaptationSet contentType=\"audio\" mimeType=\"audio/mp4\" codecs=\"mp4a.40.2\" lang=\"de\">";
    static final String TPL = "<SegmentTemplate timescale=\"1000\" duration=\"4000\" startNumber=\"1\" initialization=\"$RepresentationID$/init.mp4\" media=\"$RepresentationID$/$Number$.m4s\"/>";
    static final String VREP = "<Representation id=\"v\" bandwidth=\"1000000\" width=\"1280\" height=\"720\"/>";
    static final String AREP = "<Representation id=\"a\" bandwidth=\"128000\"/>";
    static final String GOOD = mpd(VSET + TPL + VREP + "</AdaptationSet>" + ASET + TPL + AREP + "</AdaptationSet>");

    static DashManifest.Representation rep(DashManifest m, String id) {
        for (DashManifest.Representation r : m.reps) if (id.equals(r.id)) return r;
        throw new AssertionError("rep " + id);
    }

    static String excl(String xml, String id) {
        try { return String.valueOf(rep(parse(xml), id).excluded); } catch (DashException e) { return "fatal:" + e.reason; }
    }

    static String profile(String xml) {
        try { DashProfile.Verdict v = DashProfile.check(parse(xml)); return v.ok ? "ok" : v.reason; } catch (DashException e) { return "fatal:" + e.reason; }
    }

    static String plan(String xml) {
        try { DashPlan.build(parse(xml)); return "ok"; } catch (DashException e) { return e.reason; }
    }

    static List<String> urls(DashManifest.Representation r) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < r.count; i++) out.add(r.segment(i).url);
        return out;
    }

    public static void main(String[] args) throws Exception {
        File fx = new File(args[0]);
        if (args.length >= 3 && args[1].equals("--dump-keys")) { dumpKeys(fx, new File(args[2])); return; }
        if (args.length >= 2 && args[1].equals("--write-fp")) { writeFp(fx); return; }
        xml();
        numbers();
        addresses();
        templates();
        inheritance(fx);
        timelines(fx);
        limits();
        profiles(fx);
        plans(fx);
        fingerprint(fx);
        crossCheck(fx);
        review1();
        isolation();
        System.out.println("ERGEBNIS: " + passed + " von " + (passed + failed));
        System.exit(failed == 0 ? 0 : 1);
    }

    // ---------------------------------------------------------------- XML-Härtung
    static void xml() throws Exception {
        check("Basis: gültiges MPD wird gelesen", reason(GOOD).equals("accepted"), reason(GOOD));
        String[][] bad = {
            {"<?xml version=\"1.0\"?><!DOCTYPE MPD [<!ENTITY a \"aaaa\">]><MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\"/>", "doctype", "DOCTYPE mit interner Entität"},
            {"<?xml version=\"1.0\"?><!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]><MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\"><BaseURL>&xxe;</BaseURL></MPD>", "doctype", "externe Entität (XXE)"},
            {"<?xml version=\"1.0\"?><!DOCTYPE MPD SYSTEM \"http://evil.example/x.dtd\"><MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\"/>", "doctype", "externe DTD"},
            {"<?xml version=\"1.0\"?><! DOCTYPE MPD><MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\"/>", "doctype", "„<! DOCTYPE“ mit Leerzeichen"},
            {"<?xml version=\"1.0\"?><!ELEMENT MPD ANY><MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\"/>", "doctype", "<!ELEMENT ohne DOCTYPE"},
            {"<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\"><BaseURL>&undefined;</BaseURL></MPD>", "xml", "unbekannte Entität ohne DTD"},
            {"<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" xmlns:xi=\"http://www.w3.org/2001/XInclude\"><xi:include href=\"file:///etc/passwd\" parse=\"text\"/></MPD>", "xinclude", "XInclude-Element"},
            {"<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" xmlns:xlink=\"http://www.w3.org/1999/xlink\"><Period xlink:href=\"https://evil.example/p.xml\" xlink:actuate=\"onLoad\"/></MPD>", "xlink", "XLink an Period"},
            {"<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" xmlns:x=\"http://www.w3.org/1999/xlink\"><Period><AdaptationSet x:href=\"a\"/></Period></MPD>", "xlink", "XLink mit anderem Präfix an AdaptationSet"},
            {"<html><body><MPD/></body></html>", "not-mpd", "Wurzel nicht MPD"},
            {"<MPD><Period/></MPD>", "not-mpd", "MPD ohne DASH-Namensraum"},
            {"<MPD xmlns=\"urn:example:other\"/>", "not-mpd", "MPD in fremdem Namensraum"},
            {"<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" xmlns:o=\"urn:example:o\"><Period><o:AdaptationSet/></Period></MPD>", "namespace", "DASH-Name in fremdem Namensraum (Kadri sähe ihn als DASH)"},
            {"<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" xmlns:o=\"urn:example:o\"><o:wrap><Period/></o:wrap></MPD>", "namespace", "DASH-Element innerhalb eines fremden Elements"},
            {"<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\"><Period><AdaptationSet>", "xml", "abgeschnittenes XML"},
            {"<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\"><!-- offen", "xml", "nicht geschlossener Kommentar"},
            {"<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\"/>", "encoding", "andere Kodierung deklariert"},
            {"<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\"><Period><AdaptationSet><SegmentTemplate><SegmentTimeline><S d=\"1\"><S d=\"1\"/></S></SegmentTimeline></SegmentTemplate></AdaptationSet></Period></MPD>", "structure", "S mit Kind-Element"},
            {"<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\"><Period><S d=\"1\"/></Period></MPD>", "structure", "S außerhalb einer SegmentTimeline"},
        };
        for (String[] b : bad) check("XML: " + b[2] + " → " + b[1], reason(b[0]).equals(b[1]), reason(b[0]));
        check("XML: Kommentare und CDATA werden nicht als DOCTYPE gedeutet",
            reason(GOOD.replace("<Period>", "<!-- <!DOCTYPE x> --><Period>")).equals("accepted")
                && reason(GOOD.replace("<Period>", "<Period><BaseURL><![CDATA[p/]]></BaseURL>")).equals("accepted"), "");
        check("XML: Kodierung – UTF-16-BOM, ungültiges UTF-8, NUL abgelehnt; UTF-8-BOM erlaubt",
            reasonBytes(new byte[] {(byte) 0xfe, (byte) 0xff, 0, '<'}).equals("encoding")
                && reasonBytes(new byte[] {'<', 'M', (byte) 0xc3, 0x28}).equals("encoding")
                && reasonBytes(("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\">\u0000</MPD>").getBytes(StandardCharsets.UTF_8)).equals("encoding")
                && reasonBytes(concat(new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf}, GOOD.getBytes(StandardCharsets.UTF_8))).equals("accepted"), "");
        check("XML: leer abgelehnt", reasonBytes(new byte[0]).equals("empty"), "");
        // Grenzen
        byte[] big = new byte[DashLimits.MAX_MANIFEST_BYTES + 1];
        Arrays.fill(big, (byte) ' ');
        check("Grenze: MPD über 4 MB → too-large", reasonBytes(big).equals("too-large"), "");
        StringBuilder many = new StringBuilder("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" xmlns:o=\"urn:o\">");
        for (int i = 0; i < DashLimits.MAX_ELEMENTS; i++) many.append("<o:x/>");
        check("Grenze: mehr als 200000 Elemente (auch fremde) → limits", reason(many.append("</MPD>").toString()).equals("limits"), "");
        StringBuilder deep = new StringBuilder("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" xmlns:o=\"urn:o\">");
        for (int i = 0; i < 40; i++) deep.append("<o:x>");
        for (int i = 0; i < 40; i++) deep.append("</o:x>");
        check("Grenze: Tiefe über 32 → depth", reason(deep.append("</MPD>").toString()).equals("depth"), "");
        StringBuilder attrs = new StringBuilder("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\"");
        for (int i = 0; i < 70; i++) attrs.append(" a").append(i).append("=\"1\"");
        check("Grenze: mehr als 64 Attribute → attrs", reason(attrs.append("/>").toString()).equals("attrs"), "");
        check("Grenze: Attribut über 8192 Zeichen → attr-length", reason("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" profiles=\"" + rep('x', 9000) + "\"/>").equals("attr-length"), "");
        check("Grenze: BaseURL-Text über 4096 Zeichen → text-length", reason(mpd("<BaseURL>" + rep('a', 5000) + "/</BaseURL>")).equals("text-length"), "");
        StringBuilder per = new StringBuilder("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\" mediaPresentationDuration=\"PT1S\">");
        for (int i = 0; i < 65; i++) per.append("<Period/>");
        check("Grenze: mehr als 64 Perioden → limits", reason(per.append("</MPD>").toString()).equals("limits"), "");
        StringBuilder sets = new StringBuilder();
        for (int i = 0; i < 1025; i++) sets.append("<AdaptationSet/>");
        check("Grenze: mehr als 1024 AdaptationSets → limits", reason(mpd(sets.toString())).equals("limits"), "");
        StringBuilder reps = new StringBuilder(VSET + TPL);
        for (int i = 0; i < 4097; i++) reps.append("<Representation id=\"r").append(i).append("\" bandwidth=\"1\"/>");
        check("Grenze: mehr als 4096 Darstellungen → limits", reason(mpd(reps.append("</AdaptationSet>").toString())).equals("limits"), "");
        StringBuilder s = new StringBuilder();
        for (int i = 0; i <= DashLimits.MAX_TIMELINE_ENTRIES; i++) s.append("<S d=\"1\"/>");
        check("Grenze: mehr als 100000 S-Einträge → limits",
            reason(mpd(VSET + "<SegmentTemplate media=\"$Number$.m4s\"><SegmentTimeline>" + s + "</SegmentTimeline></SegmentTemplate>" + VREP + "</AdaptationSet>")).equals("limits"), "");
        // 4-MB-Manifest mit vielen S-Einträgen bleibt im kleinen Heap (Test läuft mit -Xmx64m)
        StringBuilder full = new StringBuilder();
        for (int i = 0; i < DashLimits.MAX_TIMELINE_ENTRIES; i++) full.append("<S d=\"1000\"/>");
        String fullXml = mpdDur("PT100000S", VSET + "<SegmentTemplate timescale=\"1\" media=\"$Number$.m4s\" initialization=\"i.mp4\"><SegmentTimeline>" + full + "</SegmentTimeline></SegmentTemplate>" + VREP + "</AdaptationSet>");
        long t0 = System.nanoTime();
        String r = excl(fullXml, "v");
        check("Speicher/Zeit: 100000 S-Einträge (≈1,3 MB) werden kompakt gelesen; Expansion über 20000 Stücke → segments",
            r.equals("segments") && System.nanoTime() - t0 < 10_000_000_000L, r);
    }

    static byte[] concat(byte[] a, byte[] b) { byte[] c = Arrays.copyOf(a, a.length + b.length); System.arraycopy(b, 0, c, a.length, b.length); return c; }

    static String rep(char c, int n) { char[] x = new char[n]; Arrays.fill(x, c); return new String(x); }

    // ---------------------------------------------------------------- Zahlen und Dauern
    static void numbers() throws Exception {
        check("Zahl: führende Nullen und XML-Leerraum am Rand erlaubt", DashNum.parseU(" 0042\n") == 42 && DashNum.parseU("0") == 0, "");
        String[][] bad = {{"-1", "number"}, {"+5", "number"}, {"1e5", "number"}, {"1.5", "number"}, {"", "number"}, {"0x10", "number"},
            {"9007199254740992", "number-range"}, {"99999999999999999999", "number-range"}, {"1234567890123456789012345678901234567890", "number-range"},
            {"12345678901234567890123456789012345678901", "number"}};
        for (String[] b : bad) {
            String got;
            try { DashNum.parseU(b[0]); got = "accepted"; } catch (DashException e) { got = e.reason; }
            check("Zahl „" + (b[0].length() > 20 ? b[0].substring(0, 20) + "…" : b[0]) + "“ → " + b[1], got.equals(b[1]), got);
        }
        check("Zahl: 2^53−1 ist die größte erlaubte", DashNum.parseU("9007199254740991") == DashLimits.MAX_SAFE, "");
        check("S@r: −1 erlaubt, −2 abgelehnt", r("-1") == -1 && rr("-2").equals("number"), rr("-2"));
        String[][] dur = {{"PT60S", "60"}, {"PT1H32M5.120S", "5525.12"}, {"P1DT0.5S", "86400.5"}, {"PT0H0M30.0S", "30"}, {"PT24M10.5S", "1450.5"}};
        for (String[] d : dur) check("Dauer " + d[0] + " = " + d[1] + " s (exakt)", DashNum.canon(DashNum.duration(d[0])).equals(d[1]), DashNum.canon(DashNum.duration(d[0])));
        for (String d : new String[] {"P1Y", "P1M", "P1W", "PT", "P", "P1DT", "-PT1S", "PT1.5M", "pt1s", "PT1,5S", ""}) {
            String got;
            try { DashNum.duration(d); got = "accepted"; } catch (DashException e) { got = e.reason; }
            check("Dauer „" + d + "“ abgelehnt (Jahre/Monate/Wochen ohne feste Länge, leer, negativ, Komma)", got.equals("duration"), got);
        }
        check("MPD: ungültige Dauer ist fatal", reason(GOOD.replace("PT60S", "P1M")).equals("duration"), reason(GOOD.replace("PT60S", "P1M")));
        check("MPD: Zahl über 2^53 in startNumber ist fatal (number-range)",
            reason(mpd(VSET + TPL.replace("startNumber=\"1\"", "startNumber=\"9007199254740992\"") + VREP + "</AdaptationSet>")).equals("number-range"), "");
        check("MPD: timescale 0 und über 2^32−1 fatal",
            reason(mpd(VSET + TPL.replace("timescale=\"1000\"", "timescale=\"0\"") + VREP + "</AdaptationSet>")).equals("number-range")
                && reason(mpd(VSET + TPL.replace("timescale=\"1000\"", "timescale=\"4294967296\"") + VREP + "</AdaptationSet>")).equals("number-range"), "");
        check("MPD: @bandwidth über 1 Gbit/s fatal", reason(mpd(VSET + TPL + VREP.replace("1000000", "1000000001") + "</AdaptationSet>")).equals("number-range"), "");
    }

    static long r(String s) throws DashException { return DashNum.parseR(s); }

    static String rr(String s) { try { DashNum.parseR(s); return "accepted"; } catch (DashException e) { return e.reason; } }

    // ---------------------------------------------------------------- Adressen
    static String res(String base, String ref) { try { return DashUrl.resolve(base, ref); } catch (DashException e) { return "!" + e.reason; } }

    static void addresses() throws Exception {
        String b = "https://cdn.example/a/b/manifest.mpd?tok=1";
        String[][] ok = {
            {"seg.m4s", "https://cdn.example/a/b/seg.m4s"},                       // Query der Basis wird nicht geerbt
            {"../c/seg.m4s", "https://cdn.example/a/c/seg.m4s"},
            {"/x/seg.m4s?v=1", "https://cdn.example/x/seg.m4s?v=1"},
            {"//Other.Example:8443/s.m4s", "https://other.example:8443/s.m4s"},
            {"https://CDN.Example:443/s.m4s", "https://cdn.example/s.m4s"},       // Standardport entfällt, Host klein (wie WHATWG)
            {"http://cdn.example:80/s", "http://cdn.example/s"},
            {"?v=2", "https://cdn.example/a/b/manifest.mpd?v=2"},
            {"seg$1.m4s?a=b&c=d;e", "https://cdn.example/a/b/seg$1.m4s?a=b&c=d;e"},
            {"s%20x.m4s", "https://cdn.example/a/b/s%20x.m4s"},
            {"https://10.0.0.1/s", "https://10.0.0.1/s"},
        };
        for (String[] t : ok) check("Adresse „" + t[0] + "“ → " + t[1], res(b, t[0]).equals(t[1]), res(b, t[0]));
        String[][] bad = {
            {"seg 1.m4s", "uri-char"}, {"segä.m4s", "uri-char"}, {"a\\b", "uri-ambiguous"}, {"a'b", "uri-ambiguous"}, {"a\"b", "uri-ambiguous"},
            {"a<b", "uri-ambiguous"}, {"a{b}", "uri-ambiguous"}, {"a|b", "uri-ambiguous"}, {"a^b", "uri-ambiguous"}, {"a`b", "uri-ambiguous"},
            {"seg.m4s#frag", "uri-ambiguous"}, {"%2e%2e/x", "uri-ambiguous"}, {"%2E/x", "uri-ambiguous"}, {"a%zz", "uri-ambiguous"}, {"a%2", "uri-ambiguous"},
            {"http:seg.m4s", "uri-ambiguous"}, {"ftp://x/y", "uri-scheme"}, {"https://user@cdn.example/x", "uri-userinfo"},
            {"https://[::1]/x", "uri-ambiguous"}, {"https://127.1/x", "uri-host"}, {"https://0x7f.0.0.1/x", "uri-host"}, {"https://010.0.0.1/x", "uri-host"},
            {"https://cdn.example:0/x", "uri-port"}, {"https://cdn.example:99999/x", "uri-port"}, {"https://cdn.example:/x", "uri-port"},
            {"https://cdn..example/x", "uri-host"}, {"https://cd_n.example/x", "uri-host"}, {"", "uri-empty"},
            {"x" + rep('a', 4100), "uri-length"},
        };
        for (String[] t : bad) check("Adresse „" + (t[0].length() > 30 ? t[0].substring(0, 30) + "…" : t[0]) + "“ abgelehnt → " + t[1], res(b, t[0]).equals("!" + t[1]), res(b, t[0]));
        check("Dokumentadresse: Fragment wird entfernt, Host kanonisch",
            ok(() -> DashManifest.parse(GOOD.getBytes(StandardCharsets.UTF_8), "https://CDN.example/v/m.mpd#x").documentUrl.equals("https://cdn.example/v/m.mpd")), "");
    }

    interface Cond { boolean get() throws Exception; }

    static boolean ok(Cond c) { try { return c.get(); } catch (Exception e) { System.out.println("     (" + e + ")"); return false; } }

    // ---------------------------------------------------------------- Vorlagen
    static String media(String tpl) {
        try {
            DashManifest m = parse(mpd(VSET + TPL.replace("media=\"$RepresentationID$/$Number$.m4s\"", "media=\"" + tpl + "\"") + VREP.replace("id=\"v\"", "id=\"v_1\"") + "</AdaptationSet>"));
            DashManifest.Representation r = m.reps.get(0);
            return r.count > 0 ? r.segment(0).url.substring("https://cdn.example/v/".length()) + (r.excluded == null ? "" : " !" + r.excluded) : "!" + r.excluded;
        } catch (DashException e) { return "fatal:" + e.reason; }
    }

    static void templates() {
        String[][] ok = {
            {"$Number$.m4s", "1.m4s"}, {"$Number%05d$.m4s", "00001.m4s"}, {"$Number%01d$.m4s", "1.m4s"}, {"$Number%020d$.m4s", "00000000000000000001.m4s"},
            {"$RepresentationID$-$Bandwidth$-$Number$", "v_1-1000000-1"}, {"$Bandwidth%010d$/$Number$", "0001000000/1"},
            {"a$$b/$Number$", "a$b/1"}, {"$$$Number$$$", "$1$"}, {"seg.m4s?n=$Number$&amp;r=$RepresentationID$", "seg.m4s?n=1&r=v_1"},
        };
        for (String[] t : ok) check("Vorlage „" + t[0] + "“ → " + t[1], media(t[0]).equals(t[1]), media(t[0]));
        String[] bad = {"$Number%5d$", "$Number%d$", "$Number%00d$", "$Number%021d$", "$Number%05x$", "$RepresentationID%05d$/$Number$", "$SubNumber$",
            "$Unknown$/$Number$", "$Number", "$Number$/$Number$", "$Number$/$Time$", "seg.m4s", "https://cdn$Number$.example/x", "https://cdn.example:$Number$/x"};
        for (String t : bad) check("Vorlage „" + t + "“ abgelehnt (template)", media(t).equals("fatal:template"), media(t));
        check("Init-Vorlage mit $Number$ abgelehnt",
            reason(mpd(VSET + TPL.replace("initialization=\"$RepresentationID$/init.mp4\"", "initialization=\"$Number$/init.mp4\"") + VREP + "</AdaptationSet>")).equals("template"), "");
        check("$RepresentationID$ mit unzulässigen Zeichen (/ ? # % Leerraum) abgelehnt (rep-id)",
            reason(mpd(VSET + TPL + VREP.replace("id=\"v\"", "id=\"a/b\"") + "</AdaptationSet>")).equals("rep-id")
                && reason(mpd(VSET + TPL + VREP.replace("id=\"v\"", "id=\"a?b\"") + "</AdaptationSet>")).equals("rep-id")
                && reason(mpd(VSET + TPL + VREP.replace("id=\"v\"", "id=\"a%41\"") + "</AdaptationSet>")).equals("rep-id")
                && reason(mpd(VSET + TPL + VREP.replace(" id=\"v\"", "") + "</AdaptationSet>")).equals("rep-id"), "");
        check("$RepresentationID$ mit = , ; (z. B. „audio_eng=128000“) erlaubt",
            media("$RepresentationID$/$Number$").equals("v_1/1") && reason(mpd(VSET + TPL + VREP.replace("id=\"v\"", "id=\"video=1000,x;y\"") + "</AdaptationSet>")).equals("accepted"), "");
        check("Medienvorlage $Time$ ohne Zeitleiste: nicht nutzbar (time-without-timeline)", media("$Time$.m4s").equals("!time-without-timeline"), media("$Time$.m4s"));
    }

    // ---------------------------------------------------------------- Vererbung
    static void inheritance(File fx) throws Exception {
        DashManifest m = DashManifest.parse(Files.readAllBytes(new File(fx, "s3a/s3a-inherit-query.mpd").toPath()), DOC);
        DashManifest.Representation lo = rep(m, "lo"), hi = rep(m, "hi"), a = rep(m, "a");
        check("Vererbung: timescale/startNumber aus der Periode, duration/media/init aus dem Set, duration der Darstellung überschreibt",
            lo.timescale == 1000 && lo.startNumber == 0 && lo.duration == 4000 && hi.duration == 5000 && hi.timescale == 1000 && lo.count == 5 && hi.count == 4,
            lo.timescale + "/" + lo.startNumber + "/" + lo.duration + "/" + hi.duration + "/" + lo.count + "/" + hi.count);
        check("BaseURL-Kette: Host klein, :443 entfällt, Query der MPD-BaseURL wird bei relativer Referenz nicht geerbt, ../ aufgelöst",
            m.base.equals("https://cdn.example/root/?session=1") && m.periods.get(0).base.equals("https://cdn.example/root/p/")
                && m.sets.get(0).base.equals("https://cdn.example/root/vid/"), m.base + " " + m.periods.get(0).base + " " + m.sets.get(0).base);
        check("Stückadressen: $$-Escape, Query der Vorlage mit &amp;, $RepresentationID$ in Pfad und Query",
            lo.segment(0).url.equals("https://cdn.example/root/vid/lo/s$x-0.m4s?v=lo&q=1") && lo.segment(4).url.equals("https://cdn.example/root/vid/lo/s$x-4.m4s?v=lo&q=1"),
            lo.segment(0).url);
        check("Schema-relative BaseURL der Darstellung (anderer Host)", hi.base.equals("https://other.example/hi-base/") && hi.segment(3).url.equals("https://other.example/hi-base/hi/s$x-3.m4s?v=hi&q=1"), hi.segment(3).url);
        check("Init: Vorlage mit Query; $Bandwidth%08d$ mit Nullen aufgefüllt", lo.initUrl.equals("https://cdn.example/root/vid/lo/init.mp4?v=lo")
            && a.initUrl.equals("https://cdn.example/root/p/a/init-00096000.mp4") && a.segment(9).url.equals("https://cdn.example/root/p/a/96000/009.m4s"), a.initUrl + " " + a.segment(9).url);
        // Fehlerfälle der Vererbung
        check("Adressierung gemischt (Template am Set, SegmentList an der Darstellung) → fatal addressing-mixed",
            reason(mpd(VSET + TPL + "<Representation id=\"v\" bandwidth=\"1\"><SegmentList duration=\"1\"><SegmentURL media=\"a\"/></SegmentList></Representation></AdaptationSet>")).equals("addressing-mixed"), "");
        check("Zwei SegmentTemplates auf einer Ebene → fatal addressing-multiple", reason(mpd(VSET + TPL + TPL + VREP + "</AdaptationSet>")).equals("addressing-multiple"), "");
        check("@duration und SegmentTimeline zugleich (geerbt) → fatal addressing-ambiguous",
            reason(mpd(VSET + "<SegmentTemplate timescale=\"1\" duration=\"4\" media=\"$Number$\" initialization=\"i\"/><Representation id=\"v\" bandwidth=\"1\"><SegmentTemplate><SegmentTimeline><S d=\"4\" r=\"14\"/></SegmentTimeline></SegmentTemplate></Representation></AdaptationSet>")).equals("addressing-ambiguous"), "");
        check("@initialization und Initialization-Kind zugleich → fatal addressing-ambiguous",
            reason(mpd(VSET + TPL.replace("/>", "><Initialization sourceURL=\"x.mp4\"/></SegmentTemplate>") + VREP + "</AdaptationSet>")).equals("addressing-ambiguous"), "");
        check("Unbekanntes Vorlagen-Attribut → fatal addressing-attr", reason(mpd(VSET + TPL.replace("/>", " mystery=\"1\"/>") + VREP + "</AdaptationSet>")).equals("addressing-attr"), "");
        check("Bekanntes, aber nicht unterstütztes Vorlagen-Attribut (endNumber, indexRange, presentationDuration) → Darstellung ausgeschlossen",
            excl(mpd(VSET + TPL.replace("/>", " endNumber=\"3\"/>") + VREP + "</AdaptationSet>"), "v").equals("addressing-attr")
                && excl(mpd(VSET + TPL.replace("/>", " presentationDuration=\"3\"/>") + VREP + "</AdaptationSet>"), "v").equals("addressing-attr"), "");
        check("Fremder Namensraum an der Vorlage (Herstellererweiterung) ändert nichts",
            excl(mpd(VSET.replace(">", " xmlns:o=\"urn:o\">") + TPL.replace("/>", " o:hint=\"1\"/>") + VREP + "</AdaptationSet>"), "v").equals("null"), "");
        check("SegmentList-Einträge auf zwei Ebenen → fatal addressing-ambiguous",
            reason(mpd(VSET + "<SegmentList duration=\"30\"><SegmentURL media=\"a\"/><SegmentURL media=\"b\"/></SegmentList><Representation id=\"v\" bandwidth=\"1\"><SegmentList><SegmentURL media=\"c\"/><SegmentURL media=\"d\"/></SegmentList></Representation></AdaptationSet>")).equals("addressing-ambiguous"), "");
        check("SegmentList: Dauer geerbt, Init über Initialization@sourceURL",
            ok(() -> { DashManifest.Representation r = rep(parse(mpd(VSET + "<SegmentList timescale=\"1\" duration=\"30\"><Initialization sourceURL=\"i.mp4\"/></SegmentList><Representation id=\"v\" bandwidth=\"1\"><SegmentList><SegmentURL media=\"a.m4s\"/><SegmentURL media=\"b.m4s\"/></SegmentList></Representation></AdaptationSet>")), "v");
                return r.excluded == null && r.count == 2 && r.initUrl.equals("https://cdn.example/v/i.mp4") && r.segment(1).url.equals("https://cdn.example/v/b.m4s"); }), "");
        check("Mehrdeutige BaseURLs: zwei auf einer Ebene → fatal baseurl-multiple",
            reason(mpd("<BaseURL>https://a.example/</BaseURL><BaseURL>https://b.example/</BaseURL>" + VSET + TPL + VREP + "</AdaptationSet>")).equals("baseurl-multiple")
                && reason(GOOD.replace("<Period>", "<BaseURL>a/</BaseURL><BaseURL>b/</BaseURL><Period>")).equals("baseurl-multiple"), "");
        check("BaseURL mit byteRange oder unbekanntem Attribut → fatal baseurl-attr; leer → baseurl-empty; serviceLocation erlaubt",
            reason(mpd("<BaseURL byteRange=\"$base$?r=$first$\">a/</BaseURL>" + VSET + TPL + VREP + "</AdaptationSet>")).equals("baseurl-attr")
                && reason(mpd("<BaseURL weight=\"1\">a/</BaseURL>")).equals("baseurl-attr")
                && reason(mpd("<BaseURL>  </BaseURL>")).equals("baseurl-empty")
                && reason(mpd("<BaseURL serviceLocation=\"cdn1\">a/</BaseURL>")).equals("accepted"), "");
    }

    // ---------------------------------------------------------------- Zeitleisten
    static String tl(String timeline, String tplAttrs) {
        return mpd(VSET + "<SegmentTemplate timescale=\"1000\" " + tplAttrs + " initialization=\"i.mp4\" media=\"$Time$.m4s\"><SegmentTimeline>" + timeline
            + "</SegmentTimeline></SegmentTemplate>" + VREP + "</AdaptationSet>");
    }

    static void timelines(File fx) throws Exception {
        DashManifest m = DashManifest.parse(Files.readAllBytes(new File(fx, "s3a/s3a-timeline-pto.mpd").toPath()), DOC);
        DashManifest.Representation v = rep(m, "720"), a = rep(m, "a128");
        check("Zeitleiste: implizites t, r=5, r=−1 bis zum nächsten S, r=−1 bis zum Periodenende (mit PTO); Stück vor dem PTO entfällt",
            v.excluded == null && v.first == 1 && v.count == 15 && v.segment(0).time == 900000 && v.segment(5).time == 2700000
                && v.segment(8).time == 3780000 && v.segment(14).time == 5940000 && v.segment(0).ordinal == 1 && v.covered.compareTo(new BigDecimal("60")) == 0,
            v.excluded + " first=" + v.first + " n=" + v.count + " covered=" + v.covered);
        check("Zeitleiste: Adresse aus $Time$ (Medienzeit, nicht um PTO verschoben)", v.segment(0).url.equals("https://cdn.example/v/v/720/t900000.m4s"), v.segment(0).url);
        check("Andere Timescale (48 kHz) mit $Number%05d$ über Zeitleiste und startNumber 7: 15 Stücke 00007 … 00021",
            a.excluded == null && a.count == 15 && a.segment(0).url.endsWith("/a/seg-00007.m4s") && a.segment(14).url.endsWith("/a/seg-00021.m4s") && a.segment(14).number == 21,
            a.excluded + " " + a.count);
        check("Lücke in der Zeitleiste → Darstellung ausgeschlossen (timeline-gap)", excl(tl("<S t=\"0\" d=\"4000\" r=\"6\"/><S t=\"30000\" d=\"4000\" r=\"6\"/>", ""), "v").equals("timeline-gap"), "");
        check("Überlappung → timeline-overlap", excl(tl("<S t=\"0\" d=\"4000\" r=\"6\"/><S t=\"27000\" d=\"4000\" r=\"7\"/>", ""), "v").equals("timeline-overlap"), "");
        check("r=−1 bis zum nächsten S, das nicht glatt aufgeht → timeline-repeat", excl(tl("<S t=\"0\" d=\"4000\" r=\"-1\"/><S t=\"10000\" d=\"50000\"/>", ""), "v").equals("timeline-repeat"), "");
        check("r=−1 vor einem S ohne t → timeline-repeat", excl(tl("<S t=\"0\" d=\"4000\" r=\"-1\"/><S d=\"4000\"/>", ""), "v").equals("timeline-repeat"), "");
        check("r=−1 am Ende bei unbekannter Periodendauer → duration-unknown",
            excl(tl("<S t=\"0\" d=\"4000\" r=\"-1\"/>", "").replace(" mediaPresentationDuration=\"PT60S\"", ""), "v").equals("duration-unknown"), "");
        check("Null-Dauer (d=0) → zero-duration; @duration=0 → zero-duration",
            excl(tl("<S t=\"0\" d=\"0\" r=\"3\"/>", ""), "v").equals("zero-duration")
                && excl(mpd(VSET + TPL.replace("duration=\"4000\"", "duration=\"0\"") + VREP + "</AdaptationSet>"), "v").equals("zero-duration"), "");
        check("Unbeschränkte Expansion: r=10^15 → segments; r=−1 mit d=1 über 60 s bei timescale 1000 → segments; @duration=1 → segments",
            excl(tl("<S t=\"0\" d=\"4000\" r=\"1000000000000000\"/>", ""), "v").equals("segments")
                && excl(tl("<S t=\"0\" d=\"1\" r=\"-1\"/>", ""), "v").equals("segments")
                && excl(mpd(VSET + TPL.replace("duration=\"4000\"", "duration=\"1\"") + VREP + "</AdaptationSet>"), "v").equals("segments"), "");
        check("Überlauf: t nahe 2^53 plus d·r → number-range",
            excl(tl("<S t=\"9007199254740000\" d=\"4000\" r=\"5\"/>", "presentationTimeOffset=\"9007199254740000\""), "v").equals("number-range"), "");
        check("Abdeckung ≠ Periodendauer (Zeitleiste endet 20 s zu früh) → coverage-mismatch", excl(tl("<S t=\"0\" d=\"4000\" r=\"9\"/>", ""), "v").equals("coverage-mismatch"), "");
        check("Abdeckung innerhalb 1 s Toleranz (Rundung) ist erlaubt", excl(tl("<S t=\"0\" d=\"4000\" r=\"14\"/>", "").replace("PT60S", "PT60.4S"), "v").equals("null"), "");
        check("PTO verschiebt das Fenster: Stücke vor dem PTO und nach dem Periodenende entfallen",
            ok(() -> { DashManifest.Representation r = rep(parse(tl("<S t=\"0\" d=\"4000\" r=\"19\"/>", "presentationTimeOffset=\"8000\"")), "v");
                return r.excluded == null && r.first == 2 && r.count == 15 && r.segment(0).time == 8000 && r.segment(14).time == 64000; }), "");
        check("Zeitleiste mit unbekanntem S-Attribut (n, k) → fatal addressing-attr",
            reason(tl("<S t=\"0\" d=\"4000\" r=\"14\" n=\"3\"/>", "")).equals("addressing-attr") && reason(tl("<S t=\"0\" d=\"4000\" k=\"2\" r=\"14\"/>", "")).equals("addressing-attr"), "");
        check("S ohne d → fatal timeline-attr", reason(tl("<S t=\"0\"/>", "")).equals("timeline-attr"), "");
        check("Periodendauer aus nächstem Period@start bzw. MPD-Dauer",
            ok(() -> { DashManifest mm = parse("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\" mediaPresentationDuration=\"PT100S\"><Period start=\"PT0S\"/><Period start=\"PT40S\"/></MPD>");
                return mm.periods.get(0).effectiveDuration.compareTo(new BigDecimal(40)) == 0 && mm.periods.get(1).effectiveDuration.compareTo(new BigDecimal(60)) == 0; }), "");
    }

    // ---------------------------------------------------------------- weitere Grenzen
    static void limits() {
        check("Stückdauer über 3600 s → segment-duration",
            excl(mpdDur("PT7400S", VSET + TPL.replace("duration=\"4000\"", "duration=\"3700000\"") + VREP + "</AdaptationSet>"), "v").equals("segment-duration"), "");
        StringBuilder many = new StringBuilder(VSET + TPL);
        for (int i = 0; i < 17; i++) many.append("<Representation id=\"v").append(i).append("\" bandwidth=\"1000\" height=\"").append(i).append("\"/>");
        many.append("</AdaptationSet>").append(ASET).append(TPL);
        for (int i = 0; i < 16; i++) many.append("<Representation id=\"a").append(i).append("\" bandwidth=\"1000\"/>");
        check("Mehr als 256 Optionen (17 × 16) → options-limit", plan(mpd(many.append("</AdaptationSet>").toString())).equals("options-limit"), plan(mpd(many.toString())));
        check("Geschätzte Größe über 16 GiB je Option (1 Gbit/s × 48 h) → size-limit",
            plan(mpdDur("PT172800S", VSET + TPL.replace("duration=\"4000\"", "duration=\"10000\"") + VREP.replace("1000000", "1000000000") + "</AdaptationSet>")).equals("size-limit"), "");
        check("Gesamtdauer über 48 h → dash-duration-limit",
            plan(mpdDur("PT172801S", VSET + TPL.replace("duration=\"4000\"", "duration=\"10000\"") + VREP + "</AdaptationSet>")).equals("dash-duration-limit"), "");
    }

    // ---------------------------------------------------------------- S3-Profil
    static void profiles(File fx) throws Exception {
        String[][] k = {
            {"live-dynamic.mpd", "dash-live"}, {"vod-multi-period.mpd", "dash-multiperiod"}, {"vod-drm-cenc.mpd", "dash-protected"},
            {"vod-drm-cbcs-rep.mpd", "dash-protected"}, {"vod-av1-hevc-inherit.mpd", "dash-codec"}, {"vod-thumbnails-muxed.mpd", "dash-codec"},
            {"vod-ondemand-segmentbase.mpd", "dash-addressing"}, {"vod-webm-vp9-opus.mpd", "dash-addressing"},
            {"vod-number-ffmpeg.mpd", "ok"}, {"vod-timeline-avc-aac.mpd", "ok"}, {"vod-segmentlist.mpd", "ok"}, {"vod-baseurl-chain.mpd", "ok"}, {"vod-prefixed-ns.mpd", "ok"},
        };
        for (String[] t : k) {
            DashManifest m = DashManifest.parse(Files.readAllBytes(new File(fx, "kadri/" + t[0]).toPath()), DOC);
            DashProfile.Verdict v = DashProfile.check(m);
            check("Profil Kadri-Fixture " + t[0] + " → " + t[1], (v.ok ? "ok" : v.reason).equals(t[1]), v.reason);
        }
        for (String f : new String[] {"bad-broken.mpd", "bad-doctype-entities.mpd", "bad-xxe.mpd", "bad-not-mpd.xml"}) {
            String got;
            try { DashManifest.parse(Files.readAllBytes(new File(fx, "kadri/" + f).toPath()), DOC); got = "accepted"; } catch (DashException e) { got = e.reason; }
            check("Kadri-Fixture " + f + " fatal abgelehnt (" + got + ")", !got.equals("accepted"), got);
        }
        check("Profil: type weder static noch dynamic → dash-type", profile(GOOD.replace("type=\"static\"", "type=\"vod\"")).equals("dash-type"), "");
        check("Profil: ohne Dauer → dash-duration-unknown", profile(GOOD.replace(" mediaPresentationDuration=\"PT60S\"", "")).equals("dash-duration-unknown"), "");
        check("Profil: ContentProtection an MPD-Ebene / ContentComponent / nur im Text-Set → dash-protected",
            profile(GOOD.replace("<Period>", "<ContentProtection schemeIdUri=\"urn:x\"/><Period>")).equals("dash-protected")
                && profile(GOOD.replace(VREP, "<ContentComponent id=\"1\"><ContentProtection schemeIdUri=\"urn:x\"/></ContentComponent>" + VREP)).equals("dash-protected")
                && profile(GOOD.replace("</Period>", "<AdaptationSet mimeType=\"text/vtt\"><ContentProtection schemeIdUri=\"urn:x\"/><Representation id=\"t\" bandwidth=\"1\"><BaseURL>t.vtt</BaseURL></Representation></AdaptationSet></Period>")).equals("dash-protected"), "");
        check("Profil: Ton vorhanden, aber nur nicht unterstützter Ton (ec-3) → dash-codec (nie still ohne Ton)",
            profile(GOOD.replace("codecs=\"mp4a.40.2\"", "codecs=\"ec-3\"")).equals("dash-codec"), profile(GOOD.replace("codecs=\"mp4a.40.2\"", "codecs=\"ec-3\"")));
        check("Profil: nur Ton (kein Video) → dash-no-video", profile(mpd(ASET + TPL + AREP + "</AdaptationSet>")).equals("dash-no-video"), "");
        check("Profil: nur Video ohne Ton-Set → ok (eine Option ohne Ton)", profile(mpd(VSET + TPL + VREP + "</AdaptationSet>")).equals("ok"), "");
        check("Profil: WebM-Container → dash-codec (container)", profile(GOOD.replace("video/mp4", "video/webm").replace("avc1.64001f", "vp9")).equals("dash-codec"), "");
        check("Profil: HEVC → dash-codec", profile(GOOD.replace("avc1.64001f", "hvc1.1.6.L93.B0")).equals("dash-codec"), "");
        check("Profil: unbekannte EssentialProperty am Video-Set schließt aus → dash-unsupported",
            profile(GOOD.replace(VSET, VSET + "<EssentialProperty schemeIdUri=\"urn:x:essential\"/>")).equals("dash-unsupported"), "");
        check("Profil: EssentialProperty im Bild-Set (Kacheln) stört nicht", profile(GOOD.replace("</Period>",
            "<AdaptationSet contentType=\"image\" mimeType=\"image/jpeg\"><SegmentTemplate media=\"t/$Number$.jpg\" duration=\"60\"/><Representation id=\"th\" bandwidth=\"1\"><EssentialProperty schemeIdUri=\"http://dashif.org/thumbnail_tile\" value=\"10x10\"/></Representation></AdaptationSet></Period>")).equals("ok"), "");
        check("Profil: contentType widerspricht mimeType → set-kind (ausgeschlossen)",
            profile(mpd("<AdaptationSet contentType=\"audio\" mimeType=\"video/mp4\" codecs=\"avc1.64001f\">" + TPL + VREP + "</AdaptationSet>")).equals("dash-no-video"), "");
        check("Profil: SubRepresentation → ausgeschlossen", profile(GOOD.replace(VREP, VREP.replace("/>", "><SubRepresentation level=\"0\"/></Representation>"))).equals("dash-unsupported"), "");
        check("Profil: Darstellung ohne Init → init-missing (dash-addressing)",
            profile(GOOD.replace(" initialization=\"$RepresentationID$/init.mp4\"", "")).equals("dash-addressing"), "");
        check("Profil: Ausschlussgrund je Darstellung sichtbar, andere Darstellung bleibt nutzbar",
            ok(() -> { DashProfile.Verdict v = DashProfile.check(parse(GOOD.replace(VREP, VREP + "<Representation id=\"h\" bandwidth=\"5\" codecs=\"hvc1.1.6.L93.B0\"/>")));
                return v.ok && v.video.size() == 1 && "codec".equals(v.excluded.get(1)); }), "");
    }

    // ---------------------------------------------------------------- vollständiger Plan
    static void plans(File fx) throws Exception {
        DashPlan p = DashPlan.build(DashManifest.parse(Files.readAllBytes(new File(fx, "kadri/vod-timeline-avc-aac.mpd").toPath()), DOC));
        DashPlan.Track v0 = p.video.get(0), de = p.audio.get(0);
        check("Plan (shaka-Stil): 3 Video × 2 Ton = 6 Optionen, alle Stücke im Periodenfenster", p.options.size() == 6 && v0.count() == 921 && de.count() == 919, p.options.size() + " " + v0.count() + " " + de.count());
        long sum = 0;
        for (int i = 0; i < v0.count(); i++) sum += v0.segment(i).duration;
        check("Plan: Summe der Stückdauern = Periodendauer (Video: 920 × 6 s + 5,12 s)", sum == 920L * 76800 + 65536 && v0.seconds().compareTo(new BigDecimal("5525.12")) == 0, sum);
        check("Plan: letztes Video-Stück ist das kurze (d=65536), Adresse mit $Time$", v0.segment(920).duration == 65536 && v0.segment(920).url.equals("https://cdn.example/v/video/0/70656000.m4s"), v0.segment(920).url);
        check("Plan: Ton (48 kHz, 6,016 s) endet am Periodenende – das letzte Stück ragt hinein, keines dahinter",
            de.segment(918).time == 918L * 288768 && de.seconds().compareTo(new BigDecimal("5525.12")) == 0, de.seconds());
        check("Plan: geschätzte Größe = (Video + Ton) × Dauer / 8", p.options.get(0).estimatedBytes == (long) Math.ceil((800000 + 128000) * 5525.12 / 8), p.options.get(0).estimatedBytes);
        check("Plan: Beschreibung ohne Adressen", p.options.get(0).describe().equals("360p avc1.64001e + mp4a.40.2 (de)") && !p.options.get(0).describe().contains("http"), p.options.get(0).describe());
        DashPlan q = DashPlan.build(DashManifest.parse(Files.readAllBytes(new File(fx, "kadri/vod-segmentlist.mpd").toPath()), DOC));
        check("Plan SegmentList: Reihenfolge der SegmentURL, BaseURL der MPD-Ebene, Init", urls(q.video.get(0).rep).equals(Arrays.asList(
            "https://cdn.example/v/media/v360/seg-1.m4s", "https://cdn.example/v/media/v360/seg-2.m4s", "https://cdn.example/v/media/v360/seg-3.m4s"))
            && q.video.get(0).initUrl().equals("https://cdn.example/v/media/v360/init.mp4"), urls(q.video.get(0).rep));
        // 20000 Stücke je Spur: Plan bleibt klein (Läufe), Zugriff auf das letzte Stück direkt
        String big = mpdDur("PT80000S", VSET + TPL + VREP + "</AdaptationSet>");
        DashPlan b = DashPlan.build(parse(big));
        check("Plan: genau 20000 Stücke je Spur erlaubt; Stück 19999 direkt berechnet", b.video.get(0).count() == 20000 && b.video.get(0).segment(19999).url.endsWith("/v/20000.m4s"), b.video.get(0).count());
        check("Plan: 20001 Stücke → Darstellung ausgeschlossen (segments) → dash-addressing", plan(big.replace("PT80000S", "PT80001S")).equals("dash-addressing"), plan(big.replace("PT80000S", "PT80001S")));
        check("Plan: zwei Ton-Spuren mit denselben Stückadressen (Vorlage ohne $RepresentationID$) oder demselben Init → track-collision",
            plan(mpd(VSET + TPL + VREP + "</AdaptationSet>" + ASET + "<SegmentTemplate timescale=\"1000\" duration=\"4000\" initialization=\"a/$RepresentationID$/init.mp4\" media=\"a/$Number$.m4s\"/>"
                + AREP + "<Representation id=\"a2\" bandwidth=\"64000\"/></AdaptationSet>")).equals("track-collision")
                && plan(mpd(VSET + TPL + VREP + "</AdaptationSet>" + ASET + "<SegmentTemplate timescale=\"1000\" duration=\"4000\" initialization=\"a/init.mp4\" media=\"a/$RepresentationID$/$Number$.m4s\"/>"
                + AREP + "<Representation id=\"a2\" bandwidth=\"64000\"/></AdaptationSet>")).equals("track-collision"), "");
        String a1 = "<SegmentTemplate timescale=\"1000\" duration=\"4000\" initialization=\"a/$RepresentationID$/init.mp4\" media=\"a/$Number$.m4s\"/>";
        check("Plan: Vorlagen, die gleiche Adressen bilden können (a/$Number$ und a/1$Number$ – „a/12“), → track-collision",
            plan(mpd(VSET + TPL + VREP + "</AdaptationSet>" + ASET + a1 + AREP + "</AdaptationSet>"
                + ASET.replace("lang=\"de\"", "lang=\"en\"") + a1.replace("a/$Number$", "a/1$Number$") + "<Representation id=\"a2\" bandwidth=\"64000\"/></AdaptationSet>")).equals("track-collision"), "");
        check("Plan: Init einer Spur ist ein Stück einer anderen → track-collision",
            plan(mpd(VSET + TPL + VREP + "</AdaptationSet>" + ASET + "<SegmentTemplate timescale=\"1000\" duration=\"4000\" initialization=\"v/7.m4s\" media=\"a/$Number$.m4s\"/>" + AREP + "</AdaptationSet>")).equals("track-collision"), "");
        check("Plan: verschiedene Ordner je Darstellung (übliche Vorlage) kollidieren nicht", plan(GOOD).equals("ok"), plan(GOOD));
        check("Plan: Profil-Gründe werden durchgereicht", plan(GOOD.replace("type=\"static\"", "type=\"dynamic\"")).equals("dash-live"), "");
        check("Plan: Zugriff außerhalb des Fensters wirft", ok(() -> { try { b.video.get(0).segment(20000); return false; } catch (IndexOutOfBoundsException e) { return true; } }), "");
    }

    // ---------------------------------------------------------------- Fingerabdruck v1
    static String fp(String xml) { try { return DashFingerprint.hex(parse(xml)); } catch (DashException e) { return "fatal:" + e.reason; } }

    static void fingerprint(File fx) throws Exception {
        String base = fp(GOOD);
        check("Fingerabdruck: 64 Hex-Zeichen, deterministisch", base.matches("[0-9a-f]{64}") && base.equals(fp(GOOD)), base);
        check("Fingerabdruck: kanonischer Text beginnt mit der Version", DashFingerprint.canonical(parse(GOOD)).startsWith("fp=kadri-dash-fp/1\n"), "");
        // unverändert bei Darstellungsdetails des XML
        String same1 = GOOD.replace("<?xml version=\"1.0\"?>", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!-- Kommentar -->");
        String same2 = GOOD.replace("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\"", "<dash:MPD xmlns:dash=\"urn:mpeg:dash:schema:mpd:2011\"").replace("</MPD>", "</dash:MPD>")
            .replaceAll("<(/?)(Period|AdaptationSet|SegmentTemplate|Representation)", "<$1dash:$2");
        String same3 = GOOD.replace("type=\"static\" mediaPresentationDuration=\"PT60S\"", "mediaPresentationDuration=\"PT60S\"  type=\"static\"");
        String same4 = GOOD.replace("<Period>", "<Period>\n  ").replace(" minBufferTime", "");
        check("Fingerabdruck unverändert: XML-Deklaration/Kommentar, Namensraum-Präfix, Attributreihenfolge, Leerraum zwischen Elementen",
            fp(same1).equals(base) && fp(same2).equals(base) && fp(same3).equals(base) && fp(same4).equals(base), fp(same2));
        check("Fingerabdruck unverändert: nicht aufgenommene Angaben (minBufferTime, profiles, segmentAlignment)",
            fp(GOOD.replace("type=\"static\"", "type=\"static\" minBufferTime=\"PT9S\" profiles=\"urn:x\"").replace(VSET, VSET.replace(">", " segmentAlignment=\"true\">"))).equals(base), "");
        // jede sicherheits-, identitäts- oder folgerelevante Änderung ändert ihn
        String[][] ch = {
            {"MPD-Dauer", "PT60S", "PT61S"}, {"Typ", "type=\"static\"", "type=\"dynamic\""},
            {"BaseURL der MPD", "<Period>", "<BaseURL>https://cdn.example/other/</BaseURL><Period>"},
            {"BaseURL der Periode", "<Period>", "<Period><BaseURL>p/</BaseURL>"},
            {"Query in der Vorlage", "media=\"$RepresentationID$/$Number$.m4s\"", "media=\"$RepresentationID$/$Number$.m4s?x=1\""},
            {"Format der Nummer", "$Number$.m4s", "$Number%05d$.m4s"},
            {"startNumber", "startNumber=\"1\"", "startNumber=\"0\""}, {"timescale", "timescale=\"1000\"", "timescale=\"1001\""},
            {"@duration", "duration=\"4000\"", "duration=\"4001\""},
            {"presentationTimeOffset", "startNumber=\"1\"", "startNumber=\"1\" presentationTimeOffset=\"5\""},
            {"Init-Adresse", "/init.mp4", "/init2.mp4"},
            {"Codec", "avc1.64001f", "avc1.640028"}, {"MIME", "audio/mp4", "audio/webm"}, {"Bandbreite", "bandwidth=\"128000\"", "bandwidth=\"128001\""},
            {"Sprache", "lang=\"de\"", "lang=\"en\""}, {"Höhe", "height=\"720\"", "height=\"721\""}, {"Darstellungs-ID", "id=\"a\"", "id=\"b\""},
            {"ContentProtection am Set", VSET, VSET + "<ContentProtection schemeIdUri=\"urn:mpeg:dash:mp4protection:2011\" value=\"cenc\"/>"},
            {"ContentProtection an der MPD", "<Period>", "<ContentProtection schemeIdUri=\"urn:x\"/><Period>"},
            {"Rolle", ASET, ASET + "<Role schemeIdUri=\"urn:mpeg:dash:role:2011\" value=\"dub\"/>"},
            {"Label", ASET, ASET + "<Label>Deutsch</Label>"},
            {"EssentialProperty", VSET, VSET + "<EssentialProperty schemeIdUri=\"urn:x\"/>"},
            {"Period@start", "<Period>", "<Period start=\"PT1S\">"},
            {"Periodendauer", "<Period>", "<Period duration=\"PT60S\">"},
            {"zusätzliche Darstellung", AREP, AREP + "<Representation id=\"a2\" bandwidth=\"64000\"/>"},
            {"Reihenfolge der Sets", VSET + TPL + VREP + "</AdaptationSet>" + ASET + TPL + AREP + "</AdaptationSet>", ASET + TPL + AREP + "</AdaptationSet>" + VSET + TPL + VREP + "</AdaptationSet>"},
            {"Ebene der Vorlage (Set → Darstellung)", VSET + TPL + VREP, VSET + VREP.replace("/>", ">" + TPL + "</Representation>")},
        };
        Set<String> seen = new HashSet<>(Arrays.asList(base));
        for (String[] c : ch) {
            String x = GOOD.replaceFirst(java.util.regex.Pattern.quote(c[1]), java.util.regex.Matcher.quoteReplacement(c[2]));
            String f = fp(x);
            check("Fingerabdruck ändert sich: " + c[0], !x.equals(GOOD) && f.matches("[0-9a-f]{64}") && !f.equals(base), f);
            seen.add(f);
        }
        check("Fingerabdruck: alle Änderungen paarweise verschieden", seen.size() == ch.length + 1, seen.size());
        String tlA = tl("<S t=\"0\" d=\"4000\" r=\"14\"/>", ""), tlB = tl("<S t=\"0\" d=\"4000\" r=\"13\"/><S d=\"4000\"/>", ""), tlC = tl("<S d=\"4000\" r=\"14\"/>", "");
        check("Fingerabdruck: vollständige SegmentTimeline (r, Aufteilung, explizites/implizites t) geht ein",
            !fp(tlA).equals(fp(tlB)) && !fp(tlA).equals(fp(tlC)) && !fp(tlB).equals(fp(tlC)), "");
        check("Fingerabdruck: SegmentList-Einträge und Byte-Bereiche gehen ein", ok(() -> {
            String l1 = mpd(VSET + "<SegmentList timescale=\"1\" duration=\"30\"><Initialization sourceURL=\"i.mp4\"/><SegmentURL media=\"a.m4s\"/><SegmentURL media=\"b.m4s\"/></SegmentList>" + VREP + "</AdaptationSet>");
            return !fp(l1).equals(fp(l1.replace("b.m4s", "c.m4s"))) && !fp(l1).equals(fp(l1.replace("media=\"b.m4s\"", "media=\"b.m4s\" mediaRange=\"0-9\""))); }), "");
        check("Fingerabdruck: Dokumentadresse geht nur über aufgelöste Adressen ein (absolute MPD: gleiche Abdrücke)", ok(() -> {
            String abs = GOOD.replace("<Period>", "<BaseURL>https://media.example/x/</BaseURL><Period>");
            return DashFingerprint.hex(DashManifest.parse(abs.getBytes(StandardCharsets.UTF_8), "https://a.example/1.mpd"))
                .equals(DashFingerprint.hex(DashManifest.parse(abs.getBytes(StandardCharsets.UTF_8), "https://b.example/2.mpd?t=9")))
                && !DashFingerprint.hex(DashManifest.parse(GOOD.getBytes(StandardCharsets.UTF_8), "https://a.example/1.mpd"))
                .equals(DashFingerprint.hex(DashManifest.parse(GOOD.getBytes(StandardCharsets.UTF_8), "https://b.example/1.mpd"))); }), "");
        check("Fingerabdruck: Maskierung – wörtliches „-“, „|“, Backslash und Zeilenumbruch eindeutig",
            DashFingerprint.esc(null).equals("-") && DashFingerprint.esc("-").equals("\\-") && DashFingerprint.esc("a|b").equals("a\\|b")
                && DashFingerprint.esc("a\\b").equals("a\\\\b") && DashFingerprint.esc("a\nb").equals("a\\nb") && DashFingerprint.esc("").equals(""), "");
        check("Fingerabdruck: Label mit Zeilenumbruch bleibt eine Zeile", !fp(GOOD.replace(ASET, ASET + "<Label>a\nb</Label>")).startsWith("fatal")
            && DashFingerprint.canonical(parse(GOOD.replace(ASET, ASET + "<Label>a\nb</Label>"))).contains("s.label=a\\nb\n"), "");
        // Golden-Text und Testvektoren (für die spätere Kadri-Implementierung in S3c)
        String golden = new String(Files.readAllBytes(new File(fx, "fp-v1-golden-segmentlist.txt").toPath()), StandardCharsets.UTF_8);
        String canonSl = DashFingerprint.canonical(DashManifest.parse(Files.readAllBytes(new File(fx, "kadri/vod-segmentlist.mpd").toPath()), DOC));
        check("Fingerabdruck: kanonischer Text von vod-segmentlist.mpd = Golden-Datei (Format v1 festgeschrieben)", golden.equals(canonSl), canonSl);
        Map<String, String> vec = new LinkedHashMap<>();
        for (String line : Files.readAllLines(new File(fx, "fp-v1-vectors.tsv").toPath(), StandardCharsets.UTF_8)) {
            if (line.startsWith("#") || line.isEmpty()) continue;
            String[] t = line.split("\t");
            vec.put(t[0] + "\t" + t[1], t[2]);
        }
        int okv = 0;
        for (Map.Entry<String, String> e : vec.entrySet()) {
            String[] t = e.getKey().split("\t");
            String got = DashFingerprint.hex(DashManifest.parse(Files.readAllBytes(new File(fx, t[0]).toPath()), t[1]));
            if (got.equals(e.getValue())) okv++; else System.out.println("     Vektor " + t[0] + ": " + got);
        }
        check("Fingerabdruck: Testvektoren (fp-v1-vectors.tsv) stimmen – " + vec.size() + " Fixtures", vec.size() >= 10 && okv == vec.size(), okv + "/" + vec.size());
    }

    // ---------------------------------------------------------------- Querprüfung mit Kadri
    static final String[] CROSS = {"kadri/vod-number-ffmpeg.mpd", "kadri/vod-timeline-avc-aac.mpd", "kadri/vod-segmentlist.mpd", "kadri/vod-baseurl-chain.mpd",
        "kadri/vod-av1-hevc-inherit.mpd", "kadri/live-dynamic.mpd", "kadri/vod-multi-period.mpd", "kadri/vod-thumbnails-muxed.mpd", "kadri/vod-prefixed-ns.mpd",
        "kadri/vod-drm-cenc.mpd", "s3a/s3a-timeline-pto.mpd", "s3a/s3a-inherit-query.mpd"};
    static final String MPD_URL = "https://cdn.example/v/manifest.mpd";
    /** Adressen aus Kadris eigenen Tests (tests/test_dash_mpd.py) zusätzlich zu den aus dem Plan erzeugten. */
    static final Map<String, String[]> KADRI_KEYS = new HashMap<>();
    static {
        KADRI_KEYS.put("kadri/vod-number-ffmpeg.mpd", new String[] {"https://cdn.example/v/chunk-stream0-00001.m4s", "https://cdn.example/v/chunk-stream0-00003.m4s?x=1",
            "https://cdn.example/v/chunk-stream1-00002.m4s", "https://cdn.example/v/init-stream0.m4s", "https://cdn.example/v/chunk-stream0-00001.mp4",
            "https://other.example/v/chunk-stream0-00001.m4s", "https://cdn.example/v/chunk-stream0-00292.m4s", "https://cdn.example/v/chunk-stream0-99999.m4s"});
        KADRI_KEYS.put("kadri/vod-timeline-avc-aac.mpd", new String[] {"https://cdn.example/v/video/0/0.m4s", "https://cdn.example/v/video/0/76800.m4s",
            "https://cdn.example/v/video/2/153600.m4s", "https://cdn.example/v/video/0/76801.m4s", "https://cdn.example/v/audio/de/288768.m4s",
            "https://cdn.example/v/audio/de/265664256.m4s"});
        KADRI_KEYS.put("kadri/vod-segmentlist.mpd", new String[] {"https://cdn.example/v/media/v360/seg-2.m4s", "https://cdn.example/v/media/a/seg-3.m4s",
            "https://cdn.example/v/media/v360/init.mp4"});
        KADRI_KEYS.put("kadri/vod-baseurl-chain.mpd", new String[] {"https://cdn2.example/content/p1/video/720/7.jpg", "https://cdn2.example/content/p1/lowres/360/2.jpg",
            "https://audio.example/a/seg-128000-0005.m4s?tok=1", "https://cdn2.example/content/p1/video/720/init.jpg", "https://cdn.example/v/720/7.jpg",
            "https://audio.example/a/seg-128000-0005.m4s"});
        KADRI_KEYS.put("kadri/vod-av1-hevc-inherit.mpd", new String[] {"https://cdn.example/v/av1-1080/4000.m4s", "https://cdn.example/v/hevc/8000.m4s", "https://cdn.example/v/a/3.m4s"});
        KADRI_KEYS.put("kadri/live-dynamic.mpd", new String[] {"https://cdn.example/v/V300/41.m4s"});
        KADRI_KEYS.put("s3a/s3a-timeline-pto.mpd", new String[] {"https://cdn.example/v/v/720/t540000.m4s", "https://cdn.example/v/v/720/t6300000.m4s",
            "https://cdn.example/v/a/seg-00022.m4s", "https://cdn.example/v/a/seg-00006.m4s"});
        KADRI_KEYS.put("s3a/s3a-inherit-query.mpd", new String[] {"https://cdn.example/root/p/a/init-96000.mp4", "https://cdn.example/root/vid/lo/s$x-5.m4s?v=lo&q=1",
            "https://cdn.example/root/vid/lo/s$x-1.m4s?v=lo", "https://cdn.example/root/vid/lo/s$x-1.m4s?q=1&v=lo"});
    }

    /** Bekannte, dokumentierte Abweichungen Kadri ↔ Plan (DASH-S3A.md, Abschnitt 5). */
    static final Map<String, String> KNOWN_DEV = new HashMap<>();
    static {
        KNOWN_DEV.put("s3a/s3a-inherit-query.mpd\thttps://cdn.example/root/p/a/init-96000.mp4", "Kadri setzt $Bandwidth%08d$ ohne Auffüllen ein (S3c)");
    }

    /** Stichprobe der Plan-Adressen je Darstellung: alle bei ≤ 60 Stücken, sonst Anfang, Ende und jede 50. */
    static List<String> sample(DashManifest.Representation r) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < r.count; i++) if (r.count <= 60 || i < 20 || i >= r.count - 20 || i % 50 == 0) out.add(r.segment(i).url);
        return out;
    }

    static void dumpKeys(File fx, File out) throws Exception {
        StringBuilder sb = new StringBuilder("# fixture\tmpdUrl\tkey (erzeugt von TestDash --dump-keys)\n");
        for (String f : CROSS) {
            DashManifest m = DashManifest.parse(Files.readAllBytes(new File(fx, f).toPath()), MPD_URL);
            Set<String> keys = new java.util.LinkedHashSet<>();
            for (DashManifest.Representation r : m.reps) {
                if (r.initUrl != null) keys.add(r.initUrl);
                if (r.excluded == null) keys.addAll(sample(r));
            }
            if (KADRI_KEYS.containsKey(f)) keys.addAll(Arrays.asList(KADRI_KEYS.get(f)));
            for (String k : keys) sb.append(f).append('\t').append(MPD_URL).append('\t').append(k).append('\n');
        }
        Files.write(out.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("keys written");
    }

    /** Schreibt Golden-Text und Testvektoren des Fingerabdrucks v1 (nur bei bewusster Formatänderung neu erzeugen). */
    static void writeFp(File fx) throws Exception {
        Files.write(new File(fx, "fp-v1-golden-segmentlist.txt").toPath(), DashFingerprint.canonical(DashManifest.parse(
            Files.readAllBytes(new File(fx, "kadri/vod-segmentlist.mpd").toPath()), DOC)).getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder("# kadri-dash-fp/1: fixture\tdocumentUrl\tsha256 – Testvektoren für Helper (S3a) und Kadri (S3c)\n");
        for (String f : CROSS) for (String u : new String[] {MPD_URL, DOC})
            sb.append(f).append('\t').append(u).append('\t').append(DashFingerprint.hex(DashManifest.parse(Files.readAllBytes(new File(fx, f).toPath()), u))).append('\n');
        Files.write(new File(fx, "fp-v1-vectors.tsv").toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("fingerprint vectors written");
    }

    /** Kadri-Kategorie der Adressierung (src/038 addressingOf) aus der Helper-Auswertung. */
    static String kadriAddr(DashManifest.Representation r) {
        switch (r.addressing) {
            case NUMBER_DURATION: return "template-duration";
            case NUMBER_TIMELINE: case TIME_TIMELINE: return r.tlRaw != null ? "template-timeline" : r.duration != null ? "template-duration" : "template-other";
            case LIST_DURATION: case LIST_TIMELINE: case LIST_SINGLE: return "list";
            case SEGMENT_BASE: return "base";
            default: return "single-file";
        }
    }

    static void crossCheck(File fx) throws Exception {
        File tsv = new File(fx, "kadri-index.tsv");
        List<String> lines = Files.readAllLines(tsv.toPath(), StandardCharsets.UTF_8);
        String header = lines.isEmpty() ? "" : lines.get(0);
        check("Kadri-Index: Herkunft angegeben (Kadri-Commit, SHA-256 von src/038)", header.contains("kadri=b4a8289") && header.matches(".*src038=[0-9a-f]{64}.*"), header);
        Map<String, List<String[]>> byFix = new LinkedHashMap<>();
        Map<String, List<String>> addr = new HashMap<>();
        for (String l : lines) {
            if (l.startsWith("#") || l.isEmpty()) continue;
            String[] t = l.split("\t", -1);
            if (t[0].equals("addr")) { addr.computeIfAbsent(t[1], x -> new ArrayList<>()).add(t[2]); continue; }
            byFix.computeIfAbsent(t[0], x -> new ArrayList<>()).add(t);
        }
        // Erwartete Kadri-Ergebnisse auch beim Erzeugen gleich: die Datei deckt genau die Plan-Stichprobe ab
        int allHits = 0, allDev = 0;
        for (String f : CROSS) {
            DashManifest m = DashManifest.parse(Files.readAllBytes(new File(fx, f).toPath()), MPD_URL);
            Map<String, int[]> seg = new HashMap<>();   // url → {repIndex, ordinal}
            Map<String, Set<Integer>> init = new HashMap<>();
            for (DashManifest.Representation r : m.reps) {
                if (r.initUrl != null) init.computeIfAbsent(r.initUrl, x -> new HashSet<>()).add(r.index);
                if (r.excluded == null) for (int i = 0; i < r.count; i++) seg.put(r.segment(i).url, new int[] {r.index, r.segment(i).ordinal});
            }
            List<String[]> rows = byFix.getOrDefault(f, new ArrayList<>());
            Map<Integer, Integer> map = new HashMap<>();   // Kadri-Darstellung → Helper-Darstellung (aus gemeinsamen Treffern)
            // Ersatz, wo es keinen gemeinsamen Treffer gibt: Kadri indiziert Vorlagen/Listen in Dokumentreihenfolge
            List<Integer> idxOrder = new ArrayList<>();
            for (DashManifest.Representation r : m.reps)
                if ((r.media != null && (r.tlRaw != null || r.duration != null)) || (r.list != null && r.list.size() >= 2)) idxOrder.add(r.index);
            List<String> bad = new ArrayList<>(), dev = new ArrayList<>();
            int hits = 0, nulls = 0;
            for (String[] t : rows) {
                String key = t[2], k = t[3];
                if (k.equals("-")) {
                    nulls++;
                    if (seg.containsKey(key)) bad.add("Kadri kein Treffer, Helper-Stück: " + key);
                    continue;
                }
                String[] rk = k.split(",");
                int kr = Integer.parseInt(rk[0]);
                if (rk[1].equals("init")) {
                    if (!init.containsKey(key)) {
                        if (KNOWN_DEV.containsKey(f + "\t" + key)) dev.add(KNOWN_DEV.get(f + "\t" + key) + ": " + key);
                        else bad.add("Kadri-Init, nicht im Plan: " + key);
                    }
                    continue;
                }
                int ki = Integer.parseInt(rk[1]);
                int[] h = seg.get(key);
                if (h == null) {
                    // erlaubt nur: Kadris Index reicht über das Periodenfenster hinaus (Erkennungsausschnitt ≠ Plan)
                    Integer hr = map.containsKey(kr) ? map.get(kr) : kr < idxOrder.size() ? idxOrder.get(kr) : null;
                    DashManifest.Representation r = hr == null ? null : m.reps.get(hr);
                    if (r != null && r.excluded == null && (ki < r.first || ki >= r.first + r.count)) dev.add("außerhalb des Fensters: " + key);
                    else if (r != null && r.excluded != null) dev.add("Darstellung im Plan ausgeschlossen (" + r.excluded + "): " + key);
                    else bad.add("Kadri-Treffer ohne Plan-Stück: " + key + " " + k);
                    continue;
                }
                hits++;
                if (h[1] != ki) bad.add("Ordinalzahl Kadri " + ki + " ≠ Plan " + h[1] + ": " + key);
                Integer prev = map.putIfAbsent(kr, h[0]);
                if (prev != null && prev != h[0]) bad.add("Darstellung uneinheitlich: " + key);
            }
            if (new HashSet<>(map.values()).size() != map.size()) bad.add("Zuordnung nicht eindeutig");
            // jede Stichproben-Adresse des Plans muss in Kadris Index treffen (wo Kadri die Darstellung indiziert)
            Set<String> kadriHit = new HashSet<>();
            for (String[] t : rows) if (!t[3].equals("-") && !t[3].endsWith("init")) kadriHit.add(t[2]);
            int missed = 0;
            for (DashManifest.Representation r : m.reps) {
                if (r.excluded != null || !map.containsValue(r.index)) {
                    if (r.excluded == null) for (String u : sample(r)) if (!kadriHit.contains(u)) { dev.add("Darstellung nicht in Kadris Index: " + r.id); break; }
                    continue;
                }
                for (String u : sample(r)) if (!kadriHit.contains(u)) missed++;
            }
            if (missed > 0) bad.add(missed + " Plan-Adressen ohne Kadri-Treffer");
            // Adressierungs-Semantik je Darstellung (Kadri summary.tracks[].addressing, Dokumentreihenfolge)
            List<String> ka = addr.getOrDefault(f, new ArrayList<>());
            List<String> ha = new ArrayList<>();
            for (DashManifest.Representation r : m.reps) ha.add(kadriAddr(r));
            if (!ka.equals(ha)) bad.add("Adressierung Kadri " + ka + " ≠ Helper " + ha);
            allHits += hits;
            allDev += dev.size();
            check("Querprüfung " + f + ": " + hits + " gemeinsame Stückadressen mit gleicher Darstellung und Ordinalzahl, " + nulls + " gemeinsame Nicht-Treffer"
                + (dev.isEmpty() ? "" : ", " + dev.size() + " dokumentierte Abweichung(en)"), bad.isEmpty() && (hits > 0 || f.contains("live") || f.contains("multi")), bad);
            for (String d : dev.subList(0, Math.min(3, dev.size()))) System.out.println("     Abweichung: " + d.replaceAll("https?://\\S+", "<Adresse>"));
        }
        check("Querprüfung insgesamt: gemeinsame Treffer > 500", allHits > 500, allHits);
        // ausdrücklich dokumentierte Abweichung: $Bandwidth%08d$ setzt Kadri ohne Nullen ein (Init passt dort nicht)
        List<String[]> iq = byFix.getOrDefault("s3a/s3a-inherit-query.mpd", new ArrayList<>());
        boolean kadriPlainBw = false, kadriPaddedBw = true;
        for (String[] t : iq) {
            if (t[2].endsWith("/a/init-96000.mp4")) kadriPlainBw = t[3].endsWith("init");
            if (t[2].endsWith("/a/init-00096000.mp4")) kadriPaddedBw = t[3].endsWith("init");
        }
        check("Dokumentierte Abweichung (Kadri, für S3c): $Bandwidth%08d$ ohne Auffüllen – Kadri-Init „init-96000“, Plan „init-00096000“",
            kadriPlainBw && !kadriPaddedBw, kadriPlainBw + "/" + kadriPaddedBw);
    }

    // ---------------------------------------------------------------- S3a-Review 1: Gegenbeispiele
    interface CondT { boolean get() throws Throwable; }

    /** Wie ok(), fängt aber auch OutOfMemoryError (sonst endete der Lauf ohne FAIL-Zeile). */
    static boolean okT(CondT c) {
        try { return c.get(); } catch (Throwable e) { System.out.println("     (" + e.getClass().getSimpleName() + ")"); return false; }
    }

    /** 160 Darstellungen, die eine SegmentList mit n Einträgen vom Set erben (Gegenprobe des Reviews: ≈ 700 KB). */
    static String inheritedList(int reps, int n, boolean ownBase) {
        StringBuilder sb = new StringBuilder(VSET + "<SegmentList timescale=\"1\" duration=\"4\"><Initialization sourceURL=\"i.mp4\"/>");
        for (int i = 0; i < n; i++) sb.append("<SegmentURL media=\"s/").append(String.format("%06d", i)).append(".m4s\"/>");
        sb.append("</SegmentList>");
        for (int i = 0; i < reps; i++) {
            sb.append("<Representation id=\"r").append(i).append("\" bandwidth=\"").append(1000 + i).append("\">");
            if (ownBase) sb.append("<BaseURL>b").append(i).append("/</BaseURL>");
            sb.append("</Representation>");
        }
        return mpdDur("PT" + (4L * n) + "S", sb.append("</AdaptationSet>").toString());
    }

    static void review1() throws Exception {
        // P0: geerbte SegmentList – je (Element, Basis) einmal aufgelöst und geteilt; Gesamtgrenze statt OOM
        String big = inheritedList(160, 20000, false);
        check("P0 Gegenprobe: MPD " + big.length() + " Bytes, 160 Darstellungen erben 20000 SegmentURL – gelesen ohne OOM (64 MB Heap), Liste geteilt",
            big.length() > 680_000 && okT(() -> {
                DashManifest m = parse(big);
                return m.reps.size() == 160 && m.reps.get(0).count == 20000 && m.reps.get(0).list == m.reps.get(159).list
                    && m.reps.get(159).segment(19999).url.equals("https://cdn.example/v/s/019999.m4s");
            }), "");
        check("P0: Fingerabdruck dieses MPD wird fortlaufend gehasht (3,2 Mio. Zeilen, kein Text im Speicher)",
            okT(() -> DashFingerprint.hex(parse(big)).matches("[0-9a-f]{64}")), "");
        check("P0: Plan für 160 Spuren mit derselben geerbten Liste → track-collision (ohne Aufzählung, kein OOM)",
            okT(() -> { try { DashPlan.build(parse(big)); return false; } catch (DashException e) { return e.reason.equals("track-collision"); } }), "");
        check("P0: dieselbe Liste mit eigener BaseURL je Darstellung (160 × 20000 neu aufzulösen) → kontrolliert limits",
            okT(() -> reason(inheritedList(160, 20000, true)).equals("limits")), "");
        check("P0: zehn eigene Basen × 20000 (200000 Auflösungen) sind gerade noch erlaubt",
            okT(() -> reason(inheritedList(10, 20000, true)).equals("accepted")), "");
        // geerbte Zeitleiste: gleiche Eingabe geteilt, verschiedene PTO je Darstellung → Gesamtgrenze der Auswertung
        StringBuilder tl = new StringBuilder();
        for (int i = 0; i < 20000; i++) tl.append("<S d=\"4\"/>");
        String tlSet = VSET + "<SegmentTemplate timescale=\"1\" media=\"$RepresentationID$/$Time$.m4s\" initialization=\"$RepresentationID$/i.mp4\"><SegmentTimeline>" + tl + "</SegmentTimeline></SegmentTemplate>";
        StringBuilder same = new StringBuilder(tlSet), diff = new StringBuilder(tlSet);
        for (int i = 0; i < 160; i++) {
            same.append("<Representation id=\"r").append(i).append("\" bandwidth=\"1000\"/>");
            diff.append("<Representation id=\"r").append(i).append("\" bandwidth=\"1000\"><SegmentTemplate presentationTimeOffset=\"").append(i).append("\"/></Representation>");
        }
        check("P0: geerbte Zeitleiste (20000 S) für 160 Darstellungen – geteilt, ohne Kopie je Darstellung",
            okT(() -> { DashManifest m = parse(mpdDur("PT80000S", same + "</AdaptationSet>")); return m.reps.get(159).count == 20000 && m.reps.get(0).runT == m.reps.get(159).runT; }), "");
        check("P0: 160 verschiedene PTO über dieselbe Zeitleiste (3,2 Mio. Stücke auszuwerten) → kontrolliert limits",
            okT(() -> reason(mpdDur("PT80000S", diff + "</AdaptationSet>")).equals("limits")), "");

        // P1: Marke des Zahlenplatzhalters darf mit nichts kollidieren
        String[][] mk = {
            {"9173917391739173", "$RepresentationID$/$Number$.m4s", "", "https://cdn.example/v/9173917391739173/1.m4s"},
            {"99999", "$RepresentationID$-$Bandwidth$-9$Number$9.m4s", "", "https://cdn.example/v/99999-999999-919.m4s"},
            {"v", "$Number$.m4s", "<BaseURL>9173917391739173/8998/899999998/</BaseURL>", "https://cdn.example/v/9173917391739173/8998/899999998/1.m4s"},
            {"v", "8$Number$8.m4s?x=999", "", "https://cdn.example/v/818.m4s?x=999"},
            {"999999999", "$RepresentationID$/$Number%05d$/$RepresentationID$.m4s", "<BaseURL>9999999999/</BaseURL>", "https://cdn.example/v/9999999999/999999999/00001/999999999.m4s"},
        };
        for (String[] t : mk) {
            String x = mpd(VSET + "<SegmentTemplate timescale=\"1000\" duration=\"4000\" initialization=\"i.mp4\" media=\"" + t[1] + "\"/><Representation id=\""
                + t[0] + "\" bandwidth=\"999999\">" + t[2] + "</Representation></AdaptationSet>");
            String got;
            try { got = parse(x).reps.get(0).segment(0).url; } catch (DashException e) { got = "fatal:" + e.reason; }
            check("P1 Marke: id „" + t[0] + "“, Vorlage „" + t[1] + "“" + (t[2].isEmpty() ? "" : " mit BaseURL aus Ziffern") + " → " + t[3], got.equals(t[3]), got);
        }
        check("P1 Marke: Fingerabdruck einer Vorlage mit {Number} unverändert", ok(() -> DashFingerprint.canonical(parse(GOOD)).contains("r.media=https://cdn.example/v/v/{Number}.m4s\n")), "");

        // P1: ContentProtection an jeder Platzierung zählt (fail-closed)
        String[][] cp = {
            {"direkt unter Period", GOOD.replace("<Period>", "<Period><ContentProtection schemeIdUri=\"urn:mpeg:dash:mp4protection:2011\" value=\"cenc\"/>")},
            {"in einer SegmentTemplate", GOOD.replaceFirst("initialization=\"\\$RepresentationID\\$/init.mp4\" media=\"\\$RepresentationID\\$/\\$Number\\$.m4s\"/>",
                "initialization=\"\\$RepresentationID\\$/init.mp4\" media=\"\\$RepresentationID\\$/\\$Number\\$.m4s\"><ContentProtection schemeIdUri=\"urn:x\"/></SegmentTemplate>")},
            {"in einer SubRepresentation", GOOD.replace(VREP, VREP.replace("/>", "><SubRepresentation level=\"0\"><ContentProtection schemeIdUri=\"urn:x\"/></SubRepresentation></Representation>"))},
            {"in einem Text-Set unter Role", GOOD.replace("</Period>", "<AdaptationSet mimeType=\"text/vtt\"><Role schemeIdUri=\"urn:x\"><ContentProtection schemeIdUri=\"urn:x\"/></Role></AdaptationSet></Period>")},
        };
        for (String[] c : cp) {
            String pr = profile(c[1]), pl = plan(c[1]);
            boolean diffFp = !fp(c[1]).equals(fp(GOOD));
            check("P1 ContentProtection " + c[0] + " → Profil und Plan dash-protected, Fingerabdruck verschieden", pr.equals("dash-protected") && pl.equals("dash-protected") && diffFp, pr + "/" + pl + "/" + diffFp);
        }
        check("P1: ohne ContentProtection bleibt der Fingerabdruck ohne Zusatzzeile (bestehende Vektoren unverändert)",
            ok(() -> !DashFingerprint.canonical(parse(GOOD)).contains("contentProtectionNested")), "");

        // P2: Init == eigenes Stück derselben Spur
        String sl = mpd(VSET + "<SegmentList timescale=\"1\" duration=\"30\"><Initialization sourceURL=\"s/a.m4s\"/><SegmentURL media=\"s/a.m4s\"/><SegmentURL media=\"s/b.m4s\"/></SegmentList>"
            + VREP + "</AdaptationSet>");
        check("P2: SegmentList mit Initialization@sourceURL == SegmentURL@media derselben Spur → track-collision", plan(sl).equals("track-collision"), plan(sl));
        check("P2: Gegenprobe – getrenntes Init ist erlaubt", plan(sl.replace("sourceURL=\"s/a.m4s\"", "sourceURL=\"s/init.mp4\"")).equals("ok"), "");
        check("P2: Vorlage, deren eigenes Init auf das eigene Muster passt (v/0.m4s zu v/$Number$.m4s) → track-collision",
            plan(mpd(VSET + "<SegmentTemplate timescale=\"1000\" duration=\"4000\" initialization=\"v/0.m4s\" media=\"v/$Number$.m4s\"/>" + VREP + "</AdaptationSet>")).equals("track-collision"), "");
    }

    // ---------------------------------------------------------------- Isolation
    static void isolation() throws Exception {
        File dir = new File("app/src/main/java/de/evp/helfer/dash");
        if (!dir.isDirectory()) dir = new File(System.getProperty("dash.src", "app/src/main/java/de/evp/helfer/dash"));
        List<String> bad = new ArrayList<>();
        File[] files = dir.listFiles();
        for (File f : files == null ? new File[0] : files) {
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            for (String w : new String[] {"java.net.", "okhttp", "okio", "android.", "HttpURLConnection", "SafeHttp", "Fetcher", "java.io.File", "FileOutputStream"})
                if (s.contains(w)) bad.add(f.getName() + ":" + w);
            if (s.matches("(?s).*import de\\.evp\\.helfer\\.hls\\.(?!HlsUri;|HlsException;).*")) bad.add(f.getName() + ": hls-Import");
        }
        check("Isolation: de.evp.helfer.dash ohne Netz, Dateien, Android-Typen; aus dem HLS-Kern nur HlsUri/HlsException", files != null && files.length >= 9 && bad.isEmpty(), bad);
    }
}
