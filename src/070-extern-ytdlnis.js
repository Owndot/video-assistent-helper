  // ---------- An externen Player übergeben ----------
  function intentUrl(u, pkg, fallback) {
    const x = new URL(u);
    const title = (pageTitle() || 'Video').slice(0, 120);
    return 'intent://' + x.host + x.pathname + x.search +
      '#Intent;scheme=' + x.protocol.slice(0, -1) +
      ';action=android.intent.action.VIEW;type=video/*;' +
      (pkg ? 'package=' + pkg + ';' : '') +
      (fallback ? 'S.browser_fallback_url=' + encodeURIComponent(fallback) + ';' : '') +
      'S.title=' + encodeURIComponent(title) + ';end';
  }
  // YTDLnis nimmt Links jeder Seite direkt an (als "Video ansehen" mit Paketname) – ohne Teilen-Menü.
  // Ist YTDLnis nicht installiert, öffnet sich dessen Download-Seite.
  const YTDLNIS = 'com.deniscerri.ytdl';
  function toYtdlnis(link) {
    if (!link) return false;
    openExternal(link, null, YTDLNIS, 'An YTDLnis übergeben …', 'https://github.com/deniscerri/ytdlnis/releases/latest');
    return true;
  }
  // ---------- Pornhub: komplette MP4-Dateien mit Ton über die eigene Abfrage der Seite ----------
  // (der HLS-Stream ist dort an den Web-Player gebunden und bleibt im externen Player schwarz)
  const PH = /(^|\.)pornhub\.(com|org|net)$/i.test(location.hostname);
  let phCache = null, phPending = null, phLog = '-', phHref = '';
  function phMp4() {
    if (!PH) return null;
    if (phHref !== location.href) { phHref = location.href; phCache = null; phPending = null; }   // anderes Video
    if (phCache) return null;
    if (phPending) return phPending;
    let api = '';
    const sc = document.getElementsByTagName('script');
    for (let i = 0; i < sc.length && !api; i++) {
      const t = sc[i].textContent;
      if (!t || t.indexOf('get_media') < 0) continue;
      const m = t.match(/"videoUrl"\s*:\s*"(https?:[^"]*get_media[^"]*)"/);
      if (m) api = unesc(m[1]);
    }
    if (!api) { phLog = 'keine MP4-Abfrage auf der Seite gefunden'; phCache = []; return null; }
    phPending = fetch(api, { credentials: 'include' })
      .then((r) => r.json())
      .then((arr) => {
        const list = (Array.isArray(arr) ? arr : [])
          .filter((x) => x && typeof x.videoUrl === 'string' && x.videoUrl && (x.format === 'mp4' || /\.mp4/i.test(x.videoUrl)))
          .map((x) => ({ u: x.videoUrl, res: parseInt(Array.isArray(x.quality) ? x.quality[0] : x.quality, 10) || 0 }))
          .sort((a, b) => b.res - a.res);
        phCache = list;
        phLog = list.length ? list.length + ' MP4 gefunden (max ' + list[0].res + 'p)' : 'Abfrage ohne MP4';
        phPending = null;
        return list;
      })
      .catch((e) => { phLog = 'Fehler: ' + ((e && e.message) || e); phCache = []; phPending = null; return []; });
    return phPending;
  }

  let lastOpen = { v: null, t: 0, idx: 0 };
  let lastResolve = '-';

  // Gehört die Adresse zur Seite selbst (gleiche Domain)? Dann ist sie oft an die Browser-Sitzung gebunden.
  // „Gleiche Seite?“ – für Anmeldung (Cookies) und Helfer-Freigaben. Zweistufige Endungen (co.uk …) und Adressen, unter
  // denen viele FREMDE Betreiber eigene Seiten haben (github.io, netlify.app …), zählen als eigene Endung:
  // angreifer.github.io und opfer.github.io sind verschiedene Seiten.
  const SHARED_SUFFIX = ['github.io', 'gitlab.io', 'netlify.app', 'vercel.app', 'pages.dev', 'workers.dev', 'web.app', 'firebaseapp.com',
    'herokuapp.com', 'appspot.com', 'blogspot.com', 'azurewebsites.net', 'cloudfront.net', 's3.amazonaws.com', 'onrender.com', 'fly.dev',
    'glitch.me', 'repl.co', 'surge.sh', 'ngrok.io', 'ngrok-free.app', 'duckdns.org', 'myshopify.com', 'wixsite.com', 'wordpress.com',
    'tumblr.com', 'neocities.org', 'r2.dev', 'b-cdn.net', 'translate.goog', 'carrd.co', 'webflow.io', 'framer.app', 'notion.site'];
  function baseDomain(host) {
    const h = String(host || '').toLowerCase().replace(/\.$/, '');
    if (/^[\d.]+$/.test(h) || h.indexOf(':') >= 0) return h;   // IP-Adresse: nur sie selbst
    const p = h.split('.');
    for (const s of SHARED_SUFFIX) if (h === s || h.endsWith('.' + s)) return p.slice(-(s.split('.').length + 1)).join('.');
    // co.uk, com.au, … (zweite Stufe kurz, Land zweistellig)
    return p.length > 2 && p[p.length - 1].length === 2 && p[p.length - 2].length <= 3 ? p.slice(-3).join('.') : p.slice(-2).join('.');
  }
  function needsResolve(u) {
    if (!u || PLAYLIST_RE.test(u) || META) return false;
    const h = hostOf(u);
    return !!h && baseDomain(h) === baseDomain(location.hostname);
  }
  // Violentmonkey/Tampermonkey: darf Weiterleitungen zu anderen Servern verfolgen und die Endadresse lesen
  function gmXhr() {
    try { if (typeof GM_xmlhttpRequest === 'function') return GM_xmlhttpRequest; } catch (e) {}
    try { if (typeof GM !== 'undefined' && GM && typeof GM.xmlHttpRequest === 'function') return GM.xmlHttpRequest; } catch (e) {}
    return null;
  }
