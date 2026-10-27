    // ----- Lautstärke & Helligkeit (System-Werte sind für Webseiten gesperrt – daher im Player selbst) -----
    // Verstärken über 100 % geht nur, wenn der Ton für den Player "lesbar" ist: eigene Streams (hls.js) oder Dateien derselben Seite
    function canBoost() {
      if (!st.actx || st.mode !== 'own') return false;
      if (st.srcNode) return true;
      return !!st.hls || (st.cur && hostOf(st.cur) === location.hostname);
    }
    function applyLevel(level) {
      const m = VOL();
      let lv = clamp(level, 0, 1);   // höchstens 100 %
      if (st.sys && st.sys.vol) {
        // Helfer-App: echte Medienlautstärke des Handys. Android kennt nur ~15 Stufen – die Zwischenwerte (1 %-genau)
        // entstehen, indem das Video INNERHALB einer Stufe etwas leiser spielt (nach Androids Lautstärkekurve in dB).
        const mx = st.sys.max || 15;
        const pos = lv * mx, k = Math.ceil(pos - 1e-6);
        let page = 1;
        if (k <= 0) page = 0;
        else if (k === 1) page = pos;   // zwischen Stille und der leisesten Stufe: einfach linear
        else page = Math.pow(10, (volDb(pos, mx) - volDb(k, mx)) / 20);
        try { m.volume = clamp(page, 0, 1); m.muted = lv === 0; if ((st.mode === 'capture' && !FF_CAP) || st.bridge) vid.muted = true; } catch (e) {}
        if (st.gain) st.gain.gain.value = 1;
        st.level = lv;
        if (k !== st.sysK) { st.sysK = k; helperSend('vol', k / mx); }
        return lv;
      }
      if (lv > 1 && !canBoost()) lv = 1;
      if (lv > 1 && !st.srcNode) {
        try {
          st.srcNode = st.actx.createMediaElementSource(vid);
          st.gain = st.actx.createGain();
          st.srcNode.connect(st.gain);
          st.gain.connect(st.actx.destination);
          if (st.actx.state === 'suspended') st.actx.resume().catch(() => {});
        } catch (e) { st.srcNode = null; st.gain = null; lv = 1; }
      }
      m.volume = Math.min(1, lv);
      m.muted = lv === 0;
      if ((st.mode === 'capture' && !FF_CAP) || st.bridge) vid.muted = true;   // (beim nahtlosen Start kommt der Ton noch von der Seite)
      if (st.gain) st.gain.gain.value = Math.max(1, lv);
      st.level = lv;
      return lv;
    }
    // Helligkeit wie Androids Regler (BrightnessUtils): Reglerstellung 0–1 ↔ lineare Helligkeit 0–1
    function gammaToLin(g) {
      const R = 0.5, A = 0.17883277, B = 0.28466892, C = 0.55991073;
      const r = g <= R ? Math.pow(g / R, 2) : Math.exp((g - C) / A) + B;
      return clamp(r / 12, 0, 1);
    }
    function linToGamma(l) {
      const R = 0.5, A = 0.17883277, B = 0.28466892, C = 0.55991073;
      const v = clamp(l, 0, 1) * 12;
      return clamp(v <= 1 ? Math.sqrt(v) * R : A * Math.log(v - B) + C, 0, 1);
    }
    // Ganz nach unten und dann noch weiter wischen = automatische Helligkeit des Handys
    function setAutoBright() {
      if (st.autoB) return;
      st.autoB = true;
      st.sysBright = false;
      if (helperQ.bright) helperQ.bright.want = null;
      helper('/end');
      try { if (navigator.vibrate) navigator.vibrate(12); } catch (e) {}
    }
    // Androids Standardkurve für Medien (Stufe in % → dB): 1 % −58 dB, 20 % −40 dB, 60 % −17 dB, 100 % 0 dB
    function volDb(i, mx) {
      const x = clamp(i / mx * 100, 1, 100), pts = [[1, -58], [20, -40], [60, -17], [100, 0]];
      for (let j = 1; j < pts.length; j++) {
        if (x <= pts[j][0]) { const a = pts[j - 1], c = pts[j]; return a[1] + (c[1] - a[1]) * (x - a[0]) / (c[0] - a[0]); }
      }
      return 0;
    }
    function applyBright(b) {
      if (st.sys && st.sys.bright) {
        // Helfer-App: echte Bildschirmhelligkeit (gilt nur, solange der Player offen ist). Der Wert ist – wie
        // Androids eigener Regler – "gefühlt gleichmäßig"; ans Handy geht die passende lineare Helligkeit.
        st.bright = clamp(b, 0, 1);
        dimEl.style.opacity = '0';
        vid.style.filter = '';
        st.dim = 0;
        st.sysBright = true;
        st.autoB = false;
        helperSend('bright', Math.max(0.004, gammaToLin(st.bright)));
        return;
      }
      st.bright = clamp(b, 0.15, 1);   // höchstens 100 % (nur abdunkeln)
      dimEl.style.opacity = st.bright < 1 ? String(Math.min(0.85, 1 - st.bright)) : '0';
      vid.style.filter = st.bright > 1 ? 'brightness(' + st.bright.toFixed(2) + ')' : '';
      st.dim = parseFloat(dimEl.style.opacity) || 0;
    }
    // Läuft die Helfer-App? Dann gelten die Gesten fürs Handy selbst – Startwerte sind die aktuellen des Handys.
    st.sys = null;
    helper('/ping').then((d) => {
      if (!d || !d.ok || vp !== st) return;
      st.sys = { vol: true, bright: !!(d.overlay || d.write), max: d.volMax || 15, dl: !!d.dl, proxy: !!d.proxy, dash: !!d.dash };
      if (st.refreshDl) st.refreshDl();   // mit Helfer lassen sich auch Streams speichern
      if (typeof d.vol === 'number') {
        st.level = clamp(d.vol, 0, 1);
        st.sysK = typeof d.volStep === 'number' ? d.volStep : Math.round(st.level * st.sys.max);
        try { const m = VOL(); m.volume = 1; } catch (e) {}
      }
      st.autoB = !!st.sys.bright;   // zu Beginn: Helligkeit wie im System (auch automatisch) – bis du wischst
      if (st.sys.bright) {
        if (typeof d.bright === 'number') st.bright = linToGamma(d.bright);
        dimEl.style.opacity = '0';
        st.dim = 0;
      }
      // Lebenszeichen (sonst gibt die App die Helligkeit nach 30 s zurück) – und Lautstärketasten mitbekommen
      const beat = setInterval(() => {
        if (vp !== st || document.hidden) return;
        helper('/beat').then((r) => {
          if (!r || !r.ok || vp !== st || helperBusy('vol') || typeof r.vol !== 'number') return;
          // Lautstärketasten gedrückt? Dann gilt die neue Stufe des Handys, das Video wieder voll
          const k = typeof r.volStep === 'number' ? r.volStep : Math.round(r.vol * st.sys.max);
          if (k !== st.sysK) { st.sysK = k; st.level = clamp(r.vol, 0, 1); try { VOL().volume = 1; VOL().muted = false; } catch (e) {} }
        });
      }, 2500);
      // App verlassen (Startbildschirm, anderes Fenster): Helligkeit sofort zurückgeben, beim Zurückkommen wieder setzen
      const onVis = () => {
        if (vp !== st) return;
        if (document.hidden) helper('/end');
        else if (st.sysBright) helperSend('bright', Math.max(0.004, gammaToLin(st.bright)));
      };
      onVisChange(onVis);
      st.cleanup.push(() => { clearInterval(beat); offVisChange(onVis); });
    });
    st.cleanup.push(() => { if (st.sys) helper('/end'); });
    let indTimer = 0;
    // Anzeige: Text, optional Füllstand (0–1, über 1 = verstärkt/aufgehellt); "stay" = bleibt stehen (langes Drücken)
    // Hochformat: Anzeige direkt über der Oberkante des Videobilds (nicht ganz oben im Schwarzen).
    // Füllt das Video den Bildschirm (Reels), dann knapp über den Play-Knöpfen. Querformat: oben wie bisher.
    // Hinweise und Gesten-Anzeige: eine Stelle UNTER dem Bild – mit Abstand unter der Unterkante des Videobilds.
    // Füllt das Bild den Bildschirm (Reels, Querformat, Zoom): knapp über der unteren Leiste. Nie über Untertiteln.
    function placeInd(el) {
      el = el || ind;
      const H = wrap.clientHeight || innerHeight, hh = el.offsetHeight || 46;
      let cb = H, wt = 0;
      try {
        const r = vid.getBoundingClientRect(), wr = wrap.getBoundingClientRect();
        const vw = vid.videoWidth, vh = vid.videoHeight;
        wt = wr.top; cb = r.bottom - wt;
        if (vw && vh && (getComputedStyle(vid).objectFit || 'contain') === 'contain' && st.zoom <= 1.001) cb -= (r.height - vh * Math.min(r.width / vw, r.height / vh)) / 2;
      } catch (e) {}
      const vb = wrap.querySelector('.vbot');
      let lim = (vb ? vb.getBoundingClientRect().top - wt : H - 130) - 12;
      const mn = wrap.classList.contains('menu') ? wrap.querySelector('.vmenu.on') : null;   // offenes Menü: darüber
      if (mn) lim = Math.min(lim, mn.getBoundingClientRect().top - wt - 12);
      let top = cb + 14;
      if (top + hh > lim) top = lim - hh;
      const sb = wrap.querySelector('.vsub');
      if (sb && sb.textContent.trim() && sb.offsetParent) {
        const sr = sb.getBoundingClientRect(), a = sr.top - wt, b = sr.bottom - wt;
        if (top < b && top + hh > a) top = a - hh - 10;
      }
      el.style.top = Math.max(70, Math.round(top)) + 'px';
    }
    st.notePos = (el) => { placeInd(el); return (el || ind).style.top; };   // dieselbe Stelle für Hinweise (toast)
    const showInd = (t, lvl, stay) => {
      if (toastEl) toastEl.classList.remove('on');   // gleiche Stelle wie die Hinweise – nie beide übereinander
      placeInd();
      indTxt.textContent = tr(t);
      ind.classList.add('on');
      clearTimeout(indTimer);
      if (!stay) indTimer = setTimeout(() => ind.classList.remove('on'), 800);
    };
    const hideInd = () => { clearTimeout(indTimer); ind.classList.remove('on'); };
    // Spul-Anzeige in der Mitte: Zielzeit groß, Restzeit klein (wie Samsung), dazu die Sprungweite
    // Anzeige beim Wischen: große Zeit, darunter „Stelle / Länge“ mit kleiner Leiste, dann der Sprung („+3 s“)
    const showSeek = (to, dur, delta) => {
      const fin = isFinite(dur) && dur > 0;
      seekBig.textContent = fmtTime(to);
      seekRest.textContent = fin ? '/ ' + fmtTime(dur) : '';   // (große Zahl = Stelle, darunter die Länge)
      seekLine.style.display = fin ? '' : 'none';
      if (fin) seekFill.style.transform = 'scaleX(' + clamp(to / dur, 0, 1).toFixed(3) + ')';
      const a = Math.round(Math.abs(delta));
      seekDelta.textContent = (delta < -0.4 ? '−' : '+') + (a < 60 ? a + ' s' : fmtTime(a));
      hideInd();
      wrap.classList.add('seeking');
      seekBox.classList.add('on');
    };
    const hideSeek = () => { seekBox.classList.remove('on'); wrap.classList.remove('seeking'); };
    // Doppelt tippen: Sprung anzeigen, weitere schnelle Tipps auf derselben Seite zählen dazu (10 s, 20 s, 30 s …)
    let sfxTimer = 0;
    let btnFxTimer = 0;
    const showSkipFx = (dir, total) => {
      const btn = dir > 0 ? bFwd : bBack, obtn = dir > 0 ? bBack : bFwd;
      const ctlOn = wrap.classList.contains('ctl') && !wrap.classList.contains('menu') && !st.locked;
      if (ctlOn) {
        sfxL.classList.remove('on', 'go'); sfxR.classList.remove('on', 'go');
        obtn.classList.remove('go');
        const t = btn.querySelector('text');
        if (t) { t.textContent = String(total); t.style.fontSize = total >= 100 ? '6.4px' : ''; }
        btn.classList.remove('go'); void btn.offsetWidth; btn.classList.add('go');
        wrap.classList.add('skipfx');
        showControls();   // Leisten bleiben, solange getippt wird
        clearTimeout(btnFxTimer);
        btnFxTimer = setTimeout(() => {
          wrap.classList.remove('skipfx'); btn.classList.remove('go');
          [bFwd, bBack].forEach((b) => { const x = b.querySelector('text'); if (x) { x.textContent = String(SKIP()); x.style.fontSize = ''; } });
        }, 700);
        return;
      }
      const el = dir > 0 ? sfxR : sfxL, other = dir > 0 ? sfxL : sfxR;
      other.classList.remove('on', 'go');
      el.lab.textContent = String(total);
      el.lab.style.fontSize = total >= 100 ? '6.4px' : '';
      el.classList.remove('go'); void el.offsetWidth; el.classList.add('on', 'go');   // Pfeile neu loslaufen lassen
      clearTimeout(sfxTimer);
      sfxTimer = setTimeout(() => el.classList.remove('on'), 650);
    };
    // Senkrechte Anzeige: kind 'vol' (rechts) oder 'bright' (links); max > 1 = Bereich über 100 % (Strich bei 100 %)
    let sideTimer = 0;
    const showSide = (kind, lvl, max, txt) => {
      hideInd();
      const f = Math.min(1, Math.max(0, lvl / max));
      side.className = 'vside g on ' + (kind === 'vol' ? 'r' : 'l') + (max > 1.001 ? ' ext' : '') + (f > 0.18 ? ' lit' : '') + (f > 0.87 ? ' top' : '');
      indFill.style.height = (f * 100).toFixed(1) + '%';
      indFill.classList.toggle('over', lvl > 1.001);
      sideMark.style.bottom = (100 / max).toFixed(1) + '%';
      sideTxt.textContent = txt != null ? txt : Math.round(lvl * 100) + '%';
      setIcon(sideIco, kind === 'vol' ? (lvl < 0.005 ? P.mute : P.vol) : P.sun);
      side.setAttribute('aria-label', tr((kind === 'vol' ? 'Lautstärke ' : 'Helligkeit ') + Math.round(lvl * 100) + ' %'));
      clearTimeout(sideTimer);
      sideTimer = setTimeout(() => side.classList.remove('on'), 900);
    };
    const clamp = (x, a, b) => Math.min(b, Math.max(a, x));

    // Video im Player anordnen: Bildformat (Original/Füllen/Strecken), Zoom und Verschieben
    function layoutVideo() {
      vid.style.objectFit = st.aspect;
      // Mittig per CSS (an allen vier Seiten verankert, Rand "auto"): bleibt auch dann genau in der Mitte, wenn sich die
      // Höhe nachträglich ändert (Vollbild, Adressleiste ein/aus). Ohne Verschiebung per transform – die hat Android
      // in eingebetteten Vollbild-Fenstern falsch umgesetzt (Bild saß zu weit oben).
      vid.style.width = '100%';
      vid.style.height = '100%';
      // nur so weit verschieben, wie wirklich Bild ist (nicht in die schwarzen Ränder) – beim Ziehen mit einem Finger
      // gibt es über die Kante hinaus mit Widerstand nach (wie Telegram) und federt beim Loslassen zurück
      if (!st.panFree) {
        const lim = panLimits(st.zoom);
        st.panX = clamp(st.panX, -lim[0], lim[0]);
        st.panY = clamp(st.panY, -lim[1], lim[1]);
      }
      vid.style.transform = st.zoom > 1.001 ? 'translate(' + st.panX + 'px,' + st.panY + 'px) scale(' + st.zoom + ')' : '';
      if (st.holdCv && st.holdCv.isConnected) st.holdCv.style.cssText = vid.style.cssText + ';pointer-events:none';
      if (st.subPlace) st.subPlace();
    }
    // Sichtbares Bild bei 1× (ohne die schwarzen Ränder) und wie weit es sich bei Zoom z verschieben lässt
    function contentBox() {
      const W = wrap.clientWidth || innerWidth, H = wrap.clientHeight || innerHeight;
      const w = W, hh = H;
      let cw = w, ch = hh;
      if (st.aspect === 'contain' && vid.videoWidth && vid.videoHeight) {
        const sc = Math.min(w / vid.videoWidth, hh / vid.videoHeight);
        cw = vid.videoWidth * sc; ch = vid.videoHeight * sc;
      }
      return [cw, ch, W, H];
    }
    function panLimits(z) {
      const b = contentBox();
      return [Math.max(0, (b[0] * z - b[2]) / 2), Math.max(0, (b[1] * z - b[3]) / 2)];
    }
    // Doppeltipp in der Mitte (wie Telegram): 3× um die getippte Stelle – die Stelle unter dem Finger bleibt dort;
    // hochkant (Querformat-Video) mindestens so weit, dass das Bild den Bildschirm füllt. Noch einmal = ganzes Bild.
    // Zoom: ein Finger verschiebt das Bild (mit Schwung), zwei Finger zoomen weiter.
    const TAP_ZOOM = 3;
    function zoomMax() { const b = contentBox(); return clamp(Math.max(4, b[2] / b[0], b[3] / b[1]), 4, 6); }
    function zoomTo(z, px, py) {
      vid.classList.add('vzan');
      clearTimeout(st.zanT);
      st.zanT = setTimeout(() => vid.classList.remove('vzan'), 360);
      st.zoom = z; st.panX = px; st.panY = py;
      layoutVideo();
    }
    function tapZoom(x, y) {
      if (st.zoom > 1.01) { st.tapZoomed = false; zoomTo(1, 0, 0); return true; }
      if (!vid.videoWidth || !vid.videoHeight) return false;
      const b = contentBox(), cw = b[0], ch = b[1], W = b[2], H = b[3];
      const z = clamp(Math.max(TAP_ZOOM, W / cw, H / ch), 1, zoomMax());
      const r = wrap.getBoundingClientRect();
      const qx = x - r.left - W / 2, qy = y - r.top - H / 2;
      const ux = clamp(qx, -cw / 2, cw / 2), uy = clamp(qy, -ch / 2, ch / 2);   // getippte Stelle im Bild
      st.tapZoomed = true;
      zoomTo(z, qx - ux * z, qy - uy * z);
      return true;
    }
    const onLayout = () => { if (vp === st) layoutVideo(); };
    addEventListener('resize', onLayout);
    st.cleanup.push(() => removeEventListener('resize', onLayout));
    // Größe des Players selbst beobachten (kommt auf Android oft erst NACH dem resize-Ereignis an)
    try { const ro = new ResizeObserver(onLayout); ro.observe(wrap); st.cleanup.push(() => ro.disconnect()); } catch (e) {}
    const onFsLayout = () => { onLayout(); setTimeout(onLayout, 120); setTimeout(onLayout, 450); };
    document.addEventListener('fullscreenchange', onFsLayout);
    st.cleanup.push(() => document.removeEventListener('fullscreenchange', onFsLayout));
    layoutVideo();

