    // ----- Menüs: Glas-Blatt von unten -----
    // head: Überschrift (leer = keine) · back: Untermenü – Pfeil ‹ (und die Zurück-Taste) führt dorthin zurück
    function openMenu(head, items, keepScroll, back) {
      const old = keepScroll && menu.classList.contains('on') ? menu.querySelector('.vmlist') : null;
      const oldTop = old ? old.scrollTop : 0;
      menu.textContent = '';
      st.menuBack = back || null;
      const list = h('div', { class: 'vmlist' });
      if (head || back) {
        const bk = back ? ib(P.back, 'Zurück', 'vmb') : null;
        if (bk) bk.addEventListener('click', (e) => { stop(e); back(); });
        menu.append(h('div', { class: 'vmh' + (back ? ' bk' : '') }, bk, head || ''));   // (h übersetzt die Texte)
      }
      menu.append(list);
      items.forEach((it) => {
        const b = h('button', { class: 'vmi' + (it.cur ? ' cur' : ''), role: 'menuitemradio', 'aria-checked': it.cur ? 'true' : 'false' },
          String(it.label), it.sub ? h('small', null, String(it.sub)) : null);
        b.addEventListener('click', (e) => { stop(e); regainFs(); if (!it.stay) closeMenu(); it.fn(); });
        list.append(b);
      });
      wrap.classList.add('menu');
      menu.classList.add('on');
      const edges = () => {
        list.classList.toggle('mt', list.scrollTop > 1);
        list.classList.toggle('mb', list.scrollTop + list.clientHeight < list.scrollHeight - 1);
      };
      list.addEventListener('scroll', edges, { passive: true });
      if (oldTop) list.scrollTop = oldTop;
      requestAnimationFrame(edges);
    }
    function closeMenu() { menu.classList.remove('on'); wrap.classList.remove('menu'); st.menuBack = null; scheduleHide(); }
    const menuOpen = () => menu.classList.contains('on');
    // Zurück-Taste bei offenem Menü: eine Ebene zurück (Untermenü → ⋮) oder Menü zu – nie gleich den Player schließen
    const menuStepBack = () => { if (st.menuBack) st.menuBack(); else closeMenu(); };
    st.closeMenu = closeMenu;

    // ----- Bildformat & Zoom -----
    // Jeder Tipp schaltet weiter (wie bei Samsung): Original → Füllen → Strecken → Original. Gezoomt: erst zurück auf 1×.
    const FITS = [{ k: 'contain', label: 'Original' }, { k: 'cover', label: 'Füllen' }, { k: 'fill', label: 'Strecken' }];
    on(bFit, () => {
      let i = FITS.findIndex((a) => a.k === st.aspect);
      if (st.zoom > 1.001 && st.aspect === 'contain') i = FITS.length - 1;   // aus dem Zoom: zurück zu Original
      const a = FITS[(i + 1) % FITS.length];
      st.aspect = a.k; st.zoom = 1; st.panX = st.panY = 0; st.tapZoomed = false;
      zoomTo(1, 0, 0);
      bFit.classList.toggle('on', a.k !== 'contain');
      toast(a.label);
    });

    // ----- Qualität -----
    function qualityItems() {
      const items = [];
      const hls = st.hls;
      if (hls && hls.levels && hls.levels.length > 1) {
        items.push({ label: 'Automatisch', sub: 'passt sich der Verbindung an', cur: st.qualPinned === null,
          fn: () => { hls.currentLevel = -1; st.qualPinned = null; refreshQual(); } });
        hls.levels.map((l, i) => ({ l: l, i: i }))
          .sort((a, b) => (b.l.height || 0) - (a.l.height || 0) || (b.l.bitrate || 0) - (a.l.bitrate || 0))
          .forEach((x) => {
            const name = x.l.height ? x.l.height + 'p' : 'Stufe ' + (x.i + 1);
            items.push({ label: name, sub: x.l.bitrate ? (x.l.bitrate / 1e6).toFixed(1).replace('.', ',') + ' Mbit/s' : '', cur: st.qualPinned === x.i,
              fn: () => { hls.currentLevel = x.i; st.qualPinned = x.i; refreshQual(); } });
          });
        return items;
      }
      // Einzeldateien in mehreren Qualitäten (Facebook, Pornhub …)
      const files = st.sources.filter((u) => !PLAYLIST_RE.test(u) && st.srcRes[u]);
      if (files.length > 1 && st.mode === 'own') {
        files.slice().sort((a, b) => st.srcRes[b] - st.srcRes[a]).forEach((u) => items.push({
          label: st.srcRes[u] + 'p', sub: u === st.cur ? 'läuft gerade' : '', cur: u === st.cur, fn: () => switchSource(u),
        }));
      }
      return items;
    }
    function refreshQual() {
      const items = qualityItems();
      bQual.hidden = items.length < 2;
      let lab = 'Auto';
      const hls = st.hls;
      if (hls && hls.levels && hls.levels.length) {
        const idx = hls.currentLevel >= 0 ? hls.currentLevel : hls.loadLevel;
        const hgt = hls.levels[idx] && hls.levels[idx].height;
        lab = st.qualPinned === null ? (hgt ? 'Auto ' + hgt + 'p' : 'Auto') : (hgt ? hgt + 'p' : 'Stufe');
      } else if (st.cur && st.srcRes[st.cur]) lab = st.srcRes[st.cur] + 'p';
      else if (vid.videoHeight) lab = vid.videoHeight + 'p';
      const sp = bQual.querySelector('span');
      if (sp && sp.textContent !== lab) sp.textContent = lab;
    }
    function switchSource(u) {
      const t = vid.currentTime;
      st.expectDur = isFinite(vid.duration) && vid.duration > 0 ? vid.duration : 0;   // muss gleich lang bleiben
      st.prevCur = st.cur;
      st.sources = [u].concat(st.sources.filter((x) => x !== u));
      st.idx = 0;
      if (t > 0) st.startTime = t;
      tryNext();
    }
    on(bQual, () => { const items = qualityItems(); if (items.length > 1) openMenu('Qualität', items, false, openMore); });   // nur über ⋮ erreichbar

    // ===== ⋯ Mehr: Untertitel, Tonspur, Standbild, Einstellungen =====
    on(bMore, () => { if (menuOpen()) closeMenu(); else openMore(); });   // zweiter Tipp schließt das Menü
    // ----- Wiederholen: Aus → 1× → endlos (∞) → Aus (wie YouTube/Spotify). Am Ende von vorn, solange noch Male übrig -----
    st.repLeft = 0;
    const repShow = () => { bRep.classList.toggle('on', st.repLeft > 0); repNum.textContent = st.repLeft === Infinity ? '∞' : st.repLeft > 0 ? String(st.repLeft) : ''; };
    on(bRep, () => {
      st.repLeft = st.repLeft === 0 ? 1 : st.repLeft === 1 ? Infinity : 0;
      repShow();
      toast(st.repLeft === Infinity ? 'Wiederholen: endlos' : st.repLeft ? 'Wiederholen: 1×' : 'Wiederholen aus');
    });
    // true = es geht von vorn weiter (ein Mal verbraucht)
    st.repeatNow = () => {
      if (st.sleepAtEnd && st.sleepAtEnd()) return true;   // Sleep-Timer „Ende des Videos“: hier Schluss
      if (!(st.repLeft > 0)) return false;
      if (st.repLeft !== Infinity) st.repLeft--;
      repShow();
      const m = M();
      st.endHold = false; st.userSeekAt = Date.now();
      try { m.currentTime = 0; const pr = m.play(); if (pr && pr.catch) pr.catch(() => {}); } catch (e) {}
      return true;
    };
    vid.addEventListener('ended', () => { if (vp === st && st.mode === 'own') { st.repeatNow(); st.sleepHit = false; } });
    on(bSubs, () => openSubs());
    function openMore() {
      const items = [{ label: 'Link teilen', fn: () => bShare.click() }];
      if (bExt && !bExt.hidden) items.push({ label: 'In App öffnen', sub: 'z. B. VLC', fn: () => bExt.click() });
      if (!bQual.hidden) { const q = bQual.querySelector('span'); items.push({ label: 'Qualität', sub: q ? q.textContent : '', fn: () => bQual.click() }); }
      items.push({ label: 'Geschwindigkeit', sub: spLabel(st.speed), fn: openSpeed });
      items.push({ label: 'Sleep-Timer', sub: sleepLabel(), fn: openSleep });
      items.push({ label: 'Dialog-Verstärker', sub: CONFIG.dialogBoost ? 'An' : 'Aus', stay: true, fn: () => { toggleDialog(); openMore(); } });
      const at = audioItems();
      if (at.length > 1) items.push({ label: 'Tonspur', sub: (at.find((x) => x.cur) || {}).label || '', fn: () => openMenu('Tonspur', at, false, openMore) });
      items.push({ label: 'Standbild speichern', fn: snapshot });
      items.push({ label: 'Einstellungen', fn: () => openSettings(false) });
      openMenu('', items);   // ohne Überschrift – der ⋮-Knopf sagt schon, was es ist
    }

    // ----- Einstellungen (dauerhaft gespeichert, überstehen Updates) -----
    function openSettings(keep) {
      const yn = (b) => (b ? 'An' : 'Aus');
      const pct = (v) => (+v > 0 ? Math.round(+v * 100) + ' %' : 'Aus');
      const ZONES = [0, 0.1, 0.15, 0.2, 0.25];
      const zone = (v) => ({ 0: 'Aus', 0.1: 'Schmal', 0.15: 'Mittel', 0.2: 'Breit', 0.25: 'Sehr breit' })[+v] || pct(v);
      const zoneTip = (k, a, b) => toast(+CONFIG[k] > 0 ? a + ' ' + Math.round(CONFIG[k] * 100) + ' %: kein Wischen für Helligkeit/Lautstärke' : b + ': Wischen bis ganz an den Rand');
      const next = (k, list) => { const i = list.indexOf(CONFIG[k]); setSetting(k, list[(i + 1) % list.length]); };
      const it = (label, sub, fn) => ({ label: label, sub: sub, stay: true, fn: () => { fn(); openSettings(true); } });
      const lineOn = !wrap.classList.contains('nomini');
      const items = [
        it('Design', CONFIG.lensGlass !== false ? 'Glas' : 'Schlicht', () => setLens(CONFIG.lensGlass === false)),
        it('Ambient', yn(ambOn), () => setAmbient(!ambOn, true)),
        it('Fortschrittslinie', yn(lineOn), () => {
          wrap.classList.toggle('nomini', lineOn); setSetting('progressLine', !lineOn);
          try { localStorage.removeItem('evp-line'); } catch (e) {}
        }),
        it('Vollbild', yn(CONFIG.startFullscreen), () => setSetting('startFullscreen', !CONFIG.startFullscreen)),
        it('Sprungweite', SKIP() + ' s', () => {
          next('skipSeconds', [5, 10, 15, 30]);
          [bFwd, bBack].forEach((b) => { const x = b.querySelector('text'); if (x) x.textContent = String(SKIP()); });
          [sfxL, sfxR].forEach((e) => { if (e.lab) e.lab.textContent = String(SKIP()); });
          bBack.setAttribute('aria-label', tr(SKIP() + ' s zurück')); bFwd.setAttribute('aria-label', tr(SKIP() + ' s vor'));
        }),
        it('Spul-Vorschau', CONFIG.seekPreview === 'thumb' ? 'Kleines Bild' : 'Im Bild', () => setSetting('seekPreview', CONFIG.seekPreview === 'thumb' ? 'live' : 'thumb')),
        it('Halten', String(CONFIG.holdSpeed).replace('.', UI_EN() ? '.' : ',') + '×', () => next('holdSpeed', [1.5, 2, 3])),
        // Wischsperre: Streifen am Rand ohne Helligkeit/Lautstärke (Schnelleinstellungen, Home-Geste) – Stufe in Worten
        it('Wischsperre oben', zone(CONFIG.topGuard), () => { next('topGuard', ZONES); zoneTip('topGuard', 'Oberste', 'Oben'); }),
        it('Wischsperre unten', zone(CONFIG.bottomGuard), () => { next('bottomGuard', ZONES); zoneTip('bottomGuard', 'Unterste', 'Unten'); }),
        it('Auto-Drehen', yn(CONFIG.autoRotate), () => setSetting('autoRotate', !CONFIG.autoRotate)),
        it('Beim Verlassen', CONFIG.leaveAction === 'pause' ? 'Anhalten' : 'Weiter', () => setSetting('leaveAction', CONFIG.leaveAction === 'pause' ? 'play' : 'pause')),
        it('Download', CONFIG.downloader === 'share' ? 'Teilen' : 'YTDLnis', () => setSetting('downloader', CONFIG.downloader === 'share' ? 'ytdlnis' : 'share')),
        (!HIDE_NO_HELPER || st.sys || CONFIG.systemHelper === false) &&   // Kadri: nur, wenn der Helfer läuft (oder abgeschaltet ist)
        it('Helper-App', yn(CONFIG.systemHelper !== false), () => { setSetting('systemHelper', CONFIG.systemHelper === false); toast('Gilt ab dem nächsten Öffnen des Players'); }),
        it('Sprache', { de: 'Deutsch', en: 'English' }[CONFIG.language] || 'Auto', () => {
          next('language', ['auto', 'de', 'en']);
          toast('Gilt ab dem nächsten Öffnen des Players');
        }),
        it('Zurücksetzen', '', () => {
          resetSettings();
          setAmbient(CONFIG.ambient !== false, false);
          wrap.classList.toggle('nomini', CONFIG.progressLine === false);
          setLens(CONFIG.lensGlass !== false);
          toast('Einstellungen zurückgesetzt');
        }),
      ].filter(Boolean);
      openMenu('Einstellungen', items, keep, openMore);
    }

