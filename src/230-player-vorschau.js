    // ----- Vorschaubild beim Spulen auf der Leiste -----
    // Ein zweites, stummes Video (in der kleinsten verfügbaren Qualität) springt an die Fingerstelle.
    // Schnell durch: 1) jedes Vorschaubild wird gemerkt, 2) ein Raster von ~30 Bildern wird im Hintergrund vorgeholt
    // (Streams gleich beim Öffnen – billig in kleinster Stufe; große Dateien erst beim ersten Berühren der Leiste),
    // 3) beim Ziehen erscheint sofort das nächstgelegene gemerkte Bild, die genaue Stelle wird danach nachgeschärft.
    const pv = document.createElement('video');
    pv.className = 'vpv'; pv.muted = true; pv.preload = 'auto';
    pv.setAttribute('playsinline', ''); pv.setAttribute('webkit-playsinline', ''); pv.disablePictureInPicture = true;
    const pvc = document.createElement('canvas');
    pvc.className = 'vpc'; pvc.width = 192; pvc.height = 108;
    let pvcx = null;
    try { pvcx = pvc.getContext('2d'); } catch (e) {}
    const pvBox = h('div', { class: 'vpbox g' }, pv, pvc);
    const pvTime = h('b', null, '0:00');
    const prev = h('div', { class: 'vprev', 'aria-hidden': 'true' }, pvBox, pvTime);
    wrap.append(prev);
    const pvs = { key: '', src: '', hls: null, want: -1, lastWant: -1, busy: false, kind: '', grid: [], gridOn: false, autoGrid: false, userDone: false };
    const thumbs = new Map();   // Zeit-Fach -> { c: kleines Bild, t: genaue Zeit }
    let bucket = 0;             // Fachbreite in Sekunden (≈ Länge / 300), einmal pro Video festgelegt
    const bucketFor = () => {
      if (!bucket) { const d = isFinite(M().duration) && M().duration > 0 ? M().duration : pv.duration; if (isFinite(d) && d > 0) bucket = Math.max(0.5, d / 300); }
      return bucket || 1;
    };
    function prevReset() {
      if (pvs.hls) { try { pvs.hls.destroy(); } catch (e) {} }
      pvs.hls = null; pvs.src = ''; pvs.key = ''; pvs.want = -1; pvs.lastWant = -1; pvs.busy = false; pvs.grid = []; pvs.gridOn = false; pvs.userDone = false;
      clearInterval(pvs.wd); clearTimeout(pvs.settle); pvs.target = -1; pvs.retried = -1;
      // gemerkte Bilder gehören zum Video, nicht zur Quelle der Vorschau – sie bleiben (auch bei anderer Qualität)
      prev.classList.remove('img', 'live');
      try { pv.removeAttribute('src'); pv.load(); } catch (e) {}
    }
    st.cleanup.push(prevReset);
    st.cleanup.push(() => { thumbs.clear(); bucket = 0; });
    // Kleinste Qualität desselben Videos (lädt viel schneller); Streams nutzen ohnehin die kleinste Stufe
    function previewSrc() {
      if (st.hls) return st.cur;
      const curRes = st.srcRes[st.cur] || 0;
      const low = st.sources.filter((u) => (st.srcRes[u] || 0) >= 144).sort((x, y) => st.srcRes[x] - st.srcRes[y])[0];
      return low && curRes && st.srcRes[low] < curRes ? low : st.cur;
    }
    function prevLoad() {
      const key = st.mode === 'own' ? st.cur : '';
      if (!key) { prev.classList.add('noimg'); return false; }   // gespiegelt (Facebook …): keine eigene Quelle → nur die Zeit
      prev.classList.remove('noimg');
      if (pvs.key === key) return true;
      prevReset();
      pvs.key = key;
      const u = previewSrc();
      pvs.src = u;
      if (st.hls && typeof Hls !== 'undefined') {
        const hp = new Hls({ enableWorker: false, startLevel: 0, maxBufferLength: 2, maxMaxBufferLength: 4, backBufferLength: 0 });
        pvs.hls = hp;
        hp.on(Hls.Events.MANIFEST_PARSED, () => { try { hp.currentLevel = 0; hp.autoLevelEnabled = false; } catch (e) {} });   // kleinste Stufe
        hp.on(Hls.Events.ERROR, (ev, d) => { if (d && d.fatal) prev.classList.add('noimg'); });
        hp.loadSource(u);
        hp.attachMedia(pv);
      } else {
        pv.src = u;
      }
      return true;
    }
    function thumbDims(v) {
      const vw = v.videoWidth || 16, vh = v.videoHeight || 9;
      return [176, Math.max(40, Math.min(320, Math.round(176 * vh / vw)))];
    }
    // aktuelles Bild eines Videos merken (Vorschau-Video oder – kostenlos – das laufende Video selbst)
    function snap(v) {
      v = v || pv;
      if (!(v.videoWidth > 0)) return null;
      const tt = v.currentTime;
      const d = thumbDims(v);
      const c = document.createElement('canvas');
      c.width = d[0]; c.height = d[1];
      try { c.getContext('2d').drawImage(v, 0, 0, d[0], d[1]); } catch (e) { return null; }
      const k = Math.round(tt / bucketFor());
      thumbs.delete(k); thumbs.set(k, { c: c, t: tt });
      if (thumbs.size > 180) thumbs.delete(thumbs.keys().next().value);   // ~12 MB höchstens
      return c;
    }
    // Beim Anschauen nebenbei Bilder merken (je Fach eins, höchstens jede Sekunde): Gesehenes erscheint beim Spulen sofort
    let mainSnapAt = 0;
    const mainSnap = () => {
      if (FF_MOBILE || st.mode !== 'own' || vid.readyState < 2 || vid.seeking || prev.classList.contains('on')) return;
      const now = Date.now();
      if (now - mainSnapAt < 1000 || thumbs.has(Math.round(vid.currentTime / bucketFor()))) return;
      mainSnapAt = now;
      // erst, wenn der Browser gerade nichts zu tun hat (kein Ruckeln mitten in einer Bewegung)
      if (window.requestIdleCallback) requestIdleCallback(() => { if (vp === st && !vid.seeking) snap(vid); }, { timeout: 1500 });
      else snap(vid);
    };
    vid.addEventListener('timeupdate', mainSnap);
    vid.addEventListener('seeked', () => setTimeout(mainSnap, 120));
    // Zeigen, wenn es näher an der Fingerstelle liegt als das bisher gezeigte Bild (oder noch keins da ist)
    function better(t) {
      if (!prev.classList.contains('img') || pvs.shownT == null) return true;
      return Math.abs(t - pvs.lastWant) < Math.abs(pvs.shownT - pvs.lastWant);
    }
    function paint(c, t) {
      if (!pvcx || !c) return;
      pvs.shownT = t;
      if (pvc.width !== c.width || pvc.height !== c.height) { pvc.width = c.width; pvc.height = c.height; }
      try { pvcx.drawImage(c, 0, 0); } catch (e) { return; }
      prev.dataset.t = String(Math.round(t * 10) / 10);
      prev.classList.add('img');
    }
    function nearest(t) {
      const k = Math.round(t / bucketFor());
      for (let i = 0; i <= 8; i++) {
        if (thumbs.has(k + i)) { const x = thumbs.get(k + i); return [x.c, x.t]; }
        if (i && thumbs.has(k - i)) { const x = thumbs.get(k - i); return [x.c, x.t]; }
      }
      return null;
    }
    // Einen Sprung des Vorschau-Videos starten (bricht einen laufenden ab – der Browser meldet nur den letzten)
    function seekTo(t, kind) {
      pvs.busy = true; pvs.kind = kind; pvs.target = t; pvs.since = Date.now();
      try { pv.currentTime = t; } catch (e) { pvs.busy = false; return; }
      // Wächter: Ist der Sprung fertig, aber die Meldung („seeked“) kam nicht an (kommt bei Streams vor), selbst
      // weitermachen. Ein langsamer Sprung (schwaches Netz, große Datei) wird NICHT neu gestartet – das würde ihn nie
      // fertig werden lassen; erst nach 6 s ein einziger neuer Versuch.
      clearInterval(pvs.wd);
      pvs.wd = setInterval(() => {
        if (!pvs.busy) { clearInterval(pvs.wd); return; }
        if (!pv.seeking && pv.readyState >= 2 && Date.now() - pvs.since > 400) { clearInterval(pvs.wd); onSeeked(); return; }
        if (Date.now() - pvs.since > 6000) {
          clearInterval(pvs.wd); pvs.busy = false;
          if (pvs.retried !== pvs.target && pvs.kind === 'user' && prev.classList.contains('on')) { pvs.retried = pvs.target; pvs.want = pvs.target; }
          pump();
        }
      }, 400);
    }
    // Reihenfolge: zuerst die Fingerstelle, in Pausen das Raster – beim Spulen das Rasterbild nahe am Finger zuerst
    function pump() {
      if (pvs.busy || pv.readyState < 1) return;
      let t = -1, kind = '';
      if (pvs.want >= 0) { t = pvs.want; kind = 'user'; pvs.want = -1; }
      else {
        if (prev.classList.contains('on') && pvs.lastWant >= 0 && pvs.grid.length > 1) {
          pvs.grid.sort((a, b) => Math.abs(a - pvs.lastWant) - Math.abs(b - pvs.lastWant));
        }
        while (pvs.grid.length && t < 0) { const g = pvs.grid.shift(); if (!thumbs.has(Math.round(g / bucketFor()))) { t = g; kind = 'grid'; } }
      }
      if (t < 0) return;
      seekTo(t, kind);
    }
    function startGrid() {
      if (FF_MOBILE) return;   // dort keine gemerkten Bilder (lassen sich nicht kopieren) – das Video selbst zeigt die Stelle
      const d = pv.duration;
      // Beim ersten Ziehen hat deine Fingerstelle Vorrang – das Raster startet erst, wenn ihr Bild da ist
      if (prev.classList.contains('on') && !pvs.userDone) { pvs.autoGrid = true; return; }
      if (pvs.gridOn || !isFinite(d) || d <= 0) return;
      pvs.gridOn = true;
      const N = 30, order = [];
      for (let i = 0; i < N; i++) order.push(((i * 7) % N + 0.5) * d / N);   // gestreut statt der Reihe nach
      pvs.grid = order;
      pump();
    }
    function prevSeek(t) {
      pvs.want = t; pvs.lastWant = t;
      // Finger hat Vorrang: ein Rasterbild, das gerade lädt, wird abgebrochen (kommt später wieder dran)
      if (pvs.busy && pvs.kind === 'grid') { pvs.grid.push(pvs.target); pvs.want = -1; seekTo(t, 'user'); return; }
      pump();
      // Finger steht still, aber das Vorschau-Video lädt noch eine alte Stelle → gleich zur neuen springen
      clearTimeout(pvs.settle);
      pvs.settle = setTimeout(() => {
        if (pvs.busy && pvs.want >= 0 && Math.abs(pvs.target - pvs.want) > bucketFor() * 1.5) { const w = pvs.want; pvs.want = -1; seekTo(w, 'user'); }
      }, 160);
    }
    pv.addEventListener('loadedmetadata', () => {
      pvs.busy = false;
      // Kleinere Qualität gewählt, aber andere Länge? Dann lieber die laufende Quelle nehmen
      const md = M().duration;
      if (pvs.src !== st.cur && isFinite(md) && isFinite(pv.duration) && Math.abs(pv.duration - md) > 3) {
        const key = pvs.key; pvs.src = st.cur; pv.src = st.cur; pvs.key = key; return;
      }
      bucketFor();
      if (pvs.autoGrid) startGrid();
      pump();
    });
    pv.addEventListener('seeked', () => { if (pvs.busy) onSeeked(); });
    function onSeeked() {
      pvs.busy = false; clearInterval(pvs.wd);
      if (FF_MOBILE) {
        // Vorschau-Video direkt zeigen (statt eines kopierten Bildes)
        if (prev.classList.contains('on')) { prev.dataset.t = String(Math.round(pv.currentTime * 10) / 10); prev.classList.add('img', 'live'); }
        pump();
        return;
      }
      if (pvs.kind === 'user' && !pvs.userDone) { pvs.userDone = true; if (pvs.autoGrid) setTimeout(startGrid, 0); }
      const c = snap();
      if (c && prev.classList.contains('on')) {
        // neues Bild oder ein gemerktes – je nachdem, was näher an der Fingerstelle liegt
        const n = nearest(pvs.lastWant);
        if (n && Math.abs(n[1] - pvs.lastWant) < Math.abs(pv.currentTime - pvs.lastWant)) { if (better(n[1])) paint(n[0], n[1]); }
        else if (better(pv.currentTime)) paint(c, pv.currentTime);
      }
      pump();
    }
    pv.addEventListener('error', () => { prev.classList.add('noimg'); });
    // Streams: Raster gleich nach dem Start vorholen (kleinste Stufe, wenig Daten)
    // Vorwärmen nach dem Start: Streams holen gleich das Raster (kleinste Stufe, wenig Daten),
    // Dateien nur ihr Inhaltsverzeichnis (wenige KB) – dann wartet auch das erste Ziehen nicht darauf
    st.prevWarm = () => {
      if (st.mode !== 'own' || !st.cur) return;
      if (st.hls) { pvs.autoGrid = true; pvs.userDone = true; prevLoad(); if (pv.readyState >= 1) startGrid(); }
      else { pv.preload = 'metadata'; prevLoad(); }
    };
    function prevShow(p) {
      const d = M().duration;
      if (!isFinite(d) || d <= 0) return;
      const t = p * d;
      pvTime.textContent = fmtTime(t);
      const img = prevLoad() && !prev.classList.contains('noimg');
      if (img) {
        pvs.lastWant = t;
        const n = nearest(t);
        if (n && better(n[1])) paint(n[0], n[1]);   // sofort das nächstgelegene gemerkte Bild …
        prevSeek(t);                                // … und die genaue Stelle nachschärfen
        if (pv.readyState >= 1) startGrid(); else pvs.autoGrid = true;
      }
      const vw = vid.videoWidth || 16, vh = vid.videoHeight || 9;
      let w = innerWidth > innerHeight ? 184 : 164, hh = w * vh / vw;
      const maxH = innerWidth > innerHeight ? 110 : 150;
      if (hh > maxH) { hh = maxH; w = hh * vw / vh; }   // Hochkant-Videos nicht zu groß
      pvBox.style.width = Math.round(w) + 'px'; pvBox.style.height = Math.round(hh) + 'px';
      const br = bar.getBoundingClientRect(), wr = wrap.getBoundingClientRect();
      const pw = img ? w : 64;
      const x = br.left - wr.left + 7 + p * (br.width - 14);   // Mitte des Punkts
      prev.style.width = Math.round(pw) + 'px';
      prev.style.left = Math.round(clamp(x - pw / 2, 8, wr.width - pw - 8)) + 'px';
      prev.style.bottom = Math.round(wr.bottom - br.top + 6) + 'px';
      prev.classList.add('on');
      wrap.classList.add('scrub');   // Knöpfe darüber kurz ausblenden, damit die Vorschau frei steht
    }
    function prevHide() { prev.classList.remove('on'); wrap.classList.remove('scrub'); }

