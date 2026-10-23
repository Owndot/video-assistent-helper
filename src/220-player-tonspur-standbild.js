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

    // Fortschrittsbalken – Spulen auf der Leiste (19.4, neu 19.8). Eigene, große Berührungsfläche statt des
    // Schiebereglers des Browsers. Vorbilder: YouTube (Punkt unter dem Finger, zurück an die alte Stelle = abbrechen)
    // und iPhone (Finger nach unten = feiner, in Stufen, wieder hoch = normal):
    //  - kurz antippen = an diese Stelle springen
    //  - ziehen = die Stelle (großer Punkt im Glas des Designs) steht unter dem Finger; die Leiste wird dick. Über der
    //    Vorschau steht, wie weit es von der alten Stelle weg ist („0:38 · +18 s“).
    //  - Lupe in Stufen (nur bei Videos ab 1,5 min): Finger beim Ziehen unter die Leiste = „Lupe“ (Ausschnitt ~1 min),
    //    noch tiefer = „Fein“ (Ausschnitt ~15 s). Die Leiste zeigt dann nur den Ausschnitt, links/rechts stehen dessen
    //    Grenzen, im Schild „Lupe“/„Fein“, beim Wechsel ein kurzes Vibrieren. Wieder hoch = eine Stufe zurück, auf
    //    Höhe der Leiste = normal (die Stelle gleitet dann beim Weiterziehen wieder unter den Finger, kein Sprung).
    //    Finger an ein Ende der Leiste = der Ausschnitt wandert mit.
    //  - alte Stelle = kleiner Punkt auf der Leiste; dorthin zurück rastet ein (kurzes Vibrieren, „± 0“) – loslassen
    //    dort = nichts ändern
    //  - weit nach oben weg vom Balken = abbrechen (Hinweis erscheint); loslassen dort = zurück an die alte Stelle.
    //    Zurück nach unten = weiter spulen. Übernimmt Android die Geste (Hochwischen vom Rand), ebenfalls abbrechen.
    //    Hochwischen ist kein Spulen: die Stelle folgt dem Finger erst, wenn er ~10 px zur Seite gezogen hat.
    const barHit = h('div', { class: 'vbarhit', 'aria-hidden': 'true' });
    const barMark = h('i', { class: 'vbarmk' });
    const barKnob = h('i', { class: 'vknob g' }, h('b'));
    bar.parentNode.append(barHit, barMark, barKnob);
    bar.tabIndex = -1;
    const scr = { on: false, id: 0, x0: 0, y0: 0, lx: 0, t0: 0, t: 0, raw: 0, moved: false, first: true, mode: '', snap: false, away: false,
      lv: 0, z: 0, ws: 0, zt: 0, zx: 0, off: 0, pan: 0, pt: 0 };
    const buzz = () => { try { if (navigator.vibrate && touchFresh()) navigator.vibrate(12); } catch (e) {} };
    // Abstand zur alten Stelle: „+12 s“, „−1:05“
    const delta = (x) => { const a = Math.round(Math.abs(x)); return (x < 0 ? '−' : '+') + (a < 60 ? a + ' s' : fmtTime(a)); };
    const tenth = (t) => fmtTime(t) + ',' + Math.floor((t % 1) * 10);   // „2:11,4“ (für „Fein“)
    const durOk = () => { const d = M().duration; return isFinite(d) && d > 0 ? d : 0; };
    const barBack = () => { const m = M(), d = durOk(); if (d) { bar.value = String(Math.round(m.currentTime / d * 1000)); paintBar(bar.value / 1000); tCur.textContent = fmtTime(m.currentTime); } };
    const geom = () => { const br = bar.getBoundingClientRect(); return { br: br, L: br.left + 7, w: Math.max(60, br.width - 14) }; };
    // Ausschnitt je Stufe in Sekunden (0 = ganze Länge): Lupe ≈ Länge ÷ 6 (20–60 s), Fein = Lupe ÷ 4 (mind. 5 s)
    const spanFor = (lv, d) => { if (!lv || d < 90) return 0; const z1 = clamp(d / 6, 20, 60); return lv === 1 ? z1 : Math.max(5, z1 / 4); };
    // Stufe aus der Höhe des Fingers unter der Leiste (Querformat: weniger Platz → Stufen näher beieinander)
    function levelFor(dy) {
      const room = Math.max(60, innerHeight - scr.y0 - 8);
      const s1 = Math.min(42, room * 0.32), s2 = Math.min(92, room * 0.68), hy = 8;
      const cur = scr.lv;
      if (dy > s2 + (cur === 2 ? -hy : hy)) return 2;
      if (dy > s1 + (cur >= 1 ? -hy : hy)) return 1;
      return 0;
    }
    const posX = (p) => { const g = geom(), rr = bar.parentNode.getBoundingClientRect(); return g.L - rr.left + clamp(p, 0, 1) * g.w; };
    function scrubShow(t) {
      const d = durOk();
      if (!d) return;
      let p = clamp(t / d, 0, 1);
      if (scr.z) {   // Lupe: die Leiste zeigt den Ausschnitt [ws, ws + z]
        p = clamp((t - scr.ws) / scr.z, 0, 1);
        const P1 = (p * 100).toFixed(2);
        bar.style.setProperty('--trk', 'linear-gradient(to right,#000 ' + P1 + '%,rgba(0,0,0,.3) ' + P1 + '%)');   // (umgedreht per invert)
        const m0 = (scr.t0 - scr.ws) / scr.z;
        barMark.style.left = Math.round(posX(m0) - 5) + 'px';
        barMark.classList.toggle('off', m0 < 0 || m0 > 1);
        tCur.textContent = fmtTime(scr.ws); tDur.textContent = fmtTime(scr.ws + scr.z);
      } else {
        paintBar(p);
        barMark.style.left = Math.round(posX(scr.t0 / d) - 5) + 'px';
        barMark.classList.remove('off');
        tCur.textContent = fmtTime(t);
        if (scr.on) tDur.textContent = fmtTime(d);
      }
      bar.value = String(Math.round(p * 1000));
      barKnob.style.left = Math.round(posX(p) - 17) + 'px';
      prevShow(p, t);
      wrap.classList.toggle('scrubx', scr.mode === 'cancel');
      wrap.classList.toggle('scrubsnap', scr.snap && scr.mode !== 'cancel');
      wrap.classList.toggle('zoom', !!scr.z);
      if (scr.mode === 'cancel') pvTime.textContent = tr('Loslassen = abbrechen');
      else if (scr.moved) {
        const tx = (scr.lv === 2 ? tenth(t) : fmtTime(t)) + ' · ' + (scr.snap ? '± 0' : delta(t - scr.t0));
        pvTime.textContent = scr.lv ? tr(scr.lv === 2 ? 'Fein' : 'Lupe') + ' · ' + tx : tx;
      }
    }
    // Stufe wechseln: der Ausschnitt wird so gelegt, dass die aktuelle Stelle unter dem Finger bleibt
    function setLevel(lv) {
      const d = durOk(), z = spanFor(lv, d);
      if (lv === scr.lv || (lv && !z)) return;
      const g = geom(), f = clamp((scr.lx - g.L) / g.w, 0, 1);
      scr.lv = lv; scr.z = z;
      if (z) {
        scr.zt = scr.raw; scr.zx = scr.lx;
        scr.ws = clamp(scr.raw - f * z, 0, Math.max(0, d - z));
        tCur.classList.add('g', 'vtz'); tDur.classList.add('g', 'vtz');
      } else {
        scr.off = scr.raw - f * d;   // zurück zu „normal“: Abstand zum Finger, schrumpft beim Weiterziehen
        tCur.classList.remove('g', 'vtz'); tDur.classList.remove('g', 'vtz');
      }
      buzz();
    }
    // Stelle aus der Fingerposition: normal unter dem Finger, in der Lupe fein im Ausschnitt
    function scrubAt(x, dx) {
      const d = durOk(), g = geom();
      if (scr.z) scr.raw = clamp(scr.zt + (x - scr.zx) * scr.z / g.w, 0, d);
      else {
        if (scr.off) { scr.off *= Math.exp(-Math.abs(dx || 0) / 20); if (Math.abs(scr.off) < 0.05) scr.off = 0; }
        scr.raw = clamp(clamp((x - g.L) / g.w, 0, 1) * d + scr.off, 0, d);
      }
      // Einrasten an der alten Stelle (erst nachdem du einmal weg warst) – etwa 5 px der Leiste
      const near = Math.abs(scr.raw - scr.t0) < Math.max(0.2, (scr.z || d) / g.w * 5);
      if (!near) scr.away = true;
      if (near && scr.away && !scr.snap) buzz();
      scr.snap = near && scr.away;
      scr.t = scr.snap ? scr.t0 : scr.raw;
      scrubShow(scr.t);
    }
    // Lupe am Ende der Leiste: der Ausschnitt wandert mit (je weiter am Rand, desto schneller)
    function panTick(ts) {
      scr.pan = 0;
      if (!scr.on || !scr.z || scr.mode === 'cancel') return;
      const g = geom(), E = 28, x = scr.lx;
      const dep = x > g.L + g.w - E ? (x - (g.L + g.w - E)) / E : x < g.L + E ? -(g.L + E - x) / E : 0;
      if (!dep) return;
      const dt = scr.pt ? Math.min(50, ts - scr.pt) : 16;
      scr.pt = ts;
      const d = durOk(), v = clamp(dep, -1.5, 1.5) * scr.z * 0.6 * dt / 1000;   // bis ~0,6 Ausschnitte pro Sekunde
      scr.zt += v;
      scr.ws = clamp(scr.ws + v, 0, Math.max(0, d - scr.z));
      scrubAt(x, 0);
      scr.pan = requestAnimationFrame(panTick);
    }
    function scrubEnd(how) {   // how: 'seek' | 'tap' | 'cancel'
      if (!scr.on) return;
      scr.on = false;
      if (scr.pan) { cancelAnimationFrame(scr.pan); scr.pan = 0; }
      scr.z = 0; scr.lv = 0; scr.off = 0;
      wrap.classList.remove('thick', 'scrubx', 'scrubsnap', 'zoom');
      barMark.classList.remove('off');
      tCur.classList.remove('g', 'vtz'); tDur.classList.remove('g', 'vtz');
      const m = M(), d = durOk();
      uiLast.cur = ''; uiLast.dur = ''; tDur.textContent = fmtTime(d);   // Zeiten wieder normal
      if (how === 'seek' && scr.snap) how = 'cancel';   // an der alten Stelle losgelassen: nichts ändern
      if (how === 'cancel') { liveEnd(true); barBack(); }
      else if (d) {
        liveEnd(false);
        st.userSeekAt = Date.now();
        try { m.currentTime = clamp(scr.t, 0, Math.max(0, d - 0.2)); } catch (e) {}
        tCur.textContent = fmtTime(scr.t);
      }
      st.barDrag = false;
      prevHide();
      scheduleHide();
    }
    barHit.addEventListener('pointerdown', (e) => {
      stop(e);
      const d = durOk();
      if (!d || scr.on) return;
      try { barHit.setPointerCapture(e.pointerId); } catch (x) {}
      const m = M();
      Object.assign(scr, { on: true, id: e.pointerId, x0: e.clientX, y0: e.clientY, lx: e.clientX, t0: m.currentTime, t: m.currentTime, raw: m.currentTime,
        moved: false, first: true, mode: '', snap: false, away: false, lv: 0, z: 0, off: 0, pt: 0 });
      barMark.style.left = Math.round(posX(m.currentTime / d) - 5) + 'px';
      barKnob.style.left = Math.round(posX(m.currentTime / d) - 17) + 'px';
      st.barDrag = true; clearTimeout(st.hideTimer);
      if (!liveMode()) { pv.preload = 'auto'; pvs.autoGrid = true; if (prevLoad() && pv.readyState >= 1) startGrid(); }
    });
    barHit.addEventListener('pointermove', (e) => {
      if (!scr.on || e.pointerId !== scr.id) return;
      const d = durOk();
      if (!d) return;
      const dy = e.clientY - scr.y0, dx = e.clientX - scr.lx;
      scr.lx = e.clientX;
      if (!scr.moved) {
        if (Math.hypot(e.clientX - scr.x0, dy) < 8) return;
        scr.moved = true;
        wrap.classList.add('thick');
      }
      if (dy < -Math.max(70, geom().br.height * 1.5)) { scr.mode = 'cancel'; scrubShow(scr.t0); return; }
      scr.mode = '';
      if (scr.first && Math.abs(e.clientX - scr.x0) < 10 && dy < 30) { scrubShow(scr.t); return; }   // (noch) Hochwischen
      if (scr.first) { scr.first = false; scrubAt(e.clientX, 0); }   // erst unter den Finger, dann ggf. Lupe
      setLevel(levelFor(dy));
      scrubAt(e.clientX, dx);
      if (scr.z && !scr.pan) { scr.pt = 0; scr.pan = requestAnimationFrame(panTick); }
    });
    barHit.addEventListener('pointerup', (e) => {
      if (!scr.on || e.pointerId !== scr.id) return;
      if (!scr.moved) {   // kurz angetippt: an diese Stelle
        const g = geom(), d = durOk();
        if (d) scr.t = clamp((e.clientX - g.L) / g.w, 0, 1) * d;
        scrubShow(scr.t);
        scrubEnd('tap');
        return;
      }
      scrubEnd(scr.mode === 'cancel' ? 'cancel' : 'seek');
    });
    barHit.addEventListener('pointercancel', (e) => { if (scr.on && e.pointerId === scr.id) scrubEnd('cancel'); });   // Android hat die Geste übernommen
    barHit.addEventListener('contextmenu', (e) => e.preventDefault());   // langes Drücken: kein Menü
    barHit.addEventListener('click', stop);

