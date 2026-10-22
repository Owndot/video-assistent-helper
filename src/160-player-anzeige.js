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
    function updateUI() {
      const m = M();
      const held = heldAtEnd(m);
      const d = m.duration, c = held ? d : m.currentTime;   // angehalten am Ende: als fertig zeigen (volle Leiste, Endzeit)
      const hasLen = isFinite(d) && d > 0;
      if (uiLast.len !== hasLen) { uiLast.len = hasLen; wrap.classList.toggle('nolen', !hasLen); }   // Live-Stream: keine Linie
      if (hasLen) {
        const mf = 'scaleX(' + Math.min(1, Math.max(0, c / d)).toFixed(3) + ')';
        if (mf !== uiLast.mini) { uiLast.mini = mf; miniFill.style.transform = mf; }
      }
      const pk = !m.paused ? 'pause' : (held || m.ended) ? 'replay' : 'play';   // am Ende: „noch einmal“ statt Play
      if (uiLast.play !== pk) { uiLast.play = pk; setIcon(bPlay, P[pk]); }
      if (!wrap.classList.contains('ctl') && !st.barDrag) return;   // Leisten aus: Zeiten und Zeitleiste ruhen lassen
      const tc = fmtTime(c), td = isFinite(d) ? fmtTime(d) : 'Live';
      if (tc !== uiLast.cur) { uiLast.cur = tc; tCur.textContent = tc; }
      if (td !== uiLast.dur) { uiLast.dur = td; tDur.textContent = td; }
      if (!st.barDrag) {
        const p = isFinite(d) && d > 0 ? c / d : 0;
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

