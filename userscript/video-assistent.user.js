// ==UserScript==
// @name         Video-Assistent (externer Player)
// @namespace    externer-player
// @version      16.5
// @description  Wie der Video-Assistent von Samsung Internet: Button am laufenden Video öffnet einen eingebauten Vollbild-Player mit Wischgesten (oder die externe App wie VLC/Just Player). Mit Download über YTDLnis und eigener Erkennung für Facebook, Instagram, Pornhub u. a.
// @match        *://*/*
// @run-at       document-start
// @grant        GM_xmlhttpRequest
// @grant        GM.xmlHttpRequest
// @grant        GM_registerMenuCommand
// @grant        GM_getValue
// @grant        GM_setValue
// @grant        GM.registerMenuCommand
// @connect      *
// @connect      127.0.0.1
// @connect      localhost
// @require      https://cdn.jsdelivr.net/npm/hls.js@1/dist/hls.min.js
// @inject-into  content
// ==/UserScript==

(function () {
  'use strict';
  if (window.__evpLoaded) return;
  window.__evpLoaded = true;
  // In einem Fenster ohne eigene Quelle übernimmt die Hauptseite (sonst gäbe es zwei Buttons)
  try {
    const fe = window.frameElement;
    if (fe) {
      const src = (fe.getAttribute('src') || '').trim();
      if (!src || /^(about:|javascript:|blob:|data:)/i.test(src) || fe.hasAttribute('srcdoc')) return;
    }
  } catch (e) {}

  // ================= Einstellungen =================
  const CONFIG = {
    // '' = Android fragt nach dem Player (einmal "Immer" wählen).
    // Oder fest: 'org.videolan.vlc' (VLC), 'is.xyz.mpv' (mpv),
    // 'com.mxtech.videoplayer.ad' (MX Player), 'com.brouken.player' (Just Player)
    player: '',
    minWidth: 160,      // kleinere Videos ignorieren
    bottomOffset: 64,   // Abstand des Buttons vom unteren Videorand (über der Steuerleiste)
    rightOffset: 12,    // Abstand vom rechten Videorand
    // Was "Player" öffnet: 'browser' = eingebauter Vollbild-Player (klappt auf praktisch allen Seiten),
    // 'external' = sofort die externe App (VLC, Just Player …). Im eingebauten Player gibt es dafür auch einen Knopf.
    defaultPlayer: 'browser',
    // true = eingebauter Player startet im Vollbild (in der Ausrichtung, wie du das Handy hältst; gedreht wird nur per Knopf).
    // false = als Fenster ohne Vollbild-Hinweis des Browsers; Vollbild/Querformat dann nur über den Drehen-Knopf.
    startFullscreen: true,
    // Facebook: Bild direkt von Facebooks eigenem Player spiegeln (immer das richtige Video, gleiche Qualität wie im
    // Browser). false = wie früher selbst nach der Videodatei suchen.
    fbMirror: true,
    // Lupen-Glas wie bei iOS 26 / macOS: das Bild hinter Knöpfen und Reglern wird am Rand gebogen und leicht vergrößert.
    // false = einfaches Milchglas (spart etwas Rechenleistung)
    lensGlass: true,
    progressLine: true,   // dünne Fortschrittslinie ganz unten, wenn die Leisten ausgeblendet sind (im Format-Menü umschaltbar)
    ambient: true,   // Ambient-Modus: Farben des Bildes leuchten weich in die schwarzen Ränder (im Format-Menü umschaltbar)
    autoRotate: false,  // true = Querformat-Videos im Vollbild automatisch drehen
    resumeAfterClose: true,   // nach dem Schließen des Players läuft das Video auf der Seite weiter
    holdSpeed: 2,             // lange ins Video drücken = so viel schneller, solange du hältst
    topGuard: 0.15,           // oberer Rand (Anteil der Höhe): hier starten keine Helligkeits-/Lautstärke-Gesten (Schnelleinstellungen)
    bottomGuard: 0.15,        // unterer Rand: ebenso (Home-Wischgeste von Android)
    skipSeconds: 10,          // Doppeltipp links/rechts und die Sprung-Knöpfe: so viele Sekunden
    subSize: 'm',             // Untertitel: 's' klein, 'm' mittel, 'l' groß
    subHigh: false,           // Untertitel oben im Bild statt unten (z. B. wenn das Video eigene Texte unten hat)
    language: 'auto',         // Sprache der Oberfläche: 'auto' (wie der Browser), 'de' oder 'en'
    // Helfer-App "Video-Assistent Helfer" (eigene APK): Läuft sie, steuern die Wischgesten die ECHTE Helligkeit und
    // Medienlautstärke des Handys. Läuft sie nicht, regelt der Player wie bisher nur das Video. false = nie fragen.
    systemHelper: true,
    // Wo der Button sitzt: 'below' = direkt unter dem Video, 'video' = am Rand im Video, 'top' = oben am Bildschirmrand
    buttonAt: 'below',
    // Button nur zeigen, wenn das Video mit Ton läuft (stumme Feed-Videos lösen ihn nicht aus) – wie Samsung Internet
    onlyWithSound: true,
    topOffset: 12,      // Abstand vom oberen Rand bei buttonAt: 'top'
    // Seiten, die YTDLnis (yt-dlp) herunterladen kann – nur hier erscheint der Download-Button ⬇.
    // Nach der offiziellen Liste von yt-dlp. Eigene Seiten einfach ergänzen, z. B. 'beispiel.de'.
    ytdlpSites: [
      'youtube.com', 'youtu.be', 'youtube-nocookie.com', 'facebook.com', 'fb.watch', 'instagram.com',
      'x.com', 'twitter.com', 'tiktok.com', 'reddit.com', 'redd.it', 'vimeo.com', 'dailymotion.com', 'dai.ly',
      'twitch.tv', 'kick.com', 'pinterest.com', 'pinterest.de', 'pin.it', 'linkedin.com', 'snapchat.com',
      't.me', 'bilibili.com', 'b23.tv', 'streamable.com', 'imgur.com', 'tumblr.com', '9gag.com', 'rumble.com',
      'bitchute.com', 'odysee.com', 'vk.com', 'vkvideo.ru', 'ok.ru', 'ardmediathek.de', 'zdf.de', 'arte.tv',
      '3sat.de', 'bandcamp.com', 'soundcloud.com', 'weibo.com', 'douyin.com', 'likee.video', 'coub.com',
      'loom.com', 'mixcloud.com', 'naver.com', 'nicovideo.jp', 'vidio.com', 'bsky.app',
      'xvideos.com', 'xhamster.com',
    ],
    // Wohin "Herunterladen" auf Seiten geht, die yt-dlp kennt: 'ytdlnis' = direkt in YTDLnis öffnen (ohne Teilen-Menü),
    // 'share' = Teilen-Menü wie früher (z. B. für Seal oder andere Apps). Lange auf ⬇ drücken zeigt immer alle Wege.
    downloader: 'ytdlnis',
    // true = Download-Button auf allen Seiten zeigen (yt-dlp versucht es dann mit seinem allgemeinen Modus)
    downloadEverywhere: false,
    // Seiten, auf denen nur der Download-Button (YTDLnis) erscheint – externer Player klappt dort nicht
    downloadOnlySites: ['youtube.com', 'youtu.be', 'youtube-nocookie.com', 'twitch.tv'],
    // Seiten, auf denen das Skript ganz aus ist (Kopierschutz/DRM – weder Player noch Download möglich)
    disabledSites: ['netflix.com', 'disneyplus.com', 'primevideo.com', 'amazon.de', 'amazon.com',
      'max.com', 'hbomax.com', 'hulu.com', 'paramountplus.com', 'tv.apple.com', 'wowtv.de', 'sky.de',
      'skyshowtime.com', 'joyn.de', 'plus.rtl.de', 'rtlplus.de', 'crunchyroll.com', 'dazn.com',
      'magentatv.de', 'waipu.tv', 'zattoo.com', 'spotify.com'],
  };

  // ----- Dauerhafter Speicher (Violentmonkey; ohne GM-Rechte ersatzweise der Speicher der Seite) -----
  const store = {
    get(k, d) {
      try { if (typeof GM_getValue === 'function') { const v = GM_getValue(k, undefined); if (v !== undefined) return v; } } catch (e) {}
      try { const v = localStorage.getItem('evp-s-' + k); return v == null ? d : JSON.parse(v); } catch (e) { return d; }
    },
    set(k, v) {
      try { if (typeof GM_setValue === 'function') { GM_setValue(k, v); return; } } catch (e) {}
      try { localStorage.setItem('evp-s-' + k, JSON.stringify(v)); } catch (e) {}
    },
  };
  // Eigene Einstellungen (im Player: ⋯ → Einstellungen) gelten vor den Werten oben und überstehen Updates des Skripts
  const SETTING_KEYS = ['startFullscreen', 'lensGlass', 'ambient', 'progressLine', 'holdSpeed', 'topGuard', 'bottomGuard',
    'skipSeconds', 'systemHelper', 'downloader', 'autoRotate', 'subSize', 'subHigh', 'language'];
  const CONFIG_DEFAULT = {};
  SETTING_KEYS.forEach((k) => { CONFIG_DEFAULT[k] = CONFIG[k]; });
  const userSettings = () => { const o = store.get('settings', {}); return o && typeof o === 'object' ? o : {}; };
  (() => { const o = userSettings(); SETTING_KEYS.forEach((k) => { if (k in o && o[k] !== null) CONFIG[k] = o[k]; }); })();
  function setSetting(k, v) {
    CONFIG[k] = v;
    const o = userSettings(); o[k] = v; store.set('settings', o);
  }
  function resetSettings() { store.set('settings', {}); SETTING_KEYS.forEach((k) => { CONFIG[k] = CONFIG_DEFAULT[k]; }); }

  // ----- Sprache: Deutsch / English (Einstellungen → Sprache; „Auto“ = Sprache des Browsers) -----
  // Alle Texte stehen im Skript auf Deutsch; tr() übersetzt sie an den Stellen, durch die alles läuft (Hinweise,
  // Anzeigen, Menüs, Beschriftungen). Zusammengesetzte Texte über Muster bzw. Teil für Teil (getrennt durch „ – “).
  const UI_EN = () => (CONFIG.language === 'en' ? true : CONFIG.language === 'de' ? false : !/^de\b/i.test(navigator.language || ''));
  const EN = {
    // Knöpfe & Bedienung
    'Im Videoplayer öffnen': 'Open in video player', 'Herunterladen (z. B. mit YTDLnis)': 'Download (e.g. with YTDLnis)',
    'In externer App öffnen': 'Open in external app', 'Schließen': 'Close', 'Wiedergabe': 'Play', 'Pause': 'Pause',
    'Geschwindigkeit': 'Speed', 'Bild-in-Bild': 'Picture-in-picture', 'Qualität': 'Quality', 'Bildformat': 'Aspect ratio',
    'Drehen': 'Rotate', 'Sperren': 'Lock', 'Bedienung sperren': 'Lock controls', 'Entsperren': 'Unlock', 'Mehr': 'More',
    'Untertitel': 'Subtitles', 'Position': 'Position', 'Format': 'Format', 'Auto': 'Auto', 'Stufe': 'Level',
    'Weiterschauen': 'Resume',
    // Menüs
    'Einstellungen': 'Settings', 'Tonspur': 'Audio track', 'Standbild speichern': 'Save snapshot', 'Aus': 'Off', 'An': 'On',
    'aus': 'off', 'an': 'on', 'Datei laden …': 'Load file …', '.srt, .vtt oder .ttml vom Handy': '.srt, .vtt or .ttml from your phone',
    'Größe': 'Size', 'klein': 'small', 'mittel': 'medium', 'groß': 'large', 'Lage': 'Position', 'unten im Bild': 'bottom of picture',
    'oben im Bild': 'top of picture', 'Früher zeigen': 'Show earlier', 'Später zeigen': 'Show later', 'eigene Datei': 'own file',
    'von der Seite': 'from the page', 'Suche Untertitel der Seite …': 'Looking for subtitles on the page …',
    'Automatisch': 'Automatic', 'passt sich der Verbindung an': 'adapts to your connection', 'Original': 'Original',
    'Füllen': 'Fill', 'Strecken': 'Stretch', 'Zoom zurückgesetzt': 'Zoom reset',
    // Einstellungen (kurz)
    'Design': 'Design', 'Glas': 'Glass', 'Schlicht': 'Plain', 'Vollbild': 'Full screen', 'Ambient': 'Ambient',
    'Fortschrittslinie': 'Progress line', 'Sprungweite': 'Skip', 'Halten': 'Hold', 'Wischsperre oben': 'Swipe guard top', 'Wischsperre unten': 'Swipe guard bottom', 'Schmal': 'Narrow', 'Mittel': 'Medium', 'Breit': 'Wide', 'Sehr breit': 'Extra wide',
    'Link teilen': 'Share link', 'In App öffnen': 'Open in app', 'z. B. VLC': 'e.g. VLC', 'Link kopiert': 'Link copied', 'Kein Link zu diesem Video gefunden': 'No link to this video found',
    'Oben: Wischen bis ganz an den Rand': 'Top: swiping works right up to the edge', 'Unten: Wischen bis ganz an den Rand': 'Bottom: swiping works right down to the edge',
    '✓ Verbunden. Ab jetzt kann nur noch dieses Skript den Helper benutzen. Du kannst diese Seite schließen.': '✓ Connected. From now on only this script can use the helper. You can close this page.',
    'Verbinden hat nicht geklappt – läuft der Helper? In der App nochmal auf „Verbinden“ tippen.': 'Connecting failed – is the helper running? Tap “Connect” in the app again.',
    'Auto-Drehen': 'Auto-rotate', 'Helper-App': 'Helper app', 'Download': 'Download', 'Teilen': 'Share', 'Sprache': 'Language',
    'Zurücksetzen': 'Reset', 'Deutsch': 'Deutsch', 'English': 'English',
    // Hinweise
    'Einstellungen zurückgesetzt': 'Settings reset', 'Gilt ab dem nächsten Öffnen des Players': 'Applies the next time the player opens',
    'Untertitel werden geladen …': 'Loading subtitles …', 'Diese Untertitel lassen sich nicht laden': 'These subtitles can’t be loaded',
    'Keine Untertitel in dieser Datei gefunden': 'No subtitles found in this file', 'Noch kein Bild da': 'No picture yet',
    'Diese Seite erlaubt kein Standbild': 'This site doesn’t allow snapshots',
    'Diese Seite erlaubt kein Standbild (Videoserver sperrt es)': 'This site doesn’t allow snapshots (video server blocks it)',
    'In Firefox auf dem Handy leider nicht möglich': 'Not possible in Firefox on mobile, sorry', 'Kein Bild bekommen': 'Couldn’t get a picture',
    'Standbild gespeichert (Downloads)': 'Snapshot saved (Downloads)', 'Bild-in-Bild ist hier nicht möglich': 'Picture-in-picture isn’t possible here',
    'Drehen ist hier nicht möglich – dreh das Handy mit aktivierter Auto-Drehung': 'Rotating isn’t possible here – turn your phone with auto-rotate on',
    'Diese Qualität gehörte zu einem anderen Video': 'This quality belonged to another video',
    'Dieses Video lässt sich hier nicht im eigenen Player zeigen': 'This video can’t be shown in the player here',
    'Dieses Video gibt es hier nur mit getrennter Bild- und Tonspur': 'This video only exists with separate video and audio here',
    'Bereite Video vor …': 'Preparing video …', 'Erkenne Video …': 'Detecting video …', 'Bereite Download vor …': 'Preparing download …',
    'Download wird vorbereitet …': 'Preparing download …', 'Suche die Videodatei …': 'Looking for the video file …',
    'Suche MP4 mit Ton …': 'Looking for MP4 with sound …', 'Suche HD-Version …': 'Looking for HD version …',
    'Wird im Videoplayer geöffnet (Weiterleitung aufgelöst) …': 'Opening in video player (redirect resolved) …',
    'Die Seite sperrt den Download': 'The site blocks the download', 'Download des Browsers': 'Browser download',
    'Download läuft im Browser – du kannst die Seite verlassen': 'Downloading in the browser – you can leave the page',
    'Download läuft – siehe Benachrichtigung': 'Downloading – see notification', 'Der Server lehnt den Helper ab': 'The server rejects the helper',
    'Für Streams wird die Helper-App gebraucht': 'Streams need the helper app', 'Für dieses Video gibt es keine Datei zum Herunterladen': 'There’s no file to download for this video',
    'Für dieses Video gibt es keine Datei-Adresse': 'There’s no file address for this video', 'Helper konnte den Download nicht starten': 'The helper couldn’t start the download',
    'Helper lädt den Stream – Fortschritt in der Benachrichtigung': 'The helper is downloading the stream – progress in the notification',
    'Server verlangt die Browser-Sitzung': 'The server needs the browser session',
    'Kein Link zu diesem Video gefunden – Beitrag öffnen und nochmal versuchen': 'No link to this video found – open the post and try again',
    'Kein passendes Video gefunden – kurz laufen lassen und nochmal tippen': 'No matching video found – let it play briefly and tap again',
    'Link kopiert – in YTDLnis einfügen': 'Link copied – paste it into YTDLnis', 'Link kopiert': 'Link copied', 'Link wird geteilt': 'sharing the link',
    'klappt nicht? Nochmal tippen': 'not working? Tap again', 'Neuer Tab wurde blockiert – Pop-ups für diese Seite erlauben': 'New tab was blocked – allow pop-ups for this site',
    'Videodatei gefunden': 'Video file found', 'Loslassen, um die Diagnose zu kopieren': 'Release to copy the diagnostics', 'Diagnose kopiert': 'Diagnostics copied',
    // Download-Menü
    'Mit dem Helper': 'With the helper', 'Download-Manager': 'Download manager', 'Stream → MP4': 'Stream → MP4',
    'Über den Browser laden': 'Load via the browser', 'für geschützte Seiten · Seite offen lassen': 'for protected sites · keep the page open',
    'Mit YTDLnis': 'With YTDLnis', 'direkt öffnen': 'open directly', 'Im Browser speichern': 'Save in the browser',
    'Im Browser öffnen': 'Open in the browser', 'dort ⋮ → Herunterladen': 'then ⋮ → Download', 'Hier speichern': 'Save here',
    'Player bis zum Ende offen lassen': 'keep the player open until it’s done', 'Teilen …': 'Share …', 'an eine andere App': 'to another app',
    'lädt über den Browser, Seite offen lassen': 'loading via the browser, keep the page open',
    'Im Player öffnen': 'Open in player', 'Im Player öffnen (HD)': 'Open in player (HD)', 'Im Player öffnen (Full HD)': 'Open in player (Full HD)',
    'Player · HD': 'Player · HD', 'Player · Full HD': 'Player · Full HD', 'Diagnose anzeigen': 'Show diagnostics',
    'links': 'left', 'rechts': 'right',
  };
  const EN_RX = [
    [/^Oberste (\d+) %: kein Wischen für Helligkeit\/Lautstärke$/, (m) => 'Top ' + m[1] + ' %: no brightness/volume swipe'],
    [/^Unterste (\d+) %: kein Wischen für Helligkeit\/Lautstärke$/, (m) => 'Bottom ' + m[1] + ' %: no brightness/volume swipe'],
    [/^Untertitel: (.+)$/, (m) => 'Subtitles: ' + m[1]], [/^Tonspur: (.+)$/, (m) => 'Audio: ' + m[1]],
    [/^(\d+) s zurück$/, (m) => m[1] + ' s back'], [/^(\d+) s vor$/, (m) => m[1] + ' s forward'],
    [/^([\d,.]+)× schneller$/, (m) => m[1].replace(',', '.') + '× faster'], [/^Verschiebung (.+)$/, (m) => 'Offset ' + m[1].replace(',', '.')],
    [/^Position gespeichert \((links|rechts)\)$/, (m) => 'Position saved (' + (m[1] === 'links' ? 'left' : 'right') + ')'],
    [/^Spur (\d+)$/, (m) => 'Track ' + m[1]], [/^Seite (\d+)$/, (m) => 'Page ' + m[1]], [/^Stufe (\d+)$/, (m) => 'Level ' + m[1]],
    [/^Quelle (\d+)\/(\d+)$/, (m) => 'Source ' + m[1] + '/' + m[2]], [/^(Lautstärke|Helligkeit) (\d+) %$/, (m) => (m[1] === 'Lautstärke' ? 'Volume ' : 'Brightness ') + m[2] + ' %'],
    [/^Länge passt nicht \((.+) s statt (.+) s\)$/, (m) => 'length doesn’t match (' + m[1] + ' s instead of ' + m[2] + ' s)'],
    [/^Keine Videodatei( mit Ton)? gefunden \((.*)\)$/, (m) => 'No video file' + (m[1] ? ' with sound' : '') + ' found (' + tr(m[2]) + ')'],
  ];
  function tr(x) {
    if (typeof x !== 'string' || !x || !UI_EN()) return x;
    if (Object.prototype.hasOwnProperty.call(EN, x)) return EN[x];
    for (let i = 0; i < EN_RX.length; i++) { const m = EN_RX[i][0].exec(x); if (m) return EN_RX[i][1](m); }
    if (x.indexOf(' – ') > 0) return x.split(' – ').map(tr).join(' – ');
    return x;
  }
  // Weiterschauen gibt es nicht mehr – früher gemerkte Stellen einmalig löschen
  try { const old = store.get('resume', null); if (old && typeof old === 'object' && Object.keys(old).length) store.set('resume', {}); } catch (e) {}
  const SKIP = () => ([5, 10, 15, 30].indexOf(+CONFIG.skipSeconds) >= 0 ? +CONFIG.skipSeconds : 10);
  // =================================================

  // ================= Eingebettete Fenster (iframe) =================
  // Viele Seiten laden das Video in einem eingebetteten Fenster von einer anderen Adresse. Der Player dort drin
  // könnte nur diesen kleinen Kasten füllen. Darum spricht er mit dem Skript auf der Hauptseite: die legt den Kasten
  // über den ganzen Bildschirm, schaltet IHN ins Vollbild, dreht das Bild und meldet Titel, Ränder (Kamera-Aussparung)
  // und Vollbild-Zustand zurück. Bei mehrfach verschachtelten Fenstern reicht jede Ebene die Nachricht weiter.
  const FK = '__evpFrame1';
  const IN_FRAME = (() => { try { return window.top !== window; } catch (e) { return true; } })();
  const fb = { up: null, ack: false, child: null, childWin: null, saved: null, onState: null };
  const fpost = (w, m) => { try { m[FK] = 1; w.postMessage(m, '*'); } catch (e) {} };
  const pageTitle = () => String((IN_FRAME && fb.up && fb.up.title) || document.title || '').trim();
  const pageHost = () => String((IN_FRAME && fb.up && fb.up.host) || location.hostname).replace(/^(www|m)\./, '');
  const fsElem = () => document.fullscreenElement || document.webkitFullscreenElement || null;
  function safeInsets() {
    if (IN_FRAME) return (fb.up && fb.up.ins) || [0, 0, 0, 0];
    try {
      const d = document.createElement('div');
      d.style.cssText = 'position:fixed;left:0;top:0;width:0;height:0;visibility:hidden;pointer-events:none;' +
        'padding:env(safe-area-inset-top,0px) env(safe-area-inset-right,0px) env(safe-area-inset-bottom,0px) env(safe-area-inset-left,0px)';
      document.documentElement.appendChild(d);
      const cs = getComputedStyle(d);
      const r = [cs.paddingTop, cs.paddingRight, cs.paddingBottom, cs.paddingLeft].map((x) => parseFloat(x) || 0);
      d.remove();
      return r;
    } catch (e) { return [0, 0, 0, 0]; }
  }
  // --- Hauptseite (bzw. Zwischenebene): das eingebettete Fenster groß machen ---
  function frameOf(win) {
    const all = document.querySelectorAll('iframe,frame');
    for (let i = 0; i < all.length; i++) { try { if (all[i].contentWindow === win) return all[i]; } catch (e) {} }
    return null;
  }
  function sendState() {
    if (!fb.childWin) return;
    const fs = IN_FRAME ? !!(fb.up && fb.up.fs) : fsElem() === fb.child;
    fpost(fb.childWin, { t: 'state', fs: fs, ins: safeInsets(), title: pageTitle(), host: pageHost() });
  }
  const STRETCH = { position: 'fixed', left: '0', top: '0', right: '0', bottom: '0', width: '100%', height: '100%',
    'max-width': 'none', 'max-height': 'none', 'min-width': '0', 'min-height': '0', margin: '0', padding: '0', border: '0',
    'border-radius': '0', transform: 'none', translate: 'none', scale: 'none', filter: 'none', 'clip-path': 'none',
    opacity: '1', visibility: 'visible', display: 'block', 'z-index': '2147483647', background: '#000' };
  function stretch(f, win) {
    if (fb.child && fb.child !== f) unstretch();
    fb.childWin = win;
    if (fb.child === f) return;
    fb.child = f;
    fb.iv = setInterval(() => { if (!fb.child || !fb.child.isConnected) unstretch(); }, 1000);   // Fenster entfernt? aufräumen
    const de = document.documentElement;
    let meta = document.querySelector('meta[name="viewport"]'), made = false;
    const metaBefore = meta ? meta.getAttribute('content') : null;
    fb.saved = { style: f.getAttribute('style'), ov: de.style.getPropertyValue('overflow'), ovp: de.style.getPropertyPriority('overflow'), meta: null };
    Object.keys(STRETCH).forEach((k) => f.style.setProperty(k, STRETCH[k], 'important'));
    // Eltern mit transform/filter/contain würden "fixed" auf ihren eigenen Kasten begrenzen, eine niedrige Ebene
    // (z-index) ließe Kopfleisten der Seite darüber liegen – solange der Player offen ist, beides aufheben.
    fb.saved.anc = [];
    for (let a = f.parentElement; a && a !== de; a = a.parentElement) {
      let cs;
      try { cs = getComputedStyle(a); } catch (e) { continue; }
      const fix = {};
      if (cs.transform !== 'none') fix.transform = 'none';
      if (cs.translate && cs.translate !== 'none') fix.translate = 'none';
      if (cs.scale && cs.scale !== 'none') fix.scale = 'none';
      if (cs.rotate && cs.rotate !== 'none') fix.rotate = 'none';
      if (cs.perspective !== 'none') fix.perspective = 'none';
      if (cs.filter !== 'none') fix.filter = 'none';
      if (cs.backdropFilter && cs.backdropFilter !== 'none') fix['backdrop-filter'] = 'none';
      if (cs.contain && cs.contain !== 'none') fix.contain = 'none';
      if (cs.containerType && cs.containerType !== 'normal') fix['container-type'] = 'normal';
      if (/transform|filter|perspective|contents/.test(cs.willChange || '')) fix['will-change'] = 'auto';
      if (cs.zIndex !== 'auto') fix['z-index'] = '2147483647';
      const ks = Object.keys(fix);
      if (!ks.length) continue;
      fb.saved.anc.push({ el: a, old: ks.map((k) => [k, a.style.getPropertyValue(k), a.style.getPropertyPriority(k)]) });
      ks.forEach((k) => a.style.setProperty(k, fix[k], 'important'));
    }
    de.style.setProperty('overflow', 'hidden', 'important');
    // bis in die Kamera-Aussparung zeichnen (die Knöpfe im Player weichen über die gemeldeten Ränder aus)
    if (!IN_FRAME) {
      try {
        if (!meta) { meta = document.createElement('meta'); meta.name = 'viewport'; (document.head || de).appendChild(meta); made = true; }
        const c = metaBefore || 'width=device-width, initial-scale=1';
        if (!/viewport-fit\s*=\s*cover/i.test(c)) meta.setAttribute('content', c.replace(/,?\s*viewport-fit\s*=\s*\w+/i, '') + ', viewport-fit=cover');
        fb.saved.meta = { el: meta, made: made, before: metaBefore };
      } catch (e) {}
    }
  }
  function unstretch() {
    const f = fb.child, s = fb.saved;
    fb.child = null; fb.childWin = null; fb.saved = null;
    clearInterval(fb.iv);
    if (!f || !s) return;
    try { if (fsElem() === f) document.exitFullscreen().catch(() => {}); } catch (e) {}
    try { if (!IN_FRAME && screen.orientation && screen.orientation.unlock) screen.orientation.unlock(); } catch (e) {}
    if (s.style === null) f.removeAttribute('style'); else f.setAttribute('style', s.style);
    (s.anc || []).forEach((x) => x.old.forEach((o) => { x.el.style.removeProperty(o[0]); if (o[1]) x.el.style.setProperty(o[0], o[1], o[2]); }));
    const de = document.documentElement;
    de.style.removeProperty('overflow');
    if (s.ov) de.style.setProperty('overflow', s.ov, s.ovp);
    const m = s.meta;
    if (m) { try { if (m.made) m.el.remove(); else if (m.before !== null) m.el.setAttribute('content', m.before); } catch (e) {} }
  }
  function frameFs(f) {
    try {
      if (fsElem() === f) return;
      const rq = f.requestFullscreen || f.webkitRequestFullscreen;
      const pr = rq && rq.call(f, { navigationUI: 'hide' });
      // abgelehnt (Firefox: kein Tipp hier oben) → das Fenster selbst versucht es, dort gilt der Tipp noch
      const no = () => { sendState(); if (fb.childWin) fpost(fb.childWin, { t: 'fsno' }); };
      if (pr && pr.then) pr.then(sendState, no); else if (!rq) no();
    } catch (e) { if (fb.childWin) fpost(fb.childWin, { t: 'fsno' }); }
  }
  addEventListener('message', (e) => {
    const d = e.data;
    if (!d || typeof d !== 'object' || d[FK] !== 1) return;
    // Nachricht von OBEN (Hauptseite an uns)
    if (IN_FRAME && e.source === window.parent) {
      if (d.t === 'info' || d.t === 'state') { fb.up = Object.assign(fb.up || {}, d); fb.ack = true; }
      if (d.t === 'pageurl') { if (fb.childWin) fpost(fb.childWin, d); else if (fb.onPageUrl) fb.onPageUrl(d.url, d.title); }
      if (d.t === 'state') {
        if (fb.childWin) sendState();          // Zwischenebene: weiter nach unten
        else if (fb.onState) fb.onState(d);
      }
      if (d.t === 'fsno') { if (fb.childWin) fpost(fb.childWin, { t: 'fsno' }); else if (fb.onFsNo) fb.onFsNo(); }
      return;
    }
    // Nachricht von UNTEN (aus einem eingebetteten Fenster)
    const f = frameOf(e.source);
    if (!f) return;
    if (d.t === 'hello') { fpost(e.source, { t: 'info', title: pageTitle(), host: pageHost(), ins: safeInsets() }); return; }
    // Adresse der Seite nur an das Fenster, das gerade den Player zeigt (nicht an jede eingebettete Werbung)
    if (d.t === 'askurl') {
      if (fb.child !== f) return;
      if (IN_FRAME) fpost(window.parent, { t: 'askurl' });
      else fpost(e.source, { t: 'pageurl', url: location.href.split('#')[0], title: pageTitle() });
      return;
    }
    if (d.t === 'take') {
      if (fb.child !== f) {
        // nur nach einem echten Tipp und für ein sichtbares Fenster (keine Werbung, die sich selbst groß macht)
        // Firefox reicht den Tipp nicht an die Hauptseite weiter – dort zeigt der Fokus auf dem Fenster den Tipp an
        const ua = navigator.userActivation;
        const r = f.getBoundingClientRect();
        if ((ua && !ua.isActive && document.activeElement !== f) || r.width < 100 || r.height < 60) return;
      }
      stretch(f, e.source);
      if (IN_FRAME) fpost(window.parent, { t: 'take', fs: !!d.fs });
      else if (d.fs) frameFs(f);
      sendState();
      return;
    }
    if (fb.child !== f) return;
    if (d.t === 'release') { unstretch(); if (IN_FRAME) fpost(window.parent, { t: 'release' }); return; }
    if (d.t === 'orient') {
      if (IN_FRAME) { fpost(window.parent, { t: 'orient', o: d.o }); return; }
      try {
        if (d.o) screen.orientation.lock(d.o).catch(() => {});
        else if (screen.orientation && screen.orientation.unlock) screen.orientation.unlock();
      } catch (er) {}
      return;
    }
    if (d.t === 'exitfs') {
      if (IN_FRAME) fpost(window.parent, { t: 'exitfs' });
      else { try { if (fsElem() === f) document.exitFullscreen().catch(() => {}); } catch (er) {} }
    }
  });
  const stateSoon = () => { if (fb.child) { sendState(); setTimeout(sendState, 150); setTimeout(sendState, 500); } };
  document.addEventListener('fullscreenchange', stateSoon, true);
  document.addEventListener('webkitfullscreenchange', stateSoon, true);
  addEventListener('resize', stateSoon);
  // Eingebettet: bei der Hauptseite melden (Titel und Ränder kommen zurück)
  if (IN_FRAME) fpost(window.parent, { t: 'hello' });
  // =================================================

  // Gilt für die Seite selbst und alle Unterdomains (z. B. m.youtube.com)
  const onSite = (list) => list.some((d) => {
    const host = location.hostname.toLowerCase();
    d = String(d).toLowerCase().replace(/^\*?\./, '');
    return host === d || host.endsWith('.' + d);
  });
  if (onSite(CONFIG.disabledSites)) return;             // hier ganz aus – nichts wird geladen oder angezeigt
  const DOWNLOAD_ONLY = onSite(CONFIG.downloadOnlySites);
  const SHOW_DOWNLOAD = DOWNLOAD_ONLY || CONFIG.downloadEverywhere || onSite(CONFIG.ytdlpSites);
  let vp = null;   // Zustand des eingebauten Players (muss vor dem ersten Prüfdurchlauf existieren)

  const MEDIA_RE =/\.(m3u8|mpd|mp4|webm|mkv|mov|m4v)(\?|#|$)/i;
  const PLAYLIST_RE = /\.(m3u8|mpd)(\?|#|$)/i;
  const SEGMENT_RE = /(seg|chunk|frag|init)[-_]?\d*\.(mp4|m4s|ts)(\?|$)|[?&](bytestart|byteend|range)=/i;
  const MASTER_RE = /(master|manifest|playlist)|\/index\.m3u8/i;
  // Haupt-Playlist (alle Qualitätsstufen) vor Unter-Playlists (eine Stufe) einordnen:
  // 1. "master" im Namen, 2. "manifest"/"playlist", 3. weniger tief verschachtelt (Haupt-Playlist liegt meist eine Ebene höher)
  function rankPlaylists(list) {
    const score = (u) => {
      let path = u;
      try { path = new URL(u).pathname; } catch (e) {}
      const name = path.split('/').pop() || '';
      const s1 = /master/i.test(name) ? 0 : /(manifest|playlist)/i.test(name) ? 1 : 2;
      return [s1, path.split('/').length];
    };
    return list.map((u, i) => ({ u: u, i: i, s: score(u) }))
      .sort((a, b) => a.s[0] - b.s[0] || a.s[1] - b.s[1] || a.i - b.i)
      .map((x) => x.u);
  }
  const SPLIT_CDN_RE = /(^|\.)(fbcdn\.net|cdninstagram\.com)$/i; // dort sind Netzwerk-Videos nur Bild- ODER Tonspur
  const NS = 'http://www.w3.org/2000/svg';
  const META = /(^|\.)(facebook\.com|instagram\.com|threads\.net|threads\.com|messenger\.com)$/i.test(location.hostname);
  const FB_MIRROR = /(^|\.)facebook\.com$/i.test(location.hostname) && CONFIG.fbMirror !== false;
  // Firefox spiegelt mit mozCaptureStream – dabei wandert der Ton in die Spiegelung (siehe Player: VOL)
  const FF_CAP = !HTMLMediaElement.prototype.captureStream && !!HTMLMediaElement.prototype.mozCaptureStream;
  const GECKO = /\bGecko\/\d/.test(navigator.userAgent) && !/like Gecko/.test(navigator.userAgent);   // Firefox
  // Firefox auf dem Handy: Android liefert Videobilder direkt an die Grafik – auf eine Zeichenfläche kopiert kommen sie
  // leer an (Mozilla-Bug 1479947). Ambient-Licht und Spul-Vorschau zeigen dort deshalb Videos selbst statt Kopien.
  // (Auch bei "Desktop-Seite", daher zusätzlich Touch; "evp-ffmobile" = 1 erzwingt es zum Testen.)
  const FF_MOBILE = GECKO && (/Android|Mobile/.test(navigator.userAgent) || navigator.maxTouchPoints > 0 ||
    (() => { try { return localStorage.getItem('evp-ffmobile') === '1'; } catch (e) { return false; } })());

  // Icons (Material Icons, Apache 2.0)
  const I = {
    play: 'M10 16.5l6-4.5-6-4.5v9zM12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8z',
    open: 'M19 19H5V5h7V3H5c-1.11 0-2 .9-2 2v14c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2v-7h-2v7zM14 3v2h3.59l-9.83 9.83 1.41 1.41L19 6.41V10h2V3h-7z',
    apps: 'M4 8h4V4H4v4zm6 12h4v-4h-4v4zm-6 0h4v-4H4v4zm0-6h4v-4H4v4zm6 0h4v-4h-4v4zm6-10v4h4V4h-4zm-6 4h4V4h-4v4zm6 6h4v-4h-4v4zm0 6h4v-4h-4v4z',
    pip: 'M19 11h-8v6h8v-6zm4 8V4.98C23 3.88 22.1 3 21 3H3c-1.1 0-2 .88-2 1.98V19c0 1.1.9 2 2 2h18c1.1 0 2-.9 2-2zm-2 .02H3V4.97h18v14.05z',
    link: 'M3.9 12c0-1.71 1.39-3.1 3.1-3.1h4V7H7c-2.76 0-5 2.24-5 5s2.24 5 5 5h4v-1.9H7c-1.71 0-3.1-1.39-3.1-3.1zM8 13h8v-2H8v2zm9-6h-4v1.9h4c1.71 0 3.1 1.39 3.1 3.1s-1.39 3.1-3.1 3.1h-4V17h4c2.76 0 5-2.24 5-5s-2.24-5-5-5z',
    download: 'M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z',
    player: 'M20 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm0 14H4V6h16v12zM10 8.5v7l5.5-3.5L10 8.5z',
    share: 'M18 16.08c-.76 0-1.44.3-1.96.77L8.91 12.7c.05-.23.09-.46.09-.7s-.04-.47-.09-.7l7.05-4.11c.54.5 1.25.81 2.04.81 1.66 0 3-1.34 3-3s-1.34-3-3-3-3 1.34-3 3c0 .24.04.47.09.7L8.04 9.81C7.5 9.31 6.79 9 6 9c-1.66 0-3 1.34-3 3s1.34 3 3 3c.79 0 1.5-.31 2.04-.81l7.12 4.16c-.05.21-.08.43-.08.65 0 1.61 1.31 2.92 2.92 2.92s2.92-1.31 2.92-2.92-1.31-2.92-2.92-2.92z',
    more: 'M12 8c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm0 2c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z',
    sd: 'M19 3H5c-1.11 0-2 .9-2 2v14c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm-8 7.5H8.5V10h-2v1h2.5c.55 0 1 .45 1 1v2c0 .55-.45 1-1 1H6v-1.5h2.5v-1H6c-.55 0-1-.45-1-1V9.5c0-.55.45-1 1-1h3.5c.55 0 1 .45 1 1v1zm7 3.5c0 .55-.45 1-1 1h-4.5V8.5H17c.55 0 1 .45 1 1V14zm-4-4h2.5v3.5H14V10z',
    bug: 'M20 8h-2.81c-.45-.78-1.07-1.45-1.82-1.96L17 4.41 15.59 3l-2.17 2.17C12.96 5.06 12.49 5 12 5s-.96.06-1.41.17L8.41 3 7 4.41l1.62 1.63C7.88 6.55 7.26 7.22 6.81 8H4v2h2.09c-.05.33-.09.66-.09 1v1H4v2h2v1c0 .34.04.67.09 1H4v2h2.81c1.04 1.79 2.97 3 5.19 3s4.15-1.21 5.19-3H20v-2h-2.09c.05-.33.09-.66.09-1v-1h2v-2h-2v-1c0-.34-.04-.67-.09-1H20V8zm-6 8h-4v-2h4v2zm0-4h-4v-2h4v2z',
  };

  // ---------- Hilfen ----------
  function unesc(s) {
    return s.replace(/\\\//g, '/').replace(/\\u0025/gi, '%').replace(/\\u0026/gi, '&').replace(/\\u003d/gi, '=').replace(/&amp;/g, '&');
  }
  function hostOf(u) { try { return new URL(u).hostname; } catch (e) { return ''; } }

  // Facebook/Instagram schreiben in den Parameter "efg" (Base64-JSON), wie die Datei kodiert ist:
  // "progressive" = komplettes Video mit Ton, "dash…" = nur Bild- ODER nur Tonspur.
  function efgInfo(u) {
    try {
      const e = new URL(u).searchParams.get('efg');
      if (!e) return '';
      let s = e.replace(/-/g, '+').replace(/_/g, '/');
      while (s.length % 4) s += '=';
      return atob(s);
    } catch (err) { return ''; }
  }
  function encTag(u) {
    const m = efgInfo(u).match(/"vencode_tag"\s*:\s*"([^"]+)"/);
    return m ? m[1] : '';
  }
  function isSplitTrack(u) {
    if (!u || !SPLIT_CDN_RE.test(hostOf(u))) return false;
    if (SEGMENT_RE.test(u)) return true;
    const info = efgInfo(u).toLowerCase();
    if (!info || info.indexOf('progressive') >= 0) return false;
    return /dash|audio|video_only/.test(info);
  }
  const fullUrl = (x) => typeof x === 'string' && x.startsWith('http') && !isSplitTrack(x);

  // ---------- 1) Netzwerk-Quellen (HLS/DASH/MP4) ----------
  let sources = [];               // [{u, t}]
  let navAt = 0;
  let bodiesRead = 0;
  const isMedia = (u) => typeof u === 'string' && MEDIA_RE.test(u) && !SEGMENT_RE.test(u);

  // Facebook/Instagram: Welches Video lädt der Web-Player gerade wirklich?
  // Die nachgeladenen Bild-/Tonstücke tragen die Video-ID in ihrer Adresse.
  const assetHits = new Map();   // Video-ID -> [Zeitpunkte]
  function assetOf(u) {
    try {
      const j = JSON.parse(efgInfo(u) || '{}') || {};
      const a = String(j.xpv_asset_id || j.asset_id || j.video_id || '');
      return a && a !== 'null' && a !== 'undefined' ? a : '';
    } catch (e) { return ''; }
  }
  // Jede Stück-Adresse nur einmal zählen: Sie wird beim Anfragen UND beim Fertigladen gemeldet.
  // Die zweite Meldung setzt nur den Zeitpunkt auf "angekommen" (wichtig für die Puffer-Zuordnung).
  const splitSeen = new Map();   // Adresse -> Zeitpunkt
  // arrived = echte Ankunftszeit (aus der Ladezeiten-Messung), sonst "jetzt"
  function noteSplit(u, arrived) {
    const a = assetOf(u);
    if (!a) return;
    const now = arrived > 0 && arrived <= Date.now() + 50 ? arrived : Date.now();
    let arr = assetHits.get(a);
    if (splitSeen.has(u)) {
      if (arr && arrived > 0) {
        const i = arr.lastIndexOf(splitSeen.get(u)); if (i >= 0) arr[i] = now;
        arr.lastT = Math.max(arr.lastT || 0, now);
        splitSeen.set(u, now);
      }
      return;
    }
    splitSeen.set(u, now);
    if (splitSeen.size > 4000) splitSeen.delete(splitSeen.keys().next().value);
    if (!arr) { arr = []; arr.total = 0; arr.first = now; assetHits.set(a, arr); }
    arr.push(now);
    arr.lastT = Math.max(arr.lastT || 0, now);
    arr.total++;
    if (arr.length > 60) arr.shift();
    if (assetHits.size > 200) assetHits.delete(assetHits.keys().next().value);
  }
  function seeUrl(u, arrived) {
    if (typeof u !== 'string' || u.indexOf('http') !== 0) return;
    if (SPLIT_CDN_RE.test(hostOf(u))) { noteSplit(u, arrived); return; }
    if (isMedia(u)) addSource(u);
  }
  // Das Video, von dem in den letzten 20 Sekunden am meisten geladen wurde
  // Wie oft wurde von diesem Video seit dem Start des aktuellen Videos geladen?
  function hitCount(a, since) {
    const arr = assetHits.get(a) || [];
    const from = Math.max(Date.now() - 20000, (since || 0) - 300);
    return arr.filter((t) => t >= from).length;
  }
  function currentAsset(since) {
    const now = Date.now();
    // nur Ladevorgänge NACH dem Start dieses Videos zählen – sonst erwischt man beim Wischen das vorherige
    const from = Math.max(now - 20000, (since || 0) - 300);
    let best = '', bestN = 0, bestLast = 0;
    assetHits.forEach((arr, a) => {
      const n = arr.filter((t) => t >= from).length;
      const last = arr[arr.length - 1] || 0;
      if (n > bestN || (n === bestN && n > 0 && last > bestLast)) { best = a; bestN = n; bestLast = last; }
    });
    if (!best) {   // nichts in den letzten 20 s: zuletzt geladenes Video der letzten Minute
      assetHits.forEach((arr, a) => {
        const last = arr[arr.length - 1] || 0;
        if (last >= Math.max(now - 60000, (since || 0) - 300) && last > bestLast) { best = a; bestLast = last; }
      });
    }
    return best;
  }
  // Facebook lädt beim Scrollen schon die ersten Sekunden der NÄCHSTEN Videos im Feed vor – als kurzen Schwall.
  // Alles aus den ersten 2 Sekunden nach dem ersten Auftauchen eines Videos zählt deshalb nicht (egal wie viele Stücke):
  // nur was danach nachgeladen wird, ist echtes Abspielen.
  const PRELOAD_MS = 2000;
  function effectiveHits(arr, from) {
    const after = (arr.first || 0) + PRELOAD_MS;
    let n = 0;
    for (let i = 0; i < arr.length; i++) if (arr[i] >= from && arr[i] > after) n++;
    return n;
  }
  const playAt = new WeakMap();   // wann dieses Video zuletzt (wieder) zu spielen begann
  document.addEventListener('play', (e) => { const v = e.target; if (v && v.tagName === 'VIDEO') playAt.set(v, Date.now()); }, true);
  // Stärkstes Zeichen: Nur die Stücke des LAUFENDEN Videos landen auch im Puffer dieses Video-Elements.
  // Vorgeladene Nachbar-Reels werden nur geholt, aber nicht eingespeist. Wächst der Puffer, bekommt das Video einen
  // Punkt, dessen Stück gerade eben angekommen ist.
  const bufCredit = new WeakMap();   // Video-Element -> [{t, a: [Video-IDs]}]
  const bufPrev = new WeakMap();
  function bufTotal(v) {
    let n = 0;
    try { const b = v.buffered; for (let i = 0; i < b.length; i++) n += b.end(i) - b.start(i); } catch (e) {}
    return n;
  }
  function watchBuffers() {
    if (document.hidden || !assetHits.size) return;
    const now = Date.now();
    const vids = document.getElementsByTagName('video');
    for (let i = 0; i < vids.length; i++) {
      const v = vids[i];
      if (v.paused && !v.seeking) { bufPrev.set(v, bufTotal(v)); continue; }
      const cur = bufTotal(v), prev = bufPrev.get(v);
      bufPrev.set(v, cur);
      if (prev == null || cur <= prev + 0.15) continue;
      // Punkt nach Nähe aufteilen: was unmittelbar vorher ankam, bekommt fast alles (ein Stück von vor 1 s fast nichts)
      const w = [];
      let sum = 0;
      assetHits.forEach((arr, a) => {
        const dt = now - (arr.lastT || 0);
        if (dt < 2000) { const x = Math.exp(-dt / 350); w.push([a, x]); sum += x; }
      });
      if (!w.length || sum <= 0) continue;
      let list = bufCredit.get(v);
      if (!list) { list = []; bufCredit.set(v, list); }
      list.push({ t: now, w: w.map((e) => [e[0], e[1] / sum]) });
      if (list.length > 80) list.shift();
    }
  }
  // oft genug, um Einspeisen und Ankunft sauber zu trennen – nur wo es gebraucht wird (Facebook spiegelt sonst ohnehin)
  if (META && !FB_MIRROR) setInterval(watchBuffers, 100);
  ['loadstart', 'emptied'].forEach((t) => document.addEventListener(t, (e) => {
    const v = e.target; if (v && v.tagName === 'VIDEO') { bufCredit.delete(v); bufPrev.delete(v); }
  }, true));
  function creditsFor(v, from) {
    const m = new Map();
    (bufCredit.get(v) || []).forEach((c) => {
      if (c.t < from) return;
      c.w.forEach((e) => m.set(e[0], (m.get(e[0]) || 0) + e[1]));
    });
    return m;
  }
  // Welches Facebook-Video zeigt GENAU dieses Element? sure = sicher erkannt
  function assetFor(v) {
    if (!v) return { asset: '', sure: false, via: '-' };
    const now = Date.now();
    // 1) Video-ID aus dem Beitrag rund um das Video, die gerade auch geladen wird – am sichersten
    try {
      const live = videoIdsFor(v).filter((id) => { const arr = assetHits.get(id); return arr && arr[arr.length - 1] >= now - 90000; });
      if (live.length === 1) return { asset: live[0], sure: true, via: 'Beitrag + Netzwerk' };
    } catch (e) {}
    const since = Math.max(startAt.get(v) || 0, playAt.get(v) || 0);
    const from = Math.max(now - 20000, since - 300);
    // 2) Puffer-Zuordnung: welches Video wird wirklich in DIESES Element eingespeist?
    const cr = creditsFor(v, Math.max(now - 30000, (startAt.get(v) || 0) - 300));
    let cb = '', c1 = 0, c2 = 0;
    cr.forEach((n, a) => { if (n > c1) { c2 = c1; c1 = n; cb = a; } else if (n > c2) c2 = n; });
    if (cb && c1 >= 1.5 && c1 >= c2 * 1.8) return { asset: cb, sure: true, via: 'Puffer (' + Math.round(c1) + ':' + Math.round(c2) + ')' };
    // 3) Echtes Abspielen seit dem Start: ohne die vorgeladenen ersten Stücke
    let best = '', b1 = 0, b2 = 0;
    assetHits.forEach((arr, a) => {
      const n = effectiveHits(arr, from);
      if (n > b1) { b2 = b1; b1 = n; best = a; } else if (n > b2) b2 = n;
    });
    if (best && b1 >= 2 && b1 >= b2 * 2) return { asset: best, sure: true, via: 'Netzwerk (ohne Vorladen)' };
    // 4) unsicher: lieber die Puffer-Spur als die reine Menge (die Menge gewinnt oft ein vorgeladenes Nachbar-Reel)
    if (cb && c1 > c2) return { asset: cb, sure: false, via: 'Puffer (unsicher ' + Math.round(c1) + ':' + Math.round(c2) + ')' };
    return { asset: currentAsset(startAt.get(v) || 0), sure: false, via: 'Netzwerk (unsicher)' };
  }

  function addSource(raw) {
    let u;
    try { u = new URL(raw, location.href).href; } catch (e) { return; }
    if (!/^https?:/i.test(u) || SEGMENT_RE.test(u) || SPLIT_CDN_RE.test(hostOf(u))) return;
    if (sources.some((s) => s.u === u)) return;
    sources.push({ u: u, t: Date.now() });
    if (sources.length > 40) sources.shift();
  }

  try {
    new PerformanceObserver((list) => {
      list.getEntries().forEach((e) => seeUrl(e.name, e.responseEnd > 0 && performance.timeOrigin ? performance.timeOrigin + e.responseEnd : 0));
    }).observe({ type: 'resource', buffered: true });
  } catch (e) {}

  const API_RE = /graphql|\/api\//i;
  let rawFetch = null;   // ungehaktes fetch – für eigene Abfragen, die NICHT mitgelesen werden sollen
  try {
    const of = window.fetch;
    rawFetch = of ? of.bind(window) : null;
    // Firefox: Abfragen eines Erweiterungs-Skripts laufen sonst als Erweiterung (andere Herkunft) –
    // content.fetch fragt wie die Seite selbst (nötig z. B. für Instagram/TikTok)
    try { if (typeof content !== 'undefined' && content && typeof content.fetch === 'function') rawFetch = content.fetch.bind(content); } catch (e) {}
    if (of) {
      window.fetch = function (input) {
        let u = '';
        try { u = typeof input === 'string' ? input : (input && input.url) || ''; seeUrl(u); } catch (e) {}
        const p = of.apply(this, arguments);
        if (META && API_RE.test(u)) {
          p.then((res) => { try { res.clone().text().then((t) => { bodiesRead++; scanText(t); }, () => {}); } catch (e) {} }, () => {});
        }
        return p;
      };
    }
    const oo = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function (m, url) {
      try {
        const u = String(url);
        seeUrl(u);
        if (META && API_RE.test(u)) {
          this.addEventListener('load', function () {
            try {
              bodiesRead++;
              if (!this.responseType || this.responseType === 'text') scanText(this.responseText);
              else if (this.responseType === 'json') walk(this.response, [], 0);
            } catch (e) {}
          });
        }
      } catch (e) {}
      return oo.apply(this, arguments);
    };
  } catch (e) {}

  let lastHref = location.href;
  function checkNav() {
    if (location.href !== lastHref) { lastHref = location.href; navAt = Date.now() - 1500; }
  }

  function recent() {
    const r = sources.filter((s) => s.t >= navAt);
    return (r.length ? r : sources).map((s) => s.u);
  }

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
    const s = startAt.get(v);
    if (!s) return '';
    // Haupt-Playlists werden oft schon vor dem Videostart geladen – darum weiter zurückschauen
    const c = sources.filter((x) => x.t >= s - 15000 && x.t <= s + 6000).map((x) => x.u).filter((u) => PLAYLIST_RE.test(u));
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
    r.list = list.slice(0, 8);
    if (r.list.length) { r.best = r.list[0].u; r.how = r.list[0].how; }
    return r;
  }

  // ---------- An externen Player übergeben ----------
  function intentUrl(u, pkg, fallback) {
    const x = new URL(u);
    const title = (pageTitle() || 'Video').slice(0, 120);
    return 'intent://' + x.host + x.pathname + x.search +
      '#Intent;scheme=' + x.protocol.slice(0, -1) +
      ';action=android.intent.action.VIEW;type=video/*;' +
      (pkg ? 'package=' + pkg + ';' : '') +
      (fallback ? 'S.browser_fallback_url=' + encodeURIComponent(fallback) + ';' : '') +
      'S.title=' + encodeURIComponent(title) + ';end';
  }
  // YTDLnis nimmt Links jeder Seite direkt an (als "Video ansehen" mit Paketname) – ohne Teilen-Menü.
  // Ist YTDLnis nicht installiert, öffnet sich dessen Download-Seite.
  const YTDLNIS = 'com.deniscerri.ytdl';
  function toYtdlnis(link) {
    if (!link) return false;
    openExternal(link, null, YTDLNIS, 'An YTDLnis übergeben …', 'https://github.com/deniscerri/ytdlnis/releases/latest');
    return true;
  }
  // ---------- Pornhub: komplette MP4-Dateien mit Ton über die eigene Abfrage der Seite ----------
  // (der HLS-Stream ist dort an den Web-Player gebunden und bleibt im externen Player schwarz)
  const PH = /(^|\.)pornhub\.(com|org|net)$/i.test(location.hostname);
  let phCache = null, phPending = null, phLog = '-', phHref = '';
  function phMp4() {
    if (!PH) return null;
    if (phHref !== location.href) { phHref = location.href; phCache = null; phPending = null; }   // anderes Video
    if (phCache) return null;
    if (phPending) return phPending;
    let api = '';
    const sc = document.getElementsByTagName('script');
    for (let i = 0; i < sc.length && !api; i++) {
      const t = sc[i].textContent;
      if (!t || t.indexOf('get_media') < 0) continue;
      const m = t.match(/"videoUrl"\s*:\s*"(https?:[^"]*get_media[^"]*)"/);
      if (m) api = unesc(m[1]);
    }
    if (!api) { phLog = 'keine MP4-Abfrage auf der Seite gefunden'; phCache = []; return null; }
    phPending = fetch(api, { credentials: 'include' })
      .then((r) => r.json())
      .then((arr) => {
        const list = (Array.isArray(arr) ? arr : [])
          .filter((x) => x && typeof x.videoUrl === 'string' && x.videoUrl && (x.format === 'mp4' || /\.mp4/i.test(x.videoUrl)))
          .map((x) => ({ u: x.videoUrl, res: parseInt(Array.isArray(x.quality) ? x.quality[0] : x.quality, 10) || 0 }))
          .sort((a, b) => b.res - a.res);
        phCache = list;
        phLog = list.length ? list.length + ' MP4 gefunden (max ' + list[0].res + 'p)' : 'Abfrage ohne MP4';
        phPending = null;
        return list;
      })
      .catch((e) => { phLog = 'Fehler: ' + ((e && e.message) || e); phCache = []; phPending = null; return []; });
    return phPending;
  }

  let lastOpen = { v: null, t: 0, idx: 0 };
  let lastResolve = '-';

  // Gehört die Adresse zur Seite selbst (gleiche Domain)? Dann ist sie oft an die Browser-Sitzung gebunden.
  function baseDomain(host) {
    const p = String(host).toLowerCase().split('.');
    return p.slice(-2).join('.');
  }
  function needsResolve(u) {
    if (!u || PLAYLIST_RE.test(u) || META) return false;
    const h = hostOf(u);
    return !!h && baseDomain(h) === baseDomain(location.hostname);
  }
  // Violentmonkey/Tampermonkey: darf Weiterleitungen zu anderen Servern verfolgen und die Endadresse lesen
  function gmXhr() {
    try { if (typeof GM_xmlhttpRequest === 'function') return GM_xmlhttpRequest; } catch (e) {}
    try { if (typeof GM !== 'undefined' && GM && typeof GM.xmlHttpRequest === 'function') return GM.xmlHttpRequest; } catch (e) {}
    return null;
  }
  // ---------- Helfer-App: echte Helligkeit und Lautstärke des Handys ----------
  // Die App lauscht nur auf dem Handy selbst (127.0.0.1). Ohne App kommt schlicht keine Antwort – dann bleibt alles wie bisher.
  const HELPER = 'http://127.0.0.1:47811';
  // Schlüssel: nach dem Koppeln (Knopf „Verbinden“ in der Helfer-App) der eigene Zufallsschlüssel der App –
  // dauerhaft gespeichert, übersteht auch Updates des Skripts. Vorher der alte feste Schlüssel.
  let helperKey = 'evp-1';
  try { if (typeof GM_getValue === 'function') helperKey = GM_getValue('helperKey', '') || 'evp-1'; } catch (e) {}
  // to = Wartezeit in ms (Abholen beim Laden über den Browser wartet bis zu 8 s), body = Daten für POST /push
  function helper(path, to, body, keyOverride) {
    to = to || 1200;
    return new Promise((res) => {
      const x = CONFIG.systemHelper === false ? null : gmXhr();
      if (!x) { res(null); return; }
      let done = false;
      const fin = (v) => { if (!done) { done = true; res(v); } };
      try {
        x({
          method: body ? 'POST' : 'GET', url: HELPER + path, headers: { 'X-EVP-Key': keyOverride || helperKey }, timeout: to, anonymous: true,
          data: body || undefined,
          onload: (r) => { try { fin(r.status === 200 ? JSON.parse(r.responseText) : null); } catch (e) { fin(null); } },
          onerror: () => fin(null), ontimeout: () => fin(null), onabort: () => fin(null),
        });
      } catch (e) { fin(null); }
      setTimeout(() => fin(null), to + 400);
    });
  }
  // ---------- Laden über den Browser (für Server, die nur dem eingeloggten Browser etwas geben) ----------
  // Der Helfer sagt, was er braucht (/pull) – das Skript lädt es mit der Sitzung des Browsers und liefert (/push).
  // Läuft, solange die Seite offen ist (der Player darf zu sein).
  // anon = ohne Cookies: für Adressen fremder Seiten (eine bösartige Playlist darf nicht mit deiner Sitzung woanders abrufen)
  function gmGet(u, off, len, sizeOnly, anon) {
    return new Promise((res) => {
      const x = gmXhr();
      if (!x) { res({ st: 502 }); return; }
      const headers = { Referer: location.href };
      if (sizeOnly) headers.Range = 'bytes=0-0';
      else if (off >= 0 && len > 0) headers.Range = 'bytes=' + off + '-' + (off + len - 1);
      try {
        x({
          method: 'GET', url: u, headers: headers, responseType: 'arraybuffer', timeout: 60000, anonymous: !!anon,
          onload: (r) => {
            let total = -1;
            const m = /content-range:\s*bytes\s+\d+-\d+\/(\d+)/i.exec(r.responseHeaders || '');
            if (m) total = +m[1];
            else if (sizeOnly && r.status === 200) { const l = /content-length:\s*(\d+)/i.exec(r.responseHeaders || ''); if (l) total = +l[1]; }
            res({ st: r.status, total: total, body: sizeOnly ? null : r.response });
          },
          onerror: () => res({ st: 502 }), ontimeout: () => res({ st: 504 }), onabort: () => res({ st: 499 }),
        });
      } catch (e) { res({ st: 502 }); }
    });
  }
  const proxyJobs = new Set();
  // allow = Domains, an die die Browser-Sitzung (Cookies) mitgehen darf: die Seite selbst und der Server des Videos
  async function proxyLoop(job, allow) {
    if (proxyJobs.has(job)) return;
    const ok = (u) => { const d = baseDomain(hostOf(u)); return !!d && (allow || []).indexOf(d) >= 0; };
    proxyJobs.add(job);
    try {
      let idle = 0;
      while (true) {
        const r = await helper('/pull?job=' + job, 12000);
        if (!r) { if (++idle > 5) break; await new Promise((ok) => setTimeout(ok, 1000)); continue; }
        idle = 0;
        if (r.done) break;
        await Promise.all((r.reqs || []).map(async (q) => {
          if (!/^https?:\/\//i.test(q.u)) { await helper('/push?job=' + job + '&id=' + q.id + '&st=400&total=-1', 60000, new ArrayBuffer(0)); return; }
          const g = await gmGet(q.u, q.o, q.l, q.s, !ok(q.u));
          const st = g.st || 502;
          const body = g.body && st < 400 ? g.body : new ArrayBuffer(0);
          await helper('/push?job=' + job + '&id=' + q.id + '&st=' + st + '&total=' + (g.total || -1), 60000, body);
        }));
      }
    } finally {
      proxyJobs.delete(job);
    }
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
          url: u,
          headers: { Range: 'bytes=0-1', Referer: location.href },   // wie der Web-Player der Seite
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
        headers: { Range: 'bytes=0-1' },
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
  function openExternal(u, v, pkg, msg, fallback) {
    if (!u) { toast('Keine Quelle für dieses Video gefunden'); return; }
    try { if (v) v.pause(); } catch (e) {}
    toast(msg || 'Wird im Videoplayer geöffnet …');
    const intent = intentUrl(u, pkg, fallback);
    lastIntent = { url: u, t: Date.now(), how: '' };
    // Weg 1: wie ein angetippter Link – funktioniert auch aus dem abgeschirmten Bereich zuverlässig
    let left = false;
    const onLeave = () => { left = true; };
    addEventListener('blur', onLeave, true);
    document.addEventListener('visibilitychange', onLeave, true);
    try {
      const a = document.createElement('a');
      a.href = intent;
      a.rel = 'noreferrer';
      a.style.cssText = 'display:none';
      (root || document.documentElement).appendChild(a);
      a.click();
      a.remove();
      lastIntent.how = 'Link';
    } catch (e) { lastIntent.how = 'Link-Fehler'; }
    // Weg 2: Hat sich nach kurzer Zeit nichts getan (keine App, keine Auswahl), direkt navigieren
    setTimeout(() => {
      removeEventListener('blur', onLeave, true);
      document.removeEventListener('visibilitychange', onLeave, true);
      if (left || document.hidden) { lastIntent.how += ' → Browser verlassen ✓'; return; }
      try { location.href = intent; lastIntent.how += ' → Weg 2'; } catch (e) { lastIntent.how += ' → Weg 2 Fehler'; }
      setTimeout(() => {
        if (!document.hidden && document.hasFocus()) {
          lastIntent.how += ' → nichts passiert';
          toast('Player hat sich nicht geöffnet – lange auf „Player" drücken und Diagnose schicken');
        }
      }, 1200);
    }, 900);
  }
  // ---------- Direkter Download einer Videodatei ----------
  function fileNameFor(u) {
    const ext = ((String(u).split(/[?#]/)[0].match(/\.(mp4|webm|mov|m4v|mkv)$/i) || [0, 'mp4'])[1]).toLowerCase();
    const base = String(pageTitle() || pageHost()).replace(/[\\/:*?"<>|\u0000-\u001f]+/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 80) || 'video';
    return base + '.' + ext;
  }
  function saveBlob(blob, name) {
    const bu = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = bu; a.download = name; a.style.display = 'none';
    (document.body || document.documentElement).appendChild(a);
    a.click();
    setTimeout(() => { a.remove(); URL.revokeObjectURL(bu); }, 120000);
  }
  // Download über den Browser selbst (läuft im Download-Manager weiter, Seite darf zu).
  // Geht nur, wenn die Datei von derselben Seite kommt oder ihr Server sie als Download kennzeichnet.
  function nativeDownload(u, name) {
    const a = document.createElement('a');
    a.href = u; a.download = name; a.rel = 'noopener'; a.style.display = 'none';
    (document.body || document.documentElement).appendChild(a);
    a.click();
    setTimeout(() => a.remove(), 2000);
  }
  function sameOrigin(u) { try { return new URL(u, location.href).origin === location.origin; } catch (e) { return false; } }
  // Nur die Kopfzeilen abfragen (1 Byte): sagt der Server "Anhang/Download"?
  function probeAttachment(u) {
    return new Promise((resolve) => {
      const x = gmXhr();
      if (!x) { resolve(false); return; }
      let done = false, req = null;
      const fin = (v) => { if (done) return; done = true; clearTimeout(t); try { req && req.abort && req.abort(); } catch (e) {} resolve(v); };
      const t = setTimeout(() => fin(false), 6000);
      const look = (r) => {
        const hd = String((r && r.responseHeaders) || '');
        if (!hd) return;
        const ok = r.status >= 200 && r.status < 400;
        fin(ok && (/content-disposition:\s*attachment/i.test(hd) || /content-type:\s*application\/(octet-stream|force-download|x-download)/i.test(hd)));
      };
      try {
        req = x({ method: 'GET', url: u, headers: { Range: 'bytes=0-0', Referer: location.href },
          onreadystatechange: (r) => { if (r && r.readyState >= 2) look(r); },
          onload: (r) => { look(r); fin(false); }, onerror: () => fin(false), ontimeout: () => fin(false) });
      } catch (e) { fin(false); }
    });
  }
  // true = Browser hat den Download übernommen
  async function browserDownload(u) {
    const name = fileNameFor(u);
    if (sameOrigin(u)) { nativeDownload(u, name); return true; }
    const cands = [];
    // Viele Video-Seiten (KVS, "get_file") liefern mit download=true die Datei als Download aus
    if (/\/get_file\//i.test(u) && !/[?&]download=/i.test(u)) cands.push(u + (u.indexOf('?') < 0 ? '?' : '&') + 'download=true&download_filename=' + encodeURIComponent(name));
    cands.push(u);
    for (const c of cands) { if (await probeAttachment(c)) { nativeDownload(c, name); return true; } }
    return false;
  }
  let dlBusy = false;
  function directDownload(u, onFail) {
    if (dlBusy) { toast('Download läuft schon …'); return; }
    dlBusy = true;
    const name = fileNameFor(u);
    let lastPct = -10;
    const prog = (e) => {
      if (!e || !(e.total > 0)) return;
      const pc = Math.floor(e.loaded / e.total * 100);
      if (pc >= lastPct + 10) { lastPct = pc; toast('Lade herunter … ' + pc + ' %'); }
    };
    const ok = (blob) => { dlBusy = false; saveBlob(blob, name); toast('Gespeichert in Downloads: ' + name); };
    const fail = () => { dlBusy = false; if (onFail) onFail(); };
    const big = (b) => b && b.size > 20000;   // winzige Antworten sind Fehlerseiten, kein Video
    // 2. Versuch: normal über die Seite (klappt bei Dateien derselben Seite oder mit Freigabe)
    const viaPage = () => {
      fetch(u, { credentials: 'include' }).then((r) => (r.ok ? r.blob() : Promise.reject(r.status)))
        .then((b) => (big(b) ? ok(b) : fail()), fail);
    };
    toast('Download startet …');
    // 1. Versuch: über Violentmonkey – darf auch fremde Server und schickt die Seite als Herkunft mit
    const x = gmXhr();
    if (!x) { viaPage(); return; }
    try {
      x({ method: 'GET', url: u, responseType: 'blob', headers: { Referer: location.href }, timeout: 0,
        onprogress: prog,
        onload: (r) => { const b = r && r.response; if (r && r.status >= 200 && r.status < 300 && big(b)) ok(b); else viaPage(); },
        onerror: viaPage, onabort: fail });
    } catch (e) { viaPage(); }
  }

  let lastIntent = { url: '', t: 0, how: '' };
  function copyText(text, okMsg) {
    const ok = () => toast(okMsg);
    const fallback = () => {
      try {
        const ta = document.createElement('textarea');
        ta.value = text; ta.style.cssText = 'position:fixed;opacity:0';
        document.documentElement.appendChild(ta); ta.select();
        document.execCommand('copy'); ta.remove(); ok();
      } catch (e) { toast('Kopieren nicht möglich'); }
    };
    if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(text).then(ok, fallback);
    else fallback();
  }


  // ---------- Oberfläche: ein schwebender "Player"-Button ----------
  const CSS = `
:host{all:initial;color-scheme:only light !important;--font:system-ui,-apple-system,"SamsungOne",Roboto,sans-serif}
*{color-scheme:only light !important;forced-color-adjust:none}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
.pill{position:fixed;top:0;left:0;display:flex;align-items:center;gap:2px;height:48px;padding:0 3px;border-radius:24px;
 background:rgba(28,30,40,.36);
 -webkit-backdrop-filter:var(--lens,blur(0px)) blur(var(--gb,12px)) saturate(1.4) brightness(1.1);backdrop-filter:var(--lens,blur(0px)) blur(var(--gb,12px)) saturate(1.4) brightness(1.1);
 color:#fff;box-shadow:0 6px 20px rgba(0,0,0,.28),0 1px 3px rgba(0,0,0,.18);
 opacity:0;transform:translateY(-4px) scale(.96);transform-origin:top right;transition:opacity .22s ease,transform .32s cubic-bezier(.34,1.56,.64,1);
 pointer-events:none;z-index:2147483647;-webkit-user-select:none;user-select:none;-webkit-touch-callout:none}
/* Weiße Glaskante rundum: oben links und unten rechts hell, mit leichtem Farbsaum – auch auf dunklen Seiten sichtbar */
/* Helle Glasteile werden dunkel gezeichnet und per invert(1) umgedreht: so bleiben sie weiß, auch wenn der Browser
   Webseiten zwangsweise abdunkelt (Vivaldi/Chrome "Dunkle Webseiten" dunkelt nur helle Farben ab, dunkle lässt er stehen) */
.pill::before{content:"";position:absolute;inset:0;z-index:1;border-radius:inherit;padding:.8px;filter:invert(1);background:linear-gradient(135deg,rgba(0,0,0,.66) 0%,rgba(0,0,0,.26) 12%,rgba(0,0,0,0) 30%,rgba(0,0,0,0) 68%,rgba(50,27,0,.10) 82%,rgba(0,0,0,.32) 100%);-webkit-mask:linear-gradient(#000 0 0) content-box,linear-gradient(#000 0 0);-webkit-mask-composite:xor;mask-composite:exclude;pointer-events:none}
/* Glanz oben links + Lichtsaum unten rechts */
.pill::after{content:"";position:absolute;inset:0;border-radius:inherit;filter:invert(1);background:radial-gradient(85% 55% at 24% 0%,rgba(0,0,0,.15),rgba(0,0,0,0) 60%),radial-gradient(55% 45% at 80% 108%,rgba(0,0,0,.08),rgba(0,0,0,0) 65%),linear-gradient(180deg,rgba(0,0,0,.07),rgba(0,0,0,.02) 50%,rgba(0,0,0,.05));box-shadow:inset 0 1px 0 rgba(0,0,0,.22),inset 0 -1px 0 rgba(0,0,0,.06),inset 0 0 12px rgba(0,0,0,.05);pointer-events:none}
.pill.on{opacity:1;transform:none;pointer-events:auto;touch-action:none}
.pill.on.scrolling{opacity:0;transform:translateY(-4px) scale(.96);pointer-events:none;transition:opacity .12s ease,transform .12s ease}
.pill.snap{transition:left .24s cubic-bezier(.2,.8,.2,1),top .24s cubic-bezier(.2,.8,.2,1),opacity .2s ease,transform .3s cubic-bezier(.34,1.56,.64,1)}
.pill.drag{opacity:1 !important;transform:scale(1.06);box-shadow:0 16px 36px rgba(0,0,0,.40);transition:none}
.pill .pb{all:unset;position:relative;z-index:1;display:flex;align-items:center;justify-content:center;width:42px;height:42px;border-radius:21px;cursor:pointer;color:#fff;-webkit-touch-callout:none;transition:transform .28s cubic-bezier(.34,1.56,.64,1),background .15s ease}
.pill .pb:active{transform:scale(.86)}
.pill:active{-webkit-backdrop-filter:var(--lens,blur(0px)) blur(var(--gb,12px)) saturate(1.6) brightness(1.45);backdrop-filter:var(--lens,blur(0px)) blur(var(--gb,12px)) saturate(1.6) brightness(1.45)}
.pill .pb:focus-visible{outline:2px solid #9BE7FF;outline-offset:1px}
.pill svg{width:23px;height:23px;fill:currentColor;flex:none;filter:drop-shadow(0 1px 1.5px rgba(0,0,0,.55)) drop-shadow(0 0 3px rgba(0,0,0,.25))}
/* feine Trennlinie zwischen den zwei Symbolen */
.pill .dl::after{content:"";position:absolute;right:-1px;top:11px;bottom:11px;width:1px;background:linear-gradient(transparent,rgba(255,255,255,.28),transparent)}
/* HD gefunden: kleiner eisblauer Punkt */
.pill .hdot{position:absolute;top:8px;right:7px;width:7px;height:7px;border-radius:50%;background:#641800;box-shadow:0 0 8px rgba(100,24,0,.9);filter:invert(1);opacity:0;transform:scale(.4);transition:opacity .2s ease,transform .3s cubic-bezier(.34,1.56,.64,1)}
.pill.hd .hdot{opacity:1;transform:none}
@media (prefers-reduced-motion:reduce){.pill,.pill .pb,.pill .hdot{transition:opacity .15s ease !important}}
.diag{position:fixed;left:10px;right:10px;top:10%;bottom:10%;display:flex;flex-direction:column;gap:8px;background:#161616;color:#eee;border-radius:18px;padding:12px;font:13px/1.35 var(--font);box-shadow:0 10px 40px rgba(0,0,0,.5);z-index:2147483647;pointer-events:auto}
.diag .dh{font-weight:700;font-size:15px}
.diag textarea{flex:1;width:100%;background:#000;color:#9f9;border:0;border-radius:10px;padding:8px;font:11px/1.35 monospace;resize:none}
.diag .db{display:flex;gap:8px;justify-content:flex-end}
.diag button{all:unset;background:#3478f6;color:#fff;border-radius:16px;padding:8px 16px;font:600 14px var(--font);cursor:pointer}
.toast{position:fixed;left:50%;bottom:72px;transform:translate(-50%,10px);background:rgba(100,108,130,.44);-webkit-backdrop-filter:blur(18px) saturate(1.4) brightness(1.12);backdrop-filter:blur(18px) saturate(1.4) brightness(1.12);box-shadow:0 10px 28px rgba(0,0,0,.5);text-shadow:0 1px 2px rgba(0,0,0,.45);color:#fff;font:500 14px/1.3 var(--font);padding:11px 18px;border-radius:22px;opacity:0;transition:opacity .2s,transform .2s;pointer-events:none;max-width:86vw;text-align:center;z-index:2147483647}
.toast::before,.toast::after{content:"";position:absolute;inset:0;border-radius:inherit;z-index:-1;filter:invert(1);pointer-events:none}
.toast::before{border:1px solid rgba(0,0,0,.62);z-index:0}
.toast::after{background:linear-gradient(155deg,rgba(0,0,0,.34),rgba(0,0,0,.14) 45%,rgba(0,0,0,.2));box-shadow:0 0 24px rgba(0,0,0,.18),inset 0 1px 1px rgba(0,0,0,.7)}
.toast.on{opacity:1;transform:translate(-50%,0)}
/* Design „Schlicht“ (Einstellungen → Design): helles Milchglas wie Android One UI – keine Glaskante, kein Glanz,
   keine Lupe; das Bild dahinter scheint hell und farbig durch, Schrift/Symbole mit leichtem Schatten gut lesbar */
:host(.flat) .g,:host(.flat) .vb.g,:host(.flat) .pill,:host(.flat) .toast{background:rgba(255,255,255,.17)!important;
 -webkit-backdrop-filter:blur(22px) saturate(1.7) brightness(1.06)!important;backdrop-filter:blur(22px) saturate(1.7) brightness(1.06)!important;
 box-shadow:0 2px 14px rgba(0,0,0,.16)!important}
:host(.flat) .g::before,:host(.flat) .g::after,:host(.flat) .pill::before,:host(.flat) .pill::after,:host(.flat) .toast::before,:host(.flat) .toast::after{display:none!important}

:host(.flat) .vb.g:active,:host(.flat) .vchip:active{background:rgba(255,255,255,.32)!important}
/* Play/±10 mitten im Bild: zurückhaltend – das Video scheint deutlich durch, das Symbol trägt */
:host(.flat) .vmid .g{background:rgba(255,255,255,.05)!important;-webkit-backdrop-filter:blur(2.5px) saturate(1.1)!important;backdrop-filter:blur(2.5px) saturate(1.1)!important;box-shadow:0 1px 6px rgba(0,0,0,.08)!important}
:host(.flat) .vmid .g svg{filter:drop-shadow(0 1px 3px rgba(0,0,0,.55))}
:host(.flat) .vmenu{background:rgba(72,74,84,.46)!important;-webkit-backdrop-filter:blur(30px) saturate(1.6) brightness(1.04)!important;backdrop-filter:blur(30px) saturate(1.6) brightness(1.04)!important}
:host(.flat) .vmi,:host(.flat) .vmh,:host(.flat) .toast{text-shadow:0 1px 2px rgba(0,0,0,.4)}
:host(.flat) .vside{background:rgba(255,255,255,.17)!important}`;

  function h(tag, props) {
    const el = document.createElement(tag);
    if (props) for (const k in props) {
      if (k === 'class') el.className = props[k];
      else el.setAttribute(k, k === 'aria-label' || k === 'title' ? tr(props[k]) : props[k]);
    }
    for (let i = 2; i < arguments.length; i++) { const c = arguments[i]; if (c != null) el.append(typeof c === 'string' ? tr(c) : c); }
    return el;
  }
  function icon(d) {
    const s = document.createElementNS(NS, 'svg');
    s.setAttribute('viewBox', '0 0 24 24');
    const p = document.createElementNS(NS, 'path');
    p.setAttribute('d', d);
    s.appendChild(p);
    return s;
  }

  let hostEl = null, root = null, pill = null, toastEl = null;
  let active = null, toastTimer = 0, pressTimer = 0, longPressed = false;

  // Position nur neu berechnen, wenn sich etwas bewegt hat (Scrollen, Drehen, Größe, Verschieben)
  // – höchstens einmal pro Bild, statt dauernd bei jedem Bild
  let placeQueued = false;
  function schedulePlace() {
    if (placeQueued) return;
    placeQueued = true;
    requestAnimationFrame(() => { placeQueued = false; place(); });
  }
  let resizeObs = null;
  function watchSize(v) {
    try {
      if (!resizeObs) resizeObs = new ResizeObserver(schedulePlace);
      resizeObs.disconnect();
      if (v) resizeObs.observe(v);
    } catch (e) {}
  }
  const onMove = () => { if (pill && pill.classList.contains('on')) schedulePlace(); };
  // Wie bei Samsung: beim Scrollen kurz weg, erscheint wieder, sobald nicht mehr gescrollt wird
  let scrollTimer = 0;
  const onScroll = () => {
    if (pill) pill.classList.add('scrolling');
    clearTimeout(scrollTimer);
    scrollTimer = setTimeout(() => { if (pill) { pill.classList.remove('scrolling'); schedulePlace(); } }, 380);
    onMove();
  };
  addEventListener('scroll', onScroll, { capture: true, passive: true });   // auch Scrollen in Unterbereichen (Feeds)
  addEventListener('resize', onMove, { passive: true });
  addEventListener('orientationchange', onMove, { passive: true });
  if (window.visualViewport) {
    visualViewport.addEventListener('resize', onMove, { passive: true });
    visualViewport.addEventListener('scroll', onMove, { passive: true });
  }
  // HD gefunden? Kleiner eisblauer Punkt am Player-Symbol (statt Text)
  function setLabel(t) {
    if (!pill) return;
    const hd = /HD/.test(t);
    if (pill.classList.contains('hd') !== hd) pill.classList.toggle('hd', hd);
    const b = pill.querySelector('button[aria-label^="Im Videoplayer"]');
    if (b) b.title = hd ? (/Full/.test(t) ? 'Im Player öffnen (Full HD)' : 'Im Player öffnen (HD)') : 'Im Player öffnen';
  }
  let collapseTimer = 0;

  // ---------- Position merken (pro Webseite) ----------
  let drag = null, dragPos = null, suppressClick = false;
  const POS_KEY = 'evp:pos';
  function loadPos() {
    try {
      const p = JSON.parse(localStorage.getItem(POS_KEY) || 'null');
      if (!p || !isFinite(p.fy)) return null;
      if (p.side !== 'left' && p.side !== 'right') p.side = isFinite(p.fx) && p.fx < 0.5 ? 'left' : 'right';   // ältere Version
      return { side: p.side, fy: p.fy };
    } catch (e) { return null; }
  }
  let savedPos = loadPos();
  // Gespeichert wird: welche Seite (links/rechts) und die Höhe relativ zum Video (0 = oben, 1 = unten).
  // Beim Loslassen rastet der Button am näheren Rand ein – nie in der Mitte.
  function savePos() {
    if (!active || !pill) return;
    const r = rectOf(active);
    if (!r.width || !r.height) return;
    const left = parseFloat(pill.style.left) || 0, top = parseFloat(pill.style.top) || 0;
    const cx = left + pill.offsetWidth / 2, cy = top + pill.offsetHeight / 2;
    savedPos = {
      side: CONFIG.buttonAt === 'top' ? (cx < innerWidth / 2 ? 'left' : 'right') : (cx < r.left + r.width / 2 ? 'left' : 'right'),
      fy: Math.min(1, Math.max(0, (cy - r.top) / r.height)),
    };
    try { localStorage.setItem(POS_KEY, JSON.stringify(savedPos)); } catch (e) {}
    // sanft an den Rand gleiten lassen
    pill.classList.add('snap');
    setTimeout(() => { if (pill) pill.classList.remove('snap'); }, 260);
    toast('Position gespeichert (' + (savedPos.side === 'left' ? 'links' : 'rechts') + ')');
  }
  // (früher: kleiner Button, der sich aufklappt – jetzt immer gleich groß)
  function expand() {}
  function collapse() { clearTimeout(collapseTimer); }

  // ---------- Lupen-Glas (wie iOS 26 / macOS) ----------
  // Zu jeder Glasfläche (Größe + Rundung) eine Verschiebungskarte: am Rand wird das Bild dahinter nach innen
  // gebogen (wie dickes Glas), in der Mitte leicht vergrößert (Lupe). Läuft als SVG-Filter im Hintergrund-Filter
  // (backdrop-filter) – das kann nur Chrome/Chromium; sonst bleibt es beim Milchglas.
  // LENS_CAN = der Browser kann es (Chrome); ob es an ist, entscheidet die Einstellung – auch sofort umschaltbar
  const lensOn = () => LENS_CAN && CONFIG.lensGlass !== false;
  const LENS_CAN = /Chrome\//.test(navigator.userAgent) &&
    (() => { try { return window.CSS.supports('backdrop-filter', 'url(#a) blur(1px)'); } catch (e) { return false; } })();   // ("CSS" ist hier unser Stil-Text)
  const lensCache = new Map(), lensPre = 'l' + Math.random().toString(36).slice(2, 8);
  let lensSvg = null, lensDefs = null;
  // Wellen-Glas: die Mitte bleibt fast ruhig (zoom = nur ganz leichte Vergrößerung), zum Rand hin läuft das Bild in
  // sanften Wellen (waveA) und biegt sich am Rand stark nach innen (rimA). half = größte Verschiebung in Pixeln.
  function lensMap(w, h, r, rimA, waveA, zoom, half) {
    const R0 = Math.min(w, h) / 2;
    const c = document.createElement('canvas');
    c.width = w; c.height = h;
    const ctx = c.getContext('2d');
    const img = ctx.createImageData(w, h), d = img.data;
    for (let y = 0; y < h; y++) {
      for (let x = 0; x < w; x++) {
        const px = x + 0.5, py = y + 0.5;
        const cx = Math.min(Math.max(px, r), w - r), cy = Math.min(Math.max(py, r), h - r);
        let vx = px - cx, vy = py - cy, edge;
        const len = Math.hypot(vx, vy);
        if (len > 0) { edge = r - len; vx /= len; vy /= len; }
        else {
          const dl = px - r, dr = w - r - px, dt = py - r, db = h - r - py, m = Math.min(dl, dr, dt, db);
          edge = r + m;
          vx = m === dl ? -1 : m === dr ? 1 : 0;
          vy = vx ? 0 : (m === dt ? -1 : 1);
        }
        // u: 0 am Rand … 1 in der Mitte; Verschiebung nach innen = das Bild wird an dieser Stelle "herausgezogen"
        const u = Math.max(0, Math.min(1, edge / R0)), q = 1 - u;
        // (Steigung bleibt überall unter 1 – sonst überschlägt sich das Bild und Text erscheint gespiegelt)
        const off = rimA * q * q + waveA * Math.sin(2 * Math.PI * 1.25 * u) * q;
        const ox = (-(px - w / 2) * (1 - 1 / zoom) - vx * off) / half;
        const oy = (-(py - h / 2) * (1 - 1 / zoom) - vy * off) / half;
        const i = (y * w + x) * 4;
        d[i] = Math.round(128 + 127 * Math.max(-1, Math.min(1, ox)));
        d[i + 1] = Math.round(128 + 127 * Math.max(-1, Math.min(1, oy)));
        d[i + 2] = 128;
        d[i + 3] = 255;
      }
    }
    ctx.putImageData(img, 0, 0);
    return c.toDataURL();
  }
  function lensFor(w, h, r) {
    r = Math.max(0, Math.min(r, w / 2, h / 2));
    const key = w + 'x' + h + 'r' + Math.round(r);
    if (lensCache.has(key)) return lensCache.get(key);
    // Filter liegen im selben Shadow-DOM wie die Glasflächen – nur dort findet Chrome sie (url(#…) gilt je Baum)
    if (!lensSvg) {
      lensSvg = document.createElementNS(NS, 'svg');
      lensSvg.setAttribute('width', '0'); lensSvg.setAttribute('height', '0'); lensSvg.setAttribute('aria-hidden', 'true');
      lensSvg.style.cssText = 'position:absolute;width:0;height:0;overflow:hidden';
      lensDefs = document.createElementNS(NS, 'defs');
      lensSvg.appendChild(lensDefs);
    }
    if (root && lensSvg.parentNode !== root) root.appendChild(lensSvg);
    const m = Math.min(w, h), big = Math.max(w, h);
    const zoom = 1.04, R0 = m / 2;
    const rimA = Math.min(14, R0 * 0.27), waveA = Math.min(4.5, R0 * 0.048);
    const half = Math.ceil(big / 2 * (1 - 1 / zoom) + rimA + waveA + 1), scale = half * 2;
    const id = lensPre + lensCache.size;
    const f = document.createElementNS(NS, 'filter');
    f.setAttribute('id', id);
    f.setAttribute('x', '0%'); f.setAttribute('y', '0%'); f.setAttribute('width', '100%'); f.setAttribute('height', '100%');
    f.setAttribute('color-interpolation-filters', 'sRGB');
    const fi = document.createElementNS(NS, 'feImage');
    const url = lensMap(w, h, r, rimA, waveA, zoom, half);
    fi.setAttribute('href', url);
    fi.setAttributeNS('http://www.w3.org/1999/xlink', 'xlink:href', url);   // ältere Chromium-Versionen kennen nur diese Form
    fi.setAttribute('x', '0'); fi.setAttribute('y', '0'); fi.setAttribute('width', String(w)); fi.setAttribute('height', String(h));
    fi.setAttribute('preserveAspectRatio', 'none'); fi.setAttribute('result', 'm');
    const dm = document.createElementNS(NS, 'feDisplacementMap');
    dm.setAttribute('in', 'SourceGraphic'); dm.setAttribute('in2', 'm'); dm.setAttribute('scale', String(Math.round(scale)));
    dm.setAttribute('xChannelSelector', 'R'); dm.setAttribute('yChannelSelector', 'G');
    f.append(fi, dm);
    lensDefs.appendChild(f);
    lensCache.set(key, id);
    return id;
  }
  function applyLens(el) {
    if (!lensOn() || !el || !el.isConnected) return;
    const w = Math.round(el.offsetWidth), h = Math.round(el.offsetHeight);
    if (w < 16 || h < 16 || w * h > 420000) { el.style.removeProperty('--lens'); el.style.removeProperty('--gb'); return; }
    let r = 0;
    try { r = parseFloat(getComputedStyle(el).borderTopLeftRadius) || 0; } catch (e) {}
    const id = lensFor(w, h, r), val = 'url(#' + id + ')';
    el.style.setProperty('--lens', val);
    el.style.setProperty('--gb', '2px');   // mit Wellen-Glas nur ein wenig Weichzeichner – sonst sieht man die Wellen nicht
    // Chrome lädt das Kartenbild erst beim ersten Zeichnen und rechnet ruhige Flächen danach nicht neu →
    // kurz danach einmal ab- und wieder anschalten (bei laufendem Video passiert das ohnehin ständig)
    if (!el.__lensSeen) el.__lensSeen = {};
    if (!el.__lensSeen[id]) {
      el.__lensSeen[id] = true;
      const again = () => {
        if (el.style.getPropertyValue('--lens') !== val) return;
        el.style.setProperty('--lens', 'blur(0px)');
        requestAnimationFrame(() => requestAnimationFrame(() => { if (el.style.getPropertyValue('--lens') === 'blur(0px)') el.style.setProperty('--lens', val); }));
      };
      setTimeout(again, 180);
      setTimeout(again, 900);
    }
  }
  const lensRO = LENS_CAN && window.ResizeObserver ? new ResizeObserver((es) => es.forEach((e) => applyLens(e.target))) : null;
  // Glas sofort umschalten: Wellen/Lupe ↔ Milchglas (ohne --lens greift der normale Weichzeichner)
  function applyDesign() { if (hostEl) hostEl.classList.toggle('flat', CONFIG.lensGlass === false); }
  function setLens(on) {
    setSetting('lensGlass', !!on);
    applyDesign();
    const all = root ? Array.from(root.querySelectorAll('.g')) : [];
    all.forEach((el) => {
      if (on) lensWatch(el);
      else { el.style.removeProperty('--lens'); el.style.removeProperty('--gb'); if (lensRO) lensRO.unobserve(el); }
    });
  }
  function lensWatch(el) {
    if (!lensOn() || !el) return;
    // Play, ±10 und das Menü bleiben wie in 12.7 (ohne Lupe) – so gewünscht
    if (el.classList && (el.classList.contains('vplay') || el.classList.contains('vskip') || el.classList.contains('vmenu'))) return;
    applyLens(el);
    if (lensRO) lensRO.observe(el);
  }

  function ui() {
    if (root) return;
    // Zufälliger Name + Stile mit !important: manche Seiten blenden fremde Elemente aus (z. B. gegen Werbeblocker)
    hostEl = document.createElement('x-' + Math.random().toString(36).slice(2, 9));
    hostStyle();
    root = hostEl.attachShadow({ mode: 'closed' });
    applyDesign();
    const st = document.createElement('style');
    st.textContent = CSS;
    const btn = h('button', { class: 'pb', 'aria-label': 'Im Videoplayer öffnen' }, icon(I.player), h('span', { class: 'hdot', 'aria-hidden': 'true' }));
    const dlBtn = h('button', { class: 'pb dl', 'aria-label': 'Herunterladen (z. B. mit YTDLnis)' }, icon(I.download));
    dlBtn.addEventListener('click', () => onDownload());
    dlBtn.addEventListener('contextmenu', (e) => { e.preventDefault(); onDownload(null, { share: true }); });   // lange drücken: Teilen
    // Auf "nur Download"-Seiten (z. B. YouTube) gibt es keinen Player-Button
    // Download-Button nur auf Seiten, die YTDLnis kann
    // Der eingebaute Player geht überall – auch auf "nur Download"-Seiten wie YouTube
    pill = h('div', { class: 'pill' }, btn);   // Download gibt es im großen Player
    // Ein Tipp öffnet direkt (Verschieben zählt nicht als Tipp)
    pill.addEventListener('click', (e) => {
      if (suppressClick) {           // das war ein Verschieben, kein Tippen
        suppressClick = false;
        e.stopPropagation();
        e.preventDefault();
        return;
      }
    }, true);
    toastEl = h('div', { class: 'toast' });
    root.append(st, pill, toastEl);

    // Verschieben: Finger auf den Button legen und ziehen – die Stelle wird gespeichert
    pill.addEventListener('pointerdown', (e) => {
      drag = { x0: e.clientX, y0: e.clientY, left0: parseFloat(pill.style.left) || 0, top0: parseFloat(pill.style.top) || 0, moved: false };
    }, true);
    pill.addEventListener('pointermove', (e) => {
      if (!drag) return;
      const dx = e.clientX - drag.x0, dy = e.clientY - drag.y0;
      if (!drag.moved) {
        if (Math.abs(dx) + Math.abs(dy) < 10) return;   // kleine Zitterbewegung beim Tippen ignorieren
        drag.moved = true;
        clearTimeout(pressTimer);
        longPressed = false;
        clearTimeout(collapseTimer);
        try { pill.setPointerCapture(e.pointerId); } catch (err) {}
        pill.classList.add('drag');
      }
      dragPos = { left: drag.left0 + dx, top: drag.top0 + dy };
      schedulePlace();
      e.preventDefault();
    }, true);
    const endDrag = (save) => {
      if (drag && drag.moved) {
        if (save) savePos();
        suppressClick = true;
        setTimeout(() => { suppressClick = false; }, 400);
        pill.classList.remove('drag');
        if (!pill.classList.contains('mini')) expand();
      }
      drag = null;
      dragPos = null;
      schedulePlace();   // an den Rand gleiten lassen
    };
    pill.addEventListener('pointerup', () => endDrag(true), true);
    pill.addEventListener('pointercancel', () => endDrag(true), true);

    // Kurz tippen = im Player öffnen · lange drücken = Diagnose kopieren
    btn.addEventListener('pointerdown', () => {
      longPressed = false;
      if (pill.classList.contains('mini')) return;
      clearTimeout(pressTimer);
      pressTimer = setTimeout(() => { longPressed = true; toast('Loslassen, um die Diagnose zu kopieren'); }, 700);
    });
    btn.addEventListener('pointerup', () => {
      clearTimeout(pressTimer);
      if (longPressed) copyText(diag(active, resolve(active)), 'Diagnose kopiert');
    });
    btn.addEventListener('pointercancel', () => { clearTimeout(pressTimer); longPressed = false; });
    btn.addEventListener('contextmenu', (e) => e.preventDefault());
    btn.addEventListener('click', () => {
      if (longPressed) { longPressed = false; return; }
      onPlayerTap();
    });

    ['click', 'pointerdown', 'pointerup', 'touchstart', 'touchend', 'mousedown', 'mouseup'].forEach((t) =>
      hostEl.addEventListener(t, (e) => e.stopPropagation()));
    attachHost();
  }
  function hostStyle() {
    const s = hostEl.style, imp = (k, v) => s.setProperty(k, v, 'important');
    imp('all', 'initial');
    imp('display', 'block'); imp('position', 'fixed'); imp('top', '0'); imp('left', '0');
    imp('width', '0'); imp('height', '0'); imp('margin', '0'); imp('padding', '0');
    imp('visibility', 'visible'); imp('opacity', '1'); imp('z-index', '2147483647');
    imp('transform', 'none'); imp('filter', 'none'); imp('clip-path', 'none'); imp('contain', 'none');
    imp('overflow', 'visible'); imp('pointer-events', 'auto');
    // Vom erzwungenen Dunkelmodus des Browsers (Vivaldi/Chrome "Dunkle Webseiten") ausnehmen – sonst wird das Glas schwarz eingefärbt
    imp('color-scheme', 'only light');
  }
  let useBody = false;
  function attachHost() {
    if (!hostEl) return;
    const fs = document.fullscreenElement || document.webkitFullscreenElement;
    if (fs && (fs === hostEl || hostEl.contains(fs))) return;   // unser eigener Player ist im Vollbild
    const parent = fs && fs.tagName !== 'VIDEO' && fs.tagName !== 'IFRAME' && fs.tagName !== 'FRAME' ? fs : (useBody && document.body) || document.documentElement;
    if (parent && hostEl.parentNode !== parent) parent.appendChild(hostEl);
    // Hat die Seite unseren Stil verändert? Wiederherstellen.
    if (hostEl.style.getPropertyPriority('display') !== 'important') hostStyle();
    // Immer noch unsichtbar? Dann im <body> statt direkt in <html> versuchen.
    try {
      if (!useBody && getComputedStyle(hostEl).display === 'none' && document.body) { useBody = true; document.body.appendChild(hostEl); }
    } catch (e) {}
  }
  document.addEventListener('fullscreenchange', attachHost, true);
  document.addEventListener('webkitfullscreenchange', attachHost, true);

  function toast(msg) {
    ui();
    // im Player: eine feste Stelle für alle Hinweise (unter dem Bild) – die Gesten-Anzeige macht so lange Platz
    const pw = toastEl.parentElement;
    toastEl.textContent = tr(msg);
    if (pw && pw.classList && pw.classList.contains('vp')) {
      const ind = pw.querySelector('.vind'); if (ind) ind.classList.remove('on');
      if (vp && vp.notePos) vp.notePos(toastEl); else toastEl.style.top = '';
    } else toastEl.style.top = '';
    toastEl.classList.add('on');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => toastEl.classList.remove('on'), 2400);
  }

  // ---------- Button am laufenden Video (unten rechts, über der Steuerleiste) ----------
  function eligible(v) {
    const r = rectOf(v);
    return r.width >= CONFIG.minWidth && r.height >= 90;
  }
  function visibleArea(v) {
    const r = rectOf(v);
    const w = Math.min(r.right, innerWidth) - Math.max(r.left, 0);
    const hh = Math.min(r.bottom, innerHeight) - Math.max(r.top, 0);
    return w > 0 && hh > 0 ? w * hh : 0;
  }
  // Das am besten sichtbare Video, das gerade läuft
  // Läuft das Video hörbar? (nicht stumm, Lautstärke > 0, und es hat überhaupt eine Tonspur)
  function audible(v) {
    if (!CONFIG.onlyWithSound) return true;
    if (v.muted || v.volume < 0.01) return false;
    if (v.mozHasAudio === false) return false;
    const adb = v.webkitAudioDecodedByteCount;
    if (typeof adb === 'number' && adb === 0 && v.currentTime > 2) return false;   // Video ganz ohne Ton
    return true;
  }
  function pickPlaying() {
    let best = null, area = 0;
    const vids = allVideos();
    for (let i = 0; i < vids.length; i++) {
      const v = vids[i];
      if (v.paused || v.ended || !eligible(v) || !audible(v)) continue;
      const a = visibleArea(v);
      if (a > area) { area = a; best = v; }
    }
    return area > 15000 ? best : null;
  }
  function showPill(v) {
    ui();
    attachHost();
    if (pill && !pill.__lens) { pill.__lens = true; lensWatch(pill); }
    if (v !== active) { collapse(); watchSize(v); }   // neues Video: wieder klein anfangen
    active = v;
    pill.classList.add('on');
    schedulePlace();
  }
  function hidePill() {
    if (pill) { pill.classList.remove('on'); collapse(); }
  }
  function place() {
    if (!active || !pill || !pill.classList.contains('on')) return;
    const r = rectOf(active);
    if (!active.isConnected || r.width < CONFIG.minWidth || r.bottom < 60 || r.top > innerHeight - 60) {
      pill.classList.remove('on'); return;
    }
    const w = pill.offsetWidth || 100, ph = pill.offsetHeight || 38;
    let left, top;
    if (dragPos) {
      // wird gerade verschoben: dem Finger folgen
      left = dragPos.left;
      top = CONFIG.buttonAt === 'top' ? CONFIG.topOffset : dragPos.top;
    } else if (CONFIG.buttonAt === 'below') {
      // direkt unter dem Video, rechts oder links (per Ziehen umstellbar)
      left = savedPos && savedPos.side === 'left' ? r.left + CONFIG.rightOffset : r.right - w - CONFIG.rightOffset;
      top = r.bottom + 8;
      if (top + ph > innerHeight - 8) top = r.bottom - ph - 12;   // darunter kein Platz: an den unteren Rand im Video
    } else if (CONFIG.buttonAt === 'top') {
      // oben am Bildschirm, links oder rechts (per Ziehen umstellbar)
      left = savedPos && savedPos.side === 'left' ? CONFIG.rightOffset : innerWidth - w - CONFIG.rightOffset;
      top = CONFIG.topOffset;
    } else if (savedPos) {
      // am gespeicherten Rand (links/rechts), Höhe relativ zum Video, immer innerhalb des Videos
      left = savedPos.side === 'left' ? r.left + CONFIG.rightOffset : r.right - w - CONFIG.rightOffset;
      top = r.top + savedPos.fy * r.height - ph / 2;
      top = Math.min(Math.max(r.top + 4, top), r.bottom - ph - 4);
    } else {
      // Standard: unten rechts, über der Steuerleiste
      left = r.right - w - CONFIG.rightOffset;
      top = r.bottom - ph - CONFIG.bottomOffset;
      if (top < r.top + 8) top = r.top + 8;
    }
    left = Math.min(Math.max(8, left), innerWidth - w - 8);
    top = Math.min(Math.max(8, top), innerHeight - ph - 8);
    pill.style.left = left + 'px';
    pill.style.top = top + 'px';
    // Auf- und Zuklappen vom Rand aus nach innen
    const origin = savedPos && savedPos.side === 'left' && !dragPos ? 'center left' : 'center right';
    if (pill.style.transformOrigin !== origin) pill.style.transformOrigin = origin;
  }

  let miss = 0;
  // Seiten ohne Video (Nachrichten, Shops …): nur alle 2 Sekunden nachsehen statt 2,5-mal pro Sekunde.
  // Startet irgendwo ein Video, geht es sofort wieder im normalen Takt weiter.
  let quiet = 0, tickN = 0;
  document.addEventListener('play', () => { quiet = 0; }, true);
  function tick() {
    tickN++;
    if (quiet > 5 && tickN % 5 && !vp) return;
    if (hostEl && !hostEl.isConnected) attachHost();   // von der Seite entfernt? Wieder einsetzen.
    if (vp) { hidePill(); return; }                    // eigener Player offen: kein Button darüber
    const v = pickPlaying();
    quiet = lastVidCount ? 0 : quiet + 1;
    if (v) {
      miss = 0;
      if (v !== active || !pill || !pill.classList.contains('on')) showPill(v);
      else schedulePlace();   // Sicherheitsnetz, falls die Seite das Video ohne Scrollen verschiebt
      if (PH) { try { phMp4(); } catch (e) {} }   // MP4-Adressen schon im Hintergrund holen
      // Facebook: HD-Version selbstständig im Hintergrund suchen, sobald sicher ist, welches Video läuft
      if (META) {
        if (!FB_MIRROR) { try { fetchHD(v); } catch (e) {} }   // beim Spiegeln unnötig – spart Daten
        const a = assetFor(v).asset;
        const c = a && hdCache.get(a);
        setLabel(c && c.best >= 1080 ? 'Player · Full HD' : c && c.best >= 720 ? 'Player · HD' : 'Player');
      }
    } else if (active && ++miss >= 2) {
      hidePill();   // kurzes Ruckeln beim Laden nicht gleich als Pause werten
    }
  }
  // Prüfung läuft nur, solange die Seite sichtbar ist (anderer Tab, andere App, Bildschirm aus = Pause)
  let tickTimer = 0, navTimer = 0;
  function startChecks() {
    if (tickTimer) return;
    tickTimer = setInterval(tick, 400);
    navTimer = setInterval(checkNav, 1000);
    checkNav();
    tick();
  }
  function stopChecks() {
    clearInterval(tickTimer); clearInterval(navTimer);
    tickTimer = navTimer = 0;
    hidePill();
  }
  document.addEventListener('visibilitychange', () => { if (document.hidden) stopChecks(); else startChecks(); });
  if (!document.hidden) startChecks();
  ['play', 'playing'].forEach((t) => document.addEventListener(t, (e) => {
    if (e.target && e.target.tagName === 'VIDEO') setTimeout(tick, 0);
  }, true));
  document.addEventListener('volumechange', (e) => { if (e.target && e.target.tagName === 'VIDEO') setTimeout(tick, 0); }, true);
  ['pause', 'ended', 'emptied'].forEach((t) => document.addEventListener(t, (e) => {
    if (e.target === active) { hidePill(); setTimeout(tick, 0); }
  }, true));

  function onPillMain() {
    const v = active;
    const go = () => {
      const r = resolve(v);
      if (!r.best) {
        toast(r.splitOnly
          ? 'Dieses Video gibt es hier nur mit getrennter Bild- und Tonspur'
          : 'Kein passendes Video gefunden – kurz laufen lassen und nochmal tippen');
        return;
      }
      let url = r.best, msg = '';
      // Mehrere Quellen: erneutes Tippen beim selben Video (innerhalb 60 s) nimmt die nächste
      if (r.list && r.list.length > 1) {
        const again = lastOpen.v === v && Date.now() - lastOpen.t < 60000;
        const idx = again ? (lastOpen.idx + 1) % r.list.length : 0;
        lastOpen = { v: v, t: Date.now(), idx: idx };
        url = r.list[idx].u;
        msg = 'Quelle ' + (idx + 1) + '/' + r.list.length + ' – klappt nicht? Nochmal tippen';
      }
      hidePill();
      // Geschützte Dateien der Seite selbst (z. B. /get_file/…?token): Weiterleitung im Browser auflösen
      if (needsResolve(url)) {
        toast('Bereite Video vor …');
        finalUrl(url).then((f) => openExternal(f, v, CONFIG.player, msg || (f !== url ? 'Wird im Videoplayer geöffnet (Weiterleitung aufgelöst) …' : '')));
        return;
      }
      openExternal(url, v, CONFIG.player, msg);
    };
    const p = META ? fetchHD(v) : PH ? phMp4() : null;
    if (p) {
      // HD-Suche läuft noch: kurz abwarten (max. 3,5 s), dann mit dem Besten öffnen
      toast(PH ? 'Suche MP4 mit Ton …' : 'Suche HD-Version …');
      let done = false;
      const t = setTimeout(() => { if (!done) { done = true; go(); } }, 3500);
      p.then(() => { if (!done) { done = true; clearTimeout(t); go(); } }, () => {});
      return;
    }
    go();
  }

  // =====================================================================================
  // ---------- Eingebauter Vollbild-Player (wie bei Samsung Internet, Bedienung wie Just Player) ----------
  // Spielt im Browser selbst – mit dessen Sitzung, Cookies und Verbindung. Darum klappt er auch dort,
  // wo externe Player abgewiesen werden. HLS-Streams spielt hls.js ab.
  // =====================================================================================
  const P = {
    close: 'M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z',
    play: 'M8 5v14l11-7z',
    pause: 'M6 19h4V5H6v14zm8-14v14h4V5h-4z',
    fit: 'M19 12h-2v3h-3v2h5v-5zM7 9h3V7H5v5h2V9zm14-6H3c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h18c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 16.01H3V4.99h18v14.02z',
    rotate: 'M16.48 2.52c3.27 1.55 5.61 4.72 5.97 8.48h1.5C23.44 4.84 18.29 0 12 0l-.66.03 3.81 3.81 1.33-1.32zm-6.25-.77c-.59-.59-1.54-.59-2.12 0L1.75 8.11c-.59.59-.59 1.54 0 2.12l12.02 12.02c.59.59 1.54.59 2.12 0l6.36-6.36c.59-.59.59-1.54 0-2.12L10.23 1.75zm4.6 19.44L2.81 9.17l6.36-6.36 12.02 12.02-6.36 6.36zm-7.31.29C4.25 19.94 1.91 16.76 1.55 13H.05C.56 19.16 5.71 24 12 24l.66-.03-3.81-3.81-1.33 1.32z',
    lock: 'M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zm-6 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zm3.1-9H8.9V6c0-1.71 1.39-3.1 3.1-3.1 1.71 0 3.1 1.39 3.1 3.1v2z',
    cc: 'M19 4H5c-1.11 0-2 .9-2 2v12c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm-8 7H9.5v-.5h-2v3h2V13H11v1c0 .55-.45 1-1 1H7c-.55 0-1-.45-1-1v-4c0-.55.45-1 1-1h3c.55 0 1 .45 1 1v1zm7 0h-1.5v-.5h-2v3h2V13H18v1c0 .55-.45 1-1 1h-3c-.55 0-1-.45-1-1v-4c0-.55.45-1 1-1h3c.55 0 1 .45 1 1v1z',
    hd: 'M19 4H5c-1.11 0-2 .9-2 2v12c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm-8 11H9.5v-2h-2v2H6V9h1.5v2.5h2V9H11v6zm7-1c0 .55-.45 1-1 1h-.75v1.5h-1.5V15H14c-.55 0-1-.45-1-1v-4c0-.55.45-1 1-1h3c.55 0 1 .45 1 1v4zm-3.5-.5h2v-3h-2v3z',
    vol: 'M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z',
    mute: 'M16.5 12c0-1.77-1.02-3.29-2.5-4.03v2.21l2.45 2.45c.03-.2.05-.41.05-.63zm2.5 0c0 .94-.2 1.82-.54 2.64l1.51 1.51C20.63 14.91 21 13.5 21 12c0-4.28-2.99-7.86-7-8.77v2.06c2.89.86 5 3.54 5 6.71zM4.27 3L3 4.27 7.73 9H3v6h4l5 5v-6.73l4.25 4.25c-.67.52-1.42.93-2.25 1.18v2.06c1.38-.31 2.63-.95 3.69-1.81L19.73 21 21 19.73l-9-9L4.27 3zM12 4L9.91 6.09 12 8.18V4z',
    sun: 'M20 8.69V4h-4.69L12 .69 8.69 4H4v4.69L.69 12 4 15.31V20h4.69L12 23.31 15.31 20H20v-4.69L23.31 12 20 8.69zM12 18c-3.31 0-6-2.69-6-6s2.69-6 6-6 6 2.69 6 6-2.69 6-6 6zm0-10c-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4-1.79-4-4-4z',
  };
  const VP_CSS = `
.vp{--glass:rgba(255,255,255,.13);--edge1:rgba(255,255,255,.6);--edge2:rgba(255,255,255,.06);--sub:rgba(255,255,255,.74);--ice:#9BE7FF;--trk:rgba(255,255,255,.25);
 position:fixed;inset:0;background:#000;z-index:2147483647;touch-action:none;color:#fff;font:15px/1.2 var(--font);-webkit-user-select:none;user-select:none;overflow:hidden;overflow:clip;-webkit-tap-highlight-color:transparent}
.vv.vzan{transition:transform .25s cubic-bezier(.215,.61,.355,1)}
/* Hinweise im Player: immer an derselben Stelle wie die Gesten-Anzeige (unter dem Bild) und im selben Glas (.g) */
.vp .toast{position:absolute;top:auto;bottom:auto;transform:translate(-50%,8px);
 max-width:calc(100% - 40px);text-align:center;z-index:7;pointer-events:none}
.vp .toast.g{font:650 15px/22px var(--font);padding:12px 20px;min-height:46px;box-sizing:border-box;border-radius:23px;transition:opacity .16s ease,transform .16s ease}
.vp .toast.g::before{border:0}
.vp .toast.on{transform:translate(-50%,0)}
/* Untertitel: über dem Bild, unter den Bedienelementen; rücken über die Leisten, wenn die sichtbar sind */
.vsub{position:absolute;left:5%;right:5%;bottom:calc(26px + var(--evp-sb,env(safe-area-inset-bottom,0px)));text-align:center;pointer-events:none;white-space:pre-line;
 font:500 20px/1.34 var(--font);letter-spacing:.035em;word-spacing:.08em;color:#fff;
 text-shadow:0.7px 0px 0 rgba(0,0,0,.92),-0.7px 0px 0 rgba(0,0,0,.92),0px 0.7px 0 rgba(0,0,0,.92),0px -0.7px 0 rgba(0,0,0,.92),0.5px 0.5px 0 rgba(0,0,0,.92),-0.5px 0.5px 0 rgba(0,0,0,.92),0.5px -0.5px 0 rgba(0,0,0,.92),-0.5px -0.5px 0 rgba(0,0,0,.92),0 1px 4px rgba(0,0,0,.5);-webkit-font-smoothing:antialiased;transition:bottom .25s ease,top .25s ease}
.vsub[data-size="s"]{font-size:16px}.vsub[data-size="l"]{font-size:26px}
/* Untertitel wie in üblichen Videoplayern: nur Schrift – weiß mit dunkler Kontur, ohne Fläche (in beiden Designs) */
.vsub span{display:inline-block;margin:1px 0;padding:0 4px}
/* Standbild: kurzer Blitz */
.vp.flash::after{content:"";position:absolute;inset:0;background:#fff;opacity:0;animation:vflash .35s ease;pointer-events:none;z-index:9}
@keyframes vflash{0%{opacity:.55}100%{opacity:0}}
.vv{position:absolute;inset:0;margin:auto;width:100%;height:100%;max-width:none;max-height:none;object-fit:contain;object-position:50% 50%;background:transparent;transform-origin:50% 50%}
/* Ambient-Modus: die Farben des Bildes leuchten weich in die schwarzen Ränder (wie bei YouTube) */
.vamb{position:absolute;left:-12%;top:-12%;width:124%;height:124%;filter:blur(48px) saturate(1.45) brightness(.7);opacity:0;transition:opacity .8s ease;pointer-events:none;
 -webkit-mask-image:radial-gradient(ellipse 62% 58% at 50% 50%,#000 38%,rgba(0,0,0,.45) 66%,transparent 92%);mask-image:radial-gradient(ellipse 62% 58% at 50% 50%,#000 38%,rgba(0,0,0,.45) 66%,transparent 92%)}
.vp.amb .vamb{opacity:.7}
.vdim{position:absolute;inset:0;background:#000;opacity:0;pointer-events:none}
.vgest{position:absolute;inset:0}
/* Flüssiges Glas (Apple-Stil): klar und hell, weiße Kante rundum, Glanz oben links, Lichtsaum unten rechts –
   bewusst hell, damit es auch auf dunklen Videos und dunklen Seiten wie Glas wirkt */
/* Helle Glasteile werden dunkel gezeichnet und per invert(1) umgedreht: so bleiben sie weiß, auch wenn der Browser
   Webseiten zwangsweise abdunkelt (Vivaldi/Chrome "Dunkle Webseiten" dunkelt nur helle Farben ab, dunkle lässt er stehen) */
.g{position:relative;background:rgba(26,28,38,.14);-webkit-backdrop-filter:var(--lens,blur(0px)) blur(var(--gb,12px)) saturate(1.4) brightness(1.1);backdrop-filter:var(--lens,blur(0px)) blur(var(--gb,12px)) saturate(1.4) brightness(1.1);box-shadow:0 6px 22px rgba(0,0,0,.24);transition:transform .32s cubic-bezier(.34,1.56,.64,1),-webkit-backdrop-filter .2s ease,backdrop-filter .2s ease}
.g::before{content:"";position:absolute;inset:0;z-index:1;border-radius:inherit;padding:.8px;filter:invert(1);background:linear-gradient(135deg,rgba(0,0,0,.62) 0%,rgba(0,0,0,.24) 12%,rgba(0,0,0,0) 30%,rgba(0,0,0,0) 68%,rgba(50,27,0,.10) 82%,rgba(0,0,0,.30) 100%);-webkit-mask:linear-gradient(#000 0 0) content-box,linear-gradient(#000 0 0);-webkit-mask-composite:xor;mask-composite:exclude;pointer-events:none}
.g::after{content:"";position:absolute;inset:0;border-radius:inherit;filter:invert(1);background:radial-gradient(85% 55% at 24% 0%,rgba(0,0,0,.13),rgba(0,0,0,0) 60%),radial-gradient(55% 45% at 80% 108%,rgba(0,0,0,.07),rgba(0,0,0,0) 65%),linear-gradient(180deg,rgba(0,0,0,.06),rgba(0,0,0,.015) 50%,rgba(0,0,0,.04));box-shadow:inset 0 1px 0 rgba(0,0,0,.20),inset 0 -1px 0 rgba(0,0,0,.05),inset 0 0 12px rgba(0,0,0,.05);pointer-events:none;transition:opacity .2s ease}
/* Antippen: das Glas leuchtet kurz auf und federt (wie bei Apple) */
.vb.g:active{transform:scale(.92);-webkit-backdrop-filter:var(--lens,blur(0px)) blur(var(--gb,12px)) saturate(1.6) brightness(1.5);backdrop-filter:var(--lens,blur(0px)) blur(var(--gb,12px)) saturate(1.6) brightness(1.5)}
/* Ein-/Ausblenden: nicht die ganze Leiste halb durchsichtig machen – darunter rechnet Chrome den Milchglas-Effekt
   erst am Ende (erst hell, dann plötzlich dunkler). Jede Fläche blendet sich selbst ein, der Effekt ist sofort da. */
.vchrome{position:absolute;inset:0;pointer-events:none;visibility:hidden;transition:visibility 0s linear .22s}
.vchrome .g{opacity:0;transition:opacity .22s ease,transform .32s cubic-bezier(.34,1.56,.64,1)}
.vchrome .vtitle,.vchrome .vrow,.vchrome .vscrim{opacity:0;transition:opacity .22s ease}
.vp.ctl .vchrome .g,.vp.ctl .vchrome .vtitle,.vp.ctl .vchrome .vrow,.vp.ctl .vchrome .vscrim{opacity:1}
.vscrim{position:absolute;inset:0;background:linear-gradient(180deg,rgba(0,0,0,.34) 0,rgba(0,0,0,0) 20%,rgba(0,0,0,0) 58%,rgba(0,0,0,.5) 100%);pointer-events:none}
.vp.ctl .vchrome{visibility:visible;transition:visibility 0s}
.vp.ctl .vchrome .vb,.vp.ctl .vchrome .ia{pointer-events:auto}
.vp.locked .vchrome{opacity:0 !important}
.vp.locked .vchrome *{pointer-events:none !important}
.vp.menu .vbot,.vp.menu .vmid{opacity:0;pointer-events:none !important}
.vtop{position:absolute;left:14px;right:14px;top:calc(14px + var(--evp-st,env(safe-area-inset-top,0px)));display:flex;align-items:center;gap:10px}
/* Titel: nur so breit wie der Text, Knöpfe rechts bleiben rechts */
/* Titel oben zwischen den Knöpfen: Seite klein darüber, Titel fett – nur Schrift, kein Hintergrund */
.vtitle{flex:0 1 auto;min-width:0;margin-right:auto;display:flex;flex-direction:column-reverse;gap:1px;overflow:hidden;padding-left:2px}
.vtitle span{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font:700 15.5px/1.2 var(--font);text-shadow:0 1px 3px rgba(0,0,0,.55)}
.vtitle small{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font:600 12px/1.2 var(--font);color:var(--sub);text-shadow:0 1px 2px rgba(0,0,0,.55)}
.vcluster{display:flex;align-items:center;height:44px;border-radius:22px;padding:0 4px;transition:opacity .18s ease}
.vb{all:unset;position:relative;display:flex;align-items:center;justify-content:center;width:44px;height:44px;border-radius:22px;cursor:pointer;color:#fff;flex:none;transition:transform .32s cubic-bezier(.34,1.56,.64,1)}
/* "all:unset" oben nimmt runden Glasknöpfen sonst den Glas-Hintergrund – hier wieder dazu (mit Lupe).
   Play und ±10 bleiben wie in 12.7: nur der feine Glasrand, kein Hintergrund */
.vb.g:not(.vplay):not(.vskip){background:rgba(26,28,38,.14);-webkit-backdrop-filter:var(--lens,blur(0px)) blur(var(--gb,12px)) saturate(1.4) brightness(1.1);backdrop-filter:var(--lens,blur(0px)) blur(var(--gb,12px)) saturate(1.4) brightness(1.1);box-shadow:0 6px 22px rgba(0,0,0,.24)}
.vb:active{transform:scale(.88)}
.vb[hidden]{display:none !important}
.vb svg{width:22px;height:22px;fill:#fff;position:relative;z-index:1;filter:drop-shadow(0 1px 2px rgba(0,0,0,.5))}
.vtext,.vskip span,.vchip span,.vtime,.vind span{text-shadow:0 1px 2px rgba(0,0,0,.45)}
.vmid .g{background:rgba(22,24,32,.16)}
.vb:focus-visible,.vchip:focus-visible,.vmi:focus-visible{outline:2px solid var(--ice);outline-offset:2px}
.vstash{display:none!important}
.vtext{width:auto;min-width:40px;padding:0 9px;font:650 13.5px var(--font);font-variant-numeric:tabular-nums}
.vmid{position:absolute;left:50%;top:50%;transform:translate(-50%,-50%);display:flex;align-items:center;gap:30px;transition:opacity .2s}
.vplay{width:64px;height:64px;border-radius:32px}
.vplay svg{width:32px;height:32px}
.vskip{width:46px;height:46px;border-radius:23px}
.vskip svg{width:27px;height:27px}
.vskip .arc{fill:none !important;stroke:#fff;stroke-width:1.9;stroke-linecap:round}
.vskip text{fill:#fff;font:700 8px var(--font)}
.vbot{position:absolute;left:16px;right:16px;bottom:calc(10px + var(--evp-sb,env(safe-area-inset-bottom,0px)));transition:opacity .2s}
/* Unten (wie Samsung): Zeitleiste oben, darunter die Knöpfe – zwischen Leiste und Android-Wischgeste liegen die Knöpfe.
   Sperren allein links (nicht aus Versehen), oft Gebrauchtes rechts, Drehen ganz außen am Daumen */
.vhead{display:flex;align-items:flex-end;justify-content:space-between;gap:12px;margin-top:4px}
.vtools.solo{width:46px;padding:0;justify-content:center}
.vtools .vtool{z-index:1}
.vtools .vtool svg{width:22px;height:22px}
.vrow{display:flex;align-items:center;gap:8px;position:relative;z-index:1}
.vtools{flex:none;display:flex;align-items:center;height:46px;border-radius:23px;padding:0 4px}
/* Werkzeuge unten: nur Symbole (Beschriftung bleibt unsichtbar für Vorlese-Hilfen) */
.vchip{all:unset;position:relative;z-index:1;display:flex;align-items:center;justify-content:center;width:44px;height:44px;border-radius:22px;color:#fff;cursor:pointer;transition:background .15s,transform .2s cubic-bezier(.34,1.56,.64,1)}
.vchip:active{transform:scale(.84)}
.vchip span{position:absolute;width:1px;height:1px;overflow:hidden;clip:rect(0 0 0 0);white-space:nowrap}
.vchip svg{width:22px;height:22px;fill:#fff;filter:drop-shadow(0 1px 2px rgba(0,0,0,.45))}
.vchip.on{color:var(--ice)}
.vchip.on svg{fill:var(--ice)}
.vchip[hidden]{display:none}
.vtime{font:600 12px var(--font);font-variant-numeric:tabular-nums;color:var(--sub);min-width:40px;text-align:center}
.vbar{flex:1;-webkit-appearance:none;appearance:none;height:38px;background:transparent;margin:0;cursor:pointer;filter:invert(1);--trk:rgba(0,0,0,.25)}
.vbar::-webkit-slider-runnable-track{height:5px;border-radius:3px;background:var(--trk);transition:height .18s ease}
.vbar:active::-webkit-slider-runnable-track{height:9px;border-radius:5px}
.vbar::-moz-range-track{height:5px;border-radius:3px;background:var(--trk)}
.vbar::-webkit-slider-thumb{-webkit-appearance:none;margin-top:-6.5px;width:18px;height:18px;border-radius:50%;background:#000;box-shadow:0 0 8px rgba(0,0,0,.55);transform:scale(1);transition:transform .2s cubic-bezier(.34,1.56,.64,1),margin .18s ease}
.vbar::-moz-range-thumb{width:18px;height:18px;border:0;border-radius:50%;background:#000;box-shadow:0 0 8px rgba(0,0,0,.55);transform:scale(1)}
.vbar:active::-moz-range-thumb{transform:scale(1.2)}
.vbar:active::-webkit-slider-thumb{transform:scale(1.2);margin-top:-4.5px}
.vind{position:absolute;left:50%;top:calc(72px + var(--evp-st,env(safe-area-inset-top,0px)));transform:translate(-50%,8px) scale(.96);display:flex;align-items:center;gap:12px;height:46px;padding:0 20px;border-radius:23px;font:650 15px var(--font);opacity:0;transition:opacity .16s ease,transform .16s ease;pointer-events:none;white-space:nowrap;z-index:3}
.vind span{position:relative;z-index:1}
.vind.on{opacity:1;transform:translate(-50%,0) scale(1)}
/* Lautstärke (rechts) und Helligkeit (links): senkrechte Glaskapsel, füllt sich von unten wie bei iOS */
/* Vorschau beim Spulen: kleines Glasfenster mit Bild, darunter die Zeit */
.vprev{position:absolute;left:0;bottom:120px;display:flex;flex-direction:column;align-items:center;gap:6px;opacity:0;transform:translateY(8px) scale(.94);transform-origin:50% 100%;transition:opacity .14s ease,transform .22s cubic-bezier(.34,1.56,.64,1);pointer-events:none;z-index:5}
.vprev.on{opacity:1;transform:none}
.vhead{transition:opacity .15s ease}
.vp.scrub .vhead,.vp.scrub .vmid{opacity:0;pointer-events:none !important}
.vpbox{flex:none;border-radius:14px;overflow:hidden;background:rgba(18,20,28,.55)}
.vpv{position:absolute;inset:0;width:100%;height:100%;object-fit:cover;opacity:0;pointer-events:none}
.vpc{position:absolute;inset:0;width:100%;height:100%;border-radius:inherit;opacity:0;transition:opacity .1s ease}
.vprev.img .vpc{opacity:1}
.vprev.live .vpv{opacity:1}
.vprev.live .vpc{opacity:0}
.vprev.noimg .vpbox{display:none}
.vprev b{font:700 13px var(--font);font-variant-numeric:tabular-nums;color:#fff;text-shadow:0 1px 3px rgba(0,0,0,.6);padding:4px 10px;border-radius:12px;background:rgba(18,20,28,.5)}
/* Spulen per Wischen: große, dünne Zeit mitten im Bild, darunter die Restzeit – nur Zahlen, kein Kasten */
.vseek{position:absolute;left:50%;top:50%;transform:translate(-50%,-50%);text-align:center;color:#fff;pointer-events:none;opacity:0;transition:opacity .14s ease;z-index:3;white-space:nowrap;
 text-shadow:0 2px 14px rgba(0,0,0,.45),0 0 2px rgba(0,0,0,.4);font-variant-numeric:tabular-nums}
.vseek.on{opacity:1}
.vseek b{display:block;font:200 68px/1 var(--font);letter-spacing:.5px}
.vseek small{display:block;margin-top:10px;font:300 21px/1 var(--font);color:rgba(255,255,255,.88)}
.vseek i{display:block;margin-top:8px;font:500 13px/1 var(--font);font-style:normal;color:rgba(255,255,255,.7);letter-spacing:.3px}
.vp.seeking .vchrome{opacity:0 !important}
/* Doppelt tippen links/rechts (wie Samsung Internet, in unserem Glas): runde Glasscheibe springt auf,
   der Kreispfeil dreht sich einmal in Sprungrichtung, ein feiner Lichtring breitet sich aus. Zahl = Sekunden. */
.vsfx{position:absolute;top:50%;width:60px;height:60px;margin:-30px 0 0 -30px;border-radius:50%;display:flex;align-items:center;justify-content:center;pointer-events:none;opacity:0;transform:scale(.72);transition:opacity .28s ease,transform .32s ease;z-index:3}
.vsfx.l{left:24%}
/* Leisten sichtbar: die Bewegung kommt aus dem ±10-Knopf selbst, alles andere tritt kurz zurück */
.vskip i.ring{position:absolute;inset:0;border-radius:50%;box-shadow:0 0 0 1px rgba(0,0,0,.42);filter:invert(1);opacity:0;pointer-events:none;z-index:2}
.vskip .spin{transform-origin:12px 13px}
.vskip.go .spin{animation:vsfxspin .55s cubic-bezier(.2,.75,.25,1) both}
.vskip.go i.ring{animation:vsfxring .6s ease-out both}
.vskip.go{animation:vskpop .45s cubic-bezier(.34,1.56,.64,1) both}
@keyframes vskpop{0%{transform:scale(1)}35%{transform:scale(1.13)}100%{transform:scale(1)}}
.vtop{transition:opacity .18s ease}
/* Ganz unten: hauchdünne Linie, wie weit das Video ist – nur sichtbar, wenn die Leisten ausgeblendet sind */
/* Abstand zu abgerundeten Display-Ecken: seitlich eingerückt (links und rechts GLEICH – auch wenn die Kamera nur auf einer Seite ist), etwas über dem Rand, runde Enden */
.vmini{position:absolute;left:calc(22px + max(var(--evp-sl,env(safe-area-inset-left,0px)),var(--evp-sr,env(safe-area-inset-right,0px))));right:calc(22px + max(var(--evp-sl,env(safe-area-inset-left,0px)),var(--evp-sr,env(safe-area-inset-right,0px))));bottom:calc(6px + var(--evp-sb,env(safe-area-inset-bottom,0px)));height:2.5px;border-radius:2px;overflow:hidden;background:rgba(0,0,0,.12);filter:invert(1);pointer-events:none;opacity:1;transition:opacity .3s ease;z-index:2}
.vmini i{display:block;width:100%;height:100%;border-radius:2px;background:rgba(0,0,0,.46);transform-origin:0 50%;transform:scaleX(0)}
.vp.ctl .vmini,.vp.seeking .vmini,.vp.nolen .vmini,.vp.nomini .vmini{opacity:0}
.vmid .vb{transition:opacity .18s ease,transform .32s cubic-bezier(.34,1.56,.64,1)}

.vsfx.r{left:76%}
.vsfx.on{opacity:1;transform:scale(1);transition:opacity .1s ease,transform .38s cubic-bezier(.34,1.56,.64,1)}
.vsfx svg{position:relative;z-index:2;width:31px;height:31px;overflow:visible;filter:drop-shadow(0 1px 2.5px rgba(0,0,0,.45))}
.vsfx .arc{fill:none !important;stroke:#fff;stroke-width:1.55;stroke-linecap:round}
.vsfx .head{fill:#fff}
.vsfx text{fill:#fff;font:600 7.6px var(--font)}
.vsfx .spin{transform-origin:12px 13px}
.vsfx.go .spin{animation:vsfxspin .55s cubic-bezier(.2,.75,.25,1) both}
@keyframes vsfxspin{0%{transform:rotate(0)}100%{transform:rotate(-1turn)}}
.vsfx i{position:absolute;inset:0;border-radius:50%;box-shadow:0 0 0 1px rgba(0,0,0,.42);filter:invert(1);opacity:0;pointer-events:none}
.vsfx.go i{animation:vsfxring .6s ease-out both}
@keyframes vsfxring{0%{opacity:.7;transform:scale(1)}100%{opacity:0;transform:scale(1.38)}}
.vp.seeking .vind{opacity:0}
/* Regler wie im iOS-Kontrollzentrum: große Glaskapsel, von unten weiß gefüllt, farbiges Symbol in der Füllung */
.vside{position:absolute;top:50%;width:48px;height:min(36vh,200px);border-radius:24px;overflow:hidden;display:flex;flex-direction:column;align-items:center;justify-content:space-between;padding:12px 0 13px;opacity:0;transform:translateY(-50%) scale(.88);transition:opacity .18s ease,transform .34s cubic-bezier(.34,1.56,.64,1);pointer-events:none;z-index:3;box-shadow:0 10px 30px rgba(0,0,0,.28)}
.vside.r{right:calc(20px + var(--evp-sr,env(safe-area-inset-right,0px)))}
.vside.l{left:calc(20px + var(--evp-sl,env(safe-area-inset-left,0px)))}
.vside.on{opacity:1;transform:translateY(-50%) scale(1)}
.vside::before{z-index:2}
.vside i{position:absolute;left:0;right:0;bottom:0;height:0;filter:invert(1);background:linear-gradient(180deg,rgba(0,0,0,.97),rgba(0,0,0,.99) 60%,rgba(0,0,0,.95));box-shadow:inset 0 1px 0 rgba(0,0,0,.06),0 -1px 8px rgba(0,0,0,.30);transition:height .06s linear}

.vside u{position:absolute;left:9px;right:9px;height:1.5px;border-radius:1px;background:rgba(255,255,255,.7);box-shadow:0 0 0 .5px rgba(0,0,0,.18);display:none;z-index:1}
.vside.ext u{display:block}
.vside b{position:relative;z-index:3;font:600 11px var(--font);letter-spacing:.01em;font-variant-numeric:tabular-nums;color:#fff;opacity:.92;text-shadow:0 1px 2px rgba(0,0,0,.45);transition:color .12s}
.vside svg{position:relative;z-index:3;width:22px;height:22px;fill:#fff;filter:drop-shadow(0 1px 2px rgba(0,0,0,.45));transition:fill .15s}
/* in der weißen Füllung dunkelgrau wie bei Android (als Gegenfarbe gezeichnet + invert, s. o.) */
.vside.lit svg{fill:#c9cbd1;filter:invert(1)}   /* dunkelgrau auf Weiß, wie bei Android */
.vside.top b{color:#d5d2c9;filter:invert(1);text-shadow:none}
.vmenu{position:absolute;left:12px;right:12px;bottom:calc(12px + var(--evp-sb,env(safe-area-inset-bottom,0px)));max-height:62%;display:flex;flex-direction:column;overflow:hidden;border-radius:28px;padding:8px;opacity:0;transform:translateY(18px) scale(.98);transition:opacity .2s ease,transform .2s ease;pointer-events:none;z-index:4}
/* nur der Inhalt scrollt – Glaskante und Glanz bleiben am Rahmen */
.vmlist{position:relative;z-index:1;flex:1 1 auto;min-height:0;overflow-y:auto;overscroll-behavior:contain;-webkit-overflow-scrolling:touch;border-radius:20px}
@media (orientation:landscape){
 .vp .vmenu{left:auto;right:calc(14px + var(--evp-sr,env(safe-area-inset-right,0px)));top:50%;bottom:auto;width:min(380px,44%);max-height:calc(100% - 28px - var(--evp-st,env(safe-area-inset-top,0px)) - var(--evp-sb,env(safe-area-inset-bottom,0px)));transform:translate(24px,-50%) scale(.98)}
 .vp .vmenu.on{transform:translate(0,-50%)}
 .vmi{padding:11px 14px}
 .vp.menu .vcluster{opacity:0;pointer-events:none !important}
}
/* weich auslaufen NUR dort, wo wirklich weiterer Inhalt versteckt ist (sonst wirkt die oberste/unterste Zeile angeschnitten) */
.vmlist.mt{-webkit-mask-image:linear-gradient(180deg,transparent 0,#000 16px);mask-image:linear-gradient(180deg,transparent 0,#000 16px)}
.vmlist.mb{-webkit-mask-image:linear-gradient(0deg,transparent 0,#000 16px);mask-image:linear-gradient(0deg,transparent 0,#000 16px)}
.vmlist.mt.mb{-webkit-mask-image:linear-gradient(180deg,transparent 0,#000 16px,#000 calc(100% - 16px),transparent 100%);mask-image:linear-gradient(180deg,transparent 0,#000 16px,#000 calc(100% - 16px),transparent 100%)}
.vmenu.on{opacity:1;transform:none;pointer-events:auto}
.vmenu .vmh{position:relative;z-index:1;padding:8px 12px 6px;font:600 13px var(--font);color:var(--sub)}
.vmi{all:unset;position:relative;z-index:1;display:flex;align-items:center;gap:10px;width:100%;box-sizing:border-box;padding:13px 14px;border-radius:18px;font:500 15px var(--font);cursor:pointer}
.vmi:active{background:rgba(255,255,255,.12)}
.vmi small{margin-left:auto;color:var(--sub);font:500 12.5px var(--font)}
.vmi.cur::before{content:"";position:absolute;inset:0;border-radius:inherit;z-index:-1;background:rgba(100,24,0,.16);filter:invert(1)}
.vmi.cur small{margin-left:auto;color:rgba(255,255,255,.86)}
.vspin{position:absolute;left:50%;top:50%;width:48px;height:48px;margin:-24px 0 0 -24px;border-radius:50%;border:3px solid rgba(0,0,0,.18);border-top-color:#641800;animation:vsp .85s linear infinite;opacity:0;pointer-events:none;filter:invert(1) drop-shadow(0 0 6px rgba(155,231,255,.6))}
.vspin.on{opacity:1}
@keyframes vsp{to{transform:rotate(360deg)}}
/* Schloss beim Sperren: unten in der Mitte, aber mit Abstand zum Rand (gut mit dem Daumen erreichbar) */
.vunlock{position:absolute;left:50%;margin-left:-22px;bottom:calc(14% + var(--evp-sb,env(safe-area-inset-bottom,0px)));display:none;z-index:3}
.vp.locked.ctl2 .vunlock{display:flex}
.vunlock{transition:transform .25s cubic-bezier(.34,1.56,.64,1)}
.vunlock.hold{transform:scale(1.14)}
.vunlock .ulring{position:absolute !important;left:-4px;top:-4px;width:52px !important;height:52px !important;transform:rotate(-90deg);pointer-events:none;filter:none !important;z-index:2}
.vunlock .ulring circle{fill:none !important;stroke:#fff;stroke-width:2.4;stroke-linecap:round;stroke-dasharray:157;stroke-dashoffset:157;opacity:0;transition:stroke-dashoffset .15s ease,opacity .15s ease}
.vunlock.hold .ulring circle{stroke-dashoffset:0;opacity:1;transition:stroke-dashoffset .9s linear,opacity .1s ease}
@media (prefers-reduced-motion:reduce){.vp *{transition:none !important}.vspin{animation-duration:2.4s}}`;

  function fmtTime(s) {
    if (!isFinite(s) || s < 0) s = 0;
    s = Math.floor(s);
    const hh = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
    const p2 = (n) => String(n).padStart(2, '0');
    return hh ? hh + ':' + p2(m) + ':' + p2(sec) : m + ':' + p2(sec);
  }
  // Quellen für den eingebauten Player, die beste zuerst (DASH-Manifeste kann er nicht)
  function playerSources(v, resMap) {
    const out = [];
    const add = (u, res) => {
      if (!u || typeof u !== 'string' || !/^https?:/i.test(u) || /\.mpd(\?|#|$)/i.test(u)) return;
      if (out.indexOf(u) < 0) out.push(u);
      if (resMap && res > 0) resMap[u] = res;   // für das Qualitäts-Menü
    };
    let r = {};
    try { r = resolve(v) || {}; } catch (e) {}
    if (PH && phCache) phCache.forEach((c) => add(c.u, c.res));
    (r.qualities || []).forEach((c) => add(c.u, c.res));
    add(r.best);
    (r.list || []).forEach((x) => add(x.u));
    return out;
  }
  function skipIcon(fwd) {
    const s = document.createElementNS(NS, 'svg');
    s.setAttribute('viewBox', '0 0 24 24');
    const g = document.createElementNS(NS, 'g');
    if (fwd) g.setAttribute('transform', 'matrix(-1 0 0 1 24 0)');
    const arc = document.createElementNS(NS, 'path');
    arc.setAttribute('class', 'arc');
    arc.setAttribute('d', 'M12 4.6 A8.4 8.4 0 1 1 4.73 8.8');
    const head = document.createElementNS(NS, 'path');
    head.setAttribute('d', 'M8.7 4.6 L12.6 2 L12.6 7.2 Z');
    const spin = document.createElementNS(NS, 'g');
    spin.setAttribute('class', 'spin');
    spin.append(arc, head);
    g.append(spin);
    const t = document.createElementNS(NS, 'text');
    t.setAttribute('x', '12'); t.setAttribute('y', '15.7'); t.setAttribute('text-anchor', 'middle');
    t.textContent = String(SKIP());
    s.append(g, t);
    return s;
  }
  function setIcon(b, d) { const p = b.querySelector('path'); if (p) p.setAttribute('d', d); }

  function openBrowserPlayer(orig) {
    if (!orig) return;
    if (vp) closePlayer();
    ui();
    hidePill();
    if (!root.querySelector('style.vpcss')) { const s = document.createElement('style'); s.className = 'vpcss'; s.textContent = VP_CSS; root.append(s); }

    const srcRes = {};
    const st = {
      orig: orig, sources: FB_MIRROR && (orig.captureStream || orig.mozCaptureStream) ? [] : playerSources(orig, srcRes), srcRes: srcRes, idx: 0, hls: null, mode: 'own', cur: '',
      startTime: orig.currentTime || 0, loadTimer: 0, hideTimer: 0, u2: 0, locked: false,
      dim: 0, fit: 'contain', speed: 1, wasFs: false, cleanup: [], barDrag: false, orient: '',
      level: 1,      // Lautstärke 0–2 (über 1 = Verstärkung)
      bright: 1,     // Helligkeit 0,15–1,5 (unter 1 = abdunkeln, über 1 = Bild aufhellen)
      actx: null, gain: null, srcNode: null, boostWarned: false,
      framed: IN_FRAME, rfs: false, fsWait: null,   // eingebettetes Fenster: Hauptseite übernimmt Vollbild/Drehen
    };
    vp = st;
    // Tonverstärkung vorbereiten, solange der Tipp auf "Player" noch "frisch" ist (sonst startet der Ton stumm)
    try {
      const AC = window.AudioContext || window.webkitAudioContext;
      if (AC) { st.actx = new AC(); if (st.actx.state === 'suspended') st.actx.resume().catch(() => {}); }
    } catch (e) { st.actx = null; }

    // ----- Oberfläche -----
    const wrap = h('div', { class: 'vp ctl' });
    const vid = document.createElement('video');
    vid.className = 'vv';
    vid.setAttribute('playsinline', '');
    vid.setAttribute('webkit-playsinline', '');
    vid.preload = 'auto';
    const dimEl = h('div', { class: 'vdim' });
    const amb = document.createElement(FF_MOBILE ? 'video' : 'canvas');   // Ambient-Modus (siehe unten)
    amb.className = 'vamb';
    if (FF_MOBILE) {
      amb.muted = true; amb.setAttribute('playsinline', ''); amb.disablePictureInPicture = true;
      amb.style.objectFit = 'fill';
    } else { amb.width = 32; amb.height = 18; }
    const gest = h('div', { class: 'vgest' });
    // Anzeige für Gesten (Text + Füllstand) als Glaskapsel
    const indTxt = h('span', null, '');
    const indFill = h('i');
    const ind = h('div', { class: 'vind g', 'aria-live': 'polite' }, indTxt);
    const seekBig = h('b', null, '0:00'), seekRest = h('small', null, ''), seekDelta = h('i', null, '');
    const seekBox = h('div', { class: 'vseek', 'aria-live': 'polite' }, seekBig, seekRest, seekDelta);
    // Anzeige fürs doppelte Tippen: Glasscheibe mit Kreispfeil (vor = gespiegelt) – die Zahl zeigt die Sekunden
    const mkSfx = (dir) => {
      const sv = document.createElementNS(NS, 'svg');
      sv.setAttribute('viewBox', '0 0 24 24');
      const mir = document.createElementNS(NS, 'g');
      if (dir > 0) mir.setAttribute('transform', 'matrix(-1 0 0 1 24 0)');
      const spin = document.createElementNS(NS, 'g');
      spin.setAttribute('class', 'spin');
      const arc = document.createElementNS(NS, 'path');
      arc.setAttribute('class', 'arc'); arc.setAttribute('d', 'M12 4.6 A8.4 8.4 0 1 1 4.73 8.8');
      const head = document.createElementNS(NS, 'path');
      head.setAttribute('class', 'head'); head.setAttribute('d', 'M8.7 4.6 L12.6 2 L12.6 7.2 Z');
      spin.append(arc, head); mir.append(spin);
      const t = document.createElementNS(NS, 'text');
      t.setAttribute('x', '12'); t.setAttribute('y', '15.7'); t.setAttribute('text-anchor', 'middle');
      t.textContent = String(SKIP());
      sv.append(mir, t);
      const el = h('div', { class: 'vsfx g ' + (dir > 0 ? 'r' : 'l'), 'aria-live': 'polite' }, h('i'), sv);
      el.lab = t;
      return el;
    };
    const sfxL = mkSfx(-1), sfxR = mkSfx(1);
    const miniFill = h('i'), mini = h('div', { class: 'vmini', 'aria-hidden': 'true' }, miniFill);
    const sideMark = h('u'), sideTxt = h('b', null, ''), sideIco = icon(P.vol);
    const side = h('div', { class: 'vside g r', 'aria-live': 'polite' }, indFill, sideMark, sideTxt, sideIco);
    const spin = h('div', { class: 'vspin on' });
    const ib = (ic, label, cls) => { const b = h('button', { class: 'vb' + (cls ? ' ' + cls : ''), 'aria-label': label }); b.append(icon(ic)); return b; };
    const chip = (ic, label, aria) => { const b = h('button', { class: 'vchip', 'aria-label': aria }); b.append(icon(ic), h('span', null, label)); return b; };
    const bClose = ib(P.close, 'Schließen', 'g');
    const tSpan = h('span'), tSmall = h('small');
    const title = h('div', { class: 'vtitle' }, tSpan, tSmall);
    // Titel der Seite (bei eingebetteten Fenstern: der Hauptseite) – ohne Titel nur die Adresse, nicht doppelt
    const setTitle = () => { const t = pageTitle(), ho = pageHost(); tSpan.textContent = (t || ho).slice(0, 90); tSmall.textContent = t ? ho : ''; tSmall.hidden = !t; };
    setTitle();
    const bSpeed = h('button', { class: 'vb vtext', 'aria-label': 'Geschwindigkeit' }, '1×');
    const bPip = document.pictureInPictureEnabled ? ib(I.pip, 'Bild-in-Bild', 'vtool') : null;
    const bExt = DOWNLOAD_ONLY ? null : ib(I.open, 'In externer App öffnen');
    const bDl = ib(I.download, 'Herunterladen (z. B. mit YTDLnis)');
    const bShare = ib(I.share, 'Link teilen');
    bDl.hidden = !SHOW_DOWNLOAD;   // wird sichtbar, sobald eine echte Videodatei läuft
    const bBack = h('button', { class: 'vb vskip g', 'aria-label': SKIP() + ' s zurück' }, h('i', { class: 'ring' }), skipIcon(false));
    const bPlay = ib(P.pause, 'Wiedergabe', 'vplay g');
    const bFwd = h('button', { class: 'vb vskip g', 'aria-label': SKIP() + ' s vor' }, h('i', { class: 'ring' }), skipIcon(true));
    const tCur = h('span', { class: 'vtime' }, '0:00');
    const tDur = h('span', { class: 'vtime' }, '0:00');
    const bar = h('input', { type: 'range', min: '0', max: '1000', value: '0', class: 'vbar', 'aria-label': 'Position' });
    const bQual = chip(P.hd, 'Auto', 'Qualität');
    const bFit = chip(P.fit, 'Format', 'Bildformat');
    const bRot = chip(P.rotate, 'Drehen', 'Drehen');
    const bLock = chip(P.lock, 'Sperren', 'Bedienung sperren');
    // Aufteilung (16.4, „oben schlank“): oben nur Download und ⋮ (Titel bleibt lesbar), unten am Daumen CC,
    // Bildformat, Bild-in-Bild, Drehen. Teilen, In App öffnen, Qualität, Tempo (16.5), Tonspur, Standbild, Einstellungen: im ⋮-Menü.
    // Teilen/In App/Qualität/Tempo bleiben als unsichtbare Knöpfe (.vstash) bestehen – das Menü löst sie aus.
    const bMore = ib(I.more, 'Mehr');
    const bSubs = chip(P.cc, 'Untertitel', 'Untertitel');   // unten: Untertitel (leuchtet, wenn an)
    const subEl = h('div', { class: 'vsub', 'aria-live': 'polite' });
    const bUnlock = ib(P.lock, 'Entsperren', 'vunlock g');
    const menu = h('div', { class: 'vmenu g', role: 'menu' });
    bQual.hidden = true;   // erscheint, sobald es Qualitätsstufen gibt
    wrap.append(amb, vid, dimEl, subEl, gest, spin,
      h('div', { class: 'vchrome' },
        h('div', { class: 'vscrim' }),
        h('div', { class: 'vtop' }, bClose, title, h('div', { class: 'vcluster g ia' }, bDl, bMore)),
        h('div', { class: 'vmid' }, bBack, bPlay, bFwd),
        h('div', { class: 'vbot ia' }, h('div', { class: 'vrow' }, tCur, bar, tDur), h('div', { class: 'vhead' }, h('div', { class: 'vtools g solo' }, bLock), h('div', { class: 'vtools g' }, bSubs, bFit, bPip, bRot))), h('div', { class: 'vstash' }, bShare, bExt, bQual, bSpeed)),
      ind, side, seekBox, sfxL, sfxR, mini, menu, bUnlock);
    root.append(wrap);
    // Lupen-Glas für alle Glasflächen (Größe kommt erst nach dem Einblenden; ändert sie sich, wird neu berechnet)
    requestAnimationFrame(() => wrap.querySelectorAll('.g').forEach(lensWatch));
    st.cleanup.push(() => { if (lensRO) wrap.querySelectorAll('.g').forEach((e) => lensRO.unobserve(e)); });
    // Der Lichtschein (Ambient) ragt über den Rand hinaus – der Player-Bereich darf dadurch nie verrutschen
    wrap.addEventListener('scroll', () => { if (wrap.scrollTop || wrap.scrollLeft) { wrap.scrollTop = 0; wrap.scrollLeft = 0; } }, { passive: true });
    if (toastEl) { wrap.append(toastEl); toastEl.classList.add('g'); lensWatch(toastEl); }   // Hinweise IM Player (im Vollbild wäre alles daneben unsichtbar) – gleiches Glas wie die Knöpfe
    st.wrap = wrap; st.vid = vid;
    st.aspect = 'contain'; st.zoom = 1; st.panX = 0; st.panY = 0; st.qualPinned = null;

    const M = () => (st.mode === 'capture' ? orig : vid);   // was gerade steuert: eigenes Video oder das Original
    // Ton beim Spiegeln: Chrome spielt ihn weiter über das Original. Firefox (mozCaptureStream) leitet ihn in die
    // Spiegelung um – dort kommt er also aus unserem Video, und die Lautstärke wird dort geregelt.
    const VOL = () => (st.mode === 'capture' && !FF_CAP ? orig : vid);

    // ----- Lautstärke & Helligkeit (System-Werte sind für Webseiten gesperrt – daher im Player selbst) -----
    // Verstärken über 100 % geht nur, wenn der Ton für den Player "lesbar" ist: eigene Streams (hls.js) oder Dateien derselben Seite
    function canBoost() {
      if (!st.actx || st.mode !== 'own') return false;
      if (st.srcNode) return true;
      return !!st.hls || (st.cur && hostOf(st.cur) === location.hostname);
    }
    function applyLevel(level) {
      const m = VOL();
      let lv = clamp(level, 0, 1);   // höchstens 100 %
      if (st.sys && st.sys.vol) {
        // Helfer-App: echte Medienlautstärke des Handys. Android kennt nur ~15 Stufen – die Zwischenwerte (1 %-genau)
        // entstehen, indem das Video INNERHALB einer Stufe etwas leiser spielt (nach Androids Lautstärkekurve in dB).
        const mx = st.sys.max || 15;
        const pos = lv * mx, k = Math.ceil(pos - 1e-6);
        let page = 1;
        if (k <= 0) page = 0;
        else if (k === 1) page = pos;   // zwischen Stille und der leisesten Stufe: einfach linear
        else page = Math.pow(10, (volDb(pos, mx) - volDb(k, mx)) / 20);
        try { m.volume = clamp(page, 0, 1); m.muted = lv === 0; if (st.mode === 'capture' && !FF_CAP) vid.muted = true; } catch (e) {}
        if (st.gain) st.gain.gain.value = 1;
        st.level = lv;
        if (k !== st.sysK) { st.sysK = k; helperSend('vol', k / mx); }
        return lv;
      }
      if (lv > 1 && !canBoost()) lv = 1;
      if (lv > 1 && !st.srcNode) {
        try {
          st.srcNode = st.actx.createMediaElementSource(vid);
          st.gain = st.actx.createGain();
          st.srcNode.connect(st.gain);
          st.gain.connect(st.actx.destination);
          if (st.actx.state === 'suspended') st.actx.resume().catch(() => {});
        } catch (e) { st.srcNode = null; st.gain = null; lv = 1; }
      }
      m.volume = Math.min(1, lv);
      m.muted = lv === 0;
      if (st.mode === 'capture' && !FF_CAP) vid.muted = true;
      if (st.gain) st.gain.gain.value = Math.max(1, lv);
      st.level = lv;
      return lv;
    }
    // Helligkeit wie Androids Regler (BrightnessUtils): Reglerstellung 0–1 ↔ lineare Helligkeit 0–1
    function gammaToLin(g) {
      const R = 0.5, A = 0.17883277, B = 0.28466892, C = 0.55991073;
      const r = g <= R ? Math.pow(g / R, 2) : Math.exp((g - C) / A) + B;
      return clamp(r / 12, 0, 1);
    }
    function linToGamma(l) {
      const R = 0.5, A = 0.17883277, B = 0.28466892, C = 0.55991073;
      const v = clamp(l, 0, 1) * 12;
      return clamp(v <= 1 ? Math.sqrt(v) * R : A * Math.log(v - B) + C, 0, 1);
    }
    // Ganz nach unten und dann noch weiter wischen = automatische Helligkeit des Handys
    function setAutoBright() {
      if (st.autoB) return;
      st.autoB = true;
      st.sysBright = false;
      if (helperQ.bright) helperQ.bright.want = null;
      helper('/end');
      try { if (navigator.vibrate) navigator.vibrate(12); } catch (e) {}
    }
    // Androids Standardkurve für Medien (Stufe in % → dB): 1 % −58 dB, 20 % −40 dB, 60 % −17 dB, 100 % 0 dB
    function volDb(i, mx) {
      const x = clamp(i / mx * 100, 1, 100), pts = [[1, -58], [20, -40], [60, -17], [100, 0]];
      for (let j = 1; j < pts.length; j++) {
        if (x <= pts[j][0]) { const a = pts[j - 1], c = pts[j]; return a[1] + (c[1] - a[1]) * (x - a[0]) / (c[0] - a[0]); }
      }
      return 0;
    }
    function applyBright(b) {
      if (st.sys && st.sys.bright) {
        // Helfer-App: echte Bildschirmhelligkeit (gilt nur, solange der Player offen ist). Der Wert ist – wie
        // Androids eigener Regler – "gefühlt gleichmäßig"; ans Handy geht die passende lineare Helligkeit.
        st.bright = clamp(b, 0, 1);
        dimEl.style.opacity = '0';
        vid.style.filter = '';
        st.dim = 0;
        st.sysBright = true;
        st.autoB = false;
        helperSend('bright', Math.max(0.004, gammaToLin(st.bright)));
        return;
      }
      st.bright = clamp(b, 0.15, 1);   // höchstens 100 % (nur abdunkeln)
      dimEl.style.opacity = st.bright < 1 ? String(Math.min(0.85, 1 - st.bright)) : '0';
      vid.style.filter = st.bright > 1 ? 'brightness(' + st.bright.toFixed(2) + ')' : '';
      st.dim = parseFloat(dimEl.style.opacity) || 0;
    }
    // Läuft die Helfer-App? Dann gelten die Gesten fürs Handy selbst – Startwerte sind die aktuellen des Handys.
    st.sys = null;
    helper('/ping').then((d) => {
      if (!d || !d.ok || vp !== st) return;
      st.sys = { vol: true, bright: !!(d.overlay || d.write), max: d.volMax || 15, dl: !!d.dl, proxy: !!d.proxy, dash: !!d.dash };
      if (st.refreshDl) st.refreshDl();   // mit Helfer lassen sich auch Streams speichern
      if (typeof d.vol === 'number') {
        st.level = clamp(d.vol, 0, 1);
        st.sysK = typeof d.volStep === 'number' ? d.volStep : Math.round(st.level * st.sys.max);
        try { const m = VOL(); m.volume = 1; } catch (e) {}
      }
      st.autoB = !!st.sys.bright;   // zu Beginn: Helligkeit wie im System (auch automatisch) – bis du wischst
      if (st.sys.bright) {
        if (typeof d.bright === 'number') st.bright = linToGamma(d.bright);
        dimEl.style.opacity = '0';
        st.dim = 0;
      }
      // Lebenszeichen (sonst gibt die App die Helligkeit nach 30 s zurück) – und Lautstärketasten mitbekommen
      const beat = setInterval(() => {
        if (vp !== st || document.hidden) return;
        helper('/beat').then((r) => {
          if (!r || !r.ok || vp !== st || helperBusy('vol') || typeof r.vol !== 'number') return;
          // Lautstärketasten gedrückt? Dann gilt die neue Stufe des Handys, das Video wieder voll
          const k = typeof r.volStep === 'number' ? r.volStep : Math.round(r.vol * st.sys.max);
          if (k !== st.sysK) { st.sysK = k; st.level = clamp(r.vol, 0, 1); try { VOL().volume = 1; VOL().muted = false; } catch (e) {} }
        });
      }, 2500);
      // App verlassen (Startbildschirm, anderes Fenster): Helligkeit sofort zurückgeben, beim Zurückkommen wieder setzen
      const onVis = () => {
        if (vp !== st) return;
        if (document.hidden) helper('/end');
        else if (st.sysBright) helperSend('bright', st.bright);
      };
      document.addEventListener('visibilitychange', onVis);
      st.cleanup.push(() => { clearInterval(beat); document.removeEventListener('visibilitychange', onVis); });
    });
    st.cleanup.push(() => { if (st.sys) helper('/end'); });
    let indTimer = 0;
    // Anzeige: Text, optional Füllstand (0–1, über 1 = verstärkt/aufgehellt); "stay" = bleibt stehen (langes Drücken)
    // Hochformat: Anzeige direkt über der Oberkante des Videobilds (nicht ganz oben im Schwarzen).
    // Füllt das Video den Bildschirm (Reels), dann knapp über den Play-Knöpfen. Querformat: oben wie bisher.
    // Hinweise und Gesten-Anzeige: eine Stelle UNTER dem Bild – mit Abstand unter der Unterkante des Videobilds.
    // Füllt das Bild den Bildschirm (Reels, Querformat, Zoom): knapp über der unteren Leiste. Nie über Untertiteln.
    function placeInd(el) {
      el = el || ind;
      const H = wrap.clientHeight || innerHeight, hh = el.offsetHeight || 46;
      let cb = H, wt = 0;
      try {
        const r = vid.getBoundingClientRect(), wr = wrap.getBoundingClientRect();
        const vw = vid.videoWidth, vh = vid.videoHeight;
        wt = wr.top; cb = r.bottom - wt;
        if (vw && vh && (getComputedStyle(vid).objectFit || 'contain') === 'contain' && st.zoom <= 1.001) cb -= (r.height - vh * Math.min(r.width / vw, r.height / vh)) / 2;
      } catch (e) {}
      const vb = wrap.querySelector('.vbot');
      let lim = (vb ? vb.getBoundingClientRect().top - wt : H - 130) - 12;
      const mn = wrap.classList.contains('menu') ? wrap.querySelector('.vmenu.on') : null;   // offenes Menü: darüber
      if (mn) lim = Math.min(lim, mn.getBoundingClientRect().top - wt - 12);
      let top = cb + 14;
      if (top + hh > lim) top = lim - hh;
      const sb = wrap.querySelector('.vsub');
      if (sb && sb.textContent.trim() && sb.offsetParent) {
        const sr = sb.getBoundingClientRect(), a = sr.top - wt, b = sr.bottom - wt;
        if (top < b && top + hh > a) top = a - hh - 10;
      }
      el.style.top = Math.max(70, Math.round(top)) + 'px';
    }
    st.notePos = (el) => { placeInd(el); return (el || ind).style.top; };   // dieselbe Stelle für Hinweise (toast)
    const showInd = (t, lvl, stay) => {
      if (toastEl) toastEl.classList.remove('on');   // gleiche Stelle wie die Hinweise – nie beide übereinander
      placeInd();
      indTxt.textContent = tr(t);
      ind.classList.add('on');
      clearTimeout(indTimer);
      if (!stay) indTimer = setTimeout(() => ind.classList.remove('on'), 800);
    };
    const hideInd = () => { clearTimeout(indTimer); ind.classList.remove('on'); };
    // Spul-Anzeige in der Mitte: Zielzeit groß, Restzeit klein (wie Samsung), dazu die Sprungweite
    const showSeek = (to, dur, delta) => {
      seekBig.textContent = fmtTime(to);
      seekRest.textContent = isFinite(dur) && dur > 0 ? '(−' + fmtTime(Math.max(0, dur - to)) + ')' : '';
      seekDelta.textContent = (delta >= 0 ? '+' : '−') + Math.abs(Math.round(delta)) + ' s';
      hideInd();
      wrap.classList.add('seeking');
      seekBox.classList.add('on');
    };
    const hideSeek = () => { seekBox.classList.remove('on'); wrap.classList.remove('seeking'); };
    // Doppelt tippen: Sprung anzeigen, weitere schnelle Tipps auf derselben Seite zählen dazu (10 s, 20 s, 30 s …)
    let sfxTimer = 0;
    let btnFxTimer = 0;
    const showSkipFx = (dir, total) => {
      const btn = dir > 0 ? bFwd : bBack, obtn = dir > 0 ? bBack : bFwd;
      const ctlOn = wrap.classList.contains('ctl') && !wrap.classList.contains('menu') && !st.locked;
      if (ctlOn) {
        sfxL.classList.remove('on', 'go'); sfxR.classList.remove('on', 'go');
        obtn.classList.remove('go');
        const t = btn.querySelector('text');
        if (t) { t.textContent = String(total); t.style.fontSize = total >= 100 ? '6.4px' : ''; }
        btn.classList.remove('go'); void btn.offsetWidth; btn.classList.add('go');
        wrap.classList.add('skipfx');
        showControls();   // Leisten bleiben, solange getippt wird
        clearTimeout(btnFxTimer);
        btnFxTimer = setTimeout(() => {
          wrap.classList.remove('skipfx'); btn.classList.remove('go');
          [bFwd, bBack].forEach((b) => { const x = b.querySelector('text'); if (x) { x.textContent = String(SKIP()); x.style.fontSize = ''; } });
        }, 700);
        return;
      }
      const el = dir > 0 ? sfxR : sfxL, other = dir > 0 ? sfxL : sfxR;
      other.classList.remove('on', 'go');
      el.lab.textContent = String(total);
      el.lab.style.fontSize = total >= 100 ? '6.4px' : '';
      el.classList.remove('go'); void el.offsetWidth; el.classList.add('on', 'go');   // Pfeile neu loslaufen lassen
      clearTimeout(sfxTimer);
      sfxTimer = setTimeout(() => el.classList.remove('on'), 650);
    };
    // Senkrechte Anzeige: kind 'vol' (rechts) oder 'bright' (links); max > 1 = Bereich über 100 % (Strich bei 100 %)
    let sideTimer = 0;
    const showSide = (kind, lvl, max, txt) => {
      hideInd();
      const f = Math.min(1, Math.max(0, lvl / max));
      side.className = 'vside g on ' + (kind === 'vol' ? 'r' : 'l') + (max > 1.001 ? ' ext' : '') + (f > 0.18 ? ' lit' : '') + (f > 0.87 ? ' top' : '');
      indFill.style.height = (f * 100).toFixed(1) + '%';
      indFill.classList.toggle('over', lvl > 1.001);
      sideMark.style.bottom = (100 / max).toFixed(1) + '%';
      sideTxt.textContent = txt != null ? txt : Math.round(lvl * 100) + '%';
      setIcon(sideIco, kind === 'vol' ? (lvl < 0.005 ? P.mute : P.vol) : P.sun);
      side.setAttribute('aria-label', tr((kind === 'vol' ? 'Lautstärke ' : 'Helligkeit ') + Math.round(lvl * 100) + ' %'));
      clearTimeout(sideTimer);
      sideTimer = setTimeout(() => side.classList.remove('on'), 900);
    };
    const clamp = (x, a, b) => Math.min(b, Math.max(a, x));

    // Video im Player anordnen: Bildformat (Original/Füllen/Strecken/16:9 …), Zoom und Verschieben
    function layoutVideo() {
      const W = wrap.clientWidth || innerWidth, H = wrap.clientHeight || innerHeight;
      let w = W, hh = H, fit = st.aspect;
      const r = /^(\d+):(\d+)$/.exec(st.aspect);
      if (r) {
        const R = +r[1] / +r[2];
        if (W / H > R) { hh = H; w = H * R; } else { w = W; hh = W / R; }
        fit = 'fill';
      }
      vid.style.objectFit = fit;
      // Mittig per CSS (an allen vier Seiten verankert, Rand "auto"): bleibt auch dann genau in der Mitte, wenn sich die
      // Höhe nachträglich ändert (Vollbild, Adressleiste ein/aus). Ohne Verschiebung per transform – die hat Android
      // in eingebetteten Vollbild-Fenstern falsch umgesetzt (Bild saß zu weit oben). Nur feste Seitenverhältnisse
      // brauchen eine berechnete Größe.
      vid.style.width = r ? w + 'px' : '100%';
      vid.style.height = r ? hh + 'px' : '100%';
      // nur so weit verschieben, wie wirklich Bild ist (nicht in die schwarzen Ränder) – beim Ziehen mit einem Finger
      // gibt es über die Kante hinaus mit Widerstand nach (wie Telegram) und federt beim Loslassen zurück
      if (!st.panFree) {
        const lim = panLimits(st.zoom);
        st.panX = clamp(st.panX, -lim[0], lim[0]);
        st.panY = clamp(st.panY, -lim[1], lim[1]);
      }
      vid.style.transform = st.zoom > 1.001 ? 'translate(' + st.panX + 'px,' + st.panY + 'px) scale(' + st.zoom + ')' : '';
      if (st.holdCv && st.holdCv.isConnected) st.holdCv.style.cssText = vid.style.cssText + ';pointer-events:none';
      if (st.subPlace) st.subPlace();
    }
    // Sichtbares Bild bei 1× (ohne die schwarzen Ränder) und wie weit es sich bei Zoom z verschieben lässt
    function contentBox() {
      const W = wrap.clientWidth || innerWidth, H = wrap.clientHeight || innerHeight;
      let w = W, hh = H;
      const r = /^(\d+):(\d+)$/.exec(st.aspect);
      if (r) { const R = +r[1] / +r[2]; if (W / H > R) { hh = H; w = H * R; } else { w = W; hh = W / R; } }
      let cw = w, ch = hh;
      if (st.aspect === 'contain' && vid.videoWidth && vid.videoHeight) {
        const sc = Math.min(w / vid.videoWidth, hh / vid.videoHeight);
        cw = vid.videoWidth * sc; ch = vid.videoHeight * sc;
      }
      return [cw, ch, W, H];
    }
    function panLimits(z) {
      const b = contentBox();
      return [Math.max(0, (b[0] * z - b[2]) / 2), Math.max(0, (b[1] * z - b[3]) / 2)];
    }
    // Doppeltipp in der Mitte (wie Telegram): 3× um die getippte Stelle – die Stelle unter dem Finger bleibt dort;
    // hochkant (Querformat-Video) mindestens so weit, dass das Bild den Bildschirm füllt. Noch einmal = ganzes Bild.
    // Zoom: ein Finger verschiebt das Bild (mit Schwung), zwei Finger zoomen weiter.
    const TAP_ZOOM = 3;
    function zoomMax() { const b = contentBox(); return clamp(Math.max(4, b[2] / b[0], b[3] / b[1]), 4, 6); }
    function zoomTo(z, px, py) {
      vid.classList.add('vzan');
      clearTimeout(st.zanT);
      st.zanT = setTimeout(() => vid.classList.remove('vzan'), 360);
      st.zoom = z; st.panX = px; st.panY = py;
      layoutVideo();
    }
    function tapZoom(x, y) {
      if (st.zoom > 1.01) { st.tapZoomed = false; zoomTo(1, 0, 0); return true; }
      if (!vid.videoWidth || !vid.videoHeight) return false;
      const b = contentBox(), cw = b[0], ch = b[1], W = b[2], H = b[3];
      const z = clamp(Math.max(TAP_ZOOM, W / cw, H / ch), 1, zoomMax());
      const r = wrap.getBoundingClientRect();
      const qx = x - r.left - W / 2, qy = y - r.top - H / 2;
      const ux = clamp(qx, -cw / 2, cw / 2), uy = clamp(qy, -ch / 2, ch / 2);   // getippte Stelle im Bild
      st.tapZoomed = true;
      zoomTo(z, qx - ux * z, qy - uy * z);
      return true;
    }
    const onLayout = () => { if (vp === st) layoutVideo(); };
    addEventListener('resize', onLayout);
    st.cleanup.push(() => removeEventListener('resize', onLayout));
    // Größe des Players selbst beobachten (kommt auf Android oft erst NACH dem resize-Ereignis an)
    try { const ro = new ResizeObserver(onLayout); ro.observe(wrap); st.cleanup.push(() => ro.disconnect()); } catch (e) {}
    const onFsLayout = () => { onLayout(); setTimeout(onLayout, 120); setTimeout(onLayout, 450); };
    document.addEventListener('fullscreenchange', onFsLayout);
    st.cleanup.push(() => document.removeEventListener('fullscreenchange', onFsLayout));
    layoutVideo();

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

    // ----- Steuerleisten ein-/ausblenden -----
    function scheduleHide() {
      clearTimeout(st.hideTimer);
      st.hideTimer = setTimeout(() => { if (!M().paused && !st.barDrag) wrap.classList.remove('ctl'); }, 3200);
    }
    function showControls() { wrap.classList.add('ctl'); scheduleHide(); }
    function toggleControls() { if (wrap.classList.contains('ctl')) { wrap.classList.remove('ctl'); clearTimeout(st.hideTimer); } else showControls(); }

    // Fortschritt (weiß) + schon geladener Bereich (halbhell) + Rest (dunkel)
    function paintBar(p) {
      const m = M();
      let b = p;
      try {
        const br = m.buffered, c = m.currentTime, d = m.duration;
        for (let i = 0; i < br.length; i++) {
          if (br.start(i) <= c + 0.5 && br.end(i) >= c) { b = isFinite(d) && d > 0 ? br.end(i) / d : p; break; }
        }
      } catch (e) {}
      const P1 = (p * 100).toFixed(2), B1 = (Math.max(p, b) * 100).toFixed(2);
      bar.style.setProperty('--trk', 'linear-gradient(to right,#000 ' + P1 + '%,rgba(0,0,0,.42) ' + P1 + '%,rgba(0,0,0,.42) ' + B1 + '%,rgba(0,0,0,.18) ' + B1 + '%)'   /* umgedreht: wird per invert(1) weiß */);
    }

    // ----- Anzeige aktualisieren -----
    function updateUI() {
      const m = M();
      const d = m.duration, c = m.currentTime;
      tCur.textContent = fmtTime(c);
      tDur.textContent = isFinite(d) ? fmtTime(d) : 'Live';
      const hasLen = isFinite(d) && d > 0;
      wrap.classList.toggle('nolen', !hasLen);   // Live-Stream: keine Linie
      if (hasLen) miniFill.style.transform = 'scaleX(' + Math.min(1, Math.max(0, c / d)).toFixed(4) + ')';
      if (!st.barDrag) {
        const p = isFinite(d) && d > 0 ? c / d : 0;
        bar.value = String(Math.round(p * 1000));
        paintBar(p);
      }
      setIcon(bPlay, m.paused ? P.play : P.pause);
    }
    const onMediaEv = (e) => {
      const fromOrig = e.currentTarget === orig;
      if ((st.mode === 'capture') !== fromOrig) return;
      if (e.type === 'waiting') spin.classList.add('on');
      if (e.type === 'playing' || e.type === 'canplay') spin.classList.remove('on');
      if (e.type === 'pause') showControls();
      updateUI();
    };
    ['timeupdate', 'durationchange', 'play', 'pause', 'waiting', 'playing', 'canplay', 'seeked'].forEach((t) => {
      vid.addEventListener(t, onMediaEv);
      orig.addEventListener(t, onMediaEv);
      st.cleanup.push(() => orig.removeEventListener(t, onMediaEv));
    });

    // ----- Laden: Quellen der Reihe nach, zuletzt Bild vom Original übernehmen -----
    function tryNext() {
      clearTimeout(st.loadTimer);
      if (st.hls) { try { st.hls.destroy(); } catch (e) {} st.hls = null; }
      if (st.idx >= st.sources.length) { useCapture(); return; }
      const u = st.sources[st.idx++];
      st.cur = u;
      st.mode = 'own';
      spin.classList.add('on');
      st.loadTimer = setTimeout(tryNext, 9000);   // lädt nicht? nächste Quelle
      try { vid.srcObject = null; vid.removeAttribute('src'); } catch (e) {}
      if (PLAYLIST_RE.test(u)) {
        if (typeof Hls !== 'undefined' && Hls.isSupported()) {
          // Untertitel zeichnet der Player selbst (⋯ → Untertitel) – hls.js lädt sie nur
          const hls = new Hls({ enableWorker: false, subtitleDisplay: false, startPosition: st.startTime > 1 ? st.startTime : -1 });
          st.hls = hls;
          hls.on(Hls.Events.ERROR, (ev, data) => { if (data && data.fatal) tryNext(); });
          hls.on(Hls.Events.MANIFEST_PARSED, () => {
            try { if (hls.levels && hls.levels.length) hls.startLevel = hls.levels.length - 1; } catch (e) {}   // beste Qualität zuerst
            vid.play().catch(() => {});
            refreshQual();
          });
          hls.on(Hls.Events.LEVEL_SWITCHED, () => refreshQual());
          hls.loadSource(u);
          hls.attachMedia(vid);
          return;
        }
        if (vid.canPlayType('application/vnd.apple.mpegurl')) { vid.src = u; vid.play().catch(() => {}); return; }
        tryNext();
        return;
      }
      vid.src = u;
      vid.play().catch(() => {});
    }
    vid.addEventListener('loadedmetadata', () => {
      if (st.mode !== 'own') return;
      clearTimeout(st.loadTimer);
      // Nach einem Quellwechsel: andere Länge = anderes Video (z. B. ein vorgeladenes Reel) – sofort zurück
      if (st.expectDur && isFinite(vid.duration) && Math.abs(vid.duration - st.expectDur) > 3 && st.prevCur && st.prevCur !== st.cur) {
        const bad = st.cur, back = st.prevCur;
        st.expectDur = 0; st.prevCur = '';
        st.sources = [back].concat(st.sources.filter((x) => x !== bad && x !== back));
        delete st.srcRes[bad];
        st.idx = 0;
        tryNext();
        showInd('Diese Qualität gehörte zu einem anderen Video');
        return;
      }
      st.expectDur = 0;
      if (!st.hls && st.startTime > 1 && isFinite(vid.duration) && vid.duration > st.startTime) { try { vid.currentTime = st.startTime; } catch (e) {} }
      vid.playbackRate = st.speed;
      autoOrient(vid.videoWidth, vid.videoHeight);
      refreshQual();
      layoutVideo();
      if (st.refreshDl) st.refreshDl();
      if (st.prefetchExt) st.prefetchExt();
      if (st.prevWarm) setTimeout(() => { if (vp === st) st.prevWarm(); }, 1500);
      vid.play().catch(() => {});
    });
    vid.addEventListener('error', () => { if (st.mode === 'own' && vp === st) tryNext(); });

    function useCapture() {
      clearTimeout(st.loadTimer);
      let ms = null;
      try { ms = orig.captureStream ? orig.captureStream() : (orig.mozCaptureStream ? orig.mozCaptureStream() : null); } catch (e) {}
      if (!ms) { toast('Dieses Video lässt sich hier nicht im eigenen Player zeigen'); closePlayer(); return; }
      st.mode = 'capture';
      st.cur = '';
      if (st.refreshDl) st.refreshDl();
      try { vid.removeAttribute('src'); } catch (e) {}
      vid.srcObject = ms;
      vid.muted = !FF_CAP;   // Chrome: der Ton kommt vom Original-Video · Firefox: aus der Spiegelung
      vid.play().catch(() => {});
      orig.playbackRate = st.speed;
      try { orig.muted = st.origMuted; } catch (e) {}   // hier kommt der Ton vom Original – also Ton wieder an
      if (orig.paused) orig.play().catch(() => {});
      spin.classList.remove('on');
      autoOrient(orig.videoWidth, orig.videoHeight);
      updateUI();
      watchMirror(ms);
    }
    // Spiegeln am Leben halten: Facebook/Instagram laden ein Reel am Ende jeder Schleife NEU ins Videoelement.
    // Dabei reißt die Bildübertragung ab (Ton läuft weiter, Bild schwarz) – dann sofort neu verbinden.
    // Firefox: dasselbe Videoelement zeigt neue Spuren eines Stroms nicht an – dort jedes Neuladen nachholen
    let mirrorAt = 0, mirrorWatch = 0, mutedSince = 0, mirrorLater = 0;
    // Firefox liefert für ein angehaltenes, gerade neu geladenes Original kein Bild (schwarz) – das letzte Bild als
    // Standbild darüberlegen, bis wieder echte Bilder ankommen (Chrome zeigt es von selbst weiter)
    function holdFrame() {
      if (!GECKO || vid.readyState < 2 || !vid.videoWidth) return;
      try {
        if (!st.holdCv) { st.holdCv = document.createElement('canvas'); st.holdCv.className = 'vv vhold'; st.holdCv.style.pointerEvents = 'none'; }
        st.holdCv.width = vid.videoWidth; st.holdCv.height = vid.videoHeight;
        st.holdCv.getContext('2d').drawImage(vid, 0, 0);
        st.holdCv.style.cssText = vid.style.cssText + ';pointer-events:none';
        if (st.holdCv.previousSibling !== vid) vid.after(st.holdCv);
      } catch (e) {}
    }
    function dropHold() { if (st.holdCv && st.holdCv.isConnected) st.holdCv.remove(); }
    function rebind(ms) {
      holdFrame();
      st.mirrorFix = (st.mirrorFix || 0) + 1;
      st.mirrorWhy = 'neu zugewiesen';
      wrap.dataset.mirror = st.mirrorFix + ' ' + st.mirrorWhy;
      vid.srcObject = null;
      vid.srcObject = ms;
      vid.play().catch(() => {});
      lastFrame = mirrorAt = Date.now();
      frameLoop();
    }
    function recapture(why) {
      if (vp !== st || st.mode !== 'capture') return;
      const now = Date.now();
      if (now - mirrorAt < 400) {   // nicht im Kreis neu verbinden
        if (GECKO && !mirrorLater) mirrorLater = setTimeout(() => { mirrorLater = 0; recapture(why); }, 410 - (now - mirrorAt));
        return;
      }
      mirrorAt = now;
      let ms2 = null;
      try { ms2 = orig.captureStream ? orig.captureStream() : (orig.mozCaptureStream ? orig.mozCaptureStream() : null); } catch (e) {}
      if (!ms2) return;
      st.mirrorFix = (st.mirrorFix || 0) + 1;
      st.mirrorWhy = why;
      wrap.dataset.mirror = st.mirrorFix + ' ' + why;   // (nur zur Fehlersuche sichtbar)
      if (GECKO) { holdFrame(); vid.srcObject = null; }
      vid.srcObject = ms2;
      vid.muted = !FF_CAP;
      vid.play().catch(() => {});
      lastFrame = now;
      frameLoop();
      watchMirror(ms2);
    }
    // Wann kam hier zuletzt wirklich ein Bild an? (zuverlässigster Hinweis auf "Ton läuft, Bild schwarz")
    let lastFrame = Date.now(), frameLoopId = 0, lastOrigT = -1;
    function frameLoop() {
      if (!vid.requestVideoFrameCallback) return;
      const id = ++frameLoopId;
      const cb = () => { if (id !== frameLoopId || vp !== st) return; lastFrame = Date.now(); if (st.holdCv) dropHold(); vid.requestVideoFrameCallback(cb); };
      vid.requestVideoFrameCallback(cb);
    }
    function liveVideo() {
      const so = vid.srcObject;
      const tr = so && so.getVideoTracks ? so.getVideoTracks() : [];
      return tr.filter((t) => t.readyState === 'live');
    }
    function watchMirror(ms) {
      try {
        const cur = () => vid.srcObject === ms;   // Meldungen älterer Ströme zählen nicht mehr
        if (GECKO) {
          // Firefox: der Strom bekommt nach dem Neuladen selbst neue Spuren, nur das Videoelement zeigt sie erst nach
          // erneutem Zuweisen (ein frisch abgegriffener Strom bleibt dort dagegen oft stumm)
          let rb = 0;
          ms.addEventListener('addtrack', (e) => {
            if (!e.track || e.track.kind !== 'video') return;
            clearTimeout(rb);
            rb = setTimeout(() => { if (cur() && vp === st && st.mode === 'capture') rebind(ms); }, 40);
          });
        } else {
          // neue Spur: nur neu verbinden, wenn gerade wirklich kein Bild ankommt (sonst unnötiges Flackern)
          ms.addEventListener('addtrack', () => setTimeout(() => { if (cur() && Date.now() - lastFrame > 600) recapture('neue Spur'); }, 300));
          ms.addEventListener('removetrack', () => setTimeout(() => { if (cur() && !liveVideo().length) recapture('Spur entfernt'); }, 30));
          ms.getVideoTracks().forEach((t) => t.addEventListener('ended', () => setTimeout(() => { if (cur()) recapture('Spur beendet'); }, 30)));
        }
      } catch (e) {}
      if (mirrorWatch) return;
      frameLoop();
      // Neues Video im Original (Schleife, nächstes Reel): neu verbinden, sobald es wieder Bild hat
      const onSrc = () => { if (!GECKO) setTimeout(() => recapture('Original neu geladen'), 60); };
      ['loadedmetadata', 'loadeddata'].forEach((t) => orig.addEventListener(t, onSrc));
      // Wächter: Das Original läuft WIRKLICH weiter (Zeit rückt vor), aber hier kommt seit 1 s kein Bild an
      // – oder die Spur ist weg / stumm? Dann neu verbinden.
      mirrorWatch = setInterval(() => {
        if (vp !== st || st.mode !== 'capture' || document.hidden) { mutedSince = 0; return; }
        const ot = orig.currentTime, moving = !orig.paused && lastOrigT >= 0 && ot !== lastOrigT;
        lastOrigT = ot;
        if (!moving) { mutedSince = 0; lastFrame = Math.max(lastFrame, Date.now() - 300); return; }
        const live = liveVideo();
        if (!live.length) { recapture('kein Bild'); return; }
        if (vid.requestVideoFrameCallback && Date.now() - lastFrame > 1000) { recapture('Bild steht'); return; }
        if (live.every((t) => t.muted)) {
          if (!mutedSince) mutedSince = Date.now();
          else if (Date.now() - mutedSince > 1200) { mutedSince = 0; recapture('Bild stumm'); }
        } else mutedSince = 0;
      }, 500);
      st.cleanup.push(() => {
        clearInterval(mirrorWatch);
        clearTimeout(mirrorLater);
        dropHold();
        frameLoopId++;
        ['loadedmetadata', 'loadeddata'].forEach((t) => orig.removeEventListener(t, onSrc));
      });
    }

    // ----- Bedienelemente -----
    // Play nach dem Ende: Sprung an den Anfang ist dann gewollt (nicht als Schleife werten)
    function togglePlay() { const m = M(); if (m.paused) { st.endHold = false; st.userSeekAt = Date.now(); m.play().catch(() => {}); } else m.pause(); showControls(); }
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
    })));
    // Bild-in-Bild: kleines Fenster über anderen Apps. Dabei endet das Vollbild – das darf den Player NICHT schließen.
    const pipFallback = () => {
      // Manche Android-Browser kennen nur das automatische Bild-in-Bild: Video im Vollbild + zum Startbildschirm wischen
      const hint = () => toast('Zum Startbildschirm wischen – das Video läuft im kleinen Fenster weiter (Bild-in-Bild für den Browser in den Android-Einstellungen erlauben)');
      if (isFs()) { hint(); return; }
      enterFs().then(() => { if (isFs()) { st.fsStart = true; hint(); } else toast('Bild-in-Bild ist hier nicht möglich'); });
    };
    on(bPip, () => {
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
    function dlMenu(browserFailed) {
      const d = directUrl(), hs = helperSrc();
      let link = '';
      try { link = SHOW_DOWNLOAD ? postUrlFor(orig) : ''; } catch (e) {}
      const items = [];
      if (hs && st.sys && st.sys.dl) items.push({ label: 'Mit dem Helper', sub: hs.k === 'file' ? 'Download-Manager' : 'Stream → MP4', fn: () => helperDownload(hs) });
      if (hs && st.sys && st.sys.proxy) items.push({ label: 'Über den Browser laden', sub: 'für geschützte Seiten · Seite offen lassen', fn: () => helperDownload(hs, 'browser') });
      if (link) items.push({ label: 'Mit YTDLnis', sub: 'direkt öffnen', fn: () => toYtdlnis(link) });
      if (d && !browserFailed) items.push({ label: 'Im Browser speichern', sub: 'Download des Browsers', fn: () => browserSave(d) });
      if (d) {
        items.push({ label: 'Im Browser öffnen', sub: 'dort ⋮ → Herunterladen', fn: () => {
          const w = window.open(d, '_blank');
          if (w) { try { w.opener = null; } catch (e) {} } else toast('Neuer Tab wurde blockiert – Pop-ups für diese Seite erlauben');
        } });
        items.push({ label: 'Hier speichern', sub: 'Player bis zum Ende offen lassen', fn: () => directDownload(d, () => toast('Die Seite sperrt den Download')) });
      }
      if (link || d) items.push({ label: 'Teilen …', sub: 'an eine andere App', fn: () => shareUrl(link || d) });
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
      if (link && onSite(CONFIG.ytdlpSites)) { if (CONFIG.downloader === 'share') shareUrl(link); else toYtdlnis(link); return; }
      if (hs && st.sys && st.sys.dl) { helperDownload(hs); return; }
      if (d) { browserSave(d); return; }
      if (link) { if (CONFIG.downloader === 'share') shareUrl(link); else toYtdlnis(link); return; }
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
    // ----- Menüs: Glas-Blatt von unten -----
    function openMenu(head, items, keepScroll) {
      const old = keepScroll && menu.classList.contains('on') ? menu.querySelector('.vmlist') : null;
      const oldTop = old ? old.scrollTop : 0;
      menu.textContent = '';
      const list = h('div', { class: 'vmlist' });
      menu.append(h('div', { class: 'vmh' }, head), list);   // (h übersetzt die Texte)
      items.forEach((it) => {
        const b = h('button', { class: 'vmi' + (it.cur ? ' cur' : ''), role: 'menuitemradio', 'aria-checked': it.cur ? 'true' : 'false' },
          String(it.label), it.sub ? h('small', null, String(it.sub)) : null);
        b.addEventListener('click', (e) => { stop(e); if (!it.stay) closeMenu(); it.fn(); });
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
    function closeMenu() { menu.classList.remove('on'); wrap.classList.remove('menu'); scheduleHide(); }
    const menuOpen = () => menu.classList.contains('on');
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
    on(bQual, () => { const items = qualityItems(); if (items.length > 1) openMenu('Qualität', items); });

    // ===== ⋯ Mehr: Untertitel, Tonspur, Standbild, Einstellungen =====
    on(bMore, () => openMore());
    on(bSubs, () => openSubs());
    function openMore() {
      const items = [{ label: 'Link teilen', fn: () => bShare.click() }];
      if (bExt && !bExt.hidden) items.push({ label: 'In App öffnen', sub: 'z. B. VLC', fn: () => bExt.click() });
      if (!bQual.hidden) { const q = bQual.querySelector('span'); items.push({ label: 'Qualität', sub: q ? q.textContent : '', fn: () => bQual.click() }); }
      items.push({ label: 'Geschwindigkeit', sub: spLabel(st.speed), fn: openSpeed });
      const at = audioItems();
      if (at.length > 1) items.push({ label: 'Tonspur', sub: (at.find((x) => x.cur) || {}).label || '', fn: () => openMenu('Tonspur', at) });
      items.push({ label: 'Standbild speichern', fn: snapshot });
      items.push({ label: 'Einstellungen', fn: () => openSettings(false) });
      openMenu('Mehr', items);
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
        it('Halten', String(CONFIG.holdSpeed).replace('.', UI_EN() ? '.' : ',') + '×', () => next('holdSpeed', [1.5, 2, 3])),
        // Wischsperre: Streifen am Rand ohne Helligkeit/Lautstärke (Schnelleinstellungen, Home-Geste) – Stufe in Worten
        it('Wischsperre oben', zone(CONFIG.topGuard), () => { next('topGuard', ZONES); zoneTip('topGuard', 'Oberste', 'Oben'); }),
        it('Wischsperre unten', zone(CONFIG.bottomGuard), () => { next('bottomGuard', ZONES); zoneTip('bottomGuard', 'Unterste', 'Unten'); }),
        it('Auto-Drehen', yn(CONFIG.autoRotate), () => setSetting('autoRotate', !CONFIG.autoRotate)),
        it('Download', CONFIG.downloader === 'share' ? 'Teilen' : 'YTDLnis', () => setSetting('downloader', CONFIG.downloader === 'share' ? 'ytdlnis' : 'share')),
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
      ];
      openMenu('Einstellungen', items, keep);
    }

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
      msH('play', () => { const m = M(); st.endHold = false; m.play().catch(() => {}); });
      msH('pause', () => M().pause());
      msH('stop', () => M().pause());
      msH('seekbackward', (d) => seekBy(-((d && d.seekOffset) || SKIP())));
      msH('seekforward', (d) => seekBy((d && d.seekOffset) || SKIP()));
      msH('previoustrack', () => seekBy(-SKIP()));
      msH('nexttrack', () => seekBy(SKIP()));
      msH('seekto', (d) => { if (d && isFinite(d.seekTime)) { st.userSeekAt = Date.now(); try { M().currentTime = d.seekTime; } catch (e) {} msPos(); } });
      st.cleanup.push(() => {
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
        if (!x) { fetch(u, { credentials: 'include' }).then((r) => (r.ok ? r.text() : ''), () => '').then(res, () => res('')); return; }
        try {
          // Anmeldung (Cookies) nur an die eigene Seite – Adressen fremder Server ohne
          const anon = baseDomain(hostOf(u)) !== baseDomain(location.hostname);
          x({ method: 'GET', url: u, timeout: 20000, headers: { Referer: location.href }, anonymous: anon,
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
    const pageSubs = { done: false, busy: false, list: [], cache: new Map() };
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
      const list = [];
      for (const [u, lang] of byKey.values()) {
        const txt = await gmText(u);
        const c = parseSubs(txt);
        if (c.length < 2) continue;
        pageSubs.cache.set(u, c);
        list.push({ u: u, lang: lang });
      }
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

    // ----- Tonspur (Sprache) bei Streams mit mehreren Tonspuren -----
    function audioItems() {
      const items = [];
      const hls = st.hls;
      if (st.mode === 'own' && hls && hls.audioTracks && hls.audioTracks.length > 1) {
        hls.audioTracks.forEach((t, i) => items.push({ label: t.name || (t.lang || '').toUpperCase() || 'Spur ' + (i + 1), sub: t.lang && t.name ? t.lang.toUpperCase() : '',
          cur: hls.audioTrack === i, fn: () => { try { hls.audioTrack = i; } catch (e) {} toast('Tonspur: ' + (t.name || t.lang || i + 1)); } }));
      } else {
        const at = M().audioTracks;   // nur wo der Browser das kann
        if (at && at.length > 1) {
          for (let i = 0; i < at.length; i++) {
            const t = at[i];
            items.push({ label: t.label || (t.language || '').toUpperCase() || 'Spur ' + (i + 1), cur: !!t.enabled,
              fn: () => { for (let j = 0; j < at.length; j++) at[j].enabled = j === i; } });
          }
        }
      }
      return items;
    }

    // ----- Standbild speichern -----
    function snapshot() {
      const v = vid;
      if (!v.videoWidth || v.readyState < 2) { toast('Noch kein Bild da'); return; }
      const c = document.createElement('canvas');
      c.width = v.videoWidth; c.height = v.videoHeight;
      const g2 = c.getContext('2d');
      try { g2.drawImage(v, 0, 0); } catch (e) { toast('Diese Seite erlaubt kein Standbild'); return; }
      // gesperrt (fremder Server ohne Freigabe) oder leer (Firefox auf dem Handy)?
      try {
        const t = document.createElement('canvas'); t.width = 8; t.height = 8;
        const tx = t.getContext('2d'); tx.drawImage(c, 0, 0, 8, 8);
        const d = tx.getImageData(0, 0, 8, 8).data;
        let any = false; for (let i = 3; i < d.length; i += 4) if (d[i]) { any = true; break; }
        if (!any) { toast(FF_MOBILE ? 'In Firefox auf dem Handy leider nicht möglich' : 'Kein Bild bekommen'); return; }
      } catch (e) { toast('Diese Seite erlaubt kein Standbild (Videoserver sperrt es)'); return; }
      const name = ((pageTitle() || pageHost()).replace(/[\\/:*?"<>|]+/g, ' ').trim().slice(0, 60) || 'Video') + ' ' + fmtTime(M().currentTime).replace(/:/g, '-') + '.jpg';
      const go = (href, rev) => {
        const a = document.createElement('a');
        a.href = href; a.download = name; a.style.display = 'none';
        wrap.append(a); a.click(); a.remove();
        if (rev) setTimeout(() => URL.revokeObjectURL(href), 30000);
        wrap.classList.remove('flash'); void wrap.offsetWidth; wrap.classList.add('flash');
        setTimeout(() => wrap.classList.remove('flash'), 400);
        toast('Standbild gespeichert (Downloads)');
      };
      try {
        if (GECKO) { go(c.toDataURL('image/jpeg', 0.92), false); return; }   // Firefox lädt Blob-Adressen des Skripts nicht
        c.toBlob((b) => { if (b) go(URL.createObjectURL(b), true); else toast('Kein Bild bekommen'); }, 'image/jpeg', 0.92);
      } catch (e) { toast('Diese Seite erlaubt kein Standbild'); }
    }
    // Drehen: ins Querformat (dafür echter Vollbildmodus) – nochmal tippen: zurück ins Hochformat
    on(bRot, () => {
      st.selfTurnAt = Date.now();   // merken: diese Drehung kam von dir (nicht von der Zurück-Taste)
      if (st.orient === 'landscape') {
        if (CONFIG.startFullscreen) {
          // im Vollbild bleiben (ggf. zurückholen), nur ins Hochformat drehen
          const toPortrait = () => {
            st.orient = 'portrait';
            lockO('portrait');
          };
          if (isFs()) toPortrait(); else enterFs().then(() => { if (isFs()) st.fsStart = true; toPortrait(); });
        } else exitFs();
        return;
      }
      const toLandscape = () => {
        if (!isFs()) { toast('Drehen ist hier nicht möglich – dreh das Handy mit aktivierter Auto-Drehung'); return; }
        st.orient = 'landscape';
        lockO('landscape');
      };
      // Schon im Vollbild? Dann nur drehen – Vollbild erneut anfordern kann es auf Android kurz beenden
      if (isFs()) toLandscape();
      else enterFs().then(() => { if (isFs() && CONFIG.startFullscreen) st.fsStart = true; toLandscape(); });
    });
    on(bLock, () => { st.locked = true; wrap.classList.add('locked'); });
    // Entsperren nur mit Absicht (nicht aus Versehen): 1 Sekunde gedrückt halten – der Ring füllt sich – oder zweimal schnell tippen
    (function unlockGesture() {
      const ring = document.createElementNS(NS, 'svg');
      ring.setAttribute('class', 'ulring'); ring.setAttribute('viewBox', '0 0 52 52');
      const c = document.createElementNS(NS, 'circle'); c.setAttribute('cx', '26'); c.setAttribute('cy', '26'); c.setAttribute('r', '25');
      ring.append(c); bUnlock.append(ring);
      let holdT = 0, lastTap = 0;
      const doUnlock = () => {
        clearTimeout(holdT); bUnlock.classList.remove('hold');
        if (!st.locked) return;
        st.locked = false; wrap.classList.remove('locked', 'ctl2'); showControls();
        try { navigator.vibrate && navigator.vibrate(18); } catch (e) {}
      };
      const keepVisible = () => { clearTimeout(st.u2); st.u2 = setTimeout(() => wrap.classList.remove('ctl2'), 2500); };
      bUnlock.addEventListener('pointerdown', (e) => { stop(e); clearTimeout(st.u2); bUnlock.classList.add('hold'); clearTimeout(holdT); holdT = setTimeout(doUnlock, 950); });
      const cancel = () => { clearTimeout(holdT); bUnlock.classList.remove('hold'); if (st.locked) keepVisible(); };
      bUnlock.addEventListener('pointerup', (e) => {
        stop(e); cancel();
        if (!st.locked) return;
        const now = Date.now();
        if (now - lastTap < 450) { lastTap = 0; doUnlock(); return; }
        lastTap = now;   // ein einzelner Tipp tut nichts (kein Hinweis)
      });
      bUnlock.addEventListener('pointercancel', cancel);
      bUnlock.addEventListener('pointerleave', cancel);
      bUnlock.addEventListener('click', stop);
      bUnlock.addEventListener('contextmenu', (e) => e.preventDefault());
    })();

    // Fortschrittsbalken
    // Hochwischen über die Leiste (Android-Geste: Startbildschirm/Menü) ist kein Spulen. Mild, damit Halten und Ziehen
    // gut geht: sobald der Finger ~10 px zur Seite zieht, gilt es als Spulen (wird nie mehr abgebrochen). Nur deutlich
    // nach oben (40 px) bei kaum seitlicher Bewegung – oder wenn Android die Geste übernimmt (pointercancel) – springt
    // die Leiste zurück
    let barG = null;
    const barBack = () => { const m = M(); if (isFinite(m.duration) && m.duration > 0) { bar.value = String(Math.round(m.currentTime / m.duration * 1000)); paintBar(bar.value / 1000); tCur.textContent = fmtTime(m.currentTime); } };
    bar.addEventListener('pointerdown', (e) => {
      stop(e); st.barDrag = true; clearTimeout(st.hideTimer);
      barG = { x: e.clientX, y: e.clientY, off: false, lock: false };
      pv.preload = 'auto'; pvs.autoGrid = true; if (prevLoad() && pv.readyState >= 1) startGrid();
    });
    bar.addEventListener('pointermove', (e) => {
      if (!barG || barG.off || barG.lock) return;
      const dx = e.clientX - barG.x, dy = e.clientY - barG.y;
      if (Math.abs(dx) > 10) { barG.lock = true; return; }
      if (-dy > 40 && Math.abs(dx) < 16) { barG.off = true; st.barDrag = false; prevHide(); barBack(); }
    });
    bar.addEventListener('pointercancel', () => {   // Android hat die Wischgeste übernommen
      if (barG && !barG.lock && !barG.off) { barG.off = true; st.barDrag = false; barBack(); setTimeout(() => { if (barG && barG.off) { barG = null; barBack(); scheduleHide(); } }, 120); }
    });
    bar.addEventListener('input', () => {
      if (barG && barG.off) { barBack(); return; }
      const m = M();
      if (isFinite(m.duration)) tCur.textContent = fmtTime(bar.value / 1000 * m.duration);
      paintBar(bar.value / 1000);
      prevShow(bar.value / 1000);
    });
    bar.addEventListener('change', () => {
      if (barG && barG.off) { barG = null; barBack(); scheduleHide(); return; }   // war Hochwischen, kein Spulen
      barG = null;
      const m = M();
      st.userSeekAt = Date.now();
      if (isFinite(m.duration)) { try { m.currentTime = bar.value / 1000 * m.duration; } catch (e) {} }
      st.barDrag = false;
      prevHide();
      scheduleHide();
    });
    ['pointerup', 'pointercancel', 'lostpointercapture'].forEach((t) => bar.addEventListener(t, () => setTimeout(prevHide, 60)));

    // ----- Vorschaubild beim Spulen auf der Leiste -----
    // Ein zweites, stummes Video (in der kleinsten verfügbaren Qualität) springt an die Fingerstelle.
    // Schnell durch: 1) jedes Vorschaubild wird gemerkt, 2) ein Raster von ~30 Bildern wird im Hintergrund vorgeholt
    // (Streams gleich beim Öffnen – billig in kleinster Stufe; große Dateien erst beim ersten Berühren der Leiste),
    // 3) beim Ziehen erscheint sofort das nächstgelegene gemerkte Bild, die genaue Stelle wird danach nachgeschärft.
    const pv = document.createElement('video');
    pv.className = 'vpv'; pv.muted = true; pv.preload = 'auto';
    pv.setAttribute('playsinline', ''); pv.setAttribute('webkit-playsinline', ''); pv.disablePictureInPicture = true;
    const pvc = document.createElement('canvas');
    pvc.className = 'vpc'; pvc.width = 192; pvc.height = 108;
    let pvcx = null;
    try { pvcx = pvc.getContext('2d'); } catch (e) {}
    const pvBox = h('div', { class: 'vpbox g' }, pv, pvc);
    const pvTime = h('b', null, '0:00');
    const prev = h('div', { class: 'vprev', 'aria-hidden': 'true' }, pvBox, pvTime);
    wrap.append(prev);
    const pvs = { key: '', src: '', hls: null, want: -1, lastWant: -1, busy: false, kind: '', grid: [], gridOn: false, autoGrid: false, userDone: false };
    const thumbs = new Map();   // Zeit-Fach -> kleines Bild
    let bucket = 1;             // Fachbreite in Sekunden (≈ Länge / 300)
    function prevReset() {
      if (pvs.hls) { try { pvs.hls.destroy(); } catch (e) {} }
      pvs.hls = null; pvs.src = ''; pvs.key = ''; pvs.want = -1; pvs.lastWant = -1; pvs.busy = false; pvs.grid = []; pvs.gridOn = false; pvs.userDone = false;
      thumbs.clear();
      prev.classList.remove('img', 'live');
      try { pv.removeAttribute('src'); pv.load(); } catch (e) {}
    }
    st.cleanup.push(prevReset);
    // Kleinste Qualität desselben Videos (lädt viel schneller); Streams nutzen ohnehin die kleinste Stufe
    function previewSrc() {
      if (st.hls) return st.cur;
      const curRes = st.srcRes[st.cur] || 0;
      const low = st.sources.filter((u) => (st.srcRes[u] || 0) >= 144).sort((x, y) => st.srcRes[x] - st.srcRes[y])[0];
      return low && curRes && st.srcRes[low] < curRes ? low : st.cur;
    }
    function prevLoad() {
      const key = st.mode === 'own' ? st.cur : '';
      if (!key) { prev.classList.add('noimg'); return false; }   // nur abgefilmt: keine eigene Quelle → nur die Zeit
      prev.classList.remove('noimg');
      if (pvs.key === key) return true;
      prevReset();
      pvs.key = key;
      const u = previewSrc();
      pvs.src = u;
      if (st.hls && typeof Hls !== 'undefined') {
        const hp = new Hls({ enableWorker: false, startLevel: 0, maxBufferLength: 2, maxMaxBufferLength: 4, backBufferLength: 0 });
        pvs.hls = hp;
        hp.on(Hls.Events.MANIFEST_PARSED, () => { try { hp.currentLevel = 0; hp.autoLevelEnabled = false; } catch (e) {} });   // kleinste Stufe
        hp.on(Hls.Events.ERROR, (ev, d) => { if (d && d.fatal) prev.classList.add('noimg'); });
        hp.loadSource(u);
        hp.attachMedia(pv);
      } else {
        pv.src = u;
      }
      return true;
    }
    function thumbDims() {
      const vw = pv.videoWidth || 16, vh = pv.videoHeight || 9;
      return [192, Math.max(40, Math.min(340, Math.round(192 * vh / vw)))];
    }
    // aktuelles Bild des Vorschau-Videos merken
    function snap() {
      if (!(pv.videoWidth > 0)) return null;
      const d = thumbDims();
      const c = document.createElement('canvas');
      c.width = d[0]; c.height = d[1];
      try { c.getContext('2d').drawImage(pv, 0, 0, d[0], d[1]); } catch (e) { return null; }
      thumbs.set(Math.round(pv.currentTime / bucket), c);
      if (thumbs.size > 160) thumbs.delete(thumbs.keys().next().value);
      return c;
    }
    // Zeigen, wenn es näher an der Fingerstelle liegt als das bisher gezeigte Bild (oder noch keins da ist)
    function better(t) {
      if (!prev.classList.contains('img') || pvs.shownT == null) return true;
      return Math.abs(t - pvs.lastWant) < Math.abs(pvs.shownT - pvs.lastWant);
    }
    function paint(c, t) {
      if (!pvcx || !c) return;
      pvs.shownT = t;
      if (pvc.width !== c.width || pvc.height !== c.height) { pvc.width = c.width; pvc.height = c.height; }
      try { pvcx.drawImage(c, 0, 0); } catch (e) { return; }
      prev.dataset.t = String(Math.round(t * 10) / 10);
      prev.classList.add('img');
    }
    function nearest(t) {
      const k = Math.round(t / bucket);
      for (let i = 0; i <= 8; i++) {
        if (thumbs.has(k + i)) return [thumbs.get(k + i), (k + i) * bucket];
        if (i && thumbs.has(k - i)) return [thumbs.get(k - i), (k - i) * bucket];
      }
      return null;
    }
    // Reihenfolge: zuerst die Fingerstelle, in Pausen das Raster
    function pump() {
      if (pvs.busy || pv.readyState < 1) return;
      let t = -1, kind = '';
      if (pvs.want >= 0) { t = pvs.want; kind = 'user'; pvs.want = -1; }
      else {
        while (pvs.grid.length && t < 0) { const g = pvs.grid.shift(); if (!thumbs.has(Math.round(g / bucket))) { t = g; kind = 'grid'; } }
      }
      if (t < 0) return;
      pvs.busy = true; pvs.kind = kind;
      try { pv.currentTime = t; } catch (e) { pvs.busy = false; }
      // Wächter: kommt bei Streams kein „seeked“ (Sprung hängt), bleibt die Vorschau sonst stehen → aufgeben, neu fragen
      clearTimeout(pvs.wd);
      if (pvs.busy) pvs.wd = setTimeout(() => {
        if (!pvs.busy) return;
        pvs.busy = false;
        if (kind === 'user' && prev.classList.contains('on') && pvs.want < 0 && pvs.lastWant >= 0) pvs.want = pvs.lastWant;
        pump();
      }, 1200);
    }
    function startGrid() {
      if (FF_MOBILE) return;   // dort keine gemerkten Bilder (lassen sich nicht kopieren) – das Video selbst zeigt die Stelle
      const d = pv.duration;
      // Beim ersten Ziehen hat deine Fingerstelle Vorrang – das Raster startet erst, wenn ihr Bild da ist
      if (prev.classList.contains('on') && !pvs.userDone) { pvs.autoGrid = true; return; }
      if (pvs.gridOn || !isFinite(d) || d <= 0) return;
      pvs.gridOn = true;
      const N = 30, order = [];
      for (let i = 0; i < N; i++) order.push(((i * 7) % N + 0.5) * d / N);   // gestreut statt der Reihe nach
      pvs.grid = order;
      pump();
    }
    function prevSeek(t) {
      pvs.want = t; pvs.lastWant = t;
      pump();
    }
    pv.addEventListener('loadedmetadata', () => {
      pvs.busy = false;
      // Kleinere Qualität gewählt, aber andere Länge? Dann lieber die laufende Quelle nehmen
      const md = M().duration;
      if (pvs.src !== st.cur && isFinite(md) && isFinite(pv.duration) && Math.abs(pv.duration - md) > 3) {
        const key = pvs.key; pvs.src = st.cur; pv.src = st.cur; pvs.key = key; return;
      }
      if (isFinite(pv.duration) && pv.duration > 0) bucket = Math.max(0.5, pv.duration / 300);
      if (pvs.autoGrid) startGrid();
      pump();
    });
    pv.addEventListener('seeked', () => {
      pvs.busy = false; clearTimeout(pvs.wd);
      if (FF_MOBILE) {
        // Vorschau-Video direkt zeigen (statt eines kopierten Bildes)
        if (prev.classList.contains('on')) { prev.dataset.t = String(Math.round(pv.currentTime * 10) / 10); prev.classList.add('img', 'live'); }
        pump();
        return;
      }
      if (pvs.kind === 'user' && !pvs.userDone) { pvs.userDone = true; if (pvs.autoGrid) setTimeout(startGrid, 0); }
      const c = snap();
      if (c && prev.classList.contains('on')) {
        // neues Bild oder ein gemerktes – je nachdem, was näher an der Fingerstelle liegt
        const n = nearest(pvs.lastWant);
        if (n && Math.abs(n[1] - pvs.lastWant) < Math.abs(pv.currentTime - pvs.lastWant)) { if (better(n[1])) paint(n[0], n[1]); }
        else if (better(pv.currentTime)) paint(c, pv.currentTime);
      }
      pump();
    });
    pv.addEventListener('error', () => { prev.classList.add('noimg'); });
    // Streams: Raster gleich nach dem Start vorholen (kleinste Stufe, wenig Daten)
    // Vorwärmen nach dem Start: Streams holen gleich das Raster (kleinste Stufe, wenig Daten),
    // Dateien nur ihr Inhaltsverzeichnis (wenige KB) – dann wartet auch das erste Ziehen nicht darauf
    st.prevWarm = () => {
      if (st.mode !== 'own' || !st.cur) return;
      if (st.hls) { pvs.autoGrid = true; pvs.userDone = true; prevLoad(); if (pv.readyState >= 1) startGrid(); }
      else { pv.preload = 'metadata'; prevLoad(); }
    };
    function prevShow(p) {
      const d = M().duration;
      if (!isFinite(d) || d <= 0) return;
      const t = p * d;
      pvTime.textContent = fmtTime(t);
      const img = prevLoad() && !prev.classList.contains('noimg');
      if (img) {
        pvs.lastWant = t;
        const n = nearest(t);
        if (n && better(n[1])) paint(n[0], n[1]);   // sofort das nächstgelegene gemerkte Bild …
        prevSeek(t);                                // … und die genaue Stelle nachschärfen
        if (pv.readyState >= 1) startGrid(); else pvs.autoGrid = true;
      }
      const vw = vid.videoWidth || 16, vh = vid.videoHeight || 9;
      let w = innerWidth > innerHeight ? 184 : 164, hh = w * vh / vw;
      const maxH = innerWidth > innerHeight ? 110 : 150;
      if (hh > maxH) { hh = maxH; w = hh * vw / vh; }   // Hochkant-Videos nicht zu groß
      pvBox.style.width = Math.round(w) + 'px'; pvBox.style.height = Math.round(hh) + 'px';
      const br = bar.getBoundingClientRect(), wr = wrap.getBoundingClientRect();
      const pw = img ? w : 64;
      const x = br.left - wr.left + 7 + p * (br.width - 14);   // Mitte des Punkts
      prev.style.width = Math.round(pw) + 'px';
      prev.style.left = Math.round(clamp(x - pw / 2, 8, wr.width - pw - 8)) + 'px';
      prev.style.bottom = Math.round(wr.bottom - br.top + 6) + 'px';
      prev.classList.add('on');
      wrap.classList.add('scrub');   // Knöpfe darüber kurz ausblenden, damit die Vorschau frei steht
    }
    function prevHide() { prev.classList.remove('on'); wrap.classList.remove('scrub'); }

    // ----- Gesten (wie Just Player) -----
    const pts = new Map();
    let g = null, pinch = null, lastTapT = 0, lastZone = 9, tapTimer = 0;
    gest.addEventListener('pointerdown', (e) => {
      stop(e);
      pts.set(e.pointerId, { x: e.clientX, y: e.clientY });
      try { gest.setPointerCapture(e.pointerId); } catch (err) {}
      if (pts.size === 2) {
        const a = Array.from(pts.values());
        if (g) clearTimeout(g.holdTimer);
        pinch = { d0: Math.hypot(a[0].x - a[1].x, a[0].y - a[1].y) || 1, d: 0, z0: st.zoom,
          mx: (a[0].x + a[1].x) / 2, my: (a[0].y + a[1].y) / 2, px: st.panX, py: st.panY };
        g = null; st.panFree = false;
        return;
      }
      const m = M();
      const Wd = wrap.clientWidth || innerWidth;
      // ganz am Rand beginnt die Zurück-Geste von Android – dort keine Player-Geste starten
      const edge = e.clientX < 28 || e.clientX > Wd - 28;
      // oben (15 % der Höhe, mindestens 48 Pixel) zieht man die Schnelleinstellungen herunter – dort keine Helligkeit/Lautstärke
      const Hd = wrap.clientHeight || innerHeight;
      // unten (ebenso 15 %) beginnt die Home-Wischgeste von Android – dort ebenfalls nicht
      const top = e.clientY < Math.max(Hd * CONFIG.topGuard, 48) ||
        e.clientY > Hd - Math.max(Hd * (CONFIG.bottomGuard == null ? 0.15 : CONFIG.bottomGuard), 48);
      g = { x: e.clientX, y: e.clientY, t: Date.now(), mode: '', time: m.currentTime, vol: st.level, bright: st.bright, delta: 0, edge: edge, top: top, holdTimer: 0 };
      // Lange drücken = schneller, solange gedrückt (wie Just Player)
      const gg = g;
      gg.holdTimer = setTimeout(() => {
        if (g !== gg || gg.mode || st.locked || pts.size !== 1 || menuOpen()) return;
        gg.mode = 'hold';
        M().playbackRate = CONFIG.holdSpeed;
        showInd(String(CONFIG.holdSpeed).replace('.', ',') + '× schneller', null, true);
      }, 450);
    });
    gest.addEventListener('pointermove', (e) => {
      if (!pts.has(e.pointerId)) return;
      pts.set(e.pointerId, { x: e.clientX, y: e.clientY });
      if (st.locked) return;
      if (pinch && pts.size >= 2) {
        const a = Array.from(pts.values());
        pinch.d = Math.hypot(a[0].x - a[1].x, a[0].y - a[1].y);
        st.zoom = clamp(pinch.z0 * pinch.d / pinch.d0, 1, zoomMax());
        st.tapZoomed = false;
        st.panX = pinch.px + ((a[0].x + a[1].x) / 2 - pinch.mx);
        st.panY = pinch.py + ((a[0].y + a[1].y) / 2 - pinch.my);
        layoutVideo();
        return;
      }
      if (!g) return;
      if (g.mode === 'hold') return;
      const W = wrap.clientWidth || innerWidth, H = wrap.clientHeight || innerHeight;
      const dx = e.clientX - g.x, dy = e.clientY - g.y;
      if (!g.mode) {
        if (Math.abs(dx) < 14 && Math.abs(dy) < 14) return;
        clearTimeout(g.holdTimer);
        if (g.edge) { g.mode = 'none'; return; }
        if (st.zoom > 1.01 && !g.top) {
          // vergrößert: ein Finger verschiebt das Bild (wie Telegram) – Helligkeit/Lautstärke/Spulen erst wieder bei 1×
          g.mode = 'pan'; g.px0 = st.panX; g.py0 = st.panY; g.vs = [];
          vid.classList.remove('vzan'); st.panFree = true;
        } else g.mode = Math.abs(dx) > Math.abs(dy) ? 'seek' : (g.top ? 'none' : (g.x > W / 2 ? 'vol' : 'bright'));
        if (g.mode === 'none') return;
        // ab hier zählt jede Bewegung gleich (kein Sprung um die Anfangsstrecke); an den Enden sofort umkehrbar
        g.ly = e.clientY;
        g.v = g.mode === 'vol' ? st.level : st.bright;   // aus "Auto" heraus: von der aktuellen Helligkeit weiter
      }
      if (g.mode === 'pan') {
        const lim = panLimits(st.zoom);
        const rub = (v, l) => (v > l ? l + (v - l) / 3 : v < -l ? -l + (v + l) / 3 : v);   // über die Kante: ⅓ Weg
        st.panX = rub(g.px0 + dx, lim[0]);
        st.panY = rub(g.py0 + dy, lim[1]);
        g.vs.push([performance.now(), e.clientX, e.clientY]);
        if (g.vs.length > 6) g.vs.shift();
        layoutVideo();
        return;
      }
      const m = M();
      if (g.mode === 'seek') {
        g.delta = dx / W * 90;   // ganze Bildschirmbreite = 90 Sekunden
        const to = clamp(g.time + g.delta, 0, isFinite(m.duration) ? m.duration : g.time + g.delta);
        showSeek(to, m.duration, g.delta);
      } else if (g.mode === 'vol' || g.mode === 'bright') {
        // gut ein Drittel der Bildschirmhöhe = 0 → 100 % (im Querformat mindestens 180 Pixel)
        const range = Math.max(H * 0.36, 180);
        const step = -(e.clientY - g.ly) / range;
        g.ly = e.clientY;
        if (g.mode === 'vol') {
          g.v = clamp(g.v + step, 0, 1);
          showSide('vol', applyLevel(g.v), 1);
        } else {
          const canAuto = !!(st.sys && st.sys.bright);
          g.v = clamp(g.v + step, canAuto ? -AUTO_OVER - 0.04 : 0.15, 1);   // ohne Helfer: höchstens auf 15 % abdunkeln
          if (canAuto && g.v < -AUTO_OVER) {
            setAutoBright();
            showSide('bright', 0, 1, 'Auto');
          } else if (canAuto && st.autoB && g.v < 0) {
            showSide('bright', 0, 1, 'Auto');   // aus Auto wieder hoch: erst ab 0 % wird es wieder fest
          } else {
            applyBright(Math.max(0, g.v));
            showSide('bright', st.bright, 1);
          }
        }
      }
    });
    const endGest = (e) => {
      const had = pts.has(e.pointerId);
      pts.delete(e.pointerId);
      if (!had) return;
      if (pinch) {
        if (pts.size === 0) {
          if (st.zoom < 1.08) { st.zoom = 1; st.panX = st.panY = 0; layoutVideo(); }   // fast 1×: einrasten
          pinch = null;
        }
        return;
      }
      if (!g) return;
      const cur = g;
      g = null;
      clearTimeout(cur.holdTimer);
      if (cur.mode === 'hold') { M().playbackRate = st.speed; hideInd(); return; }
      if (cur.mode === 'pan') {
        // Schwung: in Wischrichtung weiter gleiten (aus den letzten ~100 ms), dann sanft an der Kante halten
        st.panFree = false;
        const v = cur.vs, a = v[0], z = v[v.length - 1];
        let px = st.panX, py = st.panY;
        if (a && z && z[0] - a[0] > 10 && performance.now() - z[0] < 80) {
          const dt = z[0] - a[0];
          px += clamp((z[1] - a[1]) / dt, -4, 4) * 160;
          py += clamp((z[2] - a[2]) / dt, -4, 4) * 160;
        }
        zoomTo(st.zoom, px, py);
        return;
      }
      if (cur.mode === 'seek') {
        const m = M();
        setTimeout(hideSeek, 350);   // kurz stehen lassen, dann weich ausblenden
        st.userSeekAt = Date.now();
        try { m.currentTime = clamp(cur.time + cur.delta, 0, isFinite(m.duration) ? m.duration - 0.5 : cur.time + cur.delta); } catch (err) {}
        return;
      }
      if (cur.mode) return;
      // kein Wischen = Tippen. Offenes Menü? Dann nur schließen.
      if (menuOpen()) { closeMenu(); return; }
      regainFs();   // holt ein verlorenes Vollbild zurück
      const W = wrap.clientWidth || innerWidth, now = Date.now();
      if (st.locked) {
        wrap.classList.add('ctl2');
        clearTimeout(st.u2);
        st.u2 = setTimeout(() => wrap.classList.remove('ctl2'), 2500);
        return;
      }
      const zone = cur.x < W * 0.35 ? -1 : cur.x > W * 0.65 ? 1 : 0;
      // Serie: nach einem Doppeltipp zählt jeder weitere schnelle Tipp auf derselben Seite 10 s dazu
      if (zone && st.skipRun && st.skipRun.zone === zone && now < st.skipRun.until) {
        clearTimeout(tapTimer);
        seekBy(zone * SKIP());
        st.skipRun.total += SKIP(); st.skipRun.until = now + 650;
        showSkipFx(zone, st.skipRun.total);
        return;
      }
      if (now - lastTapT < 300 && zone === lastZone) {
        clearTimeout(tapTimer);
        lastTapT = 0;
        if (zone) {
          seekBy(zone * SKIP());
          st.skipRun = { zone: zone, total: SKIP(), until: now + 650 };
          showSkipFx(zone, SKIP());
        }
        else tapZoom(cur.x, cur.y) || togglePlay();
        return;
      }
      lastTapT = now;
      lastZone = zone;
      tapTimer = setTimeout(toggleControls, 280);
    };
    gest.addEventListener('pointerup', endGest);
    gest.addEventListener('pointercancel', endGest);
    wrap.addEventListener('contextmenu', (e) => e.preventDefault());

    // ----- Zurück-Taste: im Querformat erst zurück ins Hochformat, sonst Player schließen -----
    // Vollbild verlassen (Zurück-Taste im Querformat) = nur zurück ins Hochformat, Player bleibt offen
    // Drehungen merken: manche Android-Versionen beenden beim Drehen kurz das Vollbild – das ist kein "Zurück"
    let lastTurn = 0;
    const onTurn = () => { lastTurn = Date.now(); };
    addEventListener('orientationchange', onTurn);
    try { screen.orientation && screen.orientation.addEventListener('change', onTurn); } catch (e) {}
    let lastAspect = innerWidth > innerHeight;
    const onResize = () => {
      const a = innerWidth > innerHeight;
      if (a !== lastAspect) {
        lastAspect = a; lastTurn = Date.now();
        if (st.tapZoomed) { st.tapZoomed = false; st.zoom = 1; st.panX = st.panY = 0; layoutVideo(); }   // gedreht: Doppeltipp-Zoom aufheben
      }
    };
    addEventListener('resize', onResize);
    st.cleanup.push(() => {
      removeEventListener('orientationchange', onTurn);
      removeEventListener('resize', onResize);
      try { screen.orientation && screen.orientation.removeEventListener('change', onTurn); } catch (e) {}
    });
    const onFs = () => {
      if (vp !== st || isFs()) return;
      const exitAt = Date.now(), turnBefore = lastTurn;
      // Vollbild endet wegen Bild-in-Bild (Knopf oder Wischen zum Startbildschirm): Player bleibt offen
      const pipNow = () => pipIsOurs(vid) || document.hidden || (st.pipAt && Date.now() - st.pipAt < 2500);
      if (pipNow()) { st.fsStart = false; st.fsLost = true; return; }
      // Kurz nach DEINEM Tipp auf "Drehen" beendet? Dann war es die Webseite (z. B. Pornhub beendet das Vollbild im Hochformat),
      // nicht die Zurück-Taste: Player offen lassen und Vollbild sofort zurückholen (der Tipp ist noch "frisch").
      if (st.selfTurnAt && exitAt - st.selfTurnAt < 1200) {
        enterFs().then(() => {
          if (isFs()) {
            st.fsStart = true;
            if (st.orient) lockO(st.orient);
          } else {
            st.fsStart = false;
            st.fsLost = true;
            if (st.pushHist) st.pushHist();
          }
        });
        return;
      }
      // kurz abwarten: kommt das Vollbild gleich zurück oder war es eine Drehung?
      setTimeout(() => {
        if (vp !== st || isFs()) return;
        if (pipNow()) { st.fsStart = false; st.fsLost = true; return; }
        // (Eingebettetes Fenster: dessen Größe springt beim Vollbild-Ende immer – dort zählt nur die Zurück-Taste.)
        // Gedreht VOR dem Vollbild-Ende (oder praktisch gleichzeitig) = Drehung hat das Vollbild beendet.
        // Gedreht erst DANACH = Folge der Zurück-Taste (das Bild springt nach dem Vollbild zurück ins Hochformat).
        // Über den Drehen-Knopf gesperrt? Dann kann sich das Handy nicht selbst drehen –
        // ein Vollbild-Ende ist dann immer die Zurück-Taste (Chrome dreht dabei oft schon VOR der Meldung zurück).
        const locked = !!st.orient;
        const rotated = !st.framed && !locked && ((exitAt - turnBefore < 1500) || (lastTurn >= exitAt && lastTurn - exitAt < 120));
        if (rotated) {
          // Vollbild durch Drehen verloren (Android oder die Webseite selbst): Player bleibt offen,
          // der nächste Tipp holt das Vollbild zurück. Zurück-Taste schließt ihn bis dahin über den Verlauf.
          st.fsStart = false;
          st.fsLost = true;
          if (st.pushHist) st.pushHist();   // ohne Vollbild: Zurück-Taste über den Verlauf abfangen (nicht auf Facebook/Instagram)
          return;
        }
        // Im Vollbild gestartet: Vollbild verlassen (Zurück-Taste) = Player schließen
        if (st.fsStart) { closePlayer(); return; }
        lockO('');
        st.orient = '';
      }, 400);
    };
    document.addEventListener('fullscreenchange', onFs);
    document.addEventListener('webkitfullscreenchange', onFs);
    // Eingebettet: Meldungen der Hauptseite (Vollbild an/aus, Ränder, Titel)
    if (st.framed) {
      fb.onState = (d) => {
        if (vp !== st) return;
        const was = !!st.rfs;
        st.rfs = !!d.fs;
        const ins = d.ins || [0, 0, 0, 0];
        ['t', 'r', 'b', 'l'].forEach((k, i) => wrap.style.setProperty('--evp-s' + k, (ins[i] || 0) + 'px'));
        setTitle();
        if (st.rfs && st.fsWait) st.fsWait();
        if (was !== st.rfs) { onFsLayout(); onFs(); }
      };
      // Hauptseite durfte nicht ins Vollbild schalten → selbst (geht, wenn das Fenster Vollbild erlaubt)
      fb.onFsNo = () => {
        if (vp !== st || !st.fsWait) return;
        const w = st.fsWait;
        try {
          const rq = wrap.requestFullscreen || wrap.webkitRequestFullscreen;
          const pr = rq && rq.call(wrap, { navigationUI: 'hide' });
          if (pr && pr.then) pr.then(w, w); else w();
        } catch (e) { w(); }
      };
      const ins0 = (fb.up && fb.up.ins) || null;
      if (ins0) ['t', 'r', 'b', 'l'].forEach((k, i) => wrap.style.setProperty('--evp-s' + k, (ins0[i] || 0) + 'px'));
      st.cleanup.push(() => { fb.onState = null; fb.onFsNo = null; if (st.framed) fpost(window.parent, { t: 'release' }); });
      if (!CONFIG.startFullscreen) fpost(window.parent, { t: 'take', fs: false });
    }
    // Ohne Vollbild würde die Zurück-Taste die Seite verlassen – dann ein Eintrag im Verlauf, der nur den Player schließt.
    // Im Vollbild ist das unnötig (Zurück beendet dort nur das Vollbild). Auf Facebook/Instagram nie: deren eigene
    // Verlaufs-Verwaltung hält das spätere Entfernen des Eintrags für ein echtes "Zurück" (→ Hauptseite).
    st.histPushed = false;
    const pushHist = () => {
      if (st.histPushed || META || vp !== st) return;
      try { history.pushState(history.state, '', location.href); st.histPushed = true; } catch (e) {}
    };
    st.pushHist = pushHist;
    const onPop = () => { if (vp === st) { st.histPushed = false; closePlayer(); } };
    addEventListener('popstate', onPop);
    st.cleanup.push(() => {
      document.removeEventListener('fullscreenchange', onFs);
      document.removeEventListener('webkitfullscreenchange', onFs);
      removeEventListener('popstate', onPop);
    });
    // Auf Wunsch (Einstellung startFullscreen) wie früher sofort im Vollbild starten
    if (CONFIG.startFullscreen) {
      enterFs().then(() => {
        if (isFs()) { st.fsStart = true; autoOrient(M().videoWidth, M().videoHeight); }
        else pushHist();   // Vollbild ging nicht: Zurück-Taste über den Verlauf abfangen
      });
    } else pushHist();

    // Später noch HD gefunden (Facebook)? NICHT mehr selbst umschalten – das konnte im Reels-Feed ein anderes Video sein.
    // Die neue Stufe erscheint nur im Qualitäts-Menü; du entscheidest.
    st.upgrade = () => {
      if (vp !== st || st.mode !== 'own') return;
      const fresh = playerSources(orig, st.srcRes);
      fresh.forEach((u) => { if (st.sources.indexOf(u) < 0) st.sources.push(u); });
      refreshQual();
    };

    // Video auf der Seite stumm schalten und anhalten, solange der eigene Player läuft (sonst kurz doppelter Ton).
    // Startet die Seite es von selbst wieder (Facebook macht das manchmal), sofort wieder anhalten.
    st.origMuted = orig.muted;
    // Am Ende anhalten statt Endlosschleife: Facebook/Instagram springen bei Reels still an den Anfang zurück
    // (oder laden neu). Das erkennen wir und halten an – Play-Knopf startet es noch einmal.
    st.endHold = false;
    let lastT = 0;
    const origLoop = orig.loop;
    try { orig.loop = false; } catch (e) {}
    const holdEnd = () => {
      if (vp !== st || st.mode !== 'capture') return;
      st.endHold = true;
      try { orig.pause(); } catch (e) {}
      showControls();
      updateUI();
    };
    const onOrigTime = () => {
      if (vp !== st || st.mode !== 'capture') return;
      const d = orig.duration, t = orig.currentTime;
      const userSeek = Date.now() - (st.userSeekAt || 0) < 1500;
      if (!userSeek && isFinite(d) && d > 3 && lastT > d - 1.5 && t < 1.5) holdEnd();   // von hinten nach vorn gesprungen = Schleife
      lastT = t;
    };
    const onOrigEnded = () => holdEnd();
    orig.addEventListener('timeupdate', onOrigTime);
    orig.addEventListener('ended', onOrigEnded);
    st.cleanup.push(() => {
      orig.removeEventListener('timeupdate', onOrigTime);
      orig.removeEventListener('ended', onOrigEnded);
      try { orig.loop = origLoop; } catch (e) {}
    });
    const onOrigPlay = () => {
      if (vp !== st) return;
      // eigener Player: Original bleibt still · gespiegelt und am Ende angehalten: die Seite darf nicht von selbst neu starten
      if (st.mode === 'own' || (st.mode === 'capture' && st.endHold)) { try { orig.pause(); } catch (e) {} }
    };
    orig.addEventListener('play', onOrigPlay);
    orig.addEventListener('playing', onOrigPlay);
    st.cleanup.push(() => { orig.removeEventListener('play', onOrigPlay); orig.removeEventListener('playing', onOrigPlay); });

    // Original anhalten (außer wir übernehmen dessen Bild) und mit dem Laden beginnen
    if (st.sources.length) { try { orig.muted = true; orig.pause(); } catch (e) {} tryNext(); }
    else useCapture();
    showControls();
  }

  // Wächter nach dem Schließen: Läuft das Video auf der Seite wirklich weiter? Hängt es, einmal anstupsen.
  function watchStall(o) {
    let last = o.currentTime, still = 0, n = 0;
    const iv = setInterval(() => {
      n++;
      const c = o.currentTime;
      if (c > last + 0.2 || !o.isConnected) { clearInterval(iv); return; }   // läuft (oder Video weg)
      still++;
      if (still === 3) {
        // kleiner Sprung + Play: bringt hängende Web-Player meist wieder in Gang
        try { o.currentTime = c + 0.05; const pr = o.play(); if (pr && pr.catch) pr.catch(() => {}); } catch (e) {}
      }
      if (still >= 7 || n > 12) clearInterval(iv);
      last = c;
    }, 1000);
  }

  // Läuft UNSER Video im kleinen Fenster? (im Shadow-DOM meldet document nur den äußeren Wirt, nicht das Video selbst)
  function pipIsOurs(v) {
    try {
      if (root && root.pictureInPictureElement) return root.pictureInPictureElement === v;
      const d = document.pictureInPictureElement;
      return !!d && (d === v || d === hostEl);
    } catch (e) { return false; }
  }
  function closePlayer() {
    const st = vp;
    if (!st) return;
    vp = null;
    // Kleines Fenster (Bild-in-Bild) gleich mitschließen – sonst bliebe es pausiert und tot stehen
    try {
      if (pipIsOurs(st.vid)) { const pr = document.exitPictureInPicture(); if (pr && pr.catch) pr.catch(() => {}); }
      st.vid.disablePictureInPicture = true;   // beendet Bild-in-Bild auch dort, wo exitPictureInPicture nicht greift
    } catch (e) {}
    clearTimeout(st.loadTimer); clearTimeout(st.hideTimer); clearTimeout(st.u2);
    const o = st.orig;
    // Deine Stelle ZUERST merken – wird hls.js beendet, springt das eigene Video auf 0 zurück.
    // An diese Stelle springt dann das Video auf der Seite, auch bei stückweise ladenden Seiten (Facebook, Instagram, Pornhub …).
    // Gleiche Länge prüfen, damit nicht z. B. eine Werbung oder ein anderes Video verstellt wird.
    let target = -1;
    try {
      const t = st.vid.currentTime, vd = st.vid.duration, od = o.duration;
      const sameLen = !isFinite(od) || !isFinite(vd) || Math.abs(od - vd) < 3;
      if (st.mode === 'own' && t > 0 && sameLen) target = t;
    } catch (e) {}
    if (st.hls) { try { st.hls.destroy(); } catch (e) {} }
    if (st.actx) { try { st.actx.close(); } catch (e) {} }
    try { st.vid.pause(); st.vid.srcObject = null; st.vid.removeAttribute('src'); st.vid.load(); } catch (e) {}
    if (target >= 0) { try { o.currentTime = target; } catch (e) {} }
    try { if (typeof st.origMuted === 'boolean') o.muted = st.origMuted; } catch (e) {}   // ursprünglichen Ton zurückgeben
    // Video auf der Seite weiterlaufen lassen (Instagram & Co. haben oft keinen Play-Knopf)
    if (CONFIG.resumeAfterClose) {
      setTimeout(() => {
        try { const pr = o.play(); if (pr && pr.catch) pr.catch(() => {}); } catch (e) {}
        watchStall(o);
      }, 200);
    }
    try { if (document.fullscreenElement && document.exitFullscreen) document.exitFullscreen().catch(() => {}); } catch (e) {}
    try { if (screen.orientation && screen.orientation.unlock) screen.orientation.unlock(); } catch (e) {}
    st.cleanup.forEach((f) => { try { f(); } catch (e) {} });
    // Mit ✕ geschlossen: unseren Verlaufseintrag wieder entfernen, damit "Zurück" danach normal funktioniert
    if (st.histPushed) { try { history.back(); } catch (e) {} }
    if (toastEl && root) { root.append(toastEl); toastEl.style.top = ''; toastEl.classList.remove('g'); toastEl.style.removeProperty('--lens'); toastEl.style.removeProperty('--gb'); if (lensRO) lensRO.unobserve(toastEl); }
    st.wrap.remove();
  }

  // Tipp auf "Player": eingebauter Player (Standard) oder externe App
  function onPlayerTap() {
    const v = active;
    if (!v) return;
    if (CONFIG.defaultPlayer === 'external' && !DOWNLOAD_ONLY) { onPillMain(); return; }
    const go = () => {
      if (toastEl) toastEl.classList.remove('on');
      // Sofort öffnen – nicht auf die HD-Suche warten. Kommt Facebook-HD danach noch, wechselt der Player nahtlos.
      const p = META && !FB_MIRROR ? fetchHD(v) : PH ? phMp4() : null;
      openBrowserPlayer(v);
      if (p && META) p.then(() => { if (vp && vp.orig === v && vp.upgrade) vp.upgrade(); }, () => {});
    };
    // Facebook direkt nach dem Scrollen: noch unklar, welches Video läuft (Nachbarvideos werden vorgeladen)?
    // Dann kurz warten, bis die Wiedergabe es eindeutig macht – höchstens 2,5 Sekunden.
    if (!FB_MIRROR && /(^|\.)facebook\.com$/i.test(location.hostname) && !assetFor(v).sure) {
      const t0 = Date.now();
      toast('Erkenne Video …');
      const poll = () => {
        if (vp) return;
        if (assetFor(v).sure || Date.now() - t0 > 2500) go(); else setTimeout(poll, 150);
      };
      setTimeout(poll, 150);
      return;
    }
    go();
  }

  // ---------- Download: Link GENAU dieses Videos an YTDLnis & Co. teilen ----------
  // Beitrags-Adressen bekannter Seiten (YouTube, Facebook, Instagram, X, Reddit, TikTok, Vimeo …)
  const POST_RE = /\/status\/\d+|\/comments\/[a-z0-9]+|\/(?:p|reels?|tv)\/[A-Za-z0-9_-]{5,}|\/video\/\d+|\/videos\/(?:[^\/?#]+\/)?\d+|\/watch\/?\?(?:.*&)?v=[\w-]{6,}|[?&]v=[\w-]{6,}|\/shorts\/[\w-]+|story\.php\?story_fbid=\d+|fb\.watch\/\w+|vimeo\.com\/\d+|view_video\.php\?(?:.*&)?viewkey=\w+|\/video\d+\/|\/video\/x[a-z0-9]+|\/video\/BV\w+|\/videos\/[\w-]+-\d+|\/clip\/[\w-]+|\/v[a-z0-9]{5,}-[\w-]+\.html|video-?\d+_\d+|\/embed\/[\w-]{5,}/i;
  const IG_POST_RE = /\/(?:p|reels?|tv)\/(?!audio\/)([A-Za-z0-9_-]{5,})/;
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

  function postUrlFor(v) {
    // Die Seite selbst ist schon die Beitragsseite (z. B. Reel, YouTube-Video)
    if (POST_RE.test(location.href) && !NOT_POST_RE.test(location.href)) return location.href.split('#')[0];
    if (!v) return '';
    // TikTok-Feed: Link genau dieses Videos – NIE die Feed-Adresse (/foryou)
    if (TT) return ttPostUrl(v);
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
    collapse();
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
      navigator.share({ url: url }).catch((e) => { if (!e || e.name !== 'AbortError') fallback(); });
    } else {
      fallback();
    }
  }

  function typeShort(u) {
    const m = String(u).match(MEDIA_RE);
    return m ? m[1].toUpperCase() : '?';
  }

  // ---------- Diagnose auch ohne Button: über das Violentmonkey-Menü ----------
  function biggestVideo() {
    let best = null, area = 0;
    allVideos().forEach((v) => { const a = visibleArea(v); if (a > area) { area = a; best = v; } });
    return best || allVideos()[0] || null;
  }
  function videoReport() {
    const vids = allVideos();
    const lines = ['Rahmen: ' + (window.top === window ? 'Hauptseite' : 'eingebettet (iframe) auf ' + location.hostname),
      'Alle Videos (' + vids.length + ', davon im Shadow-DOM: ' + shadowVids.length + '):'];
    vids.slice(0, 8).forEach((v, i) => {
      const r = v.getBoundingClientRect();
      lines.push(' ' + (i + 1) + '. ' + Math.round(r.width) + '×' + Math.round(r.height) +
        ' | ' + (v.paused ? 'pausiert' : 'läuft') + ' | sichtbar ' + Math.round(visibleArea(v)) +
        ' | ' + String(v.currentSrc || v.src || '-').slice(0, 60));
    });
    const fr = document.getElementsByTagName('iframe');
    lines.push('Eingebettete Fenster (iframes): ' + fr.length + (fr.length ? ' – ' + Array.prototype.slice.call(fr, 0, 4).map((f) => hostOf(f.src) || '-').join(', ') : ''));
    lines.push('Download-Button hier: ' + (SHOW_DOWNLOAD ? 'ja' : 'nein') + ' | nur Download: ' + (DOWNLOAD_ONLY ? 'ja' : 'nein'));
    try {
      lines.push('Oberfläche: ' + (!hostEl ? 'nicht erstellt' :
        (hostEl.isConnected ? 'eingesetzt in ' + (hostEl.parentNode && hostEl.parentNode.nodeName) : 'von der Seite entfernt') +
        ' | display=' + getComputedStyle(hostEl).display + ' | visibility=' + getComputedStyle(hostEl).visibility +
        ' | Button: ' + (pill ? pill.className + ' @ ' + pill.style.left + ',' + pill.style.top + ' | Breite ' + pill.offsetWidth : '-') +
        ' | Bereich: ' + (typeof GM_info !== 'undefined' && GM_info && GM_info.injectInto ? GM_info.injectInto : '?')));
    } catch (e) {}
    return lines.join('\n');
  }
  function showDiagBox(text) {
    ui();
    const box = h('div', { class: 'diag' });
    const ta = h('textarea', { readonly: 'readonly' });
    ta.value = text;
    const copy = h('button', null, 'Kopieren');
    const close = h('button', null, 'Schließen');
    copy.addEventListener('click', () => { ta.select(); copyText(text, 'Diagnose kopiert'); });
    close.addEventListener('click', () => box.remove());
    box.append(h('div', { class: 'dh' }, 'Video-Assistent · Diagnose'), ta, h('div', { class: 'db' }, copy, close));
    root.append(box);
    attachHost();
    // Prüfen, ob das Fenster wirklich sichtbar ist – sonst Notlösung im Aufrufer
    if (!hostEl || !hostEl.isConnected || !box.getBoundingClientRect().height) { box.remove(); throw new Error('Fenster nicht sichtbar'); }
  }
  function openDiag() {
    let text = '';
    try {
      const v = active || biggestVideo();
      text = diag(v, resolve(v)) + '\n' + videoReport();
    } catch (e) {
      text = 'Fehler beim Erstellen der Diagnose: ' + ((e && (e.stack || e.message)) || e);
      try { text += '\n' + videoReport(); } catch (e2) {}
    }
    try {
      showDiagBox(text);
    } catch (e) {
      // Notlösung: Eingabefeld des Browsers – dort lässt sich der Text markieren und kopieren
      try { prompt('Diagnose – alles markieren und kopieren:', text.replace(/\n/g, ' | ')); }
      catch (e2) { try { alert(text); } catch (e3) {} }
    }
  }
  try {
    const reg = (typeof GM_registerMenuCommand === 'function' && GM_registerMenuCommand) ||
      (typeof GM !== 'undefined' && GM && typeof GM.registerMenuCommand === 'function' && GM.registerMenuCommand);
    if (reg) reg(window.top === window ? 'Diagnose anzeigen' : 'Diagnose anzeigen (eingebettet: ' + location.hostname + ')', openDiag);
  } catch (e) {}

  // ---------- Diagnose (lange auf "Player" drücken) ----------
  function diag(v, r) {
    return [
      'Video-Assistent 16.5',
      'Seite: ' + location.host + location.pathname,
      'Video-src: ' + (v ? String(v.currentSrc || v.src || '-').slice(0, 90) : 'kein Video'),
      'Ergebnis: ' + (r.how || 'nichts') + (r.best ? ' – ' + r.best.slice(0, 110) : ''),
      'Zuordnung über: ' + (lastVia || '-') + ' | Laufendes Video: ' + (function () { const f = assetFor(v); return (f.asset || '-') + ' (' + f.via + (f.sure ? ', sicher' : '') + ')'; })() +
        ' | geladene Videos: ' + Array.from(assetHits.keys()).slice(-4).map((a) => a + '×' + assetHits.get(a).length).join(', '),
      'Spiegeln: ' + (vp && vp.mode === 'capture' ? 'aktiv, ' + (vp.mirrorFix || 0) + '× neu verbunden' + (vp.mirrorWhy ? ' (zuletzt: ' + vp.mirrorWhy + ')' : '') : (FB_MIRROR ? 'bereit' : 'aus')),
      'Puffer-Spur: ' + (function () { if (!v) return '-'; const m = creditsFor(v, 0); const l = (bufCredit.get(v) || []).length; return l + ' Zuwächse | ' + (Array.from(m.entries()).map((e) => e[0].slice(-6) + ':' + e[1].toFixed(1)).join(', ') || '-'); })(),
      'HD nachladen: ' + hdLog + ' | Weiterleitung: ' + lastResolve + (PH ? ' | Pornhub-MP4: ' + phLog : '') + (IG ? ' | Instagram-MP4: ' + igLog : '') + (TT ? ' | TikTok: ' + ttLog + ' · ' + (ttPostUrl(v) || 'kein Link') : ''),
      'Zuletzt geöffnet: ' + (lastIntent.t ? Math.round((Date.now() - lastIntent.t) / 1000) + ' s her | ' + lastIntent.how + ' | ' + hostOf(lastIntent.url) + ' …' + lastIntent.url.slice(-50) : '-'),
      'Kodierung: ' + (r.best ? encTag(r.best) || '-' : '-'),
      'IDs am Video: ' + (r.ids.slice(0, 12).join(', ') || '-'),
      'Meta-Einträge: ' + metaCount + ' (Schlüssel: ' + metaMap.size + ') | API-Antworten: ' + bodiesRead + ' | Netzwerk-Quellen: ' + sources.length,
      'Videos auf der Seite: ' + allVideos().length,
      'Browser: ' + navigator.userAgent.slice(0, 120),
      'Reihenfolge beim Tippen (' + ((r.list || []).length) + '):',
    ].concat((r.list || []).map((x, i) => ' ' + (i + 1) + '. ' + x.how + ' | ' + typeShort(x.u) + ' | ' + hostOf(x.u) + ' …' + x.u.slice(-40)))
    .concat([
      'Netzwerk-Quellen (letzte 5):',
    ]).concat(recent().slice(-5).map((x) => ' - ' + typeShort(x) + ' | ' + hostOf(x) + ' …' + x.slice(-40)))
    .concat([
      'Adressen zu diesem Video (' + lastNear.length + '):',
    ]).concat(lastNear.slice(0, 12).map((c, i) =>
      ' ' + (i + 1) + '. ' + c.kind + ' ' + (c.res ? c.res + 'p' : '?') + ' | ID ' + (c.asset || '-') + ' | ' + (c.tag || 'kein Tag').slice(0, 50) + ' | ' + String(c.from || '').slice(0, 40)
    )).join('\n');
  }
})();
