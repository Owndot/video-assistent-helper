    // ----- Bedienelemente -----
    // Play nach dem Ende: Sprung an den Anfang ist dann gewollt (nicht als Schleife werten)
    function togglePlay() { const m = M(); if (m.paused) { if (st.heldAtEnd(m)) { try { m.currentTime = 0; } catch (e) {} }   // am Ende angehalten: von vorn
        st.endHold = false; st.userSeekAt = Date.now(); m.play().catch(() => {}); } else { st.userPauseAt = Date.now(); m.pause(); } showControls(); }
    function seekBy(d) {
      const m = M();
      const max = isFinite(m.duration) ? m.duration - 0.5 : m.currentTime + d;
      st.userSeekAt = Date.now();
      try { m.currentTime = clamp(m.currentTime + d, 0, max); } catch (e) {}
      updateUI();
    }
    const SPEEDS = [1, 1.25, 1.5, 2, 0.5, 0.75];
    const AUTO_OVER = 0.14;   // so weit unter 0 % weiterwischen = Auto (dazwischen bleibt es ganz dunkel)
    // Nur wenn ausdrücklich gewünscht (Einstellung autoRotate): Querformat-Videos automatisch drehen
    function autoOrient(w, hgt) {
      if (!CONFIG.autoRotate || !w || !hgt || !isFs()) return;
      st.orient = w > hgt ? 'landscape' : 'portrait';
      lockO(st.orient);
    }
    // Eingebettetes Fenster: Vollbild und Drehen erledigt die Hauptseite für uns (siehe "Eingebettete Fenster")
    function isFs() { return !!(document.fullscreenElement || document.webkitFullscreenElement || (st.framed && st.rfs)); }
    function lockO(o) {
      if (st.framed) { fpost(window.parent, { t: 'orient', o: o || '' }); return; }
      try {
        if (o) screen.orientation.lock(o).catch(() => {});
        else if (screen.orientation && screen.orientation.unlock) screen.orientation.unlock();
      } catch (e) {}
    }
    // Echter Vollbildmodus (dabei zeigt der Browser seinen Hinweis "Zum Beenden…" – nur auf Wunsch)
    function enterFs() {
      if (st.framed) {
        return new Promise((res) => {
          const done = () => { if (st.fsWait === done) st.fsWait = null; clearTimeout(tm); st.wasFs = isFs(); res(); };
          st.fsWait = done;
          fpost(window.parent, { t: 'take', fs: true });
          // Hauptseite antwortet nicht (Skript dort aus)? Dann wie bisher selbst versuchen – der Tipp gilt noch
          const tm = setTimeout(() => {
            if (st.fsWait !== done) return;
            st.fsWait = null;
            if (!fb.ack) { st.framed = false; enterFs().then(res); } else { st.wasFs = isFs(); res(); }
          }, 700);
        });
      }
      try {
        const rq = wrap.requestFullscreen || wrap.webkitRequestFullscreen;
        const pr = rq && rq.call(wrap, { navigationUI: 'hide' });
        if (pr && pr.then) return pr.then(() => { st.wasFs = true; }, () => { st.wasFs = false; });
        if (rq) st.wasFs = true;
      } catch (e) {}
      return Promise.resolve();
    }
    function exitFs() {
      lockO('');
      if (st.framed) fpost(window.parent, { t: 'exitfs' });
      try { if (document.fullscreenElement && document.exitFullscreen) document.exitFullscreen().catch(() => {}); } catch (e) {}
      st.orient = '';
    }
    const stop = (e) => { e.stopPropagation(); };
    // Vollbild verloren (z. B. durch Drehen)? Beim nächsten Tipp zurückholen – nur mit Tipp erlaubt der Browser das
    function regainFs() {
      if (!CONFIG.startFullscreen || isFs() || vp !== st) return;
      enterFs().then(() => {
        if (!isFs()) return;
        st.fsStart = true;
        st.fsLost = false;
        if (st.orient) lockO(st.orient);
      });
    }
    // (Bild-in-Bild holt das Vollbild NICHT zurück – das würde den Tipp "verbrauchen", den Bild-in-Bild braucht)
    const on = (el, fn) => { if (el) el.addEventListener('click', (e) => { stop(e); if (el !== bClose && el !== bRot && el !== bPip && el !== bDl) regainFs(); fn(); showControls(); }); };
    on(bClose, () => closePlayer());
    on(bPlay, togglePlay);
    const btnSkip = (dir) => {
      const now = Date.now();
      seekBy(dir * SKIP());
      if (st.skipRun && st.skipRun.zone === dir && now < st.skipRun.until) st.skipRun.total += SKIP();
      else st.skipRun = { zone: dir, total: SKIP() };
      st.skipRun.until = now + 650;
      showSkipFx(dir, st.skipRun.total);
    };
    on(bBack, () => btnSkip(-1));
    on(bFwd, () => btnSkip(1));
    const spLabel = (v) => String(v).replace('.', UI_EN() ? '.' : ',') + '×';
    const setSpeed = (v) => { st.speed = v; M().playbackRate = v; bSpeed.textContent = spLabel(v); };
    on(bSpeed, () => setSpeed(SPEEDS[(SPEEDS.indexOf(st.speed) + 1) % SPEEDS.length]));   // unsichtbar – nur für alte Wege
    // Tempo im ⋮-Menü: Liste zum Antippen (wie YouTube), langsam → schnell
    const openSpeed = () => openMenu('Geschwindigkeit', SPEEDS.slice().sort((a, b) => a - b).map((v) => ({
      label: v === 1 ? spLabel(1) + ' · Normal' : spLabel(v), cur: v === st.speed, fn: () => { setSpeed(v); toast(spLabel(v)); },
    })), false, openMore);
    // Bild-in-Bild: kleines Fenster über anderen Apps. Dabei endet das Vollbild – das darf den Player NICHT schließen.
    const pipFallback = () => {
      // Manche Android-Browser kennen nur das automatische Bild-in-Bild: Video im Vollbild + zum Startbildschirm wischen
      const hint = () => toast('Zum Startbildschirm wischen – das Video läuft im kleinen Fenster weiter (Bild-in-Bild für den Browser in den Android-Einstellungen erlauben)');
      if (isFs()) { hint(); return; }
      enterFs().then(() => { if (isFs()) { st.fsStart = true; hint(); } else toast('Bild-in-Bild ist hier nicht möglich'); });
    };
    // Bild-in-Bild nur, wo es zuverlässig läuft: NICHT beim Spiegeln und nicht auf Facebook/Instagram/Threads –
    // dort hält die Seite ihr Video im Hintergrund an, das kleine Fenster stünde still (18.6). Dann gibt es weder den
    // Knopf noch das automatische kleine Fenster beim Wischen zum Startbildschirm (auch nicht für das Seitenvideo).
    // (Kadri: im EIGENEN Player auch auf Facebook/Instagram – die Seite kann ihn dort nicht anhalten)
    st.pipOk = !!bPip && (!META || IS_KADRI);
    function setPipAllowed(ok) {
      ok = !!ok && !!bPip && (!META || IS_KADRI);
      st.pipOk = ok;
      try { vid.disablePictureInPicture = !ok; } catch (e) {}
      if (bPip) bPip.style.display = ok ? '' : 'none';
      if (ok) return;
      if (st.origNoPip === undefined) {
        st.origNoPip = !!orig.disablePictureInPicture;
        st.cleanup.push(() => { try { orig.disablePictureInPicture = st.origNoPip; } catch (e) {} });
      }
      try { orig.disablePictureInPicture = true; } catch (e) {}
      if (pipIsOurs(vid)) { try { document.exitPictureInPicture().catch(() => {}); } catch (e) {} }
    }
    st.setPipAllowed = setPipAllowed;
    on(bPip, () => {
      if (!st.pipOk) return;
      if (pipIsOurs(vid)) { document.exitPictureInPicture().catch(() => {}); return; }
      st.pipAt = Date.now();
      let pr;
      try { vid.disablePictureInPicture = false; pr = vid.requestPictureInPicture(); } catch (e) { pr = Promise.reject(e); }
      Promise.resolve(pr).then(() => { st.pipAt = Date.now(); }, () => { st.pipAt = 0; pipFallback(); });
    });
    const onPipEnter = () => { st.pipAt = Date.now(); st.fsStart = false; };
    const onPipLeave = () => { st.pipAt = Date.now(); };
    vid.addEventListener('enterpictureinpicture', onPipEnter);
    vid.addEventListener('leavepictureinpicture', onPipLeave);
    // Herunterladen: Teilen-Menü öffnen (z. B. YTDLnis) – der Player bleibt offen
    // Läuft eine echte Videodatei (MP4/WebM, kein Stream)? Dann lässt sie sich direkt speichern.
    const directUrl = () => (st.mode === 'own' && !st.hls && st.cur && /^https?:/i.test(st.cur) && !PLAYLIST_RE.test(st.cur) && !/\.mpd(\?|#|$)/i.test(st.cur)) ? st.cur : '';
    // Download und Teilen nur zeigen, wenn sie für DIESES Video auch funktionieren:
    // Download = Datei zum Speichern oder ein Link genau dieses Videos für YTDLnis (nicht die Feed-Adresse)
    // Teilen   = eine Adresse, die eine andere App abspielen kann
    // Was der Helfer-App zum Speichern übergeben werden kann: die laufende Datei, ein HLS- oder DASH-Stream (kein blob:)
    const kindOf = (u) => /\.mpd(\?|#|$)/i.test(u) ? 'dash' : /\.m3u8(\?|#|$)/i.test(u) ? 'hls' : 'file';
    const helperSrc = () => {
      if (st.mode === 'own' && st.cur && /^https?:/i.test(st.cur)) return { u: st.cur, k: st.hls ? 'hls' : kindOf(st.cur) };
      if (st.igUrl) return { u: st.igUrl, k: 'file' };
      if (st.ttUrl) return { u: st.ttUrl, k: 'file' };
      // Bild kommt gespiegelt vom Player der Seite (z. B. DASH, den wir nicht selbst abspielen): gefundene Stream-Adresse
      const s2 = (st.sources || []).find((u) => /^https?:/i.test(u) && PLAYLIST_RE.test(u));
      if (s2 && !FB_MIRROR) return { u: s2, k: kindOf(s2) };
      return null;
    };
    const helperCanDl = () => { const h = helperSrc(); return !!(st.sys && st.sys.dl && h && (h.k !== 'dash' || st.sys.dash)); };
    // Download und Teilen nur zeigen, wenn sie für DIESES Video auch funktionieren:
    // Download = Datei zum Speichern, ein Stream für den Helfer oder ein Link genau dieses Videos für YTDLnis
    // Teilen   = eine Adresse, die eine andere App abspielen kann
    st.refreshDl = () => {
      let link = '';
      try { link = SHOW_DOWNLOAD ? postUrlFor(orig) : ''; } catch (e) {}
      bDl.hidden = !(directUrl() || link || helperCanDl());
      if (bExt) bExt.hidden = !((st.mode === 'own' && st.cur) || st.igUrl || st.ttUrl);
    };
    // Helfer-App speichert: Datei über Androids Download-Manager, Stream Stück für Stück → MP4 (mit der Seite als Referer).
    // Weist der Server den Helfer ab (401/403 – will die Sitzung des Browsers), lädt das Skript automatisch über den Browser.
    // via = 'browser': gleich über den Browser laden
    function helperDownload(src, via) {
      const send = (u) => {
        let ck = '';
        try { if (baseDomain(hostOf(u)) === baseDomain(location.hostname)) ck = document.cookie || ''; } catch (e) {}   // nur Cookies der eigenen Seite
        const q = (proxy) => '/download?u=' + encodeURIComponent(u) + '&ref=' + encodeURIComponent(location.href) +
          '&ua=' + encodeURIComponent(navigator.userAgent) + '&t=' + encodeURIComponent((pageTitle() || 'Video').slice(0, 100)) +
          '&k=' + src.k + (ck ? '&c=' + encodeURIComponent(ck) : '') + (proxy ? '&p=1' : '');
        const viaBrowser = (why) => {
          if (!(st.sys && st.sys.proxy)) { toast(why || 'Der Server lehnt den Helper ab'); return; }
          helper(q(true)).then((r) => {
            if (!r || !r.ok) { toast('Helper konnte den Download nicht starten'); return; }
            toast((why ? why + ' – ' : '') + 'lädt über den Browser, Seite offen lassen');
            proxyLoop(r.id, [baseDomain(location.hostname), baseDomain(hostOf(u))]);
          });
        };
        if (via === 'browser') { viaBrowser(''); return; }
        helper(q(false)).then((r) => {
          if (!r || !r.ok) { toast('Helper konnte den Download nicht starten' + (r && r.error ? ' (' + r.error + ')' : '')); return; }
          toast(src.k === 'file' ? 'Download läuft – siehe Benachrichtigung' : 'Helper lädt den Stream – Fortschritt in der Benachrichtigung');
          // die ersten Sekunden beobachten: verlangt der Server die Browser-Sitzung? Dann von selbst den anderen Weg
          let n = 0;
          const iv = setInterval(() => {
            if (++n > 18) { clearInterval(iv); return; }
            helper('/dlstate?id=' + r.id).then((s2) => {
              const t = s2 && s2.state || '';
              if (/^Fehler: HTTP 40[13]/.test(t)) { clearInterval(iv); viaBrowser('Server verlangt die Browser-Sitzung'); }
              else if (/^(fertig|läuft|\d+\/\d+|zusammensetzen)/.test(t) && n > 6) clearInterval(iv);
              else if (/^Fehler/.test(t)) clearInterval(iv);
            });
          }, 1500);
        });
      };
      // geschützte Adressen der Seite (/get_file/…) leiten nur MIT Browsersitzung weiter: Endadresse vorher holen
      if (src.k === 'file' && needsResolve(src.u)) {
        if (st.extUrl && st.extFor === src.u) { send(st.extUrl); return; }
        toast('Bereite Download vor …');
        finalUrl(src.u).then(send, () => send(src.u));
        return;
      }
      send(src.u);
    }
    function browserSave(d) {
      toast('Download wird vorbereitet …');
      browserDownload(d).then((ok) => {
        if (ok) { toast('Download läuft im Browser – du kannst die Seite verlassen'); return; }
        // Der Server gibt die Datei nicht als Download frei: alle Wege zur Auswahl
        dlMenu(true);
      });
    }
    // Alle Wege zum Speichern (langes Drücken auf ⬇, oder wenn der Browser nicht darf)
    // An YTDLnis / eine andere App übergeben: Der Browser verliert dabei das Vollbild (und holt es ohne Berührung nicht
    // zurück). Darum: Player hält an, und kommst du zurück, ist er zu – die Seite steht an deiner Stelle (18.7).
    const dlOut = (f) => {
      st.dlAt = Date.now(); st.dlCancel = false;
      const r = f();
      // Teilen-Menü abgebrochen? Dann bleibt der Player offen
      if (r && r.then) r.then((ok) => { if (!ok) { st.dlCancel = true; st.dlAt = 0; } });
    };
    function dlMenu(browserFailed) {
      const d = directUrl(), hs = helperSrc();
      let link = '';
      try { link = SHOW_DOWNLOAD ? postUrlFor(orig) : ''; } catch (e) {}
      const items = [];
      if (hs && st.sys && st.sys.dl) items.push({ label: 'Mit dem Helper', sub: hs.k === 'file' ? 'Download-Manager' : 'Stream → MP4', fn: () => helperDownload(hs) });
      if (hs && st.sys && st.sys.proxy) items.push({ label: 'Über den Browser laden', sub: 'für geschützte Seiten · Seite offen lassen', fn: () => helperDownload(hs, 'browser') });
      if (link) items.push({ label: 'Mit YTDLnis', sub: 'direkt öffnen', fn: () => dlOut(() => toYtdlnis(link)) });
      if (d && !browserFailed) items.push({ label: 'Im Browser speichern', sub: 'Download des Browsers', fn: () => browserSave(d) });
      if (d) {
        items.push({ label: 'Im Browser öffnen', sub: 'dort ⋮ → Herunterladen', fn: () => {
          const w = window.open(d, '_blank');
          if (w) { try { w.opener = null; } catch (e) {} } else toast('Neuer Tab wurde blockiert – Pop-ups für diese Seite erlauben');
        } });
        items.push({ label: 'Hier speichern', sub: 'Player bis zum Ende offen lassen', fn: () => directDownload(d, () => toast('Die Seite sperrt den Download')) });
      }
      if (link || d) items.push({ label: 'Teilen …', sub: 'an eine andere App', fn: () => dlOut(() => shareUrl(link || d)) });
      if (!items.length) { toast(hs && hs.k === 'hls' ? 'Für Streams wird die Helper-App gebraucht' : 'Für dieses Video gibt es keine Datei zum Herunterladen'); return; }
      openMenu('Herunterladen', items);
    }
    // Tipp: der beste Weg von selbst
    //  1. Seiten, die yt-dlp kennt → YTDLnis (beste Qualität mit Ton)
    //  2. Helfer-App läuft → sie speichert Datei oder Stream
    //  3. Videodatei → Download des Browsers
    //  4. sonst YTDLnis mit dem Link · ohne alles: Hinweis
    on(bDl, () => {
      const d = directUrl(), hs = helperSrc();
      let link = '';
      try { link = SHOW_DOWNLOAD ? postUrlFor(orig) : ''; } catch (e) {}
      if (link && onSite(CONFIG.ytdlpSites)) { if (CONFIG.downloader === 'share') dlOut(() => shareUrl(link)); else dlOut(() => toYtdlnis(link)); return; }
      if (hs && st.sys && st.sys.dl) { helperDownload(hs); return; }
      if (d) { browserSave(d); return; }
      if (link) { if (CONFIG.downloader === 'share') dlOut(() => shareUrl(link)); else dlOut(() => toYtdlnis(link)); return; }
      dlMenu();
    });
    bDl.addEventListener('contextmenu', (e) => { e.preventDefault(); e.stopPropagation(); dlMenu(); });
    // Teilen: der Link zum Video – Beitrag (Instagram/Facebook/TikTok …), sonst die Seite. In einem eingebetteten
    // Fenster fragt das Skript die Hauptseite nach ihrer Adresse (nur für das Fenster, das gerade den Player zeigt).
    on(bShare, () => {
      const title = pageTitle();
      if (IN_FRAME) {
        let done = false;
        const own = () => { if (done) return; done = true; shareLinkTo(location.href.split('#')[0], title); };
        fb.onPageUrl = (u, t) => { if (done) return; done = true; fb.onPageUrl = null; shareLinkTo(u || location.href.split('#')[0], t || title); };
        fpost(window.parent, { t: 'askurl' });
        setTimeout(own, 500);
        return;
      }
      let link = '';
      try { link = postUrlFor(orig); } catch (e) {}
      shareLinkTo(link || location.href.split('#')[0], title);
    });
    on(bExt, () => {
      const t = M().currentTime;
      const go = (u) => { closePlayer(); try { orig.currentTime = t; } catch (e) {} openExternal(u, orig, CONFIG.player); };
      const u = st.cur || (resolve(orig) || {}).best;
      if (u) {
        // Geschützte Dateien der Seite selbst (z. B. /get_file/…): Die Adresse leitet nur MIT Browsersitzung zur echten
        // Datei weiter – VLC hat die nicht. Darum die Weiterleitung hier auflösen und die Endadresse übergeben.
        if (needsResolve(u)) {
          if (st.extUrl && st.extFor === u) { go(st.extUrl); return; }
          toast('Bereite Video vor …');
          finalUrl(u).then((f) => { if (vp === st) go(f); }, () => { if (vp === st) go(u); });
          return;
        }
        go(u);
        return;
      }
      // Instagram (Bild + Ton getrennt): fertige MP4 mit Ton nehmen – meist schon beim Öffnen im Hintergrund geholt
      if (IG) {
        if (st.igUrl) { go(st.igUrl); return; }
        toast('Suche die Videodatei …');
        igVideoUrl(orig).then((x) => { if (vp !== st) return; if (x) go(x); else shareLink('Keine Videodatei mit Ton gefunden (' + igLog + ')'); });
        return;
      }
      if (TT) {
        if (st.ttUrl) { go(st.ttUrl); return; }
        toast('Suche die Videodatei …');
        ttVideoUrl(orig).then((x) => { if (vp !== st) return; if (x) go(x); else shareLink('Keine Videodatei gefunden (' + ttLog + ')'); });
        return;
      }
      shareLink('Für dieses Video gibt es keine Datei-Adresse');
    });
    // Weiterleitung schon beim Laden im Hintergrund auflösen (2 Bytes), damit "In App öffnen" sofort klappt
    st.prefetchExt = () => {
      const u = st.mode === 'own' ? st.cur : '';
      if (!u || st.extFor === u || !needsResolve(u)) return;
      st.extFor = u; st.extUrl = '';
      finalUrl(u).then((f) => { if (st.extFor === u) st.extUrl = f; }, () => {});
    };
    // Keine Datei zum Übergeben: wenigstens den Link GENAU dieses Videos teilen (z. B. an NewPipe, YTDLnis …)
    function shareLink(why) {
      const link = postUrlFor(orig);
      if (!link) { toast(why); return; }
      if (navigator.share) {
        toast(why + ' – Link wird geteilt');
        navigator.share({ url: link }).catch(() => {});
      } else copyText(link, why + ' – Link kopiert');
    }
    if (TT) ttVideoUrl(orig).then((x) => { if (x) { st.ttUrl = x; st.refreshDl(); } }, () => {});
    // Instagram: MP4 mit Ton schon vorab holen, damit "In App öffnen" sofort klappt (nur eine kleine Datenabfrage)
    if (IG) igVideoUrl(orig).then((x) => { if (x) { st.igUrl = x; st.refreshDl(); } }, () => {});
    st.refreshDl();
