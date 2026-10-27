    // ----- Benachrichtigung & Sperrbildschirm (Media Session): Titel, Play/Pause, Springen, Position -----
    const MS = navigator.mediaSession || null;
    const msH = (a, f) => { try { MS.setActionHandler(a, f); } catch (e) {} };
    function msMeta() {
      if (!MS) return;
      try {
        let art = '';
        const og = document.querySelector('meta[property="og:image"],meta[name="twitter:image"]');
        if (og && og.content) { try { art = new URL(og.content, location.href).href; } catch (e) {} }
        const t = pageTitle() || pageHost();
        MS.metadata = new MediaMetadata({ title: t, artist: pageTitle() ? pageHost() : '', artwork: art ? [{ src: art }] : [] });
      } catch (e) {}
    }
    function msPos() {
      if (!MS || vp !== st) return;
      const m = M();
      try { MS.playbackState = m.paused ? 'paused' : 'playing'; } catch (e) {}
      if (!MS.setPositionState || !isFinite(m.duration) || m.duration <= 0) return;
      try { MS.setPositionState({ duration: m.duration, playbackRate: m.playbackRate || 1, position: clamp(m.currentTime, 0, m.duration) }); } catch (e) {}
    }
    if (MS) {
      msMeta();
      msH('play', () => { const m = M(); if (st.heldAtEnd && st.heldAtEnd(m)) { try { m.currentTime = 0; } catch (e) {} } st.endHold = false; m.play().catch(() => {}); });
      msH('pause', () => { st.userPauseAt = Date.now(); M().pause(); });   // auch der Knopf im kleinen Fenster
      msH('stop', () => { st.userPauseAt = Date.now(); M().pause(); });
      msH('seekbackward', (d) => seekBy(-((d && d.seekOffset) || SKIP())));
      msH('seekforward', (d) => seekBy((d && d.seekOffset) || SKIP()));
      msH('previoustrack', () => seekBy(-SKIP()));
      msH('nexttrack', () => seekBy(SKIP()));
      msH('seekto', (d) => { if (d && isFinite(d.seekTime)) { st.userSeekAt = Date.now(); try { M().currentTime = d.seekTime; } catch (e) {} msPos(); } });
      st.cleanup.push(() => {
        if (vp && vp !== st) return;   // delayed cleanup must not clear the new player's Media Session
        ['play', 'pause', 'stop', 'seekbackward', 'seekforward', 'previoustrack', 'nexttrack', 'seekto'].forEach((a) => msH(a, null));
        try { MS.metadata = null; MS.playbackState = 'none'; } catch (e) {}
      });
    }
    // Ereignisse beider Videos (eigenes bzw. gespiegeltes Original)
    const onMedia = (ev) => {
      if (vp !== st) return;
      if (ev.target !== M()) return;
      if (ev.type === 'loadedmetadata' || ev.type === 'durationchange') msMeta();
      msPos();
    };
    const MEDIA_EV = ['play', 'pause', 'seeked', 'ratechange', 'loadedmetadata', 'durationchange'];
    MEDIA_EV.forEach((t) => { vid.addEventListener(t, onMedia); orig.addEventListener(t, onMedia); });
    st.cleanup.push(() => {
      MEDIA_EV.forEach((t) => { vid.removeEventListener(t, onMedia); orig.removeEventListener(t, onMedia); });
    });

