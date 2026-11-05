package de.evp.helfer.hls;

import java.io.File;
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
import java.util.Collections;
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

import de.evp.helfer.dl.DirectDownloads;
import de.evp.helfer.dl.DownloadStore;
import de.evp.helfer.dl.JobSlots;
import de.evp.helfer.net.NetBlockedException;
import de.evp.helfer.net.SafeHttpClient;

/**
 * P4b: HLS-VOD → MP4 im Helfer (Koordinator). Ablauf je Auftrag: Listen erneut laden und mit dem Plan vergleichen →
 * Ressourcen einzeln über {@link SafeHttpClient} laden (Länge, SHA-256, fsync, Umbenennen, Journal) → Remux (global
 * genau einer) → Prüfung der Ausgabe → Veröffentlichung über {@link DownloadStore} (atomar, wie bei MP4).
 *
 * <ul>
 *   <li>Pläne ({@code hlsplan}): nur im Arbeitsspeicher, gebunden an Kanal-Sitzung ({@code peer}) und {@code bind},
 *       {@link #PLAN_TTL_MS} gültig, höchstens {@link #MAX_PLANS_PER_PEER} je Sitzung (älteste fällt heraus). Nach
 *       außen nur Optionen ohne Adressen.</li>
 *   <li>Grenzen: Aufträge und HTTP-Anfragen über {@link JobSlots} (mit MP4 geteilt: 2 Aufträge, 4 Anfragen), je
 *       HLS-Auftrag {@link #FETCH_PER_JOB} parallele Abrufe, 1 Remuxer, Budgets aus {@link HlsPlan}/{@link HlsPlaylist},
 *       Speicherquote vor dem Start und laufend.</li>
 *   <li>Journal ohne Adressen ({@link HlsJournal}); persistenter Abbruchwunsch wird zuerst geschrieben und gewinnt nach
 *       jedem Neustart vor einer Wiederaufnahme.</li>
 *   <li>Wiederaufnahme nur durch einen neuen {@code hlsstart} mit derselben Auftragskennung und gleichem
 *       Form-Fingerabdruck; eine Ressource wird nur wiederverwendet, wenn Datei, Länge und SHA-256 stimmen UND der
 *       Server per starkem ETag (If-Range, 206) bestätigt, dass sie unverändert ist. Ohne starken ETag: neu laden.</li>
 *   <li>Unterbrochen (wiederaufnehmbar): Netzwerk, Server-/Quellfehler (z. B. abgelaufene Adresse), Dienstende,
 *       Prozessende. Fehlgeschlagen (alles gelöscht): Sicherheitsprüfung, Grenzen, Formatfehler, Prüfung. Abgebrochen:
 *       alles gelöscht.</li>
 * </ul>
 */
public final class HlsEngine implements de.evp.helfer.ControlCommands.HlsControl {
    public static final long PLAN_TTL_MS = 10 * 60_000L;
    public static final int MAX_PLANS_PER_PEER = 4;
    public static final int MAX_PLANS = 16;
    public static final int FETCH_PER_JOB = 2;
    public static final long SPACE_RESERVE_BYTES = 256L << 20;
    public static final long INTERRUPTED_KEEP_MS = 7L * 24 * 3600_000L;
    static final int JOURNAL_KEEP = 64;
    static final long PLAN_DEADLINE_MS = 20_000;
    static final long HTTP_WAIT_MS = 10 * 60_000L;

    /** Zeitquelle (Tests: steuerbar). */
    public interface Clock { long nowMs(); }

    private final SafeHttpClient http;
    private final DownloadStore store;
    private final DirectDownloads.Listener listener;
    private final File root;
    private final HlsRemux remux;
    private final JobSlots slots;
    private final boolean opusAllowed;
    private final long maxSamples;
    private final HlsJournal.Hooks hooks;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Semaphore remuxer = new Semaphore(1, true);
    private final Map<String, PlanEntry> plans = new LinkedHashMap<>();
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final AtomicInteger running = new AtomicInteger();
    private boolean stopped;

    public HlsEngine(SafeHttpClient http, DownloadStore store, DirectDownloads.Listener listener, File root, HlsRemux remux,
            JobSlots slots, boolean opusAllowed, long maxSamples) throws IOException {
        this(http, store, listener, root, remux, slots, opusAllowed, maxSamples, HlsJournal.Hooks.NONE, System::currentTimeMillis);
    }

    public HlsEngine(SafeHttpClient http, DownloadStore store, DirectDownloads.Listener listener, File root, HlsRemux remux,
            JobSlots slots, boolean opusAllowed, long maxSamples, HlsJournal.Hooks hooks, Clock clock) throws IOException {
        this.http = http.withoutConnectionReuse();
        this.store = store;
        this.listener = listener;
        this.root = root;
        this.remux = remux;
        this.slots = slots;
        this.opusAllowed = opusAllowed;
        this.maxSamples = maxSamples;
        this.hooks = hooks;
        this.clock = clock;
        Files.createDirectories(root.toPath());
        recover();
    }

    // ================================================================== Pläne (hlsplan)

    static final class PlanEntry {
        final String planId;
        final byte[] peer;
        final String bind;
        final long expires;
        final String sourceUrl, masterSha;     // nur im Arbeitsspeicher
        final List<HlsPlan.Option> options;
        final Map<String, String> fingerprints = new LinkedHashMap<>();   // Option → Plan-Fingerabdruck (vorab geladen)
        final double duration;
        PlanEntry(String planId, byte[] peer, String bind, long expires, String sourceUrl, String masterSha, List<HlsPlan.Option> options, double duration) {
            this.planId = planId; this.peer = peer; this.bind = bind; this.expires = expires; this.sourceUrl = sourceUrl;
            this.masterSha = masterSha; this.options = options; this.duration = duration;
        }
    }

    /** Ergebnis eines Listenabrufs (Master oder Medienliste). */
    static final class Source {
        final HlsPlaylist.Master master;     // null: die Adresse war direkt eine Medienliste
        final HlsPlaylist.Media media;
        final String sha;
        final List<HlsPlan.Option> options;
        Source(HlsPlaylist.Master master, HlsPlaylist.Media media, String sha, List<HlsPlan.Option> options) {
            this.master = master; this.media = media; this.sha = sha; this.options = options;
        }
    }

    /** Ausgewählte Medienlisten einer Option und der daraus gebaute Plan. */
    static final class Chosen {
        final HlsPlan.Option option;
        final HlsPlaylist.Media video, audio;
        final HlsPlan.Plan plan;
        Chosen(HlsPlan.Option option, HlsPlaylist.Media video, HlsPlaylist.Media audio, HlsPlan.Plan plan) {
            this.option = option; this.video = video; this.audio = audio; this.plan = plan;
        }
    }

    /**
     * {@code hlsplan}: lädt und prüft die Liste(n) und antwortet nur mit Optionen ohne Adressen. {@code peer}: Kennung
     * der authentifizierten Kanal-Sitzung; {@code bind}: Kadris Bindung (Player, Generation, Revision).
     */
    @Override
    public String plan(byte[] peer, String bind, String url) {
        SafeHttpClient.Cancellation cancel = new SafeHttpClient.Cancellation();
        try {
            if (stoppedNow()) return planFail("stopped");
            long deadline = clock.nowMs() + PLAN_DEADLINE_MS;
            Source src = loadSource(url, cancel, deadline);
            HlsPlan.Option first = src.options.get(0);
            Chosen c = choose(src, first, cancel, deadline);
            String planId = hex(randomBytes(16));
            PlanEntry e = new PlanEntry(planId, peer.clone(), bind, clock.nowMs() + PLAN_TTL_MS, url, src.sha, src.options,
                Math.max(c.plan.videoSeconds, c.plan.audioSeconds));
            e.fingerprints.put(first.id, c.plan.fingerprint);
            synchronized (this) { putPlan(e); }
            StringBuilder b = new StringBuilder("{\"ok\":true,\"planId\":\"").append(planId).append("\",\"expiresSec\":")
                .append(PLAN_TTL_MS / 1000).append(",\"duration\":").append(String.format(Locale.ROOT, "%.3f", e.duration)).append(",\"options\":[");
            for (int i = 0; i < src.options.size(); i++) b.append(i > 0 ? "," : "").append(src.options.get(i).describe());
            return b.append("]}").toString();
        } catch (HlsException e) {
            return planFail(e.getMessage());
        } catch (NetBlockedException e) {
            return planFail("blocked_" + e.reason.name().toLowerCase(Locale.ROOT));
        } catch (IOException | RuntimeException e) {
            return planFail("network");
        }
    }

    private synchronized boolean stoppedNow() { return stopped; }

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

    private static String planFail(String reason) {
        return "{\"ok\":false,\"reason\":\"" + code(reason) + "\"}";
    }

    /** Master oder Medienliste laden, streng prüfen, Optionen bilden. */
    private Source loadSource(String url, SafeHttpClient.Cancellation cancel, long deadline) throws IOException, HlsException {
        HlsFetch.Text t = manifest(url, cancel, deadline);
        HlsPlaylist.Parsed p = HlsPlaylist.parse(t.body, t.finalUrl);
        String sha = hex(sha256(t.body));
        if (p.master != null) return new Source(p.master, null, sha, HlsPlan.options(p.master, randomBytes(16), opusAllowed));
        // Direkt eine Medienliste: genau eine Option, Ton in den Segmenten (Codecs prüft der Remuxer)
        HlsPlaylist.Variant v = new HlsPlaylist.Variant(0, 0, 0, 0, 0, null, null, t.finalUrl);
        List<HlsPlan.Option> one = Collections.singletonList(new HlsPlan.Option(
            hex(HlsPlan.sha256(randomBytes(16), "opt:media".getBytes(StandardCharsets.US_ASCII))).substring(0, 24), v, null, "unknown"));
        return new Source(null, p.media, sha, one);
    }

    private Chosen choose(Source src, HlsPlan.Option o, SafeHttpClient.Cancellation cancel, long deadline) throws IOException, HlsException {
        HlsPlaylist.Media video = src.media != null ? src.media : media(o.variant.uri, cancel, deadline);
        HlsPlaylist.Media audio = o.audio == null ? null : media(o.audio.uri, cancel, deadline);
        return new Chosen(o, video, audio, HlsPlan.build(video, audio, o.variant.bandwidth));
    }

    private HlsPlaylist.Media media(String url, SafeHttpClient.Cancellation cancel, long deadline) throws IOException, HlsException {
        HlsFetch.Text t = manifest(url, cancel, deadline);
        HlsPlaylist.Parsed p = HlsPlaylist.parse(t.body, t.finalUrl);
        if (p.media == null) throw new HlsException("nested-master");
        return p.media;
    }

    /** Ein Listenabruf über die gemeinsame Verbindungsgrenze; Frist für den ganzen Plan. */
    private HlsFetch.Text manifest(String url, SafeHttpClient.Cancellation cancel, long deadline) throws IOException, HlsException {
        long wait = deadline - clock.nowMs();
        if (wait <= 0) throw new HlsException("timeout");
        boolean got;
        try { got = slots.acquireHttp(wait, cancel::isCancelled); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new HlsException("cancelled"); }
        if (!got) throw new HlsException(cancel.isCancelled() ? "cancelled" : "busy");
        try { return new HlsFetch(http, cancel).manifest(url); }
        finally { slots.releaseHttp(); }
    }

    // ================================================================== Aufträge

    enum Phase { FETCH, MUX, VERIFY, SAVE, NONE }

    static final class Job {
        final String id;
        final File dir;
        final HlsJournal journal;      // Änderungen nur unter synchronized(job)
        volatile SafeHttpClient.Cancellation cancel = new SafeHttpClient.Cancellation();   // je Lauf neu
        volatile boolean active, stopRequested;
        volatile Phase phase = Phase.NONE;
        final AtomicLong bytes = new AtomicLong();
        final AtomicInteger done = new AtomicInteger();
        volatile long estimate = -1;
        volatile String cause = "";    // nur Diagnose, nie im Kanal
        Job(String id, File dir, HlsJournal journal) { this.id = id; this.dir = dir; this.journal = journal; }
        boolean userCancelled() { synchronized (this) { return journal.cancelRequested; } }
    }

    /** Endgültiger Ausgang einer Phase. */
    static final class Stop extends Exception {
        final HlsJournal.State state;
        final String reason;
        Stop(HlsJournal.State state, String reason) { super(reason, null, false, false); this.state = state; this.reason = code(reason); }
    }

    @Override
    public synchronized boolean knows(String id) { return jobs.containsKey(id); }

    /**
     * {@code hlsstart}. Bekannte Kennung: unterbrochener Auftrag ohne Abbruchwunsch → Wiederaufnahme mit diesem Plan;
     * sonst nur der bekannte Zustand (nie ein zweiter Start).
     */
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
            plans.remove(planId);              // ein Plan startet höchstens einen Auftrag
            running.incrementAndGet();
            if (job == null) {
                File dir = new File(root, id);
                HlsJournal j = new HlsJournal();
                j.id = id;
                j.state = HlsJournal.State.RUNNING;
                j.salt = hex(randomBytes(16));
                j.shape = "0".repeat(64);      // gesetzt, sobald die Listen geprüft sind
                j.videoCount = 1;
                j.title = DirectDownloads.displayTitle(name);
                job = new Job(id, dir, j);
                try {
                    Files.createDirectories(dir.toPath());
                    synchronized (job) { j.save(dir, hooks); }          // erst vermerkt, dann gestartet (at most once)
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
        final Job j = job;
        final PlanEntry p = plan;
        Thread t = new Thread(() -> run(j, p, optionId), "evp-hls");
        t.setDaemon(true);
        try { t.start(); }
        catch (RuntimeException | Error e) { finish(j, HlsJournal.State.INTERRUPTED, "stopped", null); }
        return json(j);
    }

    private static HlsPlan.Option option(PlanEntry p, String optionId) {
        for (HlsPlan.Option o : p.options) if (o.id.equals(optionId)) return o;
        return null;
    }

    @Override
    public synchronized String status(String id) {
        Job j = jobs.get(id);
        return j == null ? unknown(id) : json(j);
    }

    /**
     * Abbruch: der Wunsch wird zuerst dauerhaft vermerkt (gewinnt nach jedem Neustart), dann laufende Abrufe, Warten und
     * Remux gestoppt. Ein nicht laufender (unterbrochener) Auftrag wird sofort vollständig entfernt.
     */
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
                        j.journal.resources.clear();      // Log ist mit gelöscht
                        j.journal.state = HlsJournal.State.CANCELLED;
                        j.journal.reason = "";
                        try { j.journal.save(j.dir, hooks); } catch (IOException ignored) { /* Abbruchwunsch steht schon */ }
                    }
                }
            }
            listener.finished(j.id, j.journal.title, "cancelled", "", null);
        }
        return json(j);
    }

    /** Dienstende: keine neuen Starts/Pläne; laufende Aufträge enden als "interrupted" (Daten bleiben für eine Wiederaufnahme). */
    public void stopAll() {
        List<Job> snapshot;
        synchronized (this) { stopped = true; plans.clear(); snapshot = new ArrayList<>(jobs.values()); }
        for (Job j : snapshot) if (j.active) { j.stopRequested = true; j.cancel.cancel(); }
    }

    public synchronized void serviceStarted() { stopped = false; }

    /** Wartet höchstens {@code ms}, bis kein Auftrag mehr läuft. true = leer. */
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

    private void run(Job job, PlanEntry planEntry, String optionId) {
        HlsJournal.State state = HlsJournal.State.FAILED;
        String reason = "network";
        Object saved = null;
        File out = new File(job.dir, "out.mp4.part");
        DownloadStore.Target target = null;
        try {
            if (!store.writable()) throw new Stop(HlsJournal.State.FAILED, "storage_permission");
            Chosen c = recheck(job, planEntry, optionId);
            List<String> vRes = c.plan.videoResources, aRes = c.plan.audioResources;
            String shape = shape(job.journal.salt, c);
            synchronized (job) {
                if (!shape.equals(job.journal.shape) || job.journal.videoCount != vRes.size() || job.journal.audioCount != aRes.size()) {
                    // neuer Auftrag oder andere Form: nichts Früheres verwenden
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
            job.estimate = c.plan.estimatedBytes > 0 ? c.plan.estimatedBytes : -1;
            // Speicherquote: Medien + Ausgabe (≈ gleiche Größe) + Reserve; ohne Schätzung prüft der Abruf laufend.
            if (c.plan.estimatedBytes > 0 && free() < 2 * c.plan.estimatedBytes + SPACE_RESERVE_BYTES) throw new Stop(HlsJournal.State.FAILED, "no_space");
            fetchAll(job, vRes, aRes, c.plan.estimatedBytes);
            checkStop(job);

            job.phase = Phase.MUX;
            List<File> vFiles = files(job, 'v', vRes.size()), aFiles = files(job, 'a', aRes.size());
            long media = 0;
            for (File f : vFiles) media += f.length();
            for (File f : aFiles) media += f.length();
            if (free() < media + SPACE_RESERVE_BYTES / 2) throw new Stop(HlsJournal.State.FAILED, "no_space");
            acquireRemuxer(job);
            HlsRemux.Result r;
            try {
                hooks.at("remux-start");
                r = remux.remux(new HlsRemux.Input(vFiles, aFiles, c.video.mapUri != null, maxSamples), out, () -> stopWanted(job));
                checkStop(job);
                job.phase = Phase.VERIFY;
                double tol = Math.max(1.0, Math.max(c.video.targetDuration, c.audio == null ? 0 : c.audio.targetDuration));
                remux.verify(out, r, c.plan.videoSeconds, c.audio == null ? c.plan.videoSeconds : c.plan.audioSeconds, tol, () -> stopWanted(job));
            } finally {
                remuxer.release();
            }
            checkStop(job);
            hooks.at("verified");

            job.phase = Phase.SAVE;
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
            job.bytes.set(total);
            state = HlsJournal.State.DONE;
            reason = "";
        } catch (Stop s) {
            state = s.state;
            reason = s.reason;
        } catch (HlsException e) {
            state = classify(e.getMessage());
            reason = e.getMessage();
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
        finish(job, state, reason, saved);
    }

    /** Neutraler Grund → Endzustand. Netzwerk- und Quellprobleme sind wiederaufnehmbar, alles andere nicht. */
    static HlsJournal.State classify(String reason) {
        String r = reason == null ? "" : reason;
        if (r.equals("io") || r.equals("timeout") || r.equals("cancelled") || r.equals("busy") || r.equals("length-mismatch")
            || r.startsWith("http_5") || r.equals("http_403") || r.equals("http_404") || r.equals("http_410") || r.equals("http_429")) {
            return HlsJournal.State.INTERRUPTED;
        }
        return HlsJournal.State.FAILED;
    }

    /** Listen erneut laden (Master unverändert, gewählte Medienlisten wie im Plan, falls dort vorab geladen). */
    private Chosen recheck(Job job, PlanEntry p, String optionId) throws IOException, HlsException, Stop {
        long deadline = clock.nowMs() + PLAN_DEADLINE_MS;
        Source src = loadSource(p.sourceUrl, job.cancel, deadline);
        if (!src.sha.equals(p.masterSha)) throw new Stop(HlsJournal.State.FAILED, "plan_changed");
        int idx = -1;
        for (int i = 0; i < p.options.size(); i++) if (p.options.get(i).id.equals(optionId)) idx = i;
        // Gleiche Liste (gleicher Hash) → gleiche Optionen in gleicher Reihenfolge; IDs sind je Plan gesalzen.
        HlsPlan.Option o = src.options.get(idx);
        HlsPlan.Option planned = p.options.get(idx);
        if (o.variant.index != planned.variant.index || (o.audio == null) != (planned.audio == null)
            || (o.audio != null && o.audio.index != planned.audio.index)) throw new Stop(HlsJournal.State.FAILED, "plan_changed");
        Chosen c = choose(src, o, job.cancel, deadline);
        String fp = p.fingerprints.get(optionId);
        if (fp != null && !fp.equals(c.plan.fingerprint)) throw new Stop(HlsJournal.State.FAILED, "plan_changed");
        return c;
    }

    /** Lädt alle Ressourcen (oder bestätigt früher geladene); höchstens {@link #FETCH_PER_JOB} gleichzeitig. */
    private void fetchAll(Job job, List<String> vRes, List<String> aRes, long estimate) throws Exception {
        List<Object[]> tasks = new ArrayList<>();
        for (int i = 0; i < vRes.size(); i++) tasks.add(new Object[] {'v', i, vRes.get(i)});
        for (int i = 0; i < aRes.size(); i++) tasks.add(new Object[] {'a', i, aRes.get(i)});
        AtomicInteger next = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        job.bytes.set(0);
        job.done.set(0);
        Thread[] workers = new Thread[FETCH_PER_JOB];
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
                } catch (Throwable e) {      // auch Error (z. B. Speichermangel): nie still einen Abrufer verlieren
                    failure.compareAndSet(null, e);
                    job.cancel.cancel();     // die anderen Abrufe sofort beenden (der Grund steht in failure)
                }
            }, "evp-hls-fetch");
            workers[w].setDaemon(true);
            workers[w].start();
        }
        for (Thread t : workers) t.join();
        Throwable f = failure.get();
        // Der erste eigene Fehler eines Abrufs hat Vorrang vor dem dadurch ausgelösten Abbruch der anderen.
        if (f instanceof Error) throw (Error) f;
        if (f != null) throw (Exception) f;
    }

    private void fetchOne(Job job, char track, int index, String url, long estimate) throws Exception {
        String key = track + Integer.toString(index);
        File seg = new File(job.dir, key + ".seg"), part = new File(job.dir, key + ".part");
        HlsJournal.Res known;
        synchronized (job) { known = job.journal.resources.get(key); }
        HlsFetch fetch = new HlsFetch(http, job.cancel);
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
            got = fetch.resourceVerified(url, part, HlsPlan.MAX_SEGMENT_BYTES);
        } catch (HlsException e) {
            part.delete();
            if ("cancelled".equals(e.getMessage()) || job.cancel.isCancelled()) checkStop(job);
            throw e;
        } catch (IOException e) {
            part.delete();
            checkStop(job);
            throw e;
        } finally {
            slots.releaseHttp();
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
        // Laufende Quote: Rest (geschätzt) + Ausgabe (≈ bisher geladen) + halbe Reserve
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

    /** Mit Sperre auf job. Ein nicht schreibbares Journal beendet den Auftrag (nie ohne Wiederherstellungsstand weiter). */
    private void save(Job job) throws IOException {
        job.journal.save(job.dir, hooks);
    }

    private long lastNote;

    private void progress(Job job) {
        long now = System.nanoTime();
        synchronized (this) { if (now - lastNote < 500_000_000L) return; lastNote = now; }
        listener.progress(job.id, job.journal.title, job.bytes.get(), job.estimate);
    }

    private void finish(Job job, HlsJournal.State state, String reason, Object saved) {
        synchronized (this) {
            synchronized (job) {
                if (state != HlsJournal.State.INTERRUPTED) {      // fertig, fehlgeschlagen, abgebrochen: keine Daten behalten
                    deleteData(job.dir);
                    job.journal.resources.clear();
                }
                job.journal.state = state;
                job.journal.reason = state == HlsJournal.State.CANCELLED ? "" : code(reason);
                try { job.journal.save(job.dir, hooks); } catch (IOException ignored) { /* nächster Start: recover() */ }
                job.active = false;
                job.phase = Phase.NONE;
            }
            running.decrementAndGet();
            slots.release();
            prune();
            notifyAll();
        }
        listener.finished(job.id, job.journal.title, state.name().toLowerCase(Locale.ROOT), code(reason), saved);
    }

    // ------------------------------------------------------------------ Wiederherstellung nach Prozessende

    private void recover() throws IOException {
        File[] dirs = root.listFiles();
        if (dirs == null) throw new IOException("hls root unreadable");
        Arrays.sort(dirs, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File d : dirs) {
            if (!d.isDirectory() || !HlsJournal.HEX32.matcher(d.getName()).matches()) { deleteTree(d); continue; }
            HlsJournal j;
            try { j = HlsJournal.load(d); } catch (IOException e) { j = null; }
            if (j == null || !j.id.equals(d.getName())) { deleteTree(d); continue; }   // unlesbar: nichts davon verwenden
            Job job = new Job(j.id, d, j);
            long age = clock.nowMs() - new File(d, "journal").lastModified();   // vor jeder Änderung im Ordner
            boolean changed = false;
            // Teil-Dateien, Ausgabe und Temp-Journal sind nie gültig
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
                deleteData(d);                                // Abbruchwunsch gewinnt vor jeder Wiederaufnahme
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

    /** Älteste beendete Aufträge über {@link #JOURNAL_KEEP} entfernen (mit Sperre auf this). */
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

    /** Alle Daten eines Auftrags außer dem Journal. */
    static void deleteData(File dir) {
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) if (!f.getName().equals("journal")) f.delete();
    }

    static void deleteTree(File f) {
        File[] fs = f.listFiles();
        if (fs != null) for (File c : fs) deleteTree(c);
        f.delete();
    }

    // ------------------------------------------------------------------ Form-Fingerabdruck

    /**
     * Gesalzener Fingerabdruck der Form (Option, Ziel-/Segmentdauern, Init ja/nein, Pfade ohne Query/Fragment). Gleich,
     * wenn dieselbe Quelle mit neuen Zugangs-Parametern (Query) erneut geplant wird; enthält keine Adresse im Klartext.
     */
    static String shape(String salt, Chosen c) {
        StringBuilder b = new StringBuilder("hls-shape-v1\n");
        HlsPlaylist.Variant v = c.option.variant;
        b.append("opt ").append(v.height).append('x').append(v.width).append(' ').append(v.bandwidth).append(' ').append(v.codecs)
            .append(" audio=").append(c.option.audio == null ? "-" : c.option.audio.language + "/" + c.option.audio.name + "/" + c.option.audio.channels).append('\n');
        canon(b, "V", c.video);
        if (c.audio != null) canon(b, "A", c.audio);
        return hex(HlsPlan.sha256(salt.getBytes(StandardCharsets.US_ASCII), b.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static void canon(StringBuilder b, String k, HlsPlaylist.Media m) {
        b.append(k).append(" target=").append(m.targetDuration).append(" map=").append(m.mapUri == null ? "-" : path(m.mapUri)).append('\n');
        for (HlsPlaylist.Segment s : m.segments) b.append(String.format(Locale.ROOT, "%.6f", s.duration)).append(' ').append(path(s.uri)).append('\n');
    }

    static String path(String url) {
        int q = url.indexOf('?'), h = url.indexOf('#');
        int end = q < 0 ? h : h < 0 ? q : Math.min(q, h);
        return end < 0 ? url : url.substring(0, end);
    }

    // ------------------------------------------------------------------ Antworten (ohne URL)

    private String json(Job j) {
        HlsJournal.State s;
        String reason;
        int of;
        int done;
        synchronized (j) {
            s = j.journal.state; reason = j.journal.reason; of = j.journal.videoCount + j.journal.audioCount;
            done = j.active ? j.done.get() : j.journal.resources.size();
        }
        // Ein Endzustand gilt erst, wenn der Arbeiter aufgeräumt hat (wie bei MP4: "beendet" heißt, nichts Unfertiges bleibt).
        String state = j.active || s == HlsJournal.State.PUBLISHING ? "running" : s.name().toLowerCase(Locale.ROOT);
        long bytes = s == HlsJournal.State.DONE ? Math.max(j.bytes.get(), j.journal.total) : j.bytes.get();
        return "{\"ok\":true,\"id\":\"" + j.id + "\",\"kind\":\"hls\",\"state\":\"" + state + "\",\"bytes\":" + bytes + ",\"total\":"
            + (s == HlsJournal.State.DONE ? j.journal.total : j.estimate) + ",\"done\":" + done + ",\"of\":" + of
            + ",\"phase\":\"" + (j.active ? j.phase.name().toLowerCase(Locale.ROOT) : "") + "\",\"reason\":\"" + code(reason) + "\"}";
    }

    private static String busy(String id) { return "{\"ok\":false,\"id\":\"" + id + "\",\"state\":\"busy\"}"; }

    private static String reject(String id, String reason) { return "{\"ok\":false,\"id\":\"" + id + "\",\"state\":\"rejected\",\"reason\":\"" + code(reason) + "\"}"; }

    private static String unknown(String id) { return "{\"ok\":true,\"id\":\"" + id + "\",\"state\":\"unknown\"}"; }

    /** Gründe nur als feste Codes: Kleinbuchstaben, Ziffern, Unterstrich; alles andere fällt weg (max. 40). */
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

    // ------------------------------------------------------------------ Hilfen

    private byte[] randomBytes(int n) { byte[] b = new byte[n]; random.nextBytes(b); return b; }

    static byte[] sha256(byte[] b) { return HlsPlan.sha256(new byte[0], b); }

    static String fileSha(File f) throws IOException {
        MessageDigest d = HlsFetch.sha256();
        byte[] buf = new byte[256 << 10];
        try (InputStream in = new FileInputStream(f)) {
            for (int n; (n = in.read(buf)) > 0; ) d.update(buf, 0, n);
        }
        return hex(d.digest());
    }

    static String hex(byte[] b) { return HlsPlan.hex(b); }
}
