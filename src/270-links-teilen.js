  // ---------- Download: Link GENAU dieses Videos an YTDLnis & Co. teilen ----------
  // Beitrags-Adressen bekannter Seiten (YouTube, Facebook, Instagram, X, Reddit, TikTok, Vimeo …)
  const POST_RE = /\/status\/\d+|\/comments\/[a-z0-9]+|\/(?:p|reels?|tv)\/[A-Za-z0-9_-]{5,}|\/video\/\d+|\/videos\/(?:[^\/?#]+\/)?\d+|\/watch\/?\?(?:.*&)?v=[\w-]{6,}|[?&]v=[\w-]{6,}|\/shorts\/[\w-]+|story\.php\?story_fbid=\d+|fb\.watch\/\w+|vimeo\.com\/\d+|view_video\.php\?(?:.*&)?viewkey=\w+|\/video\d+\/|\/video\/x[a-z0-9]+|\/video\/BV\w+|\/videos\/[\w-]+-\d+|\/clip\/[\w-]+|\/v[a-z0-9]{5,}-[\w-]+\.html|video-?\d+_\d+|\/embed\/[\w-]{5,}/i;
  const IG_POST_RE = /\/(?:p|reels?|tv)\/(?!audio\/)([A-Za-z0-9_-]{5,})/;
  // Seite für EIN Video: nach /video/ usw. folgt eine Kennung (mind. 3 Ziffern oder lang) – nicht /videos/neu
  const VIDEO_PAGE_RE = /\/(?:videos?|watch|clips?|films?|episoden?|episodes?|folgen?)\/(?=[^?#]*(?:\d[^?#]*){3}|[^?#]{20})[^\/?#]{3,}/i;
  const NOT_POST_RE = /\/reels?\/audio\/|\/explore\/|\/stories\//i;
  // Alle Beitragslinks in einem Bereich, gezählt (der eigene Beitrag kommt meist mehrfach vor)
  function postLinks(el) {
    const m = new Map();
    const as = el.getElementsByTagName('a');
    for (let j = 0; j < as.length && j < 200; j++) {
      const href = as[j].href || '';
      if (!POST_RE.test(href) || NOT_POST_RE.test(href)) continue;
      let key = href.split('#')[0];
      const ig = /(instagram\.com|threads\.(net|com))\//i.test(href) && href.match(IG_POST_RE);
      if (ig) key = new URL(href).origin + ig[0].replace(/^\/reels\//, '/reel/') + '/';   // z. B. /user/p/CODE/?img_index=1 -> /p/CODE/
      m.set(key, (m.get(key) || 0) + 1);
    }
    return m;
  }
  function bestLink(m) {
    let best = '', n = 0;
    m.forEach((c, k) => { if (c > n) { n = c; best = k; } });
    return best;
  }
  // ---------- TikTok: Video-ID und Kanal des laufenden Videos ----------
  // Im Feed (/foryou, /following …) steht in der Adresse kein Video. TikToks Player-Rahmen trägt die ID aber
  // in seinen Attributen (z. B. id="xgwrapper-0-7349…"), der Kanalname steht gleich daneben.
  const TT = /(^|\.)tiktok\.com$/i.test(location.hostname);
  function ttInfo(v) {
    const pm = location.pathname.match(/\/@([\w.-]+)\/(?:video|photo)\/(\d{15,})/);
    if (pm) return { id: pm[2], user: pm[1] };
    let id = '', user = '', idLvl = -1, el = v;
    for (let i = 0; el && el.nodeType === 1 && i < 16; i++, el = el.parentElement) {
      if (i > 0 && el.getElementsByTagName('video').length > 1) break;   // weiter oben liegen andere Videos
      if (!id) {
        const at = el.attributes;
        for (let j = 0; j < at.length && !id; j++) { const m = /(?:^|\D)(7\d{18})(?:\D|$)/.exec(at[j].value); if (m) id = m[1]; }
        if (!id && i <= 6) {
          const a = el.querySelector('a[href*="/video/"]');
          const m = a && (a.getAttribute('href') || '').match(/\/video\/(\d{15,})/);
          if (m) id = m[1];
        }
        if (id) idLvl = i;
      }
      if (id && !user && i <= idLvl + 3) {
        const au = el.querySelector('[data-e2e="video-author-uniqueid"], [data-e2e="browse-username"], [data-e2e="feed-video-author"], a[href^="/@"]');
        if (au) {
          const hm = (au.getAttribute('href') || '').match(/^\/@([\w.-]+)/);
          user = hm ? hm[1] : String(au.textContent || '').trim().replace(/^@/, '').replace(/\s.*$/, '');
        }
      }
      if (id && (user || i > idLvl + 3)) break;
    }
    return { id: id, user: /^[\w.-]+$/.test(user) ? user : '' };
  }
  function ttPostUrl(v) {
    const t = ttInfo(v);
    return t.id ? 'https://www.tiktok.com/@' + t.user + '/video/' + t.id : '';   // ohne Kanal klappt der Link auch (yt-dlp)
  }
  // Videodatei dieses TikToks: aus der Seite des einzelnen Videos (dort steht "playAddr")
  const ttCache = new Map();
  let ttLog = '-';
  function ttVideoUrl(v) {
    const t = TT ? ttInfo(v) : { id: '' };
    if (!t.id) { ttLog = 'keine Video-ID am Player'; return Promise.resolve(''); }
    if (ttCache.has(t.id)) return ttCache.get(t.id);
    const dur = v && isFinite(v.duration) && v.duration > 0 ? v.duration : 0;
    const p = (async () => {
      try {
        const r = await (rawFetch || fetch)(location.origin + '/@' + (t.user || 'i') + '/video/' + t.id, { credentials: 'include' });
        if (!r.ok) { ttLog = 'Videoseite: HTTP ' + r.status; return ''; }
        const html = await r.text();
        const m = html.match(/<script[^>]+id="(?:__UNIVERSAL_DATA_FOR_REHYDRATION__|SIGI_STATE)"[^>]*>([\s\S]*?)<\/script>/);
        if (!m) { ttLog = 'keine Videodaten in der Seite'; return ''; }
        const data = JSON.parse(m[1]);
        let item = null;
        const walk = (o, d) => {
          if (item || !o || typeof o !== 'object' || d > 12) return;
          if (String(o.id) === t.id && o.video && typeof o.video === 'object') { item = o; return; }
          for (const k in o) walk(o[k], d + 1);
        };
        walk(data, 0);
        if (!item) { ttLog = 'Video ' + t.id + ' nicht in den Daten'; return ''; }
        const vd = item.video;
        const d = Number(vd.duration) || 0;
        if (dur && d && Math.abs(d - dur) > 2) { ttLog = 'Länge passt nicht (' + d + ' s statt ' + Math.round(dur) + ' s)'; return ''; }
        const br = (vd.bitrateInfo || []).map((b) => ({ u: ((b.PlayAddr || {}).UrlList || [])[0], q: (b.PlayAddr || {}).Width * (b.PlayAddr || {}).Height || b.Bitrate || 0 }))
          .filter((x) => x.u).sort((a, b) => b.q - a.q);
        const u = (br[0] && br[0].u) || vd.playAddr || vd.downloadAddr || '';
        ttLog = u ? 'Videodatei gefunden' : 'keine Videodatei in den Daten';
        return u;
      } catch (e) { ttLog = 'Fehler: ' + (e && e.message); return ''; }
    })();
    ttCache.set(t.id, p);
    p.then((u) => { if (!u) setTimeout(() => ttCache.delete(t.id), 5000); });
    return p;
  }

  // Facebook-Reels: Beim Weiterwischen bleibt die Adresse oft beim ERSTEN Reel stehen. Trägt das laufende Video in
  // seinem eigenen Kasten eine andere Video-ID, gehört der Link zu dieser ID (nicht zur Seitenadresse).
  // Video-ID nur aus dem eigenen Kasten des Videos: Reel-/Video-Links und „video_id“-Daten (keine bloßen Zahlen,
  // nicht die Seitenadresse). Nach oben nur so weit, bis andere Videos dazukommen.
  function fbVideoId(v) {
    let el = v;
    for (let lvl = 0; el && el.nodeType === 1 && lvl < 18; lvl++, el = el.parentElement) {
      if (lvl > 0 && el.getElementsByTagName('video').length > 1) break;
      const ids = [];
      const at = el.attributes;
      for (let j = 0; j < at.length; j++) {
        const val = at[j].value;
        if (val.length > 5000) continue;
        const jm = val.match(/"video_?id"\s*:\s*"?(\d{12,})/i);
        if (jm) ids.push(jm[1]);
        if (at[j].name === 'href') idsIn(val, ids);
      }
      if (lvl > 0) { const as = el.querySelectorAll('a[href]'); for (let i = 0; i < as.length && i < 60; i++) idsIn(as[i].getAttribute('href'), ids); }
      const id = ids.find((x) => /^\d{12,}$/.test(x));
      if (id) return id;
    }
    return '';
  }
  function fbOwnUrl(v) {
    if (!v || !/(^|\.)facebook\.com$/i.test(location.hostname)) return '';
    const id = fbVideoId(v);
    if (!id) return '';
    const here = []; idsIn(location.href, here);
    if (here.includes(id)) return '';   // Adresse passt zum Video
    const reel = /\/reels?\//.test(location.pathname) || !/[?&]v=/.test(location.search);
    return location.origin + (reel ? '/reel/' + id + '/' : '/watch/?v=' + id);
  }
  function postUrlFor(v) {
    const fbu = fbOwnUrl(v);
    if (fbu) { postUrlFor.via = 'Video-ID'; return fbu; }
    postUrlFor.via = 'Seite';
    // Die Seite selbst ist schon die Beitragsseite (z. B. Reel, YouTube-Video)
    if (POST_RE.test(location.href) && !NOT_POST_RE.test(location.href)) return location.href.split('#')[0];
    if (!v) return '';
    // TikTok-Feed: Link genau dieses Videos – NIE die Feed-Adresse (/foryou)
    if (TT) return ttPostUrl(v);
    // Eigene Seite für genau dieses Video (Mediatheken: /video/…, /clip/…) und nur ein Video drauf: die Seitenadresse.
    // Links neben dem Player sind dort oft das nächste oder (nach einem Wechsel ohne Neuladen) das vorige Video.
    if (!META && VIDEO_PAGE_RE.test(location.pathname) && countEligible() <= 1) return location.href.split('#')[0];
    // Ganzer Beitrag als Einheit (Instagram, X und andere nutzen <article> pro Beitrag)
    const art = v.closest ? v.closest('article, [role="article"]') : null;
    if (art) { const b = bestLink(postLinks(art)); if (b) return b; }
    // Sonst: von innen nach außen suchen, bis andere Videos dazukommen
    let el = v;
    for (let i = 0; el && el.nodeType === 1 && i < 30; i++, el = el.parentElement) {
      if (i > 0 && el.getElementsByTagName('video').length > 1) break;
      const m = postLinks(el);
      if (m.size) return bestLink(m);
    }
    // Facebook-Feed: über die Video-ID
    if (/(^|\.)facebook\.com$/i.test(location.hostname)) {
      const ids = videoIdsFor(v);
      if (ids.length) return 'https://www.facebook.com/watch/?v=' + ids[0];
    }
    // Rückfall: auf unterstützten Seiten mit nur einem Video die Seitenadresse nehmen (nicht die Startseite)
    if (SHOW_DOWNLOAD && countEligible() <= 1 && !/^\/?$/.test(location.pathname) && !NOT_POST_RE.test(location.href)) {
      return location.href.split('#')[0];
    }
    return '';   // lieber nichts als die Startseite
  }
  // ---------- Instagram: fertige MP4 MIT Ton holen ----------
  // Der Instagram-Player lädt Bild und Ton getrennt (für VLC & Co. unbrauchbar). Zu jedem Beitrag gibt es aber eine
  // fertige MP4 mit Ton – die holt das Skript über dieselbe Schnittstelle, die auch die Instagram-Seite benutzt.
  const IG = /(^|\.)instagram\.com$/i.test(location.hostname);
  const IG_ALPHA = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';
  function igMediaId(code) {
    try {
      let id = BigInt(0);
      for (const ch of code.slice(0, 11)) { const i = IG_ALPHA.indexOf(ch); if (i < 0) return ''; id = id * BigInt(64) + BigInt(i); }
      return id.toString();
    } catch (e) { return ''; }
  }
  function igCodeFor(v) {
    const m = String(postUrlFor(v) || location.href).match(IG_POST_RE);
    return m ? m[1] : '';
  }
  const igCache = new Map();   // Beitrags-Code -> Promise(Adresse)
  let igLog = '-';
  function igVideoUrl(v) {
    if (!IG) return Promise.resolve('');
    const code = igCodeFor(v);
    if (!code) { igLog = 'kein Beitragslink'; return Promise.resolve(''); }
    if (igCache.has(code)) return igCache.get(code);
    const dur = v && isFinite(v.duration) && v.duration > 0 ? v.duration : 0;
    const p = (async () => {
      const log = [];
      const id = igMediaId(code);
      const paths = (id ? ['/api/v1/media/' + id + '/info/'] : []).concat(['/p/' + code + '/?__a=1&__d=dis']);
      for (const path of paths) {
        try {
          const r = await (rawFetch || fetch)(location.origin + path, { credentials: 'include', headers: { 'X-IG-App-ID': '936619743392459', 'X-Requested-With': 'XMLHttpRequest' } });
          if (!r.ok) { log.push(path.split('?')[0].replace(/\/info\/$/, '') + ': HTTP ' + r.status); igLog = log.join(' · '); continue; }
          const j = await r.json();
          const it = (j && j.items && j.items[0]) || (j && j.graphql && j.graphql.shortcode_media) || null;
          if (!it) { log.push('keine Daten'); igLog = log.join(' · '); continue; }
          const cands = [];
          const take = (n) => {
            if (!n) return;
            if (n.video_versions && n.video_versions.length) cands.push({ vv: n.video_versions, d: n.video_duration || 0 });
            else if (n.video_url) cands.push({ vv: [{ url: n.video_url, width: (n.dimensions || {}).width || 0, height: (n.dimensions || {}).height || 0 }], d: n.video_duration || 0 });
          };
          take(it);
          (it.carousel_media || []).forEach(take);
          ((it.edge_sidecar_to_children || {}).edges || []).forEach((e) => take(e && e.node));
          // Passt die Länge zum laufenden Video? Sonst ist es ein anderes (z. B. im Feed daneben)
          let pick = dur ? cands.find((c) => c.d && Math.abs(c.d - dur) < 2) : null;
          if (!pick && cands.length === 1 && (!dur || !cands[0].d)) pick = cands[0];
          if (!pick) { log.push('Länge passt nicht (' + cands.map((c) => Math.round(c.d)).join('/') + ' s statt ' + Math.round(dur) + ' s)'); igLog = log.join(' · '); continue; }
          const best = pick.vv.slice().sort((a, b) => (b.width || 0) * (b.height || 0) - (a.width || 0) * (a.height || 0))[0];
          if (best && best.url) { igLog = 'MP4 mit Ton gefunden (' + (best.width || '?') + '×' + (best.height || '?') + ')'; return best.url; }
        } catch (e) { log.push('Fehler: ' + (e && e.message)); igLog = log.join(' · '); }
      }
      return '';
    })();
    igCache.set(code, p);
    p.then((u) => { if (!u) setTimeout(() => igCache.delete(code), 5000); });
    return p;
  }

  function onDownload(v, opts) {
    const url = postUrlFor(v || active);
    if (!url) { toast('Kein Link zu diesem Video gefunden – Beitrag öffnen und nochmal versuchen'); return; }
    if (CONFIG.downloader !== 'share' && !opts) { toYtdlnis(url); return; }
    shareUrl(url);
  }
  // Link mit Titel teilen (Android: Teilen-Menü) – geht das nicht (z. B. eingebettetes Fenster), Link kopieren
  function shareLinkTo(url, title) {
    if (!url) { toast('Kein Link zu diesem Video gefunden'); return; }
    const copy = () => copyText(url, 'Link kopiert');
    if (navigator.share) {
      const data = { url: url };
      if (title) data.title = title;
      navigator.share(data).catch((e) => { if (!e || e.name !== 'AbortError') copy(); });
    } else copy();
  }
  function shareUrl(url) {
    const fallback = () => copyText(url, 'Link kopiert – in YTDLnis einfügen');
    if (navigator.share) {
      // Ergebnis: true = geteilt, false = abgebrochen/fehlgeschlagen
      return navigator.share({ url: url }).then(() => true, (e) => { if (!e || e.name !== 'AbortError') fallback(); return false; });
    }
    fallback();
    return Promise.resolve(false);
  }

  function typeShort(u) {
    const m = String(u).match(MEDIA_RE);
    return m ? m[1].toUpperCase() : '?';
  }

