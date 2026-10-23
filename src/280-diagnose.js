  // ---------- Diagnose auch ohne Button: über das Violentmonkey-Menü ----------
  function biggestVideo() {
    let best = null, area = 0;
    allVideos().forEach((v) => { const a = visibleArea(v); if (a > area) { area = a; best = v; } });
    return best || allVideos()[0] || null;
  }
  function videoReport() {
    const vids = allVideos();
    const lines = ['Rahmen: ' + (window.top === window ? 'Hauptseite' : 'eingebettet (iframe) auf ' + location.hostname),
      'Alle Videos (' + vids.length + ', davon im Shadow-DOM: ' + shadowVids.length + '):'];
    vids.slice(0, 8).forEach((v, i) => {
      const r = v.getBoundingClientRect();
      lines.push(' ' + (i + 1) + '. ' + Math.round(r.width) + '×' + Math.round(r.height) +
        ' | ' + (v.paused ? 'pausiert' : 'läuft') + ' | sichtbar ' + Math.round(visibleArea(v)) +
        ' | ' + String(v.currentSrc || v.src || '-').slice(0, 60));
    });
    const fr = document.getElementsByTagName('iframe');
    lines.push('Eingebettete Fenster (iframes): ' + fr.length + (fr.length ? ' – ' + Array.prototype.slice.call(fr, 0, 4).map((f) => hostOf(f.src) || '-').join(', ') : ''));
    lines.push('Download-Button hier: ' + (SHOW_DOWNLOAD ? 'ja' : 'nein') + ' | nur Download: ' + (DOWNLOAD_ONLY ? 'ja' : 'nein'));
    try {
      lines.push('Oberfläche: ' + (!hostEl ? 'nicht erstellt' :
        (hostEl.isConnected ? 'eingesetzt in ' + (hostEl.parentNode && hostEl.parentNode.nodeName) : 'von der Seite entfernt') +
        ' | display=' + getComputedStyle(hostEl).display + ' | visibility=' + getComputedStyle(hostEl).visibility +
        ' | Button: ' + (pill ? pill.className + ' @ ' + pill.style.left + ',' + pill.style.top + ' | Breite ' + pill.offsetWidth : '-') +
        ' | Bereich: ' + (typeof GM_info !== 'undefined' && GM_info && GM_info.injectInto ? GM_info.injectInto : '?')));
    } catch (e) {}
    return lines.join('\n');
  }
  function showDiagBox(text) {
    ui();
    const box = h('div', { class: 'diag' });
    const ta = h('textarea', { readonly: 'readonly' });
    ta.value = text;
    const copy = h('button', null, 'Kopieren');
    const close = h('button', null, 'Schließen');
    copy.addEventListener('click', () => { ta.select(); copyText(text, 'Diagnose kopiert'); });
    close.addEventListener('click', () => box.remove());
    box.append(h('div', { class: 'dh' }, 'Video-Assistent · Diagnose'), ta, h('div', { class: 'db' }, copy, close));
    root.append(box);
    attachHost();
    // Prüfen, ob das Fenster wirklich sichtbar ist – sonst Notlösung im Aufrufer
    if (!hostEl || !hostEl.isConnected || !box.getBoundingClientRect().height) { box.remove(); throw new Error('Fenster nicht sichtbar'); }
  }
  function openDiag() {
    let text = '';
    try {
      const v = active || biggestVideo();
      text = diag(v, resolve(v)) + '\n' + videoReport();
    } catch (e) {
      text = 'Fehler beim Erstellen der Diagnose: ' + ((e && (e.stack || e.message)) || e);
      try { text += '\n' + videoReport(); } catch (e2) {}
    }
    try {
      showDiagBox(text);
    } catch (e) {
      // Notlösung: Eingabefeld des Browsers – dort lässt sich der Text markieren und kopieren
      try { prompt('Diagnose – alles markieren und kopieren:', text.replace(/\n/g, ' | ')); }
      catch (e2) { try { alert(text); } catch (e3) {} }
    }
  }
  try {
    const reg = (typeof GM_registerMenuCommand === 'function' && GM_registerMenuCommand) ||
      (typeof GM !== 'undefined' && GM && typeof GM.registerMenuCommand === 'function' && GM.registerMenuCommand);
    if (reg) reg(window.top === window ? 'Diagnose anzeigen' : 'Diagnose anzeigen (eingebettet: ' + location.hostname + ')', openDiag);
  } catch (e) {}

  // ---------- Diagnose (lange auf "Player" drücken) ----------
  function diag(v, r) {
    return [
      'Video-Assistent 19.15',
      'Seite: ' + location.host + location.pathname,
      'Video-src: ' + (v ? String(v.currentSrc || v.src || '-').slice(0, 90) : 'kein Video'),
      'Ergebnis: ' + (r.how || 'nichts') + (r.best ? ' – ' + r.best.slice(0, 110) : ''),
      'Zuordnung über: ' + (lastVia || '-') + ' | Laufendes Video: ' + (function () { const f = assetFor(v); return (f.asset || '-') + ' (' + f.via + (f.sure ? ', sicher' : '') + ')'; })() +
        ' | geladene Videos: ' + Array.from(assetHits.keys()).slice(-4).map((a) => a + '×' + assetHits.get(a).length).join(', '),
      'Link zum Video: ' + (function () { try { const u = postUrlFor(v); return (u || '-') + ' (' + (postUrlFor.via || '-') + ') | Video-ID im Kasten: ' + (fbVideoId(v) || '-'); } catch (e) { return '-'; } })(),
      'Spiegeln: ' + (vp && vp.mode === 'capture' ? 'aktiv, ' + (vp.mirrorFix || 0) + '× neu verbunden' + (vp.mirrorWhy ? ' (zuletzt: ' + vp.mirrorWhy + ')' : '') : (FB_MIRROR ? 'bereit' : 'aus')),
      'Puffer-Spur: ' + (function () { if (!v) return '-'; const m = creditsFor(v, 0); const l = (bufCredit.get(v) || []).length; return l + ' Zuwächse | ' + (Array.from(m.entries()).map((e) => e[0].slice(-6) + ':' + e[1].toFixed(1)).join(', ') || '-'); })(),
      'HD nachladen: ' + hdLog + ' | Weiterleitung: ' + lastResolve + (PH ? ' | Pornhub-MP4: ' + phLog : '') + (IG ? ' | Instagram-MP4: ' + igLog : '') + (TT ? ' | TikTok: ' + ttLog + ' · ' + (ttPostUrl(v) || 'kein Link') : ''),
      'Zuletzt geöffnet: ' + (lastIntent.t ? Math.round((Date.now() - lastIntent.t) / 1000) + ' s her | ' + lastIntent.how + ' | ' + hostOf(lastIntent.url) + ' …' + lastIntent.url.slice(-50) : '-'),
      'Nächstes-Reel-Countdown abgebrochen: ' + nextCancels + '×',
      'Bild-in-Bild: Browser ' + (document.pictureInPictureEnabled ? 'kann es' : 'kann es für Webseiten nicht (nur Wischen zum Startbildschirm)') +
        ' | im Player: ' + (vp ? (vp.pipOk ? 'erlaubt' : 'aus') + ' (' + (vp.mode === 'capture' ? 'gespiegelt' + (kadriPage() ? ', Schild' : '') : 'eigener Player') + ')' : 'zu') +
        ' | Vorschau: ' + (vp ? (vp.prevWhy || (vp.mode === 'own' ? 'noch nicht gespult' : vp.fbPrev && (vp.fbBad || []).indexOf(vp.fbPrev) < 0 ? 'Bilder aus der MP4 (gespiegelt)' : 'nur Zeit (gespiegelt)')) : '-') +
        ' | Lupe: ' + (vp ? vp.lupeWhy || 'noch nicht gespult' : '-'),
      'Stream-Nachweis: ' + (r.proof || '-') + ' | Listen gelesen: ' + plInfo.size + ' | Stück-Ordner: ' + segHits.size +
        (function () { const p = playingStream(navAt); return p ? ' | lädt gerade: ' + hostOf(p.u) + ' …' + p.u.slice(-40) : ''; })(),
      'Kodierung: ' + (r.best ? encTag(r.best) || '-' : '-'),
      'IDs am Video: ' + (r.ids.slice(0, 12).join(', ') || '-'),
      'Meta-Einträge: ' + metaCount + ' (Schlüssel: ' + metaMap.size + ') | API-Antworten: ' + bodiesRead + ' | Netzwerk-Quellen: ' + sources.length,
      'Videos auf der Seite: ' + allVideos().length,
      'Browser: ' + navigator.userAgent.slice(0, 120),
      'Reihenfolge beim Tippen (' + ((r.list || []).length) + '):',
    ].concat((r.list || []).map((x, i) => ' ' + (i + 1) + '. ' + x.how + ' | ' + typeShort(x.u) + ' | ' + hostOf(x.u) + ' …' + x.u.slice(-40)))
    .concat([
      'Netzwerk-Quellen (letzte 5):',
    ]).concat(recent().slice(-5).map((x) => ' - ' + typeShort(x) + ' | ' + hostOf(x) + ' …' + x.slice(-40)))
    .concat([
      'Adressen zu diesem Video (' + lastNear.length + '):',
    ]).concat(lastNear.slice(0, 12).map((c, i) =>
      ' ' + (i + 1) + '. ' + c.kind + ' ' + (c.res ? c.res + 'p' : '?') + ' | ID ' + (c.asset || '-') + ' | ' + (c.tag || 'kein Tag').slice(0, 50) + ' | ' + String(c.from || '').slice(0, 40)
    )).join('\n');
  }
})();
