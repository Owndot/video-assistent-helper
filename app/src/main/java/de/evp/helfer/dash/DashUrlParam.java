package de.evp.helfer.dash;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * S4b-8: Annex I von ISO/IEC 23009-1 („flexible insertion of URL parameters“) in der sicheren Minimalform. Grundlage: das
 * MPEG-Schema DASH-MPD-UP.xsd (5th Ed.) und der DASH-IF-Referenzplayer dash.js (ExtUrlQueryInfoController); der Normtext lag
 * nicht vor. Kadri (src/039 dfpUrlParam) entscheidet identisch – geprüft über die gemeinsamen Golden-Vektoren dash-gate/1.
 *
 * Unterstützt: EssentialProperty oder SupplementalProperty (Period: nur Supplemental, wie im Schema) mit
 * schemeIdUri="urn:mpeg:dash:urlparam:2014", ohne @value, mit genau einem Kind UrlQueryInfo (Namensraum
 * urn:mpeg:dash:schema:urlparam:2014) mit genau queryTemplate="$querypart$" und useMPDUrlQuery="true" (oder "1"), sonst
 * nichts. Wirkung: an jede Init- und Stückadresse der Darstellungen unter dem Element wird die Query der ENDGÜLTIGEN
 * MPD-Adresse (nach Weiterleitungen; dieselbe Basis wie für relative Adressen, RFC 3986 5.1.3) unverändert angehängt – „?“
 * bzw. „&“ hinter einer vorhandenen Query, ohne Umkodieren, Sortieren oder Entfernen doppelter Parameter.
 *
 * Alles andere aus der Familie (2016/2025, urn:mpeg:dash:state:, ExtUrlQueryInfo, ExtHttpHeaderInfo, queryString,
 * $query:…$, Kindelemente, fremde Attribute, andere Stellen) wird nicht stillschweigend gedeutet, sondern abgelehnt:
 * fatal für das ganze MPD mit festem Grund (urlparam-unsupported, -template, -conflict, -limits, -query).
 * Ohne Descriptor wird nie eine Query übernommen.
 */
final class DashUrlParam {
    static final String SCHEME = "urn:mpeg:dash:urlparam:2014";
    static final String QUERY_INFO = "{" + DashXml.NS_URLPARAM + "}UrlQueryInfo";
    static final String QUERYPART = "$querypart$";
    private static final String BAD = "\"<>\\^`{|}'#[]";

    /** Kein Descriptor im MPD: keine Wirkung. */
    static final DashUrlParam NONE = new DashUrlParam("");

    /** Query der Dokumentadresse ohne „?“ ("" = keine). */
    final String query;
    /** Elemente (MPD, Period, AdaptationSet, Representation) mit wirksamem Descriptor. */
    private final IdentityHashMap<DashXml.El, Boolean> at = new IdentityHashMap<>();

    private DashUrlParam(String query) { this.query = query; }

    /** Gehört der Descriptor zur Annex-I-Familie (jede Form, auch nicht unterstützte)? */
    static boolean family(String scheme) {
        return scheme != null && (scheme.startsWith("urn:mpeg:dash:urlparam:") || scheme.startsWith("urn:mpeg:dash:state:"));
    }

    private static boolean isDescriptor(DashXml.El e) {
        return (e.name.equals("EssentialProperty") || e.name.equals("SupplementalProperty")) && family(e.attr("schemeIdUri"));
    }

    private static int countAll(DashXml.El e) {
        int n = isDescriptor(e) ? 1 : 0;
        for (DashXml.El k : e.kids) n += countAll(k);
        return n;
    }

    private static List<DashXml.El> own(DashXml.El e) {
        List<DashXml.El> out = new ArrayList<>();
        for (DashXml.El k : e.kids) if (isDescriptor(k)) out.add(k);
        return out;
    }

    /** Prüft alle Descriptoren des MPD (Reihenfolge der Prüfungen wie in Kadri) und liefert, wo sie wirken. */
    static DashUrlParam evaluate(DashXml.El root, String doc) throws DashException {
        int total = countAll(root);
        if (total == 0) return NONE;
        if (total > DashLimits.MAX_URLPARAM_DESCRIPTORS) throw new DashException("urlparam-limits");
        // 1. Stellen: nur direkt unter MPD, Period (nur Supplemental), AdaptationSet und Representation
        List<DashXml.El> placed = new ArrayList<>(own(root));
        for (DashXml.El pe : root.all("Period")) {
            for (DashXml.El d : own(pe)) {
                if (d.name.equals("EssentialProperty")) throw new DashException("urlparam-unsupported");
                placed.add(d);
            }
            for (DashXml.El ae : pe.all("AdaptationSet")) {
                placed.addAll(own(ae));
                for (DashXml.El re : ae.all("Representation")) placed.addAll(own(re));
            }
        }
        if (placed.size() != total) throw new DashException("urlparam-unsupported");
        // 2. Form jedes Descriptors (Dokumentreihenfolge je Ebene wie oben)
        for (DashXml.El d : placed) check(d);
        // 3. höchstens einer je Element und entlang jeder Kette MPD → Period → AdaptationSet → Representation
        int m = own(root).size();
        if (m > 1) throw new DashException("urlparam-conflict");
        for (DashXml.El pe : root.all("Period")) {
            int p = own(pe).size();
            if (p > 1 || m + p > 1 && p > 0) throw new DashException("urlparam-conflict");
            for (DashXml.El ae : pe.all("AdaptationSet")) {
                int a = own(ae).size();
                if (a > 1 || m + p + a > 1 && a > 0) throw new DashException("urlparam-conflict");
                for (DashXml.El re : ae.all("Representation")) {
                    int r = own(re).size();
                    if (r > 1 || m + p + a + r > 1 && r > 0) throw new DashException("urlparam-conflict");
                }
            }
        }
        // 4. die übernommene Query: Grenzen, dann Zeichen (wie eine Adressreferenz, sonst würden RFC 3986 und WHATWG abweichen)
        int qm = doc.indexOf('?');
        String q = qm < 0 ? "" : doc.substring(qm + 1);
        if (q.length() > DashLimits.MAX_URLPARAM_QUERY_CHARS) throw new DashException("urlparam-limits");
        if (!q.isEmpty() && q.split("&", -1).length > DashLimits.MAX_URLPARAM_PARAMS) throw new DashException("urlparam-limits");
        for (int i = 0; i < q.length(); i++) {
            char c = q.charAt(i);
            if (c <= 0x20 || c >= 0x7f || BAD.indexOf(c) >= 0) throw new DashException("urlparam-query");
            if (c == '%' && (i + 2 >= q.length() || hex(q.charAt(i + 1)) < 0 || hex(q.charAt(i + 2)) < 0)) throw new DashException("urlparam-query");
        }
        DashUrlParam up = new DashUrlParam(q);
        for (DashXml.El d : placed) up.at.put(owner(root, d), Boolean.TRUE);
        return up;
    }

    private static DashXml.El owner(DashXml.El root, DashXml.El d) {
        if (root.kids.contains(d)) return root;
        for (DashXml.El pe : root.all("Period")) {
            if (pe.kids.contains(d)) return pe;
            for (DashXml.El ae : pe.all("AdaptationSet")) {
                if (ae.kids.contains(d)) return ae;
                for (DashXml.El re : ae.all("Representation")) if (re.kids.contains(d)) return re;
            }
        }
        return root;   // (nicht erreichbar: alle Descriptoren stehen an geprüften Stellen)
    }

    private static void check(DashXml.El d) throws DashException {
        if (!SCHEME.equals(d.attr("schemeIdUri"))) throw new DashException("urlparam-unsupported");
        for (String k : d.attrs.keySet()) if (!k.equals("schemeIdUri") && !k.equals("id")) throw new DashException("urlparam-unsupported");
        if (d.foreignKids || d.kids.size() != 1 || !QUERY_INFO.equals(d.kids.get(0).name)) throw new DashException("urlparam-unsupported");
        DashXml.El q = d.kids.get(0);
        if (q.foreignKids || !q.kids.isEmpty()) throw new DashException("urlparam-unsupported");
        for (String k : q.attrs.keySet()) if (!k.equals("queryTemplate") && !k.equals("useMPDUrlQuery")) throw new DashException("urlparam-unsupported");
        String use = q.attr("useMPDUrlQuery");
        if (!"true".equals(use) && !"1".equals(use)) throw new DashException("urlparam-unsupported");
        if (!QUERYPART.equals(q.attr("queryTemplate"))) throw new DashException("urlparam-template");
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    /** Query für eine Darstellung (Kette MPD, Period, AdaptationSet, Representation); null = kein Descriptor wirkt. */
    String queryFor(DashXml.El mpd, DashXml.El period, DashXml.El set, DashXml.El rep) {
        if (at.isEmpty()) return null;
        return at.containsKey(mpd) || at.containsKey(period) || at.containsKey(set) || at.containsKey(rep) ? query : null;
    }

    /** Hängt die Query an eine Adresse: „?“ oder – wenn sie schon eine Query hat – „&“; leer/null: unverändert. */
    static String append(String url, String q) {
        if (q == null || q.isEmpty() || url == null) return url;
        return url + (url.indexOf('?') >= 0 ? '&' : '?') + q;
    }
}
