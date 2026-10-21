    // ----- Steuerleisten ein-/ausblenden -----
    function scheduleHide() {
      clearTimeout(st.hideTimer);
      st.hideTimer = setTimeout(() => { if (!M().paused && !st.barDrag) wrap.classList.remove('ctl'); }, 3200);
    }
    function showControls() { wrap.classList.add('ctl'); scheduleHide(); }
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
      const P1 = (p * 100).toFixed(2), B1 = (Math.max(p, b) * 100).toFixed(2);
      bar.style.setProperty('--trk', 'linear-gradient(to right,#000 ' + P1 + '%,rgba(0,0,0,.42) ' + P1 + '%,rgba(0,0,0,.42) ' + B1 + '%,rgba(0,0,0,.18) ' + B1 + '%)'   /* umgedreht: wird per invert(1) weiß */);
    }

    // ----- Anzeige aktualisieren -----
    function updateUI() {
      const m = M();
      const d = m.duration, c = m.currentTime;
      tCur.textContent = fmtTime(c);
      tDur.textContent = isFinite(d) ? fmtTime(d) : 'Live';
      const hasLen = isFinite(d) && d > 0;
      wrap.classList.toggle('nolen', !hasLen);   // Live-Stream: keine Linie
      if (hasLen) miniFill.style.transform = 'scaleX(' + Math.min(1, Math.max(0, c / d)).toFixed(4) + ')';
      if (!st.barDrag) {
        const p = isFinite(d) && d > 0 ? c / d : 0;
        bar.value = String(Math.round(p * 1000));
        paintBar(p);
      }
      setIcon(bPlay, m.paused ? P.play : P.pause);
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

