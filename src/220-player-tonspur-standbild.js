    // ----- Tonspur (Sprache) bei Streams mit mehreren Tonspuren -----
    function audioItems() {
      const items = [];
      const hls = st.hls;
      if (st.mode === 'own' && hls && hls.audioTracks && hls.audioTracks.length > 1) {
        hls.audioTracks.forEach((t, i) => items.push({ label: t.name || (t.lang || '').toUpperCase() || 'Spur ' + (i + 1), sub: t.lang && t.name ? t.lang.toUpperCase() : '',
          cur: hls.audioTrack === i, fn: () => { try { hls.audioTrack = i; } catch (e) {} toast('Tonspur: ' + (t.name || t.lang || i + 1)); } }));
      } else {
        const at = M().audioTracks;   // nur wo der Browser das kann
        if (at && at.length > 1) {
          for (let i = 0; i < at.length; i++) {
            const t = at[i];
            items.push({ label: t.label || (t.language || '').toUpperCase() || 'Spur ' + (i + 1), cur: !!t.enabled,
              fn: () => { for (let j = 0; j < at.length; j++) at[j].enabled = j === i; } });
          }
        }
      }
      return items;
    }

    // ----- Standbild speichern -----
    function snapshot() {
      const v = vid;
      if (!v.videoWidth || v.readyState < 2) { toast('Noch kein Bild da'); return; }
      const c = document.createElement('canvas');
      c.width = v.videoWidth; c.height = v.videoHeight;
      const g2 = c.getContext('2d');
      try { g2.drawImage(v, 0, 0); } catch (e) { toast('Diese Seite erlaubt kein Standbild'); return; }
      // gesperrt (fremder Server ohne Freigabe) oder leer (Firefox auf dem Handy)?
      try {
        const t = document.createElement('canvas'); t.width = 8; t.height = 8;
        const tx = t.getContext('2d'); tx.drawImage(c, 0, 0, 8, 8);
        const d = tx.getImageData(0, 0, 8, 8).data;
        let any = false; for (let i = 3; i < d.length; i += 4) if (d[i]) { any = true; break; }
        if (!any) { toast(FF_MOBILE ? 'In Firefox auf dem Handy leider nicht möglich' : 'Kein Bild bekommen'); return; }
      } catch (e) { toast('Diese Seite erlaubt kein Standbild (Videoserver sperrt es)'); return; }
      const name = ((pageTitle() || pageHost()).replace(/[\\/:*?"<>|]+/g, ' ').trim().slice(0, 60) || 'Video') + ' ' + fmtTime(M().currentTime).replace(/:/g, '-') + '.jpg';
      const go = (href, rev) => {
        const a = document.createElement('a');
        a.href = href; a.download = name; a.style.display = 'none';
        wrap.append(a); a.click(); a.remove();
        if (rev) setTimeout(() => URL.revokeObjectURL(href), 30000);
        wrap.classList.remove('flash'); void wrap.offsetWidth; wrap.classList.add('flash');
        setTimeout(() => wrap.classList.remove('flash'), 400);
        toast('Standbild gespeichert (Downloads)');
      };
      try {
        if (GECKO) { go(c.toDataURL('image/jpeg', 0.92), false); return; }   // Firefox lädt Blob-Adressen des Skripts nicht
        c.toBlob((b) => { if (b) go(URL.createObjectURL(b), true); else toast('Kein Bild bekommen'); }, 'image/jpeg', 0.92);
      } catch (e) { toast('Diese Seite erlaubt kein Standbild'); }
    }
    // Drehen: ins Querformat (dafür echter Vollbildmodus) – nochmal tippen: zurück ins Hochformat
    on(bRot, () => {
      st.selfTurnAt = Date.now();   // merken: diese Drehung kam von dir (nicht von der Zurück-Taste)
      if (st.orient === 'landscape') {
        if (CONFIG.startFullscreen) {
          // im Vollbild bleiben (ggf. zurückholen), nur ins Hochformat drehen
          const toPortrait = () => {
            st.orient = 'portrait';
            lockO('portrait');
          };
          if (isFs()) toPortrait(); else enterFs().then(() => { if (isFs()) st.fsStart = true; toPortrait(); });
        } else exitFs();
        return;
      }
      const toLandscape = () => {
        if (!isFs()) { toast('Drehen ist hier nicht möglich – dreh das Handy mit aktivierter Auto-Drehung'); return; }
        st.orient = 'landscape';
        lockO('landscape');
      };
      // Schon im Vollbild? Dann nur drehen – Vollbild erneut anfordern kann es auf Android kurz beenden
      if (isFs()) toLandscape();
      else enterFs().then(() => { if (isFs() && CONFIG.startFullscreen) st.fsStart = true; toLandscape(); });
    });
    on(bLock, () => { st.locked = true; wrap.classList.add('locked'); });
    // Entsperren nur mit Absicht (nicht aus Versehen): 1 Sekunde gedrückt halten – der Ring füllt sich – oder zweimal schnell tippen
    (function unlockGesture() {
      const ring = document.createElementNS(NS, 'svg');
      ring.setAttribute('class', 'ulring'); ring.setAttribute('viewBox', '0 0 52 52');
      const c = document.createElementNS(NS, 'circle'); c.setAttribute('cx', '26'); c.setAttribute('cy', '26'); c.setAttribute('r', '25');
      ring.append(c); bUnlock.append(ring);
      let holdT = 0, lastTap = 0;
      const doUnlock = () => {
        clearTimeout(holdT); bUnlock.classList.remove('hold');
        if (!st.locked) return;
        st.locked = false; wrap.classList.remove('locked', 'ctl2'); showControls();
        try { navigator.vibrate && navigator.vibrate(18); } catch (e) {}
      };
      const keepVisible = () => { clearTimeout(st.u2); st.u2 = setTimeout(() => wrap.classList.remove('ctl2'), 2500); };
      bUnlock.addEventListener('pointerdown', (e) => { stop(e); clearTimeout(st.u2); bUnlock.classList.add('hold'); clearTimeout(holdT); holdT = setTimeout(doUnlock, 950); });
      const cancel = () => { clearTimeout(holdT); bUnlock.classList.remove('hold'); if (st.locked) keepVisible(); };
      bUnlock.addEventListener('pointerup', (e) => {
        stop(e); cancel();
        if (!st.locked) return;
        const now = Date.now();
        if (now - lastTap < 450) { lastTap = 0; doUnlock(); return; }
        lastTap = now;   // ein einzelner Tipp tut nichts (kein Hinweis)
      });
      bUnlock.addEventListener('pointercancel', cancel);
      bUnlock.addEventListener('pointerleave', cancel);
      bUnlock.addEventListener('click', stop);
      bUnlock.addEventListener('contextmenu', (e) => e.preventDefault());
    })();

    // Fortschrittsbalken – Spulen auf der Leiste. Eigene, große Berührungsfläche statt des
    // Schiebereglers des Browsers. Vorbilder: YouTube (Punkt unter dem Finger) und iPhone (Finger nach unten = feiner,
    // in Stufen, wieder hoch = normal):
    //  - kurz antippen = an diese Stelle springen
    //  - ziehen = die Stelle (großer Punkt im Glas des Designs) steht unter dem Finger; die Leiste wird dick. Über der
    //    Vorschau steht, wie weit es von der laufenden Stelle weg ist („0:38 · +18 s“) – das Video läuft beim Spulen
    //    weiter. Kein Einrasten: der Punkt folgt immer nur dem Finger.
    //  - Lupe (nur bei Videos ab 1,5 min, auch bei Live, abschaltbar): Finger beim Ziehen unter die Leiste = „Lupe“
    //    (Ausschnitt ~1 min). Die Leiste zeigt dann nur den Ausschnitt, links/rechts stehen dessen Grenzen, im Schild
    //    „Lupe“, beim Wechsel ein kurzes Vibrieren (abschaltbar). Wieder hoch auf die Leiste = normal (die Stelle gleitet
    //    beim Weiterziehen wieder unter den Finger). Finger an ein Ende der Leiste = der Ausschnitt wandert mit.
    //  - weit nach oben weg vom Balken = abbrechen („Abbrechen“, keine kleine Vorschau – das große Video zeigt
    //    die laufende Stelle); loslassen dort = nichts ändern. Zurück nach unten = weiter spulen.
    //  - Live-Stream mit Zurückspulen: die Leiste ist der Bereich, den der Stream zurückhält; „−5:30“ = so weit hinter
    //    live, ganz rechts = wieder live.
    //  Sicher: Hochwischen ist kein Spulen (erst ~10 px zur Seite). Abgebrochen wird, wenn Android die Geste
    //  übernimmt, der Finger „verloren“ geht, 15 s lang nichts vom Finger kommt, du die App wechselst oder ein anderes
    //  Video zu laufen beginnt (Facebook lädt Reels neu, das nächste startet …). Gezeichnet wird einmal pro Bild.
    const barHit = h('div', { class: 'vbarhit', 'aria-hidden': 'true' });
    const barKnob = h('i', { class: 'vknob g' }, h('b'));
    bar.parentNode.append(barHit, barKnob);
    bar.tabIndex = -1;
    const scr = { on: false, id: 0, x0: 0, y0: 0, lx: 0, my: 0, mx: 0, raf: 0, t0: 0, t: 0, raw: 0, moved: false, first: true, mode: '',
      r: null, key: '', lv: 0, z: 0, ws: 0, zt: 0, zx: 0, off: 0, pan: 0, pt: 0, tk: 0, last: 0, wd: 0 };
    const buzz = (ms) => seekBuzz(ms || 12);
    // Abstand: „+12 s“, „−1:05“
    const delta = (x) => { const a = Math.round(Math.abs(x)); return (x < 0 ? '−' : '+') + (a < 60 ? a + ' s' : fmtTime(a)); };
    const barBack = () => {
      const m = M(), r = seekRange(m);
      if (!r) return;
      const p = clamp((m.currentTime - r.a) / r.len, 0, 1);
      bar.value = String(Math.round(p * 1000)); paintBar(p);
      tCur.textContent = r.live ? behind(r, m.currentTime) : fmtTime(m.currentTime);
    };
    const geom = () => { const br = bar.getBoundingClientRect(); return { br: br, L: br.left + 7, w: Math.max(60, br.width - 14) }; };
    // Ausschnitt der Lupe in Sekunden (0 = ganzer Bereich): Länge ÷ 6, 20–60 s. Nicht bei Videos unter 1,5 min.
    const lupeWhy = (r) => (CONFIG.seekZoom === false ? 'aus (Einstellung)' : r.len < 90 ? 'nein – ' + (r.live ? 'Live-Bereich' : 'Video') + ' kürzer als 1:30 (' + fmtTime(r.len) + ')' : 'ja');
    const spanFor = (lv, len) => (!lv || len < 90 || CONFIG.seekZoom === false ? 0 : clamp(len / 6, 20, 60));
    // Lupe an, wenn der Finger ein Stück unter der Leiste ist (Querformat: weniger Platz → näher), 8 px Spielraum
    function levelFor(dy) {
      const room = Math.max(60, innerHeight - scr.y0 - 8);
      const s1 = Math.min(42, room * 0.4), hy = 8;
      if (dy > s1 + (scr.lv ? -hy : hy)) return 1;
      return 0;
    }
    const posX = (p) => { const g = geom(), rr = bar.parentNode.getBoundingClientRect(); return g.L - rr.left + clamp(p, 0, 1) * g.w; };
    // Läuft noch dasselbe Video? (Quelle, Art, Länge – bei Live nur Quelle und Art)
    const vidKey = () => { const m = M(), r = seekRange(m); return st.mode + '|' + (m.currentSrc || '') + '|' + (r ? (r.live ? 'L' : Math.round(r.len)) : '-'); };
    const tlabel = (t) => (scr.r && scr.r.live ? behind(scr.r, t) : fmtTime(t));
    function scrubShow(t) {
      const r = scr.r;
      if (!r) return;
      let p = clamp((t - r.a) / r.len, 0, 1);
      if (scr.z) {   // Lupe: die Leiste zeigt den Ausschnitt [ws, ws + z]
        p = clamp((t - scr.ws) / scr.z, 0, 1);
        const P1 = (p * 100).toFixed(2);
        bar.style.setProperty('--trk', 'linear-gradient(to right,#000 ' + P1 + '%,rgba(0,0,0,.3) ' + P1 + '%)');   // (umgedreht per invert)
        tCur.textContent = r.live ? behind(r, scr.ws) : fmtTime(scr.ws);
        tDur.textContent = r.live ? behind(r, scr.ws + scr.z) : fmtTime(scr.ws + scr.z);
      } else {
        paintBar(p);
        tCur.textContent = r.live ? behind(r, t) : fmtTime(t);
        if (scr.on) tDur.textContent = r.live ? tr('LIVE') : fmtTime(r.b);
      }
      bar.value = String(Math.round(p * 1000));
      barKnob.style.left = Math.round(posX(p) - 17) + 'px';
      prevShow(p, t);
      wrap.classList.toggle('scrubx', scr.mode === 'cancel');
      wrap.classList.toggle('zoom', !!scr.z);
      if (scr.mode === 'cancel') pvTime.textContent = tr('Abbrechen');
      else if (scr.moved) {
        const tx = r.live ? behind(r, t) : tlabel(t) + ' · ' + delta(t - M().currentTime);
        pvTime.textContent = scr.lv ? tr('Lupe') + ' · ' + tx : tx;
      }
    }
    // Stufe wechseln: der Ausschnitt wird so gelegt, dass die aktuelle Stelle unter dem Finger bleibt
    function setLevel(lv) {
      const r = scr.r, z = spanFor(lv, r.len);
      if (lv === scr.lv || (lv && !z)) return;
      const g = geom(), f = clamp((scr.lx - g.L) / g.w, 0, 1);
      scr.lv = lv; scr.z = z;
      if (z) {
        scr.zt = scr.raw; scr.zx = scr.lx;
        scr.ws = clamp(scr.raw - f * z, r.a, Math.max(r.a, r.b - z));
        tCur.classList.add('g', 'vtz'); tDur.classList.add('g', 'vtz');
      } else {
        scr.off = scr.raw - (r.a + f * r.len);   // zurück zu „normal“: Abstand zum Finger, schrumpft beim Weiterziehen
        tCur.classList.remove('g', 'vtz'); tDur.classList.remove('g', 'vtz');
      }
      buzz();
    }
    // Stelle aus der Fingerposition: normal unter dem Finger, in der Lupe fein im Ausschnitt
    function scrubAt(x, dx) {
      const r = scr.r, g = geom();
      if (scr.z) scr.raw = clamp(scr.zt + (x - scr.zx) * scr.z / g.w, r.a, r.b);
      else {
        if (scr.off) { scr.off *= Math.exp(-Math.abs(dx || 0) / 20); if (Math.abs(scr.off) < 0.05) scr.off = 0; }
        scr.raw = clamp(r.a + clamp((x - g.L) / g.w, 0, 1) * r.len + scr.off, r.a, r.b);
      }
      scr.t = scr.raw;
      scrubShow(scr.t);
    }
    // Im Abbrechen-Bereich läuft die Anzeige mit dem Video mit (auch bei stillem Finger)
    function cancelTick() {
      clearInterval(scr.tk); scr.tk = 0;
      if (!scr.on || scr.mode !== 'cancel') return;
      scr.tk = setInterval(() => {
        if (!scr.on || scr.mode !== 'cancel') { clearInterval(scr.tk); scr.tk = 0; return; }
        scrubShow(M().currentTime);
      }, 250);
    }
    // Lupe am Ende der Leiste: der Ausschnitt wandert mit (je weiter am Rand, desto schneller)
    function panTick(ts) {
      scr.pan = 0;
      if (!scr.on || !scr.z || scr.mode === 'cancel') return;
      const g = geom(), E = 28, x = scr.lx, r = scr.r;
      const dep = x > g.L + g.w - E ? (x - (g.L + g.w - E)) / E : x < g.L + E ? -(g.L + E - x) / E : 0;
      if (!dep) return;
      const dt = scr.pt ? Math.min(50, ts - scr.pt) : 16;
      scr.pt = ts;
      const v = clamp(dep, -1.5, 1.5) * scr.z * 0.6 * dt / 1000;   // bis ~0,6 Ausschnitte pro Sekunde
      scr.zt += v;
      scr.ws = clamp(scr.ws + v, r.a, Math.max(r.a, r.b - scr.z));
      scrubAt(x, 0);
      scr.pan = requestAnimationFrame(panTick);
    }
    // Nach dem Loslassen: Lade-Kreis, bis das Video an der neuen Stelle steht; landet es deutlich woanders (manche
    // Streams), einmal neu versuchen
    function seekWatch(m, target) {
      let tries = 0;
      spin.classList.add('on');
      const fin = () => { m.removeEventListener('seeked', onSk); clearTimeout(tm); if (!m.seeking && m.readyState >= 3) spin.classList.remove('on'); };
      const onSk = () => {
        if (vp !== st || M() !== m) { fin(); return; }
        if (Math.abs(m.currentTime - target) > 3 && tries < 1) { tries++; st.userSeekAt = Date.now(); try { m.currentTime = target; } catch (e) {} return; }
        fin();
        if (m.readyState < 3) { const ok = () => { spin.classList.remove('on'); }; m.addEventListener('canplay', ok, { once: true }); setTimeout(ok, 8000); }
      };
      m.addEventListener('seeked', onSk);
      const tm = setTimeout(() => { m.removeEventListener('seeked', onSk); if (!m.seeking) spin.classList.remove('on'); }, 10000);
    }
    function scrubEnd(how) {   // how: 'seek' | 'tap' | 'cancel'
      if (!scr.on) return;
      scr.on = false;
      if (scr.pan) { cancelAnimationFrame(scr.pan); scr.pan = 0; }
      if (scr.raf) { cancelAnimationFrame(scr.raf); scr.raf = 0; }
      clearInterval(scr.tk); scr.tk = 0; clearInterval(scr.wd); scr.wd = 0;
      offVisChange(onScrubVis);
      scr.z = 0; scr.lv = 0; scr.off = 0;
      wrap.classList.remove('thick', 'scrubx', 'zoom');
      tCur.classList.remove('g', 'vtz'); tDur.classList.remove('g', 'vtz');
      const m = M(), r = seekRange(m) || scr.r;
      uiLast.cur = ''; uiLast.dur = ''; tDur.textContent = r && !r.live ? fmtTime(r.b) : 'Live';   // Zeiten wieder normal
      if (how !== 'cancel' && vidKey() !== scr.key) { how = 'cancel'; showInd('Video hat gewechselt – Spulen abgebrochen'); }
      if (how === 'cancel' || !r) { liveEnd(true); barBack(); }
      else {
        liveEnd(false);
        st.userSeekAt = Date.now();
        // Live: ganz rechts = wieder live (Stream-Bibliothek kennt die beste Stelle)
        let to = r.live ? (scr.t >= liveEdge(r) - 8 ? Math.min(liveEdge(r), r.b - 3) : scr.t) : clamp(scr.t, 0, Math.max(0, r.b - 0.2));
        to = clamp(to, r.a, r.b);
        if (Math.abs(m.currentTime - to) > 0.25) {
          try { m.currentTime = to; } catch (e) {}
          if (!liveMode()) seekWatch(m, to);
        }
        tCur.textContent = r.live ? behind(r, to) : fmtTime(to);
      }
      st.barDrag = false;
      prevHide();
      scheduleHide();
    }
    function onScrubVis() { if (document.hidden && scr.on) scrubEnd('cancel'); }   // App gewechselt (Anruf, Hochwischen …)
    // eine Fingerbewegung verarbeiten (gesammelt, höchstens einmal pro Bild)
    function moveNow() {
      scr.raf = 0;
      if (!scr.on) return;
      if (vidKey() !== scr.key) { scrubEnd('cancel'); showInd('Video hat gewechselt – Spulen abgebrochen'); return; }
      const x = scr.mx, dy = scr.my - scr.y0, dx = x - scr.lx;
      scr.lx = x;
      if (!scr.moved) {
        if (Math.hypot(x - scr.x0, dy) < 8) return;
        scr.moved = true;
        wrap.classList.add('thick');
      }
      if (dy < -Math.max(70, geom().br.height * 1.5)) { const was = scr.mode; scr.mode = 'cancel'; scrubShow(M().currentTime); if (was !== 'cancel') { cancelTick(); buzz(20); } return; }
      scr.mode = '';
      if (scr.first && Math.abs(x - scr.x0) < 10 && dy < 30) { scrubShow(scr.t); return; }   // (noch) Hochwischen
      if (scr.first) { scr.first = false; scrubAt(x, 0); }   // erst unter den Finger, dann ggf. Lupe
      setLevel(levelFor(dy));
      scrubAt(x, dx);
      if (scr.z && !scr.pan) { scr.pt = 0; scr.pan = requestAnimationFrame(panTick); }
    }
    barHit.addEventListener('pointerdown', (e) => {
      stop(e);
      const m = M(), r = seekRange(m);
      if (!r || scr.on) return;
      try { barHit.setPointerCapture(e.pointerId); } catch (x) {}
      Object.assign(scr, { on: true, id: e.pointerId, x0: e.clientX, y0: e.clientY, lx: e.clientX, mx: e.clientX, my: e.clientY, t0: m.currentTime, t: m.currentTime,
        raw: m.currentTime, moved: false, first: true, mode: '', r: r, key: vidKey(), lv: 0, z: 0, off: 0, pt: 0, last: Date.now() });
      barKnob.style.left = Math.round(posX((m.currentTime - r.a) / r.len) - 17) + 'px';
      st.lupeWhy = lupeWhy(r);
      st.barDrag = true; clearTimeout(st.hideTimer);
      onVisChange(onScrubVis);
      // Notbremse: kommt 15 s lang gar nichts vom Finger (Loslassen verloren), sauber abbrechen
      clearInterval(scr.wd);
      scr.wd = setInterval(() => { if (!scr.on) { clearInterval(scr.wd); scr.wd = 0; return; } if (Date.now() - scr.last > 15000) scrubEnd('cancel'); }, 1000);
      if (!liveMode() && !r.live) { pv.preload = 'auto'; pvs.autoGrid = true; if (prevLoad() && pv.readyState >= 1) startGrid(); }
    });
    barHit.addEventListener('pointermove', (e) => {
      if (!scr.on || e.pointerId !== scr.id) return;
      scr.mx = e.clientX; scr.my = e.clientY; scr.last = Date.now();
      if (!scr.raf) scr.raf = requestAnimationFrame(moveNow);
    });
    barHit.addEventListener('pointerup', (e) => {
      if (!scr.on || e.pointerId !== scr.id) return;
      scr.mx = e.clientX; scr.my = e.clientY;
      if (scr.raf) { cancelAnimationFrame(scr.raf); moveNow(); }   // letzte Bewegung noch verarbeiten
      if (!scr.on) return;
      if (!scr.moved) {   // kurz angetippt: an diese Stelle
        const g = geom(), r = scr.r;
        scr.t = r.a + clamp((e.clientX - g.L) / g.w, 0, 1) * r.len;
        scrubShow(scr.t);
        scrubEnd('tap');
        return;
      }
      scrubEnd(scr.mode === 'cancel' ? 'cancel' : 'seek');
    });
    barHit.addEventListener('pointercancel', (e) => { if (scr.on && e.pointerId === scr.id) scrubEnd('cancel'); });   // Android hat die Geste übernommen
    // Finger „verloren“ (Benachrichtigung, Systemgeste …) ohne Loslassen – nach einem echten Loslassen ist scr.on schon aus
    barHit.addEventListener('lostpointercapture', (e) => { if (scr.on && e.pointerId === scr.id) scrubEnd('cancel'); });
    barHit.addEventListener('contextmenu', (e) => e.preventDefault());   // langes Drücken: kein Menü
    barHit.addEventListener('click', stop);

