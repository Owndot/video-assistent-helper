  // ================= Eingebettete Fenster (iframe) =================
  // Viele Seiten laden das Video in einem eingebetteten Fenster von einer anderen Adresse. Der Player dort drin
  // könnte nur diesen kleinen Kasten füllen. Darum spricht er mit dem Skript auf der Hauptseite: die legt den Kasten
  // über den ganzen Bildschirm, schaltet IHN ins Vollbild, dreht das Bild und meldet Titel, Ränder (Kamera-Aussparung)
  // und Vollbild-Zustand zurück. Bei mehrfach verschachtelten Fenstern reicht jede Ebene die Nachricht weiter.
  const FK = '__evpFrame1';
  const IN_FRAME = (() => { try { return window.top !== window; } catch (e) { return true; } })();
  const fb = { up: null, ack: false, child: null, childWin: null, saved: null, onState: null };
  // Schlüssel für Nachrichten nach OBEN (eingebettetes Fenster → Hauptseite): Nur das Skript kennt ihn (sein eigener
  // Speicher, für Webseiten unlesbar). So kann eingebettete Werbung nicht so tun, als wäre sie der Player, und sich
  // nicht selbst über den ganzen Bildschirm legen lassen. Nach unten geht er nie mit; täglich ein neuer (den macht nur
  // die Hauptseite – eingebettete Fenster lesen ihn). Ohne Speicher des Managers (GM_…) gibt es keinen sicheren Ort:
  // dann ohne Schlüssel (null).
  const FSEC = (() => {
    try {
      if (typeof GM_getValue !== 'function' || typeof GM_setValue !== 'function') return null;
      const o = GM_getValue('frameKey', null), now = Date.now();
      if (o && typeof o.k === 'string' && o.k.length >= 24 && (IN_FRAME || now - (o.t || 0) < 864e5)) return o.k;
      if (IN_FRAME) return '';
      const a = new Uint8Array(18); crypto.getRandomValues(a);
      const k = Array.from(a, (b) => b.toString(16).padStart(2, '0')).join('');
      GM_setValue('frameKey', { k: k, t: now });
      return k;
    } catch (e) { return ''; }
  })();
  const fpost = (w, m) => { try { m[FK] = 1; if (FSEC && w === window.parent && w !== window) m.k = FSEC; w.postMessage(m, '*'); } catch (e) {} };
  const pageTitle = () => String((IN_FRAME && fb.up && fb.up.title) || document.title || '').trim();
  const pageHost = () => String((IN_FRAME && fb.up && fb.up.host) || location.hostname).replace(/^(www|m)\./, '');
  const fsElem = () => document.fullscreenElement || document.webkitFullscreenElement || null;
  function safeInsets() {
    if (IN_FRAME) return (fb.up && fb.up.ins) || [0, 0, 0, 0];
    try {
      const d = document.createElement('div');
      d.style.cssText = 'position:fixed;left:0;top:0;width:0;height:0;visibility:hidden;pointer-events:none;' +
        'padding:env(safe-area-inset-top,0px) env(safe-area-inset-right,0px) env(safe-area-inset-bottom,0px) env(safe-area-inset-left,0px)';
      document.documentElement.appendChild(d);
      const cs = getComputedStyle(d);
      const r = [cs.paddingTop, cs.paddingRight, cs.paddingBottom, cs.paddingLeft].map((x) => parseFloat(x) || 0);
      d.remove();
      return r;
    } catch (e) { return [0, 0, 0, 0]; }
  }
  // --- Hauptseite (bzw. Zwischenebene): das eingebettete Fenster groß machen ---
  function frameOf(win) {
    const all = document.querySelectorAll('iframe,frame');
    for (let i = 0; i < all.length; i++) { try { if (all[i].contentWindow === win) return all[i]; } catch (e) {} }
    return null;
  }
  function sendState() {
    if (!fb.childWin) return;
    const fs = IN_FRAME ? !!(fb.up && fb.up.fs) : fsElem() === fb.child;
    fpost(fb.childWin, { t: 'state', fs: fs, ins: safeInsets(), title: pageTitle(), host: pageHost() });
  }
  const STRETCH = { position: 'fixed', left: '0', top: '0', right: '0', bottom: '0', width: '100%', height: '100%',
    'max-width': 'none', 'max-height': 'none', 'min-width': '0', 'min-height': '0', margin: '0', padding: '0', border: '0',
    'border-radius': '0', transform: 'none', translate: 'none', scale: 'none', filter: 'none', 'clip-path': 'none',
    opacity: '1', visibility: 'visible', display: 'block', 'z-index': '2147483647', background: '#000' };
  function stretch(f, win) {
    if (fb.child && fb.child !== f) unstretch();
    fb.childWin = win;
    if (fb.child === f) return;
    fb.child = f;
    fb.iv = setInterval(() => { if (!fb.child || !fb.child.isConnected) unstretch(); }, 1000);   // Fenster entfernt? aufräumen
    const de = document.documentElement;
    let meta = document.querySelector('meta[name="viewport"]'), made = false;
    const metaBefore = meta ? meta.getAttribute('content') : null;
    fb.saved = { style: f.getAttribute('style'), ov: de.style.getPropertyValue('overflow'), ovp: de.style.getPropertyPriority('overflow'), meta: null };
    Object.keys(STRETCH).forEach((k) => f.style.setProperty(k, STRETCH[k], 'important'));
    // Eltern mit transform/filter/contain würden "fixed" auf ihren eigenen Kasten begrenzen, eine niedrige Ebene
    // (z-index) ließe Kopfleisten der Seite darüber liegen – solange der Player offen ist, beides aufheben.
    fb.saved.anc = [];
    for (let a = f.parentElement; a && a !== de; a = a.parentElement) {
      let cs;
      try { cs = getComputedStyle(a); } catch (e) { continue; }
      const fix = {};
      if (cs.transform !== 'none') fix.transform = 'none';
      if (cs.translate && cs.translate !== 'none') fix.translate = 'none';
      if (cs.scale && cs.scale !== 'none') fix.scale = 'none';
      if (cs.rotate && cs.rotate !== 'none') fix.rotate = 'none';
      if (cs.perspective !== 'none') fix.perspective = 'none';
      if (cs.filter !== 'none') fix.filter = 'none';
      if (cs.backdropFilter && cs.backdropFilter !== 'none') fix['backdrop-filter'] = 'none';
      if (cs.contain && cs.contain !== 'none') fix.contain = 'none';
      if (cs.containerType && cs.containerType !== 'normal') fix['container-type'] = 'normal';
      if (/transform|filter|perspective|contents/.test(cs.willChange || '')) fix['will-change'] = 'auto';
      if (cs.zIndex !== 'auto') fix['z-index'] = '2147483647';
      const ks = Object.keys(fix);
      if (!ks.length) continue;
      fb.saved.anc.push({ el: a, old: ks.map((k) => [k, a.style.getPropertyValue(k), a.style.getPropertyPriority(k)]) });
      ks.forEach((k) => a.style.setProperty(k, fix[k], 'important'));
    }
    de.style.setProperty('overflow', 'hidden', 'important');
    // bis in die Kamera-Aussparung zeichnen (die Knöpfe im Player weichen über die gemeldeten Ränder aus)
    if (!IN_FRAME) {
      try {
        if (!meta) { meta = document.createElement('meta'); meta.name = 'viewport'; (document.head || de).appendChild(meta); made = true; }
        const c = metaBefore || 'width=device-width, initial-scale=1';
        if (!/viewport-fit\s*=\s*cover/i.test(c)) meta.setAttribute('content', c.replace(/,?\s*viewport-fit\s*=\s*\w+/i, '') + ', viewport-fit=cover');
        fb.saved.meta = { el: meta, made: made, before: metaBefore };
      } catch (e) {}
    }
  }
  function unstretch() {
    const f = fb.child, s = fb.saved;
    fb.child = null; fb.childWin = null; fb.saved = null;
    clearInterval(fb.iv);
    if (!f || !s) return;
    try { if (fsElem() === f) document.exitFullscreen().catch(() => {}); } catch (e) {}
    try { if (!IN_FRAME && screen.orientation && screen.orientation.unlock) screen.orientation.unlock(); } catch (e) {}
    if (s.style === null) f.removeAttribute('style'); else f.setAttribute('style', s.style);
    (s.anc || []).forEach((x) => x.old.forEach((o) => { x.el.style.removeProperty(o[0]); if (o[1]) x.el.style.setProperty(o[0], o[1], o[2]); }));
    const de = document.documentElement;
    de.style.removeProperty('overflow');
    if (s.ov) de.style.setProperty('overflow', s.ov, s.ovp);
    const m = s.meta;
    if (m) { try { if (m.made) m.el.remove(); else if (m.before !== null) m.el.setAttribute('content', m.before); } catch (e) {} }
  }
  // Würde der Browser jetzt Vollbild erlauben (frische Berührung, auch in einem eingebetteten Fenster)? Ohne die
  // Angabe (ältere Browser) einfach versuchen.
  // Kurz vibrieren (Spulen: Lupe, Abbrechen, Anfang/Ende). Chrome erlaubt das, sobald man die Seite überhaupt einmal
  // angetippt hat – NICHT nur direkt nach einem Tipp (Ziehen zählt nicht als Tipp: daran lag es, dass es oft still blieb)
  function seekBuzz(ms) {
    try {
      if (CONFIG.seekBuzz === false || !navigator.vibrate) return;
      const ua = navigator.userActivation;
      if (ua && !ua.hasBeenActive) return;
      navigator.vibrate(ms || 12);
    } catch (e) {}
  }
  function touchFresh() { try { return !navigator.userActivation || navigator.userActivation.isActive; } catch (e) { return true; } }
  function frameFs(f) {
    try {
      if (fsElem() === f) return;
      // ohne Berührung lehnt Chrome ohnehin ab (und meldet einen Fehler). Firefox zählt den Tipp im eingebetteten
      // Fenster hier oben nicht mit, erlaubt es aber trotzdem – dort also einfach versuchen
      if (!GECKO && !touchFresh()) { sendState(); if (fb.childWin) fpost(fb.childWin, { t: 'fsno' }); return; }
      const rq = f.requestFullscreen || f.webkitRequestFullscreen;
      const pr = rq && rq.call(f, { navigationUI: 'hide' });
      // abgelehnt (Firefox: kein Tipp hier oben) → das Fenster selbst versucht es, dort gilt der Tipp noch
      const no = () => { sendState(); if (fb.childWin) fpost(fb.childWin, { t: 'fsno' }); };
      if (pr && pr.then) pr.then(sendState, no); else if (!rq) no();
    } catch (e) { if (fb.childWin) fpost(fb.childWin, { t: 'fsno' }); }
  }
  addEventListener('message', (e) => {
    const d = e.data;
    if (!d || typeof d !== 'object' || d[FK] !== 1) return;
    // Nachricht von OBEN (Hauptseite an uns)
    if (IN_FRAME && e.source === window.parent) {
      if (d.t === 'info' || d.t === 'state') { fb.up = Object.assign(fb.up || {}, d); fb.ack = true; }
      if (d.t === 'pageurl') { if (fb.childWin) fpost(fb.childWin, d); else if (fb.onPageUrl) fb.onPageUrl(d.url, d.title); }
      if (d.t === 'state') {
        if (fb.childWin) sendState();          // Zwischenebene: weiter nach unten
        else if (fb.onState) fb.onState(d);
      }
      if (d.t === 'fsno') { if (fb.childWin) fpost(fb.childWin, { t: 'fsno' }); else if (fb.onFsNo) fb.onFsNo(); }
      return;
    }
    // Nachricht von UNTEN (aus einem eingebetteten Fenster) – nur mit dem Schlüssel des Skripts
    if (FSEC !== null && (!FSEC || d.k !== FSEC)) return;
    const f = frameOf(e.source);
    if (!f) return;
    if (d.t === 'hello') { fpost(e.source, { t: 'info', title: pageTitle(), host: pageHost(), ins: safeInsets() }); return; }
    // Adresse der Seite nur an das Fenster, das gerade den Player zeigt (nicht an jede eingebettete Werbung)
    if (d.t === 'askurl') {
      if (fb.child !== f) return;
      if (IN_FRAME) fpost(window.parent, { t: 'askurl' });
      else fpost(e.source, { t: 'pageurl', url: location.href.split('#')[0], title: pageTitle() });
      return;
    }
    if (d.t === 'take') {
      if (fb.child !== f) {
        // nur nach einem echten Tipp und für ein sichtbares Fenster (keine Werbung, die sich selbst groß macht)
        // Firefox reicht den Tipp nicht an die Hauptseite weiter – dort zeigt der Fokus auf dem Fenster den Tipp an
        const ua = navigator.userActivation;
        const r = f.getBoundingClientRect();
        if ((ua && !ua.isActive && document.activeElement !== f) || r.width < 100 || r.height < 60) return;
      }
      stretch(f, e.source);
      if (IN_FRAME) fpost(window.parent, { t: 'take', fs: !!d.fs });
      else if (d.fs) frameFs(f);
      sendState();
      return;
    }
    if (fb.child !== f) return;
    if (d.t === 'release') { unstretch(); if (IN_FRAME) fpost(window.parent, { t: 'release' }); return; }
    if (d.t === 'orient') {
      if (IN_FRAME) { fpost(window.parent, { t: 'orient', o: d.o }); return; }
      try {
        if (d.o) screen.orientation.lock(d.o).catch(() => {});
        else if (screen.orientation && screen.orientation.unlock) screen.orientation.unlock();
      } catch (er) {}
      return;
    }
    if (d.t === 'exitfs') {
      if (IN_FRAME) fpost(window.parent, { t: 'exitfs' });
      else { try { if (fsElem() === f) document.exitFullscreen().catch(() => {}); } catch (er) {} }
    }
  });
  const stateSoon = () => { if (fb.child) { sendState(); setTimeout(sendState, 150); setTimeout(sendState, 500); } };
  document.addEventListener('fullscreenchange', stateSoon, true);
  document.addEventListener('webkitfullscreenchange', stateSoon, true);
  addEventListener('resize', stateSoon);
  // Eingebettet: bei der Hauptseite melden (Titel und Ränder kommen zurück)
  if (IN_FRAME) fpost(window.parent, { t: 'hello' });
  // =================================================

  // Gilt für die Seite selbst und alle Unterdomains (z. B. m.youtube.com)
  const onSite = (list) => list.some((d) => {
    const host = location.hostname.toLowerCase();
    d = String(d).toLowerCase().replace(/^\*?\./, '');
    return host === d || host.endsWith('.' + d);
  });
  if (onSite(CONFIG.disabledSites)) return;             // hier ganz aus – nichts wird geladen oder angezeigt
