package de.evp.helfer;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * DASH-Stream (.mpd) herunterladen: beste Bildspur + beste Tonspur als fMP4 (danach zu MP4 zusammensetzen).
 * Kann: SegmentTemplate (mit SegmentTimeline oder fester Dauer, $Number$/$Time$/$RepresentationID$/$Bandwidth$),
 * SegmentList, SegmentBase/BaseURL (eine Datei je Spur), BaseURL auf allen Ebenen, mehrere Perioden (nimmt die längste).
 * Nicht: Livestreams (type="dynamic"), Kopierschutz (ContentProtection).
 */
public class DashCore {
    private final HlsCore seg;
    private final Fetcher net;
    private final File work;

    public DashCore(Fetcher net, File workDir, int threads) {
        this.net = net;
        this.work = workDir;
        this.seg = new HlsCore(net, workDir, threads);
    }

    static double isoDur(String s) {
        if (s == null) return 0;
        Matcher m = Pattern.compile("P(?:(\\d+(?:\\.\\d+)?)D)?(?:T(?:(\\d+(?:\\.\\d+)?)H)?(?:(\\d+(?:\\.\\d+)?)M)?(?:(\\d+(?:\\.\\d+)?)S)?)?").matcher(s.trim());
        if (!m.matches()) return 0;
        double d = 0;
        if (m.group(1) != null) d += Double.parseDouble(m.group(1)) * 86400;
        if (m.group(2) != null) d += Double.parseDouble(m.group(2)) * 3600;
        if (m.group(3) != null) d += Double.parseDouble(m.group(3)) * 60;
        if (m.group(4) != null) d += Double.parseDouble(m.group(4));
        return d;
    }

    static List<Element> kids(Element e, String name) {
        List<Element> out = new ArrayList<>();
        if (e == null) return out;
        NodeList nl = e.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n instanceof Element) {
                String ln = n.getLocalName() != null ? n.getLocalName() : n.getNodeName();
                if (ln.equals(name) || ln.endsWith(":" + name)) out.add((Element) n);
            }
        }
        return out;
    }

    static Element kid(Element e, String name) {
        List<Element> k = kids(e, name);
        return k.isEmpty() ? null : k.get(0);
    }

    static String base(Element e, String parent) {
        Element b = kid(e, "BaseURL");
        if (b == null) return parent;
        String t = b.getTextContent();
        return t == null || t.trim().isEmpty() ? parent : HlsCore.resolve(parent, t.trim());
    }

    /** Attribut von der nächstliegenden Ebene (Representation → AdaptationSet → Period) */
    static String attr(String name, Element... chain) {
        for (Element e : chain) if (e != null && e.hasAttribute(name)) return e.getAttribute(name);
        return null;
    }

    static String mime(Element as, Element rep) {
        String m = attr("mimeType", rep, as);
        String ct = attr("contentType", rep, as);
        if (ct != null && !ct.isEmpty()) return ct.toLowerCase(Locale.ROOT);
        if (m == null) {
            String codecs = attr("codecs", rep, as);
            if (codecs != null && (codecs.startsWith("mp4a") || codecs.startsWith("opus") || codecs.startsWith("ac-3") || codecs.startsWith("ec-3"))) return "audio";
            return "";
        }
        return m.startsWith("video") ? "video" : m.startsWith("audio") ? "audio" : m;
    }

    static String fill(String tpl, String repId, String bw, long number, long time) {
        StringBuilder sb = new StringBuilder();
        Matcher m = Pattern.compile("\\$(RepresentationID|Number|Bandwidth|Time|)(%0(\\d+)d)?\\$").matcher(tpl);
        int last = 0;
        while (m.find()) {
            sb.append(tpl, last, m.start());
            String id = m.group(1);
            String val;
            switch (id) {
                case "": val = "$"; break;
                case "RepresentationID": val = repId; break;
                case "Bandwidth": val = bw; break;
                case "Number": val = Long.toString(number); break;
                default: val = Long.toString(time); break;
            }
            if (m.group(3) != null && !id.equals("RepresentationID")) {
                int w = Integer.parseInt(m.group(3));
                while (val.length() < w) val = "0" + val;
            }
            sb.append(val);
            last = m.end();
        }
        sb.append(tpl.substring(last));
        return sb.toString();
    }

    static long[] range(String r) {
        if (r == null || !r.contains("-")) return null;
        String[] p = r.split("-");
        long a = Long.parseLong(p[0].trim()), b = Long.parseLong(p[1].trim());
        return new long[]{a, b - a + 1};
    }

    static boolean protectedBy(Element as, Element rep) {
        return !kids(as, "ContentProtection").isEmpty() || (rep != null && !kids(rep, "ContentProtection").isEmpty());
    }

    /** Teile einer Spur als Liste (wie bei HLS) */
    HlsCore.Media media(Element period, Element as, Element rep, String baseUrl, double periodDur) throws IOException {
        HlsCore.Media m = new HlsCore.Media();
        m.ended = true;
        m.fmp4 = true;
        String repId = rep.getAttribute("id"), bw = rep.getAttribute("bandwidth");
        Element tRep = kid(rep, "SegmentTemplate"), tAs = kid(as, "SegmentTemplate"), tPer = kid(period, "SegmentTemplate");
        Element lRep = kid(rep, "SegmentList"), lAs = kid(as, "SegmentList"), lPer = kid(period, "SegmentList");
        if (tRep != null || tAs != null || tPer != null) {
            String media = attr("media", tRep, tAs, tPer);
            String init = attr("initialization", tRep, tAs, tPer);
            long timescale = Long.parseLong(def(attr("timescale", tRep, tAs, tPer), "1"));
            long startNumber = Long.parseLong(def(attr("startNumber", tRep, tAs, tPer), "1"));
            long pto = Long.parseLong(def(attr("presentationTimeOffset", tRep, tAs, tPer), "0"));
            String durA = attr("duration", tRep, tAs, tPer);
            if (init == null) {
                Element ie = null;
                for (Element t : new Element[]{tRep, tAs, tPer}) if (ie == null && t != null) ie = kid(t, "Initialization");
                if (ie != null) init = ie.getAttribute("sourceURL");
            }
            if (init != null && !init.isEmpty()) {
                HlsCore.Seg s = new HlsCore.Seg();
                s.url = HlsCore.resolve(baseUrl, fill(init, repId, bw, 0, 0));
                s.init = true;
                m.init = s;
            }
            if (media == null) throw new IOException(Tr.t("DASH: keine Teil-Adressen", "DASH: no segment addresses"));
            Element timeline = null;
            for (Element t : new Element[]{tRep, tAs, tPer}) if (timeline == null && t != null) timeline = kid(t, "SegmentTimeline");
            long number = startNumber;
            if (timeline != null) {
                long time = 0;
                long endTicks = periodDur > 0 ? (long) (periodDur * timescale) + pto : Long.MAX_VALUE;
                for (Element s : kids(timeline, "S")) {
                    if (s.hasAttribute("t")) time = Long.parseLong(s.getAttribute("t"));
                    long d = Long.parseLong(s.getAttribute("d"));
                    long r = s.hasAttribute("r") ? Long.parseLong(s.getAttribute("r")) : 0;
                    long count = r >= 0 ? r + 1 : (endTicks == Long.MAX_VALUE ? 1 : Math.max(1, (endTicks - time + d - 1) / d));
                    for (long k = 0; k < count; k++) {
                        HlsCore.Seg g = new HlsCore.Seg();
                        g.url = HlsCore.resolve(baseUrl, fill(media, repId, bw, number, time));
                        m.segs.add(g);
                        number++;
                        time += d;
                    }
                }
            } else if (durA != null) {
                double segDur = Double.parseDouble(durA) / timescale;
                if (periodDur <= 0 || segDur <= 0) throw new IOException(Tr.t("DASH: Länge unbekannt", "DASH: length unknown"));
                long count = (long) Math.ceil(periodDur / segDur - 1e-6);
                if (count > 50000) throw new IOException(Tr.t("DASH: zu viele Teile", "DASH: too many segments"));
                for (long k = 0; k < count; k++) {
                    HlsCore.Seg g = new HlsCore.Seg();
                    g.url = HlsCore.resolve(baseUrl, fill(media, repId, bw, startNumber + k, (long) (k * Double.parseDouble(durA))));
                    m.segs.add(g);
                }
            } else {
                // nur $Number$ ohne Dauer/Timeline: ein einzelnes Teil annehmen
                HlsCore.Seg g = new HlsCore.Seg();
                g.url = HlsCore.resolve(baseUrl, fill(media, repId, bw, startNumber, 0));
                m.segs.add(g);
            }
            return m;
        }
        Element list = lRep != null ? lRep : lAs != null ? lAs : lPer;
        if (list != null) {
            Element ie = kid(list, "Initialization");
            if (ie != null) {
                HlsCore.Seg s = new HlsCore.Seg();
                String src = ie.getAttribute("sourceURL");
                s.url = src.isEmpty() ? baseUrl : HlsCore.resolve(baseUrl, src);
                long[] r = range(ie.getAttribute("range"));
                if (r != null) { s.off = r[0]; s.len = r[1]; }
                s.init = true;
                m.init = s;
            }
            for (Element su : kids(list, "SegmentURL")) {
                HlsCore.Seg g = new HlsCore.Seg();
                String med = su.getAttribute("media");
                g.url = med.isEmpty() ? baseUrl : HlsCore.resolve(baseUrl, med);
                long[] r = range(su.getAttribute("mediaRange"));
                if (r != null) { g.off = r[0]; g.len = r[1]; }
                m.segs.add(g);
            }
            if (m.segs.isEmpty()) throw new IOException(Tr.t("DASH: leere Teil-Liste", "DASH: empty segment list"));
            return m;
        }
        // SegmentBase oder nur BaseURL: die ganze Spur ist eine Datei
        HlsCore.Seg g = new HlsCore.Seg();
        g.url = baseUrl;
        m.segs.add(g);
        return m;
    }

    static String def(String v, String d) { return v == null || v.isEmpty() ? d : v; }

    public HlsCore.Result run(String url, HlsCore.Listener l) throws Exception {
        if (!work.exists() && !work.mkdirs()) throw new IOException(Tr.t("Kein Arbeitsordner", "No working folder"));
        byte[] xml = net.fetch(url, -1, -1);
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (Exception ignored) {}
        DocumentBuilder b = f.newDocumentBuilder();
        Document doc = b.parse(new ByteArrayInputStream(xml));
        Element mpd = doc.getDocumentElement();
        if ("dynamic".equals(mpd.getAttribute("type"))) throw new IOException(Tr.t("Livestreams lassen sich nicht herunterladen", "Live streams can’t be downloaded"));
        double total = isoDur(mpd.getAttribute("mediaPresentationDuration"));
        String mpdBase = base(mpd, url);
        // längste Periode (Werbung steckt oft in eigenen, kurzen Perioden)
        List<Element> periods = kids(mpd, "Period");
        if (periods.isEmpty()) throw new IOException(Tr.t("DASH: keine Periode", "DASH: no period"));
        Element period = periods.get(0);
        double best = -1;
        for (int i = 0; i < periods.size(); i++) {
            Element p = periods.get(i);
            double d = isoDur(p.getAttribute("duration"));
            if (d <= 0 && periods.size() == 1) d = total;
            if (d <= 0 && i + 1 < periods.size()) d = isoDur(periods.get(i + 1).getAttribute("start")) - isoDur(p.getAttribute("start"));
            if (d <= 0) d = total - isoDur(p.getAttribute("start"));
            if (d > best) { best = d; period = p; }
        }
        double periodDur = best > 0 ? best : total;
        String perBase = base(period, mpdBase);

        Element vAs = null, vRep = null, aAs = null, aRep = null;
        long vScore = -1, aScore = -1;
        boolean sawProtected = false;
        for (Element as : kids(period, "AdaptationSet")) {
            for (Element rep : kids(as, "Representation")) {
                String kind = mime(as, rep);
                if (protectedBy(as, rep)) { sawProtected = true; continue; }
                long bw = Long.parseLong(def(rep.getAttribute("bandwidth"), "0"));
                if (kind.startsWith("video")) {
                    long h = Long.parseLong(def(attr("height", rep, as), "0"));
                    long score = h * 100000000L + bw;
                    if (score > vScore) { vScore = score; vAs = as; vRep = rep; }
                } else if (kind.startsWith("audio")) {
                    if (bw > aScore) { aScore = bw; aAs = as; aRep = rep; }
                }
            }
        }
        if (vRep == null && aRep == null) throw new IOException(sawProtected ? "Dieser Stream ist kopiergeschützt (DRM)" : "DASH: keine Spuren gefunden");
        HlsCore.Result r = new HlsCore.Result();
        r.fmp4 = true;
        HlsCore.Media vm = null, am = null;
        if (vRep != null) {
            vm = media(period, vAs, vRep, base(vRep, base(vAs, perBase)), periodDur);
            String h = attr("height", vRep, vAs);
            if (h != null && !h.isEmpty()) r.quality = h + "p";
        }
        if (aRep != null) am = media(period, aAs, aRep, base(aRep, base(aAs, perBase)), periodDur);
        int count = (vm == null ? 0 : vm.segs.size() + (vm.init != null ? 1 : 0)) + (am == null ? 0 : am.segs.size() + (am.init != null ? 1 : 0));
        AtomicInteger done = new AtomicInteger();
        if (vm != null) {
            r.video = new File(work, "video.mp4");
            seg.downloadMedia(vm, r.video, "v", l, done, count);
        }
        if (am != null) {
            File af = new File(work, "audio.mp4");
            seg.downloadMedia(am, af, "a", l, done, count);
            if (r.video == null) r.video = af; else r.audio = af;
        }
        return r;
    }
}
