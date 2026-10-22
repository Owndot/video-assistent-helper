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
      // gerade an YTDLnis / eine andere App übergeben (Download): anhalten – beim Zurückkommen ist der Player zu
      if (document.hidden && st.dlAt && Date.now() - st.dlAt < 5000) {
        markAway(); st.dlAway = true;
        st.userPauseAt = Date.now();
        try { M().pause(); } catch (e) {}
        return;
      }
      if (!document.hidden && st.dlAway) {
        st.dlAway = false;
        // kurz warten: wurde das Teilen-Menü nur abgebrochen, bleibt der Player offen (wie nach einem App-Wechsel)
        setTimeout(() => { if (vp !== st) return; if (st.dlCancel) { st.dlCancel = false; onVisAway(); return; } st.closePaused = true; closePlayer(); }, 350);
        return;
      }
      if (document.hidden) {
        markAway();
        if (pipIsOurs(vid)) return;   // läuft im kleinen Fenster weiter – genau dafür ist es da
        // kleines Fenster (Bild-in-Bild) gerade mit ✕ geschlossen → anhalten; sonst je nach Einstellung
        const pipClosed = st.pipLeaveAt && Date.now() - st.pipLeaveAt < 1500;
        if (pipClosed || CONFIG.leaveAction === 'pause') pauseAway();
      } else {
        markAway();
        // zurück im Browser: Vollbild verloren? Gleich selbst versuchen (manche Browser erlauben es beim Zurückkommen,
        // auch ein zweites Mal kurz danach) – sonst holt es die nächste Berührung zurück (Tippen, Wischen, Knopf).
        if (CONFIG.startFullscreen && !isFs() && !pipIsOurs(vid)) {
          st.fsLost = true;
          if (st.pushHist) st.pushHist();   // Zurück-Taste schließt den Player (statt die Seite zu verlassen)
          tryRegain();
          setTimeout(() => { if (vp === st && !isFs()) { showControls(); toast('Antippen für Vollbild'); } }, 900);
        }
      }
    };
    onVisChange(onVisAway);
    // Vollbild selbst zurückholen: sofort, nach 250 ms und nach 700 ms (und wenn das Fenster den Fokus bekommt) –
    // ohne Berührung erlaubt das nicht jeder Browser; dann klappt es mit der nächsten Berührung (18.6)
    function tryRegain() {
      // nur, wenn der Browser es gerade erlauben würde (frische Berührung) – sonst nur eine Fehlermeldung in der Konsole
      [0, 250, 700].forEach((ms) => setTimeout(() => { if (vp === st && st.fsLost && !isFs() && !document.hidden && !pipIsOurs(vid) && touchFresh()) regainFs(); }, ms));
    }
    const onFocusBack = () => { if (vp === st && st.fsLost && !isFs() && !document.hidden) tryRegain(); };
    addEventListener('focus', onFocusBack);
    addEventListener('pageshow', onFocusBack);
    st.cleanup.push(() => { removeEventListener('focus', onFocusBack); removeEventListener('pageshow', onFocusBack); });
    // Jede Berührung holt ein verlorenes Vollbild zurück – auch Wischen, Spulen, Zoomen (Tippen und Knöpfe tun es
    // schon selbst). Ausgelöst am Ende der Berührung: erst dann erlaubt der Browser das Vollbild.
    const onAnyTouch = (e) => {
      if (vp !== st || !st.fsLost || isFs() || !CONFIG.startFullscreen) return;
      if (e.type === 'pointerup' && e.pointerType === 'mouse') return;
      const path = e.composedPath ? e.composedPath() : [];
      if (path.some((el) => el && (el.tagName === 'BUTTON' || (el.classList && (el.classList.contains('vmenu') || el.classList.contains('vbar')))))) return;
      regainFs();
    };
    wrap.addEventListener('touchend', onAnyTouch, true);
    wrap.addEventListener('pointerup', onAnyTouch, true);
    addEventListener('blur', markAway);
    addEventListener('pagehide', markAway);
    st.cleanup.push(() => {
      offVisChange(onVisAway);
      removeEventListener('blur', markAway);
      removeEventListener('pagehide', markAway);
    });
    // Bild-in-Bild verlassen: ✕ (Seite bleibt im Hintergrund) = anhalten; „Vollbild“/zurück zur App = Player im Vollbild
    vid.addEventListener('leavepictureinpicture', () => {
      st.pipLeaveAt = Date.now();
      // „Vollbild“ im kleinen Fenster: sofort versuchen (der Browser erlaubt es oft nur in diesem Moment)
      if (CONFIG.startFullscreen && !isFs() && touchFresh()) { try { const pr = enterFs(); if (pr && pr.then) pr.then(() => { if (vp === st && isFs()) { st.fsStart = true; st.fsLost = false; if (st.orient) lockO(st.orient); } }); } catch (e) {} }
      setTimeout(() => {
        if (vp !== st) return;
        if (document.hidden) { st.userPauseAt = Date.now(); pauseAway(); return; }   // ✕ im kleinen Fenster = gewollt
        if (CONFIG.startFullscreen && !isFs()) {
          const done = () => {
            if (vp !== st) return;
            if (isFs()) { st.fsStart = true; st.fsLost = false; if (st.orient) lockO(st.orient); }
            else { st.fsLost = true; if (st.pushHist) st.pushHist(); showControls(); toast('Antippen für Vollbild'); }
          };
          if (touchFresh()) enterFs().then(done); else done();
        }
      }, 300);
    });
    // Browser, die von selbst ins kleine Fenster wechseln können (beim Verlassen der App), fragen über die Media Session
    try { if (navigator.mediaSession && document.pictureInPictureEnabled) navigator.mediaSession.setActionHandler('enterpictureinpicture', () => { if (!st.pipOk) return; vid.disablePictureInPicture = false; vid.requestPictureInPicture().catch(() => {}); }); } catch (e) {}
    st.cleanup.push(() => { try { navigator.mediaSession.setActionHandler('enterpictureinpicture', null); } catch (e) {} });
    // ----- Im kleinen Fenster weiterlaufen lassen (18.5) -----
    // Beim Wischen zum Startbildschirm wechselt der Browser ins kleine Fenster. Kurz davor oder danach halten aber
    // manche Seiten (Facebook: ihr Video, sobald sie verdeckt ist – beim Spiegeln steht dann auch das kleine Fenster),
    // der Browser selbst oder „Beim Verlassen: Anhalten“ das Video an. Hält es etwas anderes als du (Knopf, Knopf im
    // kleinen Fenster, Sleep-Timer, Reel-Ende, ✕) rund um den Wechsel oder im kleinen Fenster an: gleich weiter.
    st.pipEnterAt = 0;
    let pipResumes = 0;
    const byUser = () => Date.now() - (st.userPauseAt || 0) < 800 || !!st.endHold;
    const inPip = () => pipIsOurs(vid) || (st.pipEnterAt && Date.now() - st.pipEnterAt < 2500 && !(st.pipLeaveAt > st.pipEnterAt));
    const resumePip = () => {
      if (vp !== st || !inPip() || st.lastPauseByUser || st.endHold || pipResumes >= 8) return;
      const m = M();
      if (!m.paused) return;
      pipResumes++; st.pausedAway = false;
      try { const pr = m.play(); if (pr && pr.catch) pr.catch(() => {}); } catch (e) {}
    };
    const onAnyPause = (e) => {
      if (vp !== st || e.target !== M()) return;
      st.lastPauseAt = Date.now(); st.lastPauseByUser = byUser();
      if (!st.lastPauseByUser && inPip()) setTimeout(resumePip, 120);
    };
    const onAnyPlay = (e) => { if (e.target === M()) st.lastPauseByUser = false; };
    vid.addEventListener('pause', onAnyPause); orig.addEventListener('pause', onAnyPause);
    vid.addEventListener('play', onAnyPlay); orig.addEventListener('play', onAnyPlay);
    st.cleanup.push(() => { orig.removeEventListener('pause', onAnyPause); orig.removeEventListener('play', onAnyPlay); });
    vid.addEventListener('enterpictureinpicture', () => {
      st.pipEnterAt = Date.now(); pipResumes = 0;
      // kurz vorher angehalten (Seite verdeckt, „Anhalten“-Einstellung …), aber nicht von dir: weiter im kleinen Fenster
      if (M().paused && !st.lastPauseByUser && Date.now() - (st.lastPauseAt || 0) < 3000) setTimeout(resumePip, 120);
    });
    const onFs = () => {
      if (vp !== st || isFs()) return;
      // Zurück-Taste bei offenem Menü (Einstellungen …): nur eine Ebene zurück, Player bleibt offen;
      // das Vollbild holt der nächste Tipp zurück (auch ein Tipp im Menü)
      if (menuOpen() && !document.hidden) {
        menuStepBack();
        st.fsStart = false; st.fsLost = true;
        if (st.pushHist) st.pushHist();
        return;
      }
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
      // Sieht es nach der Zurück-Taste aus (im Vollbild gestartet, nicht gedreht, Seite sichtbar)? Dann den Player
      // SOFORT unsichtbar und stumm machen – man sieht gleich die Seite (wie bei Samsung), statt kurz den Player ohne
      // Vollbild mit den Leisten des Browsers. Geprüft wird unsichtbar weiter; war es doch etwas anderes, kommt er zurück.
      const maybeBack = st.fsStart && !awaySince(exitAt) && (!!st.orient || st.framed || exitAt - turnBefore >= 1500);
      if (maybeBack) veil(true);
      // kurz abwarten: kommt das Vollbild gleich zurück oder war es eine Drehung?
      setTimeout(() => {
        if (vp !== st) return;
        if (isFs()) { veil(false); return; }
        if (pipNow()) { veil(false); st.fsStart = false; st.fsLost = true; return; }
        // (Eingebettetes Fenster: dessen Größe springt beim Vollbild-Ende immer – dort zählt nur die Zurück-Taste.)
        // Gedreht VOR dem Vollbild-Ende (oder praktisch gleichzeitig) = Drehung hat das Vollbild beendet.
        // Gedreht erst DANACH = Folge der Zurück-Taste (das Bild springt nach dem Vollbild zurück ins Hochformat).
        // Über den Drehen-Knopf gesperrt? Dann kann sich das Handy nicht selbst drehen –
        // ein Vollbild-Ende ist dann immer die Zurück-Taste (Chrome dreht dabei oft schon VOR der Meldung zurück).
        const locked = !!st.orient;
        const rotated = !st.framed && !locked && ((exitAt - turnBefore < 1500) || (lastTurn >= exitAt && lastTurn - exitAt < 120));
        if (rotated) {
          veil(false);
          // Vollbild durch Drehen verloren (Android oder die Webseite selbst): Player bleibt offen,
          // der nächste Tipp holt das Vollbild zurück. Zurück-Taste schließt ihn bis dahin über den Verlauf.
          st.fsStart = false;
          st.fsLost = true;
          if (st.pushHist) st.pushHist();   // ohne Vollbild: Zurück-Taste über den Verlauf abfangen (nicht auf Facebook/Instagram)
          return;
        }
        // In eine andere App gewechselt (Fokus weg/Seite verdeckt)? Dann kein „Zurück“ – Player bleibt offen,
        // beim Zurückkommen holt der nächste Tipp das Vollbild zurück
        const keep = () => { veil(false); st.fsStart = false; st.fsLost = true; if (st.pushHist) st.pushHist(); };
        if (awaySince(exitAt)) { keep(); return; }
        // Im Vollbild gestartet: Vollbild verlassen (Zurück-Taste) = Player schließen – nach einem kurzen zweiten Blick,
        // falls die Meldung „Seite verdeckt“ beim App-Wechsel etwas später kommt
        if (st.fsStart) {
          setTimeout(() => { if (vp !== st) return; if (isFs()) { veil(false); return; } if (awaySince(exitAt)) keep(); else closePlayer(); }, 350);
          return;
        }
        veil(false);
        lockO('');
        st.orient = '';
      }, 400);
    };
    // Player kurz unsichtbar und stumm (während geprüft wird, ob „Zurück“ gemeint war)
    function veil(on) {
      if (on === wrap.classList.contains('vgone')) return;
      // Ton des Players aus/an – vor der Übergabe (die gibt der Seite ihren Ton zurück)
      try {
        const m = VOL();
        if (on) { st.veilMuted = m.muted; if (m !== orig) m.muted = true; }
        else if (typeof st.veilMuted === 'boolean') { m.muted = st.veilMuted; st.veilMuted = null; }
      } catch (e) {}
      wrap.classList.toggle('vgone', on);
      // Seite übernimmt sofort (an deiner Stelle, mit Ton) – war es doch kein Zurück, wieder zurücknehmen
      if (on) handBack(st); else unHand(st);
    }
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
    const onPop = () => {
      if (vp !== st) return;
      st.histPushed = false;
      if (menuOpen()) { menuStepBack(); pushHist(); return; }   // Menü offen: nur zurück im Menü
      closePlayer();
    };
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
      const fresh = st.capFallback ? fbSureSources(orig, st.srcRes) : playerSources(orig, st.srcRes);   // Kadri/Facebook: nur passende
      fresh.forEach((u) => { if (st.sources.indexOf(u) < 0) st.sources.push(u); });
      refreshQual();
    };

    // Video auf der Seite stumm schalten und anhalten, solange der eigene Player läuft (sonst kurz doppelter Ton).
    // Startet die Seite es von selbst wieder (Facebook macht das manchmal), sofort wieder anhalten.
    st.origMuted = orig.muted;
    // Am Ende anhalten statt Endlosschleife: Facebook/Instagram springen bei Reels still an den Anfang zurück
    // (oder laden neu). Das erkennen wir und halten an – Play-Knopf startet es noch einmal.
    st.endHold = false;
    armNextGuard();   // Countdown „Nächstes Reel“ der Seite sofort abbrechen
    st.cleanup.push(() => disarmNextGuard(10000));   // noch 10 s – die Seite steht danach oft am Ende
    let lastT = 0;
    const origLoop = orig.loop;
    try { orig.loop = false; } catch (e) {}
    const holdEnd = () => {
      if (vp !== st || st.mode !== 'capture') return;
      if (st.repeatNow && st.repeatNow()) { if (!st.sleepHit) return; st.sleepHit = false; }   // Wiederholen: von vorn · Sleep-Timer: anhalten
      st.endHold = true;
      try { orig.pause(); } catch (e) {}
      showControls();
      updateUI();
    };
    // Reels-Seiten (Facebook u. a.) springen beim Ende von selbst zum nächsten Video – manche schon kurz VOR „ended“
    // (sobald die Zeit fast am Ende ist). Dann wäre der Player schwarz und man stünde danach auf einem anderen Reel.
    // Darum: 0,2 s vor dem Ende anhalten (letztes Bild bleibt stehen; früher 0,6 s – seit der Facebook-Countdown
    // abgebrochen wird, reicht das, 18.4) und – siehe „Schleuse“ unten – der Seite die
    // letzten Meldungen des Videos gar nicht erst zeigen. Nahe am Ende genauer hinschauen als „timeupdate“ (¼ s).
    const END_GAP = 0.2;
    let nearIv = 0;
    const stopNear = () => { clearInterval(nearIv); nearIv = 0; };
    const nearEnd = () => {
      if (vp !== st || st.mode !== 'capture' || st.endHold || orig.paused) { stopNear(); return; }
      const d = orig.duration, t = orig.currentTime;
      if (!isFinite(d) || d < 2) { stopNear(); return; }
      if (t >= d - END_GAP * Math.max(1, orig.playbackRate || 1)) { stopNear(); holdEnd(); }
      else if (t < d - 2) stopNear();
    };
    const onOrigTime = () => {
      if (vp !== st || st.mode !== 'capture') return;
      const d = orig.duration, t = orig.currentTime;
      const userSeek = Date.now() - (st.userSeekAt || 0) < 1500;
      if (!userSeek && isFinite(d) && d > 3 && lastT > d - 1.5 && t < 1.5) holdEnd();   // von hinten nach vorn gesprungen = Schleife
      if (!nearIv && !st.endHold && isFinite(d) && d >= 2 && t > d - 1.5 && !orig.paused) nearIv = setInterval(nearEnd, 40);
      lastT = t;
    };
    st.cleanup.push(stopNear);
    const onOrigEnded = () => holdEnd();
    orig.addEventListener('timeupdate', onOrigTime);
    orig.addEventListener('ended', onOrigEnded);
    st.cleanup.push(() => {
      orig.removeEventListener('timeupdate', onOrigTime);
      orig.removeEventListener('ended', onOrigEnded);
      try { orig.loop = origLoop; } catch (e) {}
    });
    const onOrigPlay = () => {
      if (vp !== st || st.handed) return;   // schon an die Seite übergeben (Zurück-Geste): die Seite darf spielen
      // eigener Player: Original bleibt still · gespiegelt und am Ende angehalten: die Seite darf nicht von selbst neu starten
      if (st.mode === 'own' || (st.mode === 'capture' && st.endHold)) { try { orig.pause(); } catch (e) {} }
    };
    orig.addEventListener('play', onOrigPlay);
    orig.addEventListener('playing', onOrigPlay);
    st.cleanup.push(() => { orig.removeEventListener('play', onOrigPlay); orig.removeEventListener('playing', onOrigPlay); });
    // Schleuse (nur beim Spiegeln): Meldungen vom Ende des Videos erreichen die Seite nicht – „ended“ nie, „timeupdate“
    // und „pause“ in der letzten Sekunde nicht. Abgefangen wird ganz vorn (window, Einfangphase), also vor den
    // Zuhörern der Seite; die eigenen Zuhörer des Players werden hier direkt bedient.
    const nearTail = () => { const d = orig.duration; return isFinite(d) && d >= 2 && orig.currentTime > d - 1; };
    const gate = (e) => {
      if (e.target !== orig || vp !== st || st.mode !== 'capture') return;
      if (e.type === 'ended') { e.stopImmediatePropagation(); onOrigEnded(); updateUI(); return; }
      if (!nearTail()) return;
      e.stopImmediatePropagation();
      if (e.type === 'timeupdate') { onOrigTime(); updateUI(); }
      else if (e.type === 'pause') { showControls(); updateUI(); }
    };
    ['ended', 'timeupdate', 'pause'].forEach((t) => window.addEventListener(t, gate, true));
    st.cleanup.push(() => ['ended', 'timeupdate', 'pause'].forEach((t) => window.removeEventListener(t, gate, true)));

    // Original anhalten (außer wir übernehmen dessen Bild) und mit dem Laden beginnen
    if (st.sources.length) { try { orig.muted = true; orig.pause(); } catch (e) {} tryNext(); }
    else useCapture();
    showControls();
  }

  // Wächter nach dem Schließen: Läuft das Video auf der Seite wirklich weiter? Hängt es, einmal anstupsen.
