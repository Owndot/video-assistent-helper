  function openExternal(u, v, pkg, msg, fallback) {
    if (!u) { toast('Keine Quelle für dieses Video gefunden'); return; }
    try { if (v) v.pause(); } catch (e) {}
    toast(msg || 'Wird im Videoplayer geöffnet …');
    const intent = intentUrl(u, pkg, fallback);
    lastIntent = { url: u, t: Date.now(), how: '' };
    // Weg 1: wie ein angetippter Link – funktioniert auch aus dem abgeschirmten Bereich zuverlässig
    let left = false;
    const onLeave = () => { left = true; };
    addEventListener('blur', onLeave, true);
    onVisChange(onLeave, true);
    try {
      const a = document.createElement('a');
      a.href = intent;
      a.rel = 'noreferrer';
      a.style.cssText = 'display:none';
      (root || document.documentElement).appendChild(a);
      a.click();
      a.remove();
      lastIntent.how = 'Link';
    } catch (e) { lastIntent.how = 'Link-Fehler'; }
    // Weg 2: Hat sich nach kurzer Zeit nichts getan (keine App, keine Auswahl), direkt navigieren
    setTimeout(() => {
      removeEventListener('blur', onLeave, true);
      offVisChange(onLeave, true);
      if (left || document.hidden) { lastIntent.how += ' → Browser verlassen ✓'; return; }
      try { location.href = intent; lastIntent.how += ' → Weg 2'; } catch (e) { lastIntent.how += ' → Weg 2 Fehler'; }
      setTimeout(() => {
        if (!document.hidden && document.hasFocus()) {
          lastIntent.how += ' → nichts passiert';
          toast('Player hat sich nicht geöffnet – lange auf „Player" drücken und Diagnose schicken');
        }
      }, 1200);
    }, 900);
  }
  // ---------- Direkter Download einer Videodatei ----------
  function fileNameFor(u) {
    const ext = ((String(u).split(/[?#]/)[0].match(/\.(mp4|webm|mov|m4v|mkv)$/i) || [0, 'mp4'])[1]).toLowerCase();
    const base = String(pageTitle() || pageHost()).replace(/[\\/:*?"<>|\u0000-\u001f]+/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 80) || 'video';
    return base + '.' + ext;
  }
  function saveBlob(blob, name) {
    const bu = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = bu; a.download = name; a.style.display = 'none';
    (document.body || document.documentElement).appendChild(a);
    a.click();
    setTimeout(() => { a.remove(); URL.revokeObjectURL(bu); }, 120000);
  }
  // Download über den Browser selbst (läuft im Download-Manager weiter, Seite darf zu).
  // Geht nur, wenn die Datei von derselben Seite kommt oder ihr Server sie als Download kennzeichnet.
  function nativeDownload(u, name) {
    const a = document.createElement('a');
    a.href = u; a.download = name; a.rel = 'noopener'; a.style.display = 'none';
    (document.body || document.documentElement).appendChild(a);
    a.click();
    setTimeout(() => a.remove(), 2000);
  }
  function sameOrigin(u) { try { return new URL(u, location.href).origin === location.origin; } catch (e) { return false; } }
  // Nur die Kopfzeilen abfragen (1 Byte): sagt der Server "Anhang/Download"?
  function probeAttachment(u) {
    return new Promise((resolve) => {
      const x = gmXhr();
      if (!x) { resolve(false); return; }
      let done = false, req = null;
      const fin = (v) => { if (done) return; done = true; clearTimeout(t); try { req && req.abort && req.abort(); } catch (e) {} resolve(v); };
      const t = setTimeout(() => fin(false), 6000);
      const look = (r) => {
        const hd = String((r && r.responseHeaders) || '');
        if (!hd) return;
        const ok = r.status >= 200 && r.status < 400;
        fin(ok && (/content-disposition:\s*attachment/i.test(hd) || /content-type:\s*application\/(octet-stream|force-download|x-download)/i.test(hd)));
      };
      try {
        // (Anmeldung/Cookies nur an die eigene Seite – fremde Server ohne)
        req = x({ method: 'GET', url: u, headers: { Range: 'bytes=0-0', Referer: mediaReferrer(u) }, anonymous: baseDomain(hostOf(u)) !== baseDomain(location.hostname),
          onreadystatechange: (r) => { if (r && r.readyState >= 2) look(r); },
          onload: (r) => { look(r); fin(false); }, onerror: () => fin(false), ontimeout: () => fin(false) });
      } catch (e) { fin(false); }
    });
  }
  // true = Browser hat den Download übernommen
  async function browserDownload(u) {
    const name = fileNameFor(u);
    if (sameOrigin(u)) { nativeDownload(u, name); return true; }
    const cands = [];
    // Viele Video-Seiten (KVS, "get_file") liefern mit download=true die Datei als Download aus
    if (/\/get_file\//i.test(u) && !/[?&]download=/i.test(u)) cands.push(u + (u.indexOf('?') < 0 ? '?' : '&') + 'download=true&download_filename=' + encodeURIComponent(name));
    cands.push(u);
    for (const c of cands) { if (await probeAttachment(c)) { nativeDownload(c, name); return true; } }
    return false;
  }
  let dlBusy = false;
  function directDownload(u, onFail) {
    if (dlBusy) { toast('Download läuft schon …'); return; }
    dlBusy = true;
    const name = fileNameFor(u);
    let lastPct = -10;
    const prog = (e) => {
      if (!e || !(e.total > 0)) return;
      const pc = Math.floor(e.loaded / e.total * 100);
      if (pc >= lastPct + 10) { lastPct = pc; toast('Lade herunter … ' + pc + ' %'); }
    };
    const ok = (blob) => { dlBusy = false; saveBlob(blob, name); toast('Gespeichert in Downloads: ' + name); };
    const fail = () => { dlBusy = false; if (onFail) onFail(); };
    const big = (b) => b && b.size > 20000;   // winzige Antworten sind Fehlerseiten, kein Video
    // 2. Versuch: normal über die Seite (klappt bei Dateien derselben Seite oder mit Freigabe)
    const viaPage = () => {
      fetch(u, { credentials: 'include', referrerPolicy: 'strict-origin-when-cross-origin' }).then((r) => (r.ok ? r.blob() : Promise.reject(r.status)))
        .then((b) => (big(b) ? ok(b) : fail()), fail);
    };
    toast('Download startet …');
    // 1. Versuch: über Violentmonkey – darf auch fremde Server und schickt die Seite als Herkunft mit
    const x = gmXhr();
    if (!x) { viaPage(); return; }
    try {
      x({ method: 'GET', url: u, responseType: 'blob', headers: { Referer: mediaReferrer(u) }, timeout: 0, anonymous: baseDomain(hostOf(u)) !== baseDomain(location.hostname),
        onprogress: prog,
        onload: (r) => { const b = r && r.response; if (r && r.status >= 200 && r.status < 300 && big(b)) ok(b); else viaPage(); },
        onerror: viaPage, onabort: fail });
    } catch (e) { viaPage(); }
  }

  let lastIntent = { url: '', t: 0, how: '' };
  let tapAt = 0, openTimes = null;   // Zeitmessung beim Öffnen des Players (Diagnose)
  function copyText(text, okMsg) {
    const ok = () => toast(okMsg);
    const fallback = () => {
      try {
        const ta = document.createElement('textarea');
        ta.value = text; ta.style.cssText = 'position:fixed;opacity:0';
        document.documentElement.appendChild(ta); ta.select();
        document.execCommand('copy'); ta.remove(); ok();
      } catch (e) { toast('Kopieren nicht möglich'); }
    };
    if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(text).then(ok, fallback);
    else fallback();
  }


