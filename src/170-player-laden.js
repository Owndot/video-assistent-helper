    // Sofortbild: Bis die eigene Kopie das erste Bild hat (neu laden + an die Stelle springen dauert auf langsamen
    // Servern Sekunden), zeigt der Player das aktuelle Bild des Seitenvideos – er wirkt sofort da.
    function showPoster() {
      if (st.posterCv || !(orig.readyState >= 2) || !orig.videoWidth) return;
      try {
        const k = Math.min(1, 1280 / orig.videoWidth), c = document.createElement('canvas');
        c.className = 'vposter';
        c.width = Math.round(orig.videoWidth * k); c.height = Math.round(orig.videoHeight * k);
        c.getContext('2d').drawImage(orig, 0, 0, c.width, c.height);
        vid.after(c);
        st.posterCv = c;
        st.posterTm = setTimeout(dropPoster, 12000);
      } catch (e) {}
    }
    function dropPoster() { clearTimeout(st.posterTm); if (st.posterCv) { st.posterCv.remove(); st.posterCv = null; } }
    // weg, sobald das eigene Video wirklich läuft (an der richtigen Stelle) – oder beim Spiegeln
    vid.addEventListener('playing', () => { if (st.mode === 'own' && (!st.startTime || Math.abs(vid.currentTime - st.startTime) < 3 || vid.currentTime > 0.5)) dropPoster(); });
    st.cleanup.push(dropPoster);
    // ----- Nahtloser Start (auf allen Seiten): Bis die eigene Kopie an derselben Stelle läuft, zeigt der Player das
    // LAUFENDE Bild der Seite (gespiegelt), der Ton kommt so lange weiter von der Seite. Die eigene Kopie läuft stumm
    // dahinter und holt auf – etwas schneller oder langsamer, große Abstände per Sprung. Stimmt es auf unter 0,1 s,
    // wird umgeschaltet: eigener Ton an, Seite still – man merkt nichts. Klappt das nicht (die Seite hält an, du
    // bedienst schon, nach 10 s): sofort umschalten wie früher (Standbild, bis die Kopie läuft).
    // Firefox: das Spiegeln nimmt dort der Seite den Ton – dort bleibt es beim Standbild.
    function startBridge() {
      if (GECKO || orig.paused || orig.ended || !(orig.readyState >= 2) || !orig.captureStream) return false;
      let ms = null;
      try { ms = orig.captureStream(); } catch (e) { return false; }
      if (!ms) return false;
      const el = document.createElement('video');
      el.className = 'vposter vbridge';
      el.muted = true; el.setAttribute('playsinline', '');
      el.srcObject = ms;
      el.play().catch(() => {});
      el.addEventListener('playing', () => { if (!st.openStat.live) st.openStat.live = performance.now(); }, { once: true });
      (st.posterCv || vid).after(el);
      const b = { el: el, ms: ms, at: Date.now(), seeks: 0, seekAt: 0, lead: 0.35, tm: 0, iv: 0 };
      st.bridge = b;
      spin.classList.remove('on');   // das Bild läuft – kein Ladekreis
      vid.muted = true;
      b.tm = setTimeout(() => bridgeEnd('nach 10 s'), 10000);
      b.iv = setInterval(bridgeTick, 50);
      st.openStat.how = 'nahtlos (läuft)';
      return true;
    }
    function bridgeTick() {
      const b = st.bridge;
      if (!b || vp !== st) return;
      if (st.mode !== 'own' || st.handed) { if (st.handed) st.bridgeMuted = true; bridgeDrop(); return; }   // (wird gerade geschlossen: die Seite hat schon übernommen)
      if (orig.paused || orig.ended) { bridgeEnd('Seite hält an'); return; }
      if ((st.userSeekAt || 0) > b.at || (st.userPauseAt || 0) > b.at) { bridgeEnd('du bedienst'); return; }
      if (vid.readyState < 3 || vid.seeking) return;   // eigene Kopie lädt noch
      if (vid.paused) { bridgeEnd('eigene Kopie angehalten'); return; }
      const base = orig.playbackRate || 1;
      const d = orig.currentTime - vid.currentTime;   // > 0: die eigene Kopie ist noch hinten
      if (Math.abs(d) <= 0.08) { bridgeSwap(d); return; }
      if (Math.abs(d) > 1.5) {
        if (b.seeks >= 3) { bridgeEnd('holt nicht auf'); return; }
        b.seeks++; b.seekAt = performance.now(); b.to = orig.currentTime + b.lead * base;
        try { vid.currentTime = b.to; } catch (e) {}   // etwas voraus springen (Springen dauert)
        return;
      }
      vid.playbackRate = base * clamp(1 + d, 0.5, 2);   // aufholen (stumm – die Tonhöhe ist egal)
    }
    vid.addEventListener('seeked', () => {
      const b = st.bridge;
      if (b && b.seekAt) { b.lead = Math.min(3, (performance.now() - b.seekAt) / 1000 + 0.1); b.seekAt = 0; }   // so lange dauert ein Sprung hier
    });
    // Jemand anderes springt im eigenen Video (du, ein Knopf, die Media-Steuerung) – also nicht an die Stelle, die die
    // Brücke selbst gewählt hat? Dann gilt diese Stelle: sofort umschalten
    vid.addEventListener('seeking', () => {
      const b = st.bridge;
      // (vor dem ersten Abspielen springt die Kopie beim Laden selbst an den Anfangspunkt – das zählt nur, wenn es weit weg ist)
      if (!b || (typeof b.to === 'number' && Math.abs(vid.currentTime - b.to) < 0.5)) return;
      if (st.openStat.own || Math.abs(vid.currentTime - orig.currentTime) > 3) bridgeEnd('gespult');
    });
    vid.addEventListener('playing', () => { if (st.mode === 'own' && !st.openStat.own) st.openStat.own = performance.now(); });
    function bridgeSwap(d) {
      if (st.handed) { st.bridgeMuted = true; bridgeDrop(); return; }
      vid.playbackRate = st.speed;
      vid.muted = false;
      try { orig.muted = true; orig.pause(); } catch (e) {}
      st.openStat.swap = performance.now(); st.openStat.gap = d; st.openStat.how = 'nahtlos';
      bridgeDrop();
      dropPoster();
    }
    // sofort umschalten (wie früher): Standbild an der aktuellen Stelle, bis die eigene Kopie dort läuft
    function bridgeEnd(why) {
      const b = st.bridge;
      if (!b) return;
      if (st.handed) { st.bridgeMuted = true; bridgeDrop(); return; }
      const t = orig.currentTime;
      if (st.mode === 'own') {
        const moved = why === 'gespult' || (st.userSeekAt || 0) > b.at;   // du hast schon eine Stelle gewählt – die bleibt
        dropPoster(); if (!moved) showPoster();   // (Standbild der Seite nur, wenn es auch die Stelle ist, an der es weitergeht)
        try { orig.muted = true; orig.pause(); } catch (e) {}
        vid.muted = false; vid.playbackRate = st.speed;
        st.startTime = moved ? vid.currentTime : t;
        if (!moved && vid.readyState >= 1 && Math.abs(vid.currentTime - t) > 0.3) { try { vid.currentTime = t; } catch (e) {} }
        if (vid.readyState >= 3 && !vid.paused) dropPoster(); else spin.classList.add('on');   // (jetzt wartet man wirklich auf die Kopie)
      }
      st.openStat.how = 'umgeschaltet (' + why + ')'; st.openStat.swap = performance.now();
      bridgeDrop();
    }
    function bridgeDrop() {
      const b = st.bridge;
      if (!b) return;
      st.bridge = null;
      clearTimeout(b.tm); clearInterval(b.iv);
      try { b.el.srcObject = null; b.el.remove(); } catch (e) {}
      try { b.ms.getTracks().forEach((tr) => tr.stop()); } catch (e) {}
    }
    st.cleanup.push(bridgeDrop);
    // ----- Laden: Quellen der Reihe nach, zuletzt Bild vom Original übernehmen -----
    function tryNext() {
      clearTimeout(st.loadTimer);
      if (st.hls) { try { st.hls.destroy(); } catch (e) {} st.hls = null; }
      if (st.idx >= st.sources.length) { useCapture(); return; }
      const u = st.sources[st.idx++];
      st.cur = u;
      st.mode = 'own';
      if (st.setPipAllowed) st.setPipAllowed(true);
      kadriShield(false);
      if (!st.bridge) spin.classList.add('on');   // (nahtloser Start: das Bild läuft ja schon – kein Ladekreis)
      st.loadTimer = setTimeout(tryNext, 9000);   // lädt nicht? nächste Quelle
      try { vid.srcObject = null; vid.removeAttribute('src'); } catch (e) {}
      if (PLAYLIST_RE.test(u)) {
        // Kadri lädt hls.js erst jetzt nach (spart auf jeder Seite ~620 KB) – danach dieselbe Quelle noch einmal
        if (typeof Hls === 'undefined' && IS_KADRI && typeof KADRI_HOOKS !== 'undefined' && KADRI_HOOKS.loadHls && !st.hlsTried) {
          st.hlsTried = true; st.idx--;
          const again = () => { if (vp === st && st.cur === u && st.mode === 'own') tryNext(); };
          KADRI_HOOKS.loadHls().then(again, again);
          return;
        }
        if (typeof Hls !== 'undefined' && Hls.isSupported()) {
          // Untertitel zeichnet der Player selbst (⋯ → Untertitel) – hls.js lädt sie nur
          const hls = new Hls({ enableWorker: false, subtitleDisplay: false, startPosition: st.startTime > 1 ? st.startTime : -1 });
          st.hls = hls;
          hls.on(Hls.Events.ERROR, (ev, data) => { if (data && data.fatal) tryNext(); });
          hls.on(Hls.Events.MANIFEST_PARSED, () => {
            try { if (hls.levels && hls.levels.length) hls.startLevel = hls.levels.length - 1; } catch (e) {}   // beste Qualität zuerst
            vid.play().catch(() => {});
            refreshQual();
          });
          hls.on(Hls.Events.LEVEL_SWITCHED, () => refreshQual());
          hls.loadSource(u);
          hls.attachMedia(vid);
          return;
        }
        if (vid.canPlayType('application/vnd.apple.mpegurl')) { vid.src = u; vid.play().catch(() => {}); return; }
        tryNext();
        return;
      }
      vid.src = u;
      vid.play().catch(() => {});
    }
    vid.addEventListener('loadedmetadata', () => {
      if (st.mode !== 'own') return;
      clearTimeout(st.loadTimer);
      // Nach einem Quellwechsel: andere Länge = anderes Video (z. B. ein vorgeladenes Reel) – sofort zurück
      if (st.expectDur && isFinite(vid.duration) && Math.abs(vid.duration - st.expectDur) > 3 && st.prevCur && st.prevCur !== st.cur) {
        const bad = st.cur, back = st.prevCur;
        st.expectDur = 0; st.prevCur = '';
        st.sources = [back].concat(st.sources.filter((x) => x !== bad && x !== back));
        delete st.srcRes[bad];
        st.idx = 0;
        tryNext();
        showInd('Diese Qualität gehörte zu einem anderen Video');
        return;
      }
      st.expectDur = 0;
      // Gegenprobe beim Öffnen: Ist das Video gleich lang wie das auf der Seite? Sonst ist es ein anderes (z. B. das
      // vorige nach einem schnellen Wechsel) → gleich die nächste Quelle. Passt keine, zurück zur ersten (z. B. wenn die
      // Seite gerade Werbung zeigt). Nur einmal pro Öffnen, nie bei Live.
      if (!st.lenOk) {
        const od = orig.duration, vd = vid.duration;
        if (isFinite(od) && od > 1 && isFinite(vd) && vd > 1 && Math.abs(od - vd) > lenTol(od)) {
          if (st.capFallback) { st.lenOk = true; (st.fbBad || (st.fbBad = [])).push(st.cur); useCapture(); return; }   // Facebook: falsche Datei → spiegeln (und nicht als Vorschau)
          if (!st.lenFirst) st.lenFirst = st.cur;
          if (st.idx < st.sources.length) { tryNext(); return; }
          st.lenOk = true;
          if (st.lenFirst !== st.cur) { st.sources = [st.lenFirst].concat(st.sources.filter((x) => x !== st.lenFirst)); st.idx = 0; tryNext(); return; }
        }
        st.lenOk = true;
      }
      // (nahtloser Start: gleich an die Stelle, an der die Seite JETZT ist – etwas voraus, Springen dauert)
      const t0 = st.bridge ? orig.currentTime + 0.3 : st.startTime;
      if (!st.hls && t0 > 1 && isFinite(vid.duration) && vid.duration > t0) { if (st.bridge) st.bridge.to = t0; try { vid.currentTime = t0; } catch (e) {} }
      vid.playbackRate = st.speed;
      autoOrient(vid.videoWidth, vid.videoHeight);
      refreshQual();
      layoutVideo();
      if (st.refreshDl) st.refreshDl();
      if (st.prefetchExt) st.prefetchExt();
      if (st.prevWarm) setTimeout(() => { if (vp === st) st.prevWarm(); }, 1500);
      vid.play().catch(() => {});
    });
    vid.addEventListener('error', () => { if (st.mode === 'own' && vp === st) tryNext(); });

    function useCapture() {
      clearTimeout(st.loadTimer);
      bridgeDrop();
      dropPoster();
      if (!st.openStat.swap) st.openStat.how = 'gespiegelt';
      let ms = null;
      try { ms = orig.captureStream ? orig.captureStream() : (orig.mozCaptureStream ? orig.mozCaptureStream() : null); } catch (e) {}
      if (!ms) { toast('Dieses Video lässt sich hier nicht im eigenen Player zeigen'); closePlayer(); return; }
      st.mode = 'capture';
      st.cur = '';
      // gespiegelt: Bild-in-Bild nur in Kadri mit Schild (sonst hielte die Seite ihr Video im Hintergrund an)
      kadriShield(true);
      if (st.setPipAllowed) st.setPipAllowed(kadriPage());
      if (st.refreshDl) st.refreshDl();
      try { vid.removeAttribute('src'); } catch (e) {}
      vid.srcObject = ms;
      vid.muted = !FF_CAP;   // Chrome: der Ton kommt vom Original-Video · Firefox: aus der Spiegelung
      vid.play().catch(() => {});
      orig.playbackRate = st.speed;
      try { orig.muted = st.origMuted; } catch (e) {}   // hier kommt der Ton vom Original – also Ton wieder an
      if (orig.paused) orig.play().catch(() => {});
      spin.classList.remove('on');
      autoOrient(orig.videoWidth, orig.videoHeight);
      updateUI();
      watchMirror(ms);
      if (st.prevWarm) setTimeout(() => { if (vp === st && st.mode === 'capture') st.prevWarm(); }, 1500);
    }
    // Spiegeln am Leben halten: Facebook/Instagram laden ein Reel am Ende jeder Schleife NEU ins Videoelement.
    // Dabei reißt die Bildübertragung ab (Ton läuft weiter, Bild schwarz) – dann sofort neu verbinden.
    // Firefox: dasselbe Videoelement zeigt neue Spuren eines Stroms nicht an – dort jedes Neuladen nachholen
    let mirrorAt = 0, mirrorWatch = 0, mutedSince = 0, mirrorLater = 0;
    // Firefox liefert für ein angehaltenes, gerade neu geladenes Original kein Bild (schwarz) – das letzte Bild als
    // Standbild darüberlegen, bis wieder echte Bilder ankommen (Chrome zeigt es von selbst weiter)
    function holdFrame() {
      if (!GECKO || vid.readyState < 2 || !vid.videoWidth) return;
      try {
        if (!st.holdCv) { st.holdCv = document.createElement('canvas'); st.holdCv.className = 'vv vhold'; st.holdCv.style.pointerEvents = 'none'; }
        st.holdCv.width = vid.videoWidth; st.holdCv.height = vid.videoHeight;
        st.holdCv.getContext('2d').drawImage(vid, 0, 0);
        st.holdCv.style.cssText = vid.style.cssText + ';pointer-events:none';
        if (st.holdCv.previousSibling !== vid) vid.after(st.holdCv);
      } catch (e) {}
    }
    function dropHold() { if (st.holdCv && st.holdCv.isConnected) st.holdCv.remove(); }
    function rebind(ms) {
      holdFrame();
      st.mirrorFix = (st.mirrorFix || 0) + 1;
      st.mirrorWhy = 'neu zugewiesen';
      wrap.dataset.mirror = st.mirrorFix + ' ' + st.mirrorWhy;
      vid.srcObject = null;
      vid.srcObject = ms;
      vid.play().catch(() => {});
      lastFrame = mirrorAt = Date.now();
      frameLoop();
    }
    function recapture(why) {
      if (vp !== st || st.mode !== 'capture') return;
      const now = Date.now();
      if (now - mirrorAt < 400) {   // nicht im Kreis neu verbinden
        if (GECKO && !mirrorLater) mirrorLater = setTimeout(() => { mirrorLater = 0; recapture(why); }, 410 - (now - mirrorAt));
        return;
      }
      mirrorAt = now;
      let ms2 = null;
      try { ms2 = orig.captureStream ? orig.captureStream() : (orig.mozCaptureStream ? orig.mozCaptureStream() : null); } catch (e) {}
      if (!ms2) return;
      st.mirrorFix = (st.mirrorFix || 0) + 1;
      st.mirrorWhy = why;
      wrap.dataset.mirror = st.mirrorFix + ' ' + why;   // (nur zur Fehlersuche sichtbar)
      if (GECKO) { holdFrame(); vid.srcObject = null; }
      vid.srcObject = ms2;
      vid.muted = !FF_CAP;
      vid.play().catch(() => {});
      lastFrame = now;
      frameLoop();
      watchMirror(ms2);
    }
    // Wann kam hier zuletzt wirklich ein Bild an? (zuverlässigster Hinweis auf "Ton läuft, Bild schwarz")
    let lastFrame = Date.now(), frameLoopId = 0, lastOrigT = -1;
    function frameLoop() {
      if (!vid.requestVideoFrameCallback) return;
      const id = ++frameLoopId;
      const cb = () => { if (id !== frameLoopId || vp !== st) return; lastFrame = Date.now(); if (st.holdCv) dropHold(); vid.requestVideoFrameCallback(cb); };
      vid.requestVideoFrameCallback(cb);
    }
    function liveVideo() {
      const so = vid.srcObject;
      const tr = so && so.getVideoTracks ? so.getVideoTracks() : [];
      return tr.filter((t) => t.readyState === 'live');
    }
    function watchMirror(ms) {
      try {
        const cur = () => vid.srcObject === ms;   // Meldungen älterer Ströme zählen nicht mehr
        if (GECKO) {
          // Firefox: der Strom bekommt nach dem Neuladen selbst neue Spuren, nur das Videoelement zeigt sie erst nach
          // erneutem Zuweisen (ein frisch abgegriffener Strom bleibt dort dagegen oft stumm)
          let rb = 0;
          ms.addEventListener('addtrack', (e) => {
            if (!e.track || e.track.kind !== 'video') return;
            clearTimeout(rb);
            rb = setTimeout(() => { if (cur() && vp === st && st.mode === 'capture') rebind(ms); }, 40);
          });
        } else {
          // neue Spur: nur neu verbinden, wenn gerade wirklich kein Bild ankommt (sonst unnötiges Flackern)
          ms.addEventListener('addtrack', () => setTimeout(() => { if (cur() && Date.now() - lastFrame > 600) recapture('neue Spur'); }, 300));
          ms.addEventListener('removetrack', () => setTimeout(() => { if (cur() && !liveVideo().length) recapture('Spur entfernt'); }, 30));
          ms.getVideoTracks().forEach((t) => t.addEventListener('ended', () => setTimeout(() => { if (cur()) recapture('Spur beendet'); }, 30)));
        }
      } catch (e) {}
      if (mirrorWatch) return;
      frameLoop();
      // Neues Video im Original (Schleife, nächstes Reel): neu verbinden, sobald es wieder Bild hat
      const onSrc = () => { if (!GECKO) setTimeout(() => recapture('Original neu geladen'), 60); };
      ['loadedmetadata', 'loadeddata'].forEach((t) => orig.addEventListener(t, onSrc));
      // Wächter: Das Original läuft WIRKLICH weiter (Zeit rückt vor), aber hier kommt seit 1 s kein Bild an
      // – oder die Spur ist weg / stumm? Dann neu verbinden.
      mirrorWatch = setInterval(() => {
        if (vp !== st || st.mode !== 'capture' || document.hidden) { mutedSince = 0; return; }
        const ot = orig.currentTime, moving = !orig.paused && lastOrigT >= 0 && ot !== lastOrigT;
        lastOrigT = ot;
        if (!moving) { mutedSince = 0; lastFrame = Math.max(lastFrame, Date.now() - 300); return; }
        const live = liveVideo();
        if (!live.length) { recapture('kein Bild'); return; }
        if (vid.requestVideoFrameCallback && Date.now() - lastFrame > 1000) { recapture('Bild steht'); return; }
        if (live.every((t) => t.muted)) {
          if (!mutedSince) mutedSince = Date.now();
          else if (Date.now() - mutedSince > 1200) { mutedSince = 0; recapture('Bild stumm'); }
        } else mutedSince = 0;
      }, 500);
      st.cleanup.push(() => {
        clearInterval(mirrorWatch);
        clearTimeout(mirrorLater);
        dropHold();
        frameLoopId++;
        ['loadedmetadata', 'loadeddata'].forEach((t) => orig.removeEventListener(t, onSrc));
      });
    }

