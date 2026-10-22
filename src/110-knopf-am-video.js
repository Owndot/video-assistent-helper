  // ---------- Button am laufenden Video (unten rechts, über der Steuerleiste) ----------
  function eligible(v) {
    const r = rectOf(v);
    return r.width >= CONFIG.minWidth && r.height >= 90;
  }
  function visibleArea(v) {
    const r = rectOf(v);
    const w = Math.min(r.right, innerWidth) - Math.max(r.left, 0);
    const hh = Math.min(r.bottom, innerHeight) - Math.max(r.top, 0);
    return w > 0 && hh > 0 ? w * hh : 0;
  }
  // Das am besten sichtbare Video, das gerade läuft
  // Läuft das Video hörbar? (nicht stumm, Lautstärke > 0, und es hat überhaupt eine Tonspur)
  function audible(v) {
    if (!CONFIG.onlyWithSound) return true;
    if (v.muted || v.volume < 0.01) return false;
    if (v.mozHasAudio === false) return false;
    const adb = v.webkitAudioDecodedByteCount;
    if (typeof adb === 'number' && adb === 0 && v.currentTime > 2) return false;   // Video ganz ohne Ton
    return true;
  }
  function pickPlaying() {
    let best = null, area = 0;
    const vids = allVideos();
    for (let i = 0; i < vids.length; i++) {
      const v = vids[i];
      if (v.paused || v.ended || !eligible(v) || !audible(v)) continue;
      const a = visibleArea(v);
      if (a > area) { area = a; best = v; }
    }
    return area > 15000 ? best : null;
  }
  function showPill(v) {
    ui();
    attachHost();
    if (pill && !pill.__lens) { pill.__lens = true; lensWatch(pill); }
    if (v !== active) watchSize(v);   // neues Video: wieder klein anfangen
    active = v;
    pill.classList.add('on');
    schedulePlace();
  }
  function hidePill() {
    if (pill) pill.classList.remove('on');
  }
  function place() {
    if (!active || !pill || !pill.classList.contains('on')) return;
    const r = rectOf(active);
    if (!active.isConnected || r.width < CONFIG.minWidth || r.bottom < 60 || r.top > innerHeight - 60) {
      pill.classList.remove('on'); return;
    }
    const w = pill.offsetWidth || 100, ph = pill.offsetHeight || 38;
    let left, top;
    if (dragPos) {
      // wird gerade verschoben: dem Finger folgen
      left = dragPos.left;
      top = CONFIG.buttonAt === 'top' ? CONFIG.topOffset : dragPos.top;
    } else if (CONFIG.buttonAt === 'below') {
      // direkt unter dem Video, rechts oder links (per Ziehen umstellbar)
      left = savedPos && savedPos.side === 'left' ? r.left + CONFIG.rightOffset : r.right - w - CONFIG.rightOffset;
      top = r.bottom + 8;
      if (top + ph > innerHeight - 8) top = r.bottom - ph - 12;   // darunter kein Platz: an den unteren Rand im Video
    } else if (CONFIG.buttonAt === 'top') {
      // oben am Bildschirm, links oder rechts (per Ziehen umstellbar)
      left = savedPos && savedPos.side === 'left' ? CONFIG.rightOffset : innerWidth - w - CONFIG.rightOffset;
      top = CONFIG.topOffset;
    } else if (savedPos) {
      // am gespeicherten Rand (links/rechts), Höhe relativ zum Video, immer innerhalb des Videos
      left = savedPos.side === 'left' ? r.left + CONFIG.rightOffset : r.right - w - CONFIG.rightOffset;
      top = r.top + savedPos.fy * r.height - ph / 2;
      top = Math.min(Math.max(r.top + 4, top), r.bottom - ph - 4);
    } else {
      // Standard: unten rechts, über der Steuerleiste
      left = r.right - w - CONFIG.rightOffset;
      top = r.bottom - ph - CONFIG.bottomOffset;
      if (top < r.top + 8) top = r.top + 8;
    }
    left = Math.min(Math.max(8, left), innerWidth - w - 8);
    top = Math.min(Math.max(8, top), innerHeight - ph - 8);
    pill.style.left = left + 'px';
    pill.style.top = top + 'px';
    // Auf- und Zuklappen vom Rand aus nach innen
    const origin = savedPos && savedPos.side === 'left' && !dragPos ? 'center left' : 'center right';
    if (pill.style.transformOrigin !== origin) pill.style.transformOrigin = origin;
  }

  let miss = 0;
  // Seiten ohne Video (Nachrichten, Shops …): nur alle 2 Sekunden nachsehen statt 2,5-mal pro Sekunde.
  // Startet irgendwo ein Video, geht es sofort wieder im normalen Takt weiter.
  let quiet = 0, tickN = 0;
  document.addEventListener('play', () => { quiet = 0; }, true);
  function tick() {
    tickN++;
    if (quiet > 5 && tickN % 5 && !vp) return;
    if (hostEl && !hostEl.isConnected) attachHost();   // von der Seite entfernt? Wieder einsetzen.
    if (vp) { hidePill(); return; }                    // eigener Player offen: kein Button darüber
    const v = pickPlaying();
    quiet = lastVidCount ? 0 : quiet + 1;
    if (v) {
      miss = 0;
      if (v !== active || !pill || !pill.classList.contains('on')) showPill(v);
      else schedulePlace();   // Sicherheitsnetz, falls die Seite das Video ohne Scrollen verschiebt
      if (PH) { try { phMp4(); } catch (e) {} }   // MP4-Adressen schon im Hintergrund holen
      // Facebook: HD-Version selbstständig im Hintergrund suchen, sobald sicher ist, welches Video läuft
      if (META) {
        if (!FB_MIRROR) { try { fetchHD(v); } catch (e) {} }   // beim Spiegeln unnötig – spart Daten
        const a = assetFor(v).asset;
        const c = a && hdCache.get(a);
        setLabel(c && c.best >= 1080 ? 'Player · Full HD' : c && c.best >= 720 ? 'Player · HD' : 'Player');
      }
    } else if (active && ++miss >= 2) {
      hidePill();   // kurzes Ruckeln beim Laden nicht gleich als Pause werten
    }
  }
  // Prüfung läuft nur, solange die Seite sichtbar ist (anderer Tab, andere App, Bildschirm aus = Pause)
  let tickTimer = 0, navTimer = 0;
  function startChecks() {
    if (tickTimer) return;
    tickTimer = setInterval(tick, 400);
    navTimer = setInterval(checkNav, 1000);
    checkNav();
    tick();
  }
  function stopChecks() {
    clearInterval(tickTimer); clearInterval(navTimer);
    tickTimer = navTimer = 0;
    hidePill();
  }
  document.addEventListener('visibilitychange', () => { if (document.hidden) stopChecks(); else startChecks(); });
  if (!document.hidden) startChecks();
  ['play', 'playing'].forEach((t) => document.addEventListener(t, (e) => {
    if (e.target && e.target.tagName === 'VIDEO') setTimeout(tick, 0);
  }, true));
  document.addEventListener('volumechange', (e) => { if (e.target && e.target.tagName === 'VIDEO') setTimeout(tick, 0); }, true);
  ['pause', 'ended', 'emptied'].forEach((t) => document.addEventListener(t, (e) => {
    if (e.target === active) { hidePill(); setTimeout(tick, 0); }
  }, true));

  function onPillMain() {
    const v = active;
    const go = () => {
      const r = resolve(v);
      if (!r.best) {
        toast(r.splitOnly
          ? 'Dieses Video gibt es hier nur mit getrennter Bild- und Tonspur'
          : 'Kein passendes Video gefunden – kurz laufen lassen und nochmal tippen');
        return;
      }
      let url = r.best, msg = '';
      // Mehrere Quellen: erneutes Tippen beim selben Video (innerhalb 60 s) nimmt die nächste
      if (r.list && r.list.length > 1) {
        const again = lastOpen.v === v && Date.now() - lastOpen.t < 60000;
        const idx = again ? (lastOpen.idx + 1) % r.list.length : 0;
        lastOpen = { v: v, t: Date.now(), idx: idx };
        url = r.list[idx].u;
        msg = 'Quelle ' + (idx + 1) + '/' + r.list.length + ' – klappt nicht? Nochmal tippen';
      }
      hidePill();
      // Geschützte Dateien der Seite selbst (z. B. /get_file/…?token): Weiterleitung im Browser auflösen
      if (needsResolve(url)) {
        toast('Bereite Video vor …');
        finalUrl(url).then((f) => openExternal(f, v, CONFIG.player, msg || (f !== url ? 'Wird im Videoplayer geöffnet (Weiterleitung aufgelöst) …' : '')));
        return;
      }
      openExternal(url, v, CONFIG.player, msg);
    };
    const p = META ? fetchHD(v) : PH ? phMp4() : null;
    if (p) {
      // HD-Suche läuft noch: kurz abwarten (max. 3,5 s), dann mit dem Besten öffnen
      toast(PH ? 'Suche MP4 mit Ton …' : 'Suche HD-Version …');
      let done = false;
      const t = setTimeout(() => { if (!done) { done = true; go(); } }, 3500);
      p.then(() => { if (!done) { done = true; clearTimeout(t); go(); } }, () => {});
      return;
    }
    go();
  }

