  function openBrowserPlayer(orig) {
    if (!orig) return;
    if (vp) closePlayer();
    ui();
    hidePill();
    if (!root.querySelector('style.vpcss')) { const s = document.createElement('style'); s.className = 'vpcss'; s.textContent = VP_CSS; root.append(s); }

    const srcRes = {};
    const st = {
      orig: orig, sources: FB_MIRROR && (orig.captureStream || orig.mozCaptureStream) ? [] : playerSources(orig, srcRes), srcRes: srcRes, idx: 0, hls: null, mode: 'own', cur: '',
      startTime: orig.currentTime || 0, loadTimer: 0, hideTimer: 0, u2: 0, locked: false,
      dim: 0, fit: 'contain', speed: 1, wasFs: false, cleanup: [], barDrag: false, orient: '',
      level: 1,      // Lautstärke 0–2 (über 1 = Verstärkung)
      bright: 1,     // Helligkeit 0,15–1,5 (unter 1 = abdunkeln, über 1 = Bild aufhellen)
      actx: null, gain: null, srcNode: null, boostWarned: false,
      framed: IN_FRAME, rfs: false, fsWait: null,   // eingebettetes Fenster: Hauptseite übernimmt Vollbild/Drehen
    };
    vp = st;
    // Tonverstärkung vorbereiten, solange der Tipp auf "Player" noch "frisch" ist (sonst startet der Ton stumm)
    try {
      const AC = window.AudioContext || window.webkitAudioContext;
      if (AC) { st.actx = new AC(); if (st.actx.state === 'suspended') st.actx.resume().catch(() => {}); }
    } catch (e) { st.actx = null; }

    // ----- Oberfläche -----
    const wrap = h('div', { class: 'vp ctl' });
    const vid = document.createElement('video');
    vid.className = 'vv';
    vid.setAttribute('playsinline', '');
    vid.setAttribute('webkit-playsinline', '');
    vid.preload = 'auto';
    const dimEl = h('div', { class: 'vdim' });
    const amb = document.createElement(FF_MOBILE ? 'video' : 'canvas');   // Ambient-Modus (siehe unten)
    amb.className = 'vamb';
    if (FF_MOBILE) {
      amb.muted = true; amb.setAttribute('playsinline', ''); amb.disablePictureInPicture = true;
      amb.style.objectFit = 'fill';
    } else { amb.width = 32; amb.height = 18; }
    const gest = h('div', { class: 'vgest' });
    // Anzeige für Gesten (Text + Füllstand) als Glaskapsel
    const indTxt = h('span', null, '');
    const indFill = h('i');
    const ind = h('div', { class: 'vind g', 'aria-live': 'polite' }, indTxt);
    const seekBig = h('b', null, '0:00'), seekRest = h('small', null, ''), seekDelta = h('i', null, '');
    const seekBox = h('div', { class: 'vseek', 'aria-live': 'polite' }, seekBig, seekRest, seekDelta);
    // Anzeige fürs doppelte Tippen: Glasscheibe mit Kreispfeil (vor = gespiegelt) – die Zahl zeigt die Sekunden
    const mkSfx = (dir) => {
      const sv = document.createElementNS(NS, 'svg');
      sv.setAttribute('viewBox', '0 0 24 24');
      const mir = document.createElementNS(NS, 'g');
      if (dir > 0) mir.setAttribute('transform', 'matrix(-1 0 0 1 24 0)');
      const spin = document.createElementNS(NS, 'g');
      spin.setAttribute('class', 'spin');
      const arc = document.createElementNS(NS, 'path');
      arc.setAttribute('class', 'arc'); arc.setAttribute('d', 'M12 4.6 A8.4 8.4 0 1 1 4.73 8.8');
      const head = document.createElementNS(NS, 'path');
      head.setAttribute('class', 'head'); head.setAttribute('d', 'M8.7 4.6 L12.6 2 L12.6 7.2 Z');
      spin.append(arc, head); mir.append(spin);
      const t = document.createElementNS(NS, 'text');
      t.setAttribute('x', '12'); t.setAttribute('y', '15.7'); t.setAttribute('text-anchor', 'middle');
      t.textContent = String(SKIP());
      sv.append(mir, t);
      const el = h('div', { class: 'vsfx g ' + (dir > 0 ? 'r' : 'l'), 'aria-live': 'polite' }, h('i'), sv);
      el.lab = t;
      return el;
    };
    const sfxL = mkSfx(-1), sfxR = mkSfx(1);
    const miniFill = h('i'), mini = h('div', { class: 'vmini', 'aria-hidden': 'true' }, miniFill);
    const sideMark = h('u'), sideTxt = h('b', null, ''), sideIco = icon(P.vol);
    const side = h('div', { class: 'vside g r', 'aria-live': 'polite' }, indFill, sideMark, sideTxt, sideIco);
    const spin = h('div', { class: 'vspin on' });
    const ib = (ic, label, cls) => { const b = h('button', { class: 'vb' + (cls ? ' ' + cls : ''), 'aria-label': label }); b.append(icon(ic)); return b; };
    const chip = (ic, label, aria) => { const b = h('button', { class: 'vchip', 'aria-label': aria }); b.append(icon(ic), h('span', null, label)); return b; };
    const bClose = ib(P.close, 'Schließen', 'g');
    const tSpan = h('span'), tSmall = h('small');
    const title = h('div', { class: 'vtitle' }, tSpan, tSmall);
    // Titel der Seite (bei eingebetteten Fenstern: der Hauptseite) – ohne Titel nur die Adresse, nicht doppelt
    const setTitle = () => { const t = pageTitle(), ho = pageHost(); tSpan.textContent = (t || ho).slice(0, 90); tSmall.textContent = t ? ho : ''; tSmall.hidden = !t; };
    setTitle();
    const bSpeed = h('button', { class: 'vb vtext', 'aria-label': 'Geschwindigkeit' }, '1×');
    const bPip = document.pictureInPictureEnabled ? ib(I.pip, 'Bild-in-Bild', 'vtool') : null;
    const bExt = DOWNLOAD_ONLY ? null : ib(I.open, 'In externer App öffnen');
    const bDl = ib(I.download, 'Herunterladen (z. B. mit YTDLnis)');
    const bShare = ib(I.share, 'Link teilen');
    bDl.hidden = !SHOW_DOWNLOAD;   // wird sichtbar, sobald eine echte Videodatei läuft
    const bBack = h('button', { class: 'vb vskip g', 'aria-label': SKIP() + ' s zurück' }, h('i', { class: 'ring' }), skipIcon(false));
    const bPlay = ib(P.pause, 'Wiedergabe', 'vplay g');
    const bFwd = h('button', { class: 'vb vskip g', 'aria-label': SKIP() + ' s vor' }, h('i', { class: 'ring' }), skipIcon(true));
    const tCur = h('span', { class: 'vtime' }, '0:00');
    const tDur = h('span', { class: 'vtime' }, '0:00');
    const bar = h('input', { type: 'range', min: '0', max: '1000', value: '0', class: 'vbar', 'aria-label': 'Position' });
    const bQual = chip(P.hd, 'Auto', 'Qualität');
    const bFit = chip(P.fit, 'Format', 'Bildformat');
    const bRot = chip(P.rotate, 'Drehen', 'Drehen');
    const bLock = chip(P.lock, 'Sperren', 'Bedienung sperren');
    // Aufteilung (16.4, „oben schlank“): oben nur Download und ⋮ (Titel bleibt lesbar), unten am Daumen CC,
    // Bildformat, Bild-in-Bild, Drehen. Teilen, In App öffnen, Qualität, Tempo (16.5), Tonspur, Standbild, Einstellungen: im ⋮-Menü.
    // Teilen/In App/Qualität/Tempo bleiben als unsichtbare Knöpfe (.vstash) bestehen – das Menü löst sie aus.
    const bMore = ib(I.more, 'Mehr');
    const bSubs = chip(P.cc, 'Untertitel', 'Untertitel');   // unten: Untertitel (leuchtet, wenn an)
    const bRep = chip(P.repeat, 'Wiederholen', 'Wiederholen');   // unten: Aus → 1× → ∞ → Aus
    const repNum = h('b', { class: 'vrepn' }, ''); bRep.append(repNum);
    const subEl = h('div', { class: 'vsub', 'aria-live': 'polite' });
    const bUnlock = ib(P.lock, 'Entsperren', 'vunlock g');
    const menu = h('div', { class: 'vmenu g', role: 'menu' });
    bQual.hidden = true;   // erscheint, sobald es Qualitätsstufen gibt
    wrap.append(amb, vid, dimEl, subEl, gest, spin,
      h('div', { class: 'vchrome' },
        h('div', { class: 'vscrim' }),
        h('div', { class: 'vtop' }, bClose, title, h('div', { class: 'vcluster g ia' }, bDl, bMore)),
        h('div', { class: 'vmid' }, bBack, bPlay, bFwd),
        h('div', { class: 'vbot ia' }, h('div', { class: 'vrow' }, tCur, bar, tDur), h('div', { class: 'vhead' }, h('div', { class: 'vtools g solo' }, bLock), h('div', { class: 'vtools g' }, bSubs, bRep, bFit, bPip, bRot))), h('div', { class: 'vstash' }, bShare, bExt, bQual, bSpeed)),
      ind, side, seekBox, sfxL, sfxR, mini, menu, bUnlock);
    root.append(wrap);
    // Lupen-Glas für alle Glasflächen (Größe kommt erst nach dem Einblenden; ändert sie sich, wird neu berechnet)
    requestAnimationFrame(() => wrap.querySelectorAll('.g').forEach(lensWatch));
    st.cleanup.push(() => { if (lensRO) wrap.querySelectorAll('.g').forEach((e) => lensRO.unobserve(e)); });
    // Der Lichtschein (Ambient) ragt über den Rand hinaus – der Player-Bereich darf dadurch nie verrutschen
    wrap.addEventListener('scroll', () => { if (wrap.scrollTop || wrap.scrollLeft) { wrap.scrollTop = 0; wrap.scrollLeft = 0; } }, { passive: true });
    if (toastEl) { wrap.append(toastEl); toastEl.classList.add('g'); lensWatch(toastEl); }   // Hinweise IM Player (im Vollbild wäre alles daneben unsichtbar) – gleiches Glas wie die Knöpfe
    st.wrap = wrap; st.vid = vid;
    st.aspect = 'contain'; st.zoom = 1; st.panX = 0; st.panY = 0; st.qualPinned = null;

    const M = () => (st.mode === 'capture' ? orig : vid);   // was gerade steuert: eigenes Video oder das Original
    // Ton beim Spiegeln: Chrome spielt ihn weiter über das Original. Firefox (mozCaptureStream) leitet ihn in die
    // Spiegelung um – dort kommt er also aus unserem Video, und die Lautstärke wird dort geregelt.
    const VOL = () => (st.mode === 'capture' && !FF_CAP ? orig : vid);

