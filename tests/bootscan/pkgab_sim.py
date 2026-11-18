#!/usr/bin/env python3
"""Deterministischer Gerätesimulator für die A/B-Paketdiagnose (nur Selbsttests, kein Gerät).

Bildet mit EINER virtuellen Geräteuhr nach, was tools/run-pkgremoval-probe.sh und MediaStoreBootScanProbeTest auf einem
Emulator erzeugen: Logcat-Zeilen ("-v epoch"), MediaStore-Zeilen (Besitzer, ausstehend, Pfad), Dateien, Paketzustand,
Mount-Scan, verzögertes oder sofortiges Verwaisen, Host-Abfragen, Instrumentierungsausgaben. Alle Zeitstempel einer
Simulation sind widerspruchsfrei (eine Uhr, nur vorwärts). Wird von tests/bootscan/test_pkgremoval_verdict.py direkt und
von tests/bootscan/test_pkgremoval_script.py über ein nachgebildetes adb benutzt.

Szenario (dict):
  scan_after_pkg_end   ms nach dem Ende des Paketschritts, zu dem der externe Mount-Scan endet (None: Scan endet
                       schon kurz nach Installation 1, also VOR dem Paketschritt -> keine Überschneidung)
  orphan               None | 'immediate' (beim Deinstallieren, nur Zeilen der ersten Installation) |
                       'delayed-all' (nach Scan-Ende alle Zeilen des Paketnamens: veröffentlichte -> NULL, ausstehende
                       werden gelöscht) | 'delayed-install1' (nach Scan-Ende nur Zeilen der ersten Installation)
  orphan_delay         ms nach dem Scan-Ende (Standard 4, wie in den fehlgeschlagenen Läufen 3–4 ms)
  uninstall_fail       None | 'test' | 'app' ("adb uninstall" meldet Failure, Paket bleibt) | 'lie-test' | 'lie-app'
                       (meldet Success, Paket bleibt trotzdem installiert)
  uninstall_rc         Rückgabecode von adb bei Failure (alte adb-Versionen: 0)
  owner_log            True: owner_package_changed-Zeilen schreiben
  create_ms            Dauer von create() (insertStart..insertEnd), Standard 5
  recheck_mutation     (seq, 'lost'|'null'|'pending'): 3 s nach Beginn der Nachprüfung (nach den URI-Abfragen) wird die
                       Zeile gelöscht, verwaist oder wieder ausstehend
  proc_events          True: am_kill "finished inst" und am_proc_died nach jeder Instrumentierung (reguläres Ende)
  pkg_step_kill        True: am_kill des App-Prozesses durch die Paketaktion (installPackageLI bzw. deletePackageX)
  crash                None | 'app_fatal' | 'app_kill_lmk' | 'provider_died' | 'oom' | 'anr' (3 s nach Beginn von post)
  leak                 None | Zeichenkette (Fake-Token): erscheint in einer Logcat-Zeile einer fremden App und in der
                       Ausgabe von "adb install" – Prüffall, dass keine Rohinhalte ins Job-Log gelangen
"""
import hashlib
import heapq
import os

PKG = 'de.evp.helfer.channeltest'
TEST_PKG = PKG + '.test'
DIR = '/storage/emulated/0/Download/Video-Assistent/'
SIZE = 262144
MP = (1642, 2289)          # MediaProvider-Prozess/-Thread
T0 = 1763400000000


def sha(seq):
    return hashlib.sha256(b'probe-%d' % seq).hexdigest()


def digest(m):
    text = '\n'.join('%s|%s' % (k, '|'.join(m[k])) for k in sorted(m))
    return hashlib.sha256(text.encode('utf-8')).hexdigest()[:16]


def instr_ok():
    c, t = 'de.evp.helfer.MediaStoreBootScanProbeTest', 'probe'
    head = ['INSTRUMENTATION_STATUS: class=' + c, 'INSTRUMENTATION_STATUS: current=1', 'INSTRUMENTATION_STATUS: id=AndroidJUnitRunner',
            'INSTRUMENTATION_STATUS: numtests=1', 'INSTRUMENTATION_STATUS: stream=', 'INSTRUMENTATION_STATUS: test=' + t, 'INSTRUMENTATION_STATUS_CODE: 1']
    return '\n'.join(head + head[:4] + ['INSTRUMENTATION_STATUS: stream=.', 'INSTRUMENTATION_STATUS: test=' + t, 'INSTRUMENTATION_STATUS_CODE: 0',
                                         'INSTRUMENTATION_RESULT: stream=', '', 'Time: 9.1', '', 'OK (1 test)', '', '', 'INSTRUMENTATION_CODE: -1']) + '\n'


class Device:
    def __init__(self, scenario=None, t0=T0):
        self.sc = {'scan_after_pkg_end': 1500, 'orphan': None, 'orphan_delay': 4, 'uninstall_fail': None, 'uninstall_rc': 0, 'owner_log': True,
                   'create_ms': 5, 'recheck_mutation': None, 'pkg_step_kill': False, 'crash': None,
                   # PKGAB_SIM_NO_PROC_EVENTS=1: ohne reguläre Prozessereignisse (nur zur Nachstellung alter Befunde)
                   'proc_events': os.environ.get('PKGAB_SIM_NO_PROC_EVENTS') != '1'}
        self.sc.update(scenario or {})
        self.clock = t0
        self.log = []
        self.rows = {}           # id -> [owner, pending, trashed, path]
        self.disk = {}           # path -> (size, sha)
        self.next_id = 1000000100
        self.installed = {PKG: False, TEST_PKG: False}
        self.uid, self.pid = 10190, 4300
        self.actions = []        # [(t, nr, name)]
        self.marks = {}
        self.install1_ids = set()
        self.scan_line_t = None
        self.stream = None       # Datei eines laufenden "adb logcat" (nachgebildetes adb)
        self.sampling, self.next_qt, self.qt_buffer = None, None, []   # nebenläufiger Host-Sampler (Token, nächste Zeit)
        self.nr = 0

    # ---------------------------------------------------------------- Grundlagen
    def emit(self, tag, msg, pid=2000, tid=None, t=None):
        t = self.clock if t is None else t
        line = '  %d.%03d  %d  %d I %s: %s' % (t // 1000, t % 1000, pid, tid or pid, tag, msg)
        self.log.append(line)
        if self.stream:
            with open(self.stream, 'a') as f:
                f.write(line + '\n')

    def schedule(self, t, name):
        self.nr += 1
        heapq.heappush(self.actions, (t, self.nr, name))

    def run_until(self, t):
        """Führt fällige Geräteaktionen (Scan, Verwaisen) und – solange der Host-Sampler läuft – dessen Abfragen etwa alle
        0,4 s in zeitlicher Reihenfolge aus und stellt die Uhr auf t (nie rückwärts). Abfragen, die während langer Befehle
        (Installation, Instrumentierung) anfallen, werden gepuffert und mit der nächsten Sampler-Ausgabe geliefert."""
        while True:
            na = self.actions[0][0] if self.actions else None
            ns = self.next_qt if self.sampling else None
            if ns is not None and ns <= t and (na is None or ns < na):
                self.clock = max(self.clock, ns)
                self.qt_buffer.append(self._block(self.sampling, ns, ns + 60))
                self.next_qt = ns + 400
            elif na is not None and na <= t:
                at, _, name = heapq.heappop(self.actions)
                self.clock = max(self.clock, at)
                getattr(self, 'act_' + name)()
            else:
                break
        self.clock = max(self.clock, t)

    def advance(self, ms):
        self.run_until(self.clock + ms)

    # ---------------------------------------------------------------- Geräteaktionen
    def act_scan(self):
        start = self.marks.get('install1-begin', self.clock - 20_000) - 1_000
        self.scan_line_t = self.clock
        self.emit('MediaProvider', 'Scanned external_primary due to REASON_MOUNTED, found 18 items in %dms, 2 inserts 1 updates 0 deletes'
                  % (self.clock - start), *MP)
        if self.sc['orphan'] in ('delayed-all', 'delayed-install1'):
            self.schedule(self.clock + self.sc['orphan_delay'], 'orphan')

    def act_orphan(self):
        scope_all = self.sc['orphan'] == 'delayed-all'
        for i, r in sorted(self.rows.items()):
            if r[0] != PKG or not (scope_all or i in self.install1_ids):
                continue
            if r[1] == '1':
                del self.rows[i]          # ausstehende Zeile wird gelöscht
            else:
                r[0] = 'NULL'
                if self.sc['owner_log']:
                    self.emit('MediaProvider', 'Invalidating LocalCallingIdentity cache for package null. Reason: owner_package_changed:' + r[3], *MP)
            self.clock += 1

    # ---------------------------------------------------------------- Host-Seite (nachgebildete adb-Befehle)
    def marker(self, run, variant, event, extra=''):
        self.advance(20)
        self.emit('BOOTSCAN', ("BOOTSCAN PKG run=%s variant=%s event=%s %s" % (run, variant, event, extra)).rstrip())
        self.marks[event] = self.clock
        if event == 'install1-end' and self.sc.get('leak'):
            self.emit('LeakyApp', 'Authorization: Bearer %s' % self.sc['leak'], 3333, 3333)
        if event in ('install2-end', 'update-end') and self.sc['scan_after_pkg_end'] is not None:
            self.schedule(self.clock + self.sc['scan_after_pkg_end'], 'scan')
        if event == 'install1-end' and self.sc['scan_after_pkg_end'] is None:
            self.schedule(self.clock + 300, 'scan')

    def proc_event(self, tag, pid, process, reason):
        self.emit(tag, '[0,%d,%s,900,%s]' % (pid, process, reason), 1100, 1180)

    def install(self, pkg):
        self.advance(1_200)
        if self.sc['pkg_step_kill'] and pkg == PKG and self.installed[PKG]:
            self.proc_event('am_kill', self.pid, PKG, 'stop %s due to installPackageLI' % PKG)
        self.advance(1_300)
        if not self.installed[pkg] and pkg == PKG:
            self.uid += 1
        self.installed[pkg] = True
        if self.sc.get('leak'):
            return 0, 'Performing Streamed Install (session %s)\nSuccess' % self.sc['leak']
        return 0, 'Performing Streamed Install\nSuccess'

    def uninstall(self, pkg):
        self.advance(700)
        which = 'test' if pkg == TEST_PKG else 'app'
        if self.sc['uninstall_fail'] == which:
            return self.sc['uninstall_rc'], 'Failure [DELETE_FAILED_INTERNAL_ERROR]'
        if self.sc['uninstall_fail'] == 'lie-' + which:
            return 0, 'Success'          # meldet Erfolg, Paket bleibt installiert
        if not self.installed[pkg]:
            return 1, 'Failure [DELETE_FAILED_INTERNAL_ERROR]'
        self.installed[pkg] = False
        if self.sc['pkg_step_kill'] and pkg == PKG:
            self.proc_event('am_kill', self.pid, PKG, 'stop %s due to deletePackageX' % PKG)
        if pkg == PKG and self.sc['orphan'] == 'immediate':
            for i, r in sorted(self.rows.items()):
                if r[0] == PKG and i in self.install1_ids:
                    r[0] = 'NULL'
                    if self.sc['owner_log']:
                        self.emit('MediaProvider', 'Invalidating LocalCallingIdentity cache for package null. Reason: owner_package_changed:' + r[3], *MP)
                    self.clock += 1
        return 0, 'Success'

    def pm_path(self, pkg):
        self.advance(30)
        return 'package:/data/app/~~x/%s-1/base.apk' % pkg if self.installed[pkg] else ''

    def list_packages(self, pkg):
        self.advance(30)
        return '\n'.join('package:%s uid:%d' % (p, self.uid) for p in (PKG, TEST_PKG) if self.installed[p] and pkg in p)

    def start_sampling(self, token):
        if not self.sampling:
            self.sampling, self.next_qt = token, self.clock

    def stop_sampling(self):
        self.sampling = None

    def _block(self, token, a, b):
        rows = [(i, r) for i, r in sorted(self.rows.items()) if token in r[3]]
        out = ['QT-BEGIN t=%d res=ms' % a] + (['No result found.'] if not rows else [])
        out += ['Row: %d _id=%s, owner_package_name=%s, is_pending=%s, is_trashed=%s, _data=%s, _display_name=%s, _size=%d'
                % (n, i, r[0], r[1], r[2], r[3], r[3].rsplit('/', 1)[-1], SIZE) for n, (i, r) in enumerate(rows)]
        return '\n'.join(out + ['QT-END rc=0 t=%d' % b])

    def qt(self, token, tick=True):
        """tick=False (nachgebildetes adb): Host-Abfragen laufen nebenläufig und dürfen die Geräteuhr nicht verschieben,
        sonst hinge der Ablauf von der Anzahl der Abfragen ab; der erste Aufruf startet das nebenläufige Sampling.
        Vor der aktuellen Abfrage werden die gepufferten Abfragen geliefert."""
        if not tick:
            self.start_sampling(token)
        buffered, self.qt_buffer = self.qt_buffer, []
        if tick:
            self.advance(40)
        t = self.clock
        rows = [(i, r) for i, r in sorted(self.rows.items()) if token in r[3]]
        out = buffered + ['QT-BEGIN t=%d res=ms' % t]
        if not rows:
            out.append('No result found.')
        for n, (i, r) in enumerate(rows):
            name = r[3].rsplit('/', 1)[-1]
            out.append('Row: %d _id=%s, owner_package_name=%s, is_pending=%s, is_trashed=%s, _data=%s, _display_name=%s, _size=%d'
                       % (n, i, r[0], r[1], r[2], r[3], name, SIZE))
        if tick:
            self.advance(15)
        out.append('QT-END rc=0 t=%d' % self.clock)
        return '\n'.join(out)

    # ---------------------------------------------------------------- Testprozess (eine Instrumentierung)
    def views(self, token):
        app = {i: (r[0], r[1], r[3].rsplit('/', 1)[-1]) for i, r in self.rows.items() if token in r[3] and r[0] == PKG}
        if self.sc.get('app_owner_lie') and app:   # Prüffall: App-Sicht widerspricht der privilegierten Sicht
            k = min(app)
            app[k] = ('com.example.other',) + app[k][1:]
        priv = {i: tuple(r) for i, r in self.rows.items() if token in r[3]}
        disk = {p: (str(v[0]), v[1]) for p, v in self.disk.items() if token in p}
        return app, priv, disk

    def instrument(self, run, phase, args):
        token = 'BootScanProbe-%s-' % run
        self.advance(300)
        self.pid += 7
        pid = self.pid
        start = self.clock
        L = lambda msg, t=None: self.emit('BOOTSCAN', 'BOOTSCAN ' + msg, pid, pid + 31, t)
        L('START run=%s phase=%s sdk=34 pkg=%s appUid=%d pid=%d t=%d' % (run, phase, PKG, self.uid, pid, start))
        ev = []
        nr = [0]

        def at(t, fn, *a):
            nr[0] += 1
            heapq.heappush(ev, (t, nr[0], fn, a))

        prev = {'app': {}, 'priv': {}, 'disk': {}}
        snaps = [0]

        def sample(final=False):
            snaps[0] += 1
            n, t = snaps[0], self.clock
            app, priv, disk = self.views(token)
            for kind, cur in (('APP', app), ('PRIV', priv)):
                old = prev[kind.lower()]
                for k in sorted(cur):
                    if old.get(k) != cur[k]:
                        L('%s run=%s n=%d +%s|%s' % (kind, run, n, k, '|'.join(cur[k])))
                for k in sorted(old):
                    if k not in cur:
                        L('%s run=%s n=%d -%s' % (kind, run, n, k))
                prev[kind.lower()] = dict(cur)
            self.clock += 3
            L('SNAP run=%s n=%d t=%d tEnd=%d app=ok appN=%d appH=%s priv=ok privN=%d privH=%s cross=ok final=%d'
              % (run, n, t, self.clock, len(app), digest(app), len(priv), digest(priv), 1 if final else 0))
            if final or n % 2 == 1:
                old = prev['disk']
                for k in sorted(disk):
                    if old.get(k) != disk[k]:
                        L('DISK run=%s n=%d +%s|%s' % (run, n, k, '|'.join(disk[k])))
                for k in sorted(old):
                    if k not in disk:
                        L('DISK run=%s n=%d -%s' % (run, n, k))
                prev['disk'] = dict(disk)
                L('DSNAP run=%s n=%d t=%d disk=ok diskN=%d diskH=%s final=%d' % (run, n, self.clock, len(disk), digest(disk), 1 if final else 0))

        state = {'count': 0, 'stop': False, 'last': start}
        seq0 = 20000 if phase == 'pre' else 30000
        maxn = int(args.get('bootscanMax', '120'))
        pace = int(args.get('bootscanPaceMs', '0'))
        after_scan = int(args.get('bootscanAfterScanMs', '5000'))
        offsets = (0, 250) if phase == 'pre' else (0, 250, 2000, 10000)

        def tick():
            if not state['stop']:
                sample()
                at(self.clock + 500, tick)

        def start_dl():
            scan = self.scan_line_t
            scan_at = None if scan is None else max(scan, start)
            if state['count'] >= maxn or (phase == 'post' and scan_at is not None and self.clock > scan_at + after_scan) \
                    or self.clock > start + int(args.get('bootscanSeconds', '120')) * 1000:
                state['stop'] = True
                return
            seq = seq0 + state['count']
            state['count'] += 1
            name = 'BootScanProbe-%s-%d.mp4' % (run, seq)
            path = DIR + name
            rid = str(self.next_id)
            self.next_id += 1
            ins = self.clock
            self.rows[rid] = [PKG, '1', '0', path]
            self.disk[path] = (SIZE, sha(seq))
            if phase == 'pre':
                self.install1_ids.add(rid)
            at(ins + self.sc['create_ms'] + 285, commit, seq, name, rid, ins)

        def commit(seq, name, rid, ins):
            cs = self.clock
            r = self.rows.get(rid)
            ok = r is not None and r[0] == PKG
            if ok:
                r[1] = '0'
            ce = cs + 8
            done = ce + 2
            self.clock = done
            L('DL run=%s phase=%s seq=%d name=%s sha=%s size=%d insertStart=%d insertEnd=%d commitStart=%d commitEnd=%d commit=%s done=%d '
              'state=%s reason=%s uri=%s id=%s' % (run, phase, seq, name, sha(seq), SIZE, ins, ins + self.sc['create_ms'], cs, ce, 'ok' if ok else 'ex:IOException', done,
                                                   'done' if ok else 'failed', '-' if ok else 'storage',
                                                   'content://media/external_primary/downloads/' + rid if ok else '-', rid if ok else '-'))
            if ok:
                for off in offsets:
                    at(done + off, uriq, seq, rid, off)
            at(done + 10 + pace, start_dl)

        def uriq(seq, rid, off):
            r = self.rows.get(rid)
            res = ('found id=%s owner=%s pending=0 data=%s' % (rid, r[0], r[3])) if r and r[0] == PKG and r[1] == '0' else 'none'
            L('URIQ run=%s seq=%d off=%d t=%d res=%s' % (run, seq, off, self.clock, res))

        def mutate():
            seq, kind = self.sc['recheck_mutation']
            for i, r in list(self.rows.items()):
                if r[3].endswith('-%d.mp4' % seq):
                    if kind == 'lost':
                        del self.rows[i]
                    elif kind == 'null':
                        r[0] = 'NULL'
                    else:
                        r[1] = '1'

        def crash():
            kind = self.sc['crash']
            if kind == 'app_fatal':
                self.emit('AndroidRuntime', 'FATAL EXCEPTION: main', pid, pid)
                self.emit('AndroidRuntime', 'Process: %s, PID: %d' % (PKG, pid), pid, pid)
            elif kind == 'app_kill_lmk':
                self.proc_event('am_kill', pid, PKG, 'lmk')
            elif kind == 'provider_died':
                self.proc_event('am_proc_died', 1642, 'com.android.providers.media.module', 'crash')
            elif kind == 'oom':
                self.emit('art', 'Throwing OutOfMemoryError "Failed to allocate"', 1642, 2289)
            elif kind == 'anr':
                self.proc_event('am_anr', 1642, 'com.android.providers.media.module', 'Input dispatching timed out')

        at(start + 1, tick)
        if phase == 'recheck' and self.sc['recheck_mutation']:
            at(start + 3_000, mutate)
        if phase == 'post' and self.sc['crash']:
            at(start + 3_000, crash)
        if phase == 'recheck':
            ids = [p.split(':') for p in args.get('bootscanIds', '-').split(',') if ':' in p]
            for k, (seq, rid) in enumerate(ids):
                at(start + 100 + k, uriq, int(seq), rid, -1)
            at(start + 6_000, lambda: state.update(stop=True))
        else:
            at(start + 200, start_dl)
        while ev:
            t, _, fn, a = heapq.heappop(ev)
            if state['stop'] and fn is tick:
                continue
            if state['stop'] and fn is start_dl:
                continue
            self.run_until(t)
            fn(*a)
        self.advance(50)
        sample(final=True)
        self.advance(5)
        L('END run=%s phase=%s downloads=%d snapshots=%d invalid=0 final=%d t=%d' % (run, phase, state['count'], snaps[0], snaps[0], self.clock))
        if self.sc['proc_events']:   # reguläres Ende der Instrumentierung: ActivityManager beendet den Prozess
            self.advance(30)
            self.proc_event('am_kill', pid, PKG, 'finished inst')
            self.advance(5)
            self.proc_event('am_proc_died', pid, PKG, '0,0')
        return instr_ok()


def simulate(variant, scenario=None, run='a1b2c3d4e5f6', before_recheck=None):
    """Ablauf wie tools/run-pkgremoval-probe.sh, ohne adb. -> dict(logcat, hosts, instr, before, after, run, dev).
    before_recheck(dev): Eingriff in den Gerätezustand vor der Nachprüfung (z. B. Datei verändern)."""
    dev = Device(scenario)
    hosts, instr = [], {}
    token = 'BootScanProbe-%s-' % run

    def op(name, fn, *a):
        dev.marker(run, variant, 'op-begin', 'op=%s attempt=1' % name)
        rc, out = fn(*a)
        dev.marker(run, variant, 'op-end', 'op=%s attempt=1 rc=%d result=%s' % (name, rc, out.splitlines()[-1].replace(' ', '_')))
        return rc, out

    dev.marker(run, variant, 'install1-begin')
    op('install-app', dev.install, PKG)
    op('install-test', dev.install, TEST_PKG)
    dev.marker(run, variant, 'install1-end', 'uid=%d' % dev.uid)
    instr['pre'] = dev.instrument(run, 'pre', {'bootscanMax': '3'})
    dev.start_sampling(token)   # wie das Hostskript: Sampler läuft ab hier bis nach Phase post
    hosts.append(dev.qt(token))
    if variant == 'uninstall':
        dev.marker(run, variant, 'uninstall-begin')
        op('uninstall-test', dev.uninstall, TEST_PKG)
        op('uninstall-app', dev.uninstall, PKG)
        a, b, c = dev.pm_path(PKG), dev.pm_path(TEST_PKG), dev.list_packages(PKG)
        dev.marker(run, variant, 'absent', 'pmApp=%s pmTest=%s listed=%s' % ('none' if not a else 'present', 'none' if not b else 'present',
                                                                             'none' if not c else 'present'))
        dev.marker(run, variant, 'uninstall-end')
        hosts.append(dev.qt(token))
        dev.marker(run, variant, 'install2-begin')
        op('install-app', dev.install, PKG)
        op('install-test', dev.install, TEST_PKG)
        dev.marker(run, variant, 'install2-end', 'uid=%d' % dev.uid)
    else:
        dev.marker(run, variant, 'update-begin')
        op('install-app', dev.install, PKG)
        op('install-test', dev.install, TEST_PKG)
        dev.marker(run, variant, 'update-end', 'uid=%d' % dev.uid)
    hosts.append(dev.qt(token))
    instr['post'] = dev.instrument(run, 'post', {'bootscanMax': '120', 'bootscanPaceMs': '500', 'bootscanAfterScanMs': '8000', 'bootscanSeconds': '120'})
    hosts.append(dev.qt(token))
    dev.stop_sampling()
    ids = ','.join('%s:%s' % (l.split(' seq=')[1].split()[0], l.split(' id=')[-1]) for l in dev.log
                   if ' BOOTSCAN DL run=%s ' % run in l and not l.endswith(' id=-'))
    dev.marker(run, variant, 'sync', 'id=1')   # wie das Hostskript: Abgleich des Logcat-Stroms vor der Nachprüfung
    if before_recheck:
        before_recheck(dev)
    instr['recheck'] = dev.instrument(run, 'recheck', {'bootscanIds': ids or '-'})
    dev.marker(run, variant, 'sync', 'id=2')
    before = {'/storage/emulated/0/Movies/.thumbnails/.database_uuid': ('a' * 64, 36)}
    after = dict(before)
    return {'logcat': '\n'.join(dev.log) + '\n', 'hosts': '\n'.join(hosts) + '\n', 'instr': instr, 'before': before, 'after': after,
            'run': run, 'dev': dev}
