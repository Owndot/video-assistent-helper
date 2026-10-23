  // ---------- Oberfläche: ein schwebender "Player"-Button ----------
  const CSS = `
:host{all:initial;color-scheme:only light !important;--font:system-ui,-apple-system,"SamsungOne",Roboto,sans-serif}
*{color-scheme:only light !important;forced-color-adjust:none}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
.pill{position:fixed;top:0;left:0;display:flex;align-items:center;gap:2px;height:48px;padding:0 3px;border-radius:24px;
 background:rgba(28,30,40,.36);
 -webkit-backdrop-filter:var(--lens,blur(0px)) blur(var(--gb,12px)) saturate(1.4) brightness(1.1);backdrop-filter:var(--lens,blur(0px)) blur(var(--gb,12px)) saturate(1.4) brightness(1.1);
 color:#fff;box-shadow:0 6px 20px rgba(0,0,0,.28),0 1px 3px rgba(0,0,0,.18);
 opacity:0;transform:translateY(-4px) scale(.96);transform-origin:top right;transition:opacity .22s ease,transform .32s cubic-bezier(.34,1.56,.64,1);
 pointer-events:none;z-index:2147483647;-webkit-user-select:none;user-select:none;-webkit-touch-callout:none}
/* Weiße Glaskante rundum: oben links und unten rechts hell, mit leichtem Farbsaum – auch auf dunklen Seiten sichtbar */
/* Helle Glasteile werden dunkel gezeichnet und per invert(1) umgedreht: so bleiben sie weiß, auch wenn der Browser
   Webseiten zwangsweise abdunkelt (Vivaldi/Chrome "Dunkle Webseiten" dunkelt nur helle Farben ab, dunkle lässt er stehen) */
.pill::before{content:"";position:absolute;inset:0;z-index:1;border-radius:inherit;padding:.8px;filter:invert(1);background:linear-gradient(135deg,rgba(0,0,0,.66) 0%,rgba(0,0,0,.26) 12%,rgba(0,0,0,0) 30%,rgba(0,0,0,0) 68%,rgba(50,27,0,.10) 82%,rgba(0,0,0,.32) 100%);-webkit-mask:linear-gradient(#000 0 0) content-box,linear-gradient(#000 0 0);-webkit-mask-composite:xor;mask-composite:exclude;pointer-events:none}
/* Glanz oben links + Lichtsaum unten rechts */
.pill::after{content:"";position:absolute;inset:0;border-radius:inherit;filter:invert(1);background:radial-gradient(85% 55% at 24% 0%,rgba(0,0,0,.15),rgba(0,0,0,0) 60%),radial-gradient(55% 45% at 80% 108%,rgba(0,0,0,.08),rgba(0,0,0,0) 65%),linear-gradient(180deg,rgba(0,0,0,.07),rgba(0,0,0,.02) 50%,rgba(0,0,0,.05));box-shadow:inset 0 1px 0 rgba(0,0,0,.22),inset 0 -1px 0 rgba(0,0,0,.06),inset 0 0 12px rgba(0,0,0,.05);pointer-events:none}
.pill.on{opacity:1;transform:none;pointer-events:auto;touch-action:none}
.pill.on.scrolling{opacity:0;transform:translateY(-4px) scale(.96);pointer-events:none;transition:opacity .12s ease,transform .12s ease}
.pill.snap{transition:left .24s cubic-bezier(.2,.8,.2,1),top .24s cubic-bezier(.2,.8,.2,1),opacity .2s ease,transform .3s cubic-bezier(.34,1.56,.64,1)}
.pill.drag{opacity:1 !important;transform:scale(1.06);box-shadow:0 16px 36px rgba(0,0,0,.40);transition:none}
.pill .pb{all:unset;position:relative;z-index:1;display:flex;align-items:center;justify-content:center;width:42px;height:42px;border-radius:21px;cursor:pointer;color:#fff;-webkit-touch-callout:none;transition:transform .28s cubic-bezier(.34,1.56,.64,1),background .15s ease}
.pill .pb:active{transform:scale(.86)}
.pill:active{-webkit-backdrop-filter:var(--lens,blur(0px)) blur(var(--gb,12px)) saturate(1.6) brightness(1.45);backdrop-filter:var(--lens,blur(0px)) blur(var(--gb,12px)) saturate(1.6) brightness(1.45)}
.pill .pb:focus-visible{outline:2px solid #fff;outline-offset:1px}
.pill svg{width:23px;height:23px;fill:currentColor;flex:none;filter:drop-shadow(0 1px 1.5px rgba(0,0,0,.55)) drop-shadow(0 0 3px rgba(0,0,0,.25))}
/* feine Trennlinie zwischen den zwei Symbolen */
.pill .dl::after{content:"";position:absolute;right:-1px;top:11px;bottom:11px;width:1px;background:linear-gradient(transparent,rgba(255,255,255,.28),transparent)}
/* HD gefunden: kleiner eisblauer Punkt */
.pill .hdot{position:absolute;top:8px;right:7px;width:7px;height:7px;border-radius:50%;background:#641800;box-shadow:0 0 8px rgba(100,24,0,.9);filter:invert(1);opacity:0;transform:scale(.4);transition:opacity .2s ease,transform .3s cubic-bezier(.34,1.56,.64,1)}
.pill.hd .hdot{opacity:1;transform:none}
@media (prefers-reduced-motion:reduce){.pill,.pill .pb,.pill .hdot{transition:opacity .15s ease !important}}
.diag{position:fixed;left:10px;right:10px;top:10%;bottom:10%;display:flex;flex-direction:column;gap:8px;background:#161616;color:#eee;border-radius:18px;padding:12px;font:13px/1.35 var(--font);box-shadow:0 10px 40px rgba(0,0,0,.5);z-index:2147483647;pointer-events:auto}
.diag .dh{font-weight:700;font-size:15px}
.diag textarea{flex:1;width:100%;background:#000;color:#9f9;border:0;border-radius:10px;padding:8px;font:11px/1.35 monospace;resize:none}
.diag .db{display:flex;gap:8px;justify-content:flex-end}
.diag button{all:unset;background:#3478f6;color:#fff;border-radius:16px;padding:8px 16px;font:600 14px var(--font);cursor:pointer}
.toast{position:fixed;left:50%;bottom:72px;transform:translate(-50%,10px);background:rgba(100,108,130,.44);-webkit-backdrop-filter:blur(18px) saturate(1.4) brightness(1.12);backdrop-filter:blur(18px) saturate(1.4) brightness(1.12);box-shadow:0 10px 28px rgba(0,0,0,.5);text-shadow:0 1px 2px rgba(0,0,0,.45);color:#fff;font:500 14px/1.3 var(--font);padding:11px 18px;border-radius:22px;opacity:0;transition:opacity .2s,transform .2s;pointer-events:none;max-width:86vw;text-align:center;z-index:2147483647}
.toast::before,.toast::after{content:"";position:absolute;inset:0;border-radius:inherit;z-index:-1;filter:invert(1);pointer-events:none}
.toast::before{border:1px solid rgba(0,0,0,.62);z-index:0}
.toast::after{background:linear-gradient(155deg,rgba(0,0,0,.34),rgba(0,0,0,.14) 45%,rgba(0,0,0,.2));box-shadow:0 0 24px rgba(0,0,0,.18),inset 0 1px 1px rgba(0,0,0,.7)}
.toast.on{opacity:1;transform:translate(-50%,0)}
/* Design „Schlicht“ (Einstellungen → Design): helles Milchglas wie Android One UI – keine Glaskante, kein Glanz,
   keine Lupe; das Bild dahinter scheint hell und farbig durch, Schrift/Symbole mit leichtem Schatten gut lesbar */
:host(.flat) .g,:host(.flat) .vb.g,:host(.flat) .pill,:host(.flat) .toast{background:rgba(255,255,255,.17)!important;
 -webkit-backdrop-filter:blur(22px) saturate(1.7) brightness(1.06)!important;backdrop-filter:blur(22px) saturate(1.7) brightness(1.06)!important;
 box-shadow:0 2px 14px rgba(0,0,0,.16)!important}
:host(.flat) .g::before,:host(.flat) .g::after,:host(.flat) .pill::before,:host(.flat) .pill::after,:host(.flat) .toast::before,:host(.flat) .toast::after{display:none!important}

:host(.flat) .vb.g:active,:host(.flat) .vchip:active{background:rgba(255,255,255,.32)!important}
/* Play/±10 mitten im Bild: zurückhaltend – das Video scheint deutlich durch, das Symbol trägt */
:host(.flat) .vmid .g{background:rgba(255,255,255,.05)!important;-webkit-backdrop-filter:blur(2.5px) saturate(1.1)!important;backdrop-filter:blur(2.5px) saturate(1.1)!important;box-shadow:0 1px 6px rgba(0,0,0,.08)!important}
:host(.flat) .vmid .g svg{filter:drop-shadow(0 1px 3px rgba(0,0,0,.55))}
:host(.flat) .vmenu{background:rgba(72,74,84,.46)!important;-webkit-backdrop-filter:blur(30px) saturate(1.6) brightness(1.04)!important;backdrop-filter:blur(30px) saturate(1.6) brightness(1.04)!important}
:host(.flat) .vmi,:host(.flat) .vmh,:host(.flat) .toast{text-shadow:0 1px 2px rgba(0,0,0,.4)}
:host(.flat) .vside{background:rgba(255,255,255,.17)!important}`;

  function h(tag, props) {
    const el = document.createElement(tag);
    if (props) for (const k in props) {
      if (k === 'class') el.className = props[k];
      else el.setAttribute(k, k === 'aria-label' || k === 'title' ? tr(props[k]) : props[k]);
    }
    for (let i = 2; i < arguments.length; i++) { const c = arguments[i]; if (c != null) el.append(typeof c === 'string' ? tr(c) : c); }
    return el;
  }
  function icon(d) {
    const s = document.createElementNS(NS, 'svg');
    s.setAttribute('viewBox', '0 0 24 24');
    const p = document.createElementNS(NS, 'path');
    p.setAttribute('d', d);
    s.appendChild(p);
    return s;
  }

  let hostEl = null, root = null, pill = null, toastEl = null;
  let active = null, toastTimer = 0, pressTimer = 0, longPressed = false;

  // Position nur neu berechnen, wenn sich etwas bewegt hat (Scrollen, Drehen, Größe, Verschieben)
  // – höchstens einmal pro Bild, statt dauernd bei jedem Bild
  let placeQueued = false;
  function schedulePlace() {
    if (placeQueued) return;
    placeQueued = true;
    requestAnimationFrame(() => { placeQueued = false; place(); });
  }
  let resizeObs = null;
  function watchSize(v) {
    try {
      if (!resizeObs) resizeObs = new ResizeObserver(schedulePlace);
      resizeObs.disconnect();
      if (v) resizeObs.observe(v);
    } catch (e) {}
  }
  const onMove = () => { if (pill && pill.classList.contains('on')) schedulePlace(); };
  // Wie bei Samsung: beim Scrollen kurz weg, erscheint wieder, sobald nicht mehr gescrollt wird
  let scrollTimer = 0;
  const onScroll = () => {
    if (pill) pill.classList.add('scrolling');
    clearTimeout(scrollTimer);
    scrollTimer = setTimeout(() => { if (pill) { pill.classList.remove('scrolling'); schedulePlace(); } }, 380);
    onMove();
  };
  addEventListener('scroll', onScroll, { capture: true, passive: true });   // auch Scrollen in Unterbereichen (Feeds)
  addEventListener('resize', onMove, { passive: true });
  addEventListener('orientationchange', onMove, { passive: true });
  if (window.visualViewport) {
    visualViewport.addEventListener('resize', onMove, { passive: true });
    visualViewport.addEventListener('scroll', onMove, { passive: true });
  }
  // HD gefunden? Kleiner eisblauer Punkt am Player-Symbol (statt Text)
  function setLabel(t) {
    if (!pill) return;
    const hd = /HD/.test(t);
    if (pill.classList.contains('hd') !== hd) pill.classList.toggle('hd', hd);
    const b = pill.querySelector('button[aria-label^="Im Videoplayer"]');
    if (b) b.title = hd ? (/Full/.test(t) ? 'Im Player öffnen (Full HD)' : 'Im Player öffnen (HD)') : 'Im Player öffnen';
  }

  // ---------- Position merken (pro Webseite) ----------
  let drag = null, dragPos = null, suppressClick = false;
  const POS_KEY = 'evp:pos';
  function loadPos() {
    try {
      const p = JSON.parse(localStorage.getItem(POS_KEY) || 'null');
      if (!p || !isFinite(p.fy)) return null;
      if (p.side !== 'left' && p.side !== 'right') p.side = isFinite(p.fx) && p.fx < 0.5 ? 'left' : 'right';   // ältere Version
      return { side: p.side, fy: p.fy };
    } catch (e) { return null; }
  }
  let savedPos = loadPos();
  // Gespeichert wird: welche Seite (links/rechts) und die Höhe relativ zum Video (0 = oben, 1 = unten).
  // Beim Loslassen rastet der Button am näheren Rand ein – nie in der Mitte.
  function savePos() {
    if (!active || !pill) return;
    const r = rectOf(active);
    if (!r.width || !r.height) return;
    const left = parseFloat(pill.style.left) || 0, top = parseFloat(pill.style.top) || 0;
    const cx = left + pill.offsetWidth / 2, cy = top + pill.offsetHeight / 2;
    savedPos = {
      side: CONFIG.buttonAt === 'top' ? (cx < innerWidth / 2 ? 'left' : 'right') : (cx < r.left + r.width / 2 ? 'left' : 'right'),
      fy: Math.min(1, Math.max(0, (cy - r.top) / r.height)),
    };
    try { localStorage.setItem(POS_KEY, JSON.stringify(savedPos)); } catch (e) {}
    // sanft an den Rand gleiten lassen
    pill.classList.add('snap');
    setTimeout(() => { if (pill) pill.classList.remove('snap'); }, 260);
    toast('Position gespeichert (' + (savedPos.side === 'left' ? 'links' : 'rechts') + ')');
  }

  // ---------- Lupen-Glas (wie iOS 26 / macOS) ----------
  // Zu jeder Glasfläche (Größe + Rundung) eine Verschiebungskarte: am Rand wird das Bild dahinter nach innen
  // gebogen (wie dickes Glas), in der Mitte leicht vergrößert (Lupe). Läuft als SVG-Filter im Hintergrund-Filter
  // (backdrop-filter) – das kann nur Chrome/Chromium; sonst bleibt es beim Milchglas.
  // LENS_CAN = der Browser kann es (Chrome); ob es an ist, entscheidet die Einstellung – auch sofort umschaltbar
  const lensOn = () => LENS_CAN && CONFIG.lensGlass !== false;
  const LENS_CAN = /Chrome\//.test(navigator.userAgent) &&
    (() => { try { return window.CSS.supports('backdrop-filter', 'url(#a) blur(1px)'); } catch (e) { return false; } })();   // ("CSS" ist hier unser Stil-Text)
  const lensCache = new Map(), lensPre = 'l' + Math.random().toString(36).slice(2, 8);
  let lensSvg = null, lensDefs = null;
  // Wellen-Glas: die Mitte bleibt fast ruhig (zoom = nur ganz leichte Vergrößerung), zum Rand hin läuft das Bild in
  // sanften Wellen (waveA) und biegt sich am Rand stark nach innen (rimA). half = größte Verschiebung in Pixeln.
  function lensMap(w, h, r, rimA, waveA, zoom, half) {
    const R0 = Math.min(w, h) / 2;
    const c = document.createElement('canvas');
    c.width = w; c.height = h;
    const ctx = c.getContext('2d');
    const img = ctx.createImageData(w, h), d = img.data;
    for (let y = 0; y < h; y++) {
      for (let x = 0; x < w; x++) {
        const px = x + 0.5, py = y + 0.5;
        const cx = Math.min(Math.max(px, r), w - r), cy = Math.min(Math.max(py, r), h - r);
        let vx = px - cx, vy = py - cy, edge;
        const len = Math.hypot(vx, vy);
        if (len > 0) { edge = r - len; vx /= len; vy /= len; }
        else {
          const dl = px - r, dr = w - r - px, dt = py - r, db = h - r - py, m = Math.min(dl, dr, dt, db);
          edge = r + m;
          vx = m === dl ? -1 : m === dr ? 1 : 0;
          vy = vx ? 0 : (m === dt ? -1 : 1);
        }
        // u: 0 am Rand … 1 in der Mitte; Verschiebung nach innen = das Bild wird an dieser Stelle "herausgezogen"
        const u = Math.max(0, Math.min(1, edge / R0)), q = 1 - u;
        // (Steigung bleibt überall unter 1 – sonst überschlägt sich das Bild und Text erscheint gespiegelt)
        const off = rimA * q * q + waveA * Math.sin(2 * Math.PI * 1.25 * u) * q;
        const ox = (-(px - w / 2) * (1 - 1 / zoom) - vx * off) / half;
        const oy = (-(py - h / 2) * (1 - 1 / zoom) - vy * off) / half;
        const i = (y * w + x) * 4;
        d[i] = Math.round(128 + 127 * Math.max(-1, Math.min(1, ox)));
        d[i + 1] = Math.round(128 + 127 * Math.max(-1, Math.min(1, oy)));
        d[i + 2] = 128;
        d[i + 3] = 255;
      }
    }
    ctx.putImageData(img, 0, 0);
    return c.toDataURL();
  }
  function lensFor(w, h, r) {
    r = Math.max(0, Math.min(r, w / 2, h / 2));
    const key = w + 'x' + h + 'r' + Math.round(r);
    if (lensCache.has(key)) return lensCache.get(key);
    // Filter liegen im selben Shadow-DOM wie die Glasflächen – nur dort findet Chrome sie (url(#…) gilt je Baum)
    if (!lensSvg) {
      lensSvg = document.createElementNS(NS, 'svg');
      lensSvg.setAttribute('width', '0'); lensSvg.setAttribute('height', '0'); lensSvg.setAttribute('aria-hidden', 'true');
      lensSvg.style.cssText = 'position:absolute;width:0;height:0;overflow:hidden';
      lensDefs = document.createElementNS(NS, 'defs');
      lensSvg.appendChild(lensDefs);
    }
    if (root && lensSvg.parentNode !== root) root.appendChild(lensSvg);
    const m = Math.min(w, h), big = Math.max(w, h);
    const zoom = 1.04, R0 = m / 2;
    const rimA = Math.min(14, R0 * 0.27), waveA = Math.min(4.5, R0 * 0.048);
    const half = Math.ceil(big / 2 * (1 - 1 / zoom) + rimA + waveA + 1), scale = half * 2;
    const id = lensPre + lensCache.size;
    const f = document.createElementNS(NS, 'filter');
    f.setAttribute('id', id);
    f.setAttribute('x', '0%'); f.setAttribute('y', '0%'); f.setAttribute('width', '100%'); f.setAttribute('height', '100%');
    f.setAttribute('color-interpolation-filters', 'sRGB');
    const fi = document.createElementNS(NS, 'feImage');
    const url = lensMap(w, h, r, rimA, waveA, zoom, half);
    fi.setAttribute('href', url);
    fi.setAttributeNS('http://www.w3.org/1999/xlink', 'xlink:href', url);   // ältere Chromium-Versionen kennen nur diese Form
    fi.setAttribute('x', '0'); fi.setAttribute('y', '0'); fi.setAttribute('width', String(w)); fi.setAttribute('height', String(h));
    fi.setAttribute('preserveAspectRatio', 'none'); fi.setAttribute('result', 'm');
    const dm = document.createElementNS(NS, 'feDisplacementMap');
    dm.setAttribute('in', 'SourceGraphic'); dm.setAttribute('in2', 'm'); dm.setAttribute('scale', String(Math.round(scale)));
    dm.setAttribute('xChannelSelector', 'R'); dm.setAttribute('yChannelSelector', 'G');
    f.append(fi, dm);
    lensDefs.appendChild(f);
    lensCache.set(key, id);
    return id;
  }
  function applyLens(el) {
    if (!lensOn() || !el || !el.isConnected) return;
    const w = Math.round(el.offsetWidth), h = Math.round(el.offsetHeight);
    if (w < 16 || h < 16 || w * h > 420000) { el.style.removeProperty('--lens'); el.style.removeProperty('--gb'); return; }
    let r = 0;
    try { r = parseFloat(getComputedStyle(el).borderTopLeftRadius) || 0; } catch (e) {}
    const id = lensFor(w, h, r), val = 'url(#' + id + ')';
    el.style.setProperty('--lens', val);
    el.style.setProperty('--gb', '2px');   // mit Wellen-Glas nur ein wenig Weichzeichner – sonst sieht man die Wellen nicht
    // Chrome lädt das Kartenbild erst beim ersten Zeichnen und rechnet ruhige Flächen danach nicht neu →
    // kurz danach einmal ab- und wieder anschalten (bei laufendem Video passiert das ohnehin ständig)
    if (!el.__lensSeen) el.__lensSeen = {};
    if (!el.__lensSeen[id]) {
      el.__lensSeen[id] = true;
      const again = () => {
        if (el.style.getPropertyValue('--lens') !== val) return;
        el.style.setProperty('--lens', 'blur(0px)');
        requestAnimationFrame(() => requestAnimationFrame(() => { if (el.style.getPropertyValue('--lens') === 'blur(0px)') el.style.setProperty('--lens', val); }));
      };
      setTimeout(again, 180);
      setTimeout(again, 900);
    }
  }
  const lensRO = LENS_CAN && window.ResizeObserver ? new ResizeObserver((es) => es.forEach((e) => applyLens(e.target))) : null;
  // Glas sofort umschalten: Wellen/Lupe ↔ Milchglas (ohne --lens greift der normale Weichzeichner)
  function applyDesign() { if (hostEl) hostEl.classList.toggle('flat', CONFIG.lensGlass === false); }
  function setLens(on) {
    setSetting('lensGlass', !!on);
    applyDesign();
    const all = root ? Array.from(root.querySelectorAll('.g')) : [];
    all.forEach((el) => {
      if (on) lensWatch(el);
      else { el.style.removeProperty('--lens'); el.style.removeProperty('--gb'); if (lensRO) lensRO.unobserve(el); }
    });
  }
  function lensWatch(el) {
    if (!lensOn() || !el) return;
    // Play, ±10 und das Menü ohne Lupen-Effekt – so gewünscht
    if (el.classList && (el.classList.contains('vplay') || el.classList.contains('vskip') || el.classList.contains('vmenu'))) return;
    applyLens(el);
    if (lensRO) lensRO.observe(el);
  }

  function ui() {
    if (root) return;
    // Zufälliger Name + Stile mit !important: manche Seiten blenden fremde Elemente aus (z. B. gegen Werbeblocker)
    hostEl = document.createElement('x-' + Math.random().toString(36).slice(2, 9));
    hostStyle();
    root = hostEl.attachShadow({ mode: 'closed' });
    applyDesign();
    const st = document.createElement('style');
    st.textContent = CSS;
    const btn = h('button', { class: 'pb', 'aria-label': 'Im Videoplayer öffnen' }, icon(I.player), h('span', { class: 'hdot', 'aria-hidden': 'true' }));
    const dlBtn = h('button', { class: 'pb dl', 'aria-label': 'Herunterladen (z. B. mit YTDLnis)' }, icon(I.download));
    dlBtn.addEventListener('click', () => onDownload());
    dlBtn.addEventListener('contextmenu', (e) => { e.preventDefault(); onDownload(null, { share: true }); });   // lange drücken: Teilen
    // Auf "nur Download"-Seiten (z. B. YouTube) gibt es keinen Player-Button
    // Download-Button nur auf Seiten, die YTDLnis kann
    // Der eingebaute Player geht überall – auch auf "nur Download"-Seiten wie YouTube
    pill = h('div', { class: 'pill' }, btn);   // Download gibt es im großen Player
    // Ein Tipp öffnet direkt (Verschieben zählt nicht als Tipp)
    pill.addEventListener('click', (e) => {
      if (suppressClick) {           // das war ein Verschieben, kein Tippen
        suppressClick = false;
        e.stopPropagation();
        e.preventDefault();
        return;
      }
    }, true);
    toastEl = h('div', { class: 'toast' });
    root.append(st, pill, toastEl);

    // Verschieben: Finger auf den Button legen und ziehen – die Stelle wird gespeichert
    pill.addEventListener('pointerdown', (e) => {
      drag = { x0: e.clientX, y0: e.clientY, left0: parseFloat(pill.style.left) || 0, top0: parseFloat(pill.style.top) || 0, moved: false };
    }, true);
    pill.addEventListener('pointermove', (e) => {
      if (!drag) return;
      const dx = e.clientX - drag.x0, dy = e.clientY - drag.y0;
      if (!drag.moved) {
        if (Math.abs(dx) + Math.abs(dy) < 10) return;   // kleine Zitterbewegung beim Tippen ignorieren
        drag.moved = true;
        clearTimeout(pressTimer);
        longPressed = false;
        try { pill.setPointerCapture(e.pointerId); } catch (err) {}
        pill.classList.add('drag');
      }
      dragPos = { left: drag.left0 + dx, top: drag.top0 + dy };
      schedulePlace();
      e.preventDefault();
    }, true);
    const endDrag = (save) => {
      if (drag && drag.moved) {
        if (save) savePos();
        suppressClick = true;
        setTimeout(() => { suppressClick = false; }, 400);
        pill.classList.remove('drag');
      }
      drag = null;
      dragPos = null;
      schedulePlace();   // an den Rand gleiten lassen
    };
    pill.addEventListener('pointerup', () => endDrag(true), true);
    pill.addEventListener('pointercancel', () => endDrag(true), true);

    // Kurz tippen = im Player öffnen · lange drücken = Diagnose kopieren
    btn.addEventListener('pointerdown', () => {
      longPressed = false;
      clearTimeout(pressTimer);
      pressTimer = setTimeout(() => { longPressed = true; toast('Loslassen, um die Diagnose zu kopieren'); }, 700);
    });
    btn.addEventListener('pointerup', () => {
      clearTimeout(pressTimer);
      if (longPressed) copyText(diag(active, resolve(active)), 'Diagnose kopiert');
    });
    btn.addEventListener('pointercancel', () => { clearTimeout(pressTimer); longPressed = false; });
    btn.addEventListener('contextmenu', (e) => e.preventDefault());
    btn.addEventListener('click', () => {
      if (longPressed) { longPressed = false; return; }
      onPlayerTap();
    });

    ['click', 'pointerdown', 'pointerup', 'touchstart', 'touchend', 'mousedown', 'mouseup'].forEach((t) =>
      hostEl.addEventListener(t, (e) => e.stopPropagation()));
    attachHost();
  }
  function hostStyle() {
    const s = hostEl.style, imp = (k, v) => s.setProperty(k, v, 'important');
    imp('all', 'initial');
    imp('display', 'block'); imp('position', 'fixed'); imp('top', '0'); imp('left', '0');
    imp('width', '0'); imp('height', '0'); imp('margin', '0'); imp('padding', '0');
    imp('visibility', 'visible'); imp('opacity', '1'); imp('z-index', '2147483647');
    imp('transform', 'none'); imp('filter', 'none'); imp('clip-path', 'none'); imp('contain', 'none');
    imp('overflow', 'visible'); imp('pointer-events', 'auto');
    // Vom erzwungenen Dunkelmodus des Browsers (Vivaldi/Chrome "Dunkle Webseiten") ausnehmen – sonst wird das Glas schwarz eingefärbt
    imp('color-scheme', 'only light');
  }
  let useBody = false;
  function attachHost() {
    if (!hostEl) return;
    const fs = document.fullscreenElement || document.webkitFullscreenElement;
    if (fs && (fs === hostEl || hostEl.contains(fs))) return;   // unser eigener Player ist im Vollbild
    const parent = fs && fs.tagName !== 'VIDEO' && fs.tagName !== 'IFRAME' && fs.tagName !== 'FRAME' ? fs : (useBody && document.body) || document.documentElement;
    if (parent && hostEl.parentNode !== parent) parent.appendChild(hostEl);
    // Hat die Seite unseren Stil verändert? Wiederherstellen.
    if (hostEl.style.getPropertyPriority('display') !== 'important') hostStyle();
    // Immer noch unsichtbar? Dann im <body> statt direkt in <html> versuchen.
    try {
      if (!useBody && getComputedStyle(hostEl).display === 'none' && document.body) { useBody = true; document.body.appendChild(hostEl); }
    } catch (e) {}
  }
  document.addEventListener('fullscreenchange', attachHost, true);
  document.addEventListener('webkitfullscreenchange', attachHost, true);

  function toast(msg) {
    ui();
    // im Player: eine feste Stelle für alle Hinweise (unter dem Bild) – die Gesten-Anzeige macht so lange Platz
    const pw = toastEl.parentElement;
    toastEl.textContent = tr(msg);
    if (pw && pw.classList && pw.classList.contains('vp')) {
      const ind = pw.querySelector('.vind'); if (ind) ind.classList.remove('on');
      if (vp && vp.notePos) vp.notePos(toastEl); else toastEl.style.top = '';
    } else toastEl.style.top = '';
    toastEl.classList.add('on');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => toastEl.classList.remove('on'), 2400);
  }

