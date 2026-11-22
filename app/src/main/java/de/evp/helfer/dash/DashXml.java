package de.evp.helfer.dash;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

/**
 * S3a: gehärtetes Lesen eines MPD zu einem kleinen Elementbaum – nur Elemente im MPD-Namensraum, alles begrenzt.
 *
 * Reihenfolge der Schutzmaßnahmen (jede für sich ausreichend gegen DTD-Angriffe, zusammen unabhängig von der
 * Parser-Implementierung auf JVM und Android):
 *  1. Bytes streng als UTF-8 dekodieren (BOM erlaubt; UTF-16 oder ungültige Folgen → "encoding"); eine
 *     XML-Deklaration darf nur UTF-8 nennen. Danach wird genau dieser Text geparst (kein zweites Dekodieren).
 *  2. Vorprüfung des Textes: jedes "<!" außer Kommentar und CDATA (DOCTYPE, ENTITY, ELEMENT, ATTLIST …) → "doctype".
 *     Ohne DTD gibt es keine eigenen Entitäten und keine externen Ressourcen.
 *  3. SAX-Parser namensraumbewusst, nicht validierend, ohne XInclude, mit Secure Processing und – soweit die
 *     Implementierung sie kennt – ohne DOCTYPE/externe Entitäten; ein EntityResolver, der jede Auflösung verweigert.
 *  4. Elemente aus dem XInclude-Namensraum → "xinclude"; Attribute aus dem XLink-Namensraum → "xlink".
 *  5. DASH-Elementnamen in einem fremden Namensraum → "namespace" (Kadri ordnet nach dem lokalen Namen zu; ein
 *     solcher Name wäre dort ein DASH-Element und hier nicht – mehrdeutig, also abgelehnt).
 *  6. Grenzen: Größe, Elemente (auch fremde, S, SegmentURL), Tiefe, Attribute je Element, Attribut- und Textlänge.
 * SegmentTimeline/S und SegmentList/SegmentURL werden kompakt (Zahlen bzw. Zeichenketten) statt als Elemente
 * gespeichert; der Speicherbedarf bleibt damit in der Größenordnung des Eingabetextes.
 */
final class DashXml {
    static final String NS_MPD = "urn:mpeg:dash:schema:mpd:2011";
    static final String NS_XLINK = "http://www.w3.org/1999/xlink";
    static final String NS_XINCLUDE = "http://www.w3.org/2001/XInclude";
    /** Absent-Markierung für S@t. */
    static final long NO_T = Long.MIN_VALUE;

    /** Alle Elementnamen des MPD-Schemas, die hier oder in Kadri eine Bedeutung haben (für die Namensraumprüfung). */
    static final Set<String> DASH_NAMES = new HashSet<>(Arrays.asList(
        "MPD", "Period", "AdaptationSet", "Representation", "SubRepresentation", "ContentComponent", "BaseURL",
        "SegmentBase", "SegmentList", "SegmentTemplate", "SegmentTimeline", "S", "SegmentURL", "Initialization",
        "RepresentationIndex", "BitstreamSwitching", "ContentProtection", "EssentialProperty", "SupplementalProperty",
        "Role", "Accessibility", "Label", "AudioChannelConfiguration", "Location", "PatchLocation", "EventStream",
        "InbandEventStream", "FailoverContent", "Preselection", "ProducerReferenceTime", "UTCTiming"));
    /** Elemente, deren Text gebraucht wird. */
    private static final Set<String> TEXT_NAMES = new HashSet<>(Arrays.asList("BaseURL", "Label"));

    static final class El {
        final String name;
        /** Attribute ohne Namensraum: lokaler Name; mit Namensraum: "{ns}lokal" (z. B. cenc:default_KID). */
        final Map<String, String> attrs;
        final List<El> kids = new ArrayList<>();
        StringBuilder text;
        /** SegmentTimeline: je S drei Werte t (NO_T = fehlt), d, r. */
        long[] s;
        int sCount;
        /** SegmentList: media und mediaRange je SegmentURL (null = fehlt). */
        List<String> listMedia, listRange;
        /** Weitere Attribute an S/SegmentURL (nicht unterstützt → wird beim Auswerten abgelehnt). */
        boolean entryExtra;

        El(String name, Map<String, String> attrs) { this.name = name; this.attrs = attrs; }

        String attr(String k) { return attrs.get(k); }

        List<El> all(String n) {
            List<El> out = new ArrayList<>();
            for (El k : kids) if (k.name.equals(n)) out.add(k);
            return out;
        }

        String trimmedText() { return text == null ? "" : xmlTrim(text.toString()); }
    }

    private DashXml() {}

    /** Entfernt nur XML-Leerraum (Leerzeichen, Tab, CR, LF) an den Rändern – so auch in Kadri festgelegt. */
    static String xmlTrim(String s) {
        int a = 0, b = s.length();
        while (a < b && isXmlSpace(s.charAt(a))) a++;
        while (b > a && isXmlSpace(s.charAt(b - 1))) b--;
        return s.substring(a, b);
    }

    static boolean isXmlSpace(char c) { return c == ' ' || c == '\t' || c == '\r' || c == '\n'; }

    private static final Pattern DECL_ENCODING = Pattern.compile("^<\\?xml[^>]*?\\sencoding\\s*=\\s*[\"']([^\"']*)[\"']");

    static String decode(byte[] body) throws DashException {
        if (body == null || body.length == 0) throw new DashException("empty");
        if (body.length > DashLimits.MAX_MANIFEST_BYTES) throw new DashException("too-large");
        int off = 0;
        if (body.length >= 3 && (body[0] & 0xff) == 0xef && (body[1] & 0xff) == 0xbb && (body[2] & 0xff) == 0xbf) off = 3;
        else if (body.length >= 2 && (((body[0] & 0xff) == 0xfe && (body[1] & 0xff) == 0xff) || ((body[0] & 0xff) == 0xff && (body[1] & 0xff) == 0xfe)))
            throw new DashException("encoding");
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body, off, body.length - off)).toString();
        } catch (CharacterCodingException e) {
            throw new DashException("encoding");
        }
        if (text.indexOf('\u0000') >= 0) throw new DashException("encoding");
        Matcher m = DECL_ENCODING.matcher(text);
        if (m.find() && !"utf-8".equals(m.group(1).toLowerCase(Locale.ROOT))) throw new DashException("encoding");
        return text;
    }

    /** Jedes Markup "<!" außer Kommentar und CDATA – vor dem Parser, unabhängig von dessen Fähigkeiten. */
    static void prescan(String text) throws DashException {
        int i = -1;
        while ((i = text.indexOf("<!", i + 1)) >= 0) {
            if (text.startsWith("<!--", i)) {
                int end = text.indexOf("-->", i + 4);
                if (end < 0) throw new DashException("xml");
                i = end + 2;
                continue;
            }
            if (text.startsWith("<![CDATA[", i)) {
                int end = text.indexOf("]]>", i + 9);
                if (end < 0) throw new DashException("xml");
                i = end + 2;
                continue;
            }
            throw new DashException("doctype");
        }
    }

    private static void feature(SAXParserFactory f, String name, boolean on) {
        try { f.setFeature(name, on); } catch (Exception e) { /* nicht jede Implementierung kennt jedes Merkmal */ }
    }

    static El parse(byte[] body) throws DashException {
        String text = decode(body);
        prescan(text);
        SAXParserFactory f = SAXParserFactory.newInstance();
        f.setNamespaceAware(true);
        f.setValidating(false);
        try { f.setXIncludeAware(false); } catch (Exception e) { /* Android: nicht unterstützt, dort ohnehin aus */ }
        feature(f, XMLConstants.FEATURE_SECURE_PROCESSING, true);
        feature(f, "http://apache.org/xml/features/disallow-doctype-decl", true);
        feature(f, "http://xml.org/sax/features/external-general-entities", false);
        feature(f, "http://xml.org/sax/features/external-parameter-entities", false);
        feature(f, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        Handler h = new Handler();
        try {
            SAXParser p = f.newSAXParser();
            org.xml.sax.XMLReader r = p.getXMLReader();
            r.setEntityResolver((publicId, systemId) -> { throw new SAXException(new Stop("entity")); });
            r.setContentHandler(h);
            r.setErrorHandler(h);
            r.parse(new InputSource(new StringReader(text)));
        } catch (SAXException e) {
            Throwable c = e.getException() != null ? e.getException() : e.getCause();
            if (c instanceof Stop) throw new DashException(((Stop) c).reason);
            if (h.stop != null) throw new DashException(h.stop);
            throw new DashException("xml");   // DOCTYPE/ENTITY hat die Vorprüfung schon abgelehnt
        } catch (Exception e) {
            throw new DashException("xml");
        }
        if (h.root == null) throw new DashException("not-mpd");
        return h.root;
    }

    /** Interne Abbruchursache (über SAXException transportiert). */
    static final class Stop extends Exception {
        final String reason;
        Stop(String reason) { super(reason, null, false, false); this.reason = reason; }
    }

    private static final class Handler extends DefaultHandler {
        El root;
        String stop;
        final List<El> stack = new ArrayList<>();   // null-Einträge: fremdes Element (übersprungen)
        int elements, timeline, list;

        private SAXException fail(String reason) {
            stop = reason;
            return new SAXException(new Stop(reason));
        }

        @Override
        public void startElement(String uri, String local, String qName, Attributes a) throws SAXException {
            if (++elements > DashLimits.MAX_ELEMENTS) throw fail("limits");
            if (stack.size() + 1 > DashLimits.MAX_DEPTH) throw fail("depth");
            if (NS_XINCLUDE.equals(uri)) throw fail("xinclude");
            if (a.getLength() > DashLimits.MAX_ATTRS) throw fail("attrs");
            Map<String, String> attrs = new LinkedHashMap<>();
            for (int i = 0; i < a.getLength(); i++) {
                String an = a.getURI(i), ln = a.getLocalName(i), v = a.getValue(i);
                if (ln == null || ln.isEmpty()) ln = a.getQName(i);
                if (v.length() > DashLimits.MAX_ATTR_CHARS) throw fail("attr-length");
                if (NS_XLINK.equals(an)) throw fail("xlink");
                if (XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(an) || ln.startsWith("xmlns")) continue;
                if (an == null || an.isEmpty()) attrs.put(ln, v);
                else attrs.put("{" + an + "}" + ln, v);
            }
            El parent = stack.isEmpty() ? null : stack.get(stack.size() - 1);
            boolean dash = NS_MPD.equals(uri);
            if (root == null) {
                if (!dash || !"MPD".equals(local)) throw fail("not-mpd");
                root = new El(local, attrs);
                stack.add(root);
                return;
            }
            if (!dash) {
                if (DASH_NAMES.contains(local)) throw fail("namespace");
                stack.add(null);
                return;
            }
            if (parent == null) throw fail("namespace");   // DASH-Element innerhalb eines fremden Elements
            if ("S".equals(local)) {
                if (!"SegmentTimeline".equals(parent.name)) throw fail("structure");
                if (++timeline > DashLimits.MAX_TIMELINE_ENTRIES) throw fail("limits");
                long t = NO_T, d, r = 0;
                for (String k : attrs.keySet()) if (!k.equals("t") && !k.equals("d") && !k.equals("r")) parent.entryExtra = true;
                try {
                    if (attrs.containsKey("t")) t = DashNum.parseU(attrs.get("t"));
                    if (!attrs.containsKey("d")) throw new DashException("timeline-attr");
                    d = DashNum.parseU(attrs.get("d"));
                    if (attrs.containsKey("r")) r = DashNum.parseR(attrs.get("r"));
                } catch (DashException e) {
                    throw fail(e.reason);
                }
                if (parent.s == null) parent.s = new long[48];
                if (parent.sCount * 3 + 3 > parent.s.length) parent.s = Arrays.copyOf(parent.s, parent.s.length * 2);
                parent.s[parent.sCount * 3] = t;
                parent.s[parent.sCount * 3 + 1] = d;
                parent.s[parent.sCount * 3 + 2] = r;
                parent.sCount++;
                El marker = new El("S", attrs);   // nur als Stapeleintrag (keine Kinder erlaubt)
                stack.add(marker);
                return;
            }
            if ("SegmentURL".equals(local)) {
                if (!"SegmentList".equals(parent.name)) throw fail("structure");
                if (++list > DashLimits.MAX_LIST_ENTRIES) throw fail("limits");
                for (String k : attrs.keySet()) if (!k.equals("media") && !k.equals("mediaRange")) parent.entryExtra = true;
                if (parent.listMedia == null) { parent.listMedia = new ArrayList<>(); parent.listRange = new ArrayList<>(); }
                parent.listMedia.add(attrs.get("media"));
                parent.listRange.add(attrs.get("mediaRange"));
                stack.add(new El("SegmentURL", attrs));
                return;
            }
            if ("S".equals(parent.name) || "SegmentURL".equals(parent.name)) throw fail("structure");
            El e = new El(local, attrs);
            parent.kids.add(e);
            stack.add(e);
        }

        @Override
        public void endElement(String uri, String local, String qName) {
            stack.remove(stack.size() - 1);
        }

        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
            if (stack.isEmpty()) return;
            El top = stack.get(stack.size() - 1);
            if (top == null || !TEXT_NAMES.contains(top.name)) return;
            if (top.text == null) top.text = new StringBuilder();
            if (top.text.length() + length > DashLimits.MAX_TEXT_CHARS) throw fail("text-length");
            top.text.append(ch, start, length);
        }

        @Override
        public void fatalError(SAXParseException e) throws SAXException { throw e; }

        @Override
        public void error(SAXParseException e) throws SAXException { throw e; }
    }
}
