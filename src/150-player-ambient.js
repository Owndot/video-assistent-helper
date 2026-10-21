    // ----- Bis in die Kamera-Aussparung zeichnen -----
    // Ohne "viewport-fit=cover" lässt Android im Vollbild oben den Streifen der Statusleiste/Kamera frei – dann sitzt
    // das Video (mittig in der restlichen Fläche) auf dem ganzen Bildschirm sichtbar zu tief. Solange der Player offen
    // ist, erlauben wir der Seite das Zeichnen bis an den Rand; Knöpfe weichen über safe-area weiterhin aus.
    (function edgeToEdge() {
      try {
        let m = document.querySelector('meta[name="viewport"]'), made = false;
        const before = m ? m.getAttribute('content') : null;
        if (!m) { m = document.createElement('meta'); m.name = 'viewport'; (document.head || document.documentElement).appendChild(m); made = true; }
        const c = before || 'width=device-width, initial-scale=1';
        if (!/viewport-fit\s*=\s*cover/i.test(c)) m.setAttribute('content', c.replace(/,?\s*viewport-fit\s*=\s*\w+/i, '') + ', viewport-fit=cover');
        st.cleanup.push(() => { try { if (made) m.remove(); else if (before !== null) m.setAttribute('content', before); } catch (e) {} });
      } catch (e) {}
    })();

    // ----- Ambient-Modus -----
    // Ein winziges Abbild des aktuellen Bildes (32 Pixel breit), stark weichgezeichnet hinter dem Video.
    // Wenig Arbeit: ~8 Mal pro Sekunde ein Mini-Bild, sanft überblendet. Abschaltbar im Format-Menü (wird gemerkt).
    let actx = null;
    if (!FF_MOBILE) { try { actx = amb.getContext('2d', { alpha: false }); } catch (e) {} }
    // Firefox-Handy: derselbe Bildstrom in einem zweiten, weichgezeichneten Video (kein Kopieren, kein zweites Laden).
    // Eigene Quelle: Abgriff des Player-Videos – nur mit captureStream (lässt den Ton im Player); ältere Firefox
    // (nur mozCaptureStream, das den Ton mitnimmt) zeigen Ambient nur beim Spiegeln.
    let ambStream = null, ambTag = '';
    function ambFeed(force) {
      if (!ambOn || vp !== st) { if (amb.srcObject) amb.srcObject = null; ambTag = ''; return; }
      let s = null;
      if (st.mode === 'capture') s = vid.srcObject;
      else if (!FF_CAP && vid.captureStream && vid.readyState >= 1) {
        if (!ambStream) { try { ambStream = vid.captureStream(); } catch (e) { ambStream = null; } }
        s = ambStream;
      }
      if (!s) { if (amb.srcObject) amb.srcObject = null; return; }
      const tag = st.mode + ':' + (st.mirrorFix || 0) + ':' + (st.cur || '');
      // neue Quelle/Spuren: Firefox zeigt neue Spuren eines Stroms erst nach erneutem Zuweisen
      if (amb.srcObject !== s || force || tag !== ambTag) { ambTag = tag; amb.srcObject = null; amb.srcObject = s; }
      if (amb.paused) amb.play().catch(() => {});
    }
    let ambOn = CONFIG.ambient !== false;
    try { const sv = localStorage.getItem('evp-ambient'); if (sv === '0') ambOn = false; else if (sv === '1') ambOn = true; } catch (e) {}
    function ambDraw(first) {
      if (FF_MOBILE) { ambFeed(first); return; }
      if (!actx || !ambOn || vp !== st) return;
      const vw = vid.videoWidth, vh = vid.videoHeight;
      if (!vw || !vh || vid.readyState < 2) return;
      const ch = Math.max(8, Math.min(48, Math.round(32 * vh / vw)));
      if (amb.height !== ch) { amb.height = ch; first = true; }
      try {
        actx.globalAlpha = first ? 1 : 0.3;   // sanft überblenden statt flackern
        actx.drawImage(vid, 0, 0, amb.width, amb.height);
      } catch (e) {}
    }
    function setAmbient(on, save) {
      ambOn = on;
      wrap.classList.toggle('amb', on);
      if (on) ambDraw(true);
      if (save) { setSetting('ambient', on); try { localStorage.removeItem('evp-ambient'); } catch (e) {} }   // gilt für alle Seiten
    }
    const ambTimer = setInterval(() => { if (!document.hidden && !M().paused) ambDraw(false); }, 120);
    vid.addEventListener('loadeddata', () => ambDraw(true));
    vid.addEventListener('seeked', () => ambDraw(true));
    st.cleanup.push(() => {
      clearInterval(ambTimer);
      if (FF_MOBILE) { amb.srcObject = null; if (ambStream) { try { ambStream.getVideoTracks().forEach((t) => t.stop()); } catch (e) {} } ambStream = null; }
    });
    setAmbient(ambOn, false);
    // Fortschrittslinie ganz unten: an/aus aus dem Format-Menü (gemerkt), Standard an
    try { if (localStorage.getItem('evp-line') === '0' || CONFIG.progressLine === false && localStorage.getItem('evp-line') !== '1') wrap.classList.add('nomini'); } catch (e) {}

