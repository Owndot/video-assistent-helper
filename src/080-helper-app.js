  // ---------- Helfer-App: echte Helligkeit und Lautstärke des Handys ----------
  // Die App lauscht nur auf dem Handy selbst (127.0.0.1). Ohne App kommt schlicht keine Antwort – dann bleibt alles wie bisher.
  const HELPER = 'http://127.0.0.1:47811';
  // Schlüssel: nach dem Koppeln (Knopf „Verbinden“ in der Helfer-App) der eigene Zufallsschlüssel der App –
  // dauerhaft gespeichert, übersteht auch Updates des Skripts. Bis zum Koppeln der feste Startschlüssel.
  let helperKey = 'evp-1';
  try { if (typeof GM_getValue === 'function') helperKey = GM_getValue('helperKey', '') || 'evp-1'; } catch (e) {}
  // to = Wartezeit in ms (Abholen beim Laden über den Browser wartet bis zu 8 s), body = Daten für POST /push
  async function helper(path, to, body, keyOverride, job) {
    if (job && job.cancelled) return null;
    to = to || 1200;
    const x = CONFIG.systemHelper === false ? null : gmXhr();
    if (!x) return null;
    // Freeze one trusted key per request. Kadri must never fall back to its
    // document cache when persistent storage is missing or fails.
    let key;
    try {
      if (keyOverride !== undefined && keyOverride !== null) key = keyOverride;
      else if (typeof __KADRI__ !== 'undefined' && __KADRI__) key = await KADRI_HELPER_KEY();
      else key = typeof GM_getValue === 'function' ? (GM_getValue('helperKey', '') || 'evp-1') : helperKey;
    } catch (e) { return null; }
    if (typeof key !== 'string' || !key || job && job.cancelled) return null;
    return new Promise((res) => {
      let done = false, request;
      const fin = (v) => { if (!done) { done = true; clearTimeout(timer); if (job) job.aborters.delete(abort); res(v); } };
      const abort = () => { fin(null); try { if (request) request.abort(); } catch (e) {} };
      const timer = setTimeout(abort, to + 400);
      if (job) job.aborters.add(abort);
      try {
        request = x({
          method: body ? 'POST' : 'GET', url: HELPER + path, headers: { 'X-EVP-Key': key }, timeout: to, anonymous: true,
          data: body || undefined,
          onload: (r) => { try { fin(r.status === 200 ? JSON.parse(r.responseText) : null); } catch (e) { fin(null); } },
          onerror: () => fin(null), ontimeout: () => fin(null), onabort: () => fin(null),
        });
        if (done && job && job.cancelled && request) request.abort();
      } catch (e) { fin(null); }
    });
  }
  // ---------- Laden über den Browser (für Server, die nur dem eingeloggten Browser etwas geben) ----------
  // Der Helfer sagt, was er braucht (/pull) – das Skript lädt es mit der Sitzung des Browsers und liefert (/push).
  // Läuft, solange die Seite offen ist (der Player darf zu sein).
  // anon = ohne Cookies: für Adressen fremder Seiten (eine bösartige Playlist darf nicht mit deiner Sitzung woanders abrufen)
  function gmGet(u, off, len, sizeOnly, anon, expectedValidator, expectedTotal = -1, job) {
    return new Promise((res) => {
      if (job && job.cancelled) { res({ st: 499 }); return; }
      const x = gmXhr();
      if (!x) { res({ st: 502 }); return; }
      const ranged = sizeOnly || off >= 0 && len > 0;
      const start = sizeOnly ? 0 : off, count = sizeOnly ? 1 : len;
      if (ranged && (!Number.isSafeInteger(start) || !Number.isSafeInteger(count) || count <= 0 || !Number.isSafeInteger(start + count))) { res({ st: 422 }); return; }
      const headers = { Referer: mediaReferrer(u) };
      if (ranged) headers.Range = 'bytes=' + start + '-' + (start + count - 1);
      if (ranged && !sizeOnly && expectedValidator) headers['If-Range'] = expectedValidator;
      let request, done = false, pendingAbort = false;
      const finish = (value, abort) => {
        if (done) return;
        done = true; clearTimeout(timer);
        if (job) job.aborters.delete(cancel);
        if (abort) { if (request) { try { request.abort(); } catch (e) {} } else pendingAbort = true; }
        res(value);
      };
      const cancel = () => finish({ st: 499 }, true);
      const timer = setTimeout(() => finish({ st: 504 }, true), 60400);
      if (job) job.aborters.add(cancel);
      function parse(r, headOnly) {
        const st = +r.status;
        if (st >= 400) return { st: st };
        if (st !== 200 && st !== 206) return { st: 422 };
        const fields = {};
        String(r.responseHeaders || '').split(/\r?\n/).forEach((line) => {
          const at = line.indexOf(':'); if (at <= 0) return;
          const name = line.slice(0, at).trim().toLowerCase(), value = line.slice(at + 1).trim();
          (fields[name] || (fields[name] = [])).push(value);
        });
        const values = (name) => fields[name] || [];
        const single = (name) => values(name)[0] || '';
        const bad = () => ({ st: 422 });
        const lengths = values('content-length'), transfers = values('transfer-encoding');
        if (lengths.length && transfers.length) return bad();
        if (transfers.length && (transfers.length !== 1 || !/^chunked$/i.test(transfers[0]))) return bad();
        let declared = -1;
        for (const value of lengths) {
          if (!/^\d+$/.test(value) || !Number.isSafeInteger(+value) || declared >= 0 && declared !== +value) return bad();
          declared = +value;
        }
        const encoded = single('content-encoding') && !/^identity$/i.test(single('content-encoding'));
        const etag = single('etag');
        const validator = etag && !/^W\//i.test(etag) ? etag : single('last-modified');
        if (expectedValidator && validator !== expectedValidator) return bad();
        let total = encoded ? -1 : declared, span = -1;
        if (st === 206) {
          const cr = /^bytes (\d+)-(\d+)\/(\d+|\*)$/i.exec(single('content-range'));
          if (!ranged || encoded || values('content-range').length !== 1 || !cr) return bad();
          const a = +cr[1], b = +cr[2]; total = cr[3] === '*' ? -1 : +cr[3];
          if (![a, b, total].every(Number.isSafeInteger) || a !== start || b < a || b >= start + count || total >= 0 && b >= total) return bad();
          span = b - a + 1;
          if (span !== count && (total < 0 || b !== total - 1) || declared >= 0 && declared !== span) return bad();
        }
        if (expectedTotal >= 0 && total >= 0 && total !== expectedTotal) return bad();
        if (headOnly) return { st: st, total: total, validator: validator, body: null };
        const data = r.response;
        if (Object.prototype.toString.call(data) !== '[object ArrayBuffer]') return bad();
        if (!encoded && declared >= 0 && declared !== data.byteLength || span >= 0 && span !== data.byteLength) return bad();
        if (sizeOnly) return { st: st, total: total, validator: validator, body: null };
        if (st === 200) {
          total = data.byteLength;
          if (expectedTotal >= 0 && total !== expectedTotal) return bad();
          if (ranged) {
            if (start >= total) return bad();
            const end = Math.min(start + count, total);
            return { st: 206, total: total, validator: validator, body: start === 0 && end === total ? data : data.slice(start, end) };
          }
        }
        return { st: st, total: total, validator: validator, body: data };
      }
      try {
        request = x({
          method: 'GET', url: u, headers: headers, responseType: 'arraybuffer', timeout: 60000, anonymous: !!anon,
          onreadystatechange: (r) => {
            // A size probe never needs its body, including unknown-size/ignored Range.
            if (!sizeOnly || r.readyState !== 2 || !(r.status === 200 || r.status === 206)) return;
            if (r.status === 206 && !/content-range:/i.test(r.responseHeaders || '')) return;
            finish(parse(r, true), true);
          },
          onload: (r) => { if (!done) finish(parse(r, false)); },
          onerror: () => finish({ st: 502 }, true), ontimeout: () => finish({ st: 504 }, true), onabort: () => finish({ st: 499 }),
        });
        if (pendingAbort && request) request.abort();
      } catch (e) { finish({ st: 502 }, true); }
    });
  }
  const proxyJobs = new Map();
  let proxyActive = 0;
  const proxyWaiting = [];
  function pumpProxy() {
    while (proxyActive < 2 && proxyWaiting.length) {
      const entry = proxyWaiting.shift();
      if (entry.job.cancelled) { entry.resolve(false); continue; }
      proxyActive++; entry.resolve(true);
    }
  }
  function cancelProxyJob(job) {
    if (job.cancelled) return;
    job.cancelled = true;
    for (let i = proxyWaiting.length - 1; i >= 0; i--) {
      if (proxyWaiting[i].job === job) proxyWaiting.splice(i, 1)[0].resolve(false);
    }
    for (const abort of [...job.aborters]) { try { abort(); } catch (e) {} }
    pumpProxy();
  }
  async function proxyTransfer(job, run) {
    const acquired = await new Promise((resolve) => {
      if (job.cancelled) { resolve(false); return; }
      proxyWaiting.push({ job, resolve }); pumpProxy();
    });
    if (!acquired) return;
    try { if (!job.cancelled) await run(); }
    catch (e) { cancelProxyJob(job); }
    finally { proxyActive--; pumpProxy(); }
  }
  function proxyPause(job, ms) {
    return new Promise((resolve) => {
      let timer;
      const wake = () => { clearTimeout(timer); job.aborters.delete(wake); resolve(); };
      if (job.cancelled) { resolve(); return; }
      job.aborters.add(wake); timer = setTimeout(wake, ms);
    });
  }
  function proxyAlive(reply) {
    if (!reply) return null;
    return reply.ok === true && typeof reply.state === 'string' &&
      !/^(fertig|abgebrochen|Fehler\b)/i.test(reply.state);
  }
  // allow = domains whose browser session may be used, unchanged from the existing policy.
  async function proxyLoop(job, allow) {
    if (proxyJobs.has(job)) return;
    const state = { cancelled: false, aborters: new Set() };
    const ok = (u) => { const d = baseDomain(hostOf(u)); return !!d && (allow || []).indexOf(d) >= 0; };
    proxyJobs.set(job, state);
    const transfers = new Map();
    try {
      let idle = 0;
      while (!state.cancelled) {
        const r = await helper('/pull?job=' + job, 12000, null, null, state);
        if (!r) { if (++idle > 5) break; await proxyPause(state, 1000); continue; }
        idle = 0;
        if (r.done) break;
        const requests = r.reqs || [];
        if (!requests.length) continue; // /pull already provides the idle wait.
        // Keep this pulled batch while status is temporarily unknown; never re-pull it.
        let initialAlive = null;
        for (let attempt = 0; attempt < 3 && !state.cancelled; attempt++) {
          initialAlive = proxyAlive(await helper('/dlstate?id=' + job, 1200, null, null, state));
          if (initialAlive !== null) break; // Confirmed active or terminal, not a transport failure.
          if (attempt < 2) await proxyPause(state, 2000);
        }
        if (initialAlive !== true) { cancelProxyJob(state); break; }
        // Poll only while a batch owns active or queued work, one request at a time.
        let batchDone = false, stopWatch, missedStatus = 0;
        const watcher = (async () => {
          while (!batchDone && !state.cancelled) {
            await new Promise((resolve) => {
              let timer;
              stopWatch = () => { clearTimeout(timer); state.aborters.delete(stopWatch); resolve(); };
              state.aborters.add(stopWatch); timer = setTimeout(stopWatch, 2000);
            });
            if (batchDone || state.cancelled) break;
            const reply = await helper('/dlstate?id=' + job, 1200, null, null, state);
            const alive = proxyAlive(reply);
            if (alive === false || alive === null && ++missedStatus >= 3) cancelProxyJob(state);
            if (alive === true) missedStatus = 0;
          }
        })().catch(() => cancelProxyJob(state));
        try {
          await Promise.all(requests.map((q) => proxyTransfer(state, async () => {
            let g;
            if (!/^https?:\/\//i.test(q.u)) g = { st: 400 };
            else {
              const expected = transfers.get(q.u);
              g = await gmGet(q.u, q.o, q.l, q.s, !ok(q.u), expected && expected.validator, expected ? expected.total : -1, state);
              if (q.s && g.st < 400) transfers.set(q.u, { validator: g.validator, total: g.total });
            }
            if (state.cancelled) return;
            const st = g.st || 502, body = g.body && st < 400 ? g.body : new ArrayBuffer(0);
            const reply = await helper('/push?job=' + job + '&id=' + q.id + '&st=' + st + '&total=' + (g.total == null ? -1 : g.total), 60000, body, null, state);
            if (!reply || reply.ok !== true || st >= 400 && st < 500) cancelProxyJob(state);
          })));
        } finally { batchDone = true; if (stopWatch) stopWatch(); await watcher; }
      }
    } catch (e) { cancelProxyJob(state); }
    finally { cancelProxyJob(state); proxyJobs.delete(job); }
  }
  // Koppel-Seite der Helfer-App (http://127.0.0.1:47811/pair#SCHLÜSSEL): Schlüssel prüfen und dauerhaft merken.
  // Der Schlüssel steht nur im #-Teil – den sieht weder der Server noch eine andere App.
  if (location.origin === HELPER && location.pathname === '/pair') {
    const m = /^#([0-9a-f]{32})$/.exec(location.hash);
    if (m) {
      try { history.replaceState(null, '', '/pair'); } catch (e) {}
      const say = (t, okc) => {
        const show = () => { const el = document.getElementById('evp-pair'); if (el) { el.textContent = tr(t); el.style.color = okc ? '#4cd964' : '#ff9f0a'; } };
        if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', show); else show();
      };
      helper('/ping', 2500, null, m[1]).then((r) => {
        if (r && r.ok) {
          try { GM_setValue('helperKey', m[1]); } catch (e) {}
          helperKey = m[1];
          say('✓ Verbunden. Ab jetzt kann nur noch dieses Skript den Helper benutzen. Du kannst diese Seite schließen.', true);
        } else say('Verbinden hat nicht geklappt – läuft der Helper? In der App nochmal auf „Verbinden“ tippen.', false);
      });
    }
  }
  // Beim Wischen nicht jede Bewegung schicken: immer nur den neuesten Wert, sobald die vorige Antwort da ist
  const helperQ = {};
  function helperSend(kind, v) {
    const q = helperQ[kind] || (helperQ[kind] = { busy: false, want: null });
    q.want = v;
    if (!q.busy) helperPump(kind);
  }
  function helperPump(kind) {
    const q = helperQ[kind];
    if (q.want === null) { q.busy = false; return; }
    const v = q.want;
    q.want = null;
    q.busy = true;
    helper('/' + kind + '?v=' + v.toFixed(3)).then(() => setTimeout(() => helperPump(kind), 25));
  }
  const helperBusy = (kind) => !!(helperQ[kind] && helperQ[kind].busy);

  function finalUrlGM(u, xhr) {
    return new Promise((resolve) => {
      let done = false, req = null;
      const desc = (st, f) => 'HTTP ' + st + (f && f !== u ? ' → ' + hostOf(f) : ' (keine Weiterleitung)');
      const finish = (f, info) => {
        if (done) return;
        done = true;
        clearTimeout(timer);
        lastResolve = info;
        try { req && req.abort && req.abort(); } catch (e) {}
        resolve(f || u);
      };
      const timer = setTimeout(() => finish(u, 'Zeitüberschreitung'), 4000);
      try {
        req = xhr({
          method: 'GET',
          kadriPurpose: 'player-resolve',
          url: u,
          headers: { Range: 'bytes=0-1', Referer: mediaReferrer(u) },   // wie der Web-Player der Seite
          timeout: 4000,
          onreadystatechange: (r) => { if (r && r.readyState >= 2 && r.finalUrl) finish(r.finalUrl, desc(r.status, r.finalUrl)); },
          onload: (r) => finish((r && r.finalUrl) || u, desc(r && r.status, r && r.finalUrl)),
          onerror: () => finish(u, 'Anfrage abgelehnt'),
          ontimeout: () => finish(u, 'Zeitüberschreitung'),
        });
      } catch (e) { finish(u, 'nicht möglich (' + ((e && e.name) || 'Fehler') + ')'); }
    });
  }

  // Datei mit den Angaben der Seite anfragen (nur 2 Bytes) und die Endadresse nach Weiterleitungen nehmen
  async function finalUrl(u) {
    const xhr = gmXhr();
    if (xhr) return finalUrlGM(u, xhr);
    const ctl = typeof AbortController !== 'undefined' ? new AbortController() : null;
    const timer = setTimeout(() => { try { ctl && ctl.abort(); } catch (e) {} }, 2500);
    try {
      const res = await fetch(u, {
        headers: { Range: 'bytes=0-1' }, referrerPolicy: 'strict-origin-when-cross-origin',
        credentials: 'include',
        cache: 'no-store',
        signal: ctl ? ctl.signal : undefined,
      });
      clearTimeout(timer);
      const f = res.url || u;
      lastResolve = 'HTTP ' + res.status + (f !== u ? ' → ' + hostOf(f) : ' (keine Weiterleitung)');
      try { ctl && ctl.abort(); } catch (e) {}
      return f;
    } catch (e) {
      clearTimeout(timer);
      lastResolve = 'nicht möglich (' + ((e && e.name) || 'Fehler') + ')';
      return u;
    }
  }
