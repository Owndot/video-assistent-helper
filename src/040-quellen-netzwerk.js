  const DOWNLOAD_ONLY = onSite(CONFIG.downloadOnlySites);
  const SHOW_DOWNLOAD = DOWNLOAD_ONLY || CONFIG.downloadEverywhere || onSite(CONFIG.ytdlpSites);
  let vp = null;   // Zustand des eingebauten Players (muss vor dem ersten Prüfdurchlauf existieren)
  // ----- Kadri: Spiegeln + Bild-in-Bild -----
  // Facebook hält sein Video an, sobald die Seite verdeckt ist – beim Spiegeln stünde dann auch das kleine Fenster.
  // In Kadri läuft ein Skript IN der Seite (page.js): Solange gespiegelt wird, verbirgt es „verdeckt“ vor der Seite
  // („Schild“). Dabei hält es die Meldung an, bevor irgendwer sie bekommt – auch dieses Skript. Darum reicht es sie
  // als VIS_EV weiter, und die eigenen Zuhörer hören auf beides.
  const VIS_EV = 'kadri-vis';
  function onVisChange(f, cap) { document.addEventListener('visibilitychange', f, cap); if (IS_KADRI) document.addEventListener(VIS_EV, f, cap); }
  function offVisChange(f, cap) { document.removeEventListener('visibilitychange', f, cap); if (IS_KADRI) document.removeEventListener(VIS_EV, f, cap); }
  // Läuft page.js in dieser Seite (Chrome/Vivaldi)? Kurz nachfragen – page.js antwortet sofort. (Kein Merkmal an der
  // Seite, an dem Webseiten Kadri erkennen könnten.) Einmal „ja“ gilt für immer.
  let kadriPageOk = false;
  const kadriPage = () => {
    if (!IS_KADRI) return false;
    if (kadriPageOk) return true;
    try {
      let ok = false;
      const f = () => { ok = true; };
      document.addEventListener('kadri-pong', f);
      document.dispatchEvent(new Event('kadri-ping'));
      document.removeEventListener('kadri-pong', f);
      kadriPageOk = ok;
      return ok;
    } catch (e) { return false; }
  };
  function kadriShield(on) {
    if (!kadriPage()) return;
    try { document.dispatchEvent(new CustomEvent('kadri-ctl', { detail: JSON.stringify({ shield: !!on }) })); } catch (e) {}
  }

  const MEDIA_RE =/\.(m3u8|mpd|mp4|webm|mkv|mov|m4v)(\?|#|$)/i;
  const PLAYLIST_RE = /\.(m3u8|mpd)(\?|#|$)/i;
  const SEGMENT_RE = /(seg|chunk|frag|init)[-_]?\d*\.(mp4|m4s|ts)(\?|$)|[?&](bytestart|byteend|range)=/i;
  // Haupt-Playlist (alle Qualitätsstufen) vor Unter-Playlists (eine Stufe) einordnen:
  // 1. "master" im Namen, 2. "manifest"/"playlist", 3. weniger tief verschachtelt (Haupt-Playlist liegt meist eine Ebene höher)
  function rankPlaylists(list) {
    const score = (u) => {
      let path = u;
      try { path = new URL(u).pathname; } catch (e) {}
      const name = path.split('/').pop() || '';
      const s1 = /master/i.test(name) ? 0 : /(manifest|playlist)/i.test(name) ? 1 : 2;
      return [s1, path.split('/').length];
    };
    return list.map((u, i) => ({ u: u, i: i, s: score(u) }))
      .sort((a, b) => a.s[0] - b.s[0] || a.s[1] - b.s[1] || a.i - b.i)
      .map((x) => x.u);
  }
  const SPLIT_CDN_RE = /(^|\.)(fbcdn\.net|cdninstagram\.com)$/i; // dort sind Netzwerk-Videos nur Bild- ODER Tonspur
  const NS = 'http://www.w3.org/2000/svg';
  const META = /(^|\.)(facebook\.com|instagram\.com|threads\.net|threads\.com|messenger\.com)$/i.test(location.hostname);
  const FB_MIRROR = /(^|\.)facebook\.com$/i.test(location.hostname) && CONFIG.fbMirror !== false;
  // Kadri: auf Facebook zuerst der EIGENE Player mit der fertigen MP4 – aber nur, wenn sicher ist, dass sie zum
  // laufenden Video gehört (gleiche Video-ID wie die gerade geladenen Stücke); sonst spiegeln
  const FB_TRY_OWN = FB_MIRROR && IS_KADRI;
  // Firefox spiegelt mit mozCaptureStream – dabei wandert der Ton in die Spiegelung (siehe Player: VOL)
  const FF_CAP = !HTMLMediaElement.prototype.captureStream && !!HTMLMediaElement.prototype.mozCaptureStream;
  const GECKO = /\bGecko\/\d/.test(navigator.userAgent) && !/like Gecko/.test(navigator.userAgent);   // Firefox
  // Firefox auf dem Handy: Android liefert Videobilder direkt an die Grafik – auf eine Zeichenfläche kopiert kommen sie
  // leer an (Mozilla-Bug 1479947). Ambient-Licht und Spul-Vorschau zeigen dort deshalb Videos selbst statt Kopien.
  // (Auch bei "Desktop-Seite", daher zusätzlich Touch; "evp-ffmobile" = 1 erzwingt es zum Testen.)
  const FF_MOBILE = GECKO && (/Android|Mobile/.test(navigator.userAgent) || navigator.maxTouchPoints > 0 ||
    (() => { try { return localStorage.getItem('evp-ffmobile') === '1'; } catch (e) { return false; } })());

  // Icons (Material Icons, Apache 2.0)
  const I = {
    play: 'M10 16.5l6-4.5-6-4.5v9zM12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8z',
    open: 'M19 19H5V5h7V3H5c-1.11 0-2 .9-2 2v14c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2v-7h-2v7zM14 3v2h3.59l-9.83 9.83 1.41 1.41L19 6.41V10h2V3h-7z',
    apps: 'M4 8h4V4H4v4zm6 12h4v-4h-4v4zm-6 0h4v-4H4v4zm0-6h4v-4H4v4zm6 0h4v-4h-4v4zm6-10v4h4V4h-4zm-6 4h4V4h-4v4zm6 6h4v-4h-4v4zm0 6h4v-4h-4v4z',
    pip: 'M19 11h-8v6h8v-6zm4 8V4.98C23 3.88 22.1 3 21 3H3c-1.1 0-2 .88-2 1.98V19c0 1.1.9 2 2 2h18c1.1 0 2-.9 2-2zm-2 .02H3V4.97h18v14.05z',
    link: 'M3.9 12c0-1.71 1.39-3.1 3.1-3.1h4V7H7c-2.76 0-5 2.24-5 5s2.24 5 5 5h4v-1.9H7c-1.71 0-3.1-1.39-3.1-3.1zM8 13h8v-2H8v2zm9-6h-4v1.9h4c1.71 0 3.1 1.39 3.1 3.1s-1.39 3.1-3.1 3.1h-4V17h4c2.76 0 5-2.24 5-5s-2.24-5-5-5z',
    download: 'M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z',
    player: 'M20 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm0 14H4V6h16v12zM10 8.5v7l5.5-3.5L10 8.5z',
    share: 'M18 16.08c-.76 0-1.44.3-1.96.77L8.91 12.7c.05-.23.09-.46.09-.7s-.04-.47-.09-.7l7.05-4.11c.54.5 1.25.81 2.04.81 1.66 0 3-1.34 3-3s-1.34-3-3-3-3 1.34-3 3c0 .24.04.47.09.7L8.04 9.81C7.5 9.31 6.79 9 6 9c-1.66 0-3 1.34-3 3s1.34 3 3 3c.79 0 1.5-.31 2.04-.81l7.12 4.16c-.05.21-.08.43-.08.65 0 1.61 1.31 2.92 2.92 2.92s2.92-1.31 2.92-2.92-1.31-2.92-2.92-2.92z',
    more: 'M12 8c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm0 2c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z',
    sd: 'M19 3H5c-1.11 0-2 .9-2 2v14c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm-8 7.5H8.5V10h-2v1h2.5c.55 0 1 .45 1 1v2c0 .55-.45 1-1 1H6v-1.5h2.5v-1H6c-.55 0-1-.45-1-1V9.5c0-.55.45-1 1-1h3.5c.55 0 1 .45 1 1v1zm7 3.5c0 .55-.45 1-1 1h-4.5V8.5H17c.55 0 1 .45 1 1V14zm-4-4h2.5v3.5H14V10z',
    bug: 'M20 8h-2.81c-.45-.78-1.07-1.45-1.82-1.96L17 4.41 15.59 3l-2.17 2.17C12.96 5.06 12.49 5 12 5s-.96.06-1.41.17L8.41 3 7 4.41l1.62 1.63C7.88 6.55 7.26 7.22 6.81 8H4v2h2.09c-.05.33-.09.66-.09 1v1H4v2h2v1c0 .34.04.67.09 1H4v2h2.81c1.04 1.79 2.97 3 5.19 3s4.15-1.21 5.19-3H20v-2h-2.09c.05-.33.09-.66.09-1v-1h2v-2h-2v-1c0-.34-.04-.67-.09-1H20V8zm-6 8h-4v-2h4v2zm0-4h-4v-2h4v2z',
  };

  // ---------- Hilfen ----------
  function unesc(s) {
    return s.replace(/\\\//g, '/').replace(/\\u0025/gi, '%').replace(/\\u0026/gi, '&').replace(/\\u003d/gi, '=').replace(/&amp;/g, '&');
  }
  function hostOf(u) { try { return new URL(u).hostname; } catch (e) { return ''; } }

  // Facebook/Instagram schreiben in den Parameter "efg" (Base64-JSON), wie die Datei kodiert ist:
  // "progressive" = komplettes Video mit Ton, "dash…" = nur Bild- ODER nur Tonspur.
  function efgInfo(u) {
    try {
      const e = new URL(u).searchParams.get('efg');
      if (!e) return '';
      let s = e.replace(/-/g, '+').replace(/_/g, '/');
      while (s.length % 4) s += '=';
      return atob(s);
    } catch (err) { return ''; }
  }
  function encTag(u) {
    const m = efgInfo(u).match(/"vencode_tag"\s*:\s*"([^"]+)"/);
    return m ? m[1] : '';
  }
  function isSplitTrack(u) {
    if (!u || !SPLIT_CDN_RE.test(hostOf(u))) return false;
    if (SEGMENT_RE.test(u)) return true;
    const info = efgInfo(u).toLowerCase();
    if (!info || info.indexOf('progressive') >= 0) return false;
    return /dash|audio|video_only/.test(info);
  }
  const fullUrl = (x) => typeof x === 'string' && x.startsWith('http') && !isSplitTrack(x);

  // ---------- 1) Netzwerk-Quellen (HLS/DASH/MP4) ----------
  let sources = [];               // [{u, t}]
  let navAt = 0;
  let bodiesRead = 0;
  const isMedia = (u) => typeof u === 'string' && MEDIA_RE.test(u) && !SEGMENT_RE.test(u);

  // Facebook/Instagram: Welches Video lädt der Web-Player gerade wirklich?
  // Die nachgeladenen Bild-/Tonstücke tragen die Video-ID in ihrer Adresse.
  const assetHits = new Map();   // Video-ID -> [Zeitpunkte]
  function assetOf(u) {
    try {
      const j = JSON.parse(efgInfo(u) || '{}') || {};
      const a = String(j.xpv_asset_id || j.asset_id || j.video_id || '');
      return a && a !== 'null' && a !== 'undefined' ? a : '';
    } catch (e) { return ''; }
  }
  // Jede Stück-Adresse nur einmal zählen: Sie wird beim Anfragen UND beim Fertigladen gemeldet.
  // Die zweite Meldung setzt nur den Zeitpunkt auf "angekommen" (wichtig für die Puffer-Zuordnung).
  const splitSeen = new Map();   // Adresse -> Zeitpunkt
  // arrived = echte Ankunftszeit (aus der Ladezeiten-Messung), sonst "jetzt"
  function noteSplit(u, arrived) {
    const a = assetOf(u);
    if (!a) return;
    const now = arrived > 0 && arrived <= Date.now() + 50 ? arrived : Date.now();
    let arr = assetHits.get(a);
    if (splitSeen.has(u)) {
      if (arr && arrived > 0) {
        const i = arr.lastIndexOf(splitSeen.get(u)); if (i >= 0) arr[i] = now;
        arr.lastT = Math.max(arr.lastT || 0, now);
        splitSeen.set(u, now);
      }
      return;
    }
    splitSeen.set(u, now);
    if (splitSeen.size > 4000) splitSeen.delete(splitSeen.keys().next().value);
    if (!arr) { arr = []; arr.total = 0; arr.first = now; assetHits.set(a, arr); }
    arr.push(now);
    arr.lastT = Math.max(arr.lastT || 0, now);
    arr.total++;
    if (arr.length > 60) arr.shift();
    if (assetHits.size > 200) assetHits.delete(assetHits.keys().next().value);
  }
  function seeUrl(u, arrived) {
    if (typeof u !== 'string' || u.indexOf('http') !== 0) return;
    if (SPLIT_CDN_RE.test(hostOf(u))) { noteSplit(u, arrived); return; }
    noteSegment(u);
    if (isMedia(u)) addSource(u);
  }
  // Das Video, von dem in den letzten 20 Sekunden am meisten geladen wurde
  // Wie oft wurde von diesem Video seit dem Start des aktuellen Videos geladen?
  function hitCount(a, since) {
    const arr = assetHits.get(a) || [];
    const from = Math.max(Date.now() - 20000, (since || 0) - 300);
    return arr.filter((t) => t >= from).length;
  }
  function currentAsset(since) {
    const now = Date.now();
    // nur Ladevorgänge NACH dem Start dieses Videos zählen – sonst erwischt man beim Wischen das vorherige
    const from = Math.max(now - 20000, (since || 0) - 300);
    let best = '', bestN = 0, bestLast = 0;
    assetHits.forEach((arr, a) => {
      const n = arr.filter((t) => t >= from).length;
      const last = arr[arr.length - 1] || 0;
      if (n > bestN || (n === bestN && n > 0 && last > bestLast)) { best = a; bestN = n; bestLast = last; }
    });
    if (!best) {   // nichts in den letzten 20 s: zuletzt geladenes Video der letzten Minute
      assetHits.forEach((arr, a) => {
        const last = arr[arr.length - 1] || 0;
        if (last >= Math.max(now - 60000, (since || 0) - 300) && last > bestLast) { best = a; bestLast = last; }
      });
    }
    return best;
  }
  // Facebook lädt beim Scrollen schon die ersten Sekunden der NÄCHSTEN Videos im Feed vor – als kurzen Schwall.
  // Alles aus den ersten 2 Sekunden nach dem ersten Auftauchen eines Videos zählt deshalb nicht (egal wie viele Stücke):
  // nur was danach nachgeladen wird, ist echtes Abspielen.
  const PRELOAD_MS = 2000;
  function effectiveHits(arr, from) {
    const after = (arr.first || 0) + PRELOAD_MS;
    let n = 0;
    for (let i = 0; i < arr.length; i++) if (arr[i] >= from && arr[i] > after) n++;
    return n;
  }
  const playAt = new WeakMap();   // wann dieses Video zuletzt (wieder) zu spielen begann
  document.addEventListener('play', (e) => { const v = e.target; if (v && v.tagName === 'VIDEO') playAt.set(v, Date.now()); }, true);
  // Stärkstes Zeichen: Nur die Stücke des LAUFENDEN Videos landen auch im Puffer dieses Video-Elements.
  // Vorgeladene Nachbar-Reels werden nur geholt, aber nicht eingespeist. Wächst der Puffer, bekommt das Video einen
  // Punkt, dessen Stück gerade eben angekommen ist.
  const bufCredit = new WeakMap();   // Video-Element -> [{t, a: [Video-IDs]}]
  const bufPrev = new WeakMap();
  function bufTotal(v) {
    let n = 0;
    try { const b = v.buffered; for (let i = 0; i < b.length; i++) n += b.end(i) - b.start(i); } catch (e) {}
    return n;
  }
  function watchBuffers() {
    if (document.hidden || !assetHits.size) return;
    const now = Date.now();
    const vids = document.getElementsByTagName('video');
    for (let i = 0; i < vids.length; i++) {
      const v = vids[i];
      if (v.paused && !v.seeking) { bufPrev.set(v, bufTotal(v)); continue; }
      const cur = bufTotal(v), prev = bufPrev.get(v);
      bufPrev.set(v, cur);
      if (prev == null || cur <= prev + 0.15) continue;
      // Punkt nach Nähe aufteilen: was unmittelbar vorher ankam, bekommt fast alles (ein Stück von vor 1 s fast nichts)
      const w = [];
      let sum = 0;
      assetHits.forEach((arr, a) => {
        const dt = now - (arr.lastT || 0);
        if (dt < 2000) { const x = Math.exp(-dt / 350); w.push([a, x]); sum += x; }
      });
      if (!w.length || sum <= 0) continue;
      let list = bufCredit.get(v);
      if (!list) { list = []; bufCredit.set(v, list); }
      list.push({ t: now, w: w.map((e) => [e[0], e[1] / sum]) });
      if (list.length > 80) list.shift();
    }
  }
  // viermal pro Sekunde: genug, um Einspeisen und Ankunft zu trennen (spart Akku) – nur wo es gebraucht wird
  if (META && (!FB_MIRROR || FB_TRY_OWN)) setInterval(watchBuffers, 250);
  ['loadstart', 'emptied'].forEach((t) => document.addEventListener(t, (e) => {
    const v = e.target; if (v && v.tagName === 'VIDEO') { bufCredit.delete(v); bufPrev.delete(v); }
  }, true));
  function creditsFor(v, from) {
    const m = new Map();
    (bufCredit.get(v) || []).forEach((c) => {
      if (c.t < from) return;
      c.w.forEach((e) => m.set(e[0], (m.get(e[0]) || 0) + e[1]));
    });
    return m;
  }
  // Welches Facebook-Video zeigt GENAU dieses Element? sure = sicher erkannt
  function assetFor(v) {
    if (!v) return { asset: '', sure: false, via: '-' };
    const now = Date.now();
    // 1) Video-ID aus dem Beitrag rund um das Video, die gerade auch geladen wird – am sichersten
    try {
      const live = videoIdsFor(v).filter((id) => { const arr = assetHits.get(id); return arr && arr[arr.length - 1] >= now - 90000; });
      if (live.length === 1) return { asset: live[0], sure: true, via: 'Beitrag + Netzwerk' };
    } catch (e) {}
    const since = Math.max(startAt.get(v) || 0, playAt.get(v) || 0);
    const from = Math.max(now - 20000, since - 300);
    // 2) Puffer-Zuordnung: welches Video wird wirklich in DIESES Element eingespeist?
    const cr = creditsFor(v, Math.max(now - 30000, (startAt.get(v) || 0) - 300));
    let cb = '', c1 = 0, c2 = 0;
    cr.forEach((n, a) => { if (n > c1) { c2 = c1; c1 = n; cb = a; } else if (n > c2) c2 = n; });
    if (cb && c1 >= 1.5 && c1 >= c2 * 1.8) return { asset: cb, sure: true, via: 'Puffer (' + Math.round(c1) + ':' + Math.round(c2) + ')' };
    // 3) Echtes Abspielen seit dem Start: ohne die vorgeladenen ersten Stücke
    let best = '', b1 = 0, b2 = 0;
    assetHits.forEach((arr, a) => {
      const n = effectiveHits(arr, from);
      if (n > b1) { b2 = b1; b1 = n; best = a; } else if (n > b2) b2 = n;
    });
    if (best && b1 >= 2 && b1 >= b2 * 2) return { asset: best, sure: true, via: 'Netzwerk (ohne Vorladen)' };
    // 4) unsicher: lieber die Puffer-Spur als die reine Menge (die Menge gewinnt oft ein vorgeladenes Nachbar-Reel)
    if (cb && c1 > c2) return { asset: cb, sure: false, via: 'Puffer (unsicher ' + Math.round(c1) + ':' + Math.round(c2) + ')' };
    return { asset: currentAsset(startAt.get(v) || 0), sure: false, via: 'Netzwerk (unsicher)' };
  }

  function addSource(raw) {
    let u;
    try { u = new URL(raw, location.href).href; } catch (e) { return; }
    if (!/^https?:/i.test(u) || SEGMENT_RE.test(u) || SPLIT_CDN_RE.test(hostOf(u))) return;
    const i = sources.findIndex((s) => s.u === u);
    if (i >= 0) {
      // die Seite lädt sie erneut (z. B. zurück zu einem früheren Video): gilt ab jetzt wieder als neu.
      // Lädt sie nur der eigene Player, bleibt der Zeitpunkt der Seite.
      if (vp && vp.mode === 'own') return;
      const s = sources.splice(i, 1)[0]; s.t = Date.now(); sources.push(s);
      return;
    }
    sources.push({ u: u, t: Date.now() });
    if (sources.length > 40) sources.shift();
  }

  // ---------- Nachweis: welcher Stream läuft WIRKLICH? ----------
  // Statt nach dem Zeitpunkt zu raten: Die Stream-Listen (m3u8/mpd), die die Seite ohnehin lädt, werden mitgelesen
  // (kein zusätzlicher Abruf). Daraus ist bekannt, in welchem Ordner die Stücke jedes Streams liegen, welche
  // Unter-Listen zu welcher Haupt-Liste gehören und wie lang das Video ist. Lädt die Seite dann Stücke aus einem
  // dieser Ordner, ist bewiesen, welcher Stream läuft.
  const urlKey = (u) => { try { const x = new URL(u, location.href); return x.origin + x.pathname; } catch (e) { return String(u).split(/[?#]/)[0]; } };
  const dirOf = (u) => urlKey(u).replace(/[^\/]*$/, '');
  const plInfo = new Map();    // urlKey → { u, kids:Set(urlKey), segDirs:[Ordner], dur, live, t }
  const segHits = new Map();   // Ordner der Stücke → zuletzt geladen
  const SEGFILE_RE = /\.(ts|m4s|mp4|m4a|m4v|aac|cmfv|cmfa|webm|mp3)(\?|#|$)/i;
  function isoDur(s) {
    const m = /P(?:(\d+(?:\.\d+)?)D)?T?(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?/.exec(s || '');
    if (!m) return NaN;
    return (+m[1] || 0) * 86400 + (+m[2] || 0) * 3600 + (+m[3] || 0) * 60 + (+m[4] || 0);
  }
  function notePlaylist(reqUrl, finalUrl, text) {
    try {
      if (typeof text !== 'string' || text.length > 4e6) return;
      const base = finalUrl || reqUrl;
      const abs = (x) => { try { return new URL(x, base).href; } catch (e) { return ''; } };
      const info = { u: reqUrl, kids: new Set(), segDirs: [], dur: NaN, live: false, t: Date.now() };
      const dirs = new Set();
      const addDir = (a) => { if (!a) return; const d = dirOf(a); try { if (new URL(d).pathname.length > 1) dirs.add(d); } catch (e) {} };
      const head = text.slice(0, 600).trimStart();
      if (head.startsWith('#EXTM3U')) {
        const lines = text.split(/\r?\n/);
        let sum = 0, segs = 0, kidNext = false;
        for (let i = 0; i < lines.length; i++) {
          const l = lines[i].trim();
          if (!l) continue;
          if (l[0] === '#') {
            if (l.startsWith('#EXTINF:')) { sum += parseFloat(l.slice(8)) || 0; segs++; }
            else if (l.startsWith('#EXT-X-STREAM-INF')) kidNext = true;
            else if (l.startsWith('#EXT-X-MEDIA:') || l.startsWith('#EXT-X-I-FRAME-STREAM-INF')) { const m = /URI="([^"]+)"/.exec(l); if (m) info.kids.add(urlKey(abs(m[1]))); }
            else if (l.startsWith('#EXT-X-MAP:')) { const m = /URI="([^"]+)"/.exec(l); if (m) addDir(abs(m[1])); }
            continue;
          }
          if (kidNext) { info.kids.add(urlKey(abs(l))); kidNext = false; }
          else addDir(abs(l));
        }
        if (segs) { info.live = text.indexOf('#EXT-X-ENDLIST') < 0; if (!info.live) info.dur = sum; }
      } else if (/<MPD[\s>]/.test(head) || /<MPD[\s>]/.test(text.slice(0, 4000))) {
        info.live = /\btype\s*=\s*["']dynamic["']/.test(text);
        const m = /mediaPresentationDuration\s*=\s*["']([^"']+)["']/.exec(text);
        if (m && !info.live) info.dur = isoDur(m[1]);
        addDir(base);   // DASH: Stücke liegen bei der mpd (oder unter ihrer BaseURL)
        const re = /<BaseURL[^>]*>([^<]+)<\/BaseURL>/g; let b, n = 0;
        while ((b = re.exec(text)) && n++ < 8) { const x = b[1].trim(); addDir(abs(/\/$/.test(x) ? x + 'x' : x)); }
      } else return;
      info.segDirs = Array.from(dirs).slice(0, 12);
      plInfo.set(urlKey(reqUrl), info);
      if (finalUrl && urlKey(finalUrl) !== urlKey(reqUrl)) plInfo.set(urlKey(finalUrl), info);
      while (plInfo.size > 80) plInfo.delete(plInfo.keys().next().value);
    } catch (e) {}
  }
  // Erweiterung Kadri: Ein winziges Skript IN der Seite (page.js) liest die Stream-Listen und – auf Facebook/Instagram –
  // die API-Antworten mit, die die Seite lädt, und reicht sie hierher. Im abgetrennten Bereich (Userscript im Modus
  // „content“) sieht das Skript diese Anfragen sonst nicht. Nur in der Erweiterung aktiv.
  if (IS_KADRI) {
    document.addEventListener('kadri-net', (e) => {
      // (die Seite kann solche Meldungen auch selbst schicken: nur Text, höchstens ~8 MB, sonst nichts tun)
      if (typeof e.detail !== 'string' || e.detail.length > 8e6) return;
      let d = null;
      try { d = JSON.parse(e.detail); } catch (x) { d = null; }
      if (!d || typeof d.u !== 'string' || typeof d.t !== 'string') return;
      if (d.k === 'pl') notePlaylist(d.u, typeof d.f === 'string' ? d.f : '', d.t);
      else if (d.k === 'src') { if (typeof KADRI_HOOKS !== 'undefined' && KADRI_HOOKS.onSrc) KADRI_HOOKS.onSrc(d.u, d.f === 'mpd' ? 'mpd' : 'm3u8'); }
      else if (d.k === 'api' && META) { bodiesRead++; try { scanText(d.t); } catch (x) {} }
    });
  }
  // Kadri: Der Hintergrund der Erweiterung meldet Stream-Listen, die der Tab lädt – nach dem, was der Server sagt
  // („Content-Type“), also auch ohne .m3u8 im Namen und aus Hintergrund-Skripten der Seite (Worker), die sonst
  // niemand sieht. Ohne Endung bekommt die Adresse ein Kennzeichen hinter „#“ (geht nicht an den Server), damit der
  // Player sie als Stream lädt.
  if (IS_KADRI && typeof KADRI_HOOKS !== 'undefined') {
    KADRI_HOOKS.onSrc = (u, k) => {
      if (typeof u !== 'string' || !/^https?:/i.test(u) || SEGMENT_RE.test(u)) return;
      if (!PLAYLIST_RE.test(u)) u += k === 'mpd' ? '#kadri.mpd' : '#kadri.m3u8';
      addSource(u);
    };
  }
  // Kadri: page.js hebt seine letzten Meldungen auf – jetzt, wo alles zuhört, noch einmal schicken lassen
  // (in eingebetteten Fenstern startet Kadri erst, wenn ein Video auftaucht)
  if (IS_KADRI) { try { document.dispatchEvent(new Event('kadri-hello')); } catch (e) {} }
  function noteSegment(u) {
    if (PLAYLIST_RE.test(u) || !(SEGFILE_RE.test(u) || SEGMENT_RE.test(u))) return;
    if (vp && vp.mode === 'own') return;   // Stücke des eigenen Players zählen nicht
    const d = dirOf(u);
    segHits.delete(d); segHits.set(d, Date.now());
    if (segHits.size > 120) segHits.delete(segHits.keys().next().value);
  }
  // Der Stream, aus dem die Seite seit „since“ zuletzt Stücke geladen hat – als Haupt-Liste (alle Qualitäten)
  function playingStream(since) {
    const now = Date.now(), from = Math.max(since || 0, now - 30000);
    let media = null, mediaT = 0;
    segHits.forEach((t, d) => {
      if (t < from || t <= mediaT) return;
      let m = null;
      plInfo.forEach((p) => { if (p.segDirs.some((sd) => d.startsWith(sd)) && (!m || p.t > m.t)) m = p; });
      if (m) { media = m; mediaT = t; }
    });
    if (!media) return null;
    const mk = urlKey(media.u);
    let master = null;
    plInfo.forEach((p) => { if (p.kids.has(mk) && (!master || p.t > master.t)) master = p; });
    let u = master ? master.u : '';
    if (!u) {   // Haupt-Liste nicht lesbar: die kurz davor (nach dem letzten Videowechsel) geladene, die nichts anderes enthält
      const near = sources.filter((s) => PLAYLIST_RE.test(s.u) && s.t >= navAt && s.t <= media.t + 500 && s.t >= media.t - 8000 && urlKey(s.u) !== mk)
        .map((s) => s.u).filter((x) => { const p = plInfo.get(urlKey(x)); return !p || (!p.kids.size && !p.segDirs.length); });
      u = rankPlaylists(near).filter((x) => /master|manifest|playlist/i.test(x))[0] || media.u;
    }
    return { u: u, dur: media.dur, live: media.live };
  }
  // Länge eines Streams, soweit bekannt (aus der eigenen Liste oder einer ihrer Unter-Listen)
  function streamLen(u) {
    const p = plInfo.get(urlKey(u));
    if (!p) return null;
    if (p.live) return { live: true };
    if (isFinite(p.dur)) return { dur: p.dur };
    for (const k of p.kids) { const q = plInfo.get(k); if (q && q.live) return { live: true }; if (q && isFinite(q.dur)) return { dur: q.dur }; }
    return null;
  }
  const lenTol = (d) => Math.max(4, d * 0.015);

  try {
    new PerformanceObserver((list) => {
      list.getEntries().forEach((e) => seeUrl(e.name, e.responseEnd > 0 && performance.timeOrigin ? performance.timeOrigin + e.responseEnd : 0));
    }).observe({ type: 'resource', buffered: true });
  } catch (e) {}

  const API_RE = /graphql|\/api\//i;
  let rawFetch = null;   // ungehaktes fetch – für eigene Abfragen, die NICHT mitgelesen werden sollen
  try {
    // Antwort mitlesen, aber höchstens max Zeichen: zu Große gar nicht erst ganz einlesen (Länge vorab, sonst Stück für
    // Stück und abbrechen)
    const readCapped = (r, max) => {
      try {
        if (+(r.headers.get('content-length') || 0) > max) return Promise.resolve('');
        const c = r.clone();
        if (!c.body || !c.body.getReader || typeof TextDecoder !== 'function') return c.text().then((t) => (t.length > max ? '' : t));
        const rd = c.body.getReader(), dec = new TextDecoder();
        let out = '', n = 0;
        const pump = () => rd.read().then(({ done, value }) => {
          if (done) return out + dec.decode();
          n += value.length;
          if (n > max) { rd.cancel().catch(() => {}); return ''; }
          out += dec.decode(value, { stream: true });
          return pump();
        });
        return pump();
      } catch (e) { return Promise.resolve(''); }
    };
    const of = window.fetch;
    rawFetch = of ? of.bind(window) : null;
    // Firefox: Abfragen eines Erweiterungs-Skripts laufen sonst als Erweiterung (andere Herkunft) –
    // content.fetch fragt wie die Seite selbst (nötig z. B. für Instagram/TikTok)
    try { if (typeof content !== 'undefined' && content && typeof content.fetch === 'function') rawFetch = content.fetch.bind(content); } catch (e) {}
    if (of) {
      window.fetch = function (input) {
        let u = '';
        try { u = typeof input === 'string' ? input : (input && input.url) || ''; seeUrl(u); } catch (e) {}
        const p = of.apply(this, arguments);
        if (PLAYLIST_RE.test(u)) p.then((res) => { if (res && res.ok) readCapped(res, 4e6).then((t) => { if (t) notePlaylist(u, res.url, t); }, () => {}); }, () => {});
        if (META && API_RE.test(u)) {
          p.then((res) => { if (res) readCapped(res, 8e6).then((t) => { if (t) { bodiesRead++; scanText(t); } }, () => {}); }, () => {});
        }
        return p;
      };
    }
    const oo = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function (m, url) {
      try {
        const u = String(url);
        seeUrl(u);
        if (PLAYLIST_RE.test(u)) {
          this.addEventListener('load', function () {
            try { if (this.status < 400 && (!this.responseType || this.responseType === 'text')) notePlaylist(new URL(u, location.href).href, this.responseURL, this.responseText); } catch (e) {}
          });
        }
        if (META && API_RE.test(u)) {
          this.addEventListener('load', function () {
            try {
              bodiesRead++;
              if (!this.responseType || this.responseType === 'text') scanText(this.responseText);
              else if (this.responseType === 'json') walk(this.response, [], 0);
            } catch (e) {}
          });
        }
      } catch (e) {}
      return oo.apply(this, arguments);
    };
  } catch (e) {}

  // Wechsel zu einem anderen Video OHNE Neuladen (ARD, YouTube & Co. tauschen nur den Stream im selben Player):
  // Ab hier gelten nur noch Adressen, die danach geladen wurden. Als Zeitpunkt zählt das letzte Mal, dass noch die
  // alte Adresse zu sehen war (nicht erst die Prüfung danach) – so fällt nichts Neues heraus.
  const hrefKey = () => location.href.split('#')[0];
  let lastHref = hrefKey(), hrefSeenAt = Date.now();
  function checkNav() {
    const now = Date.now(), h = hrefKey();
    if (h !== lastHref) { lastHref = h; navAt = Math.max(navAt, hrefSeenAt - 300); }
    hrefSeenAt = now;
  }
  addEventListener('popstate', () => setTimeout(checkNav, 0));

  function recent() {
    const r = sources.filter((s) => s.t >= navAt);
    return (r.length ? r : sources).map((s) => s.u);
  }

