  function watchStall(o) {
    let last = o.currentTime, still = 0, n = 0;
    const iv = setInterval(() => {
      n++;
      const c = o.currentTime;
      if (c > last + 0.2 || !o.isConnected) { clearInterval(iv); return; }   // läuft (oder Video weg)
      still++;
      if (still === 3) {
        // kleiner Sprung + Play: bringt hängende Web-Player meist wieder in Gang
        try { o.currentTime = c + 0.05; const pr = o.play(); if (pr && pr.catch) pr.catch(() => {}); } catch (e) {}
      }
      if (still >= 7 || n > 12) clearInterval(iv);
      last = c;
    }, 1000);
  }

  // Läuft UNSER Video im kleinen Fenster? (im Shadow-DOM meldet document nur den äußeren Wirt, nicht das Video selbst)
  function pipIsOurs(v) {
    try {
      if (root && root.pictureInPictureElement) return root.pictureInPictureElement === v;
      const d = document.pictureInPictureElement;
      return !!d && (d === v || d === hostEl);
    } catch (e) { return false; }
  }
  // ----- Reels: „Nächstes Reel in 3 … Abbrechen“ -----
  // Facebook zählt am Ende eines Reels selbst herunter und springt dann weiter – auch wenn das Video angehalten ist.
  // Solange der Player offen ist (und kurz danach), tippt das Skript sofort auf „Abbrechen“. Erkannt wird nur ein
  // kleiner Kasten mit Countdown (Reel/Video … Zahl) UND einem Abbrechen-Knopf – sonst wird nichts angetippt.
  const NEXT_RE = /(reel|video|clip)[^\n]{0,40}?\b\d{1,2}\s*(s|sek|sec)?\.?\s*$/i;   // „Next reel in 2“, „Nächstes Video in 3 s“
  const NEXT_WORD_RE = /(next|nächst|weiter|suivant|siguiente|próxim|prossim|następn|volgend|nästa|seuraava|sonraki|следующ|наступн|შემდეგ)|\bin\s+\d/i;
  const CANCEL_RE = /^(cancel|abbrechen|annuler|cancelar|annulla|anuluj|annuleren|avbryt|peruuta|iptal|zrušit|mégse|отмена|скасувати|취소|キャンセル|取消|إلغاء|ביטול|გაუქმება)$/i;
  let nextObs = null, nextOff = 0, nextQ = new Set(), nextT = 0, nextCancels = 0;
  function cancelIn(box) {
    if (!box || box.nodeType !== 1 || (hostEl && (box === hostEl || hostEl.contains(box)))) return false;   // nie im eigenen Player
    const txt = (box.textContent || '').trim();
    if (!txt || txt.length > 140) return false;
    const els = box.querySelectorAll('*');
    let btn = null;
    for (let i = 0; i < els.length && i < 60; i++) {
      const t = (els[i].textContent || '').trim();
      if (t && CANCEL_RE.test(t)) { btn = els[i].closest('[role="button"], button, a') || els[i]; break; }
    }
    if (!btn) return false;
    // Countdown-Text: der Rest ohne den Knopf
    const rest = txt.replace((btn.textContent || '').trim(), ' ').trim();
    if (!NEXT_RE.test(rest) || !NEXT_WORD_RE.test(rest)) return false;
    try { btn.click(); } catch (e) { return false; }
    nextCancels++;
    return true;
  }
  function nextScan() {
    nextT = 0;
    const q = Array.from(nextQ); nextQ.clear();
    for (const n of q) {
      let el = n.nodeType === 1 ? n : n.parentElement;
      // vom geänderten Teil bis zum kleinsten Kasten, der Countdown UND Knopf enthält
      for (let up = 0; el && up < 5; up++, el = el.parentElement) {
        const l = (el.textContent || '').length;
        if (l > 140) break;
        if (cancelIn(el)) return;
      }
    }
  }
  function armNextGuard() {
    clearTimeout(nextOff); nextOff = 0;
    if (nextObs || !document.body) return;
    try {
      nextObs = new MutationObserver((ms) => {
        for (const m of ms) {
          if (m.type === 'characterData') nextQ.add(m.target);
          else for (const a of m.addedNodes) if (a.nodeType === 1 || a.nodeType === 3) nextQ.add(a);
        }
        if (nextQ.size > 400) nextQ = new Set(Array.from(nextQ).slice(-200));
        if (!nextT && nextQ.size) nextT = setTimeout(nextScan, 50);
      });
      nextObs.observe(document.body, { childList: true, subtree: true, characterData: true });
    } catch (e) { nextObs = null; }
  }
  function disarmNextGuard(later) {
    clearTimeout(nextOff);
    nextOff = setTimeout(() => { if (vp) return; if (nextObs) nextObs.disconnect(); nextObs = null; nextQ.clear(); }, later || 0);
  }

  // ----- Übergabe an die Seite -----
  // Das Video der Seite springt an deine Stelle und läuft (mit Ton) weiter. Bis es dort ein Bild hat, liegt dein
  // letztes Bild genau darüber – sonst sähe man kurz den Anfang des Videos und dann einen Sprung.
  // Bei der Zurück-Geste passiert das schon in dem Moment, in dem sie erkannt wird (nicht erst nach der Prüfung).
  function handBack(st) {
    if (st.handed) return;
    st.handed = true;
    const o = st.orig;
    let target = -1, atEnd = !!st.endHold;
    // Deine Stelle ZUERST merken – wird hls.js beendet, springt das eigene Video auf 0 zurück.
    // Gleiche Länge prüfen, damit nicht z. B. eine Werbung oder ein anderes Video verstellt wird.
    try {
      const t = st.vid.currentTime, vd = st.vid.duration, od = o.duration;
      const sameLen = !isFinite(od) || !isFinite(vd) || Math.abs(od - vd) < 3;
      if (st.mode === 'own' && !st.bridge && t > 0 && sameLen) {   // (nahtloser Start noch nicht fertig: die Seite steht schon richtig)
        target = t;
        // am Ende: kurz VOR das Ende stellen und angehalten lassen – sonst springt eine Reels-Seite zum nächsten Video
        if (isFinite(vd) && (st.vid.ended || t >= vd - 1)) { atEnd = true; target = Math.max(0, (isFinite(od) ? od : vd) - 0.3); }
      }
    } catch (e) {}
    // Nahtlos zurück: dein Video läuft noch kurz (mit Ton) genau über dem der Seite weiter, bis die Seite bereit ist
    if (target >= 0 && !atEnd && !st.closePaused && CONFIG.resumeAfterClose && softBack(st)) return;
    if (target >= 0 && Math.abs((o.currentTime || 0) - target) > 0.4) {
      coverOver(o, st.vid);
      try { o.currentTime = target; } catch (e) {}
    }
    try { if (typeof st.origMuted === 'boolean') o.muted = st.origMuted; } catch (e) {}   // ursprünglichen Ton zurückgeben
    // Video auf der Seite weiterlaufen lassen (Instagram & Co. haben oft keinen Play-Knopf)
    if (atEnd || st.closePaused) { try { o.pause(); } catch (e) {} }   // (nach einem Download: angehalten lassen)
    else if (CONFIG.resumeAfterClose && st.mode === 'own') {
      try { const pr = o.play(); if (pr && pr.catch) pr.catch(() => {}); } catch (e) {}
      watchStall(o);
    } else if (CONFIG.resumeAfterClose) {
      setTimeout(() => { try { const pr = o.play(); if (pr && pr.catch) pr.catch(() => {}); } catch (e) {} watchStall(o); }, 200);
    }
  }
  // ----- Nahtlos zurück zur Seite (Gegenstück zum nahtlosen Start) -----
  // Bisher sprang das angehaltene Video der Seite an deine Stelle und musste dort erst laden – so lange lag ein
  // Standbild darüber (wirkt wie eine Pause). Jetzt läuft DEIN Video (mit Ton) noch kurz genau über dem der Seite
  // weiter; die Seite springt still etwas voraus und wartet angehalten. Erreicht dein Video diese Stelle, startet die
  // Seite dort mit Ton und dein Video verschwindet – Bild und Ton laufen ohne Lücke weiter. Klappt es nicht in 3 s
  // (lädt zu langsam), wie früher: Standbild, bis die Seite an deiner Stelle ist.
  let softSt = null;   // läuft gerade ein nahtloses Zurück? (ein neuer Player beendet es sofort)
  function softBack(st) {
    const v = st.vid, o = st.orig;
    try {
      if (st.mode !== 'own' || st.bridge || v.paused || v.ended || v.readyState < 3 || !o.isConnected || document.hidden || pipIsOurs(v) || !root) return false;
      if (!isFinite(v.duration) || v.currentTime > v.duration - 2.5) return false;   // (fast am Ende: lieber wie bisher)
      const r = o.getBoundingClientRect();
      if (r.width < 60 || r.height < 40) return false;
    } catch (e) { return false; }
    const s = { v: v, o: o, at: performance.now(), lead: 0.6, T: 0, tries: 0, iv: 0, done: false, after: [],
      parent: v.parentNode, next: v.nextSibling, css: v.style.cssText, fit: getComputedStyle(o).objectFit || 'contain' };
    st.soft = s; softSt = st;
    const place = () => {
      if (s.done) return;
      const q = o.getBoundingClientRect();
      v.style.cssText = 'position:fixed;left:' + q.left + 'px;top:' + q.top + 'px;width:' + q.width + 'px;height:' + q.height + 'px;margin:0;transform:none;' +
        'object-fit:' + (s.fit === 'cover' ? 'cover' : 'contain') + ';background:#000;z-index:2147483646;pointer-events:none;filter:none';
    };
    s.place = place;
    place();
    root.append(v);   // aus dem (unsichtbaren) Player heraus, genau über das Video der Seite – es läuft dabei weiter
    if (v.paused) v.play().catch(() => {});
    addEventListener('resize', place); addEventListener('scroll', place, true);
    requestAnimationFrame(place); setTimeout(place, 120); setTimeout(place, 400);   // (die Seite rückt sich nach dem Vollbild zurecht)
    const aim = () => {
      s.T = v.currentTime + s.lead * (v.playbackRate || 1);
      try { o.muted = true; o.pause(); o.playbackRate = v.playbackRate || 1; o.currentTime = s.T; } catch (e) {}
    };
    aim();
    s.iv = setInterval(() => {
      if (s.done) return;
      if (performance.now() - s.at > 3000) { softFail(st); return; }
      if (v.paused && !v.ended) { try { v.play().catch(() => {}); } catch (e) {} }
      // dein Video selbst hängt (lädt nach)? Dann bringt das Überbrücken nichts – gleich wie früher
      if (v.currentTime !== s.lastV) { s.lastV = v.currentTime; s.lastAt = performance.now(); }
      else if (performance.now() - (s.lastAt || s.at) > 600) { softFail(st); return; }
      if (o.seeking || o.readyState < 3) {   // Seite lädt noch – läuft dein Video schon an der Stelle vorbei? weiter voraus
        if (v.currentTime > s.T + 0.05) { s.tries++; s.lead = Math.min(2.5, s.lead * 1.8); if (s.tries > 3) softFail(st); else aim(); }
        return;
      }
      const d = s.T - v.currentTime;
      if (d > 0.02) return;                     // noch nicht da
      if (d < -0.12) { s.tries++; s.lead = Math.min(2.5, s.lead * 1.5); if (s.tries > 3) softFail(st); else aim(); return; }
      softSwap(st);
    }, 15);
    return true;
  }
  function softSwap(st) {
    const s = st.soft, v = s.v, o = s.o;
    if (s.swapped) return;
    s.swapped = true; clearInterval(s.iv);   // (ab jetzt nicht mehr nachsteuern – die Seite spielt)
    try { if (typeof st.origMuted === 'boolean') o.muted = st.origMuted; const pr = o.play(); if (pr && pr.catch) pr.catch(() => {}); } catch (e) {}
    const gone = () => softEnd(st, 'nahtlos');
    // erst weg, wenn die Seite ihr erstes Bild an der Stelle zeigt (sonst blitzt kurz das alte Bild auf)
    if (o.requestVideoFrameCallback) { o.requestVideoFrameCallback(() => gone()); setTimeout(gone, 300); } else setTimeout(gone, 80);
    try { v.muted = true; } catch (e) {}
    watchStall(o);
  }
  function softFail(st) {
    const s = st.soft;
    if (!s || s.done) return;
    const v = s.v, o = s.o, t = v.currentTime;
    coverOver(o, v);   // wie früher: dein letztes Bild darüber, bis die Seite an deiner Stelle ist
    try { v.muted = true; v.pause(); } catch (e) {}
    try { o.currentTime = t; if (typeof st.origMuted === 'boolean') o.muted = st.origMuted; const pr = o.play(); if (pr && pr.catch) pr.catch(() => {}); } catch (e) {}
    watchStall(o);
    softEnd(st, 'Standbild');
  }
  function softEnd(st, how) {
    const s = st.soft;
    if (!s) return;
    if (!s.done) {
      s.done = true;
      clearInterval(s.iv);
      removeEventListener('resize', s.place); removeEventListener('scroll', s.place, true);
      try { s.v.muted = true; s.v.pause(); } catch (e) {}
      if (openTimes && how) openTimes.back = how + ' nach ' + Math.round(performance.now() - s.at) + ' ms';
    }
    // Bei der Zurück-Geste ist das Schließen noch nicht sicher (vielleicht war es eine Drehung): dann das Video nur
    // verstecken – kommt der Player zurück, macht er an der Stelle der Seite weiter
    if (!st.closing) { s.parked = true; try { s.v.style.display = 'none'; } catch (e) {} return; }
    try { s.v.remove(); } catch (e) {}
    st.soft = null; if (softSt === st) softSt = null;
    s.after.forEach((f) => { try { f(); } catch (e) {} });
  }
  // doch nicht zurück (Drehen …): dein Video zurück in den Player, die Seite wieder still
  function softCancel(st) {
    const s = st.soft;
    if (!s) return;
    if (!s.done) { clearInterval(s.iv); removeEventListener('resize', s.place); removeEventListener('scroll', s.place, true); }
    s.done = true;
    try { s.v.style.cssText = s.css; if (s.parent) s.parent.insertBefore(s.v, s.next && s.next.parentNode === s.parent ? s.next : null); } catch (e) {}
    // hatte die Seite schon übernommen: dort weitermachen, wo sie jetzt ist (mit Ton)
    if (s.parked) { try { s.v.currentTime = s.o.currentTime; s.v.muted = false; } catch (e) {} }
    try { s.v.play().catch(() => {}); } catch (e) {}
    st.soft = null; if (softSt === st) softSt = null;
  }
  // doch nicht geschlossen (Drehen, App-Wechsel): Seite wieder still und angehalten, Player übernimmt wieder
  function unHand(st) {
    if (!st.handed) return;
    st.handed = false;
    softCancel(st);
    removeCover();
    if (st.mode !== 'own') return;
    try { st.orig.muted = true; st.orig.pause(); } catch (e) {}
    // (der nahtlose Start war beim Übergeben noch nicht fertig: die eigene Kopie war stumm – jetzt hat sie den Ton)
    if (st.bridgeMuted) { st.bridgeMuted = false; try { st.vid.muted = false; st.vid.play().catch(() => {}); } catch (e) {} }
  }
  let coverEl = null;
  function coverOver(o, src) {
    removeCover();
    if (!root) return;
    // Firefox-Handy kann Videobilder nicht kopieren (wäre ein schwarzer Kasten): dann das Video der Seite
    // unsichtbar lassen, bis es an der richtigen Stelle ein Bild hat – lieber kurz dunkel als Anfang + Sprung
    const hideOrig = () => {
      const c = { _hid: o, _op: o.style.getPropertyValue('opacity'), _pr: o.style.getPropertyPriority('opacity'), remove() {}, style: {} };
      o.style.setProperty('opacity', '0', 'important');
      return c;
    };
    try {
      const r = o.getBoundingClientRect();
      if (FF_MOBILE || r.width < 60 || r.height < 40 || !src.videoWidth || !src.videoHeight || src.readyState < 2) {
        const c = hideOrig(); coverEl = c;
        const gone = () => removeCover(c);
        const onSeeked = () => { if (o.requestVideoFrameCallback) o.requestVideoFrameCallback(() => gone()); else setTimeout(gone, 120); };
        o.addEventListener('seeked', onSeeked, { once: true });
        c._off = () => o.removeEventListener('seeked', onSeeked);
        c._t = setTimeout(gone, 2500);
        return;
      }
      const c = document.createElement('canvas');
      c.className = 'evpcover';
      const dpr = Math.min(2, window.devicePixelRatio || 1);
      c.width = Math.round(r.width * dpr); c.height = Math.round(r.height * dpr);
      const x = c.getContext('2d');
      x.fillStyle = '#000'; x.fillRect(0, 0, c.width, c.height);
      const cov = getComputedStyle(o).objectFit === 'cover';
      const s = (cov ? Math.max : Math.min)(c.width / src.videoWidth, c.height / src.videoHeight);
      const w = src.videoWidth * s, hh = src.videoHeight * s;
      x.drawImage(src, (c.width - w) / 2, (c.height - hh) / 2, w, hh);
      const place = () => { const q = o.getBoundingClientRect(); c.style.cssText = 'position:fixed;left:' + q.left + 'px;top:' + q.top + 'px;width:' + q.width + 'px;height:' + q.height + 'px;z-index:2147483646;pointer-events:none;opacity:' + (c._out ? 0 : 1) + ';transition:opacity .15s ease'; };
      place();
      root.append(c);
      coverEl = c;
      // mitgehen, falls sich die Seite nach dem Vollbild noch zurechtrückt (Drehen, Leisten)
      c._place = place;
      addEventListener('resize', place); addEventListener('scroll', place, true);
      requestAnimationFrame(place);
      // weg, sobald die Seite an der neuen Stelle ein Bild zeigt – spätestens nach 3 s
      const gone = () => removeCover(c);
      const onSeeked = () => { if (o.requestVideoFrameCallback) o.requestVideoFrameCallback(() => gone()); else setTimeout(gone, 80); };
      o.addEventListener('seeked', onSeeked, { once: true });
      c._off = () => o.removeEventListener('seeked', onSeeked);
      c._t = setTimeout(gone, 3000);
    } catch (e) {}
  }
  function removeCover(c) {
    c = c || coverEl;
    if (!c || c._out) return;
    c._out = true;
    clearTimeout(c._t);
    if (c._off) c._off();
    if (c._hid) {   // nur versteckt: Sichtbarkeit wie vorher
      try { if (c._op) c._hid.style.setProperty('opacity', c._op, c._pr); else c._hid.style.removeProperty('opacity'); } catch (e) {}
      if (coverEl === c) coverEl = null;
      return;
    }
    removeEventListener('resize', c._place); removeEventListener('scroll', c._place, true);
    c.style.opacity = '0';
    setTimeout(() => c.remove(), 180);
    if (coverEl === c) coverEl = null;
  }
  function closePlayer() {
    const st = vp;
    if (!st) return;
    vp = null;
    // Kleines Fenster (Bild-in-Bild) gleich mitschließen – sonst bliebe es pausiert und tot stehen
    try {
      if (pipIsOurs(st.vid)) { const pr = document.exitPictureInPicture(); if (pr && pr.catch) pr.catch(() => {}); }
      st.vid.disablePictureInPicture = true;   // beendet Bild-in-Bild auch dort, wo exitPictureInPicture nicht greift
    } catch (e) {}
    clearTimeout(st.loadTimer); clearTimeout(st.hideTimer); clearTimeout(st.u2);
    // Schneller zu: Ton aus, Player sofort unsichtbar, Vollbild aus – das Aufräumen (Seite übernimmt, Video und Stream
    // abbauen …) kommt direkt danach, wenn der Bildschirm schon frei ist
    // (nahtlos zurück: dein Video läuft noch kurz über dem der Seite weiter – nicht stumm schalten)
    const softOk = !st.handed && st.mode === 'own' && !st.vid.paused;
    if (!softOk && !st.soft) { try { st.vid.muted = true; st.vid.pause(); } catch (e) {} }
    st.wrap.style.display = 'none';
    try { if (document.fullscreenElement && document.exitFullscreen) document.exitFullscreen().catch(() => {}); } catch (e) {}
    try { if (screen.orientation && screen.orientation.unlock) screen.orientation.unlock(); } catch (e) {}
    handBack(st);   // Seite übernimmt an deiner Stelle (bei der Zurück-Geste schon geschehen)
    // Mit ✕ geschlossen: unseren Verlaufseintrag wieder entfernen, damit "Zurück" danach normal funktioniert
    if (st.histPushed) { try { history.back(); } catch (e) {} }
    if (toastEl && root) { root.append(toastEl); toastEl.style.top = ''; toastEl.classList.remove('g'); toastEl.style.removeProperty('--lens'); toastEl.style.removeProperty('--gb'); if (lensRO) lensRO.unobserve(toastEl); }
    const restUi = () => {
      if (st.uiGone) return;
      st.uiGone = true;
      if (!vp) kadriShield(false);   // Seite bekommt „verdeckt/sichtbar“ wieder selbst (außer ein neuer Player spiegelt schon)
      st.cleanup.forEach((f) => { try { f(); } catch (e) {} });
      st.wrap.remove();
    };
    const restVid = () => {
      if (st.hls) { try { st.hls.destroy(); } catch (e) {} }
      if (st.actx) { try { st.actx.close(); } catch (e) {} }
      try { st.vid.srcObject = null; st.vid.removeAttribute('src'); st.vid.load(); } catch (e) {}
    };
    const rest = () => { restVid(); restUi(); };
    st.closing = true;
    if (st.soft && st.soft.parked) softEnd(st, '');   // (Übergabe war schon fertig – Video jetzt wirklich weg)
    // nahtlos zurück läuft noch: Player gleich abbauen, das eigene Video erst nach der Übergabe (es läuft ja noch)
    if (st.soft) { restUi(); st.soft.after.push(restVid); return; }
    if (softOk) { try { st.vid.muted = true; st.vid.pause(); } catch (e) {} }   // (nicht nahtlos geworden: jetzt still)
    // nach dem nächsten Bild (Bildschirm ist dann schon frei); im Hintergrund gibt es keine Bilder → gleich
    if (document.hidden || typeof requestAnimationFrame !== 'function') rest();
    else { let done = false; const go = () => { if (!done) { done = true; rest(); } }; requestAnimationFrame(() => setTimeout(go, 0)); setTimeout(go, 250); }
  }

  // Tipp auf "Player": eingebauter Player (Standard) oder externe App
  function onPlayerTap() {
    tapAt = performance.now();
    const v = active;
    if (!v) return;
    if (CONFIG.defaultPlayer === 'external' && !DOWNLOAD_ONLY) { onPillMain(); return; }
    const go = () => {
      if (toastEl) toastEl.classList.remove('on');
      // Sofort öffnen – nicht auf die HD-Suche warten. Kommt Facebook-HD danach noch, wechselt der Player nahtlos.
      const p = META && (!FB_MIRROR || FB_TRY_OWN) ? fetchHD(v) : PH ? phMp4() : null;
      openBrowserPlayer(v);
      if (p && META) p.then(() => { if (vp && vp.orig === v && vp.upgrade) vp.upgrade(); }, () => {});
    };
    // Facebook direkt nach dem Scrollen: noch unklar, welches Video läuft (Nachbarvideos werden vorgeladen)?
    // Dann kurz warten, bis die Wiedergabe es eindeutig macht – höchstens 2,5 Sekunden.
    if (!FB_MIRROR && /(^|\.)facebook\.com$/i.test(location.hostname) && !assetFor(v).sure) {
      const t0 = Date.now();
      toast('Erkenne Video …');
      const poll = () => {
        if (vp) return;
        if (assetFor(v).sure || Date.now() - t0 > 2500) go(); else setTimeout(poll, 150);
      };
      setTimeout(poll, 150);
      return;
    }
    go();
  }

