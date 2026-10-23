    // ----- Steuerleisten ein-/ausblenden -----
    function scheduleHide() {
      clearTimeout(st.hideTimer);
      st.hideTimer = setTimeout(() => { if (!M().paused && !st.barDrag) wrap.classList.remove('ctl'); }, 3200);
    }
    function showControls() { const was = wrap.classList.contains('ctl'); wrap.classList.add('ctl'); scheduleHide(); if (!was) updateUI(); }
    function toggleControls() { if (wrap.classList.contains('ctl')) { wrap.classList.remove('ctl'); clearTimeout(st.hideTimer); } else showControls(); }

    // Fortschritt (weiß) + schon geladener Bereich (halbhell) + Rest (dunkel)
    function paintBar(p) {
      const m = M();
      let b = p;
      try {
        const br = m.buffered, c = m.currentTime, d = m.duration;
        for (let i = 0; i < br.length; i++) {
          if (br.start(i) <= c + 0.5 && br.end(i) >= c) { b = isFinite(d) && d > 0 ? br.end(i) / d : p; break; }
        }
      } catch (e) {}
      if (p >= 0.999) b = 1;
      const P1 = (p * 100).toFixed(2), B1 = (Math.max(p, b) * 100).toFixed(2);
      bar.style.setProperty('--trk', 'linear-gradient(to right,#000 ' + P1 + '%,rgba(0,0,0,.42) ' + P1 + '%,rgba(0,0,0,.42) ' + B1 + '%,rgba(0,0,0,.18) ' + B1 + '%)'   /* umgedreht: wird per invert(1) weiß */);
    }

    // ----- Anzeige aktualisieren -----
    // Nur schreiben, was sich geändert hat – und Zeitleiste/Zeiten nur, solange sie zu sehen sind (beim Einblenden
    // aktualisiert showControls sofort). Spart beim normalen Schauen ständige Stil-Neuberechnungen (17.7).
    const uiLast = { cur: '', dur: '', play: null, len: null, mini: '' };
    // Am Ende angehalten (Reels: kurz VOR dem Ende, damit die Seite nicht zum nächsten springt) – aber nur, solange es
    // noch dort steht (nicht nach Zurückspulen)
    function heldAtEnd(m) {
      m = m || M();
      const d = m.duration;
      return !!st.endHold && m.paused && isFinite(d) && d > 0 && m.currentTime >= d - 1.5;
    }
    st.heldAtEnd = heldAtEnd;
    // Spulbereich: Datei/Video = 0 … Länge. Live-Stream mit Zurückspulen (z. B. ARD live) = der Bereich, den der Stream
    // zurückhält (ab 1 min; rechts ist „live“). Sonst (reines Live) kein Spulen.
    // (hls.js gibt Live-Streams eine endliche, wachsende Länge – dort fragen, ob die Liste „live“ ist)
    const hlsLive = () => {
      try { const h = st.mode === 'own' && st.hls; if (!h) return false; const dt = h.latestLevelDetails || ((h.levels || [])[h.currentLevel] || {}).details; return !!(dt && dt.live); } catch (e) { return false; }
    };
    function seekRange(m) {
      m = m || M();
      const d = m.duration;
      if (isFinite(d) && d > 0 && !(m === vid && hlsLive())) return { a: 0, b: d, len: d, live: false };
      try {
        const s = m.seekable;
        if (s && s.length) {
          const a = s.start(0), b = s.end(s.length - 1);
          if (isFinite(a) && isFinite(b) && b - a >= 60) return { a: a, b: b, len: b - a, live: true };
        }
      } catch (e) {}
      return null;
    }
    // Live-Rand: wo „live“ ist – hls.js spielt Live bewusst ein paar Stücke hinter dem Ende der Liste (liveSyncPosition)
    const liveEdge = (r) => { try { const p = st.mode === 'own' && st.hls && st.hls.liveSyncPosition; if (isFinite(p) && p > r.a) return Math.min(r.b, p); } catch (e) {} return r.b; };
    // Live: wie weit hinter live („−5:30“), nah am Live-Rand „LIVE“
    const behind = (r, t) => { const e = liveEdge(r); return e - t < 8 ? tr('LIVE') : '−' + fmtTime(e - t); };
    function updateUI() {
      const m = M();
      const held = heldAtEnd(m);
      const d = m.duration, c = held ? d : m.currentTime;   // angehalten am Ende: als fertig zeigen (volle Leiste, Endzeit)
      const hasLen = isFinite(d) && d > 0 && !(m === vid && hlsLive());
      if (uiLast.len !== hasLen) { uiLast.len = hasLen; wrap.classList.toggle('nolen', !hasLen); }   // Live-Stream: keine Linie
      if (hasLen) {
        const mf = 'scaleX(' + Math.min(1, Math.max(0, c / d)).toFixed(3) + ')';
        if (mf !== uiLast.mini) { uiLast.mini = mf; miniFill.style.transform = mf; }
      }
      const pk = !m.paused ? 'pause' : (held || m.ended) ? 'replay' : 'play';   // am Ende: „noch einmal“ statt Play
      if (uiLast.play !== pk) { uiLast.play = pk; setIcon(bPlay, P[pk]); }
      if (!wrap.classList.contains('ctl') && !st.barDrag) return;   // Leisten aus: Zeiten und Zeitleiste ruhen lassen
      const dvr = hasLen ? null : seekRange(m);   // Live mit Zurückspulen
      const tc = dvr ? behind(dvr, c) : fmtTime(c), td = hasLen ? fmtTime(d) : 'Live';
      if (tc !== uiLast.cur) { uiLast.cur = tc; tCur.textContent = tc; }
      if (td !== uiLast.dur) { uiLast.dur = td; tDur.textContent = td; }
      if (!st.barDrag) {
        const p = hasLen ? c / d : dvr ? clamp((c - dvr.a) / dvr.len, 0, 1) : 0;
        bar.value = String(Math.round(p * 1000));
        paintBar(p);
      }
    }
    const onMediaEv = (e) => {
      const fromOrig = e.currentTarget === orig;
      if ((st.mode === 'capture') !== fromOrig) return;
      if (e.type === 'waiting') spin.classList.add('on');
      if (e.type === 'playing' || e.type === 'canplay') spin.classList.remove('on');
      if (e.type === 'pause') showControls();
      updateUI();
    };
    ['timeupdate', 'durationchange', 'play', 'pause', 'waiting', 'playing', 'canplay', 'seeked'].forEach((t) => {
      vid.addEventListener(t, onMediaEv);
      orig.addEventListener(t, onMediaEv);
      st.cleanup.push(() => orig.removeEventListener(t, onMediaEv));
    });

