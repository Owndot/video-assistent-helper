    // ----- Gesten (wie Just Player) -----
    const pts = new Map();
    let g = null, pinch = null, lastTapT = 0, lastZone = 9;
    gest.addEventListener('pointerdown', (e) => {
      stop(e);
      pts.set(e.pointerId, { x: e.clientX, y: e.clientY });
      try { gest.setPointerCapture(e.pointerId); } catch (err) {}
      if (pts.size === 2) {
        const a = Array.from(pts.values());
        if (g) clearTimeout(g.holdTimer);
        if (g && g.seekOn) seekAbort(g);   // zweiter Finger mitten im Wischen: Wischen abbrechen, alles wie vorher
        pinch = { d0: Math.hypot(a[0].x - a[1].x, a[0].y - a[1].y) || 1, d: 0, z0: st.zoom,
          mx: (a[0].x + a[1].x) / 2, my: (a[0].y + a[1].y) / 2, px: st.panX, py: st.panY };
        g = null; st.panFree = false;
        return;
      }
      const m = M();
      const Wd = wrap.clientWidth || innerWidth;
      // ganz am Rand beginnt die Zurück-Geste von Android – dort keine Player-Geste starten
      const edge = e.clientX < 28 || e.clientX > Wd - 28;
      // oben (15 % der Höhe, mindestens 48 Pixel) zieht man die Schnelleinstellungen herunter – dort keine Helligkeit/Lautstärke
      const Hd = wrap.clientHeight || innerHeight;
      // unten (ebenso 15 %) beginnt die Home-Wischgeste von Android – dort ebenfalls nicht
      const top = e.clientY < Math.max(Hd * CONFIG.topGuard, 48) ||
        e.clientY > Hd - Math.max(Hd * (CONFIG.bottomGuard == null ? 0.15 : CONFIG.bottomGuard), 48);
      g = { x: e.clientX, y: e.clientY, t: Date.now(), mode: '', time: m.currentTime, vol: st.level, bright: st.bright, delta: 0, edge: edge, top: top, holdTimer: 0 };
      // Lange drücken = schneller, solange gedrückt (wie Just Player)
      const gg = g;
      gg.holdTimer = setTimeout(() => {
        if (g !== gg || gg.mode || st.locked || pts.size !== 1 || menuOpen()) return;
        gg.mode = 'hold';
        M().playbackRate = CONFIG.holdSpeed;
        showInd(String(CONFIG.holdSpeed).replace('.', ',') + '× schneller', null, true);
      }, 450);
    });
    gest.addEventListener('pointermove', (e) => {
      if (!pts.has(e.pointerId)) return;
      pts.set(e.pointerId, { x: e.clientX, y: e.clientY });
      if (st.locked) return;
      if (pinch && pts.size >= 2) {
        const a = Array.from(pts.values());
        pinch.d = Math.hypot(a[0].x - a[1].x, a[0].y - a[1].y);
        st.zoom = clamp(pinch.z0 * pinch.d / pinch.d0, 1, zoomMax());
        st.tapZoomed = false;
        st.panX = pinch.px + ((a[0].x + a[1].x) / 2 - pinch.mx);
        st.panY = pinch.py + ((a[0].y + a[1].y) / 2 - pinch.my);
        layoutVideo();
        return;
      }
      if (!g) return;
      if (g.mode === 'hold') return;
      const W = wrap.clientWidth || innerWidth, H = wrap.clientHeight || innerHeight;
      const dx = e.clientX - g.x, dy = e.clientY - g.y;
      if (!g.mode) {
        if (Math.abs(dx) < 14 && Math.abs(dy) < 14) return;
        clearTimeout(g.holdTimer);
        if (g.edge) { g.mode = 'none'; return; }
        if (st.zoom > 1.01 && !g.top) {
          // vergrößert: ein Finger verschiebt das Bild (wie Telegram) – Helligkeit/Lautstärke/Spulen erst wieder bei 1×
          g.mode = 'pan'; g.px0 = st.panX; g.py0 = st.panY; g.vs = [];
          vid.classList.remove('vzan'); st.panFree = true;
        } else g.mode = Math.abs(dx) > Math.abs(dy) ? 'seek' : (g.top ? 'none' : (g.x > W / 2
          ? (HIDE_NO_HELPER && !(st.sys && st.sys.vol) ? 'none' : 'vol')            // Lautstärke nur mit Helfer (Kadri)
          : (HIDE_NO_HELPER && !(st.sys && st.sys.bright) ? 'none' : 'bright')));   // Helligkeit nur mit Helfer (Kadri)
        if (g.mode === 'none') return;
        // ab hier zählt jede Bewegung gleich (kein Sprung um die Anfangsstrecke); an den Enden sofort umkehrbar
        g.ly = e.clientY;
        g.v = g.mode === 'vol' ? st.level : st.bright;   // aus "Auto" heraus: von der aktuellen Helligkeit weiter
      }
      if (g.mode === 'pan') {
        const lim = panLimits(st.zoom);
        const rub = (v, l) => (v > l ? l + (v - l) / 3 : v < -l ? -l + (v + l) / 3 : v);   // über die Kante: ⅓ Weg
        st.panX = rub(g.px0 + dx, lim[0]);
        st.panY = rub(g.py0 + dy, lim[1]);
        g.vs.push([performance.now(), e.clientX, e.clientY]);
        if (g.vs.length > 6) g.vs.shift();
        layoutVideo();
        return;
      }
      const m = M();
      if (g.mode === 'seek') {
        // Wischen übers Video (wie Samsung Internet): Das Video hält an, das große Bild springt an die Stelle unter dem
        // Wisch (immer nur ein Sprung auf einmal), die Zahlen zeigen genau den Sprung, der passiert. Loslassen: dort
        // weiter (lief es vorher, läuft es wieder). Zurück zum Ausgangspunkt („+0 s“) = nichts ändern.
        // Ganze Breite = 90 s; bei kurzen Videos die Länge (mindestens 20 s) – so geht es bei Reels nicht zu schnell
        const d = m.duration, fin = isFinite(d) && d > 0;
        if (!g.seekOn) {
          g.seekOn = true; g.wasPlaying = !m.paused; g.edgeHit = 0;
          st.swipeHold = true;
          if (g.wasPlaying) { try { m.pause(); } catch (err) {} }
        }
        const span = fin ? clamp(d, 20, 90) : 90;
        g.delta = dx / W * span;
        const to = fin ? clamp(g.time + g.delta, 0, Math.max(0, d - 0.5)) : Math.max(0, g.time + g.delta);
        const edge = fin && to <= 0 ? -1 : fin && to >= d - 0.5 ? 1 : 0;   // am Anfang/Ende: einmal kurz vibrieren
        if (edge && edge !== g.edgeHit) { try { if (CONFIG.seekBuzz !== false && navigator.vibrate && touchFresh()) navigator.vibrate(10); } catch (err) {} }
        g.edgeHit = edge;
        g.to = to;
        showSeek(to, d, to - g.time);
        if (st.liveTo) st.liveTo(to);   // das große Bild springt mit (das Video steht ja)
      } else if (g.mode === 'vol' || g.mode === 'bright') {
        // gut ein Drittel der Bildschirmhöhe = 0 → 100 % (im Querformat mindestens 180 Pixel)
        const range = Math.max(H * 0.36, 180);
        const step = -(e.clientY - g.ly) / range;
        g.ly = e.clientY;
        if (g.mode === 'vol') {
          g.v = clamp(g.v + step, 0, 1);
          showSide('vol', applyLevel(g.v), 1);
        } else {
          const canAuto = !!(st.sys && st.sys.bright);
          g.v = clamp(g.v + step, canAuto ? -AUTO_OVER - 0.04 : 0.15, 1);   // ohne Helfer: höchstens auf 15 % abdunkeln
          if (canAuto && g.v < -AUTO_OVER) {
            setAutoBright();
            showSide('bright', 0, 1, 'Auto');
          } else if (canAuto && st.autoB && g.v < 0) {
            showSide('bright', 0, 1, 'Auto');   // aus Auto wieder hoch: erst ab 0 % wird es wieder fest
          } else {
            applyBright(Math.max(0, g.v));
            showSide('bright', st.bright, 1);
          }
        }
      }
    });
    // Wischen übers Video abbrechen (zweiter Finger …): zurück an den Ausgangspunkt, weiterlaufen wie vorher
    function seekAbort(cur) {
      hideSeek();
      if (st.liveEnd) st.liveEnd(true);
      st.swipeHold = false;
      if (cur.wasPlaying) M().play().catch(() => {});
    }
    st.cleanup.push(() => { st.swipeHold = false; });
    const endGest = (e) => {
      const had = pts.has(e.pointerId);
      pts.delete(e.pointerId);
      if (!had) return;
      if (pinch) {
        if (pts.size === 0) {
          if (st.zoom < 1.08) { st.zoom = 1; st.panX = st.panY = 0; layoutVideo(); }   // fast 1×: einrasten
          pinch = null;
        }
        return;
      }
      if (!g) return;
      const cur = g;
      g = null;
      clearTimeout(cur.holdTimer);
      if (cur.mode === 'hold') { M().playbackRate = st.speed; hideInd(); return; }
      if (cur.mode === 'pan') {
        // Schwung: in Wischrichtung weiter gleiten (aus den letzten ~100 ms), dann sanft an der Kante halten
        st.panFree = false;
        const v = cur.vs, a = v[0], z = v[v.length - 1];
        let px = st.panX, py = st.panY;
        if (a && z && z[0] - a[0] > 10 && performance.now() - z[0] < 80) {
          const dt = z[0] - a[0];
          px += clamp((z[1] - a[1]) / dt, -4, 4) * 160;
          py += clamp((z[2] - a[2]) / dt, -4, 4) * 160;
        }
        zoomTo(st.zoom, px, py);
        return;
      }
      if (cur.mode === 'seek') {
        const m = M();
        setTimeout(hideSeek, 350);   // kurz stehen lassen, dann weich ausblenden
        const to = cur.to != null ? cur.to : cur.time;
        // abbrechen: Android hat die Geste übernommen – oder zurück am Ausgangspunkt
        const cancel = e.type === 'pointercancel' || Math.abs(to - cur.time) < 0.5;
        if (st.liveEnd) st.liveEnd(cancel);
        if (!cancel) { st.userSeekAt = Date.now(); try { m.currentTime = to; } catch (err) {} }
        st.swipeHold = false;
        if (cur.wasPlaying) m.play().catch(() => {});
        return;
      }
      if (cur.mode) return;
      // kein Wischen = Tippen. Offenes Menü? Dann nur schließen.
      if (menuOpen()) { closeMenu(); return; }
      regainFs();   // holt ein verlorenes Vollbild zurück
      const W = wrap.clientWidth || innerWidth, now = Date.now();
      if (st.locked) {
        wrap.classList.add('ctl2');
        clearTimeout(st.u2);
        st.u2 = setTimeout(() => wrap.classList.remove('ctl2'), 2500);
        return;
      }
      const zone = cur.x < W * 0.35 ? -1 : cur.x > W * 0.65 ? 1 : 0;
      // Serie: nach einem Doppeltipp zählt jeder weitere schnelle Tipp auf derselben Seite 10 s dazu
      if (zone && st.skipRun && st.skipRun.zone === zone && now < st.skipRun.until) {
        seekBy(zone * SKIP());
        st.skipRun.total += SKIP(); st.skipRun.until = now + 650;
        showSkipFx(zone, st.skipRun.total);
        return;
      }
      if (now - lastTapT < 300 && zone === lastZone) {
        lastTapT = 0;
        // Doppeltipp: der erste Tipp hat die Leisten schon umgeschaltet – zurück auf den Stand davor
        if (wrap.classList.contains('ctl') !== st.tapCtl) { if (st.tapCtl) showControls(); else { wrap.classList.remove('ctl'); clearTimeout(st.hideTimer); } }
        if (zone) {
          seekBy(zone * SKIP());
          st.skipRun = { zone: zone, total: SKIP(), until: now + 650 };
          showSkipFx(zone, SKIP());
        }
        else tapZoom(cur.x, cur.y) || togglePlay();
        return;
      }
      lastTapT = now;
      lastZone = zone;
      // sofort umschalten (wie native Player) statt 280 ms auf einen möglichen zweiten Tipp zu warten. Frisch
      // eingeblendete Knöpfe nehmen 300 ms lang keine Tipps an – sonst träfe der zweite Tipp eines Doppeltipps Play.
      st.tapCtl = wrap.classList.contains('ctl');
      toggleControls();
      if (!st.tapCtl) { wrap.classList.add('ctlnew'); clearTimeout(st.ctlNewT); st.ctlNewT = setTimeout(() => wrap.classList.remove('ctlnew'), 320); }
    };
    gest.addEventListener('pointerup', endGest);
    gest.addEventListener('pointercancel', endGest);
    wrap.addEventListener('contextmenu', (e) => e.preventDefault());

