package de.evp.helfer.dashdl;

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import de.evp.helfer.ControlCommands;
import de.evp.helfer.dash.BaseSelection;
import de.evp.helfer.dash.DashException;
import de.evp.helfer.dash.DashFingerprint;
import de.evp.helfer.dash.DashLimits;
import de.evp.helfer.dash.DashManifest;
import de.evp.helfer.dash.DashPlan;
import de.evp.helfer.dl.DirectDownloads;
import de.evp.helfer.dl.DownloadStore;
import de.evp.helfer.dl.JobSlots;
import de.evp.helfer.dl.Progress;
import de.evp.helfer.hls.HlsException;
import de.evp.helfer.hls.HlsFetch;
import de.evp.helfer.hls.HlsJournal;
import de.evp.helfer.hls.HlsPlan;
import de.evp.helfer.hls.HlsRemux;
import de.evp.helfer.net.NetBlockedException;
import de.evp.helfer.net.RequestContext;
import de.evp.helfer.net.SafeHttpClient;

/**
 * S3b: DASH-VOD (S3-Profil: statisch, eine Periode, unverschlüsselt, fMP4 H.264 + AAC) → MP4 im Helfer.
 *
 * Eigener Koordinator neben dem HLS-Koordinator; der HLS-Code bleibt unverändert und wird nur als Baustein benutzt:
 * {@link HlsFetch} (Init/Stücke: Länge, SHA-256, fsync, starker ETag), {@link HlsJournal} (Journal ohne Adressen, CRC je
 * Zeile, atomarer Kopf), {@link HlsRemux} (Zusammenbau der App und Prüfung der Ausgabe), {@link JobSlots} (mit MP4/HLS
 * geteilte Grenzen), {@link DownloadStore} (atomare Veröffentlichung). Das MPD lädt {@link DashFetch} über denselben
 * {@link SafeHttpClient}. Der frühere, nicht angebundene DASH-Weg des Helfers wird nicht benutzt.
 *
 * <ul>
 *   <li>{@code dashplan}: MPD laden, streng lesen (src: de.evp.helfer.dash), Strukturfingerabdruck {@code kadri-dash-fp/1}
 *       berechnen und mit dem von Kadri übergebenen vergleichen – abweichend → {@code manifest_mismatch}. Dann S3-Profil
 *       und vollständiger Plan ({@link DashPlan}); Optionen ohne Adressen. Pläne nur im Arbeitsspeicher, gebunden an
 *       Kanal-Sitzung und Kadris Bindung, {@link #PLAN_TTL_MS} gültig, höchstens {@link #MAX_PLANS_PER_PEER} je Sitzung.</li>
 *   <li>{@code dashstart}: jeder Versuch lädt das MPD neu; Fingerabdruck ≠ Plan → {@code plan_changed} (fehlgeschlagen).
 *       Erst Init-Ressourcen laden und mit {@link Fmp4Inspect} prüfen (Container, Codec gegen MPD, Verschlüsselung), dann
 *       die Stücke – jedes wird vor der Aufnahme ins Journal geprüft. Remux (global genau einer), Prüfung der Ausgabe
 *       (Spuren, Samples, Dauer ≈ Plan), atomare Veröffentlichung.</li>
 *   <li>Wiederaufnahme: neuer {@code dashstart} mit derselben Kennung (oder „Fortsetzen“, solange der Prozess lebt);
 *       eine Ressource wird nur mit Länge, SHA-256 und vom Server bestätigtem starken ETag wiederverwendet. Nach einem
 *       Prozessneustart sind laufende Aufträge unterbrochen; eine verschlüsselt gespeicherte Quelle wie bei HLS gibt es
 *       für DASH in S3b nicht (die Adresse bleibt nur im Arbeitsspeicher).</li>
 *   <li>Gründe nur als feste Codes, nie Adressen, Hosts oder Manifestinhalt – weder im Journal noch in Antworten.</li>
 * </ul>
 */
public final class DashEngine implements ControlCommands.DashControl {
    public static final long PLAN_TTL_MS = 10 * 60_000L;
    public static final int MAX_PLANS_PER_PEER = 4;
    public static final int MAX_PLANS = 16;
    public static final int FETCH_PER_JOB = 2;
    /** Einziger Ton-Codec von S3b (AAC-LC). */
    static final String AAC_LC = "mp4a.40.2";
    /**
     * Zulässige Abweichung der tatsächlichen Mediendauer (Summe der Probendauern aller Stücke einer Spur) von der
     * Plandauer und der Ausgabe von den Stücken – dieselbe Sekunde, die S3a zwischen MPD-Dauer und Stückfolge zulässt
     * ({@link DashLimits#COVERAGE_TOLERANCE_SECONDS}).
     */
    static final double DURATION_TOLERANCE_SECONDS = DashLimits.COVERAGE_TOLERANCE_SECONDS;
    public static final long SPACE_RESERVE_BYTES = 256L << 20;
    public static final long INTERRUPTED_KEEP_MS = 7L * 24 * 3600_000L;
    static final int JOURNAL_KEEP = 64;
    static final long PLAN_DEADLINE_MS = 20_000;
    static final long HTTP_WAIT_MS = 10 * 60_000L;
    static final int MAX_AUTO_ATTEMPTS = 64;

    public interface Clock { long nowMs(); }

    private final SafeHttpClient http;
    private final DownloadStore store;
    private final DirectDownloads.Listener listener;
    private final File root;
    private final HlsRemux remux;
    private final JobSlots slots;
    private final long maxSamples;
    private final HlsJournal.Hooks hooks;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Semaphore remuxer = new Semaphore(1, true);
    private final Map<String, PlanEntry> plans = new LinkedHashMap<>();
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final AtomicInteger running = new AtomicInteger();
    private boolean stopped;
    private volatile de.evp.helfer.dl.Retry retry = de.evp.helfer.dl.Retry.DEFAULT;

    public void setRetry(de.evp.helfer.dl.Retry r) { this.retry = r; }

    public DashEngine(SafeHttpClient http, DownloadStore store, DirectDownloads.Listener listener, File root, HlsRemux remux,
            JobSlots slots, long maxSamples) throws IOException {
        this(http, store, listener, root, remux, slots, maxSamples, HlsJournal.Hooks.NONE, System::currentTimeMillis);
    }

    public DashEngine(SafeHttpClient http, DownloadStore store, DirectDownloads.Listener listener, File root, HlsRemux remux,
            JobSlots slots, long maxSamples, HlsJournal.Hooks hooks, Clock clock) throws IOException {
        this.http = http.withoutConnectionReuse();
        this.store = store;
        this.listener = listener;
        this.root = root;
        this.remux = remux;
        this.slots = slots;
        this.maxSamples = maxSamples;
        this.hooks = hooks;
        this.clock = clock;
        Files.createDirectories(root.toPath());
        recover();
    }

    // ================================================================== Pläne (dashplan)

    /** Eine Auswahl (Video- und Tondarstellung) ohne Adressen. */
    static final class Option {
        final String id;
        final int videoRep, audioRep;     // Index der Darstellung im MPD (Dokumentreihenfolge)
        final String describe;
        Option(String id, int videoRep, int audioRep, String describe) { this.id = id; this.videoRep = videoRep; this.audioRep = audioRep; this.describe = describe; }
    }

    static final class PlanEntry {
        final String planId;
        final byte[] peer;
        final String bind;
        final long expires;
        final String sourceUrl;        // nur im Arbeitsspeicher
        final String fp;               // kadri-dash-fp/1, bei dashplan geprüft
        final List<Option> options;
        final double duration;
        final RequestContext context;
        final String alt;   // S4b-6: Auswahl unter mehreren BaseURL (null: höchstens eine je Ebene)
        PlanEntry(String planId, byte[] peer, String bind, long expires, String sourceUrl, String fp, List<Option> options, double duration, RequestContext context, String alt) {
            this.planId = planId; this.peer = peer; this.bind = bind; this.expires = expires; this.sourceUrl = sourceUrl; this.fp = fp;
            this.options = options; this.duration = duration; this.context = context == null ? RequestContext.NONE : context; this.alt = alt;
        }
    }

    /** Geladenes, geprüftes MPD. */
    static final class Source {
        final DashPlan plan;
        final String fp;
        Source(DashPlan plan, String fp) { this.plan = plan; this.fp = fp; }
    }

    /** Ausgewählte Spuren und ihre Ressourcen (Init zuerst, dann die Stücke in Folge). */
    static final class Chosen {
        final Option option;
        final DashPlan.Track video, audio;
        final List<String> vRes, aRes;
        final long estimate;
        Chosen(Option option, DashPlan.Track video, DashPlan.Track audio, List<String> vRes, List<String> aRes, long estimate) {
            this.option = option; this.video = video; this.audio = audio; this.vRes = vRes; this.aRes = aRes; this.estimate = estimate;
        }
    }

    @Override
    public String plan(byte[] peer, String bind, String url, String fp, int[] played, RequestContext context) {
        return plan(peer, bind, url, fp, played, context, null);
    }

    /**
     * S4b-6: mit {@code alt} (dashplan v2) wählt Kadri unter mehreren im MPD erklärten BaseURL die Alternative, deren
     * Stückfolge es an das Video gebunden hat. Der Helfer prüft die Auswahl selbst am neu geladenen MPD (BaseSelection: nur
     * erklärte Elemente und Indizes), berechnet den Abdruck über das ganze MPD (alle Alternativen) und plant die Projektion.
     */
    @Override
    public String plan(byte[] peer, String bind, String url, String fp, int[] played, RequestContext context, String alt) {
        RequestContext ctx = context == null ? RequestContext.NONE : context;
        SafeHttpClient.Cancellation cancel = new SafeHttpClient.Cancellation();
        Phase0 phase = new Phase0();
        try {
            if (stoppedNow()) return planFail("stopped", null);
            if (fp == null || !fp.matches("[0-9a-f]{64}")) return planFail("fingerprint_missing", null);
            BaseSelection sel = null;
            if (alt != null) {
                try { sel = BaseSelection.parse(alt); } catch (DashException e) { return planFail("alt_invalid", null); }
            }
            long deadline = clock.nowMs() + PLAN_DEADLINE_MS;
            Source src = loadSource(url, cancel, deadline, ctx, phase, sel, fp, "manifest_mismatch");
            phase.at = "fingerprint";
            if (!src.fp.equals(fp)) return planFail("manifest_mismatch", phase);
            phase.at = "options";
            List<Option> options = options(src.plan, played);
            if (options.isEmpty()) return planFail("segments_limit", phase);
            // S4b-6: Adressen der geplanten Spuren (Init und erstes Stück) dürfen nicht schon an den Netzregeln scheitern –
            // frühe Ablehnung statt eines Auftrags, der beim ersten Abruf blockiert (jeder Abruf prüft weiterhin selbst)
            phase.at = "network";
            for (DashPlan.Option po : src.plan.options) {
                for (DashPlan.Track t : new DashPlan.Track[] {po.video, po.audio}) {
                    if (t == null) continue;
                    if (http.blockedStatically(t.initUrl()) || http.blockedStatically(t.segment(0).url)) return planFail("blocked_host", phase);
                }
            }
            String planId = hex(randomBytes(16));
            double dur = src.plan.seconds.doubleValue();
            PlanEntry e = new PlanEntry(planId, peer.clone(), bind, clock.nowMs() + PLAN_TTL_MS, url, src.fp, options, dur, ctx, alt);
            synchronized (this) { putPlan(e); }
            StringBuilder b = new StringBuilder("{\"ok\":true,\"planId\":\"").append(planId).append("\",\"expiresSec\":").append(PLAN_TTL_MS / 1000)
                .append(",\"duration\":").append(String.format(Locale.ROOT, "%.3f", dur)).append(",\"fp\":\"").append(DashFingerprint.VERSION).append("\",\"options\":[");
            for (int i = 0; i < options.size(); i++) b.append(i > 0 ? "," : "").append(options.get(i).describe);
            return b.append("]}").toString();
        } catch (Stop s) {
            return planFail(s.reason, phase);
        } catch (DashException e) {
            return planFail(e.reason, phase);
        } catch (NetBlockedException e) {
            return planFail("blocked_" + e.reason.name().toLowerCase(Locale.ROOT), phase);
        } catch (IOException | RuntimeException e) {
            return planFail("network", phase);
        }
    }

    /** Planungsort nur für die Diagnose (feste Codes). */
    static final class Phase0 { String at = "mpd_fetch"; }

    private synchronized boolean stoppedNow() { return stopped; }

    /** Optionen: jede nutzbare Video- × Tondarstellung; Ton ist Pflicht (der Remuxer verlangt genau eine Tonspur). */
    private List<Option> options(DashPlan p, int[] played) throws Stop {
        if (p.audio.isEmpty()) throw new Stop(HlsJournal.State.FAILED, "dash_no_audio");
        List<Option> out = new ArrayList<>();
        byte[] salt = randomBytes(16);
        boolean onlyHeAac = true;
        for (DashPlan.Option o : p.options) {
            if (o.audio == null) continue;
            // S3b speichert nur AAC-LC: HE-AAC/HE-AACv2 (mp4a.40.5/.29) lässt sich ohne Decoder nicht sicher gegen das
            // MPD prüfen (implizites SBR) → fail-closed nicht angeboten (Fmp4Inspect prüft die AudioSpecificConfig)
            if (!AAC_LC.equals(o.audio.rep.codecs.trim())) continue;
            onlyHeAac = false;
            // Journalgrenze des HLS-Bausteins: Video + Ton zusammen höchstens MAX_PLAN_SEGMENTS + 2 Ressourcen
            int res = (o.video.count() + 1) + (o.audio.count() + 1);
            if (res > HlsPlan.MAX_PLAN_SEGMENTS + 2 || o.estimatedBytes > HlsPlan.MAX_MEDIA_BYTES) continue;
            int vi = o.video.rep.index, ai = o.audio.rep.index;
            String id = hex(sha256(salt, ("dash-opt:" + vi + ":" + ai).getBytes(StandardCharsets.US_ASCII))).substring(0, 24);
            boolean pl = contains(played, vi) && contains(played, ai);
            String lang = o.audio.lang();
            StringBuilder d = new StringBuilder("{\"id\":\"").append(id).append('"');
            d.append(",\"height\":").append(num(o.video.rep.height)).append(",\"width\":").append(num(o.video.rep.width));
            d.append(",\"bandwidth\":").append(o.video.rep.bandwidth + o.audio.rep.bandwidth);
            d.append(",\"video\":\"").append(o.video.rep.codecs.trim()).append("\",\"audio\":\"").append(o.audio.rep.codecs.trim()).append('"');
            if (lang != null && lang.matches("[A-Za-z0-9-]{1,16}")) d.append(",\"lang\":\"").append(lang).append('"');
            d.append(",\"played\":").append(pl).append('}');
            out.add(new Option(id, vi, ai, d.toString()));
        }
        if (out.isEmpty() && onlyHeAac) throw new Stop(HlsJournal.State.FAILED, "audio_codec");
        return out;
    }

    private static boolean contains(int[] a, int v) { if (a == null) return false; for (int x : a) if (x == v) return true; return false; }

    private static long num(String s) { try { long v = s == null ? 0 : Long.parseLong(s.trim()); return v >= 0 && v <= 100_000 ? v : 0; } catch (NumberFormatException e) { return 0; } }

    private void putPlan(PlanEntry e) {
        long now = clock.nowMs();
        plans.values().removeIf(p -> p.expires <= now);
        List<String> mine = new ArrayList<>();
        for (PlanEntry p : plans.values()) if (Arrays.equals(p.peer, e.peer)) mine.add(p.planId);
        while (mine.size() >= MAX_PLANS_PER_PEER) plans.remove(mine.remove(0));
        while (plans.size() >= MAX_PLANS) {
            Iterator<String> it = plans.keySet().iterator();
            it.next();
            it.remove();
        }
        plans.put(e.planId, e);
    }

    private static String planFail(String reason, Phase0 phase) {
        return "{\"ok\":false,\"reason\":\"" + code(reason) + "\"" + (phase == null ? "" : ",\"diag\":{\"v\":1,\"phase\":\"" + code(phase.at) + "\"}") + "}";
    }

    /**
     * MPD laden, lesen, Fingerabdruck, S3-Profil und vollständiger Plan. S4b-6: mit Auswahl {@code sel} wird der Abdruck
     * über das ganze MPD (alle Alternativen, BaseSelection.FIRST) und der Plan über die gewählte Projektion berechnet.
     */
    private Source loadSource(String url, SafeHttpClient.Cancellation cancel, long deadline, RequestContext ctx, Phase0 phase, BaseSelection sel,
                              String expectFp, String mismatch) throws IOException, DashException, Stop {
        if (phase != null) phase.at = "mpd_fetch";
        DashFetch.Text t = manifest(url, cancel, deadline, ctx);
        if (phase != null) phase.at = "mpd_parse";
        DashManifest m, all;
        try { all = DashManifest.parse(t.body, t.finalUrl, sel == null ? null : BaseSelection.FIRST); }
        catch (DashException e) { throw new Stop(HlsJournal.State.FAILED, "mpd_" + e.reason); }
        String fp = DashFingerprint.hex(all);
        // S4b-6: ein anderes MPD (auch: Alternative weggefallen oder hinzugekommen) wird VOR der Auswahl erkannt
        if (sel != null && expectFp != null && !fp.equals(expectFp)) { if (phase != null) phase.at = "fingerprint"; throw new Stop(HlsJournal.State.FAILED, mismatch); }
        try { m = sel == null ? all : DashManifest.parse(t.body, t.finalUrl, sel); }
        catch (DashException e) { throw new Stop(HlsJournal.State.FAILED, "mpd_" + e.reason); }
        if (phase != null) phase.at = "profile";
        DashPlan p;
        try { p = DashPlan.build(m); }
        catch (DashException e) { throw new Stop(HlsJournal.State.FAILED, e.reason); }
        return new Source(p, fp);
    }

    private DashFetch.Text manifest(String url, SafeHttpClient.Cancellation cancel, long deadline, RequestContext ctx) throws IOException, DashException {
        long wait = deadline - clock.nowMs();
        if (wait <= 0) throw new DashException("timeout");
        boolean got;
        try { got = slots.acquireHttp(wait, cancel::isCancelled); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new DashException("cancelled"); }
        if (!got) throw new DashException(cancel.isCancelled() ? "cancelled" : "busy");
        try { return DashFetch.manifest(http, cancel, ctx, url); }
        finally { slots.releaseHttp(); }
    }

    // ================================================================== Aufträge

    enum Phase { FETCH, MUX, VERIFY, SAVE, NONE }

    static final class Job {
        final String id;
        final File dir;
        final HlsJournal journal;
        volatile SafeHttpClient.Cancellation cancel = new SafeHttpClient.Cancellation();
        volatile boolean active, stopRequested;
        volatile Phase phase = Phase.NONE;
        final AtomicLong bytes = new AtomicLong();
        final AtomicInteger done = new AtomicInteger();
        long lastNote;
        volatile String cause = "";
        volatile de.evp.helfer.dl.Retry.Wait wait = de.evp.helfer.dl.Retry.Wait.NONE;
        volatile PlanEntry source;     // nur im Arbeitsspeicher
        volatile String sourceOption;
        String failAt;                 // mpd | video_init | audio_init | video_segment | audio_segment (unter synchronized(job))
        int failIndex = -1;
        volatile RequestContext context = RequestContext.NONE;
        Job(String id, File dir, HlsJournal journal) { this.id = id; this.dir = dir; this.journal = journal; }
        boolean userCancelled() { synchronized (this) { return journal.cancelRequested; } }
    }

    static final class Stop extends Exception {
        final HlsJournal.State state;
        final String reason;
        Stop(HlsJournal.State state, String reason) { super(reason, null, false, false); this.state = state; this.reason = code(reason); }
    }

    @Override
    public synchronized boolean knows(String id) { return jobs.containsKey(id); }

    @Override
    public String start(byte[] peer, String bind, String id, String planId, String optionId, String name) {
        PlanEntry plan;
        Job job;
        synchronized (this) {
            job = jobs.get(id);
            if (job != null) {
                synchronized (job) {
                    if (job.active || job.journal.state != HlsJournal.State.INTERRUPTED || job.journal.cancelRequested) return json(job);
                }
            }
            if (stopped) return busy(id);
            plan = plans.get(planId);
            if (plan == null || plan.expires <= clock.nowMs() || !Arrays.equals(plan.peer, peer) || !plan.bind.equals(bind)) return reject(id, "plan_stale");
            if (option(plan, optionId) == null) return reject(id, "option_unknown");
            if (!slots.tryAcquire()) return busy(id);
            plans.remove(planId);
            running.incrementAndGet();
            if (job == null) {
                File dir = new File(root, id);
                HlsJournal j = new HlsJournal();
                j.id = id;
                j.state = HlsJournal.State.RUNNING;
                j.salt = hex(randomBytes(16));
                j.shape = NO_SHAPE;
                j.videoCount = 1;
                j.title = DirectDownloads.displayTitle(name);
                job = new Job(id, dir, j);
                try {
                    Files.createDirectories(dir.toPath());
                    synchronized (job) { j.save(dir, hooks); }
                } catch (IOException e) {
                    deleteTree(dir);
                    running.decrementAndGet();
                    slots.release();
                    return busy(id);
                }
                jobs.put(id, job);
            } else {
                synchronized (job) {
                    if (job.journal.cancelRequested) { running.decrementAndGet(); slots.release(); return json(job); }
                    job.journal.state = HlsJournal.State.RUNNING;
                    job.journal.reason = "";
                    try { job.journal.save(job.dir, hooks); }
                    catch (IOException e) { job.journal.state = HlsJournal.State.INTERRUPTED; running.decrementAndGet(); slots.release(); return busy(id); }
                }
            }
            synchronized (job) {
                job.cancel = new SafeHttpClient.Cancellation();
                job.stopRequested = false;
                job.cause = "";
                job.active = true;
                job.phase = Phase.FETCH;
            }
        }
        launch(job, plan, optionId);
        return json(job);
    }

    private void launch(Job j, PlanEntry p, String optionId) {
        j.source = p;
        j.sourceOption = optionId;
        Thread t = new Thread(() -> run(j, p, optionId), "evp-dash");
        t.setDaemon(true);
        try { t.start(); }
        catch (RuntimeException | Error e) { finish(j, HlsJournal.State.INTERRUPTED, "stopped", null); }
    }

    /** „Fortsetzen“ aus der Benachrichtigung möglich (Quelle noch im Arbeitsspeicher)? */
    public synchronized boolean canResumeLocal(String id) {
        Job j = jobs.get(id);
        if (j == null || j.source == null || stopped) return false;
        synchronized (j) { return !j.active && j.journal.state == HlsJournal.State.INTERRUPTED && !j.journal.cancelRequested; }
    }

    public boolean resumeLocal(String id) {
        Job job;
        PlanEntry p;
        String option;
        synchronized (this) {
            job = jobs.get(id);
            if (job == null || stopped) return false;
            p = job.source;
            option = job.sourceOption;
            if (p == null || option == null) return false;
            synchronized (job) {
                if (job.active || job.journal.state != HlsJournal.State.INTERRUPTED || job.journal.cancelRequested) return false;
            }
            if (!slots.tryAcquire()) return false;
            running.incrementAndGet();
            synchronized (job) {
                job.journal.state = HlsJournal.State.RUNNING;
                job.journal.reason = "";
                try { job.journal.save(job.dir, hooks); }
                catch (IOException e) { job.journal.state = HlsJournal.State.INTERRUPTED; running.decrementAndGet(); slots.release(); return false; }
                job.cancel = new SafeHttpClient.Cancellation();
                job.stopRequested = false;
                job.cause = "";
                job.active = true;
                job.phase = Phase.FETCH;
            }
        }
        launch(job, p, option);
        return true;
    }

    private static Option option(PlanEntry p, String optionId) {
        for (Option o : p.options) if (o.id.equals(optionId)) return o;
        return null;
    }

    @Override
    public synchronized String status(String id) {
        Job j = jobs.get(id);
        return j == null ? unknown(id) : json(j);
    }

    @Override
    public String cancel(String id) {
        Job j;
        synchronized (this) { j = jobs.get(id); }
        if (j == null) return unknown(id);
        boolean cleanupNow = false;
        synchronized (j) {
            HlsJournal.State s = j.journal.state;
            if (s == HlsJournal.State.DONE || s == HlsJournal.State.FAILED || s == HlsJournal.State.CANCELLED) return json(j);
            j.journal.cancelRequested = true;
            try { j.journal.save(j.dir, hooks); } catch (IOException e) { j.cause = "cancel-journal: " + e.getMessage(); }
            if (!j.active) cleanupNow = true;
        }
        j.cancel.cancel();
        if (cleanupNow) {
            synchronized (this) {
                synchronized (j) {
                    if (!j.active) {
                        deleteData(j.dir);
                        j.journal.resources.clear();
                        j.journal.state = HlsJournal.State.CANCELLED;
                        j.journal.reason = "";
                        j.source = null; j.sourceOption = null;
                        try { j.journal.save(j.dir, hooks); } catch (IOException ignored) { /* Abbruchwunsch steht schon */ }
                    }
                }
            }
            listener.finished(j.id, j.journal.title, "cancelled", "", null);
        }
        return json(j);
    }

    public void stopAll() {
        List<Job> snapshot;
        synchronized (this) { stopped = true; plans.clear(); snapshot = new ArrayList<>(jobs.values()); }
        for (Job j : snapshot) if (j.active) { j.stopRequested = true; j.cancel.cancel(); }
    }

    public synchronized void serviceStarted() { stopped = false; }

    public synchronized boolean awaitIdle(long ms) throws InterruptedException {
        long end = System.nanoTime() + ms * 1_000_000L;
        while (running.get() > 0) {
            long left = (end - System.nanoTime()) / 1_000_000L;
            if (left <= 0) return false;
            wait(left);
        }
        return true;
    }

    /** Nur Diagnose (Tests/Logs), nie im Kanal. */
    public synchronized String diagnostics(String id) {
        Job j = jobs.get(id);
        return j == null ? "" : j.cause;
    }

    // ------------------------------------------------------------------ Ablauf

    static final class Outcome {
        final HlsJournal.State state;
        final String reason;
        final Object saved;
        Outcome(HlsJournal.State state, String reason, Object saved) { this.state = state; this.reason = reason; this.saved = saved; }
    }

    private void run(Job job, PlanEntry planEntry, String optionId) {
        Outcome o;
        int attempt = 0, total = 0, best = -1;
        while (true) {
            o = attempt(job, planEntry, optionId);
            if (o.state != HlsJournal.State.INTERRUPTED || !de.evp.helfer.dl.Retry.transientReason(o.reason)
                || job.userCancelled() || job.stopRequested || ++total > MAX_AUTO_ATTEMPTS) break;
            int reached = job.done.get();
            attempt = reached > best ? 1 : attempt + 1;
            best = Math.max(best, reached);
            boolean go;
            try { go = retry.await(attempt, () -> job.userCancelled() || job.stopRequested, w -> { job.wait = w; listener.waiting(job.id, job.journal.title, w); }); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); go = false; }
            finally { job.wait = de.evp.helfer.dl.Retry.Wait.NONE; }
            if (go) synchronized (job) { job.cancel = new SafeHttpClient.Cancellation(); job.phase = Phase.FETCH; }
            if (job.userCancelled()) { o = new Outcome(HlsJournal.State.CANCELLED, "", null); break; }
            if (job.stopRequested) { o = new Outcome(HlsJournal.State.INTERRUPTED, "stopped", null); break; }
            if (!go) break;
        }
        finish(job, o.state, o.reason, o.saved);
    }

    private Outcome attempt(Job job, PlanEntry planEntry, String optionId) {
        HlsJournal.State state = HlsJournal.State.FAILED;
        String reason = "network";
        Object saved = null;
        File out = new File(job.dir, "out.mp4.part");
        DownloadStore.Target target = null;
        try {
            if (!store.writable()) throw new Stop(HlsJournal.State.FAILED, "storage_permission");
            synchronized (job) { job.failAt = null; job.failIndex = -1; }
            job.context = planEntry.context;
            Chosen c = recheck(job, planEntry, optionId);
            List<String> vRes = c.vRes, aRes = c.aRes;
            String shape = shape(job.journal.salt, planEntry.fp, c);
            synchronized (job) {
                if (!shape.equals(job.journal.shape) || job.journal.videoCount != vRes.size() || job.journal.audioCount != aRes.size()) {
                    deleteData(job.dir);
                    job.journal.clearResources(job.dir);
                    job.journal.shape = shape;
                    job.journal.videoCount = vRes.size();
                    job.journal.audioCount = aRes.size();
                }
                job.journal.ref = null;
                job.journal.total = -1;
                save(job);
            }
            if (c.estimate > 0 && free() < 2 * c.estimate + SPACE_RESERVE_BYTES) throw new Stop(HlsJournal.State.FAILED, "no_space");
            // 1. Init-Ressourcen laden und prüfen, bevor ein einziges Stück geladen wird
            List<Object[]> inits = new ArrayList<>();
            inits.add(new Object[] {'v', 0, vRes.get(0)});
            inits.add(new Object[] {'a', 0, aRes.get(0)});
            job.bytes.set(0);
            job.done.set(0);
            fetchAll(job, inits, c.estimate);
            checkStop(job);
            Fmp4Inspect.Info vInfo = inspectInit(job, 'v', "video", c.video.rep.codecs);
            Fmp4Inspect.Info aInfo = inspectInit(job, 'a', "audio", c.audio.rep.codecs);
            // 2. alle Stücke (jedes vor der Aufnahme ins Journal geprüft)
            List<Object[]> segs = new ArrayList<>();
            for (int i = 1; i < vRes.size(); i++) segs.add(new Object[] {'v', i, vRes.get(i)});
            for (int i = 1; i < aRes.size(); i++) segs.add(new Object[] {'a', i, aRes.get(i)});
            fetchAll(job, segs, c.estimate);
            checkStop(job);

            phase(job, Phase.MUX);
            List<File> vFiles = files(job, 'v', vRes.size()), aFiles = files(job, 'a', aRes.size());
            // 3. Vollständigkeit: tatsächliche Dauer je Spur aus den Stücken gegen den Plan (vor dem Zusammenbau)
            double vActual = mediaSeconds(vFiles, vInfo), aActual = mediaSeconds(aFiles, aInfo);
            checkDuration(job, 'v', vActual, c.video);
            checkDuration(job, 'a', aActual, c.audio);
            long media = 0;
            for (File f : vFiles) media += f.length();
            for (File f : aFiles) media += f.length();
            if (free() < media + SPACE_RESERVE_BYTES / 2) throw new Stop(HlsJournal.State.FAILED, "no_space");
            acquireRemuxer(job);
            HlsRemux.Result r;
            try {
                hooks.at("remux-start");
                // E/A-Fehler beim Zusammenbau (z. B. abgeschnittene Probe trotz Prüfung) sind Formatfehler der Quelle,
                // keine Netzstörung: fehlgeschlagen statt automatisch wiederholt
                try {
                    r = remux.remux(new HlsRemux.Input(vFiles, aFiles, true, maxSamples), out, () -> stopWanted(job));
                    checkStop(job);
                    phase(job, Phase.VERIFY);
                    // Ausgabe = tatsächliche Stückdauer ± 1 s, ohne Spielraum für Medien außerhalb der Periode: der Remuxer
                    // kennt keine Periode und schneidet nichts ab (er schreibt jede Probe der übergebenen Stücke, verify
                    // verlangt dieselbe Probenzahl) – angeschnittene Stücke erscheinen vollständig in der Ausgabe
                    remux.verify(out, r, vActual, aActual, DURATION_TOLERANCE_SECONDS, () -> stopWanted(job));
                } catch (IOException e) {
                    job.cause = e.toString();
                    checkStop(job);
                    throw new Stop(HlsJournal.State.FAILED, "remux_io");
                }
            } finally {
                remuxer.release();
            }
            checkStop(job);
            hooks.at("verified");

            phase(job, Phase.SAVE);
            long total = out.length();
            if (free() < total + SPACE_RESERVE_BYTES / 4) throw new Stop(HlsJournal.State.FAILED, "no_space");
            try { target = store.create(DirectDownloads.fileName(job.journal.title), "video/mp4"); }
            catch (IOException e) { job.cause = e.toString(); throw new Stop(HlsJournal.State.FAILED, "storage"); }
            synchronized (job) {
                job.journal.state = HlsJournal.State.PUBLISHING;
                job.journal.ref = target.ref();
                job.journal.total = total;
                save(job);
            }
            hooks.at("publish-journaled");
            copy(job, out, target);
            checkStop(job);
            hooks.at("publish-copied");
            try { saved = target.commit(); }
            catch (IOException e) { job.cause = e.toString(); throw new Stop(HlsJournal.State.FAILED, "storage"); }
            String ref = target.ref();
            target = null;
            hooks.at("published");
            synchronized (job) {
                job.journal.state = HlsJournal.State.DONE;
                job.journal.ref = ref;
                job.journal.reason = "";
                job.journal.resources.clear();
                try { job.journal.save(job.dir, hooks); } catch (IOException ignored) { /* Nach Neustart: isPublished → done */ }
            }
            hooks.at("done-journaled");
            job.bytes.set(total);
            state = HlsJournal.State.DONE;
            reason = "";
        } catch (Stop s) {
            state = s.state;
            reason = s.reason;
        } catch (HlsException e) {
            state = classify(e.getMessage());
            reason = e.getMessage();
        } catch (DashException e) {
            state = classify(e.reason);
            reason = e.reason;
        } catch (NetBlockedException e) {
            state = HlsJournal.State.FAILED;
            reason = e.reason == NetBlockedException.Reason.BODY_TOO_LARGE ? "segment_size" : "blocked_" + e.reason.name().toLowerCase(Locale.ROOT);
        } catch (OutOfMemoryError e) {
            state = HlsJournal.State.FAILED;
            reason = "memory";
        } catch (IOException e) {
            job.cause = e.toString();
            state = HlsJournal.State.INTERRUPTED;
            reason = "network";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            state = HlsJournal.State.INTERRUPTED;
            reason = "stopped";
        } catch (Exception e) {
            job.cause = e.toString();
            state = HlsJournal.State.FAILED;
            reason = "internal";
        } finally {
            if (target != null) target.abort();
            if (state != HlsJournal.State.DONE) out.delete();
        }
        if (state != HlsJournal.State.DONE) {
            if (job.userCancelled()) { state = HlsJournal.State.CANCELLED; reason = ""; }
            else if (job.stopRequested) { state = HlsJournal.State.INTERRUPTED; reason = "stopped"; }
        }
        return new Outcome(state, reason, saved);
    }

    /** Summe der Probendauern aller Medienstücke (ohne Init, Index 0) in Sekunden. */
    private static double mediaSeconds(List<File> files, Fmp4Inspect.Info info) throws Stop {
        long ticks = 0;
        try {
            for (int i = 1; i < files.size(); i++) ticks = Math.addExact(ticks, Fmp4Inspect.duration(files.get(i), info));
        } catch (DashException e) {
            throw new Stop(HlsJournal.State.FAILED, e.reason);
        } catch (ArithmeticException | IOException e) {
            throw new Stop(HlsJournal.State.FAILED, "media_duration");
        }
        return (double) ticks / info.timescale;
    }

    /** Vom Plan erfasste Stückdauer außerhalb der Periode (Summe der vollen Stückdauern − abgedeckte Dauer), Sekunden. */
    private static double outsidePeriod(DashPlan.Track t) {
        BigDecimal raw = BigDecimal.ZERO;
        for (int i = 0; i < t.count(); i++) raw = raw.add(BigDecimal.valueOf(t.segment(i).duration));
        double out = raw.divide(BigDecimal.valueOf(t.timescale()), 9, RoundingMode.HALF_EVEN).subtract(t.seconds()).doubleValue();
        return Math.max(0, out);
    }

    /**
     * Die Stücke einer Spur müssen die Periode abdecken: tatsächliche Dauer ≥ Plandauer − 1 s und ≤ Summe der vollen
     * Stückdauern + 1 s. Zu kurze (oder zu lange) Fragmente bei formal gültigem MPD → failed media_duration.
     */
    private void checkDuration(Job job, char track, double actual, DashPlan.Track t) throws Stop {
        double planned = t.seconds().doubleValue();
        if (actual < planned - DURATION_TOLERANCE_SECONDS || actual > planned + outsidePeriod(t) + DURATION_TOLERANCE_SECONDS) {
            synchronized (job) { job.failAt = track == 'v' ? "video_segment" : "audio_segment"; job.failIndex = -1; }
            throw new Stop(HlsJournal.State.FAILED, "media_duration");
        }
    }

    /** Wie beim HLS-Koordinator: Netzwerk- und Quellprobleme sind wiederaufnehmbar, alles andere nicht. */
    static HlsJournal.State classify(String reason) {
        String r = reason == null ? "" : reason;
        if (r.equals("io") || r.equals("timeout") || r.equals("cancelled") || r.equals("busy") || r.equals("length-mismatch")
            || r.startsWith("http_5") || r.equals("http_403") || r.equals("http_404") || r.equals("http_410") || r.equals("http_429")) {
            return HlsJournal.State.INTERRUPTED;
        }
        return HlsJournal.State.FAILED;
    }

    /** MPD neu laden: unveränderter Fingerabdruck, dieselbe Auswahl, dieselben Stückzahlen. */
    private Chosen recheck(Job job, PlanEntry p, String optionId) throws IOException, DashException, Stop {
        long deadline = clock.nowMs() + PLAN_DEADLINE_MS;
        Option o = option(p, optionId);
        if (o == null) throw new Stop(HlsJournal.State.FAILED, "plan_changed");
        Source src;
        try {
            BaseSelection sel = null;
            if (p.alt != null) { try { sel = BaseSelection.parse(p.alt); } catch (DashException e) { throw new Stop(HlsJournal.State.FAILED, "plan_changed"); } }
            src = loadSource(p.sourceUrl, job.cancel, deadline, p.context, null, sel, p.fp, "plan_changed");
        } catch (DashException e) {
            if (e.reason.startsWith("http_")) synchronized (job) { if (job.failAt == null) { job.failAt = "mpd"; job.failIndex = -1; } }
            throw e;
        }
        if (!src.fp.equals(p.fp)) throw new Stop(HlsJournal.State.FAILED, "plan_changed");
        DashPlan.Track v = null, a = null;
        long est = -1;
        for (DashPlan.Option po : src.plan.options) {
            if (po.audio != null && po.video.rep.index == o.videoRep && po.audio.rep.index == o.audioRep) { v = po.video; a = po.audio; est = po.estimatedBytes; }
        }
        if (v == null || a == null) throw new Stop(HlsJournal.State.FAILED, "plan_changed");
        return new Chosen(o, v, a, resources(v), resources(a), est);
    }

    /** Init + alle Stücke der Spur in Folge (Adressen nur im Arbeitsspeicher). */
    private static List<String> resources(DashPlan.Track t) {
        List<String> l = new ArrayList<>(t.count() + 1);
        l.add(t.initUrl());
        for (int i = 0; i < t.count(); i++) l.add(t.segment(i).url);
        return l;
    }

    private Fmp4Inspect.Info inspectInit(Job job, char track, String kind, String codecs) throws Exception {
        File f = new File(job.dir, track + "0.seg");
        try { return Fmp4Inspect.init(f, kind, codecs); }
        catch (DashException e) { throw new Stop(HlsJournal.State.FAILED, e.reason); }
    }

    private void fetchAll(Job job, List<Object[]> tasks, long estimate) throws Exception {
        AtomicInteger next = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread[] workers = new Thread[Math.min(FETCH_PER_JOB, Math.max(1, tasks.size()))];
        for (int w = 0; w < workers.length; w++) {
            workers[w] = new Thread(() -> {
                try {
                    while (failure.get() == null) {
                        int k = next.getAndIncrement();
                        if (k >= tasks.size()) return;
                        checkStop(job);
                        Object[] t = tasks.get(k);
                        fetchOne(job, (Character) t[0], (Integer) t[1], (String) t[2], estimate);
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                    job.cancel.cancel();
                }
            }, "evp-dash-fetch");
            workers[w].setDaemon(true);
            workers[w].start();
        }
        for (Thread t : workers) t.join();
        Throwable f = failure.get();
        if (f instanceof Error) throw (Error) f;
        if (f != null) throw (Exception) f;
    }

    private void fetchOne(Job job, char track, int index, String url, long estimate) throws Exception {
        String key = track + Integer.toString(index);
        File seg = new File(job.dir, key + ".seg"), part = new File(job.dir, key + ".part");
        HlsJournal.Res known;
        synchronized (job) { known = job.journal.resources.get(key); }
        HlsFetch fetch = new HlsFetch(http, job.cancel, job.context);
        if (known != null) {
            boolean ok = known.etag != null && seg.isFile() && seg.length() == known.length && known.sha256.equals(fileSha(seg));
            if (ok) {
                acquireHttp(job);
                try { ok = fetch.unchanged(url, known.length, known.etag); }
                finally { slots.releaseHttp(); }
            }
            if (ok) {
                job.bytes.addAndGet(known.length);
                job.done.incrementAndGet();
                progress(job);
                return;
            }
            synchronized (job) { job.journal.drop(job.dir, key, hooks); }
            Files.deleteIfExists(seg.toPath());
        }
        HlsFetch.Fetched got;
        acquireHttp(job);
        try {
            got = fetch.resourceVerified(url, part, index == 0 ? Fmp4Inspect.MAX_INIT_BYTES : HlsPlan.MAX_SEGMENT_BYTES);
        } catch (HlsException e) {
            part.delete();
            String m = e.getMessage();
            if (m != null && m.startsWith("http_")) synchronized (job) {
                if (job.failAt == null) { job.failAt = (track == 'v' ? "video_" : "audio_") + (index == 0 ? "init" : "segment"); job.failIndex = index == 0 ? -1 : index - 1; }
            }
            if ("cancelled".equals(m) || job.cancel.isCancelled()) checkStop(job);
            throw e;
        } catch (IOException e) {
            part.delete();
            checkStop(job);
            throw e;
        } finally {
            slots.releaseHttp();
        }
        // Stücke vor der Aufnahme ins Journal prüfen (Init prüft der Aufrufer zusammen mit dem MPD-Codec)
        if (index > 0) {
            try { Fmp4Inspect.segment(part); }
            catch (DashException e) { part.delete(); throw new Stop(HlsJournal.State.FAILED, e.reason); }
        }
        hooks.at("segment-fetched");
        Files.move(part.toPath(), seg.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        HlsJournal.syncDir(job.dir);
        hooks.at("segment-renamed");
        long sum = job.bytes.addAndGet(got.length);
        if (sum > HlsPlan.MAX_MEDIA_BYTES) throw new Stop(HlsJournal.State.FAILED, "size_limit");
        synchronized (job) { job.journal.append(job.dir, new HlsJournal.Res(track, index, got.length, got.sha256, got.strongEtag), hooks); }
        hooks.at("segment-journaled");
        job.done.incrementAndGet();
        long rest = estimate > 0 ? Math.max(0, estimate - sum) : 0;
        if (free() < rest + sum + SPACE_RESERVE_BYTES / 2) throw new Stop(HlsJournal.State.FAILED, "no_space");
        progress(job);
    }

    private void acquireHttp(Job job) throws Exception {
        boolean got;
        try { got = slots.acquireHttp(HTTP_WAIT_MS, () -> stopWanted(job)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new Stop(HlsJournal.State.INTERRUPTED, "stopped"); }
        if (!got) { checkStop(job); throw new Stop(HlsJournal.State.INTERRUPTED, "busy"); }
    }

    private void acquireRemuxer(Job job) throws Stop {
        try {
            while (!remuxer.tryAcquire(50, TimeUnit.MILLISECONDS)) checkStop(job);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Stop(HlsJournal.State.INTERRUPTED, "stopped");
        }
        try { checkStop(job); } catch (Stop s) { remuxer.release(); throw s; }
    }

    private static boolean stopWanted(Job job) { return job.cancel.isCancelled() || job.stopRequested || job.userCancelled(); }

    private static void checkStop(Job job) throws Stop {
        if (job.userCancelled()) throw new Stop(HlsJournal.State.CANCELLED, "");
        if (job.stopRequested) throw new Stop(HlsJournal.State.INTERRUPTED, "stopped");
        if (job.cancel.isCancelled()) throw new Stop(HlsJournal.State.INTERRUPTED, "stopped");
    }

    private void copy(Job job, File out, DownloadStore.Target target) throws Stop {
        byte[] buf = new byte[256 << 10];
        try (InputStream in = new FileInputStream(out)) {
            OutputStream o = target.out();
            long n = 0, next = 64L << 20;
            for (int r; (r = in.read(buf)) > 0; ) {
                if ((n += r) >= next) { next = n + (64L << 20); checkStop(job); }
                o.write(buf, 0, r);
            }
            o.close();
        } catch (IOException e) {
            job.cause = e.toString();
            throw new Stop(HlsJournal.State.FAILED, "storage");
        }
    }

    private List<File> files(Job job, char track, int count) throws Stop {
        List<File> l = new ArrayList<>();
        synchronized (job) {
            for (int i = 0; i < count; i++) {
                HlsJournal.Res r = job.journal.resources.get(track + Integer.toString(i));
                File f = new File(job.dir, track + Integer.toString(i) + ".seg");
                if (r == null || !f.isFile() || f.length() != r.length) throw new Stop(HlsJournal.State.FAILED, "incomplete");
                l.add(f);
            }
        }
        return l;
    }

    private long free() throws Stop {
        try { return store.freeBytes(); } catch (IOException e) { throw new Stop(HlsJournal.State.FAILED, "storage"); }
    }

    private void save(Job job) throws IOException { job.journal.save(job.dir, hooks); }

    private void progress(Job job) { progress(job, false); }

    private void progress(Job job, boolean force) {
        long now = System.nanoTime();
        synchronized (job) {
            if (!force && now - job.lastNote < 500_000_000L) return;
            job.lastNote = now;
        }
        listener.progress(job.id, job.journal.title, snapshot(job));
    }

    static final String NO_SHAPE = repeat('0', 64);

    private static String repeat(char c, int n) { char[] a = new char[n]; Arrays.fill(a, c); return new String(a); }

    static int parts(HlsJournal j) { return NO_SHAPE.equals(j.shape) ? 0 : j.videoCount + j.audioCount; }

    static Progress snapshot(Job job) {
        int of;
        synchronized (job) { of = parts(job.journal); }
        Phase ph = job.phase;
        Progress.Phase p = ph == Phase.MUX ? Progress.Phase.MUX : ph == Phase.VERIFY ? Progress.Phase.VERIFY : ph == Phase.SAVE ? Progress.Phase.SAVE : Progress.Phase.FETCH;
        return Progress.hls(p, job.done.get(), of, job.bytes.get());
    }

    private void phase(Job job, Phase p) {
        job.phase = p;
        progress(job, true);
    }

    private void finish(Job job, HlsJournal.State state, String reason, Object saved) {
        synchronized (this) {
            synchronized (job) {
                if (state != HlsJournal.State.INTERRUPTED) {
                    deleteData(job.dir);
                    job.journal.resources.clear();
                }
                job.journal.state = state;
                job.journal.reason = state == HlsJournal.State.CANCELLED ? "" : code(reason);
                try { job.journal.save(job.dir, hooks); } catch (IOException ignored) { /* nächster Start: recover() */ }
                job.active = false;
                job.phase = Phase.NONE;
                if (state != HlsJournal.State.INTERRUPTED) { job.source = null; job.sourceOption = null; }
            }
            running.decrementAndGet();
            slots.release();
            prune();
            notifyAll();
        }
        long size;
        synchronized (job) { size = state == HlsJournal.State.DONE ? job.journal.total : -1; }
        listener.finished(job.id, job.journal.title, state.name().toLowerCase(Locale.ROOT), code(reason), saved, size);
    }

    // ------------------------------------------------------------------ Wiederherstellung nach Prozessende

    private void recover() throws IOException {
        File[] dirs = root.listFiles();
        if (dirs == null) throw new IOException("dash root unreadable");
        Arrays.sort(dirs, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File d : dirs) {
            if (!d.isDirectory() || !d.getName().matches("[0-9a-f]{32}")) { deleteTree(d); continue; }
            HlsJournal j;
            try { j = HlsJournal.load(d); } catch (IOException e) { j = null; }
            if (j == null || !j.id.equals(d.getName())) { deleteTree(d); continue; }
            Job job = new Job(j.id, d, j);
            long age = clock.nowMs() - new File(d, "journal").lastModified();
            boolean changed = false;
            File[] fs = d.listFiles();
            if (fs != null) for (File f : fs) {
                String n = f.getName();
                if (n.equals("journal") || n.equals("segments.log")) continue;
                if (n.endsWith(".seg") && j.resources.containsKey(n.substring(0, n.length() - 4)) && f.length() == j.resources.get(n.substring(0, n.length() - 4)).length) continue;
                f.delete();
            }
            for (HlsJournal.Res r : new ArrayList<>(j.resources.values())) {
                if (!new File(d, r.key() + ".seg").isFile()) j.drop(d, r.key(), hooks);
            }
            if (j.cancelRequested && j.state != HlsJournal.State.DONE && j.state != HlsJournal.State.CANCELLED) {
                deleteData(d);
                j.resources.clear();
                j.state = HlsJournal.State.CANCELLED;
                j.reason = "";
                changed = true;
            } else if (j.state == HlsJournal.State.PUBLISHING) {
                boolean published = j.ref != null && j.total > 0 && isPublished(j.ref, j.total);
                j.state = published ? HlsJournal.State.DONE : HlsJournal.State.INTERRUPTED;
                j.reason = published ? "" : "restarted";
                if (published) { deleteData(d); j.resources.clear(); }
                else { j.ref = null; j.total = -1; }
                changed = true;
            } else if (j.state == HlsJournal.State.RUNNING) {
                j.state = HlsJournal.State.INTERRUPTED;
                j.reason = "restarted";
                changed = true;
            }
            if (j.state == HlsJournal.State.INTERRUPTED && age > INTERRUPTED_KEEP_MS) {
                deleteData(d);
                j.resources.clear();
                j.state = HlsJournal.State.FAILED;
                j.reason = "expired";
                changed = true;
            }
            if (j.state == HlsJournal.State.DONE || j.state == HlsJournal.State.FAILED || j.state == HlsJournal.State.CANCELLED) {
                if (!j.resources.isEmpty()) { deleteData(d); j.resources.clear(); changed = true; }
            }
            if (changed) j.save(d, hooks);
            jobs.put(j.id, job);
        }
        prune();
    }

    private boolean isPublished(String ref, long total) {
        try { return store.isPublished(ref, total); } catch (IOException | RuntimeException e) { return false; }
    }

    private void prune() {
        int excess = jobs.size() - JOURNAL_KEEP;
        for (Iterator<Job> it = jobs.values().iterator(); it.hasNext() && excess > 0; ) {
            Job j = it.next();
            HlsJournal.State s = j.journal.state;
            if (j.active || s == HlsJournal.State.INTERRUPTED || s == HlsJournal.State.RUNNING || s == HlsJournal.State.PUBLISHING) continue;
            deleteTree(j.dir);
            it.remove();
            excess--;
        }
    }

    static void deleteData(File dir) {
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) if (!f.getName().equals("journal")) f.delete();
    }

    static void deleteTree(File f) {
        File[] fs = f.listFiles();
        if (fs != null) for (File c : fs) deleteTree(c);
        f.delete();
    }

    /** Gesalzene Form: Fingerabdruck des MPD, gewählte Darstellungen, Ressourcenzahlen (keine Adresse im Klartext). */
    static String shape(String salt, String fp, Chosen c) {
        String s = "dash-shape-v1\n" + fp + "\n" + c.option.videoRep + " " + c.option.audioRep + " " + c.vRes.size() + " " + c.aRes.size() + "\n";
        return hex(sha256(salt.getBytes(StandardCharsets.US_ASCII), s.getBytes(StandardCharsets.UTF_8)));
    }

    // ------------------------------------------------------------------ Antworten (ohne URL)

    private String json(Job j) {
        HlsJournal.State s;
        String reason, at;
        int of, done, atIndex;
        synchronized (j) {
            s = j.journal.state; reason = j.journal.reason; of = parts(j.journal);
            done = j.active ? j.done.get() : j.journal.resources.size();
            at = j.failAt; atIndex = j.failIndex;
        }
        String state = j.active || s == HlsJournal.State.PUBLISHING ? "running" : s.name().toLowerCase(Locale.ROOT);
        boolean fin = "done".equals(state) && j.journal.total > 0;
        long bytes = fin ? j.journal.total : j.bytes.get();
        return "{\"ok\":true,\"id\":\"" + j.id + "\",\"kind\":\"dash\",\"state\":\"" + state + "\",\"bytes\":" + bytes + ",\"total\":"
            + (fin ? j.journal.total : -1) + ",\"done\":" + done + ",\"of\":" + of
            + ",\"phase\":\"" + (j.active ? j.phase.name().toLowerCase(Locale.ROOT) : "") + "\",\"reason\":\"" + code(reason) + "\""
            + (j.active && j.wait != de.evp.helfer.dl.Retry.Wait.NONE ? ",\"wait\":\"" + (j.wait == de.evp.helfer.dl.Retry.Wait.OFFLINE ? "offline" : "retry") + "\"" : "")
            + (!j.active && at != null && code(reason).startsWith("http_") && (s == HlsJournal.State.INTERRUPTED || s == HlsJournal.State.FAILED)
                ? ",\"at\":\"" + at + "\",\"at_index\":" + atIndex : "")
            + "}";
    }

    private static String busy(String id) { return "{\"ok\":false,\"id\":\"" + id + "\",\"state\":\"busy\"}"; }

    private static String reject(String id, String reason) { return "{\"ok\":false,\"id\":\"" + id + "\",\"state\":\"rejected\",\"reason\":\"" + code(reason) + "\"}"; }

    private static String unknown(String id) { return "{\"ok\":true,\"id\":\"" + id + "\",\"state\":\"unknown\"}"; }

    /** Gründe nur als feste Codes: Kleinbuchstaben, Ziffern, Unterstrich (max. 40). */
    static String code(String reason) {
        if (reason == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < reason.length() && b.length() < 40; i++) {
            char c = reason.charAt(i);
            if (c == '-') c = '_';
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') b.append(c);
        }
        return b.toString();
    }

    private byte[] randomBytes(int n) { byte[] b = new byte[n]; random.nextBytes(b); return b; }

    static String fileSha(File f) throws IOException {
        MessageDigest d;
        try { d = MessageDigest.getInstance("SHA-256"); } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        byte[] buf = new byte[256 << 10];
        try (InputStream in = new FileInputStream(f)) {
            for (int n; (n = in.read(buf)) > 0; ) d.update(buf, 0, n);
        }
        return hex(d.digest());
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xf, 16)).append(Character.forDigit(x & 0xf, 16));
        return sb.toString();
    }

    /** SHA-256 über Salz und Daten (eigene Hilfe; die HLS-Hilfen bleiben paketprivat und unverändert). */
    static byte[] sha256(byte[] salt, byte[] data) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            d.update(salt);
            d.update(data);
            return d.digest();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
