  // ---------- 3) Passende Quelle für GENAU dieses Video finden ----------
  const FB_ID_RE = /(?:\/videos\/(?:vb\.\d+\/)?(?:[^\/?#]+\/)?|\/reels?\/|[?&]v=|video_id=|story_fbid=)(\d{6,})/g;
  const IG_ID_RE = /\/(?:p|reels?|tv)\/([A-Za-z0-9_-]{5,})/g;
  function idsIn(s, out) {
    if (!s) return;
    let m;
    FB_ID_RE.lastIndex = 0; while ((m = FB_ID_RE.exec(s))) out.push(m[1]);
    IG_ID_RE.lastIndex = 0; while ((m = IG_ID_RE.exec(s))) out.push(m[1]);
  }

  function srcOf(v) {
    if (!v) return '';
    const s = v.currentSrc || v.src || ((v.querySelector && v.querySelector('source[src]')) || {}).src || '';
    if (!/^https?:/i.test(s)) return '';
    if (isSplitTrack(s)) return '';
    return s;
  }

  // Alle Videoadressen im Beitrag rund um das Video sammeln und einordnen:
  // full = komplett mit Ton, split = nur Bild- oder Tonspur, unknown = unklar
  const URL_G = /https?:(?:\\?\/){2}[^"'\s<>]+?\.(?:m3u8|mpd|mp4)(?:[?#][^"'\s<>]*)?/gi;
  function classify(u) {
    const raw = efgInfo(u);
    const info = raw.toLowerCase();
    let j = {};
    try { j = JSON.parse(raw) || {}; } catch (e) {}
    const tag = String(j.vencode_tag || encTag(u) || '');
    let kind = 'unknown';
    if (SEGMENT_RE.test(u)) kind = 'split';
    else if (info.indexOf('progressive') >= 0) kind = 'full';
    else if (/dash|audio|video_only/.test(info)) kind = 'split';
    // Auflösung aus der Kennung lesen, z. B. "…C3.720.dash_h264…_720p" oder "…_360p"
    let res = 0;
    const rms = tag.match(/(\d{3,4})p/g);
    if (rms) res = Math.max.apply(null, rms.map((x) => parseInt(x, 10)));
    if (!res) { const rd = tag.match(/\.(\d{3,4})\./); if (rd) res = +rd[1]; }
    if (!res && /(^|[^a-z])hd([^a-z]|$)/.test(tag.toLowerCase())) res = 720;
    if (!res && /(^|[^a-z])sd([^a-z]|$)/.test(tag.toLowerCase())) res = 360;
    // Welches Video? (dieselbe ID = dasselbe Video in anderer Qualität)
    const asset = String(j.xpv_asset_id || j.asset_id || j.video_id || '') || '';
    return { u: u, tag: tag, kind: kind, res: res, br: +j.bitrate || 0, asset: asset === 'null' ? '' : asset };
  }
  function cleanUrl(s) {
    return unesc(s.split(/&quot;|&#0?39;|&#x27;|&lt;|&gt;/)[0]).replace(/\\+$/, '');
  }
  function collectUrls(text, out, seen, from) {
    if (!text || text.length < 12 || text.indexOf('http') < 0) return;
    URL_G.lastIndex = 0;
    let m;
    while ((m = URL_G.exec(text)) && out.length < 400) {
      const u = cleanUrl(m[0]);
      if (!MEDIA_RE.test(u)) continue;
      if (seen.has(u)) continue;
      seen.add(u);
      const c = classify(u);
      c.from = from + ': …' + text.slice(Math.max(0, m.index - 28), m.index).replace(/\s+/g, ' ');
      out.push(c);
    }
  }
  function scanElement(container, out, seen, skipVideo) {
    const els = [container].concat(Array.from(container.getElementsByTagName('*')).slice(0, 4000));
    for (const el of els) {
      const at = el.attributes;
      if (at) for (let j = 0; j < at.length; j++) {
        if (el === skipVideo && /^(src|currentsrc)$/i.test(at[j].name)) continue;
        collectUrls(at[j].value, out, seen, at[j].name);
      }
      if (el.tagName === 'SCRIPT') collectUrls(el.textContent, out, seen, 'script');
    }
  }

  let lastNear = [];
  const byQuality = (a, b) => (b.res - a.res) || (b.br - a.br);
  function scanWholePage(out, seen) {
    try { collectUrls(document.documentElement.innerHTML, out, seen, 'seite'); } catch (e) {}
    const sc = document.getElementsByTagName('script');
    for (let i = 0; i < sc.length; i++) collectUrls(sc[i].textContent, out, seen, 'script');
  }
  // ---------- Facebook: HD-Version mit Ton im Hintergrund nachladen ----------
  const hdCache = new Map();    // Video-ID (asset) -> {found, best, tries, at}
  const hdPending = new Map();  // Video-ID -> Promise
  let hdLog = '-';
  function videoIdsFor(v) {
    const ids = [];
    if (v) metaFor(v, ids);
    return ids.filter((x) => /^\d{12,}$/.test(x));
  }
  function fetchHD(v) {
    if (!v || !/(^|\.)facebook\.com$/i.test(location.hostname)) return null;
    const since = startAt.get(v) || 0;
    const af = assetFor(v), asset = af.asset;
    if (!asset) { hdLog = 'kein laufendes Video erkannt'; return null; }
    if (!af.sure && hitCount(asset, since) < 2) { hdLog = 'Video wird noch erkannt …'; return null; }   // erst sicher sein, welches Video läuft
    if (hdPending.has(asset)) return hdPending.get(asset);
    const prev = hdCache.get(asset);
    // HD schon da, oder 3× versucht, oder letzter Versuch < 8 s her: nicht nochmal suchen
    if (prev && (prev.best >= 720 || prev.tries >= 3 || Date.now() - prev.at < 8000)) return null;
    const ids = videoIdsFor(v);
    const paths = [];
    const own = ids.indexOf(asset) >= 0 ? [asset].concat(ids.filter((x) => x !== asset)) : ids;   // eigene Seite zuerst
    own.slice(0, 3).forEach((id) => paths.push('/reel/' + id + '/', '/watch/?v=' + id, '/video.php?v=' + id, '/' + id));
    if (!paths.length) { hdLog = 'keine Video-ID zum Nachladen gefunden'; return null; }
    const p = (async () => {
      const found = prev ? prev.found.slice() : [];
      for (const path of paths) {
        try {
          const res = await fetch(location.origin + path, { credentials: 'include' });
          if (!res.ok) { hdLog = path + ': HTTP ' + res.status; continue; }
          const text = await res.text();
          const out = [], seen = new Set();
          collectUrls(text, out, seen, 'nachgeladen');
          let mine = out.filter((c) => c.asset === asset && c.kind === 'full');
          // HD-Adressen ohne Video-ID-Kennung über die Seitendaten zuordnen
          scanText(text);
          // Nur die eigene Video-ID: die Seite enthält im Reels-Feed auch die nächsten (vorgeladenen) Reels –
          // deren HD-Adressen gehören zu einem ANDEREN Video
          ids.filter((id) => id === asset).forEach((id) => {
            const e = metaMap.get(id);
            if (!e) return;
            [e.hd, e.sd].filter(Boolean).forEach((u) => {
              if (mine.some((c) => c.u === u)) return;
              const c = classify(u);
              if (c.kind === 'split') return;
              if (c.asset && c.asset !== asset) return;   // Adresse sagt selbst: anderes Video
              c.kind = 'full';
              if (!c.res) c.res = u === e.hd ? 720 : 360;
              c.from = 'nachgeladen';
              mine.push(c);
            });
          });
          hdLog = path + ': ' + out.length + ' Adressen, ' + mine.length + ' passend' +
            (mine.length ? ', max ' + Math.max.apply(null, mine.map((c) => c.res)) + 'p' : '');
          mine.forEach((c) => { if (!found.some((f) => f.u === c.u)) found.push(c); });
          if (found.some((f) => f.res >= 720)) break;     // HD gefunden – fertig; sonst nächste Seite probieren
        } catch (e) { hdLog = path + ': Fehler ' + (e && e.message); }
      }
      for (let i = found.length - 1; i >= 0; i--) if (found[i].asset && found[i].asset !== asset) found.splice(i, 1);
      const best = found.length ? Math.max.apply(null, found.map((f) => f.res)) : 0;
      hdCache.set(asset, { found: found, best: best, tries: (prev ? prev.tries : 0) + 1, at: Date.now() });
      hdPending.delete(asset);
      return found;
    })();
    hdPending.set(asset, p);
    return p;
  }

  let lastVia = '';
  function qualityResult(group, via) {
    const full = group.filter((c) => c.kind === 'full').sort(byQuality);
    if (!full.length) return null;
    const q = [];
    full.forEach((c) => { if (!q.some((x) => x.res === c.res && x.res)) q.push(c); });
    const splitMax = Math.max.apply(null, [0].concat(group.filter((c) => c.kind === 'split').map((c) => c.res)));
    lastVia = via;
    return { best: q[0].u, alt: '', qualities: q, splitMax: splitMax > q[0].res ? splitMax : 0,
      how: 'mit Ton' + (q[0].res ? ' · ' + q[0].res + 'p' : '') };
  }
  function mediaNear(v) {
    lastVia = '';
    lastNear = [];
    const out = [], seen = new Set();

    // 1) Sicherster Weg: das Video, das der Web-Player gerade lädt (Video-ID aus dem Netzwerk)
    // Nur wenn dieses Video das einzige ist oder gerade selbst läuft – sonst gehört der Netzwerkverkehr evtl. zu einem anderen
    const af = META && (countEligible() <= 1 || !v.paused) ? assetFor(v) : { asset: '', sure: false };
    const byAsset = (asset, via) => {
      scanWholePage(out, seen);
      const mine = out.filter((c) => c.asset === asset);
      ((hdCache.get(asset) || {}).found || []).forEach((c) => { if ((!c.asset || c.asset === asset) && !mine.some((x) => x.u === c.u)) mine.push(c); });
      lastNear = mine;
      const res = qualityResult(mine, via);
      return res || (mine.length ? 'split' : null);
    };
    if (af.asset && af.sure) {
      const res = byAsset(af.asset, 'laufendes Video');
      if (res === 'split') return null;   // nur Spuren ohne Ton für genau dieses Video
      if (res) return res;
    }
    // Unsicher (z. B. Reels: mehrere Videos gleichzeitig vorgeladen)? Erst direkt am Video schauen, raten nur zuletzt.
    const near = localNear(v);
    if (near) return near;
    if (af.asset && !af.sure) {
      const res = byAsset(af.asset, 'laufendes Video (unsicher)');
      if (res && res !== 'split') return res;
    }
    return localNear.fallback || null;
  }
  // 2) Nur die direkte Umgebung des Videos, Ebene für Ebene von innen nach außen
  function localNear(v) {
    localNear.fallback = null;
    const out = [], seen = new Set();

    const local = [], lseen = new Set();
    let el = v;
    for (let lvl = 0; el && el.nodeType === 1 && lvl < 10; lvl++, el = el.parentElement) {
      if (lvl > 0 && el.getElementsByTagName('video').length > 1) break;
      const before = local.length;
      scanElement(el, local, lseen, v);
      if (local.length === before) continue;
      const full = local.filter((c) => c.kind === 'full');
      const ids = new Set(local.filter((c) => c.asset).map((c) => c.asset));
      if (ids.size > 1) break;                 // hier beginnen schon andere Videos – lieber aufhören
      if (full.length) {
        lastNear = local.slice();
        // Gleiche Video-ID: weitere Qualitäten dieses Videos auf der Seite einsammeln
        const id = ids.size ? Array.from(ids)[0] : '';
        if (id) {
          scanWholePage(out, seen);
          const all = local.concat(out.filter((c) => c.asset === id && !lseen.has(c.u)));
          lastNear = all;
          return qualityResult(all, 'Beitrag');
        }
        return qualityResult(local, 'Beitrag');
      }
    }
    if (!lastNear.length) lastNear = local;
    const unk = local.filter((c) => c.kind === 'unknown');
    if (unk.length === 1) localNear.fallback = { best: unk[0].u, alt: '', how: 'Seitendaten' };
    return null;
  }

  function metaFor(v, idsOut) {
    if (!META) return null;
    scanScripts();
    const check = (ids) => {
      for (const id of ids) {
        if (!idsOut.includes(id)) idsOut.push(id);
        if (metaMap.has(id)) { metaFor.lastId = id; return metaMap.get(id); }
      }
      return null;
    };
    metaFor.lastId = '';
    let el = v;
    for (let lvl = 0; el && el.nodeType === 1 && lvl < 18; lvl++, el = el.parentElement) {
      if (lvl > 0 && el.getElementsByTagName('video').length > 1) break;   // weiter oben liegen schon andere Beiträge
      const ids = [];
      const at = el.attributes;
      for (let j = 0; j < at.length; j++) {
        const val = at[j].value;
        if (/^\d{8,}$/.test(val)) ids.push(val);
        else if (val.length < 5000) {
          idsIn(val, ids);
          const jm = val.match(/"video_?id"\s*:\s*"?(\d{6,})/i);
          if (jm) ids.push(jm[1]);
        }
      }
      if (lvl > 0) {
        const as = el.querySelectorAll('a[href]');
        for (let i = 0; i < as.length && i < 60; i++) idsIn(as[i].getAttribute('href'), ids);
      }
      const hit = check(ids);
      if (hit) return hit;
    }
    const ids = [];
    idsIn(location.href, ids);
    return check(ids);
  }

  function posterMatch(v) {
    const p = (v && (v.poster || v.getAttribute('poster'))) || '';
    const toks = p.match(/\d{8,}/g) || [];
    if (!toks.length) return '';
    const m = recent().filter((u) => toks.some((t) => u.indexOf(t) >= 0));
    const pl = m.filter((u) => PLAYLIST_RE.test(u));
    return rankPlaylists(pl)[0] || m[0] || '';
  }

  const startAt = new WeakMap();
  document.addEventListener('loadstart', (e) => { const v = e.target; if (v && v.tagName === 'VIDEO') startAt.set(v, Date.now()); }, true);
  document.addEventListener('play', (e) => { const v = e.target; if (v && v.tagName === 'VIDEO' && !startAt.has(v)) startAt.set(v, Date.now()); }, true);
  function timingMatch(v) {
    checkNav();   // gerade erst zu einem anderen Video gewechselt? (Prüfung läuft nur jede Sekunde)
    const s = startAt.get(v);
    if (!s) return '';
    // Haupt-Playlists werden oft schon vor dem Videostart geladen – darum weiter zurückschauen,
    // aber nie vor einen Videowechsel auf der Seite (sonst käme schnell hintereinander das VORIGE Video)
    const lo = Math.max(s - 15000, navAt);
    const c = sources.filter((x) => x.t >= lo && x.t <= Math.max(s, navAt) + 6000).map((x) => x.u).filter((u) => PLAYLIST_RE.test(u));
    return rankPlaylists(c)[0] || '';
  }

  // Videos auch in gekapselten Bereichen (Shadow DOM) finden – viele moderne Player verstecken sie dort.
  // Die Suche dort ist teurer, deshalb nur alle 3 Sekunden.
  let shadowVids = [], shadowAt = 0;
  function scanShadow() {
    const out = [];
    let budget = 8000;
    const visit = (root, depth) => {
      if (depth > 4 || budget <= 0) return;
      const els = root.querySelectorAll('*');
      for (let i = 0; i < els.length && budget > 0; i++, budget--) {
        const sr = els[i].shadowRoot;
        if (!sr) continue;
        const vs = sr.querySelectorAll('video');
        for (let j = 0; j < vs.length; j++) out.push(vs[j]);
        visit(sr, depth + 1);
      }
    };
    try { visit(document, 0); } catch (e) {}
    return out;
  }
  // Fenster ohne eigene Quelle? (Die Adresse hilft nicht: per Skript befüllte Fenster übernehmen die der Seite.)
  function isBlankFrame(fe) {
    const src = (fe.getAttribute('src') || '').trim();
    return !src || /^(about:|javascript:|blob:|data:)/i.test(src) || fe.hasAttribute('srcdoc');
  }
  // Eingebettete Fenster OHNE eigene Adresse (about:blank, srcdoc, blob – von der Seite per Skript befüllt):
  // Dort laufen Userscripts nicht. Weil sie zur Seite gehören, kann die Seite selbst hineinschauen.
  function blankFrameVideos() {
    const out = [];
    const visit = (doc, depth) => {
      if (depth > 3) return;
      const frames = doc.getElementsByTagName('iframe');
      for (let i = 0; i < frames.length; i++) {
        let d = null;
        try { d = frames[i].contentDocument; } catch (e) { d = null; }
        if (!d) continue;                       // fremde Domain: dort läuft das Skript selbst
        if (!isBlankFrame(frames[i])) continue; // normale Seite: dort läuft das Skript selbst
        const vs = d.getElementsByTagName('video');
        for (let j = 0; j < vs.length; j++) out.push(vs[j]);
        visit(d, depth + 1);
      }
    };
    try { visit(document, 0); } catch (e) {}
    return out;
  }
  let frameVids = [], frameAt = 0;
  let lastVidCount = 0;
  function allVideos() {
    const list = Array.prototype.slice.call(document.getElementsByTagName('video'));
    if (Date.now() - shadowAt > 3000) { shadowAt = Date.now(); shadowVids = scanShadow(); }
    shadowVids = shadowVids.filter((v) => v.isConnected);
    shadowVids.forEach((v) => { if (list.indexOf(v) < 0) list.push(v); });
    if (Date.now() - frameAt > 1500) { frameAt = Date.now(); frameVids = blankFrameVideos(); }
    frameVids = frameVids.filter((v) => v.isConnected);
    frameVids.forEach((v) => { if (list.indexOf(v) < 0) list.push(v); });
    lastVidCount = list.length;
    return list;
  }
  // Position eines Videos auf dem Bildschirm – auch wenn es in einem eingebetteten Fenster steckt
  function rectOf(v) {
    const r = v.getBoundingClientRect();
    let fe = null;
    try { const w = v.ownerDocument && v.ownerDocument.defaultView; fe = w && w !== window ? w.frameElement : null; } catch (e) { fe = null; }
    if (!fe) return r;
    const fr = rectOf(fe);   // auch verschachtelte Fenster
    return { left: r.left + fr.left, top: r.top + fr.top, right: r.right + fr.left, bottom: r.bottom + fr.top, width: r.width, height: r.height };
  }

  function countEligible() {
    let n = 0;
    const vids = allVideos();
    for (let i = 0; i < vids.length; i++) if (!(vp && vids[i] === vp.vid) && eligible(vids[i])) n++;   // eigenen Player nicht mitzählen
    return n;
  }


  function resolve(v) {
    const r = { best: '', alt: '', how: '', ids: [] };
    if (!v) return r;
    // Facebook/Instagram: zuerst die fertigen MP4s mit Ton aus den Seitendaten
    if (META) {
      let m = metaFor(v, r.ids);
      // Gegenprobe mit dem, was gerade WIRKLICH abgespielt wird: Im Reels-Feed steht neben dem Video oft schon
      // die ID des nächsten (vorgeladenen) Reels – dann gehören diese Seitendaten zu einem anderen Video.
      if (m) {
        const af = assetFor(v), hit = metaFor.lastId;
        if (af.sure && af.asset && hit && hit !== af.asset) {
          if (metaMap.has(af.asset)) { m = metaMap.get(af.asset); r.ids.unshift(af.asset); }   // Daten des richtigen Videos
          else if (assetHits.has(hit)) m = null;   // die gefundene ID ist ein anderes, gerade geladenes Video
        }
      }
      if (m) { r.best = m.hd || m.sd; r.alt = m.hd && m.sd ? m.sd : ''; r.how = m.hd ? 'HD' : 'SD'; return r; }
    }
    let u = srcOf(v);
    if (u) { r.best = u; r.how = 'Video-Element'; r.list = [{ u: u, how: r.how }]; return r; }

    if (META) {
      const near = mediaNear(v);
      if (near) { r.best = near.best; r.alt = near.alt; r.how = near.how; r.qualities = near.qualities || null; r.splitMax = near.splitMax || 0; return r; }
      if (lastNear.length && lastNear.every((c) => c.kind === 'split')) r.splitOnly = true;
      return r;
    }

    // Alle anderen Seiten: Liste möglicher Quellen, die beste zuerst.
    // Was der Web-Player GERADE lädt (Netzwerk) ist zuverlässiger als Angaben in der Seitenbeschreibung.
    const list = [];
    const add = (url, how) => { if (url && !list.some((x) => x.u === url)) list.push({ u: url, how: how }); };
    const single = countEligible() <= 1;
    // Pornhub: komplette MP4 mit Ton zuerst (beste Qualität vorn), der Stream nur als Ausweich
    if (PH && phCache && phCache.length) phCache.forEach((c) => add(c.u, 'MP4 mit Ton' + (c.res ? ' · ' + c.res + 'p' : '')));
    // 1. bewiesen: die Seite lädt gerade Stücke genau dieses Streams (seit Start dieses Videos / letztem Wechsel)
    checkNav();
    const ps = playingStream(Math.max(navAt, (startAt.get(v) || 0) - 300));
    if (ps) add(ps.u, 'Stream (läuft gerade, geprüft)');
    add(timingMatch(v), 'Stream (läuft gerade)');
    add(posterMatch(v), 'Stream (Vorschaubild)');
    if (single) {
      const net = recent();
      const pl = net.filter((x) => PLAYLIST_RE.test(x));
      rankPlaylists(pl).forEach((x) => add(x, 'Stream (Netzwerk)'));
    }
    const near = mediaNear(v);
    if (near) {
      (near.qualities || [{ u: near.best }]).forEach((c) => add(c.u, near.how || 'Seitendaten'));
    }
    if (single) recent().filter((x) => !PLAYLIST_RE.test(x)).reverse().forEach((x) => add(x, 'Datei (Netzwerk)'));
    // 2. Gegenprobe Länge: Streams, deren Länge NICHT zum Video auf der Seite passt, sind ein anderes Video → ans Ende.
    //    Passende vor unbekannten. Der bewiesene bleibt vorn. (Live: keine Länge, keine Prüfung.)
    const d = v.duration;
    r.proof = ps ? 'Stücke' : '';
    if (isFinite(d) && d > 1) {
      const cls = (x) => {
        if (ps && x.u === ps.u) return -1;
        const l = PLAYLIST_RE.test(x.u) ? streamLen(x.u) : null;
        if (!l || l.live) return 1;
        if (Math.abs(l.dur - d) <= lenTol(d)) { x.how += ' · Länge passt'; return 0; }
        x.how += ' · andere Länge'; return 2;
      };
      list.forEach((x, i) => { x.c = cls(x); x.i = i; });
      list.sort((a, b) => a.c - b.c || a.i - b.i);
      if (!r.proof && list.length && list[0].c === 0) r.proof = 'Länge';
      list.forEach((x) => { delete x.c; delete x.i; });
    }
    r.list = list.slice(0, 8);
    if (r.list.length) { r.best = r.list[0].u; r.how = r.list[0].how; }
    return r;
  }

