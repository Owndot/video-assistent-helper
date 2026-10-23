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
      if (st.mode === 'own' && t > 0 && sameLen) {
        target = t;
        // am Ende: kurz VOR das Ende stellen und angehalten lassen – sonst springt eine Reels-Seite zum nächsten Video
        if (isFinite(vd) && (st.vid.ended || t >= vd - 1)) { atEnd = true; target = Math.max(0, (isFinite(od) ? od : vd) - 0.3); }
      }
    } catch (e) {}
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
  // doch nicht geschlossen (Drehen, App-Wechsel): Seite wieder still und angehalten, Player übernimmt wieder
  function unHand(st) {
    if (!st.handed) return;
    st.handed = false;
    removeCover();
    if (st.mode !== 'own') return;
    try { st.orig.muted = true; st.orig.pause(); } catch (e) {}
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
    handBack(st);   // Seite übernimmt an deiner Stelle (bei der Zurück-Geste schon geschehen)
    if (st.hls) { try { st.hls.destroy(); } catch (e) {} }
    if (st.actx) { try { st.actx.close(); } catch (e) {} }
    try { st.vid.pause(); st.vid.srcObject = null; st.vid.removeAttribute('src'); st.vid.load(); } catch (e) {}
    try { if (document.fullscreenElement && document.exitFullscreen) document.exitFullscreen().catch(() => {}); } catch (e) {}
    try { if (screen.orientation && screen.orientation.unlock) screen.orientation.unlock(); } catch (e) {}
    kadriShield(false);   // Seite bekommt „verdeckt/sichtbar“ wieder selbst
    st.cleanup.forEach((f) => { try { f(); } catch (e) {} });
    // Mit ✕ geschlossen: unseren Verlaufseintrag wieder entfernen, damit "Zurück" danach normal funktioniert
    if (st.histPushed) { try { history.back(); } catch (e) {} }
    if (toastEl && root) { root.append(toastEl); toastEl.style.top = ''; toastEl.classList.remove('g'); toastEl.style.removeProperty('--lens'); toastEl.style.removeProperty('--gb'); if (lensRO) lensRO.unobserve(toastEl); }
    st.wrap.remove();
  }

  // Tipp auf "Player": eingebauter Player (Standard) oder externe App
  function onPlayerTap() {
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

