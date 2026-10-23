    // ----- Bis in die Kamera-Aussparung zeichnen -----
    // Ohne "viewport-fit=cover" lässt Android im Vollbild oben den Streifen der Statusleiste/Kamera frei – dann sitzt
    // das Video (mittig in der restlichen Fläche) auf dem ganzen Bildschirm sichtbar zu tief. Solange der Player offen
    // ist, erlauben wir der Seite das Zeichnen bis an den Rand; Knöpfe weichen über safe-area weiterhin aus.
    (function edgeToEdge() {
      try {
        // Nur eine vorhandene Angabe ergänzen. Fehlt sie (Seiten fürs große Bild), NICHT neu anlegen: Das änderte die
        // Breite der ganzen Seite – auf großen Seiten mehrere hundert Millisekunden Neuaufbau beim Öffnen und Schließen.
        const m = document.querySelector('meta[name="viewport"]');
        const before = m ? m.getAttribute('content') : null;
        if (!m || before === null) return;
        if (!/viewport-fit\s*=\s*cover/i.test(before)) m.setAttribute('content', before.replace(/,?\s*viewport-fit\s*=\s*\w+/i, '') + ', viewport-fit=cover');
        st.cleanup.push(() => { try { m.setAttribute('content', before); } catch (e) {} });
      } catch (e) {}
    })();

    // ----- Ambient-Modus -----
    // Ein winziges Abbild des aktuellen Bildes (32 Pixel breit) hinter dem Video. Weichzeichnen, Farbe und der weiche
    // Rand werden IM Mini-Bild gerechnet (32 Pixel – kostet nichts); der Bildschirm vergrößert es nur. Früher machte das
    // ein CSS-Weichzeichner über den ganzen Bildschirm – der kostet auf Handys flüssige Bilder.
    // 2–3 Mal pro Sekunde ein neues Bild, sanft überblendet (in einem zweiten Mini-Bild gesammelt).
    let actx = null, accC = null, accX = null;
    if (!FF_MOBILE) {
      try {
        actx = amb.getContext('2d');
        accC = document.createElement('canvas'); accC.width = amb.width; accC.height = amb.height;
        accX = accC.getContext('2d', { alpha: false });
        if (!accX) actx = null;
      } catch (e) { actx = null; }
    }
    // weicher, ovaler Rand (wie eine CSS-Maske): Ellipse 62 % × 58 %, voll bis 38 %, 45 % bei 66 %, weg bei 92 %
    function ambMask(w, h) {
      actx.globalCompositeOperation = 'destination-in';
      actx.filter = 'none';
      actx.setTransform(w * 0.62, 0, 0, h * 0.58, w / 2, h / 2);
      const g = actx.createRadialGradient(0, 0, 0, 0, 0, 1);
      g.addColorStop(0.38, 'rgba(0,0,0,1)'); g.addColorStop(0.66, 'rgba(0,0,0,.45)'); g.addColorStop(0.92, 'rgba(0,0,0,0)');
      actx.fillStyle = g;
      actx.fillRect(-2, -2, 4, 4);
      actx.setTransform(1, 0, 0, 1, 0, 0);
      actx.globalCompositeOperation = 'source-over';
    }
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
      if (amb.height !== ch) { amb.height = ch; accC.height = ch; first = true; }
      const w = amb.width, h = amb.height;
      try {
        accX.globalAlpha = first ? 1 : 0.5;   // sanft überblenden statt flackern
        accX.drawImage(vid, 0, 0, w, h);
        actx.clearRect(0, 0, w, h);
        actx.filter = 'blur(3px) saturate(1.45) brightness(.7)';   // entspricht blur(48px) auf voller Größe
        actx.drawImage(accC, 0, 0);
        ambMask(w, h);
      } catch (e) {}
    }
    function setAmbient(on, save) {
      ambOn = on;
      wrap.classList.toggle('amb', on);
      if (on) ambDraw(true);
      if (save) { setSetting('ambient', on); try { localStorage.removeItem('evp-ambient'); } catch (e) {} }   // gilt für alle Seiten
    }
    const ambTimer = setInterval(() => { if (!document.hidden && !M().paused) ambDraw(false); }, 400);
    vid.addEventListener('loadeddata', () => ambDraw(true));
    vid.addEventListener('seeked', () => ambDraw(true));
    st.cleanup.push(() => {
      clearInterval(ambTimer);
      if (FF_MOBILE) { amb.srcObject = null; if (ambStream) { try { ambStream.getVideoTracks().forEach((t) => t.stop()); } catch (e) {} } ambStream = null; }
    });
    setAmbient(ambOn, false);
    // Fortschrittslinie ganz unten: an/aus aus dem Format-Menü (gemerkt), Standard an
    try { if (localStorage.getItem('evp-line') === '0' || CONFIG.progressLine === false && localStorage.getItem('evp-line') !== '1') wrap.classList.add('nomini'); } catch (e) {}

