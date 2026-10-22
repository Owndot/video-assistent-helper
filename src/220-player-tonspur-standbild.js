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

    // Fortschrittsbalken
    // Hochwischen über die Leiste (Android-Geste: Startbildschirm/Menü) ist kein Spulen. Mild, damit Halten und Ziehen
    // gut geht: sobald der Finger ~10 px zur Seite zieht, gilt es als Spulen (wird nie mehr abgebrochen). Nur deutlich
    // nach oben (40 px) bei kaum seitlicher Bewegung – oder wenn Android die Geste übernimmt (pointercancel) – springt
    // die Leiste zurück
    // Spulen auf der Leiste (19.4) – eigene, große Berührungsfläche statt des Schiebereglers des Browsers:
    //  - kurz antippen = an diese Stelle springen
    //  - ziehen = die Stelle folgt dem Finger; die Leiste wird dabei dick
    //  - Punkt unter dem Finger (19.6): beim Ziehen steht die Stelle genau unter dem Finger (wie YouTube) – nie läuft der
    //    Finger aus dem Bild, Anfang und Ende liegen an den Enden der Leiste.
    //  - fast stillhalten und kriechen (unter ~35 px/s) = fein (bis achtmal genauer, nur bei längeren Videos). Danach
    //    bei normaler Bewegung gleitet die Stelle innerhalb weniger Millimeter wieder unter den Finger (kein Sprung).
    //    Über der Vorschau steht, wie weit es von der alten Stelle weg ist („+0:42“).
    //  - zurück an die alte Stelle (weißer Strich auf der Leiste): rastet ein (kurzes Vibrieren, „± 0“) – loslassen
    //    dort = nichts ändern
    //  - nach oben weg vom Balken = abbrechen (Hinweis erscheint); loslassen dort = zurück an die alte Stelle.
    //    Zurück nach unten = weiter spulen. Übernimmt Android die Geste (Hochwischen vom Rand), ebenfalls abbrechen.
    const barHit = h('div', { class: 'vbarhit', 'aria-hidden': 'true' });
    const barMark = h('i', { class: 'vbarmk' });
    bar.parentNode.append(barHit, barMark);
    bar.tabIndex = -1;
    const scr = { on: false, id: 0, x0: 0, y0: 0, lx: 0, lt: 0, v: -1, t0: 0, t: 0, raw: 0, moved: false, mode: '', snap: false, away: false };
    const buzz = () => { try { if (navigator.vibrate && touchFresh()) navigator.vibrate(12); } catch (e) {} };
    // Abstand zur alten Stelle: „+12 s“, „−1:05“
    const delta = (x) => { const a = Math.round(Math.abs(x)); return (x < 0 ? '−' : '+') + (a < 60 ? a + ' s' : fmtTime(a)); };
    const durOk = () => { const d = M().duration; return isFinite(d) && d > 0 ? d : 0; };
    const barBack = () => { const m = M(), d = durOk(); if (d) { bar.value = String(Math.round(m.currentTime / d * 1000)); paintBar(bar.value / 1000); tCur.textContent = fmtTime(m.currentTime); } };
    function scrubShow(t) {
      const d = durOk();
      if (!d) return;
      const p = clamp(t / d, 0, 1);
      bar.value = String(Math.round(p * 1000));
      paintBar(p);
      tCur.textContent = fmtTime(t);
      prevShow(p);
      wrap.classList.toggle('scrubx', scr.mode === 'cancel');
      wrap.classList.toggle('scrubfine', scr.mode === 'fine');
      wrap.classList.toggle('scrubsnap', scr.snap && scr.mode !== 'cancel');
      if (scr.mode === 'cancel') pvTime.textContent = tr('Loslassen = abbrechen');
      else if (scr.moved) pvTime.textContent = fmtTime(t) + ' · ' + (scr.snap ? '± 0' : delta(t - scr.t0));
    }
    function scrubEnd(how) {   // how: 'seek' | 'tap' | 'cancel'
      if (!scr.on) return;
      scr.on = false;
      wrap.classList.remove('thick', 'scrubx', 'scrubfine', 'scrubsnap');
      const m = M(), d = durOk();
      if (how === 'seek' && scr.snap) how = 'cancel';   // an der alten Stelle losgelassen: nichts ändern
      if (how === 'cancel') { liveEnd(true); barBack(); }
      else if (d) {
        liveEnd(false);
        st.userSeekAt = Date.now();
        try { m.currentTime = clamp(scr.t, 0, Math.max(0, d - 0.2)); } catch (e) {}
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
      Object.assign(scr, { on: true, id: e.pointerId, x0: e.clientX, y0: e.clientY, lx: e.clientX, lt: e.timeStamp || performance.now(), v: -1, t0: m.currentTime, t: m.currentTime, raw: m.currentTime, moved: false, first: true, mode: '', snap: false, away: false });
      // weißer Strich: hier warst du (dorthin zurück rastet ein)
      const br = bar.getBoundingClientRect(), rr = bar.parentNode.getBoundingClientRect();
      barMark.style.left = Math.round(br.left - rr.left + 7 + clamp(m.currentTime / d, 0, 1) * (br.width - 14) - 1.5) + 'px';
      st.barDrag = true; clearTimeout(st.hideTimer);
      if (!liveMode()) { pv.preload = 'auto'; pvs.autoGrid = true; if (prevLoad() && pv.readyState >= 1) startGrid(); }
    });
    barHit.addEventListener('pointermove', (e) => {
      if (!scr.on || e.pointerId !== scr.id) return;
      const d = durOk();
      if (!d) return;
      const dy = e.clientY - scr.y0;
      if (!scr.moved) {
        if (Math.hypot(e.clientX - scr.x0, dy) < 8) return;
        scr.moved = true;
        wrap.classList.add('thick');
      }
      const br = bar.getBoundingClientRect(), w = Math.max(60, br.width - 14);
      const at = (cx) => clamp((cx - br.left - 7) / w, 0, 1) * d;   // Stelle unter dem Finger
      const now = e.timeStamp || performance.now(), dt = Math.max(4, now - scr.lt);
      const dx = e.clientX - scr.lx;
      scr.lx = e.clientX; scr.lt = now;
      if (dy < -Math.max(70, br.height * 1.5)) { scr.mode = 'cancel'; scr.v = -1; scrubShow(scr.t0); return; }
      // Fingergeschwindigkeit (px/ms, geglättet) mischt zwischen „fein“ (kleine Schritte von der Stelle aus) und
      // „unter dem Finger“ (der Abstand zum Finger schrumpft mit jeder Bewegung, nach ~25 px auf ein Drittel)
      // Hochwischen (Android-Geste) ist kein Spulen: erst wenn der Finger ~10 px zur Seite gezogen hat, springt die
      // Stelle unter ihn
      if (scr.first && Math.abs(e.clientX - scr.x0) < 10) { scrubShow(scr.t); return; }
      const sp = Math.abs(dx) / dt;
      scr.v = scr.v < 0 ? sp : scr.v * 0.6 + sp * 0.4;
      const full = d / w, fine = Math.min(full, Math.max(full / 8, 0.08));
      const g = scr.first ? 1 : clamp((scr.v - 0.035) / 0.085, 0, 1);
      const inc = scr.raw + dx * fine;
      const under = at(e.clientX) + (scr.first ? 0 : (scr.raw - at(e.clientX - dx)) * Math.exp(-Math.abs(dx) / 25));
      scr.first = false;
      scr.mode = g < 0.5 && fine < full * 0.6 ? 'fine' : '';
      scr.raw = clamp(inc + (under - inc) * g, 0, d);
      // Einrasten an der alten Stelle (erst nachdem du einmal weg warst)
      const zone = Math.max(0.25, fine * 7);
      const near = Math.abs(scr.raw - scr.t0) < zone;
      if (!near) scr.away = true;
      if (near && scr.away && !scr.snap) buzz();
      scr.snap = near && scr.away;
      scr.t = scr.snap ? scr.t0 : scr.raw;
      scrubShow(scr.t);
    });
    barHit.addEventListener('pointerup', (e) => {
      if (!scr.on || e.pointerId !== scr.id) return;
      if (!scr.moved) {   // kurz angetippt: an diese Stelle
        const br = bar.getBoundingClientRect(), d = durOk();
        if (d) scr.t = clamp((e.clientX - br.left - 7) / Math.max(60, br.width - 14), 0, 1) * d;
        scrubShow(scr.t);
        scrubEnd('tap');
        return;
      }
      scrubEnd(scr.mode === 'cancel' ? 'cancel' : 'seek');
    });
    barHit.addEventListener('pointercancel', (e) => { if (scr.on && e.pointerId === scr.id) scrubEnd('cancel'); });   // Android hat die Geste übernommen
    barHit.addEventListener('click', stop);

