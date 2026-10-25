    // ----- Untertitel: von der Seite (<track>), aus dem Stream (HLS) oder eigene Datei (.srt/.vtt) -----
    // Eigene Anzeige (gleich in allen Browsern, Größe/Höhe einstellbar), Text immer nur als Text (kein HTML der Seite).
    const subs = { cur: null, cues: null, tt: null, last: '', timer: 0, offset: 0, file: null };
    // TTML / EBU-TT / DFXP (XML – nutzen z. B. ARD und ZDF): <p begin=".." end=".."> mit <br/> und <span>
    function parseTTML(txt) {
      let doc = null;
      try { doc = new DOMParser().parseFromString(txt, 'application/xml'); } catch (e) { return []; }
      if (!doc || !doc.documentElement || doc.getElementsByTagName('parsererror').length) return [];
      const tt = doc.documentElement;
      const par = (n) => { for (let i = 0; i < tt.attributes.length; i++) { const a = tt.attributes[i]; if (a.localName === n) return a.value; } return ''; };
      const fr = parseFloat(par('frameRate')) || 25, tr = parseFloat(par('tickRate')) || fr;
      const tm = (x) => {
        x = String(x || '').trim(); let m;
        if ((m = /^(\d+):(\d{2}):(\d{2})(?:[.,](\d+))?$/.exec(x))) return +m[1] * 3600 + +m[2] * 60 + +m[3] + (m[4] ? +('0.' + m[4]) : 0);
        if ((m = /^(\d+):(\d{2}):(\d{2}):(\d+(?:\.\d+)?)$/.exec(x))) return +m[1] * 3600 + +m[2] * 60 + +m[3] + +m[4] / fr;
        if ((m = /^(\d+(?:\.\d+)?)(h|ms|m|s|f|t)$/.exec(x))) { const v = +m[1]; return { h: v * 3600, m: v * 60, s: v, ms: v / 1000, f: v / fr, t: v / tr }[m[2]]; }
        return NaN;
      };
      const txtOf = (el) => {
        let o = '';
        el.childNodes.forEach((c) => {
          if (c.nodeType === 3) o += c.nodeValue.replace(/\s+/g, ' ');
          else if (c.nodeType === 1) o += c.localName === 'br' ? '\n' : txtOf(c);
        });
        return o;
      };
      const out = [];
      const ps = doc.getElementsByTagNameNS('*', 'p');
      for (let i = 0; i < ps.length; i++) {
        const p = ps[i];
        let base = 0;   // Zeiten der umgebenden <div>/<body> zählen mit
        for (let a = p.parentElement; a && a !== tt; a = a.parentElement) { const b = tm(a.getAttribute('begin')); if (b > 0) base += b; }
        const b = tm(p.getAttribute('begin'));
        let e = tm(p.getAttribute('end'));
        if (!(e >= 0) && p.getAttribute('dur')) e = b + tm(p.getAttribute('dur'));
        const text = txtOf(p).split('\n').map((l) => l.trim()).filter(Boolean).join('\n');
        if (b >= 0 && e > b && text) out.push([base + b, base + e, text]);
      }
      return out.sort((x, y) => x[0] - y[0]);
    }
    function parseSubs(txt) {
      if (/^\s*(<\?xml|<tt[\s>:])/i.test(String(txt || '').replace(/^\uFEFF/, ''))) return parseTTML(String(txt).replace(/^\uFEFF/, ''));
      const out = [];
      const ts = (x) => {
        const m = /(?:(\d+):)?(\d{1,2}):(\d{2})[.,](\d{1,3})/.exec(x);
        return m ? (+m[1] || 0) * 3600 + +m[2] * 60 + +m[3] + +(m[4] + '00').slice(0, 3) / 1000 : NaN;
      };
      const ent = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ' };
      String(txt || '').replace(/^﻿/, '').replace(/\r/g, '').split(/\n[ \t]*\n/).forEach((b) => {
        const lines = b.split('\n');
        const i = lines.findIndex((l) => l.indexOf('-->') >= 0);
        if (i < 0) return;
        const pr = lines[i].split('-->');
        const a = ts(pr[0]), z = ts(pr[1] || '');
        if (!(z > a)) return;
        const text = lines.slice(i + 1).join('\n').replace(/<[^>]*>/g, '').replace(/\{\\[^}]*\}/g, '')
          .replace(/&(amp|lt|gt|quot|apos|nbsp);/g, (_, k) => ent[k]).trim();
        if (text) out.push([a, z, text]);
      });
      return out.sort((x, y) => x[0] - y[0]);
    }
    function cueAt(t) {
      const c = subs.cues;
      if (!c || !c.length) return '';
      let lo = 0, hi = c.length;
      while (lo < hi) { const mid = (lo + hi) >> 1; if (c[mid][0] <= t) lo = mid + 1; else hi = mid; }
      const on = [];
      for (let i = lo - 1; i >= 0 && i >= lo - 8; i--) if (c[i][1] > t) on.unshift(c[i][2]);
      return on.join('\n');
    }
    function subText() {
      const t = M().currentTime + subs.offset;
      if (subs.cues) return cueAt(t);
      const tt = subs.tt;
      if (!tt || !tt.cues) return '';
      const on = [];
      for (let i = 0; i < tt.cues.length; i++) { const c = tt.cues[i]; if (c.startTime <= t && c.endTime > t) on.push(String(c.text || '')); }
      return on.join('\n').replace(/<[^>]*>/g, '');
    }
    function subTick() {
      if (vp !== st || !subs.cur) return;
      if (subs.tt && subs.tt.mode !== 'hidden') { try { subs.tt.mode = 'hidden'; } catch (e) {} }   // nicht doppelt (Browser + wir)
      const x = subText();
      if (x === subs.last) return;
      subs.last = x;
      subEl.textContent = '';
      // jede Zeile eine eigene Fläche; der Zeilenumbruch bleibt als Text (pre-line) erhalten
      if (x) x.split('\n').forEach((l, i) => { if (i) subEl.append('\n'); subEl.append(h('span', null, l)); });
    }
    // Lage am SICHTBAREN Bild (nicht am Bildschirmrand): unten knapp über der Unterkante des Bildes, oben knapp unter
    // der Oberkante – bei Querformat-Videos hochkant also im Bild statt ganz unten. Eingeblendete Leisten verdecken nie.
    function subPlace() {
      if (!subEl.isConnected) return;
      const b = contentBox(), H = b[3];
      const hz = b[1] * st.zoom;
      const top = Math.max(0, H / 2 + st.panY - hz / 2), bot = Math.min(H, H / 2 + st.panY + hz / 2);
      const pad = Math.round(Math.max(8, (bot - top) * 0.04));
      // Schriftgröße wie in Videoplayern üblich nach der Höhe des sichtbaren Bildes (≈ 5 %) – hochkant wirkt sie so
      // genauso wie im Querformat; nie kleiner als gut lesbar. klein/mittel/groß = Faktor darauf.
      const f = { s: 0.85, m: 1, l: 1.25 }[CONFIG.subSize] || 1;
      subEl.style.fontSize = Math.round(clamp((bot - top) * 0.052, 14, 28) * f * 10) / 10 + 'px';
      const ctlOn = wrap.classList.contains('ctl');
      // Lage eines Bedienelements ohne laufende Einblend-Animation (offsetTop statt getBoundingClientRect)
      const yOf = (el) => { let y = 0; for (let e = el; e && e !== wrap; e = e.offsetParent) y += e.offsetTop; return y; };
      if (CONFIG.subHigh) {
        subEl.style.bottom = 'auto';
        // Querformat: direkt unter Titel und Knöpfen (gemessen) statt unter einem festen Abstand
        const tops = ctlOn && b[2] > H ? Array.from(wrap.querySelectorAll('.vtop > *')).filter((e) => e.offsetHeight) : [];
        const under = tops.length ? Math.max.apply(null, tops.map((e) => yOf(e) + e.offsetHeight)) + 4 : -1;
        subEl.style.top = under > 0
          ? Math.round(Math.max(top + pad, under)) + 'px'
          : 'max(' + Math.round(top + pad) + 'px, calc(' + (ctlOn ? 82 : 12) + 'px + var(--evp-st,env(safe-area-inset-top,0px))))';
      } else {
        subEl.style.top = 'auto';
        // Querformat: die Knöpfe sitzen dort links/rechts in den Ecken – nur über die Zeitleiste ganz unten rücken,
        // damit die Untertitel beim Einblenden der Leisten nicht in die Bildmitte springen
        const row = ctlOn && b[2] > H ? wrap.querySelector('.vrow') : null;
        let rowTop = -1;
        if (row) rowTop = yOf(row);
        subEl.style.bottom = rowTop > 0
          ? Math.round(Math.max(H - bot + pad, H - rowTop + 4)) + 'px'
          : 'max(' + Math.round(H - bot + pad) + 'px, calc(' + (ctlOn ? 150 : 26) + 'px + var(--evp-sb,env(safe-area-inset-bottom,0px))))';
      }
    }
    st.subPlace = subPlace;
    function subLayout() {
      subEl.dataset.size = CONFIG.subSize || 'm';
      subEl.classList.toggle('hi', !!CONFIG.subHigh);
      subPlace();
    }
    subLayout();
    // Leisten ein/aus → Lage nachziehen (nur ein Blick auf die Klasse)
    let subCtl = wrap.classList.contains('ctl');
    const subMO = new MutationObserver(() => { const c = wrap.classList.contains('ctl'); if (c !== subCtl) { subCtl = c; subPlace(); } });
    subMO.observe(wrap, { attributes: true, attributeFilter: ['class'] });
    vid.addEventListener('loadedmetadata', subPlace);
    addEventListener('resize', subPlace);
    st.cleanup.push(() => { subMO.disconnect(); removeEventListener('resize', subPlace); });
    function subStop(keepHls) {
      clearInterval(subs.timer); subs.timer = 0;
      if (subs.cur && subs.cur.kind === 'hls') {
        try { if (st.hls && keepHls !== true) { st.hls.subtitleTrack = -1; st.hls.subtitleDisplay = false; } } catch (e) {}
        if (subs.tt) { try { subs.tt.mode = 'disabled'; } catch (e) {} }
      } else if (subs.tt && subs.ttWas != null) { try { subs.tt.mode = subs.ttWas; } catch (e) {} }
      subs.cur = null; subs.cues = null; subs.tt = null; subs.ttWas = null; subs.last = '';
      subEl.textContent = '';
      bSubs.classList.remove('on');
    }
    st.cleanup.push(() => subStop());
    function subStart(src, cues, tt) {
      subStop(src.kind === 'hls');   // neue Stream-Spur ist schon gewählt – nicht wieder abschalten
      subs.cur = src; subs.cues = cues || null;
      if (tt) { subs.tt = tt; subs.ttWas = tt.mode; try { tt.mode = 'hidden'; } catch (e) {} }
      subs.timer = setInterval(subTick, 150);
      bSubs.classList.add('on');
      subTick();
    }
    function gmText(u) {
      return new Promise((res) => {
        const x = /^(data|blob):/i.test(u) ? null : gmXhr();
        if (!x) { fetch(u, { credentials: 'include', referrerPolicy: 'strict-origin-when-cross-origin' }).then((r) => (r.ok ? r.text() : ''), () => '').then(res, () => res('')); return; }
        try {
          // Anmeldung (Cookies) nur an die eigene Seite – Adressen fremder Server ohne
          const anon = baseDomain(hostOf(u)) !== baseDomain(location.hostname);
          x({ method: 'GET', url: u, timeout: 20000, headers: { Referer: mediaReferrer(u) }, anonymous: anon,
            onload: (r) => res(r.status >= 200 && r.status < 300 ? String(r.responseText || '') : ''),
            onerror: () => res(''), ontimeout: () => res(''), onabort: () => res('') });
        } catch (e) { res(''); }
      });
    }
    // ----- Untertitel der Seite finden (Mediatheken zeichnen sie mit ihrem eigenen Player, ohne <track>) -----
    // Gesucht wird erst, wenn du das Untertitel-Menü öffnest: in den Daten der Seite, in dem, was der Seiten-Player
    // geladen hat (auch seine Konfiguration), und bei der ARD in deren Daten-Schnittstelle. Jede Fundstelle wird
    // geladen und geprüft – nur echte Untertitel landen im Menü.
    const LANG = { de: 'Deutsch', deu: 'Deutsch', ger: 'Deutsch', en: 'English', eng: 'English', fr: 'Français', fra: 'Français', fre: 'Français',
      es: 'Español', spa: 'Español', it: 'Italiano', ita: 'Italiano', tr: 'Türkçe', tur: 'Türkçe', ar: 'العربية', ara: 'العربية', uk: 'Українська', ukr: 'Українська', ru: 'Русский', rus: 'Русский', pl: 'Polski', pol: 'Polski', nl: 'Nederlands' };
    const SUB_EXT = /\.(vtt|webvtt|srt|ttml|dfxp)(\?|#|$)/i;
    const SUB_HINT = /(subtitle|untertitel|caption|webvtt|ebutt|ttml|dfxp|\/ut\/|\/subs?\/|\/cc\/)/i;
    const NOT_SUB = /\.(m3u8|mpd|mp4|m4s|ts|webm|mp3|aac|js|mjs|css|png|jpe?g|gif|webp|svg|ico|woff2?|html?|json)(\?|#|$)/i;
    const subLike = (u) => SUB_EXT.test(u) || (SUB_HINT.test(u) && !NOT_SUB.test(u));
    const pageSubs = { done: false, busy: false, list: [], cache: new Map(), near: new Map(), why: '' };
    function scanText(txt, found) {
      const t = String(txt || '').replace(/\\u002[fF]/g, '/').replace(/\\\//g, '/').replace(/&amp;/g, '&');
      const re = /https?:\/\/[^\s"'<>\\)]+/g;
      let m;
      while ((m = re.exec(t))) {
        const u = m[0].replace(/[.,;]+$/, '');
        if (!subLike(u) || found.has(u)) continue;
        // Sprache aus der Nähe (z. B. "languageCode":"deu") oder aus der Adresse
        const near = t.slice(Math.max(0, m.index - 400), m.index);
        const all = near.match(/"(?:languageCode|language|lang|srclang|locale)"\s*:\s*"[a-zA-Z]{2,3}(?:[-_][a-zA-Z]+)?"/g);
        const lm = all ? /"([a-zA-Z]{2,3})(?:[-_][a-zA-Z]+)?"$/.exec(all[all.length - 1]) : null;   // die letzte davor
        found.set(u, lm ? lm[1].toLowerCase() : urlLang(u));
        // lange Zahlen rundherum (Video-IDs): gehört die Datei zu DIESEM Video? (Facebook: viele Videos auf einer Seite)
        pageSubs.near.set(u, t.slice(Math.max(0, m.index - 1500), m.index + u.length + 1500).match(/\d{12,}/g) || []);
      }
    }
    function urlLang(u) {
      const m = /[\/_.-](deu|ger|de|eng|en|fra|fre|fr|spa|es|ita|it|tur|tr|ara|ar|ukr|uk|rus|ru|pol|pl|nl)(?=[\/_.-]|$)/i.exec(u.split('?')[0]);
      return m ? m[1].toLowerCase() : '';
    }
    function subKey(u) { return u.split('?')[0].replace(/\/(webvtt|ebutt|ttml|vtt)\//i, '/').replace(/\.(vtt|webvtt|xml|ttml|dfxp|srt)$/i, ''); }
    async function findPageSubs() {
      if (pageSubs.done || pageSubs.busy) return;
      pageSubs.busy = true;
      const found = new Map();
      try { scanText(document.documentElement.innerHTML, found); } catch (e) {}
      let res = [];
      try { res = performance.getEntriesByType('resource').map((e) => [e.name, e.initiatorType]); } catch (e) {}
      res.forEach((r) => { if (subLike(r[0]) && !found.has(r[0])) found.set(r[0], urlLang(r[0])); });
      // Konfigurationsdaten, die der Seiten-Player geladen hat, noch einmal lesen (meist aus dem Zwischenspeicher)
      const cfg = res.filter((r) => /^(fetch|xmlhttprequest|other)$/.test(r[1]) && !NOT_SUB.test(r[0].split('?')[0].replace(/\.json$/i, '')) &&
        /(ptmd|mediacollection|media-?collection|player|config|item|video|clip|playlist|metadata|stream|page-gateway|api)/i.test(r[0]) &&
        !/\.(m3u8|mpd|m4s|ts|mp4|vtt)(\?|$)/i.test(r[0]) && !/(analytics|tracking|telemetry|beacon|ads?[./]|doubleclick|google)/i.test(r[0]))
        .map((r) => r[0]).slice(-8);
      // ARD Mediathek: Daten der Sendung über deren Schnittstelle (falls die Seite selbst nichts verrät)
      if (/(^|\.)ardmediathek\.de$/i.test(location.hostname)) {
        const id = location.pathname.split('/').filter(Boolean).pop();
        if (id && id.length > 20) cfg.push('https://api.ardmediathek.de/page-gateway/pages/ard/item/' + encodeURIComponent(id) + '?embedded=false&mcV6=true');   // wie yt-dlp
      }
      for (const u of cfg) { const t = await gmText(u); if (t) scanText(t, found); }
      // laden + prüfen (höchstens 10); gleiche Untertitel in zwei Formaten (ARD: WebVTT + EBU-TT) nur einmal, WebVTT bevorzugt
      const byKey = new Map();
      Array.from(found.entries()).slice(0, 10).forEach(([u, lang]) => {
        const k = subKey(u), o = byKey.get(k);
        if (!o || (/vtt/i.test(u) && !/vtt/i.test(o[0]))) byKey.set(k, [u, lang || (o && o[1]) || '']);
      });
      // Welche gehören zu DIESEM Video? Seine IDs (am Video, im Beitrag, aus dem Netzwerk) stehen nahe bei der Adresse.
      // Und: Untertitel, die deutlich nach dem Ende des Videos noch weiterlaufen, gehören zu einem anderen Video.
      const own = new Set();
      try { videoIdsFor(orig).forEach((x) => own.add(x)); } catch (e) {}
      try { const a = assetFor(orig).asset; if (a) own.add(a); } catch (e) {}
      try { lastNear.forEach((c) => { if (c.asset) own.add(c.asset); }); } catch (e) {}
      const md = M().duration;
      let list = [], longer = 0;
      for (const [u, lang] of byKey.values()) {
        const txt = await gmText(u);
        const c = parseSubs(txt);
        if (c.length < 2) continue;
        const end = fixStart(c).reduce((x, q) => Math.max(x, q[1]), 0);   // (Zählung ab 10:00:00 vorher abziehen)
        if (isFinite(md) && md > 5 && end > md + 3) { longer++; continue; }   // läuft länger als das Video → anderes Video
        pageSubs.cache.set(u, c);
        list.push({ u: u, lang: lang, mine: own.size > 0 && (pageSubs.near.get(u) || []).some((x) => own.has(x)) });
      }
      const mine = list.filter((x) => x.mine);
      if (mine.length) list = mine;   // eindeutig zugeordnete gefunden → nur diese (die übrigen gehören zu anderen Videos)
      st.subsWhy = pageSubs.why = list.length + ' passend' + (mine.length ? ' (über die Video-ID)' : '') + (longer ? ', ' + longer + '× länger als das Video verworfen' : '') +
        (byKey.size - list.length - longer > 0 ? ', ' + (byKey.size - list.length - longer) + '× anderes Video/leer' : '');
      const counts = {};
      pageSubs.list = list.map((x, i) => {
        const name = LANG[x.lang] || (x.lang ? x.lang.toUpperCase() : 'Seite ' + (i + 1));
        counts[name] = (counts[name] || 0) + 1;
        return { label: name + (counts[name] > 1 ? ' ' + counts[name] : ''), sub: 'von der Seite', kind: 'page', src: x.u };
      });
      pageSubs.done = true; pageSubs.busy = false;
    }
    function subSources() {
      const out = [];
      const seen = new Set();
      [orig, vid].forEach((v) => {
        v.querySelectorAll('track[src]').forEach((t) => {
          const k = (t.getAttribute('kind') || 'subtitles').toLowerCase();
          if (k !== 'subtitles' && k !== 'captions') return;
          let u = ''; try { u = new URL(t.getAttribute('src'), location.href).href; } catch (e) { return; }
          if (/^data:/i.test(u) && u.length < 60) return;   // leerer Platzhalter (legt z. B. hls.js an)
          if (seen.has(u)) return; seen.add(u);
          out.push({ label: t.label || (t.srclang || '').toUpperCase() || 'Untertitel', kind: 'url', src: u });
        });
      });
      if (st.hls && st.hls.subtitleTracks && st.hls.subtitleTracks.length) {
        st.hls.subtitleTracks.forEach((t, i) => {
          const label = t.name || (t.lang || '').toUpperCase() || 'Spur ' + (i + 1);
          if (out.some((x) => x.kind === 'hls' && x.label === label && x.lang === t.lang)) return;   // hls.js listet manche doppelt
          out.push({ label: label, kind: 'hls', idx: i, lang: t.lang });
        });
      }
      // schon geladene Spuren des Originals (z. B. vom Player der Seite eingebunden)
      if (!out.length) {
        const tl = orig.textTracks || [];
        for (let i = 0; i < tl.length; i++) {
          const t = tl[i];
          if (t.kind === 'subtitles' || t.kind === 'captions') out.push({ label: t.label || (t.language || '').toUpperCase() || 'Spur ' + (i + 1), kind: 'tt', tt: t });
        }
      }
      pageSubs.list.forEach((x) => { if (!out.some((o) => o.src && subKey(o.src) === subKey(x.src))) out.push(x); });
      if (subs.file) out.push(subs.file);
      return out;
    }
    // Manche Sender zählen ab 10:00:00 (Sendezeit-Code) – liegt die erste Zeile hinter dem Videoende, um die vollen Stunden zurück
    function fixStart(c) {
      const d = M().duration;
      if (!c.length || !isFinite(d) || c[0][0] < d) return c;
      const sh = Math.floor(c[0][0] / 3600) * 3600;
      return sh ? c.map((x) => [x[0] - sh, x[1] - sh, x[2]]) : c;
    }
    function subPick(src) {
      if (src.kind === 'url') {
        toast('Untertitel werden geladen …');
        gmText(src.src).then((txt) => {
          if (vp !== st) return;
          const c = parseSubs(txt);
          if (!c.length) { toast('Diese Untertitel lassen sich nicht laden'); return; }
          subStart(src, fixStart(c)); toast('Untertitel: ' + src.label);
        });
      } else if (src.kind === 'file') {
        subStart(src, src.cues); toast('Untertitel: ' + src.label);
      } else if (src.kind === 'page') {
        const c = pageSubs.cache.get(src.src);
        if (c) { subStart(src, fixStart(c)); toast('Untertitel: ' + src.label); }
      } else if (src.kind === 'tt') {
        subStart(src, null, src.tt);
      } else if (src.kind === 'hls' && st.hls) {
        // hls.js liefert die Zeilen nur an eine eingeschaltete Spur – sie bleibt für den Browser unsichtbar (wir zeichnen)
        try { st.hls.subtitleDisplay = true; st.hls.subtitleTrack = src.idx; } catch (e) {}
        // hls.js legt die Spur als Textspur am Video an – die wird unsichtbar geschaltet und von uns gezeichnet
        let n = 0;
        const find = () => {
          if (vp !== st) return;
          const tl = vid.textTracks || [];
          let t = null;
          for (let i = 0; i < tl.length; i++) if (tl[i].label === src.label || (src.lang && tl[i].language === src.lang)) t = tl[i];
          if (t) { subStart(src, null, t); toast('Untertitel: ' + src.label); }
          else if (++n < 20) setTimeout(find, 250);
          else toast('Diese Untertitel lassen sich nicht laden');
        };
        find();
      }
    }
    function subFile() {
      const inp = document.createElement('input');
      inp.type = 'file'; inp.accept = '.srt,.vtt,.ttml,.dfxp,.xml,.txt,text/vtt,application/x-subrip,application/ttml+xml';
      inp.style.cssText = 'position:fixed;left:-9999px;top:0;opacity:0';
      inp.addEventListener('change', () => {
        const f = inp.files && inp.files[0];
        inp.remove();
        if (!f) return;
        const rd = new FileReader();
        rd.onload = () => {
          const c = parseSubs(rd.result);
          if (!c.length) { toast('Keine Untertitel in dieser Datei gefunden'); return; }
          subs.file = { label: f.name.replace(/\.(srt|vtt|txt|ttml|dfxp|xml)$/i, '').slice(0, 40), kind: 'file', cues: fixStart(c) };
          subPick(subs.file);
        };
        rd.readAsText(f);
      });
      wrap.append(inp);
      inp.click();
    }
    function openSubs(keep) {
      const fmtOff = (o) => (o > 0 ? '+' : '') + o.toFixed(1).replace('.', ',') + ' s';
      if (!pageSubs.done && !pageSubs.busy) {
        findPageSubs().then(() => {   // Ergebnis nachreichen, wenn das Menü noch offen ist
          if (vp === st && menuOpen() && (menu.querySelector('.vmh') || {}).textContent === 'Untertitel') openSubs(true);
        }, () => { pageSubs.busy = false; });
      }
      const items = [{ label: 'Aus', cur: !subs.cur, fn: () => subStop() }];
      subSources().forEach((src) => items.push({ label: src.label, sub: src.kind === 'file' ? 'eigene Datei' : (src.sub || ''), cur: !!subs.cur && subs.cur.label === src.label && subs.cur.kind === src.kind, fn: () => subPick(src) }));
      if (pageSubs.busy) items.push({ label: 'Suche Untertitel der Seite …', sub: '', stay: true, fn: () => {} });
      items.push({ label: 'Datei laden …', sub: '.srt, .vtt oder .ttml vom Handy', fn: subFile });
      const it = (label, sub, fn) => ({ label: label, sub: sub, stay: true, fn: () => { fn(); openSubs(true); } });
      const SIZES = { s: 'klein', m: 'mittel', l: 'groß' };
      items.push(
        it('Größe', SIZES[CONFIG.subSize] || 'mittel', () => { setSetting('subSize', { s: 'm', m: 'l', l: 's' }[CONFIG.subSize] || 'l'); subLayout(); }),
        it('Lage', CONFIG.subHigh ? 'oben im Bild' : 'unten im Bild', () => { setSetting('subHigh', !CONFIG.subHigh); subLayout(); }));
      if (subs.cur) {
        items.push(
          it('Früher zeigen', 'Verschiebung ' + fmtOff(subs.offset), () => { subs.offset = Math.round((subs.offset + 0.5) * 10) / 10; subs.last = '\u0000'; subTick(); }),
          it('Später zeigen', 'Verschiebung ' + fmtOff(subs.offset), () => { subs.offset = Math.round((subs.offset - 0.5) * 10) / 10; subs.last = '\u0000'; subTick(); }));
      }
      openMenu('Untertitel', items, keep === true);
    }

