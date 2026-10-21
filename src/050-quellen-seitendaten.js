  // ---------- 2) Facebook / Instagram / Threads: fertige MP4s aus den Seitendaten ----------
  const metaMap = new Map();     // Video-ID / Shortcode -> {hd, sd}
  const metaRecent = [];
  let metaCount = 0;
  const KEYWORDS = /video_versions|playable_url|browser_native_|progressive_url|hd_src|sd_src|video_url/;
  const HD_KEYS = ['browser_native_hd_url', 'playable_url_quality_hd', 'hd_src', 'hd_src_no_ratelimit'];
  const SD_KEYS = ['browser_native_sd_url', 'playable_url', 'sd_src', 'sd_src_no_ratelimit', 'video_url'];
  const ID_OWN = ['id', 'videoId', 'video_id', 'videoID', 'code', 'shortcode', 'pk', 'post_id'];
  const ID_UP = ['id', 'videoId', 'video_id', 'code', 'shortcode', 'pk'];

  function urlsIn(n) {
    let hd = '', sd = '';
    for (const k of HD_KEYS) { const x = n[k]; if (fullUrl(x)) { hd = x; break; } }
    for (const k of SD_KEYS) { const x = n[k]; if (fullUrl(x)) { sd = x; break; } }
    const vv = n.video_versions;
    if (Array.isArray(vv)) {
      const s = vv.filter((x) => x && fullUrl(x.url)).sort((a, b) => (b.width || 0) - (a.width || 0));
      if (s.length) {
        hd = hd || s[0].url;
        if (s.length > 1 && s[s.length - 1].url !== s[0].url) sd = sd || s[s.length - 1].url;
      }
    }
    const pu = n.progressive_urls;
    if (Array.isArray(pu)) {
      for (const p of pu) {
        if (!p || !fullUrl(p.progressive_url)) continue;
        const q = String((p.metadata && p.metadata.quality) || '').toUpperCase();
        if (q === 'HD') hd = hd || p.progressive_url; else sd = sd || p.progressive_url;
      }
    }
    if (hd === sd) sd = '';
    return hd || sd ? { hd: hd, sd: sd } : null;
  }

  function register(f, node, stack) {
    const ids = new Set();
    const take = (o, keys) => {
      for (const k of keys) {
        const x = o[k];
        if ((typeof x === 'string' || typeof x === 'number') && String(x).length >= 5) ids.add(String(x));
      }
    };
    take(node, ID_OWN);
    for (let i = stack.length - 1, n = 0; i >= 0 && n < 3; i--, n++) take(stack[i], ID_UP);
    if (!ids.size) return;
    const e = { hd: f.hd, sd: f.sd, t: Date.now(), ids: Array.from(ids) };
    ids.forEach((id) => {
      metaMap.set(id, e);
      const us = id.indexOf('_');
      if (us > 0) metaMap.set(id.slice(0, us), e);   // Instagram: "123_456" -> "123"
    });
    metaCount++;
    metaRecent.push(e);
    if (metaRecent.length > 50) metaRecent.shift();
    if (metaMap.size > 4000) {
      let n = 0;
      for (const k of metaMap.keys()) { metaMap.delete(k); if (++n > 800) break; }
    }
  }

  function walk(node, stack, depth) {
    if (!node || typeof node !== 'object' || depth > 150) return;
    if (Array.isArray(node)) { for (let i = 0; i < node.length; i++) walk(node[i], stack, depth + 1); return; }
    const f = urlsIn(node);
    if (f) register(f, node, stack);
    stack.push(node);
    for (const k in node) {
      const v = node[k];
      if (v && typeof v === 'object') walk(v, stack, depth + 1);
    }
    stack.pop();
  }

  // Notlösung für Daten, die nicht als sauberes JSON vorliegen
  const RX_URL = /"(browser_native_hd_url|browser_native_sd_url|playable_url_quality_hd|playable_url|hd_src|sd_src)"\s*:\s*"(https?:[^"]+)"/g;
  const RX_ID = /"(videoId|video_id|videoID|id)"\s*:\s*"?(\d{6,})"?/g;
  function regexScan(text) {
    let m;
    RX_URL.lastIndex = 0;
    while ((m = RX_URL.exec(text))) {
      const from = Math.max(0, m.index - 3000);
      const win = text.slice(from, m.index + 3000);
      let best = null, bestDist = 1e9, im;
      RX_ID.lastIndex = 0;
      while ((im = RX_ID.exec(win))) {
        const d = Math.abs(from + im.index - m.index);
        if (d < bestDist) { bestDist = d; best = im[2]; }
      }
      if (!best) continue;
      const url = unesc(m[2]);
      if (isSplitTrack(url)) continue;
      const hd = /hd/.test(m[1]) ? url : '';
      const sd = hd ? '' : url;
      const old = metaMap.get(best);
      register({ hd: hd || (old && old.hd) || '', sd: sd || (old && old.sd) || '' }, { id: best }, []);
    }
  }

  function scanText(text) {
    if (!text || typeof text !== 'string' || text.length > 15e6 || !KEYWORDS.test(text)) return;
    const t = text.replace(/^\s*for\s*\(;;\);/, '');
    try { walk(JSON.parse(t), [], 0); return; } catch (e) {}
    let any = false;
    t.split(/\r?\n/).forEach((line) => {
      line = line.trim().replace(/^for\s*\(;;\);/, '');
      if (!line || (line[0] !== '{' && line[0] !== '[') || !KEYWORDS.test(line)) return;
      try { walk(JSON.parse(line), [], 0); any = true; } catch (e) {}
    });
    if (!any) regexScan(t);
  }

  const seenScripts = new WeakSet();
  function scanScriptEl(s) {
    if (seenScripts.has(s)) return;
    const ty = s.type;
    if (ty && !/json|javascript|ecmascript/i.test(ty)) { seenScripts.add(s); return; }
    const txt = s.textContent;
    if (!txt) return;
    seenScripts.add(s);
    scanText(txt);
  }
  function scanScripts() {
    if (!META) return;
    const all = document.getElementsByTagName('script');
    for (let i = 0; i < all.length; i++) scanScriptEl(all[i]);
  }
  if (META) {
    document.addEventListener('DOMContentLoaded', scanScripts);
    try {
      new MutationObserver((muts) => {
        if (document.readyState === 'loading') return;
        for (const m of muts) for (const n of m.addedNodes) if (n.tagName === 'SCRIPT') scanScriptEl(n);
      }).observe(document, { childList: true, subtree: true });
    } catch (e) {}
  }

