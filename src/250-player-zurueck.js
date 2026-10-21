    // ----- Zurück-Taste: im Querformat erst zurück ins Hochformat, sonst Player schließen -----
    // Vollbild verlassen (Zurück-Taste im Querformat) = nur zurück ins Hochformat, Player bleibt offen
    // Drehungen merken: manche Android-Versionen beenden beim Drehen kurz das Vollbild – das ist kein "Zurück"
    let lastTurn = 0;
    const onTurn = () => { lastTurn = Date.now(); };
    addEventListener('orientationchange', onTurn);
    try { screen.orientation && screen.orientation.addEventListener('change', onTurn); } catch (e) {}
    let lastAspect = innerWidth > innerHeight;
    const onResize = () => {
      const a = innerWidth > innerHeight;
      if (a !== lastAspect) {
        lastAspect = a; lastTurn = Date.now();
        if (st.tapZoomed) { st.tapZoomed = false; st.zoom = 1; st.panX = st.panY = 0; layoutVideo(); }   // gedreht: Doppeltipp-Zoom aufheben
      }
    };
    addEventListener('resize', onResize);
    st.cleanup.push(() => {
      removeEventListener('orientationchange', onTurn);
      removeEventListener('resize', onResize);
      try { screen.orientation && screen.orientation.removeEventListener('change', onTurn); } catch (e) {}
    });
    // ----- App verlassen (Wischgeste, Übersicht, andere App) und Bild-in-Bild -----
    // Android beendet beim Wechsel in eine andere App das Vollbild. Das ist KEIN „Zurück“: Der Player bleibt offen.
    // Erkennen: Die Seite verliert den Fokus oder wird verdeckt – kurz vor oder nach dem Ende des Vollbilds.
    let awayAt = 0;
    const markAway = () => { awayAt = Date.now(); };
    const awaySince = (t) => awayAt >= t - 800 || document.hidden || (!st.framed && !document.hasFocus());
    const pauseAway = () => { const m = M(); if (!m.paused) { st.pausedAway = true; try { m.pause(); } catch (e) {} } };
    const onVisAway = () => {
      if (vp !== st) return;
      if (document.hidden) {
        markAway();
        if (pipIsOurs(vid)) return;   // läuft im kleinen Fenster weiter – genau dafür ist es da
        // kleines Fenster (Bild-in-Bild) gerade mit ✕ geschlossen → anhalten; sonst je nach Einstellung
        const pipClosed = st.pipLeaveAt && Date.now() - st.pipLeaveAt < 1500;
        if (pipClosed || CONFIG.leaveAction === 'pause') pauseAway();
      } else {
        markAway();
        // zurück im Browser: Vollbild verloren? Der Browser erlaubt es nur nach einem Tipp – der nächste holt es zurück
        if (CONFIG.startFullscreen && !isFs() && !pipIsOurs(vid)) {
          st.fsLost = true;
          if (st.pushHist) st.pushHist();   // Zurück-Taste schließt den Player (statt die Seite zu verlassen)
          showControls();
          setTimeout(() => { if (vp === st && !isFs()) toast('Antippen für Vollbild'); }, 400);
        }
      }
    };
    document.addEventListener('visibilitychange', onVisAway);
    addEventListener('blur', markAway);
    addEventListener('pagehide', markAway);
    st.cleanup.push(() => {
      document.removeEventListener('visibilitychange', onVisAway);
      removeEventListener('blur', markAway);
      removeEventListener('pagehide', markAway);
    });
    // Bild-in-Bild verlassen: ✕ (Seite bleibt im Hintergrund) = anhalten; „Vollbild“/zurück zur App = Player im Vollbild
    vid.addEventListener('leavepictureinpicture', () => {
      st.pipLeaveAt = Date.now();
      setTimeout(() => {
        if (vp !== st) return;
        if (document.hidden) { pauseAway(); return; }
        if (CONFIG.startFullscreen && !isFs()) {
          enterFs().then(() => {
            if (vp !== st) return;
            if (isFs()) { st.fsStart = true; st.fsLost = false; if (st.orient) lockO(st.orient); }
            else { st.fsLost = true; if (st.pushHist) st.pushHist(); showControls(); toast('Antippen für Vollbild'); }
          });
        }
      }, 300);
    });
    // Browser, die von selbst ins kleine Fenster wechseln können (beim Verlassen der App), fragen über die Media Session
    try { if (navigator.mediaSession && document.pictureInPictureEnabled) navigator.mediaSession.setActionHandler('enterpictureinpicture', () => { vid.disablePictureInPicture = false; vid.requestPictureInPicture().catch(() => {}); }); } catch (e) {}
    st.cleanup.push(() => { try { navigator.mediaSession.setActionHandler('enterpictureinpicture', null); } catch (e) {} });
    const onFs = () => {
      if (vp !== st || isFs()) return;
      const exitAt = Date.now(), turnBefore = lastTurn;
      // Vollbild endet wegen Bild-in-Bild (Knopf oder Wischen zum Startbildschirm): Player bleibt offen
      const pipNow = () => pipIsOurs(vid) || document.hidden || (st.pipAt && Date.now() - st.pipAt < 2500);
      if (pipNow()) { st.fsStart = false; st.fsLost = true; return; }
      // Kurz nach DEINEM Tipp auf "Drehen" beendet? Dann war es die Webseite (z. B. Pornhub beendet das Vollbild im Hochformat),
      // nicht die Zurück-Taste: Player offen lassen und Vollbild sofort zurückholen (der Tipp ist noch "frisch").
      if (st.selfTurnAt && exitAt - st.selfTurnAt < 1200) {
        enterFs().then(() => {
          if (isFs()) {
            st.fsStart = true;
            if (st.orient) lockO(st.orient);
          } else {
            st.fsStart = false;
            st.fsLost = true;
            if (st.pushHist) st.pushHist();
          }
        });
        return;
      }
      // kurz abwarten: kommt das Vollbild gleich zurück oder war es eine Drehung?
      setTimeout(() => {
        if (vp !== st || isFs()) return;
        if (pipNow()) { st.fsStart = false; st.fsLost = true; return; }
        // (Eingebettetes Fenster: dessen Größe springt beim Vollbild-Ende immer – dort zählt nur die Zurück-Taste.)
        // Gedreht VOR dem Vollbild-Ende (oder praktisch gleichzeitig) = Drehung hat das Vollbild beendet.
        // Gedreht erst DANACH = Folge der Zurück-Taste (das Bild springt nach dem Vollbild zurück ins Hochformat).
        // Über den Drehen-Knopf gesperrt? Dann kann sich das Handy nicht selbst drehen –
        // ein Vollbild-Ende ist dann immer die Zurück-Taste (Chrome dreht dabei oft schon VOR der Meldung zurück).
        const locked = !!st.orient;
        const rotated = !st.framed && !locked && ((exitAt - turnBefore < 1500) || (lastTurn >= exitAt && lastTurn - exitAt < 120));
        if (rotated) {
          // Vollbild durch Drehen verloren (Android oder die Webseite selbst): Player bleibt offen,
          // der nächste Tipp holt das Vollbild zurück. Zurück-Taste schließt ihn bis dahin über den Verlauf.
          st.fsStart = false;
          st.fsLost = true;
          if (st.pushHist) st.pushHist();   // ohne Vollbild: Zurück-Taste über den Verlauf abfangen (nicht auf Facebook/Instagram)
          return;
        }
        // In eine andere App gewechselt (Fokus weg/Seite verdeckt)? Dann kein „Zurück“ – Player bleibt offen,
        // beim Zurückkommen holt der nächste Tipp das Vollbild zurück
        const keep = () => { st.fsStart = false; st.fsLost = true; if (st.pushHist) st.pushHist(); };
        if (awaySince(exitAt)) { keep(); return; }
        // Im Vollbild gestartet: Vollbild verlassen (Zurück-Taste) = Player schließen – nach einem kurzen zweiten Blick,
        // falls die Meldung „Seite verdeckt“ beim App-Wechsel etwas später kommt
        if (st.fsStart) {
          setTimeout(() => { if (vp !== st || isFs()) return; if (awaySince(exitAt)) keep(); else closePlayer(); }, 350);
          return;
        }
        lockO('');
        st.orient = '';
      }, 400);
    };
    document.addEventListener('fullscreenchange', onFs);
    document.addEventListener('webkitfullscreenchange', onFs);
    // Eingebettet: Meldungen der Hauptseite (Vollbild an/aus, Ränder, Titel)
    if (st.framed) {
      fb.onState = (d) => {
        if (vp !== st) return;
        const was = !!st.rfs;
        st.rfs = !!d.fs;
        const ins = d.ins || [0, 0, 0, 0];
        ['t', 'r', 'b', 'l'].forEach((k, i) => wrap.style.setProperty('--evp-s' + k, (ins[i] || 0) + 'px'));
        setTitle();
        if (st.rfs && st.fsWait) st.fsWait();
        if (was !== st.rfs) { onFsLayout(); onFs(); }
      };
      // Hauptseite durfte nicht ins Vollbild schalten → selbst (geht, wenn das Fenster Vollbild erlaubt)
      fb.onFsNo = () => {
        if (vp !== st || !st.fsWait) return;
        const w = st.fsWait;
        try {
          const rq = wrap.requestFullscreen || wrap.webkitRequestFullscreen;
          const pr = rq && rq.call(wrap, { navigationUI: 'hide' });
          if (pr && pr.then) pr.then(w, w); else w();
        } catch (e) { w(); }
      };
      const ins0 = (fb.up && fb.up.ins) || null;
      if (ins0) ['t', 'r', 'b', 'l'].forEach((k, i) => wrap.style.setProperty('--evp-s' + k, (ins0[i] || 0) + 'px'));
      st.cleanup.push(() => { fb.onState = null; fb.onFsNo = null; if (st.framed) fpost(window.parent, { t: 'release' }); });
      if (!CONFIG.startFullscreen) fpost(window.parent, { t: 'take', fs: false });
    }
    // Ohne Vollbild würde die Zurück-Taste die Seite verlassen – dann ein Eintrag im Verlauf, der nur den Player schließt.
    // Im Vollbild ist das unnötig (Zurück beendet dort nur das Vollbild). Auf Facebook/Instagram nie: deren eigene
    // Verlaufs-Verwaltung hält das spätere Entfernen des Eintrags für ein echtes "Zurück" (→ Hauptseite).
    st.histPushed = false;
    const pushHist = () => {
      if (st.histPushed || META || vp !== st) return;
      try { history.pushState(history.state, '', location.href); st.histPushed = true; } catch (e) {}
    };
    st.pushHist = pushHist;
    const onPop = () => { if (vp === st) { st.histPushed = false; closePlayer(); } };
    addEventListener('popstate', onPop);
    st.cleanup.push(() => {
      document.removeEventListener('fullscreenchange', onFs);
      document.removeEventListener('webkitfullscreenchange', onFs);
      removeEventListener('popstate', onPop);
    });
    // Auf Wunsch (Einstellung startFullscreen) wie früher sofort im Vollbild starten
    if (CONFIG.startFullscreen) {
      enterFs().then(() => {
        if (isFs()) { st.fsStart = true; autoOrient(M().videoWidth, M().videoHeight); }
        else pushHist();   // Vollbild ging nicht: Zurück-Taste über den Verlauf abfangen
      });
    } else pushHist();

    // Später noch HD gefunden (Facebook)? NICHT mehr selbst umschalten – das konnte im Reels-Feed ein anderes Video sein.
    // Die neue Stufe erscheint nur im Qualitäts-Menü; du entscheidest.
    st.upgrade = () => {
      if (vp !== st || st.mode !== 'own') return;
      const fresh = playerSources(orig, st.srcRes);
      fresh.forEach((u) => { if (st.sources.indexOf(u) < 0) st.sources.push(u); });
      refreshQual();
    };

    // Video auf der Seite stumm schalten und anhalten, solange der eigene Player läuft (sonst kurz doppelter Ton).
    // Startet die Seite es von selbst wieder (Facebook macht das manchmal), sofort wieder anhalten.
    st.origMuted = orig.muted;
    // Am Ende anhalten statt Endlosschleife: Facebook/Instagram springen bei Reels still an den Anfang zurück
    // (oder laden neu). Das erkennen wir und halten an – Play-Knopf startet es noch einmal.
    st.endHold = false;
    let lastT = 0;
    const origLoop = orig.loop;
    try { orig.loop = false; } catch (e) {}
    const holdEnd = () => {
      if (vp !== st || st.mode !== 'capture') return;
      st.endHold = true;
      try { orig.pause(); } catch (e) {}
      showControls();
      updateUI();
    };
    const onOrigTime = () => {
      if (vp !== st || st.mode !== 'capture') return;
      const d = orig.duration, t = orig.currentTime;
      const userSeek = Date.now() - (st.userSeekAt || 0) < 1500;
      if (!userSeek && isFinite(d) && d > 3 && lastT > d - 1.5 && t < 1.5) holdEnd();   // von hinten nach vorn gesprungen = Schleife
      lastT = t;
    };
    const onOrigEnded = () => holdEnd();
    orig.addEventListener('timeupdate', onOrigTime);
    orig.addEventListener('ended', onOrigEnded);
    st.cleanup.push(() => {
      orig.removeEventListener('timeupdate', onOrigTime);
      orig.removeEventListener('ended', onOrigEnded);
      try { orig.loop = origLoop; } catch (e) {}
    });
    const onOrigPlay = () => {
      if (vp !== st) return;
      // eigener Player: Original bleibt still · gespiegelt und am Ende angehalten: die Seite darf nicht von selbst neu starten
      if (st.mode === 'own' || (st.mode === 'capture' && st.endHold)) { try { orig.pause(); } catch (e) {} }
    };
    orig.addEventListener('play', onOrigPlay);
    orig.addEventListener('playing', onOrigPlay);
    st.cleanup.push(() => { orig.removeEventListener('play', onOrigPlay); orig.removeEventListener('playing', onOrigPlay); });

    // Original anhalten (außer wir übernehmen dessen Bild) und mit dem Laden beginnen
    if (st.sources.length) { try { orig.muted = true; orig.pause(); } catch (e) {} tryNext(); }
    else useCapture();
    showControls();
  }

  // Wächter nach dem Schließen: Läuft das Video auf der Seite wirklich weiter? Hängt es, einmal anstupsen.
