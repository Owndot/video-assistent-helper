#!/usr/bin/env python3
# S3a: Mutationsproben für den DASH-Kern (de.evp.helfer.dash). Jede abgeschwächte Kopie muss die JVM-Tests rot machen
# (strenge Bewertung: tests/java/mutation_verdict.py – nur KILLED zählt).
#   python3 -I tests/java/dash/mutation.py
import os, shutil, sys, tempfile
here = os.path.dirname(os.path.abspath(__file__)); root = os.path.join(here, '..', '..', '..'); src = os.path.join(root, 'app', 'src', 'main', 'java')
D = 'de/evp/helfer/dash/'
M = [
    # XML-Härtung
    (D + 'DashXml.java', '        prescan(text);\n', ''),
    (D + 'DashXml.java', 'if (NS_XLINK.equals(an)) throw fail("xlink");', ''),
    (D + 'DashXml.java', 'if (NS_XINCLUDE.equals(uri)) throw fail("xinclude");', ''),
    (D + 'DashXml.java', 'if (DASH_NAMES.contains(local)) throw fail("namespace");', ''),
    (D + 'DashXml.java', 'if (m.find() && !"utf-8".equals(m.group(1).toLowerCase(Locale.ROOT))) throw new DashException("encoding");', ''),
    (D + 'DashXml.java', 'if (++elements > DashLimits.MAX_ELEMENTS) throw fail("limits");', ''),
    (D + 'DashXml.java', 'if (stack.size() + 1 > DashLimits.MAX_DEPTH) throw fail("depth");', ''),
    (D + 'DashXml.java', 'if (top.text.length() + length > DashLimits.MAX_TEXT_CHARS) throw fail("text-length");', ''),
    # Zahlen
    (D + 'DashNum.java', '        if (v > DashLimits.MAX_SAFE) throw new DashException("number-range");\n        return v;', '        return v;'),
    (D + 'DashNum.java', 'if (s.startsWith("-")) throw new DashException("number");', 'if (s.startsWith("-")) return -1;'),
    # Adressen
    (D + 'DashUrl.java', 'private static final String BAD = "\\"<>\\\\^`{|}\'#[]";', 'private static final String BAD = "\\"<>\\\\^`{|}#[]";'),
    (D + 'DashUrl.java', 'if (ref.charAt(i + 1) == \'2\' && (ref.charAt(i + 2) == \'e\' || ref.charAt(i + 2) == \'E\')) throw new DashException("uri-ambiguous");', ''),
    (D + 'DashUrl.java', 'if (!host.matches("(25[0-5]', 'if (false && !host.matches("(25[0-5]'),
    (D + 'DashUrl.java', 'if (("http".equals(scheme) && p == 80) || ("https".equals(scheme) && p == 443)) port = null;', ''),
    (D + 'DashUrl.java', '&& !ref.startsWith("//", colon + 1))\n            throw new DashException("uri-ambiguous");', '&& false)\n            throw new DashException("uri-ambiguous");'),
    # Vorlagen
    (D + 'DashTemplate.java', 'if (w > DashLimits.MAX_FORMAT_WIDTH) throw new DashException("template");', ''),
    (D + 'DashTemplate.java', 'if (!media || kind != Kind.NONE) throw new DashException("template");', 'if (!media) throw new DashException("template");'),
    (D + 'DashTemplate.java', 'if (at < resolved.indexOf("://") + 3 || at < DashUrl.origin(resolved).length()) throw new DashException("template");', ''),
    (D + 'DashTemplate.java', 'Pattern.compile("[A-Za-z0-9._~=,;!*()+&:@-]{1,256}")', 'Pattern.compile("[A-Za-z0-9._~=,;!*()+&:@/-]{1,256}")'),
    # Vererbung und Adressierung
    (D + 'DashManifest.java', 'if (kinds.size() > 1) throw new DashException("addressing-mixed");', ''),
    (D + 'DashManifest.java', 'if (n > 1) throw new DashException("addressing-multiple");', ''),
    (D + 'DashManifest.java', 'if (tl != null && r.duration != null) throw new DashException("addressing-ambiguous");', ''),
    (D + 'DashManifest.java', 'if (!SEG_KNOWN.contains(k)) throw new DashException("addressing-attr");', ''),
    (D + 'DashManifest.java', 'if (tl.entryExtra) throw new DashException("addressing-attr");', ''),
    # Zeitleisten und Grenzen
    (D + 'DashManifest.java', 'else if (k > 0 && t != end) return Exp.excl(t > end ? "timeline-gap" : "timeline-overlap");', ''),
    (D + 'DashManifest.java', 'BigDecimal left = pdTicks.add(BigDecimal.valueOf(pto)).subtract(BigDecimal.valueOf(t));', 'BigDecimal left = pdTicks.subtract(BigDecimal.valueOf(t));'),
    (D + 'DashManifest.java', 'if (nt == DashXml.NO_T || nt <= t || (nt - t) % d != 0) return Exp.excl("timeline-repeat");', 'if (nt == DashXml.NO_T) return Exp.excl("timeline-repeat");'),
    (D + 'DashManifest.java', 'if (miss.compareTo(BigDecimal.valueOf(DashLimits.COVERAGE_TOLERANCE_SECONDS)) > 0) return Exp.excl("coverage-mismatch");', ''),
    (D + 'DashManifest.java', '            if (c.compareTo(BigDecimal.valueOf(DashLimits.MAX_SEGMENTS_PER_TRACK)) > 0) return Exp.excl("segments");\n            int cnt', '            int cnt'),
    (D + 'DashManifest.java', 'if (e0 <= 0 || s0 >= ceil) continue;', 'if (false) continue;'),
    (D + 'DashManifest.java', 'if (d <= 0) return Exp.excl("zero-duration");\n                if (t == DashXml.NO_T)', 'if (t == DashXml.NO_T)'),
    (D + 'DashManifest.java', 'try { end = DashNum.addExact(t, DashNum.mulExact(d, cnt)); } catch (DashException x) { return Exp.excl("number-range"); }', 'end = t + d * cnt;'),
    # Profil
    (D + 'DashProfile.java', 'if (m.contentProtectionCount > 0) return new Verdict("dash-protected", video, audio, ex);', ''),
    (D + 'DashProfile.java', 'if (anyAudio && audio.isEmpty()) return new Verdict(category(audioWhy), video, audio, ex);', ''),
    (D + 'DashProfile.java', 'if (!a.essential.isEmpty()) return "set-essential";', ''),
    (D + 'DashProfile.java', 'if (m.periods.size() != 1) return new Verdict("dash-multiperiod", video, audio, ex);', ''),
    (D + 'DashManifest.java', '        for (DashXml.El k : e.kids) n += countAll(k, name);', ''),
    # Plan
    (D + 'DashPlan.java', '                if (pre && suf) return true;', ''),
    (D + 'DashPlan.java', '&& u.substring(ma.prefix.length(), u.length() - ma.suffix.length()).matches("[0-9]{1,20}")) return true;', '&& false) return true;'),
    (D + 'DashPlan.java', 'if (pairs > DashLimits.MAX_OPTIONS) throw new DashException("options-limit");', ''),
    (D + 'DashPlan.java', 'if (bytes.compareTo(BigDecimal.valueOf(DashLimits.MAX_MEDIA_BYTES)) > 0) return null;', ''),
    # S3a-Review 1
    (D + 'DashManifest.java', '                ctx.lists.put(lk, cached);\n', ''),
    (D + 'DashManifest.java', 'if (ctx.listResolved > DashLimits.MAX_RESOLVED_LIST_ENTRIES) throw new DashException("limits");', ''),
    (D + 'DashManifest.java', '            ctx.exps.put(key, e);\n', ''),
    (D + 'DashManifest.java', 'if (ctx.expanded > DashLimits.MAX_EXPANDED_SEGMENTS) throw new DashException("limits");', ''),
    (D + 'DashManifest.java', 'r.tlRaw = tl.s == null ? new long[0] : tl.s;', 'r.tlRaw = tl.s == null ? new long[0] : Arrays.copyOf(tl.s, tl.sCount * 3);'),
    (D + 'DashManifest.java', 'this.contentProtectionCount = all;', 'this.contentProtectionCount = direct(root);'),
    (D + 'DashFingerprint.java', 'if (m.contentProtectionNested > 0) line(sb, "mpd.contentProtectionNested", Integer.toString(m.contentProtectionNested));', ''),
    (D + 'DashFingerprint.java', 'write(m, new Out(null, md));', 'md.update(canonical(m).getBytes(StandardCharsets.UTF_8));'),
    (D + 'DashTemplate.java', 'int run = Math.max(longestNines(text), longestNines(base)) + 1;', 'int run = 3;'),
    (D + 'DashPlan.java', 'for (Track t : tracks) if (!fixed.add(t.rep.initUrl)) return true;', 'for (Track t : tracks) if (!fixed.add(t.rep.initUrl) && false) return true;'),
    (D + 'DashPlan.java', 'for (int i = 0; i < r.count; i++) if (!fixed.add(r.segment(i).url)) return true;', 'for (int i = 0; i < r.count; i++) if (!fixed.add(r.segment(i).url) && !r.segment(i).url.equals(r.initUrl)) return true;'),
    # Fingerabdruck
    (D + 'DashFingerprint.java', '                            line(sb, "r.S", ', '                            if (false) line(sb, "r.S", '),
    (D + 'DashFingerprint.java', '                    line(sb, "r.base", r.base);\n', ''),
    (D + 'DashFingerprint.java', '                props(sb, "s.contentProtection", a.contentProtection);\n', ''),
    (D + 'DashFingerprint.java', '                    line(sb, "r.segmentElements", r.segLevels);\n', ''),
    (D + 'DashFingerprint.java', 'if (c == \'|\') sb.append("\\\\|");', 'if (false) sb.append("\\\\|");'),
    # S4b-6: mehrere BaseURL (BaseSelection, Grenzen, Projektion, Abdruck)
    (D + 'BaseSelection.java', 'if (i >= count) throw new DashException("alt-invalid");', ''),
    (D + 'BaseSelection.java', 'if (!first && !used.containsAll(pick.keySet())) throw new DashException("alt-invalid");', ''),
    (D + 'DashManifest.java', 'if (b.size() > DashLimits.MAX_BASEURLS_PER_ELEMENT) throw new DashException("baseurl-limits");', ''),
    (D + 'DashManifest.java', 'if ((long) cur.size() * raw.size() > DashLimits.MAX_BASE_COMBINATIONS) throw new DashException("baseurl-limits");', ''),
    (D + 'DashManifest.java', 'if (i < 0 || outer == null) return null;', 'if (outer == null) return null; if (i < 0) i = 0;'),
    (D + 'DashManifest.java', 'if (sel == null) throw new DashException("baseurl-multiple");', 'if (sel == null) sel = BaseSelection.FIRST;'),
    (D + 'DashFingerprint.java', 'for (String a : alts) line(sb, level + ".baseAlt", a);', ''),
    (D + 'DashProfile.java', 'if (!whys.isEmpty() && whys.stream().allMatch("baseurl-undetermined"::equals)) return "dash-baseurl-undetermined";', ''),
]
sys.path.insert(0, os.path.join(here, '..'))
import mutation_verdict as mv  # noqa: E402
KILLED = [r'^FAIL ']
tally = mv.Tally('dash-mutations')
for f, a, b in M:
    name = f.split('/')[-1] + ': ' + a[:70].replace('\n', ' ')
    with tempfile.TemporaryDirectory() as t:
        shutil.copytree(src, os.path.join(t, 'java'))
        p = os.path.join(t, 'java', f); s = open(p).read()
        if s.count(a) != 1:
            tally.add('%s (fragment found %d times)' % (name, s.count(a)), 'STALE'); continue
        open(p, 'w').write(s.replace(a, b))
        v, out = mv.run(['bash', os.path.join(here, 'run.sh')], dict(os.environ, APP_SRC=os.path.join(t, 'java')), 600, KILLED)
        tally.add(name, v, out)
sys.exit(tally.finish())
