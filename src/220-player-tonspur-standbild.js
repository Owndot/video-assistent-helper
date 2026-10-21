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
    let barG = null;
    const barBack = () => { const m = M(); if (isFinite(m.duration) && m.duration > 0) { bar.value = String(Math.round(m.currentTime / m.duration * 1000)); paintBar(bar.value / 1000); tCur.textContent = fmtTime(m.currentTime); } };
    bar.addEventListener('pointerdown', (e) => {
      stop(e); st.barDrag = true; clearTimeout(st.hideTimer);
      barG = { x: e.clientX, y: e.clientY, off: false, lock: false };
      pv.preload = 'auto'; pvs.autoGrid = true; if (prevLoad() && pv.readyState >= 1) startGrid();
    });
    bar.addEventListener('pointermove', (e) => {
      if (!barG || barG.off || barG.lock) return;
      const dx = e.clientX - barG.x, dy = e.clientY - barG.y;
      if (Math.abs(dx) > 10) { barG.lock = true; return; }
      if (-dy > 40 && Math.abs(dx) < 16) { barG.off = true; st.barDrag = false; prevHide(); barBack(); }
    });
    bar.addEventListener('pointercancel', () => {   // Android hat die Wischgeste übernommen
      if (barG && !barG.lock && !barG.off) { barG.off = true; st.barDrag = false; barBack(); setTimeout(() => { if (barG && barG.off) { barG = null; barBack(); scheduleHide(); } }, 120); }
    });
    bar.addEventListener('input', () => {
      if (barG && barG.off) { barBack(); return; }
      const m = M();
      if (isFinite(m.duration)) tCur.textContent = fmtTime(bar.value / 1000 * m.duration);
      paintBar(bar.value / 1000);
      prevShow(bar.value / 1000);
    });
    bar.addEventListener('change', () => {
      if (barG && barG.off) { barG = null; barBack(); scheduleHide(); return; }   // war Hochwischen, kein Spulen
      barG = null;
      const m = M();
      st.userSeekAt = Date.now();
      if (isFinite(m.duration)) { try { m.currentTime = bar.value / 1000 * m.duration; } catch (e) {} }
      st.barDrag = false;
      prevHide();
      scheduleHide();
    });
    ['pointerup', 'pointercancel', 'lostpointercapture'].forEach((t) => bar.addEventListener(t, () => setTimeout(prevHide, 60)));

