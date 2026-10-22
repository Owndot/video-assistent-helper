    // ----- Sleep-Timer: nach 15–60 Minuten oder am Ende des Videos sanft leiser werden, dann anhalten -----
    st.sleep = null;   // { at: Zeitpunkt (ms) | 0 = am Ende des Videos, iv, fading }
    function sleepLabel() {
      const s = st.sleep;
      if (!s) return 'Aus';
      if (!s.at) return 'Ende des Videos';
      return 'noch ' + Math.max(1, Math.ceil((s.at - Date.now()) / 60000)) + ' min';
    }
    function sleepOff() {
      const s = st.sleep;
      if (!s) return;
      clearInterval(s.iv);
      if (s.fading) { try { VOL().volume = s.vol0; } catch (e) {} }
      st.sleep = null;
    }
    // Ausblenden über 8 s (Lautstärke des Videos), dann Pause – danach die alte Lautstärke wiederherstellen
    function sleepFire() {
      const s = st.sleep;
      if (!s || s.fading) return;
      clearInterval(s.iv);
      const m = VOL();
      s.fading = true; s.vol0 = m.volume;
      const t0 = Date.now(), D = 8000;
      s.iv = setInterval(() => {
        if (vp !== st) { clearInterval(s.iv); return; }
        const k = Math.min(1, (Date.now() - t0) / D);
        try { m.volume = s.vol0 * (1 - k); } catch (e) {}
        if (k >= 1) {
          clearInterval(s.iv);
          st.userPauseAt = Date.now();   // gewollt (Sleep-Timer) – auch im kleinen Fenster nicht wieder anwerfen
          try { M().pause(); } catch (e) {}
          try { m.volume = s.vol0; } catch (e) {}
          st.sleep = null;
          toast('Sleep-Timer: angehalten');
        }
      }, 100);
    }
    function sleepSet(min) {
      sleepOff();
      if (min == null) { toast('Sleep-Timer aus'); return; }
      st.sleep = { at: min ? Date.now() + min * 60000 : 0, iv: 0, fading: false };
      if (min) st.sleep.iv = setInterval(() => { if (vp !== st) { sleepOff(); return; } if (Date.now() >= st.sleep.at) sleepFire(); }, 1000);
      toast(min ? 'Sleep-Timer: ' + min + ' min' : 'Sleep-Timer: Ende des Videos');
    }
    st.cleanup.push(sleepOff);
    // am Ende des Videos (eigener Player oder gespiegelt): true = Timer hat zugeschlagen → nicht wiederholen
    st.sleepAtEnd = () => {
      if (!st.sleep || st.sleep.at) return false;
      sleepOff(); st.sleepHit = true;
      st.userPauseAt = Date.now();
      try { M().pause(); } catch (e) {}
      toast('Sleep-Timer: angehalten');
      return true;
    };
    function openSleep() {
      const cur = st.sleep ? (st.sleep.at ? -1 : 0) : null;
      const opt = (label, min, isCur) => ({ label: label, cur: isCur, fn: () => sleepSet(min) });
      openMenu('Sleep-Timer', [
        opt('Aus', null, cur === null),
        opt('15 min', 15, false), opt('30 min', 30, false), opt('45 min', 45, false), opt('60 min', 60, false),
        opt('Ende des Videos', 0, cur === 0),
      ].concat(cur === -1 ? [{ label: 'Läuft', sub: sleepLabel(), cur: true, stay: true, fn: () => {} }] : []), false, openMore);
    }

    // ----- Dialog-Verstärker: Stimmen deutlicher, laute Stellen gezähmt (wie „Sprache verbessern“ bei Fernsehern) -----
    // Tiefen unter 120 Hz leiser, Sprachbereich (1–4 kHz) angehoben, Kompressor gleicht Laut und Leise an.
    // Geht nur, wenn der Player den Ton bearbeiten darf (eigene Streams, Dateien derselben Seite) – sonst wäre er stumm.
    st.dlg = null;
    function audioGraph() {
      if (st.srcNode) return true;
      if (!canBoost()) return false;
      try {
        st.srcNode = st.actx.createMediaElementSource(vid);
        st.gain = st.actx.createGain();
        st.srcNode.connect(st.gain);
        st.gain.connect(st.actx.destination);
        if (st.actx.state === 'suspended') st.actx.resume().catch(() => {});
        return true;
      } catch (e) { st.srcNode = null; st.gain = null; return false; }
    }
    function setDialog(on) {
      if (on && !audioGraph()) return false;
      if (!st.srcNode) return !on;
      const a = st.actx;
      try { st.srcNode.disconnect(); } catch (e) {}
      if (st.dlg) { st.dlg.forEach((n) => { try { n.disconnect(); } catch (e) {} }); st.dlg = null; }
      if (on) {
        const hp = a.createBiquadFilter(); hp.type = 'highpass'; hp.frequency.value = 120; hp.Q.value = 0.7;
        const lo = a.createBiquadFilter(); lo.type = 'peaking'; lo.frequency.value = 300; lo.Q.value = 0.9; lo.gain.value = -2;
        const sp = a.createBiquadFilter(); sp.type = 'peaking'; sp.frequency.value = 2400; sp.Q.value = 0.8; sp.gain.value = 6;
        const cp = a.createDynamicsCompressor();
        cp.threshold.value = -26; cp.knee.value = 18; cp.ratio.value = 4; cp.attack.value = 0.004; cp.release.value = 0.25;
        const mk = a.createGain(); mk.gain.value = 1.5;   // Ausgleich: nach dem Kompressor wieder etwa gleich laut
        st.srcNode.connect(hp); hp.connect(lo); lo.connect(sp); sp.connect(cp); cp.connect(mk); mk.connect(st.gain);
        st.dlg = [hp, lo, sp, cp, mk];
      } else st.srcNode.connect(st.gain);
      return true;
    }
    // gespeicherte Einstellung beim Start anwenden, sobald der Ton bearbeitbar ist (eigene Quelle geladen)
    const dlgStart = () => { if (CONFIG.dialogBoost && !st.dlg && vp === st && st.mode === 'own') setDialog(true); };
    vid.addEventListener('playing', dlgStart);
    function toggleDialog() {
      const want = !CONFIG.dialogBoost;
      if (want && !setDialog(true)) { toast('Dialog-Verstärker: hier nicht möglich (die Seite gibt den Ton nicht frei)'); return; }
      if (!want) setDialog(false);
      setSetting('dialogBoost', want);
      toast(want ? 'Dialog-Verstärker an' : 'Dialog-Verstärker aus');
    }
