// ==UserScript==
// @name         Video-Assistent (externer Player)
// @namespace    externer-player
// @version      19.28
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
  // Skript UND Erweiterung (Kadri) gleichzeitig installiert? Sie sehen sich nicht (getrennte Bereiche) – darum ein
  // Zeichen an der Seite: Wer zuerst da ist, läuft; der andere bleibt still (sonst zwei Knöpfe, zwei Player).
  try {
    const de = document.documentElement;
    if (de) { if (de.hasAttribute('data-evp-on')) return; de.setAttribute('data-evp-on', ''); }
  } catch (e) {}
  // In der Erweiterung „Kadri“ gebaut? (Die Erweiterung setzt __KADRI__ vor dem Skript.) Dort sieht man ohne
  // laufende Helfer-App nur, was auch ohne sie geht (keine Helligkeit usw.).
  const IS_KADRI = typeof __KADRI__ !== 'undefined' && !!__KADRI__;
  const HIDE_NO_HELPER = IS_KADRI;
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
    // Browser). false = selbst nach der Videodatei suchen.
    fbMirror: true,
    // Lupen-Glas wie bei iOS 26 / macOS: das Bild hinter Knöpfen und Reglern wird am Rand gebogen und leicht vergrößert.
    // false = einfaches Milchglas (spart etwas Rechenleistung)
    lensGlass: false,
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
    dialogBoost: false,       // Dialog-Verstärker (Stimmen deutlicher) – im ⋮-Menü umschaltbar
    // Spul-Vorschau: 'thumb' = kleines Vorschaubild über der Leiste (Standard), 'live' = das Video selbst springt beim
    // Ziehen mit (geht überall, auch beim Spiegeln)
    seekPreview: 'thumb',
    openUi: 'quiet',          // Beim Öffnen: 'quiet' = nur das Bild (Bedienung nach dem ersten Antippen), 'controls' = gleich mit Bedienung
    seekZoom: true,           // Lupe beim Spulen: Finger unter die Leiste = feiner (Ausschnitt ~1 min)
    seekBuzz: true,           // kurzes Vibrieren beim Spulen (Wechsel der Lupen-Stufe)
    leaveAction: 'play',      // in eine andere App wechseln: 'play' = weiterlaufen lassen (wie der Browser es erlaubt), 'pause' = anhalten
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
    // 'share' = Teilen-Menü (z. B. für Seal oder andere Apps). Lange auf ⬇ drücken zeigt immer alle Wege.
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
    'skipSeconds', 'systemHelper', 'downloader', 'autoRotate', 'subSize', 'subHigh', 'language', 'leaveAction', 'dialogBoost', 'seekPreview',
    'seekZoom', 'seekBuzz', 'openUi',
    // Seitenlisten (in Kadri über die Einstellungsseite änderbar; ein eigener Wert ersetzt die Liste oben)
    'ytdlpSites', 'downloadOnlySites', 'disabledSites'];
  const CONFIG_DEFAULT = {};
  SETTING_KEYS.forEach((k) => { CONFIG_DEFAULT[k] = CONFIG[k]; });
  const userSettings = () => { const o = store.get('settings', {}); return o && typeof o === 'object' ? o : {}; };
  (() => { const o = userSettings(); SETTING_KEYS.forEach((k) => {
    if (!(k in o) || o[k] === null) return;
    if (Array.isArray(CONFIG[k]) && !(Array.isArray(o[k]) && o[k].every((x) => typeof x === 'string'))) return;   // Seitenliste kaputt → Standard
    CONFIG[k] = o[k];
  }); })();
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
    'In externer App öffnen': 'Open in external app', 'Schließen': 'Close', 'Wiedergabe': 'Play',
    'Geschwindigkeit': 'Speed', 'Bild-in-Bild': 'Picture-in-picture', 'Qualität': 'Quality', 'Bildformat': 'Aspect ratio',
    'Drehen': 'Rotate', 'Sperren': 'Lock', 'Bedienung sperren': 'Lock controls', 'Entsperren': 'Unlock', 'Mehr': 'More',
    'Untertitel': 'Subtitles', 'Position': 'Position', 'Format': 'Format', 'Auto': 'Auto', 'Stufe': 'Level',
    // Menüs
    'Einstellungen': 'Settings', 'Tonspur': 'Audio track', 'Standbild speichern': 'Save snapshot', 'Aus': 'Off', 'An': 'On',
    'aus': 'off', 'an': 'on', 'Datei laden …': 'Load file …', '.srt, .vtt oder .ttml vom Handy': '.srt, .vtt or .ttml from your phone',
    'Größe': 'Size', 'klein': 'small', 'mittel': 'medium', 'groß': 'large', 'Lage': 'Position', 'unten im Bild': 'bottom of picture',
    'oben im Bild': 'top of picture', 'Früher zeigen': 'Show earlier', 'Später zeigen': 'Show later', 'eigene Datei': 'own file',
    'von der Seite': 'from the page', 'Suche Untertitel der Seite …': 'Looking for subtitles on the page …',
    'Automatisch': 'Automatic', 'passt sich der Verbindung an': 'adapts to your connection', 'Original': 'Original',
    'Füllen': 'Fill', 'Strecken': 'Stretch',
    // Einstellungen (kurz)
    'Design': 'Design', 'Glas': 'Glass', 'Schlicht': 'Plain', 'Vollbild': 'Full screen', 'Ambient': 'Ambient',
    'Fortschrittslinie': 'Progress line', 'Sprungweite': 'Skip', 'Abbrechen': 'Cancel', 'Halten': 'Hold', 'Lupe': 'Zoom', 'Spul-Lupe': 'Seek zoom', 'Beim Öffnen': 'On open', 'Nur Bild': 'Picture only', 'Mit Bedienung': 'With controls', 'Vibration': 'Vibration', 'LIVE': 'LIVE', 'Video hat gewechselt – Spulen abgebrochen': 'Video changed – seeking cancelled', 'Spul-Vorschau': 'Seek preview', 'Im Bild': 'In video', 'Kleines Bild': 'Thumbnail', 'Wischsperre oben': 'Swipe guard top', 'Wischsperre unten': 'Swipe guard bottom', 'Schmal': 'Narrow', 'Mittel': 'Medium', 'Breit': 'Wide', 'Sehr breit': 'Extra wide',
    'Link teilen': 'Share link', 'Zurück': 'Back', 'Wiederholen': 'Repeat', 'Wiederholen aus': 'Repeat off', 'Wiederholen: endlos': 'Repeat: endless', 'Sleep-Timer': 'Sleep timer', 'Ende des Videos': 'End of video', 'Läuft': 'Running', 'Sleep-Timer aus': 'Sleep timer off', 'Sleep-Timer: angehalten': 'Sleep timer: paused', 'Sleep-Timer: Ende des Videos': 'Sleep timer: end of video', 'Dialog-Verstärker': 'Dialogue boost', 'Dialog-Verstärker an': 'Dialogue boost on', 'Dialog-Verstärker aus': 'Dialogue boost off', 'Dialog-Verstärker: hier nicht möglich (die Seite gibt den Ton nicht frei)': 'Dialogue boost: not possible here (the site doesn’t release the sound)', 'In App öffnen': 'Open in app', 'Beim Verlassen': 'When leaving', 'Weiter': 'Keep playing', 'Anhalten': 'Pause', 'Antippen für Vollbild': 'Tap for full screen', 'z. B. VLC': 'e.g. VLC', 'Link kopiert': 'Link copied', 'Kein Link zu diesem Video gefunden': 'No link to this video found',
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
    [/^Sleep-Timer: (\d+) min$/, (m) => 'Sleep timer: ' + m[1] + ' min'],
    [/^noch (\d+) min$/, (m) => m[1] + ' min left'],
    [/^(\d+) min$/, (m) => m[1] + ' min'],
    [/^Wiederholen: (\d+)×$/, (m) => 'Repeat: ' + m[1] + '×'],
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
  // Schlüssel für Nachrichten nach OBEN (eingebettetes Fenster → Hauptseite): Nur das Skript kennt ihn (sein eigener
  // Speicher, für Webseiten unlesbar). So kann eingebettete Werbung nicht so tun, als wäre sie der Player, und sich
  // nicht selbst über den ganzen Bildschirm legen lassen. Nach unten geht er nie mit; täglich ein neuer (den macht nur
  // die Hauptseite – eingebettete Fenster lesen ihn). Ohne Speicher des Managers (GM_…) gibt es keinen sicheren Ort:
  // dann gar kein Schlüssel – und damit keine Nachrichten von unten (Player bleibt im Kasten).
  const FSEC_BASE = (() => {
    try {
      if (typeof GM_getValue !== 'function' || typeof GM_setValue !== 'function') return null;
      const o = GM_getValue('frameKey', null), now = Date.now();
      if (o && typeof o.k === 'string' && o.k.length >= 24 && (IN_FRAME || now - (o.t || 0) < 864e5)) return o.k;
      if (IN_FRAME) return '';
      const a = new Uint8Array(18); crypto.getRandomValues(a);
      const k = Array.from(a, (b) => b.toString(16).padStart(2, '0')).join('');
      GM_setValue('frameKey', { k: k, t: now });
      return k;
    } catch (e) { return ''; }
  })();
  // Pro Seite ein eigener Schlüssel: aus dem geheimen Schlüssel und der Adresse der HAUPTSEITE berechnet. Die
  // Hauptseite kann den Schlüssel ihrer Fenster zwar mitlesen – er gilt aber nur für sie selbst und hilft auf keiner
  // anderen Seite. Die Adresse der Hauptseite kennt ein Fenster nur über den Browser (ancestorOrigins – nicht
  // fälschbar). Verbirgt die Seite sie, gibt es keinen Schlüssel (Player dann im Kasten). Browser ganz ohne diese
  // Angabe (Firefox vor 2026, Firefox ESR): ebenso kein Schlüssel – der gemeinsame Schlüssel würde über die
  // Nachrichten an fremde Hauptseiten gelangen und auf jeder anderen Seite gelten. Der Player im eingebetteten Fenster
  // füllt dann nur dessen Kasten (Vollbild wie dort üblich).
  const HAS_AO = (() => { try { return 'ancestorOrigins' in location; } catch (e) { return false; } })();
  const KEY_ORIGIN = (() => {
    if (!IN_FRAME) return location.origin;
    if (!HAS_AO) return '';
    try { const a = location.ancestorOrigins, o = a && a.length ? a[a.length - 1] : ''; return o && o !== 'null' ? o : '-'; } catch (e) { return '-'; }
  })();
  const FSEC = !FSEC_BASE || !HAS_AO || KEY_ORIGIN === '-' ? '' : sha256hex(FSEC_BASE + '|' + KEY_ORIGIN).slice(0, 40);
  function sha256hex(str) {
    const K = [0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3,
      0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
      0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
      0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
      0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
      0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2];
    const bytes = Array.from(new TextEncoder().encode(str));
    const bitLen = bytes.length * 8;
    bytes.push(0x80);
    while (bytes.length % 64 !== 56) bytes.push(0);
    for (let i = 7; i >= 0; i--) bytes.push(i >= 4 ? 0 : (bitLen >>> (i * 8)) & 255);
    const H = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19];
    const W = new Array(64), rot = (x, n) => (x >>> n) | (x << (32 - n));
    for (let o = 0; o < bytes.length; o += 64) {
      for (let i = 0; i < 16; i++) W[i] = (bytes[o + i * 4] << 24) | (bytes[o + i * 4 + 1] << 16) | (bytes[o + i * 4 + 2] << 8) | bytes[o + i * 4 + 3];
      for (let i = 16; i < 64; i++) {
        const s0 = rot(W[i - 15], 7) ^ rot(W[i - 15], 18) ^ (W[i - 15] >>> 3), s1 = rot(W[i - 2], 17) ^ rot(W[i - 2], 19) ^ (W[i - 2] >>> 10);
        W[i] = (W[i - 16] + s0 + W[i - 7] + s1) | 0;
      }
      let [a, b, c, d, e, f, g, h] = H;
      for (let i = 0; i < 64; i++) {
        const t1 = (h + (rot(e, 6) ^ rot(e, 11) ^ rot(e, 25)) + ((e & f) ^ (~e & g)) + K[i] + W[i]) | 0;
        const t2 = ((rot(a, 2) ^ rot(a, 13) ^ rot(a, 22)) + ((a & b) ^ (a & c) ^ (b & c))) | 0;
        h = g; g = f; f = e; e = (d + t1) | 0; d = c; c = b; b = a; a = (t1 + t2) | 0;
      }
      H[0] = (H[0] + a) | 0; H[1] = (H[1] + b) | 0; H[2] = (H[2] + c) | 0; H[3] = (H[3] + d) | 0;
      H[4] = (H[4] + e) | 0; H[5] = (H[5] + f) | 0; H[6] = (H[6] + g) | 0; H[7] = (H[7] + h) | 0;
    }
    return H.map((x) => (x >>> 0).toString(16).padStart(8, '0')).join('');
  }
  const fpost = (w, m) => { try { m[FK] = 1; if (FSEC && w === window.parent && w !== window) m.k = FSEC; w.postMessage(m, '*'); } catch (e) {} };
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
  // Würde der Browser jetzt Vollbild erlauben (frische Berührung, auch in einem eingebetteten Fenster)? Ohne die
  // Angabe (ältere Browser) einfach versuchen.
  // Kurz vibrieren (Spulen: Lupe, Abbrechen, Anfang/Ende). Chrome erlaubt das, sobald man die Seite überhaupt einmal
  // angetippt hat – NICHT nur direkt nach einem Tipp (Ziehen zählt nicht als Tipp: daran lag es, dass es oft still blieb)
  function seekBuzz(ms) {
    try {
      if (CONFIG.seekBuzz === false || !navigator.vibrate) return;
      const ua = navigator.userActivation;
      if (ua && !ua.hasBeenActive) return;
      navigator.vibrate(ms || 12);
    } catch (e) {}
  }
  function touchFresh() { try { return !navigator.userActivation || navigator.userActivation.isActive; } catch (e) { return true; } }
  function frameFs(f) {
    try {
      if (fsElem() === f) return;
      // ohne Berührung lehnt Chrome ohnehin ab (und meldet einen Fehler). Firefox zählt den Tipp im eingebetteten
      // Fenster hier oben nicht mit, erlaubt es aber trotzdem – dort also einfach versuchen
      if (!GECKO && !touchFresh()) { sendState(); if (fb.childWin) fpost(fb.childWin, { t: 'fsno' }); return; }
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
    // Nachricht von UNTEN (aus einem eingebetteten Fenster) – nur mit dem Schlüssel des Skripts
    if (!FSEC || d.k !== FSEC) return;
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
  // ----- Kadri: Spiegeln + Bild-in-Bild -----
  // Facebook hält sein Video an, sobald die Seite verdeckt ist – beim Spiegeln stünde dann auch das kleine Fenster.
  // In Kadri läuft ein Skript IN der Seite (page.js): Solange gespiegelt wird, verbirgt es „verdeckt“ vor der Seite
  // („Schild“). Dabei hält es die Meldung an, bevor irgendwer sie bekommt – auch dieses Skript. Darum reicht es sie
  // als VIS_EV weiter, und die eigenen Zuhörer hören auf beides.
  const VIS_EV = 'kadri-vis';
  function onVisChange(f, cap) { document.addEventListener('visibilitychange', f, cap); if (IS_KADRI) document.addEventListener(VIS_EV, f, cap); }
  function offVisChange(f, cap) { document.removeEventListener('visibilitychange', f, cap); if (IS_KADRI) document.removeEventListener(VIS_EV, f, cap); }
  // Läuft page.js in dieser Seite (Chrome/Vivaldi)? Kurz nachfragen – page.js antwortet sofort. (Kein Merkmal an der
  // Seite, an dem Webseiten Kadri erkennen könnten.) Einmal „ja“ gilt für immer.
  let kadriPageOk = false;
  const kadriPage = () => {
    if (!IS_KADRI) return false;
    if (kadriPageOk) return true;
    try {
      let ok = false;
      const f = () => { ok = true; };
      document.addEventListener('kadri-pong', f);
      document.dispatchEvent(new Event('kadri-ping'));
      document.removeEventListener('kadri-pong', f);
      kadriPageOk = ok;
      return ok;
    } catch (e) { return false; }
  };
  function kadriShield(on) {
    if (!kadriPage()) return;
    try { document.dispatchEvent(new CustomEvent('kadri-ctl', { detail: JSON.stringify({ shield: !!on }) })); } catch (e) {}
  }

  const MEDIA_RE =/\.(m3u8|mpd|mp4|webm|mkv|mov|m4v)(\?|#|$)/i;
  const PLAYLIST_RE = /\.(m3u8|mpd)(\?|#|$)/i;
  const SEGMENT_RE = /(seg|chunk|frag|init)[-_]?\d*\.(mp4|m4s|ts)(\?|$)|[?&](bytestart|byteend|range)=/i;
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
  // Kadri: auf Facebook zuerst der EIGENE Player mit der fertigen MP4 – aber nur, wenn sicher ist, dass sie zum
  // laufenden Video gehört (gleiche Video-ID wie die gerade geladenen Stücke); sonst spiegeln
  const FB_TRY_OWN = FB_MIRROR && IS_KADRI;
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
    noteSegment(u);
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
  // viermal pro Sekunde: genug, um Einspeisen und Ankunft zu trennen (spart Akku) – nur wo es gebraucht wird
  if (META && (!FB_MIRROR || FB_TRY_OWN)) setInterval(watchBuffers, 250);
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
    const i = sources.findIndex((s) => s.u === u);
    if (i >= 0) {
      // die Seite lädt sie erneut (z. B. zurück zu einem früheren Video): gilt ab jetzt wieder als neu.
      // Lädt sie nur der eigene Player, bleibt der Zeitpunkt der Seite.
      if (vp && vp.mode === 'own') return;
      const s = sources.splice(i, 1)[0]; s.t = Date.now(); sources.push(s);
      return;
    }
    sources.push({ u: u, t: Date.now() });
    if (sources.length > 40) sources.shift();
  }

  // ---------- Nachweis: welcher Stream läuft WIRKLICH? ----------
  // Statt nach dem Zeitpunkt zu raten: Die Stream-Listen (m3u8/mpd), die die Seite ohnehin lädt, werden mitgelesen
  // (kein zusätzlicher Abruf). Daraus ist bekannt, in welchem Ordner die Stücke jedes Streams liegen, welche
  // Unter-Listen zu welcher Haupt-Liste gehören und wie lang das Video ist. Lädt die Seite dann Stücke aus einem
  // dieser Ordner, ist bewiesen, welcher Stream läuft.
  const urlKey = (u) => { try { const x = new URL(u, location.href); return x.origin + x.pathname; } catch (e) { return String(u).split(/[?#]/)[0]; } };
  const dirOf = (u) => urlKey(u).replace(/[^\/]*$/, '');
  const plInfo = new Map();    // urlKey → { u, kids:Set(urlKey), segDirs:[Ordner], dur, live, t }
  const segHits = new Map();   // Ordner der Stücke → zuletzt geladen
  const SEGFILE_RE = /\.(ts|m4s|mp4|m4a|m4v|aac|cmfv|cmfa|webm|mp3)(\?|#|$)/i;
  function isoDur(s) {
    const m = /P(?:(\d+(?:\.\d+)?)D)?T?(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?/.exec(s || '');
    if (!m) return NaN;
    return (+m[1] || 0) * 86400 + (+m[2] || 0) * 3600 + (+m[3] || 0) * 60 + (+m[4] || 0);
  }
  function notePlaylist(reqUrl, finalUrl, text) {
    try {
      if (typeof text !== 'string' || text.length > 4e6) return;
      const base = finalUrl || reqUrl;
      const abs = (x) => { try { return new URL(x, base).href; } catch (e) { return ''; } };
      const info = { u: reqUrl, kids: new Set(), segDirs: [], dur: NaN, live: false, t: Date.now() };
      const dirs = new Set();
      const addDir = (a) => { if (!a) return; const d = dirOf(a); try { if (new URL(d).pathname.length > 1) dirs.add(d); } catch (e) {} };
      const head = text.slice(0, 600).trimStart();
      if (head.startsWith('#EXTM3U')) {
        const lines = text.split(/\r?\n/);
        let sum = 0, segs = 0, kidNext = false;
        for (let i = 0; i < lines.length; i++) {
          const l = lines[i].trim();
          if (!l) continue;
          if (l[0] === '#') {
            if (l.startsWith('#EXTINF:')) { sum += parseFloat(l.slice(8)) || 0; segs++; }
            else if (l.startsWith('#EXT-X-STREAM-INF')) kidNext = true;
            else if (l.startsWith('#EXT-X-MEDIA:') || l.startsWith('#EXT-X-I-FRAME-STREAM-INF')) { const m = /URI="([^"]+)"/.exec(l); if (m) info.kids.add(urlKey(abs(m[1]))); }
            else if (l.startsWith('#EXT-X-MAP:')) { const m = /URI="([^"]+)"/.exec(l); if (m) addDir(abs(m[1])); }
            continue;
          }
          if (kidNext) { info.kids.add(urlKey(abs(l))); kidNext = false; }
          else addDir(abs(l));
        }
        if (segs) { info.live = text.indexOf('#EXT-X-ENDLIST') < 0; if (!info.live) info.dur = sum; }
      } else if (/<MPD[\s>]/.test(head) || /<MPD[\s>]/.test(text.slice(0, 4000))) {
        info.live = /\btype\s*=\s*["']dynamic["']/.test(text);
        const m = /mediaPresentationDuration\s*=\s*["']([^"']+)["']/.exec(text);
        if (m && !info.live) info.dur = isoDur(m[1]);
        addDir(base);   // DASH: Stücke liegen bei der mpd (oder unter ihrer BaseURL)
        const re = /<BaseURL[^>]*>([^<]+)<\/BaseURL>/g; let b, n = 0;
        while ((b = re.exec(text)) && n++ < 8) { const x = b[1].trim(); addDir(abs(/\/$/.test(x) ? x + 'x' : x)); }
      } else return;
      info.segDirs = Array.from(dirs).slice(0, 12);
      plInfo.set(urlKey(reqUrl), info);
      if (finalUrl && urlKey(finalUrl) !== urlKey(reqUrl)) plInfo.set(urlKey(finalUrl), info);
      while (plInfo.size > 80) plInfo.delete(plInfo.keys().next().value);
    } catch (e) {}
  }
  // Erweiterung Kadri: Ein winziges Skript IN der Seite (page.js) liest die Stream-Listen und – auf Facebook/Instagram –
  // die API-Antworten mit, die die Seite lädt, und reicht sie hierher. Im abgetrennten Bereich (Userscript im Modus
  // „content“) sieht das Skript diese Anfragen sonst nicht. Nur in der Erweiterung aktiv.
  if (IS_KADRI) {
    document.addEventListener('kadri-net', (e) => {
      // (die Seite kann solche Meldungen auch selbst schicken: nur Text, höchstens ~8 MB, sonst nichts tun)
      if (typeof e.detail !== 'string' || e.detail.length > 8e6) return;
      let d = null;
      try { d = JSON.parse(e.detail); } catch (x) { d = null; }
      if (!d || typeof d.u !== 'string' || typeof d.t !== 'string') return;
      if (d.k === 'pl') notePlaylist(d.u, typeof d.f === 'string' ? d.f : '', d.t);
      else if (d.k === 'src') { if (typeof KADRI_HOOKS !== 'undefined' && KADRI_HOOKS.onSrc) KADRI_HOOKS.onSrc(d.u, d.f === 'mpd' ? 'mpd' : 'm3u8'); }
      else if (d.k === 'api' && META) { bodiesRead++; try { scanText(d.t); } catch (x) {} }
    });
  }
  // Kadri: Der Hintergrund der Erweiterung meldet Stream-Listen, die der Tab lädt – nach dem, was der Server sagt
  // („Content-Type“), also auch ohne .m3u8 im Namen und aus Hintergrund-Skripten der Seite (Worker), die sonst
  // niemand sieht. Ohne Endung bekommt die Adresse ein Kennzeichen hinter „#“ (geht nicht an den Server), damit der
  // Player sie als Stream lädt.
  if (IS_KADRI && typeof KADRI_HOOKS !== 'undefined') {
    KADRI_HOOKS.onSrc = (u, k) => {
      if (typeof u !== 'string' || !/^https?:/i.test(u) || SEGMENT_RE.test(u)) return;
      if (!PLAYLIST_RE.test(u)) u += k === 'mpd' ? '#kadri.mpd' : '#kadri.m3u8';
      addSource(u);
    };
  }
  // Kadri: page.js hebt seine letzten Meldungen auf – jetzt, wo alles zuhört, noch einmal schicken lassen
  // (in eingebetteten Fenstern startet Kadri erst, wenn ein Video auftaucht)
  if (IS_KADRI) { try { document.dispatchEvent(new Event('kadri-hello')); } catch (e) {} }
  function noteSegment(u) {
    if (PLAYLIST_RE.test(u) || !(SEGFILE_RE.test(u) || SEGMENT_RE.test(u))) return;
    if (vp && vp.mode === 'own') return;   // Stücke des eigenen Players zählen nicht
    const d = dirOf(u);
    segHits.delete(d); segHits.set(d, Date.now());
    if (segHits.size > 120) segHits.delete(segHits.keys().next().value);
  }
  // Der Stream, aus dem die Seite seit „since“ zuletzt Stücke geladen hat – als Haupt-Liste (alle Qualitäten)
  function playingStream(since) {
    const now = Date.now(), from = Math.max(since || 0, now - 30000);
    let media = null, mediaT = 0;
    segHits.forEach((t, d) => {
      if (t < from || t <= mediaT) return;
      let m = null;
      plInfo.forEach((p) => { if (p.segDirs.some((sd) => d.startsWith(sd)) && (!m || p.t > m.t)) m = p; });
      if (m) { media = m; mediaT = t; }
    });
    if (!media) return null;
    const mk = urlKey(media.u);
    let master = null;
    plInfo.forEach((p) => { if (p.kids.has(mk) && (!master || p.t > master.t)) master = p; });
    let u = master ? master.u : '';
    if (!u) {   // Haupt-Liste nicht lesbar: die kurz davor (nach dem letzten Videowechsel) geladene, die nichts anderes enthält
      const near = sources.filter((s) => PLAYLIST_RE.test(s.u) && s.t >= navAt && s.t <= media.t + 500 && s.t >= media.t - 8000 && urlKey(s.u) !== mk)
        .map((s) => s.u).filter((x) => { const p = plInfo.get(urlKey(x)); return !p || (!p.kids.size && !p.segDirs.length); });
      u = rankPlaylists(near).filter((x) => /master|manifest|playlist/i.test(x))[0] || media.u;
    }
    return { u: u, dur: media.dur, live: media.live };
  }
  // Länge eines Streams, soweit bekannt (aus der eigenen Liste oder einer ihrer Unter-Listen)
  function streamLen(u) {
    const p = plInfo.get(urlKey(u));
    if (!p) return null;
    if (p.live) return { live: true };
    if (isFinite(p.dur)) return { dur: p.dur };
    for (const k of p.kids) { const q = plInfo.get(k); if (q && q.live) return { live: true }; if (q && isFinite(q.dur)) return { dur: q.dur }; }
    return null;
  }
  const lenTol = (d) => Math.max(4, d * 0.015);

  try {
    new PerformanceObserver((list) => {
      list.getEntries().forEach((e) => seeUrl(e.name, e.responseEnd > 0 && performance.timeOrigin ? performance.timeOrigin + e.responseEnd : 0));
    }).observe({ type: 'resource', buffered: true });
  } catch (e) {}

  const API_RE = /graphql|\/api\//i;
  let rawFetch = null;   // ungehaktes fetch – für eigene Abfragen, die NICHT mitgelesen werden sollen
  try {
    // Antwort mitlesen, aber höchstens max Zeichen: zu Große gar nicht erst ganz einlesen (Länge vorab, sonst Stück für
    // Stück und abbrechen)
    const readCapped = (r, max) => {
      try {
        if (+(r.headers.get('content-length') || 0) > max) return Promise.resolve('');
        const c = r.clone();
        if (!c.body || !c.body.getReader || typeof TextDecoder !== 'function') return c.text().then((t) => (t.length > max ? '' : t));
        const rd = c.body.getReader(), dec = new TextDecoder();
        let out = '', n = 0;
        const pump = () => rd.read().then(({ done, value }) => {
          if (done) return out + dec.decode();
          n += value.length;
          if (n > max) { rd.cancel().catch(() => {}); return ''; }
          out += dec.decode(value, { stream: true });
          return pump();
        });
        return pump();
      } catch (e) { return Promise.resolve(''); }
    };
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
        if (PLAYLIST_RE.test(u)) p.then((res) => { if (res && res.ok) readCapped(res, 4e6).then((t) => { if (t) notePlaylist(u, res.url, t); }, () => {}); }, () => {});
        if (META && API_RE.test(u)) {
          p.then((res) => { if (res) readCapped(res, 8e6).then((t) => { if (t) { bodiesRead++; scanText(t); } }, () => {}); }, () => {});
        }
        return p;
      };
    }
    const oo = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function (m, url) {
      try {
        const u = String(url);
        seeUrl(u);
        if (PLAYLIST_RE.test(u)) {
          this.addEventListener('load', function () {
            try { if (this.status < 400 && (!this.responseType || this.responseType === 'text')) notePlaylist(new URL(u, location.href).href, this.responseURL, this.responseText); } catch (e) {}
          });
        }
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

  // Wechsel zu einem anderen Video OHNE Neuladen (ARD, YouTube & Co. tauschen nur den Stream im selben Player):
  // Ab hier gelten nur noch Adressen, die danach geladen wurden. Als Zeitpunkt zählt das letzte Mal, dass noch die
  // alte Adresse zu sehen war (nicht erst die Prüfung danach) – so fällt nichts Neues heraus.
  const hrefKey = () => location.href.split('#')[0];
  let lastHref = hrefKey(), hrefSeenAt = Date.now();
  function checkNav() {
    const now = Date.now(), h = hrefKey();
    if (h !== lastHref) { lastHref = h; navAt = Math.max(navAt, hrefSeenAt - 300); }
    hrefSeenAt = now;
  }
  addEventListener('popstate', () => setTimeout(checkNav, 0));

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
    let m, n = 0;
    RX_URL.lastIndex = 0;
    while ((m = RX_URL.exec(text)) && n++ < 3000) {   // (höchstens 3000 Fundstellen – nie stundenlang suchen)
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
  // Die ganze Seite nach Video-Adressen durchsuchen – auf großen Seiten (Facebook: viele MB) teuer. Darum nur einmal
  // (der Seitentext enthält auch die Skripte) und das Ergebnis 1,5 s lang merken: beim Öffnen fragt der Player mehrmals.
  let wholeMemo = { at: 0, href: '', n: -1, list: [] };
  function scanWholePage(out, seen) {
    const now = Date.now(), n = document.getElementsByTagName('*').length;
    if (now - wholeMemo.at > 1500 || wholeMemo.href !== location.href || wholeMemo.n !== n) {
      const list = [];
      try { collectUrls(document.documentElement.innerHTML, list, new Set(), 'seite'); } catch (e) {}
      wholeMemo = { at: now, href: location.href, n: n, list: list };
    }
    for (const c of wholeMemo.list) {
      if (out.length >= 400) break;
      if (seen.has(c.u)) continue;
      seen.add(c.u); out.push(Object.assign({}, c));
    }
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
  // Wie oft: in der ersten Minute (nach Laden oder Videowechsel) alle 3 s, danach alle 15 s – und gleich nach einem
  // Tipp oder wenn etwas zu spielen beginnt (dann tauchen Player meist auf)
  let shadowVids = [], shadowAt = 0;
  const shadowFrom = Date.now();
  const shadowGap = () => (Date.now() - Math.max(shadowFrom, navAt || 0) < 60000 ? 3000 : 15000);
  const shadowSoon = () => { if (Date.now() - shadowAt > 1000) shadowAt = 0; };
  document.addEventListener('pointerdown', shadowSoon, true);
  document.addEventListener('play', shadowSoon, true);
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
    if (Date.now() - shadowAt > shadowGap()) { shadowAt = Date.now(); shadowVids = scanShadow(); }
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

// Pinned PSL data and matching algorithm from Owndot/Kadri @ 4097e7b77e62b6a9f2419e80a1686d35dc0c43b9.
// Instantiate on first credential-site lookup; no network fetch and no new dependency.
// Erzeugt von tools/make-psl.js aus vendor/public_suffix_list.dat (SHA-256 d3e6243b51695327984abb3e2a5aa7077096867f75121e031e6a5a3840ca8bcd).
// Public Suffix List, https://publicsuffix.org – Mozilla Public License 2.0 (siehe psl.LICENSE).
// pslBase(host) → die „registrierbare Domain“ (eTLD+1): www.bbc.co.uk → bbc.co.uk, alice.eu.org → alice.eu.org.
// Ist host selbst eine öffentliche Endung (github.io), kommt host zurück.
'use strict';
const PSL_LIST = "ac,com.ac,edu.ac,gov.ac,mil.ac,net.ac,org.ac,ad,ae,ac.ae,co.ae,gov.ae,mil.ae,net.ae,org.ae,sch.ae,aero,airline.aero,airport.aero,accident-investigation.aero,accident-prevention.aero,aerobatic.aero,aeroclub.aero,aerodrome.aero,agents.aero,air-surveillance.aero,air-traffic-control.aero,aircraft.aero,airtraffic.aero,ambulance.aero,association.aero,author.aero,ballooning.aero,broker.aero,caa.aero,cargo.aero,catering.aero,certification.aero,championship.aero,charter.aero,civilaviation.aero,club.aero,conference.aero,consultant.aero,consulting.aero,control.aero,council.aero,crew.aero,design.aero,dgca.aero,educator.aero,emergency.aero,engine.aero,engineer.aero,entertainment.aero,equipment.aero,exchange.aero,express.aero,federation.aero,flight.aero,freight.aero,fuel.aero,gliding.aero,government.aero,groundhandling.aero,group.aero,hanggliding.aero,homebuilt.aero,insurance.aero,journal.aero,journalist.aero,leasing.aero,logistics.aero,magazine.aero,maintenance.aero,marketplace.aero,media.aero,microlight.aero,modelling.aero,navigation.aero,parachuting.aero,paragliding.aero,passenger-association.aero,pilot.aero,press.aero,production.aero,recreation.aero,repbody.aero,res.aero,research.aero,rotorcraft.aero,safety.aero,scientist.aero,services.aero,show.aero,skydiving.aero,software.aero,student.aero,taxi.aero,trader.aero,trading.aero,trainer.aero,union.aero,workinggroup.aero,works.aero,af,com.af,edu.af,gov.af,net.af,org.af,ag,co.ag,com.ag,net.ag,nom.ag,org.ag,ai,com.ai,net.ai,off.ai,org.ai,al,com.al,edu.al,gov.al,mil.al,net.al,org.al,am,co.am,com.am,commune.am,net.am,org.am,ao,co.ao,ed.ao,edu.ao,gov.ao,gv.ao,it.ao,og.ao,org.ao,pb.ao,aq,ar,bet.ar,com.ar,coop.ar,edu.ar,gob.ar,gov.ar,int.ar,mil.ar,musica.ar,mutual.ar,net.ar,org.ar,seg.ar,senasa.ar,tur.ar,arpa,e164.arpa,home.arpa,in-addr.arpa,ip6.arpa,iris.arpa,uri.arpa,urn.arpa,as,gov.as,asia,at,ac.at,sth.ac.at,co.at,gv.at,or.at,au,asn.au,com.au,edu.au,gov.au,id.au,net.au,org.au,conf.au,oz.au,act.au,nsw.au,nt.au,qld.au,sa.au,tas.au,vic.au,wa.au,act.edu.au,catholic.edu.au,nsw.edu.au,nt.edu.au,qld.edu.au,sa.edu.au,tas.edu.au,vic.edu.au,wa.edu.au,qld.gov.au,sa.gov.au,tas.gov.au,vic.gov.au,wa.gov.au,aw,com.aw,ax,az,biz.az,co.az,com.az,edu.az,gov.az,info.az,int.az,mil.az,name.az,net.az,org.az,pp.az,pro.az,ba,com.ba,edu.ba,gov.ba,mil.ba,net.ba,org.ba,bb,biz.bb,co.bb,com.bb,edu.bb,gov.bb,info.bb,net.bb,org.bb,store.bb,tv.bb,bd,ac.bd,ai.bd,co.bd,com.bd,edu.bd,gov.bd,id.bd,info.bd,it.bd,mil.bd,net.bd,org.bd,sch.bd,tv.bd,be,ac.be,bf,gov.bf,bg,0.bg,1.bg,2.bg,3.bg,4.bg,5.bg,6.bg,7.bg,8.bg,9.bg,a.bg,b.bg,c.bg,d.bg,e.bg,f.bg,g.bg,h.bg,i.bg,j.bg,k.bg,l.bg,m.bg,n.bg,o.bg,p.bg,q.bg,r.bg,s.bg,t.bg,u.bg,v.bg,w.bg,x.bg,y.bg,z.bg,bh,com.bh,edu.bh,gov.bh,net.bh,org.bh,bi,co.bi,com.bi,edu.bi,or.bi,org.bi,biz,bj,africa.bj,agro.bj,architectes.bj,assur.bj,avocats.bj,co.bj,com.bj,eco.bj,econo.bj,edu.bj,info.bj,loisirs.bj,money.bj,net.bj,org.bj,ote.bj,restaurant.bj,resto.bj,tourism.bj,univ.bj,bm,com.bm,edu.bm,gov.bm,net.bm,org.bm,bn,com.bn,edu.bn,gov.bn,net.bn,org.bn,bo,com.bo,edu.bo,gob.bo,int.bo,mil.bo,net.bo,org.bo,tv.bo,web.bo,academia.bo,agro.bo,arte.bo,blog.bo,bolivia.bo,ciencia.bo,cooperativa.bo,democracia.bo,deporte.bo,ecologia.bo,economia.bo,empresa.bo,ia.bo,indigena.bo,industria.bo,info.bo,medicina.bo,movimiento.bo,musica.bo,natural.bo,nombre.bo,noticias.bo,patria.bo,plurinacional.bo,politica.bo,profesional.bo,pueblo.bo,revista.bo,salud.bo,tecnologia.bo,tksat.bo,transporte.bo,wiki.bo,br,9guacu.br,abc.br,adm.br,adv.br,agr.br,aju.br,am.br,anani.br,aparecida.br,api.br,app.br,arq.br,art.br,ato.br,b.br,barueri.br,belem.br,bet.br,bhz.br,bib.br,bio.br,blog.br,bmd.br,boavista.br,bsb.br,campinagrande.br,campinas.br,caxias.br,cim.br,cng.br,cnt.br,com.br,contagem.br,coop.br,coz.br,cri.br,cuiaba.br,curitiba.br,def.br,des.br,det.br,dev.br,ecn.br,eco.br,edu.br,emp.br,enf.br,eng.br,esp.br,etc.br,eti.br,far.br,feira.br,flog.br,floripa.br,fm.br,fnd.br,fortal.br,fot.br,foz.br,fst.br,g12.br,geo.br,ggf.br,goiania.br,gov.br,ac.gov.br,al.gov.br,am.gov.br,ap.gov.br,ba.gov.br,ce.gov.br,df.gov.br,es.gov.br,go.gov.br,ma.gov.br,mg.gov.br,ms.gov.br,mt.gov.br,pa.gov.br,pb.gov.br,pe.gov.br,pi.gov.br,pr.gov.br,rj.gov.br,rn.gov.br,ro.gov.br,rr.gov.br,rs.gov.br,sc.gov.br,se.gov.br,sp.gov.br,to.gov.br,gru.br,ia.br,imb.br,ind.br,inf.br,jab.br,jampa.br,jdf.br,joinville.br,jor.br,jus.br,leg.br,leilao.br,lel.br,log.br,londrina.br,macapa.br,maceio.br,manaus.br,maringa.br,mat.br,med.br,mil.br,morena.br,mp.br,mus.br,natal.br,net.br,niteroi.br,*.nom.br,not.br,ntr.br,odo.br,ong.br,org.br,osasco.br,palmas.br,poa.br,ppg.br,pro.br,psc.br,psi.br,pvh.br,qsl.br,radio.br,rec.br,recife.br,rep.br,ribeirao.br,rio.br,riobranco.br,riopreto.br,salvador.br,sampa.br,santamaria.br,santoandre.br,saobernardo.br,saogonca.br,seg.br,sjc.br,slg.br,slz.br,social.br,sorocaba.br,srv.br,taxi.br,tc.br,tec.br,teo.br,the.br,tmp.br,trd.br,tur.br,tv.br,udi.br,vet.br,vix.br,vlog.br,wiki.br,xyz.br,zlg.br,bs,com.bs,edu.bs,gov.bs,net.bs,org.bs,bt,com.bt,edu.bt,gov.bt,net.bt,org.bt,bv,bw,ac.bw,co.bw,gov.bw,net.bw,org.bw,by,gov.by,mil.by,com.by,of.by,bz,co.bz,com.bz,edu.bz,gov.bz,net.bz,org.bz,ca,ab.ca,bc.ca,mb.ca,nb.ca,nf.ca,nl.ca,ns.ca,nt.ca,nu.ca,on.ca,pe.ca,qc.ca,sk.ca,yk.ca,gc.ca,cat,cc,cd,gov.cd,cf,cg,ch,ci,ac.ci,xn--aroport-bya.ci,asso.ci,co.ci,com.ci,ed.ci,edu.ci,go.ci,gouv.ci,int.ci,net.ci,or.ci,org.ci,*.ck,!www.ck,cl,co.cl,gob.cl,gov.cl,mil.cl,cm,co.cm,com.cm,gov.cm,net.cm,cn,ac.cn,com.cn,edu.cn,gov.cn,mil.cn,net.cn,org.cn,xn--55qx5d.cn,xn--od0alg.cn,xn--io0a7i.cn,ah.cn,bj.cn,cq.cn,fj.cn,gd.cn,gs.cn,gx.cn,gz.cn,ha.cn,hb.cn,he.cn,hi.cn,hk.cn,hl.cn,hn.cn,jl.cn,js.cn,jx.cn,ln.cn,mo.cn,nm.cn,nx.cn,qh.cn,sc.cn,sd.cn,sh.cn,sn.cn,sx.cn,tj.cn,tw.cn,xj.cn,xz.cn,yn.cn,zj.cn,co,com.co,edu.co,gov.co,mil.co,net.co,nom.co,org.co,com,coop,cr,ac.cr,co.cr,ed.cr,fi.cr,go.cr,or.cr,sa.cr,cu,com.cu,edu.cu,gob.cu,inf.cu,nat.cu,net.cu,org.cu,cv,com.cv,edu.cv,id.cv,int.cv,net.cv,nome.cv,org.cv,publ.cv,cw,com.cw,edu.cw,net.cw,org.cw,cx,gov.cx,cy,ac.cy,biz.cy,com.cy,ekloges.cy,gov.cy,ltd.cy,mil.cy,net.cy,org.cy,press.cy,pro.cy,tm.cy,cz,gov.cz,de,dj,dk,dm,co.dm,com.dm,edu.dm,gov.dm,net.dm,org.dm,do,art.do,com.do,edu.do,gob.do,gov.do,mil.do,net.do,org.do,sld.do,web.do,dz,art.dz,asso.dz,com.dz,edu.dz,gov.dz,net.dz,org.dz,pol.dz,soc.dz,tm.dz,ec,abg.ec,adm.ec,agron.ec,arqt.ec,art.ec,bar.ec,chef.ec,com.ec,cont.ec,cpa.ec,cue.ec,dent.ec,dgn.ec,disco.ec,doc.ec,edu.ec,eng.ec,esm.ec,fin.ec,fot.ec,gal.ec,gob.ec,gov.ec,gye.ec,ibr.ec,info.ec,k12.ec,lat.ec,loj.ec,med.ec,mil.ec,mktg.ec,mon.ec,net.ec,ntr.ec,odont.ec,org.ec,pro.ec,prof.ec,psic.ec,psiq.ec,pub.ec,rio.ec,rrpp.ec,sal.ec,tech.ec,tul.ec,tur.ec,uio.ec,vet.ec,xxx.ec,edu,ee,aip.ee,com.ee,edu.ee,fie.ee,gov.ee,lib.ee,med.ee,org.ee,pri.ee,riik.ee,eg,ac.eg,com.eg,edu.eg,eun.eg,gov.eg,info.eg,me.eg,mil.eg,name.eg,net.eg,org.eg,sci.eg,sport.eg,tv.eg,*.er,es,com.es,edu.es,gob.es,nom.es,org.es,et,biz.et,com.et,edu.et,gov.et,info.et,name.et,net.et,org.et,eu,fi,aland.fi,fj,ac.fj,biz.fj,com.fj,edu.fj,gov.fj,id.fj,info.fj,mil.fj,name.fj,net.fj,org.fj,pro.fj,*.fk,fm,com.fm,edu.fm,net.fm,org.fm,fo,fr,asso.fr,com.fr,gouv.fr,nom.fr,prd.fr,tm.fr,avoues.fr,cci.fr,greta.fr,huissier-justice.fr,ga,gb,gd,edu.gd,gov.gd,ge,com.ge,cyb.ge,edu.ge,gov.ge,llc.ge,net.ge,online.ge,org.ge,pvt.ge,school.ge,tnx.ge,gf,gg,co.gg,net.gg,org.gg,gh,biz.gh,com.gh,edu.gh,gov.gh,mil.gh,net.gh,org.gh,gi,com.gi,edu.gi,gov.gi,ltd.gi,mod.gi,org.gi,gl,co.gl,com.gl,edu.gl,net.gl,org.gl,gm,gn,ac.gn,com.gn,edu.gn,gov.gn,net.gn,org.gn,gov,gp,asso.gp,com.gp,edu.gp,mobi.gp,net.gp,org.gp,gq,gr,com.gr,edu.gr,gov.gr,net.gr,org.gr,gs,gt,com.gt,edu.gt,gob.gt,ind.gt,mil.gt,net.gt,org.gt,gu,com.gu,edu.gu,gov.gu,guam.gu,info.gu,net.gu,org.gu,web.gu,gw,gy,co.gy,com.gy,edu.gy,gov.gy,net.gy,org.gy,hk,com.hk,edu.hk,gov.hk,idv.hk,net.hk,org.hk,xn--ciqpn.hk,xn--gmqw5a.hk,xn--55qx5d.hk,xn--mxtq1m.hk,xn--lcvr32d.hk,xn--wcvs22d.hk,xn--gmq050i.hk,xn--uc0atv.hk,xn--uc0ay4a.hk,xn--od0alg.hk,xn--zf0avx.hk,xn--mk0axi.hk,xn--tn0ag.hk,xn--od0aq3b.hk,xn--io0a7i.hk,hm,hn,com.hn,edu.hn,gob.hn,mil.hn,net.hn,org.hn,hr,com.hr,from.hr,iz.hr,name.hr,ht,adult.ht,art.ht,asso.ht,com.ht,coop.ht,edu.ht,firm.ht,gouv.ht,info.ht,med.ht,net.ht,org.ht,perso.ht,pol.ht,pro.ht,rel.ht,shop.ht,hu,2000.hu,agrar.hu,bolt.hu,casino.hu,city.hu,co.hu,erotica.hu,erotika.hu,film.hu,forum.hu,games.hu,hotel.hu,info.hu,ingatlan.hu,jogasz.hu,konyvelo.hu,lakas.hu,media.hu,news.hu,org.hu,priv.hu,reklam.hu,sex.hu,shop.hu,sport.hu,suli.hu,szex.hu,tm.hu,tozsde.hu,utazas.hu,video.hu,id,ac.id,ai.id,biz.id,co.id,desa.id,go.id,kop.id,mil.id,my.id,net.id,or.id,ponpes.id,sch.id,web.id,xn--9tfky.id,ie,gov.ie,il,ac.il,co.il,gov.il,idf.il,k12.il,muni.il,net.il,org.il,xn--4dbrk0ce,xn--4dbgdty6c.xn--4dbrk0ce,xn--5dbhl8d.xn--4dbrk0ce,xn--8dbq2a.xn--4dbrk0ce,xn--hebda8b.xn--4dbrk0ce,im,ac.im,co.im,ltd.co.im,plc.co.im,com.im,net.im,org.im,tt.im,tv.im,in,5g.in,6g.in,ac.in,aero.in,ai.in,alumni.in,am.in,bank.in,bihar.in,biz.in,business.in,ca.in,cn.in,co.in,com.in,coop.in,cs.in,delhi.in,dr.in,edu.in,er.in,fin.in,firm.in,gen.in,gov.in,gujarat.in,ind.in,info.in,int.in,internet.in,io.in,me.in,mil.in,net.in,nic.in,org.in,pg.in,post.in,pro.in,res.in,school.in,travel.in,tv.in,ub.in,uk.in,up.in,us.in,info,int,eu.int,io,co.io,com.io,edu.io,gov.io,mil.io,net.io,nom.io,org.io,iq,com.iq,edu.iq,gov.iq,mil.iq,net.iq,org.iq,ir,ac.ir,co.ir,gov.ir,id.ir,net.ir,org.ir,sch.ir,xn--mgba3a4f16a.ir,xn--mgba3a4fra.ir,is,it,edu.it,gov.it,abr.it,abruzzo.it,aosta-valley.it,aostavalley.it,bas.it,basilicata.it,cal.it,calabria.it,cam.it,campania.it,emilia-romagna.it,emiliaromagna.it,emr.it,friuli-v-giulia.it,friuli-ve-giulia.it,friuli-vegiulia.it,friuli-venezia-giulia.it,friuli-veneziagiulia.it,friuli-vgiulia.it,friuliv-giulia.it,friulive-giulia.it,friulivegiulia.it,friulivenezia-giulia.it,friuliveneziagiulia.it,friulivgiulia.it,fvg.it,laz.it,lazio.it,lig.it,liguria.it,lom.it,lombardia.it,lombardy.it,lucania.it,mar.it,marche.it,mol.it,molise.it,piedmont.it,piemonte.it,pmn.it,pug.it,puglia.it,sar.it,sardegna.it,sardinia.it,sic.it,sicilia.it,sicily.it,taa.it,tos.it,toscana.it,trentin-sud-tirol.it,xn--trentin-sd-tirol-rzb.it,trentin-sudtirol.it,xn--trentin-sdtirol-7vb.it,trentin-sued-tirol.it,trentin-suedtirol.it,trentino-a-adige.it,trentino-aadige.it,trentino-alto-adige.it,trentino-altoadige.it,trentino-s-tirol.it,trentino-stirol.it,trentino-sud-tirol.it,xn--trentino-sd-tirol-c3b.it,trentino-sudtirol.it,xn--trentino-sdtirol-szb.it,trentino-sued-tirol.it,trentino-suedtirol.it,trentinoa-adige.it,trentinoaadige.it,trentinoalto-adige.it,trentinoaltoadige.it,trentinos-tirol.it,trentinostirol.it,trentinosud-tirol.it,xn--trentinosd-tirol-rzb.it,xn--trentinosdtirol-7vb.it,trentinosued-tirol.it,trentinosuedtirol.it,trentinsud-tirol.it,xn--trentinsd-tirol-6vb.it,trentinsudtirol.it,xn--trentinsdtirol-nsb.it,trentinsued-tirol.it,trentinsuedtirol.it,tuscany.it,umb.it,umbria.it,val-d-aosta.it,val-daosta.it,vald-aosta.it,valle-aosta.it,valle-d-aosta.it,valle-daosta.it,valleaosta.it,valled-aosta.it,valledaosta.it,vallee-aoste.it,xn--valle-aoste-ebb.it,vallee-d-aoste.it,xn--valle-d-aoste-ehb.it,valleeaoste.it,xn--valleaoste-e7a.it,valleedaoste.it,xn--valledaoste-ebb.it,vao.it,vda.it,ven.it,veneto.it,ag.it,agrigento.it,al.it,alessandria.it,alto-adige.it,altoadige.it,an.it,ancona.it,andria-barletta-trani.it,andria-trani-barletta.it,andriabarlettatrani.it,andriatranibarletta.it,ao.it,aosta.it,aoste.it,ap.it,aq.it,ar.it,arezzo.it,ascoli-piceno.it,ascolipiceno.it,asti.it,at.it,av.it,avellino.it,ba.it,balsan.it,balsan-sudtirol.it,xn--balsan-sdtirol-nsb.it,balsan-suedtirol.it,bari.it,barletta-trani-andria.it,barlettatraniandria.it,belluno.it,benevento.it,bergamo.it,bg.it,bi.it,biella.it,bl.it,bn.it,bo.it,bologna.it,bolzano.it,bolzano-altoadige.it,bozen.it,bozen-sudtirol.it,xn--bozen-sdtirol-2ob.it,bozen-suedtirol.it,br.it,brescia.it,brindisi.it,bs.it,bt.it,bulsan.it,bulsan-sudtirol.it,xn--bulsan-sdtirol-nsb.it,bulsan-suedtirol.it,bz.it,ca.it,cagliari.it,caltanissetta.it,campidano-medio.it,campidanomedio.it,campobasso.it,carbonia-iglesias.it,carboniaiglesias.it,carrara-massa.it,carraramassa.it,caserta.it,catania.it,catanzaro.it,cb.it,ce.it,cesena-forli.it,xn--cesena-forl-mcb.it,cesenaforli.it,xn--cesenaforl-i8a.it,ch.it,chieti.it,ci.it,cl.it,cn.it,co.it,como.it,cosenza.it,cr.it,cremona.it,crotone.it,cs.it,ct.it,cuneo.it,cz.it,dell-ogliastra.it,dellogliastra.it,en.it,enna.it,fc.it,fe.it,fermo.it,ferrara.it,fg.it,fi.it,firenze.it,florence.it,fm.it,foggia.it,forli-cesena.it,xn--forl-cesena-fcb.it,forlicesena.it,xn--forlcesena-c8a.it,fr.it,frosinone.it,ge.it,genoa.it,genova.it,go.it,gorizia.it,gr.it,grosseto.it,iglesias-carbonia.it,iglesiascarbonia.it,im.it,imperia.it,is.it,isernia.it,kr.it,la-spezia.it,laquila.it,laspezia.it,latina.it,lc.it,le.it,lecce.it,lecco.it,li.it,livorno.it,lo.it,lodi.it,lt.it,lu.it,lucca.it,macerata.it,mantova.it,massa-carrara.it,massacarrara.it,matera.it,mb.it,mc.it,me.it,medio-campidano.it,mediocampidano.it,messina.it,mi.it,milan.it,milano.it,mn.it,mo.it,modena.it,monza.it,monza-brianza.it,monza-e-della-brianza.it,monzabrianza.it,monzaebrianza.it,monzaedellabrianza.it,ms.it,mt.it,na.it,naples.it,napoli.it,no.it,novara.it,nu.it,nuoro.it,og.it,ogliastra.it,olbia-tempio.it,olbiatempio.it,or.it,oristano.it,ot.it,pa.it,padova.it,padua.it,palermo.it,parma.it,pavia.it,pc.it,pd.it,pe.it,perugia.it,pesaro-urbino.it,pesarourbino.it,pescara.it,pg.it,pi.it,piacenza.it,pisa.it,pistoia.it,pn.it,po.it,pordenone.it,potenza.it,pr.it,prato.it,pt.it,pu.it,pv.it,pz.it,ra.it,ragusa.it,ravenna.it,rc.it,re.it,reggio-calabria.it,reggio-emilia.it,reggiocalabria.it,reggioemilia.it,rg.it,ri.it,rieti.it,rimini.it,rm.it,rn.it,ro.it,roma.it,rome.it,rovigo.it,sa.it,salerno.it,sassari.it,savona.it,si.it,siena.it,siracusa.it,so.it,sondrio.it,sp.it,sr.it,ss.it,su.it,sud-sardegna.it,sudsardegna.it,xn--sdtirol-n2a.it,suedtirol.it,sv.it,ta.it,taranto.it,te.it,tempio-olbia.it,tempioolbia.it,teramo.it,terni.it,tn.it,to.it,torino.it,tp.it,tr.it,trani-andria-barletta.it,trani-barletta-andria.it,traniandriabarletta.it,tranibarlettaandria.it,trapani.it,trentino.it,trento.it,treviso.it,trieste.it,ts.it,turin.it,tv.it,ud.it,udine.it,urbino-pesaro.it,urbinopesaro.it,va.it,varese.it,vb.it,vc.it,ve.it,venezia.it,venice.it,verbania.it,verbano-cusio-ossola.it,vercelli.it,verona.it,vi.it,vibo-valentia.it,vibovalentia.it,vicenza.it,viterbo.it,vr.it,vs.it,vt.it,vv.it,je,co.je,net.je,org.je,*.jm,jo,agri.jo,ai.jo,com.jo,edu.jo,eng.jo,fm.jo,gov.jo,mil.jo,net.jo,org.jo,per.jo,phd.jo,sch.jo,tv.jo,jobs,jp,ac.jp,ad.jp,co.jp,ed.jp,go.jp,gr.jp,lg.jp,ne.jp,or.jp,aichi.jp,akita.jp,aomori.jp,chiba.jp,ehime.jp,fukui.jp,fukuoka.jp,fukushima.jp,gifu.jp,gunma.jp,hiroshima.jp,hokkaido.jp,hyogo.jp,ibaraki.jp,ishikawa.jp,iwate.jp,kagawa.jp,kagoshima.jp,kanagawa.jp,kochi.jp,kumamoto.jp,kyoto.jp,mie.jp,miyagi.jp,miyazaki.jp,nagano.jp,nagasaki.jp,nara.jp,niigata.jp,oita.jp,okayama.jp,okinawa.jp,osaka.jp,saga.jp,saitama.jp,shiga.jp,shimane.jp,shizuoka.jp,tochigi.jp,tokushima.jp,tokyo.jp,tottori.jp,toyama.jp,wakayama.jp,yamagata.jp,yamaguchi.jp,yamanashi.jp,xn--ehqz56n.jp,xn--1lqs03n.jp,xn--qqqt11m.jp,xn--f6qx53a.jp,xn--djrs72d6uy.jp,xn--mkru45i.jp,xn--0trq7p7nn.jp,xn--5js045d.jp,xn--kbrq7o.jp,xn--pssu33l.jp,xn--ntsq17g.jp,xn--uisz3g.jp,xn--6btw5a.jp,xn--1ctwo.jp,xn--6orx2r.jp,xn--rht61e.jp,xn--rht27z.jp,xn--nit225k.jp,xn--rht3d.jp,xn--djty4k.jp,xn--klty5x.jp,xn--kltx9a.jp,xn--kltp7d.jp,xn--c3s14m.jp,xn--vgu402c.jp,xn--efvn9s.jp,xn--1lqs71d.jp,xn--4pvxs.jp,xn--uuwu58a.jp,xn--zbx025d.jp,xn--8pvr4u.jp,xn--5rtp49c.jp,xn--ntso0iqx3a.jp,xn--elqq16h.jp,xn--4it168d.jp,xn--klt787d.jp,xn--rny31h.jp,xn--7t0a264c.jp,xn--uist22h.jp,xn--8ltr62k.jp,xn--2m4a15e.jp,xn--32vp30h.jp,xn--4it797k.jp,xn--5rtq34k.jp,xn--k7yn95e.jp,xn--tor131o.jp,xn--d5qv7z876c.jp,*.kawasaki.jp,!city.kawasaki.jp,*.kitakyushu.jp,!city.kitakyushu.jp,*.kobe.jp,!city.kobe.jp,*.nagoya.jp,!city.nagoya.jp,*.sapporo.jp,!city.sapporo.jp,*.sendai.jp,!city.sendai.jp,*.yokohama.jp,!city.yokohama.jp,aisai.aichi.jp,ama.aichi.jp,anjo.aichi.jp,asuke.aichi.jp,chiryu.aichi.jp,chita.aichi.jp,fuso.aichi.jp,gamagori.aichi.jp,handa.aichi.jp,hazu.aichi.jp,hekinan.aichi.jp,higashiura.aichi.jp,ichinomiya.aichi.jp,inazawa.aichi.jp,inuyama.aichi.jp,isshiki.aichi.jp,iwakura.aichi.jp,kanie.aichi.jp,kariya.aichi.jp,kasugai.aichi.jp,kira.aichi.jp,kiyosu.aichi.jp,komaki.aichi.jp,konan.aichi.jp,kota.aichi.jp,mihama.aichi.jp,miyoshi.aichi.jp,nishio.aichi.jp,nisshin.aichi.jp,obu.aichi.jp,oguchi.aichi.jp,oharu.aichi.jp,okazaki.aichi.jp,owariasahi.aichi.jp,seto.aichi.jp,shikatsu.aichi.jp,shinshiro.aichi.jp,shitara.aichi.jp,tahara.aichi.jp,takahama.aichi.jp,tobishima.aichi.jp,toei.aichi.jp,togo.aichi.jp,tokai.aichi.jp,tokoname.aichi.jp,toyoake.aichi.jp,toyohashi.aichi.jp,toyokawa.aichi.jp,toyone.aichi.jp,toyota.aichi.jp,tsushima.aichi.jp,yatomi.aichi.jp,akita.akita.jp,daisen.akita.jp,fujisato.akita.jp,gojome.akita.jp,hachirogata.akita.jp,happou.akita.jp,higashinaruse.akita.jp,honjo.akita.jp,honjyo.akita.jp,ikawa.akita.jp,kamikoani.akita.jp,kamioka.akita.jp,katagami.akita.jp,kazuno.akita.jp,kitaakita.akita.jp,kosaka.akita.jp,kyowa.akita.jp,misato.akita.jp,mitane.akita.jp,moriyoshi.akita.jp,nikaho.akita.jp,noshiro.akita.jp,odate.akita.jp,oga.akita.jp,ogata.akita.jp,semboku.akita.jp,yokote.akita.jp,yurihonjo.akita.jp,aomori.aomori.jp,gonohe.aomori.jp,hachinohe.aomori.jp,hashikami.aomori.jp,hiranai.aomori.jp,hirosaki.aomori.jp,itayanagi.aomori.jp,kuroishi.aomori.jp,misawa.aomori.jp,mutsu.aomori.jp,nakadomari.aomori.jp,noheji.aomori.jp,oirase.aomori.jp,owani.aomori.jp,rokunohe.aomori.jp,sannohe.aomori.jp,shichinohe.aomori.jp,shingo.aomori.jp,takko.aomori.jp,towada.aomori.jp,tsugaru.aomori.jp,tsuruta.aomori.jp,abiko.chiba.jp,asahi.chiba.jp,chonan.chiba.jp,chosei.chiba.jp,choshi.chiba.jp,chuo.chiba.jp,funabashi.chiba.jp,futtsu.chiba.jp,hanamigawa.chiba.jp,ichihara.chiba.jp,ichikawa.chiba.jp,ichinomiya.chiba.jp,inzai.chiba.jp,isumi.chiba.jp,kamagaya.chiba.jp,kamogawa.chiba.jp,kashiwa.chiba.jp,katori.chiba.jp,katsuura.chiba.jp,kimitsu.chiba.jp,kisarazu.chiba.jp,kozaki.chiba.jp,kujukuri.chiba.jp,kyonan.chiba.jp,matsudo.chiba.jp,midori.chiba.jp,mihama.chiba.jp,minamiboso.chiba.jp,mobara.chiba.jp,mutsuzawa.chiba.jp,nagara.chiba.jp,nagareyama.chiba.jp,narashino.chiba.jp,narita.chiba.jp,noda.chiba.jp,oamishirasato.chiba.jp,omigawa.chiba.jp,onjuku.chiba.jp,otaki.chiba.jp,sakae.chiba.jp,sakura.chiba.jp,shimofusa.chiba.jp,shirako.chiba.jp,shiroi.chiba.jp,shisui.chiba.jp,sodegaura.chiba.jp,sosa.chiba.jp,tako.chiba.jp,tateyama.chiba.jp,togane.chiba.jp,tohnosho.chiba.jp,tomisato.chiba.jp,urayasu.chiba.jp,yachimata.chiba.jp,yachiyo.chiba.jp,yokaichiba.chiba.jp,yokoshibahikari.chiba.jp,yotsukaido.chiba.jp,ainan.ehime.jp,honai.ehime.jp,ikata.ehime.jp,imabari.ehime.jp,iyo.ehime.jp,kamijima.ehime.jp,kihoku.ehime.jp,kumakogen.ehime.jp,masaki.ehime.jp,matsuno.ehime.jp,matsuyama.ehime.jp,namikata.ehime.jp,niihama.ehime.jp,ozu.ehime.jp,saijo.ehime.jp,seiyo.ehime.jp,shikokuchuo.ehime.jp,tobe.ehime.jp,toon.ehime.jp,uchiko.ehime.jp,uwajima.ehime.jp,yawatahama.ehime.jp,echizen.fukui.jp,eiheiji.fukui.jp,fukui.fukui.jp,ikeda.fukui.jp,katsuyama.fukui.jp,mihama.fukui.jp,minamiechizen.fukui.jp,obama.fukui.jp,ohi.fukui.jp,ono.fukui.jp,sabae.fukui.jp,sakai.fukui.jp,takahama.fukui.jp,tsuruga.fukui.jp,wakasa.fukui.jp,ashiya.fukuoka.jp,buzen.fukuoka.jp,chikugo.fukuoka.jp,chikuho.fukuoka.jp,chikujo.fukuoka.jp,chikushino.fukuoka.jp,chikuzen.fukuoka.jp,chuo.fukuoka.jp,dazaifu.fukuoka.jp,fukuchi.fukuoka.jp,hakata.fukuoka.jp,higashi.fukuoka.jp,hirokawa.fukuoka.jp,hisayama.fukuoka.jp,iizuka.fukuoka.jp,inatsuki.fukuoka.jp,kaho.fukuoka.jp,kasuga.fukuoka.jp,kasuya.fukuoka.jp,kawara.fukuoka.jp,keisen.fukuoka.jp,koga.fukuoka.jp,kurate.fukuoka.jp,kurogi.fukuoka.jp,kurume.fukuoka.jp,minami.fukuoka.jp,miyako.fukuoka.jp,miyama.fukuoka.jp,miyawaka.fukuoka.jp,mizumaki.fukuoka.jp,munakata.fukuoka.jp,nakagawa.fukuoka.jp,nakama.fukuoka.jp,nishi.fukuoka.jp,nogata.fukuoka.jp,ogori.fukuoka.jp,okagaki.fukuoka.jp,okawa.fukuoka.jp,oki.fukuoka.jp,omuta.fukuoka.jp,onga.fukuoka.jp,onojo.fukuoka.jp,oto.fukuoka.jp,saigawa.fukuoka.jp,sasaguri.fukuoka.jp,shingu.fukuoka.jp,shinyoshitomi.fukuoka.jp,shonai.fukuoka.jp,soeda.fukuoka.jp,sue.fukuoka.jp,tachiarai.fukuoka.jp,tagawa.fukuoka.jp,takata.fukuoka.jp,toho.fukuoka.jp,toyotsu.fukuoka.jp,tsuiki.fukuoka.jp,ukiha.fukuoka.jp,umi.fukuoka.jp,usui.fukuoka.jp,yamada.fukuoka.jp,yame.fukuoka.jp,yanagawa.fukuoka.jp,yukuhashi.fukuoka.jp,aizubange.fukushima.jp,aizumisato.fukushima.jp,aizuwakamatsu.fukushima.jp,asakawa.fukushima.jp,bandai.fukushima.jp,date.fukushima.jp,fukushima.fukushima.jp,furudono.fukushima.jp,futaba.fukushima.jp,hanawa.fukushima.jp,higashi.fukushima.jp,hirata.fukushima.jp,hirono.fukushima.jp,iitate.fukushima.jp,inawashiro.fukushima.jp,ishikawa.fukushima.jp,iwaki.fukushima.jp,izumizaki.fukushima.jp,kagamiishi.fukushima.jp,kaneyama.fukushima.jp,kawamata.fukushima.jp,kitakata.fukushima.jp,kitashiobara.fukushima.jp,koori.fukushima.jp,koriyama.fukushima.jp,kunimi.fukushima.jp,miharu.fukushima.jp,mishima.fukushima.jp,namie.fukushima.jp,nango.fukushima.jp,nishiaizu.fukushima.jp,nishigo.fukushima.jp,okuma.fukushima.jp,omotego.fukushima.jp,ono.fukushima.jp,otama.fukushima.jp,samegawa.fukushima.jp,shimogo.fukushima.jp,shirakawa.fukushima.jp,showa.fukushima.jp,soma.fukushima.jp,sukagawa.fukushima.jp,taishin.fukushima.jp,tamakawa.fukushima.jp,tanagura.fukushima.jp,tenei.fukushima.jp,yabuki.fukushima.jp,yamato.fukushima.jp,yamatsuri.fukushima.jp,yanaizu.fukushima.jp,yugawa.fukushima.jp,anpachi.gifu.jp,ena.gifu.jp,gifu.gifu.jp,ginan.gifu.jp,godo.gifu.jp,gujo.gifu.jp,hashima.gifu.jp,hichiso.gifu.jp,hida.gifu.jp,higashishirakawa.gifu.jp,ibigawa.gifu.jp,ikeda.gifu.jp,kakamigahara.gifu.jp,kani.gifu.jp,kasahara.gifu.jp,kasamatsu.gifu.jp,kawaue.gifu.jp,kitagata.gifu.jp,mino.gifu.jp,minokamo.gifu.jp,mitake.gifu.jp,mizunami.gifu.jp,motosu.gifu.jp,nakatsugawa.gifu.jp,ogaki.gifu.jp,sakahogi.gifu.jp,seki.gifu.jp,sekigahara.gifu.jp,shirakawa.gifu.jp,tajimi.gifu.jp,takayama.gifu.jp,tarui.gifu.jp,toki.gifu.jp,tomika.gifu.jp,wanouchi.gifu.jp,yamagata.gifu.jp,yaotsu.gifu.jp,yoro.gifu.jp,annaka.gunma.jp,chiyoda.gunma.jp,fujioka.gunma.jp,higashiagatsuma.gunma.jp,isesaki.gunma.jp,itakura.gunma.jp,kanna.gunma.jp,kanra.gunma.jp,katashina.gunma.jp,kawaba.gunma.jp,kiryu.gunma.jp,kusatsu.gunma.jp,maebashi.gunma.jp,meiwa.gunma.jp,midori.gunma.jp,minakami.gunma.jp,naganohara.gunma.jp,nakanojo.gunma.jp,nanmoku.gunma.jp,numata.gunma.jp,oizumi.gunma.jp,ora.gunma.jp,ota.gunma.jp,shibukawa.gunma.jp,shimonita.gunma.jp,shinto.gunma.jp,showa.gunma.jp,takasaki.gunma.jp,takayama.gunma.jp,tamamura.gunma.jp,tatebayashi.gunma.jp,tomioka.gunma.jp,tsukiyono.gunma.jp,tsumagoi.gunma.jp,ueno.gunma.jp,yoshioka.gunma.jp,asaminami.hiroshima.jp,daiwa.hiroshima.jp,etajima.hiroshima.jp,fuchu.hiroshima.jp,fukuyama.hiroshima.jp,hatsukaichi.hiroshima.jp,higashihiroshima.hiroshima.jp,hongo.hiroshima.jp,jinsekikogen.hiroshima.jp,kaita.hiroshima.jp,kui.hiroshima.jp,kumano.hiroshima.jp,kure.hiroshima.jp,mihara.hiroshima.jp,miyoshi.hiroshima.jp,naka.hiroshima.jp,onomichi.hiroshima.jp,osakikamijima.hiroshima.jp,otake.hiroshima.jp,saka.hiroshima.jp,sera.hiroshima.jp,seranishi.hiroshima.jp,shinichi.hiroshima.jp,shobara.hiroshima.jp,takehara.hiroshima.jp,abashiri.hokkaido.jp,abira.hokkaido.jp,aibetsu.hokkaido.jp,akabira.hokkaido.jp,akkeshi.hokkaido.jp,asahikawa.hokkaido.jp,ashibetsu.hokkaido.jp,ashoro.hokkaido.jp,assabu.hokkaido.jp,atsuma.hokkaido.jp,bibai.hokkaido.jp,biei.hokkaido.jp,bifuka.hokkaido.jp,bihoro.hokkaido.jp,biratori.hokkaido.jp,chippubetsu.hokkaido.jp,chitose.hokkaido.jp,date.hokkaido.jp,ebetsu.hokkaido.jp,embetsu.hokkaido.jp,eniwa.hokkaido.jp,erimo.hokkaido.jp,esan.hokkaido.jp,esashi.hokkaido.jp,fukagawa.hokkaido.jp,fukushima.hokkaido.jp,furano.hokkaido.jp,furubira.hokkaido.jp,haboro.hokkaido.jp,hakodate.hokkaido.jp,hamatonbetsu.hokkaido.jp,hidaka.hokkaido.jp,higashikagura.hokkaido.jp,higashikawa.hokkaido.jp,hiroo.hokkaido.jp,hokuryu.hokkaido.jp,hokuto.hokkaido.jp,honbetsu.hokkaido.jp,horokanai.hokkaido.jp,horonobe.hokkaido.jp,ikeda.hokkaido.jp,imakane.hokkaido.jp,ishikari.hokkaido.jp,iwamizawa.hokkaido.jp,iwanai.hokkaido.jp,kamifurano.hokkaido.jp,kamikawa.hokkaido.jp,kamishihoro.hokkaido.jp,kamisunagawa.hokkaido.jp,kamoenai.hokkaido.jp,kayabe.hokkaido.jp,kembuchi.hokkaido.jp,kikonai.hokkaido.jp,kimobetsu.hokkaido.jp,kitahiroshima.hokkaido.jp,kitami.hokkaido.jp,kiyosato.hokkaido.jp,koshimizu.hokkaido.jp,kunneppu.hokkaido.jp,kuriyama.hokkaido.jp,kuromatsunai.hokkaido.jp,kushiro.hokkaido.jp,kutchan.hokkaido.jp,kyowa.hokkaido.jp,mashike.hokkaido.jp,matsumae.hokkaido.jp,mikasa.hokkaido.jp,minamifurano.hokkaido.jp,mombetsu.hokkaido.jp,moseushi.hokkaido.jp,mukawa.hokkaido.jp,muroran.hokkaido.jp,naie.hokkaido.jp,nakagawa.hokkaido.jp,nakasatsunai.hokkaido.jp,nakatombetsu.hokkaido.jp,nanae.hokkaido.jp,nanporo.hokkaido.jp,nayoro.hokkaido.jp,nemuro.hokkaido.jp,niikappu.hokkaido.jp,niki.hokkaido.jp,nishiokoppe.hokkaido.jp,noboribetsu.hokkaido.jp,numata.hokkaido.jp,obihiro.hokkaido.jp,obira.hokkaido.jp,oketo.hokkaido.jp,okoppe.hokkaido.jp,otaru.hokkaido.jp,otobe.hokkaido.jp,otofuke.hokkaido.jp,otoineppu.hokkaido.jp,oumu.hokkaido.jp,ozora.hokkaido.jp,pippu.hokkaido.jp,rankoshi.hokkaido.jp,rebun.hokkaido.jp,rikubetsu.hokkaido.jp,rishiri.hokkaido.jp,rishirifuji.hokkaido.jp,saroma.hokkaido.jp,sarufutsu.hokkaido.jp,shakotan.hokkaido.jp,shari.hokkaido.jp,shibecha.hokkaido.jp,shibetsu.hokkaido.jp,shikabe.hokkaido.jp,shikaoi.hokkaido.jp,shimamaki.hokkaido.jp,shimizu.hokkaido.jp,shimokawa.hokkaido.jp,shinshinotsu.hokkaido.jp,shintoku.hokkaido.jp,shiranuka.hokkaido.jp,shiraoi.hokkaido.jp,shiriuchi.hokkaido.jp,sobetsu.hokkaido.jp,sunagawa.hokkaido.jp,taiki.hokkaido.jp,takasu.hokkaido.jp,takikawa.hokkaido.jp,takinoue.hokkaido.jp,teshikaga.hokkaido.jp,tobetsu.hokkaido.jp,tohma.hokkaido.jp,tomakomai.hokkaido.jp,tomari.hokkaido.jp,toya.hokkaido.jp,toyako.hokkaido.jp,toyotomi.hokkaido.jp,toyoura.hokkaido.jp,tsubetsu.hokkaido.jp,tsukigata.hokkaido.jp,urakawa.hokkaido.jp,urausu.hokkaido.jp,uryu.hokkaido.jp,utashinai.hokkaido.jp,wakkanai.hokkaido.jp,wassamu.hokkaido.jp,yakumo.hokkaido.jp,yoichi.hokkaido.jp,aioi.hyogo.jp,akashi.hyogo.jp,ako.hyogo.jp,amagasaki.hyogo.jp,aogaki.hyogo.jp,asago.hyogo.jp,ashiya.hyogo.jp,awaji.hyogo.jp,fukusaki.hyogo.jp,goshiki.hyogo.jp,harima.hyogo.jp,himeji.hyogo.jp,ichikawa.hyogo.jp,inagawa.hyogo.jp,itami.hyogo.jp,kakogawa.hyogo.jp,kamigori.hyogo.jp,kamikawa.hyogo.jp,kasai.hyogo.jp,kasuga.hyogo.jp,kawanishi.hyogo.jp,miki.hyogo.jp,minamiawaji.hyogo.jp,nishinomiya.hyogo.jp,nishiwaki.hyogo.jp,ono.hyogo.jp,sanda.hyogo.jp,sannan.hyogo.jp,sasayama.hyogo.jp,sayo.hyogo.jp,shingu.hyogo.jp,shinonsen.hyogo.jp,shiso.hyogo.jp,sumoto.hyogo.jp,taishi.hyogo.jp,taka.hyogo.jp,takarazuka.hyogo.jp,takasago.hyogo.jp,takino.hyogo.jp,tamba.hyogo.jp,tatsuno.hyogo.jp,toyooka.hyogo.jp,yabu.hyogo.jp,yashiro.hyogo.jp,yoka.hyogo.jp,yokawa.hyogo.jp,ami.ibaraki.jp,asahi.ibaraki.jp,bando.ibaraki.jp,chikusei.ibaraki.jp,daigo.ibaraki.jp,fujishiro.ibaraki.jp,hitachi.ibaraki.jp,hitachinaka.ibaraki.jp,hitachiomiya.ibaraki.jp,hitachiota.ibaraki.jp,ibaraki.ibaraki.jp,ina.ibaraki.jp,inashiki.ibaraki.jp,itako.ibaraki.jp,iwama.ibaraki.jp,joso.ibaraki.jp,kamisu.ibaraki.jp,kasama.ibaraki.jp,kashima.ibaraki.jp,kasumigaura.ibaraki.jp,koga.ibaraki.jp,miho.ibaraki.jp,mito.ibaraki.jp,moriya.ibaraki.jp,naka.ibaraki.jp,namegata.ibaraki.jp,oarai.ibaraki.jp,ogawa.ibaraki.jp,omitama.ibaraki.jp,ryugasaki.ibaraki.jp,sakai.ibaraki.jp,sakuragawa.ibaraki.jp,shimodate.ibaraki.jp,shimotsuma.ibaraki.jp,shirosato.ibaraki.jp,sowa.ibaraki.jp,suifu.ibaraki.jp,takahagi.ibaraki.jp,tamatsukuri.ibaraki.jp,tokai.ibaraki.jp,tomobe.ibaraki.jp,tone.ibaraki.jp,toride.ibaraki.jp,tsuchiura.ibaraki.jp,tsukuba.ibaraki.jp,uchihara.ibaraki.jp,ushiku.ibaraki.jp,yachiyo.ibaraki.jp,yamagata.ibaraki.jp,yawara.ibaraki.jp,yuki.ibaraki.jp,anamizu.ishikawa.jp,hakui.ishikawa.jp,hakusan.ishikawa.jp,kaga.ishikawa.jp,kahoku.ishikawa.jp,kanazawa.ishikawa.jp,kawakita.ishikawa.jp,komatsu.ishikawa.jp,nakanoto.ishikawa.jp,nanao.ishikawa.jp,nomi.ishikawa.jp,nonoichi.ishikawa.jp,noto.ishikawa.jp,shika.ishikawa.jp,suzu.ishikawa.jp,tsubata.ishikawa.jp,tsurugi.ishikawa.jp,uchinada.ishikawa.jp,wajima.ishikawa.jp,fudai.iwate.jp,fujisawa.iwate.jp,hanamaki.iwate.jp,hiraizumi.iwate.jp,hirono.iwate.jp,ichinohe.iwate.jp,ichinoseki.iwate.jp,iwaizumi.iwate.jp,iwate.iwate.jp,joboji.iwate.jp,kamaishi.iwate.jp,kanegasaki.iwate.jp,karumai.iwate.jp,kawai.iwate.jp,kitakami.iwate.jp,kuji.iwate.jp,kunohe.iwate.jp,kuzumaki.iwate.jp,miyako.iwate.jp,mizusawa.iwate.jp,morioka.iwate.jp,ninohe.iwate.jp,noda.iwate.jp,ofunato.iwate.jp,oshu.iwate.jp,otsuchi.iwate.jp,rikuzentakata.iwate.jp,shiwa.iwate.jp,shizukuishi.iwate.jp,sumita.iwate.jp,tanohata.iwate.jp,tono.iwate.jp,yahaba.iwate.jp,yamada.iwate.jp,ayagawa.kagawa.jp,higashikagawa.kagawa.jp,kanonji.kagawa.jp,kotohira.kagawa.jp,manno.kagawa.jp,marugame.kagawa.jp,mitoyo.kagawa.jp,naoshima.kagawa.jp,sanuki.kagawa.jp,tadotsu.kagawa.jp,takamatsu.kagawa.jp,tonosho.kagawa.jp,uchinomi.kagawa.jp,utazu.kagawa.jp,zentsuji.kagawa.jp,akune.kagoshima.jp,amami.kagoshima.jp,hioki.kagoshima.jp,isa.kagoshima.jp,isen.kagoshima.jp,izumi.kagoshima.jp,kagoshima.kagoshima.jp,kanoya.kagoshima.jp,kawanabe.kagoshima.jp,kinko.kagoshima.jp,kouyama.kagoshima.jp,makurazaki.kagoshima.jp,matsumoto.kagoshima.jp,minamitane.kagoshima.jp,nakatane.kagoshima.jp,nishinoomote.kagoshima.jp,satsumasendai.kagoshima.jp,soo.kagoshima.jp,tarumizu.kagoshima.jp,yusui.kagoshima.jp,aikawa.kanagawa.jp,atsugi.kanagawa.jp,ayase.kanagawa.jp,chigasaki.kanagawa.jp,ebina.kanagawa.jp,fujisawa.kanagawa.jp,hadano.kanagawa.jp,hakone.kanagawa.jp,hiratsuka.kanagawa.jp,isehara.kanagawa.jp,kaisei.kanagawa.jp,kamakura.kanagawa.jp,kiyokawa.kanagawa.jp,matsuda.kanagawa.jp,minamiashigara.kanagawa.jp,miura.kanagawa.jp,nakai.kanagawa.jp,ninomiya.kanagawa.jp,odawara.kanagawa.jp,oi.kanagawa.jp,oiso.kanagawa.jp,sagamihara.kanagawa.jp,samukawa.kanagawa.jp,tsukui.kanagawa.jp,yamakita.kanagawa.jp,yamato.kanagawa.jp,yokosuka.kanagawa.jp,yugawara.kanagawa.jp,zama.kanagawa.jp,zushi.kanagawa.jp,aki.kochi.jp,geisei.kochi.jp,hidaka.kochi.jp,higashitsuno.kochi.jp,ino.kochi.jp,kagami.kochi.jp,kami.kochi.jp,kitagawa.kochi.jp,kochi.kochi.jp,mihara.kochi.jp,motoyama.kochi.jp,muroto.kochi.jp,nahari.kochi.jp,nakamura.kochi.jp,nankoku.kochi.jp,nishitosa.kochi.jp,niyodogawa.kochi.jp,ochi.kochi.jp,okawa.kochi.jp,otoyo.kochi.jp,otsuki.kochi.jp,sakawa.kochi.jp,sukumo.kochi.jp,susaki.kochi.jp,tosa.kochi.jp,tosashimizu.kochi.jp,toyo.kochi.jp,tsuno.kochi.jp,umaji.kochi.jp,yasuda.kochi.jp,yusuhara.kochi.jp,amakusa.kumamoto.jp,arao.kumamoto.jp,aso.kumamoto.jp,choyo.kumamoto.jp,gyokuto.kumamoto.jp,kamiamakusa.kumamoto.jp,kikuchi.kumamoto.jp,kumamoto.kumamoto.jp,mashiki.kumamoto.jp,mifune.kumamoto.jp,minamata.kumamoto.jp,minamioguni.kumamoto.jp,nagasu.kumamoto.jp,nishihara.kumamoto.jp,oguni.kumamoto.jp,ozu.kumamoto.jp,sumoto.kumamoto.jp,takamori.kumamoto.jp,uki.kumamoto.jp,uto.kumamoto.jp,yamaga.kumamoto.jp,yamato.kumamoto.jp,yatsushiro.kumamoto.jp,ayabe.kyoto.jp,fukuchiyama.kyoto.jp,higashiyama.kyoto.jp,ide.kyoto.jp,ine.kyoto.jp,joyo.kyoto.jp,kameoka.kyoto.jp,kamo.kyoto.jp,kita.kyoto.jp,kizu.kyoto.jp,kumiyama.kyoto.jp,kyotamba.kyoto.jp,kyotanabe.kyoto.jp,kyotango.kyoto.jp,maizuru.kyoto.jp,minami.kyoto.jp,minamiyamashiro.kyoto.jp,miyazu.kyoto.jp,muko.kyoto.jp,nagaokakyo.kyoto.jp,nakagyo.kyoto.jp,nantan.kyoto.jp,oyamazaki.kyoto.jp,sakyo.kyoto.jp,seika.kyoto.jp,tanabe.kyoto.jp,uji.kyoto.jp,ujitawara.kyoto.jp,wazuka.kyoto.jp,yamashina.kyoto.jp,yawata.kyoto.jp,asahi.mie.jp,inabe.mie.jp,ise.mie.jp,kameyama.mie.jp,kawagoe.mie.jp,kiho.mie.jp,kisosaki.mie.jp,kiwa.mie.jp,komono.mie.jp,kumano.mie.jp,kuwana.mie.jp,matsusaka.mie.jp,meiwa.mie.jp,mihama.mie.jp,minamiise.mie.jp,misugi.mie.jp,miyama.mie.jp,nabari.mie.jp,shima.mie.jp,suzuka.mie.jp,tado.mie.jp,taiki.mie.jp,taki.mie.jp,tamaki.mie.jp,toba.mie.jp,tsu.mie.jp,udono.mie.jp,ureshino.mie.jp,watarai.mie.jp,yokkaichi.mie.jp,furukawa.miyagi.jp,higashimatsushima.miyagi.jp,ishinomaki.miyagi.jp,iwanuma.miyagi.jp,kakuda.miyagi.jp,kami.miyagi.jp,kawasaki.miyagi.jp,marumori.miyagi.jp,matsushima.miyagi.jp,minamisanriku.miyagi.jp,misato.miyagi.jp,murata.miyagi.jp,natori.miyagi.jp,ogawara.miyagi.jp,ohira.miyagi.jp,onagawa.miyagi.jp,osaki.miyagi.jp,rifu.miyagi.jp,semine.miyagi.jp,shibata.miyagi.jp,shichikashuku.miyagi.jp,shikama.miyagi.jp,shiogama.miyagi.jp,shiroishi.miyagi.jp,tagajo.miyagi.jp,taiwa.miyagi.jp,tome.miyagi.jp,tomiya.miyagi.jp,wakuya.miyagi.jp,watari.miyagi.jp,yamamoto.miyagi.jp,zao.miyagi.jp,aya.miyazaki.jp,ebino.miyazaki.jp,gokase.miyazaki.jp,hyuga.miyazaki.jp,kadogawa.miyazaki.jp,kawaminami.miyazaki.jp,kijo.miyazaki.jp,kitagawa.miyazaki.jp,kitakata.miyazaki.jp,kitaura.miyazaki.jp,kobayashi.miyazaki.jp,kunitomi.miyazaki.jp,kushima.miyazaki.jp,mimata.miyazaki.jp,miyakonojo.miyazaki.jp,miyazaki.miyazaki.jp,morotsuka.miyazaki.jp,nichinan.miyazaki.jp,nishimera.miyazaki.jp,nobeoka.miyazaki.jp,saito.miyazaki.jp,shiiba.miyazaki.jp,shintomi.miyazaki.jp,takaharu.miyazaki.jp,takanabe.miyazaki.jp,takazaki.miyazaki.jp,tsuno.miyazaki.jp,achi.nagano.jp,agematsu.nagano.jp,anan.nagano.jp,aoki.nagano.jp,asahi.nagano.jp,azumino.nagano.jp,chikuhoku.nagano.jp,chikuma.nagano.jp,chino.nagano.jp,fujimi.nagano.jp,hakuba.nagano.jp,hara.nagano.jp,hiraya.nagano.jp,iida.nagano.jp,iijima.nagano.jp,iiyama.nagano.jp,iizuna.nagano.jp,ikeda.nagano.jp,ikusaka.nagano.jp,ina.nagano.jp,karuizawa.nagano.jp,kawakami.nagano.jp,kiso.nagano.jp,kisofukushima.nagano.jp,kitaaiki.nagano.jp,komagane.nagano.jp,komoro.nagano.jp,matsukawa.nagano.jp,matsumoto.nagano.jp,miasa.nagano.jp,minamiaiki.nagano.jp,minamimaki.nagano.jp,minamiminowa.nagano.jp,minowa.nagano.jp,miyada.nagano.jp,miyota.nagano.jp,mochizuki.nagano.jp,nagano.nagano.jp,nagawa.nagano.jp,nagiso.nagano.jp,nakagawa.nagano.jp,nakano.nagano.jp,nozawaonsen.nagano.jp,obuse.nagano.jp,ogawa.nagano.jp,okaya.nagano.jp,omachi.nagano.jp,omi.nagano.jp,ookuwa.nagano.jp,ooshika.nagano.jp,otaki.nagano.jp,otari.nagano.jp,sakae.nagano.jp,sakaki.nagano.jp,saku.nagano.jp,sakuho.nagano.jp,shimosuwa.nagano.jp,shinanomachi.nagano.jp,shiojiri.nagano.jp,suwa.nagano.jp,suzaka.nagano.jp,takagi.nagano.jp,takamori.nagano.jp,takayama.nagano.jp,tateshina.nagano.jp,tatsuno.nagano.jp,togakushi.nagano.jp,togura.nagano.jp,tomi.nagano.jp,ueda.nagano.jp,wada.nagano.jp,yamagata.nagano.jp,yamanouchi.nagano.jp,yasaka.nagano.jp,yasuoka.nagano.jp,chijiwa.nagasaki.jp,futsu.nagasaki.jp,goto.nagasaki.jp,hasami.nagasaki.jp,hirado.nagasaki.jp,iki.nagasaki.jp,isahaya.nagasaki.jp,kawatana.nagasaki.jp,kuchinotsu.nagasaki.jp,matsuura.nagasaki.jp,nagasaki.nagasaki.jp,obama.nagasaki.jp,omura.nagasaki.jp,oseto.nagasaki.jp,saikai.nagasaki.jp,sasebo.nagasaki.jp,seihi.nagasaki.jp,shimabara.nagasaki.jp,shinkamigoto.nagasaki.jp,togitsu.nagasaki.jp,tsushima.nagasaki.jp,unzen.nagasaki.jp,ando.nara.jp,gose.nara.jp,heguri.nara.jp,higashiyoshino.nara.jp,ikaruga.nara.jp,ikoma.nara.jp,kamikitayama.nara.jp,kanmaki.nara.jp,kashiba.nara.jp,kashihara.nara.jp,katsuragi.nara.jp,kawai.nara.jp,kawakami.nara.jp,kawanishi.nara.jp,koryo.nara.jp,kurotaki.nara.jp,mitsue.nara.jp,miyake.nara.jp,nara.nara.jp,nosegawa.nara.jp,oji.nara.jp,ouda.nara.jp,oyodo.nara.jp,sakurai.nara.jp,sango.nara.jp,shimoichi.nara.jp,shimokitayama.nara.jp,shinjo.nara.jp,soni.nara.jp,takatori.nara.jp,tawaramoto.nara.jp,tenkawa.nara.jp,tenri.nara.jp,uda.nara.jp,yamatokoriyama.nara.jp,yamatotakada.nara.jp,yamazoe.nara.jp,yoshino.nara.jp,aga.niigata.jp,agano.niigata.jp,gosen.niigata.jp,itoigawa.niigata.jp,izumozaki.niigata.jp,joetsu.niigata.jp,kamo.niigata.jp,kariwa.niigata.jp,kashiwazaki.niigata.jp,minamiuonuma.niigata.jp,mitsuke.niigata.jp,muika.niigata.jp,murakami.niigata.jp,myoko.niigata.jp,nagaoka.niigata.jp,niigata.niigata.jp,ojiya.niigata.jp,omi.niigata.jp,sado.niigata.jp,sanjo.niigata.jp,seiro.niigata.jp,seirou.niigata.jp,sekikawa.niigata.jp,shibata.niigata.jp,tagami.niigata.jp,tainai.niigata.jp,tochio.niigata.jp,tokamachi.niigata.jp,tsubame.niigata.jp,tsunan.niigata.jp,uonuma.niigata.jp,yahiko.niigata.jp,yoita.niigata.jp,yuzawa.niigata.jp,beppu.oita.jp,bungoono.oita.jp,bungotakada.oita.jp,hasama.oita.jp,hiji.oita.jp,himeshima.oita.jp,hita.oita.jp,kamitsue.oita.jp,kokonoe.oita.jp,kuju.oita.jp,kunisaki.oita.jp,kusu.oita.jp,oita.oita.jp,saiki.oita.jp,taketa.oita.jp,tsukumi.oita.jp,usa.oita.jp,usuki.oita.jp,yufu.oita.jp,akaiwa.okayama.jp,asakuchi.okayama.jp,bizen.okayama.jp,hayashima.okayama.jp,ibara.okayama.jp,kagamino.okayama.jp,kasaoka.okayama.jp,kibichuo.okayama.jp,kumenan.okayama.jp,kurashiki.okayama.jp,maniwa.okayama.jp,misaki.okayama.jp,nagi.okayama.jp,niimi.okayama.jp,nishiawakura.okayama.jp,okayama.okayama.jp,satosho.okayama.jp,setouchi.okayama.jp,shinjo.okayama.jp,shoo.okayama.jp,soja.okayama.jp,takahashi.okayama.jp,tamano.okayama.jp,tsuyama.okayama.jp,wake.okayama.jp,yakage.okayama.jp,aguni.okinawa.jp,ginowan.okinawa.jp,ginoza.okinawa.jp,gushikami.okinawa.jp,haebaru.okinawa.jp,higashi.okinawa.jp,hirara.okinawa.jp,iheya.okinawa.jp,ishigaki.okinawa.jp,ishikawa.okinawa.jp,itoman.okinawa.jp,izena.okinawa.jp,kadena.okinawa.jp,kin.okinawa.jp,kitadaito.okinawa.jp,kitanakagusuku.okinawa.jp,kumejima.okinawa.jp,kunigami.okinawa.jp,minamidaito.okinawa.jp,motobu.okinawa.jp,nago.okinawa.jp,naha.okinawa.jp,nakagusuku.okinawa.jp,nakijin.okinawa.jp,nanjo.okinawa.jp,nishihara.okinawa.jp,ogimi.okinawa.jp,okinawa.okinawa.jp,onna.okinawa.jp,shimoji.okinawa.jp,taketomi.okinawa.jp,tarama.okinawa.jp,tokashiki.okinawa.jp,tomigusuku.okinawa.jp,tonaki.okinawa.jp,urasoe.okinawa.jp,uruma.okinawa.jp,yaese.okinawa.jp,yomitan.okinawa.jp,yonabaru.okinawa.jp,yonaguni.okinawa.jp,zamami.okinawa.jp,abeno.osaka.jp,chihayaakasaka.osaka.jp,chuo.osaka.jp,daito.osaka.jp,fujiidera.osaka.jp,habikino.osaka.jp,hannan.osaka.jp,higashiosaka.osaka.jp,higashisumiyoshi.osaka.jp,higashiyodogawa.osaka.jp,hirakata.osaka.jp,ibaraki.osaka.jp,ikeda.osaka.jp,izumi.osaka.jp,izumiotsu.osaka.jp,izumisano.osaka.jp,kadoma.osaka.jp,kaizuka.osaka.jp,kanan.osaka.jp,kashiwara.osaka.jp,katano.osaka.jp,kawachinagano.osaka.jp,kishiwada.osaka.jp,kita.osaka.jp,kumatori.osaka.jp,matsubara.osaka.jp,minato.osaka.jp,minoh.osaka.jp,misaki.osaka.jp,moriguchi.osaka.jp,neyagawa.osaka.jp,nishi.osaka.jp,nose.osaka.jp,osakasayama.osaka.jp,sakai.osaka.jp,sayama.osaka.jp,sennan.osaka.jp,settsu.osaka.jp,shijonawate.osaka.jp,shimamoto.osaka.jp,suita.osaka.jp,tadaoka.osaka.jp,taishi.osaka.jp,tajiri.osaka.jp,takaishi.osaka.jp,takatsuki.osaka.jp,tondabayashi.osaka.jp,toyonaka.osaka.jp,toyono.osaka.jp,yao.osaka.jp,ariake.saga.jp,arita.saga.jp,fukudomi.saga.jp,genkai.saga.jp,hamatama.saga.jp,hizen.saga.jp,imari.saga.jp,kamimine.saga.jp,kanzaki.saga.jp,karatsu.saga.jp,kashima.saga.jp,kitagata.saga.jp,kitahata.saga.jp,kiyama.saga.jp,kouhoku.saga.jp,kyuragi.saga.jp,nishiarita.saga.jp,ogi.saga.jp,omachi.saga.jp,ouchi.saga.jp,saga.saga.jp,shiroishi.saga.jp,taku.saga.jp,tara.saga.jp,tosu.saga.jp,yoshinogari.saga.jp,arakawa.saitama.jp,asaka.saitama.jp,chichibu.saitama.jp,fujimi.saitama.jp,fujimino.saitama.jp,fukaya.saitama.jp,hanno.saitama.jp,hanyu.saitama.jp,hasuda.saitama.jp,hatogaya.saitama.jp,hatoyama.saitama.jp,hidaka.saitama.jp,higashichichibu.saitama.jp,higashimatsuyama.saitama.jp,honjo.saitama.jp,ina.saitama.jp,iruma.saitama.jp,iwatsuki.saitama.jp,kamiizumi.saitama.jp,kamikawa.saitama.jp,kamisato.saitama.jp,kasukabe.saitama.jp,kawagoe.saitama.jp,kawaguchi.saitama.jp,kawajima.saitama.jp,kazo.saitama.jp,kitamoto.saitama.jp,koshigaya.saitama.jp,kounosu.saitama.jp,kuki.saitama.jp,kumagaya.saitama.jp,matsubushi.saitama.jp,minano.saitama.jp,misato.saitama.jp,miyashiro.saitama.jp,miyoshi.saitama.jp,moroyama.saitama.jp,nagatoro.saitama.jp,namegawa.saitama.jp,niiza.saitama.jp,ogano.saitama.jp,ogawa.saitama.jp,ogose.saitama.jp,okegawa.saitama.jp,omiya.saitama.jp,otaki.saitama.jp,ranzan.saitama.jp,ryokami.saitama.jp,saitama.saitama.jp,sakado.saitama.jp,satte.saitama.jp,sayama.saitama.jp,shiki.saitama.jp,shiraoka.saitama.jp,soka.saitama.jp,sugito.saitama.jp,toda.saitama.jp,tokigawa.saitama.jp,tokorozawa.saitama.jp,tsurugashima.saitama.jp,urawa.saitama.jp,warabi.saitama.jp,yashio.saitama.jp,yokoze.saitama.jp,yono.saitama.jp,yorii.saitama.jp,yoshida.saitama.jp,yoshikawa.saitama.jp,yoshimi.saitama.jp,aisho.shiga.jp,gamo.shiga.jp,higashiomi.shiga.jp,hikone.shiga.jp,koka.shiga.jp,konan.shiga.jp,kosei.shiga.jp,koto.shiga.jp,kusatsu.shiga.jp,maibara.shiga.jp,moriyama.shiga.jp,nagahama.shiga.jp,nishiazai.shiga.jp,notogawa.shiga.jp,omihachiman.shiga.jp,otsu.shiga.jp,ritto.shiga.jp,ryuoh.shiga.jp,takashima.shiga.jp,takatsuki.shiga.jp,torahime.shiga.jp,toyosato.shiga.jp,yasu.shiga.jp,akagi.shimane.jp,ama.shimane.jp,gotsu.shimane.jp,hamada.shimane.jp,higashiizumo.shimane.jp,hikawa.shimane.jp,hikimi.shimane.jp,izumo.shimane.jp,kakinoki.shimane.jp,masuda.shimane.jp,matsue.shimane.jp,misato.shimane.jp,nishinoshima.shimane.jp,ohda.shimane.jp,okinoshima.shimane.jp,okuizumo.shimane.jp,shimane.shimane.jp,tamayu.shimane.jp,tsuwano.shimane.jp,unnan.shimane.jp,yakumo.shimane.jp,yasugi.shimane.jp,yatsuka.shimane.jp,arai.shizuoka.jp,atami.shizuoka.jp,fuji.shizuoka.jp,fujieda.shizuoka.jp,fujikawa.shizuoka.jp,fujinomiya.shizuoka.jp,fukuroi.shizuoka.jp,gotemba.shizuoka.jp,haibara.shizuoka.jp,hamamatsu.shizuoka.jp,higashiizu.shizuoka.jp,ito.shizuoka.jp,iwata.shizuoka.jp,izu.shizuoka.jp,izunokuni.shizuoka.jp,kakegawa.shizuoka.jp,kannami.shizuoka.jp,kawanehon.shizuoka.jp,kawazu.shizuoka.jp,kikugawa.shizuoka.jp,kosai.shizuoka.jp,makinohara.shizuoka.jp,matsuzaki.shizuoka.jp,minamiizu.shizuoka.jp,mishima.shizuoka.jp,morimachi.shizuoka.jp,nishiizu.shizuoka.jp,numazu.shizuoka.jp,omaezaki.shizuoka.jp,shimada.shizuoka.jp,shimizu.shizuoka.jp,shimoda.shizuoka.jp,shizuoka.shizuoka.jp,susono.shizuoka.jp,yaizu.shizuoka.jp,yoshida.shizuoka.jp,ashikaga.tochigi.jp,bato.tochigi.jp,haga.tochigi.jp,ichikai.tochigi.jp,iwafune.tochigi.jp,kaminokawa.tochigi.jp,kanuma.tochigi.jp,karasuyama.tochigi.jp,kuroiso.tochigi.jp,mashiko.tochigi.jp,mibu.tochigi.jp,moka.tochigi.jp,motegi.tochigi.jp,nasu.tochigi.jp,nasushiobara.tochigi.jp,nikko.tochigi.jp,nishikata.tochigi.jp,nogi.tochigi.jp,ohira.tochigi.jp,ohtawara.tochigi.jp,oyama.tochigi.jp,sakura.tochigi.jp,sano.tochigi.jp,shimotsuke.tochigi.jp,shioya.tochigi.jp,takanezawa.tochigi.jp,tochigi.tochigi.jp,tsuga.tochigi.jp,ujiie.tochigi.jp,utsunomiya.tochigi.jp,yaita.tochigi.jp,aizumi.tokushima.jp,anan.tokushima.jp,ichiba.tokushima.jp,itano.tokushima.jp,kainan.tokushima.jp,komatsushima.tokushima.jp,matsushige.tokushima.jp,mima.tokushima.jp,minami.tokushima.jp,miyoshi.tokushima.jp,mugi.tokushima.jp,nakagawa.tokushima.jp,naruto.tokushima.jp,sanagochi.tokushima.jp,shishikui.tokushima.jp,tokushima.tokushima.jp,wajiki.tokushima.jp,adachi.tokyo.jp,akiruno.tokyo.jp,akishima.tokyo.jp,aogashima.tokyo.jp,arakawa.tokyo.jp,bunkyo.tokyo.jp,chiyoda.tokyo.jp,chofu.tokyo.jp,chuo.tokyo.jp,edogawa.tokyo.jp,fuchu.tokyo.jp,fussa.tokyo.jp,hachijo.tokyo.jp,hachioji.tokyo.jp,hamura.tokyo.jp,higashikurume.tokyo.jp,higashimurayama.tokyo.jp,higashiyamato.tokyo.jp,hino.tokyo.jp,hinode.tokyo.jp,hinohara.tokyo.jp,inagi.tokyo.jp,itabashi.tokyo.jp,katsushika.tokyo.jp,kita.tokyo.jp,kiyose.tokyo.jp,kodaira.tokyo.jp,koganei.tokyo.jp,kokubunji.tokyo.jp,komae.tokyo.jp,koto.tokyo.jp,kouzushima.tokyo.jp,kunitachi.tokyo.jp,machida.tokyo.jp,meguro.tokyo.jp,minato.tokyo.jp,mitaka.tokyo.jp,mizuho.tokyo.jp,musashimurayama.tokyo.jp,musashino.tokyo.jp,nakano.tokyo.jp,nerima.tokyo.jp,ogasawara.tokyo.jp,okutama.tokyo.jp,ome.tokyo.jp,oshima.tokyo.jp,ota.tokyo.jp,setagaya.tokyo.jp,shibuya.tokyo.jp,shinagawa.tokyo.jp,shinjuku.tokyo.jp,suginami.tokyo.jp,sumida.tokyo.jp,tachikawa.tokyo.jp,taito.tokyo.jp,tama.tokyo.jp,toshima.tokyo.jp,chizu.tottori.jp,hino.tottori.jp,kawahara.tottori.jp,koge.tottori.jp,kotoura.tottori.jp,misasa.tottori.jp,nanbu.tottori.jp,nichinan.tottori.jp,sakaiminato.tottori.jp,tottori.tottori.jp,wakasa.tottori.jp,yazu.tottori.jp,yonago.tottori.jp,asahi.toyama.jp,fuchu.toyama.jp,fukumitsu.toyama.jp,funahashi.toyama.jp,himi.toyama.jp,imizu.toyama.jp,inami.toyama.jp,johana.toyama.jp,kamiichi.toyama.jp,kurobe.toyama.jp,nakaniikawa.toyama.jp,namerikawa.toyama.jp,nanto.toyama.jp,nyuzen.toyama.jp,oyabe.toyama.jp,taira.toyama.jp,takaoka.toyama.jp,tateyama.toyama.jp,toga.toyama.jp,tonami.toyama.jp,toyama.toyama.jp,unazuki.toyama.jp,uozu.toyama.jp,yamada.toyama.jp,arida.wakayama.jp,aridagawa.wakayama.jp,gobo.wakayama.jp,hashimoto.wakayama.jp,hidaka.wakayama.jp,hirogawa.wakayama.jp,inami.wakayama.jp,iwade.wakayama.jp,kainan.wakayama.jp,kamitonda.wakayama.jp,katsuragi.wakayama.jp,kimino.wakayama.jp,kinokawa.wakayama.jp,kitayama.wakayama.jp,koya.wakayama.jp,koza.wakayama.jp,kozagawa.wakayama.jp,kudoyama.wakayama.jp,kushimoto.wakayama.jp,mihama.wakayama.jp,misato.wakayama.jp,nachikatsuura.wakayama.jp,shingu.wakayama.jp,shirahama.wakayama.jp,taiji.wakayama.jp,tanabe.wakayama.jp,wakayama.wakayama.jp,yuasa.wakayama.jp,yura.wakayama.jp,asahi.yamagata.jp,funagata.yamagata.jp,higashine.yamagata.jp,iide.yamagata.jp,kahoku.yamagata.jp,kaminoyama.yamagata.jp,kaneyama.yamagata.jp,kawanishi.yamagata.jp,mamurogawa.yamagata.jp,mikawa.yamagata.jp,murayama.yamagata.jp,nagai.yamagata.jp,nakayama.yamagata.jp,nanyo.yamagata.jp,nishikawa.yamagata.jp,obanazawa.yamagata.jp,oe.yamagata.jp,oguni.yamagata.jp,ohkura.yamagata.jp,oishida.yamagata.jp,sagae.yamagata.jp,sakata.yamagata.jp,sakegawa.yamagata.jp,shinjo.yamagata.jp,shirataka.yamagata.jp,shonai.yamagata.jp,takahata.yamagata.jp,tendo.yamagata.jp,tozawa.yamagata.jp,tsuruoka.yamagata.jp,yamagata.yamagata.jp,yamanobe.yamagata.jp,yonezawa.yamagata.jp,yuza.yamagata.jp,abu.yamaguchi.jp,hagi.yamaguchi.jp,hikari.yamaguchi.jp,hofu.yamaguchi.jp,iwakuni.yamaguchi.jp,kudamatsu.yamaguchi.jp,mitou.yamaguchi.jp,nagato.yamaguchi.jp,oshima.yamaguchi.jp,shimonoseki.yamaguchi.jp,shunan.yamaguchi.jp,tabuse.yamaguchi.jp,tokuyama.yamaguchi.jp,toyota.yamaguchi.jp,ube.yamaguchi.jp,yuu.yamaguchi.jp,chuo.yamanashi.jp,doshi.yamanashi.jp,fuefuki.yamanashi.jp,fujikawa.yamanashi.jp,fujikawaguchiko.yamanashi.jp,fujiyoshida.yamanashi.jp,hayakawa.yamanashi.jp,hokuto.yamanashi.jp,ichikawamisato.yamanashi.jp,kai.yamanashi.jp,kofu.yamanashi.jp,koshu.yamanashi.jp,kosuge.yamanashi.jp,minami-alps.yamanashi.jp,minobu.yamanashi.jp,nakamichi.yamanashi.jp,nanbu.yamanashi.jp,narusawa.yamanashi.jp,nirasaki.yamanashi.jp,nishikatsura.yamanashi.jp,oshino.yamanashi.jp,otsuki.yamanashi.jp,showa.yamanashi.jp,tabayama.yamanashi.jp,tsuru.yamanashi.jp,uenohara.yamanashi.jp,yamanakako.yamanashi.jp,yamanashi.yamanashi.jp,ke,ac.ke,co.ke,go.ke,info.ke,me.ke,mobi.ke,ne.ke,or.ke,sc.ke,kg,com.kg,edu.kg,gov.kg,mil.kg,net.kg,org.kg,kh,com.kh,edu.kh,gov.kh,net.kh,org.kh,ki,biz.ki,com.ki,edu.ki,gov.ki,info.ki,net.ki,org.ki,km,ass.km,com.km,edu.km,gov.km,mil.km,nom.km,org.km,prd.km,tm.km,asso.km,coop.km,gouv.km,medecin.km,notaires.km,pharmaciens.km,presse.km,veterinaire.km,kn,edu.kn,gov.kn,net.kn,org.kn,kp,com.kp,edu.kp,gov.kp,org.kp,rep.kp,tra.kp,kr,ac.kr,ai.kr,co.kr,es.kr,go.kr,hs.kr,io.kr,it.kr,kg.kr,me.kr,mil.kr,ms.kr,ne.kr,or.kr,pe.kr,re.kr,sc.kr,busan.kr,chungbuk.kr,chungnam.kr,daegu.kr,daejeon.kr,gangwon.kr,gwangju.kr,gyeongbuk.kr,gyeonggi.kr,gyeongnam.kr,incheon.kr,jeju.kr,jeonbuk.kr,jeonnam.kr,seoul.kr,ulsan.kr,kw,com.kw,edu.kw,emb.kw,gov.kw,ind.kw,net.kw,org.kw,ky,com.ky,edu.ky,net.ky,org.ky,kz,com.kz,edu.kz,gov.kz,mil.kz,net.kz,org.kz,la,com.la,edu.la,gov.la,info.la,int.la,net.la,org.la,per.la,lb,com.lb,edu.lb,gov.lb,net.lb,org.lb,lc,co.lc,com.lc,edu.lc,gov.lc,net.lc,org.lc,li,lk,ac.lk,assn.lk,com.lk,edu.lk,gov.lk,grp.lk,hotel.lk,int.lk,ltd.lk,net.lk,ngo.lk,org.lk,sch.lk,soc.lk,web.lk,lr,com.lr,edu.lr,gov.lr,net.lr,org.lr,ls,ac.ls,biz.ls,co.ls,edu.ls,gov.ls,info.ls,net.ls,org.ls,sc.ls,lt,gov.lt,lu,lv,asn.lv,com.lv,conf.lv,edu.lv,gov.lv,id.lv,mil.lv,net.lv,org.lv,ly,com.ly,edu.ly,gov.ly,id.ly,med.ly,net.ly,org.ly,plc.ly,sch.ly,ma,ac.ma,co.ma,gov.ma,net.ma,org.ma,press.ma,mc,asso.mc,tm.mc,md,me,ac.me,co.me,edu.me,gov.me,its.me,net.me,org.me,priv.me,mg,co.mg,com.mg,edu.mg,gov.mg,mil.mg,nom.mg,org.mg,prd.mg,mh,mil,mk,com.mk,edu.mk,gov.mk,inf.mk,name.mk,net.mk,org.mk,ml,ac.ml,art.ml,asso.ml,com.ml,edu.ml,gouv.ml,gov.ml,info.ml,inst.ml,net.ml,org.ml,pr.ml,presse.ml,*.mm,mn,edu.mn,gov.mn,org.mn,mo,com.mo,edu.mo,gov.mo,net.mo,org.mo,mobi,mp,mq,mr,gov.mr,ms,com.ms,edu.ms,gov.ms,net.ms,org.ms,mt,com.mt,edu.mt,net.mt,org.mt,mu,ac.mu,co.mu,com.mu,gov.mu,net.mu,or.mu,org.mu,museum,mv,aero.mv,biz.mv,com.mv,coop.mv,edu.mv,gov.mv,info.mv,int.mv,mil.mv,museum.mv,name.mv,net.mv,org.mv,pro.mv,mw,ac.mw,biz.mw,co.mw,com.mw,coop.mw,edu.mw,gov.mw,int.mw,net.mw,org.mw,mx,com.mx,edu.mx,gob.mx,net.mx,org.mx,my,biz.my,com.my,edu.my,gov.my,mil.my,name.my,net.my,org.my,mz,ac.mz,adv.mz,co.mz,edu.mz,gov.mz,mil.mz,net.mz,org.mz,na,alt.na,co.na,com.na,gov.na,net.na,org.na,name,nc,asso.nc,nom.nc,ne,net,nf,arts.nf,com.nf,firm.nf,info.nf,net.nf,other.nf,per.nf,rec.nf,store.nf,web.nf,ng,com.ng,edu.ng,gov.ng,i.ng,mil.ng,mobi.ng,name.ng,net.ng,org.ng,sch.ng,ni,ac.ni,biz.ni,co.ni,com.ni,edu.ni,gob.ni,in.ni,info.ni,int.ni,mil.ni,net.ni,nom.ni,org.ni,web.ni,nl,no,fhs.no,folkebibl.no,fylkesbibl.no,gielda.no,herad.no,idrett.no,kommune.no,museum.no,priv.no,suohkan.no,tjielte.no,uenorge.no,vgs.no,dep.no,mil.no,stat.no,aa.no,ah.no,bu.no,fm.no,hl.no,hm.no,jan-mayen.no,mr.no,nl.no,nt.no,of.no,ol.no,oslo.no,rl.no,sf.no,st.no,svalbard.no,tm.no,tr.no,va.no,vf.no,gs.aa.no,gs.ah.no,gs.bu.no,gs.fm.no,gs.hl.no,gs.hm.no,gs.jan-mayen.no,gs.mr.no,gs.nl.no,gs.nt.no,gs.of.no,gs.ol.no,gs.oslo.no,gs.rl.no,gs.sf.no,gs.st.no,gs.svalbard.no,gs.tm.no,gs.tr.no,gs.va.no,gs.vf.no,akrehamn.no,xn--krehamn-dxa.no,algard.no,xn--lgrd-poac.no,arna.no,bronnoysund.no,xn--brnnysund-m8ac.no,brumunddal.no,bryne.no,drobak.no,xn--drbak-wua.no,egersund.no,fetsund.no,floro.no,xn--flor-jra.no,fredrikstad.no,hokksund.no,honefoss.no,xn--hnefoss-q1a.no,jessheim.no,jorpeland.no,xn--jrpeland-54a.no,kirkenes.no,kopervik.no,krokstadelva.no,langevag.no,xn--langevg-jxa.no,leirvik.no,mjondalen.no,xn--mjndalen-64a.no,mo-i-rana.no,mosjoen.no,xn--mosjen-eya.no,nesoddtangen.no,orkanger.no,osoyro.no,xn--osyro-wua.no,raholt.no,xn--rholt-mra.no,sandnessjoen.no,xn--sandnessjen-ogb.no,skedsmokorset.no,slattum.no,spjelkavik.no,stathelle.no,stavern.no,stjordalshalsen.no,xn--stjrdalshalsen-sqb.no,tananger.no,tranby.no,vossevangen.no,aarborte.no,aejrie.no,afjord.no,xn--fjord-lra.no,agdenes.no,nes.akershus.no,aknoluokta.no,xn--koluokta-7ya57h.no,al.no,xn--l-1fa.no,alaheadju.no,xn--laheadju-7ya.no,alesund.no,xn--lesund-hua.no,alstahaug.no,alta.no,xn--lt-liac.no,alvdal.no,amli.no,xn--mli-tla.no,amot.no,xn--mot-tla.no,andasuolo.no,andebu.no,andoy.no,xn--andy-ira.no,ardal.no,xn--rdal-poa.no,aremark.no,arendal.no,xn--s-1fa.no,aseral.no,xn--seral-lra.no,asker.no,askim.no,askoy.no,xn--asky-ira.no,askvoll.no,asnes.no,xn--snes-poa.no,audnedal.no,aukra.no,aure.no,aurland.no,aurskog-holand.no,xn--aurskog-hland-jnb.no,austevoll.no,austrheim.no,averoy.no,xn--avery-yua.no,badaddja.no,xn--bdddj-mrabd.no,xn--brum-voa.no,bahcavuotna.no,xn--bhcavuotna-s4a.no,bahccavuotna.no,xn--bhccavuotna-k7a.no,baidar.no,xn--bidr-5nac.no,bajddar.no,xn--bjddar-pta.no,balat.no,xn--blt-elab.no,balestrand.no,ballangen.no,balsfjord.no,bamble.no,bardu.no,barum.no,batsfjord.no,xn--btsfjord-9za.no,bearalvahki.no,xn--bearalvhki-y4a.no,beardu.no,beiarn.no,berg.no,bergen.no,berlevag.no,xn--berlevg-jxa.no,bievat.no,xn--bievt-0qa.no,bindal.no,birkenes.no,bjerkreim.no,bjugn.no,bodo.no,xn--bod-2na.no,bokn.no,bomlo.no,xn--bmlo-gra.no,bremanger.no,bronnoy.no,xn--brnny-wuac.no,budejju.no,nes.buskerud.no,bygland.no,bykle.no,cahcesuolo.no,xn--hcesuolo-7ya35b.no,davvenjarga.no,xn--davvenjrga-y4a.no,davvesiida.no,deatnu.no,dielddanuorri.no,divtasvuodna.no,divttasvuotna.no,donna.no,xn--dnna-gra.no,dovre.no,drammen.no,drangedal.no,dyroy.no,xn--dyry-ira.no,eid.no,eidfjord.no,eidsberg.no,eidskog.no,eidsvoll.no,eigersund.no,elverum.no,enebakk.no,engerdal.no,etne.no,etnedal.no,evenassi.no,xn--eveni-0qa01ga.no,evenes.no,evje-og-hornnes.no,farsund.no,fauske.no,fedje.no,fet.no,finnoy.no,xn--finny-yua.no,fitjar.no,fjaler.no,fjell.no,fla.no,xn--fl-zia.no,flakstad.no,flatanger.no,flekkefjord.no,flesberg.no,flora.no,folldal.no,forde.no,xn--frde-gra.no,forsand.no,fosnes.no,xn--frna-woa.no,frana.no,frogn.no,froland.no,frosta.no,froya.no,xn--frya-hra.no,fuoisku.no,fuossko.no,fusa.no,fyresdal.no,gaivuotna.no,xn--givuotna-8ya.no,galsa.no,xn--gls-elac.no,gamvik.no,gangaviika.no,xn--ggaviika-8ya47h.no,gaular.no,gausdal.no,giehtavuoatna.no,gildeskal.no,xn--gildeskl-g0a.no,giske.no,gjemnes.no,gjerdrum.no,gjerstad.no,gjesdal.no,gjovik.no,xn--gjvik-wua.no,gloppen.no,gol.no,gran.no,grane.no,granvin.no,gratangen.no,grimstad.no,grong.no,grue.no,gulen.no,guovdageaidnu.no,ha.no,xn--h-2fa.no,habmer.no,xn--hbmer-xqa.no,hadsel.no,xn--hgebostad-g3a.no,hagebostad.no,halden.no,halsa.no,hamar.no,hamaroy.no,xn--hamary-fya.no,hammarfeasta.no,xn--hmmrfeasta-s4ac.no,hammerfest.no,hapmir.no,xn--hpmir-xqa.no,haram.no,hareid.no,harstad.no,hasvik.no,hattfjelldal.no,haugesund.no,os.hedmark.no,valer.hedmark.no,xn--vler-qoa.hedmark.no,hemne.no,hemnes.no,hemsedal.no,hitra.no,hjartdal.no,hjelmeland.no,hobol.no,xn--hobl-ira.no,hof.no,hol.no,hole.no,holmestrand.no,holtalen.no,xn--holtlen-hxa.no,os.hordaland.no,hornindal.no,horten.no,hoyanger.no,xn--hyanger-q1a.no,hoylandet.no,xn--hylandet-54a.no,hurdal.no,hurum.no,hvaler.no,hyllestad.no,ibestad.no,inderoy.no,xn--indery-fya.no,iveland.no,ivgu.no,jevnaker.no,jolster.no,xn--jlster-bya.no,jondal.no,kafjord.no,xn--kfjord-iua.no,karasjohka.no,xn--krjohka-hwab49j.no,karasjok.no,karlsoy.no,xn--karlsy-fya.no,karmoy.no,xn--karmy-yua.no,kautokeino.no,klabu.no,xn--klbu-woa.no,klepp.no,kongsberg.no,kongsvinger.no,kraanghke.no,xn--kranghke-b0a.no,kragero.no,xn--krager-gya.no,kristiansand.no,kristiansund.no,krodsherad.no,xn--krdsherad-m8a.no,xn--kvfjord-nxa.no,xn--kvnangen-k0a.no,kvafjord.no,kvalsund.no,kvam.no,kvanangen.no,kvinesdal.no,kvinnherad.no,kviteseid.no,kvitsoy.no,xn--kvitsy-fya.no,laakesvuemie.no,xn--lrdal-sra.no,lahppi.no,xn--lhppi-xqa.no,lardal.no,larvik.no,lavagis.no,lavangen.no,leangaviika.no,xn--leagaviika-52b.no,lebesby.no,leikanger.no,leirfjord.no,leka.no,leksvik.no,lenvik.no,lerdal.no,lesja.no,levanger.no,lier.no,lierne.no,lillehammer.no,lillesand.no,lindas.no,xn--linds-pra.no,lindesnes.no,loabat.no,xn--loabt-0qa.no,lodingen.no,xn--ldingen-q1a.no,lom.no,loppa.no,lorenskog.no,xn--lrenskog-54a.no,loten.no,xn--lten-gra.no,lund.no,lunner.no,luroy.no,xn--lury-ira.no,luster.no,lyngdal.no,lyngen.no,malatvuopmi.no,xn--mlatvuopmi-s4a.no,malselv.no,xn--mlselv-iua.no,malvik.no,mandal.no,marker.no,marnardal.no,masfjorden.no,masoy.no,xn--msy-ula0h.no,matta-varjjat.no,xn--mtta-vrjjat-k7af.no,meland.no,meldal.no,melhus.no,meloy.no,xn--mely-ira.no,meraker.no,xn--merker-kua.no,midsund.no,midtre-gauldal.no,moareke.no,xn--moreke-jua.no,modalen.no,modum.no,molde.no,heroy.more-og-romsdal.no,sande.more-og-romsdal.no,xn--hery-ira.xn--mre-og-romsdal-qqb.no,sande.xn--mre-og-romsdal-qqb.no,moskenes.no,moss.no,muosat.no,xn--muost-0qa.no,naamesjevuemie.no,xn--nmesjevuemie-tcba.no,xn--nry-yla5g.no,namdalseid.no,namsos.no,namsskogan.no,nannestad.no,naroy.no,narviika.no,narvik.no,naustdal.no,navuotna.no,xn--nvuotna-hwa.no,nedre-eiker.no,nesna.no,nesodden.no,nesseby.no,nesset.no,nissedal.no,nittedal.no,nord-aurdal.no,nord-fron.no,nord-odal.no,norddal.no,nordkapp.no,bo.nordland.no,xn--b-5ga.nordland.no,heroy.nordland.no,xn--hery-ira.nordland.no,nordre-land.no,nordreisa.no,nore-og-uvdal.no,notodden.no,notteroy.no,xn--nttery-byae.no,odda.no,oksnes.no,xn--ksnes-uua.no,omasvuotna.no,oppdal.no,oppegard.no,xn--oppegrd-ixa.no,orkdal.no,orland.no,xn--rland-uua.no,orskog.no,xn--rskog-uua.no,orsta.no,xn--rsta-fra.no,osen.no,osteroy.no,xn--ostery-fya.no,valer.ostfold.no,xn--vler-qoa.xn--stfold-9xa.no,ostre-toten.no,xn--stre-toten-zcb.no,overhalla.no,ovre-eiker.no,xn--vre-eiker-k8a.no,oyer.no,xn--yer-zna.no,oygarden.no,xn--ygarden-p1a.no,oystre-slidre.no,xn--ystre-slidre-ujb.no,porsanger.no,porsangu.no,xn--porsgu-sta26f.no,porsgrunn.no,rade.no,xn--rde-ula.no,radoy.no,xn--rady-ira.no,xn--rlingen-mxa.no,rahkkeravju.no,xn--rhkkervju-01af.no,raisa.no,xn--risa-5na.no,rakkestad.no,ralingen.no,rana.no,randaberg.no,rauma.no,re.no,rendalen.no,rennebu.no,rennesoy.no,xn--rennesy-v1a.no,rindal.no,ringebu.no,ringerike.no,ringsaker.no,risor.no,xn--risr-ira.no,rissa.no,roan.no,rodoy.no,xn--rdy-0nab.no,rollag.no,romsa.no,romskog.no,xn--rmskog-bya.no,roros.no,xn--rros-gra.no,rost.no,xn--rst-0na.no,royken.no,xn--ryken-vua.no,royrvik.no,xn--ryrvik-bya.no,ruovat.no,rygge.no,salangen.no,salat.no,xn--slat-5na.no,xn--slt-elab.no,saltdal.no,samnanger.no,sandefjord.no,sandnes.no,sandoy.no,xn--sandy-yua.no,sarpsborg.no,sauda.no,sauherad.no,sel.no,selbu.no,selje.no,seljord.no,siellak.no,sigdal.no,siljan.no,sirdal.no,skanit.no,xn--sknit-yqa.no,skanland.no,xn--sknland-fxa.no,skaun.no,skedsmo.no,ski.no,skien.no,skierva.no,xn--skierv-uta.no,skiptvet.no,skjak.no,xn--skjk-soa.no,skjervoy.no,xn--skjervy-v1a.no,skodje.no,smola.no,xn--smla-hra.no,snaase.no,xn--snase-nra.no,snasa.no,xn--snsa-roa.no,snillfjord.no,snoasa.no,sogndal.no,sogne.no,xn--sgne-gra.no,sokndal.no,sola.no,solund.no,somna.no,xn--smna-gra.no,sondre-land.no,xn--sndre-land-0cb.no,songdalen.no,sor-aurdal.no,xn--sr-aurdal-l8a.no,sor-fron.no,xn--sr-fron-q1a.no,sor-odal.no,xn--sr-odal-q1a.no,sor-varanger.no,xn--sr-varanger-ggb.no,sorfold.no,xn--srfold-bya.no,sorreisa.no,xn--srreisa-q1a.no,sortland.no,sorum.no,xn--srum-gra.no,spydeberg.no,stange.no,stavanger.no,steigen.no,steinkjer.no,stjordal.no,xn--stjrdal-s1a.no,stokke.no,stor-elvdal.no,stord.no,stordal.no,storfjord.no,strand.no,stranda.no,stryn.no,sula.no,suldal.no,sund.no,sunndal.no,surnadal.no,sveio.no,svelvik.no,sykkylven.no,tana.no,bo.telemark.no,xn--b-5ga.telemark.no,time.no,tingvoll.no,tinn.no,tjeldsund.no,tjome.no,xn--tjme-hra.no,tokke.no,tolga.no,tonsberg.no,xn--tnsberg-q1a.no,torsken.no,xn--trna-woa.no,trana.no,tranoy.no,xn--trany-yua.no,troandin.no,trogstad.no,xn--trgstad-r1a.no,tromsa.no,tromso.no,xn--troms-zua.no,trondheim.no,trysil.no,tvedestrand.no,tydal.no,tynset.no,tysfjord.no,tysnes.no,xn--tysvr-vra.no,tysvar.no,ullensaker.no,ullensvang.no,ulstein.no,ulvik.no,unjarga.no,xn--unjrga-rta.no,utsira.no,vaapste.no,vadso.no,xn--vads-jra.no,xn--vry-yla5g.no,vaga.no,xn--vg-yiab.no,vagan.no,xn--vgan-qoa.no,vagsoy.no,xn--vgsy-qoa0j.no,vaksdal.no,valle.no,vang.no,vanylven.no,vardo.no,xn--vard-jra.no,varggat.no,xn--vrggt-xqad.no,varoy.no,vefsn.no,vega.no,vegarshei.no,xn--vegrshei-c0a.no,vennesla.no,verdal.no,verran.no,vestby.no,sande.vestfold.no,vestnes.no,vestre-slidre.no,vestre-toten.no,vestvagoy.no,xn--vestvgy-ixa6o.no,vevelstad.no,vik.no,vikna.no,vindafjord.no,voagat.no,volda.no,voss.no,*.np,nr,biz.nr,com.nr,edu.nr,gov.nr,info.nr,net.nr,org.nr,nu,nz,ac.nz,co.nz,cri.nz,geek.nz,gen.nz,govt.nz,health.nz,iwi.nz,kiwi.nz,maori.nz,xn--mori-qsa.nz,mil.nz,net.nz,org.nz,parliament.nz,school.nz,om,co.om,com.om,edu.om,gov.om,med.om,museum.om,net.om,org.om,pro.om,onion,org,pa,abo.pa,ac.pa,com.pa,edu.pa,gob.pa,ing.pa,med.pa,net.pa,nom.pa,org.pa,sld.pa,pe,com.pe,edu.pe,gob.pe,mil.pe,net.pe,nom.pe,org.pe,pf,com.pf,edu.pf,org.pf,*.pg,ph,com.ph,edu.ph,gov.ph,i.ph,mil.ph,net.ph,ngo.ph,org.ph,pk,ac.pk,biz.pk,com.pk,edu.pk,fam.pk,gkp.pk,gob.pk,gog.pk,gok.pk,gop.pk,gos.pk,gov.pk,net.pk,org.pk,web.pk,pl,com.pl,net.pl,org.pl,agro.pl,aid.pl,atm.pl,auto.pl,biz.pl,edu.pl,gmina.pl,gsm.pl,info.pl,mail.pl,media.pl,miasta.pl,mil.pl,nieruchomosci.pl,nom.pl,pc.pl,powiat.pl,priv.pl,realestate.pl,rel.pl,sex.pl,shop.pl,sklep.pl,sos.pl,szkola.pl,targi.pl,tm.pl,tourism.pl,travel.pl,turystyka.pl,gov.pl,ap.gov.pl,griw.gov.pl,ic.gov.pl,is.gov.pl,kmpsp.gov.pl,konsulat.gov.pl,kppsp.gov.pl,kwp.gov.pl,kwpsp.gov.pl,mup.gov.pl,mw.gov.pl,oia.gov.pl,oirm.gov.pl,oke.gov.pl,oow.gov.pl,oschr.gov.pl,oum.gov.pl,pa.gov.pl,pinb.gov.pl,piw.gov.pl,po.gov.pl,pr.gov.pl,psp.gov.pl,psse.gov.pl,pup.gov.pl,rzgw.gov.pl,sa.gov.pl,sdn.gov.pl,sko.gov.pl,so.gov.pl,sr.gov.pl,starostwo.gov.pl,ug.gov.pl,ugim.gov.pl,um.gov.pl,umig.gov.pl,upow.gov.pl,uppo.gov.pl,us.gov.pl,uw.gov.pl,uzs.gov.pl,wif.gov.pl,wiih.gov.pl,winb.gov.pl,wios.gov.pl,witd.gov.pl,wiw.gov.pl,wkz.gov.pl,wsa.gov.pl,wskr.gov.pl,wsse.gov.pl,wuoz.gov.pl,wzmiuw.gov.pl,zp.gov.pl,zpisdn.gov.pl,augustow.pl,babia-gora.pl,bedzin.pl,beskidy.pl,bialowieza.pl,bialystok.pl,bielawa.pl,bieszczady.pl,boleslawiec.pl,bydgoszcz.pl,bytom.pl,cieszyn.pl,czeladz.pl,czest.pl,dlugoleka.pl,elblag.pl,elk.pl,glogow.pl,gniezno.pl,gorlice.pl,grajewo.pl,ilawa.pl,jaworzno.pl,jelenia-gora.pl,jgora.pl,kalisz.pl,karpacz.pl,kartuzy.pl,kaszuby.pl,katowice.pl,kazimierz-dolny.pl,kepno.pl,ketrzyn.pl,klodzko.pl,kobierzyce.pl,kolobrzeg.pl,konin.pl,konskowola.pl,kutno.pl,lapy.pl,lebork.pl,legnica.pl,lezajsk.pl,limanowa.pl,lomza.pl,lowicz.pl,lubin.pl,lukow.pl,malbork.pl,malopolska.pl,mazowsze.pl,mazury.pl,mielec.pl,mielno.pl,mragowo.pl,naklo.pl,nowaruda.pl,nysa.pl,olawa.pl,olecko.pl,olkusz.pl,olsztyn.pl,opoczno.pl,opole.pl,ostroda.pl,ostroleka.pl,ostrowiec.pl,ostrowwlkp.pl,pila.pl,pisz.pl,podhale.pl,podlasie.pl,polkowice.pl,pomorskie.pl,pomorze.pl,prochowice.pl,pruszkow.pl,przeworsk.pl,pulawy.pl,radom.pl,rawa-maz.pl,rybnik.pl,rzeszow.pl,sanok.pl,sejny.pl,skoczow.pl,slask.pl,slupsk.pl,sosnowiec.pl,stalowa-wola.pl,starachowice.pl,stargard.pl,suwalki.pl,swidnica.pl,swiebodzin.pl,swinoujscie.pl,szczecin.pl,szczytno.pl,tarnobrzeg.pl,tgory.pl,turek.pl,tychy.pl,ustka.pl,walbrzych.pl,warmia.pl,warszawa.pl,waw.pl,wegrow.pl,wielun.pl,wlocl.pl,wloclawek.pl,wodzislaw.pl,wolomin.pl,wroclaw.pl,zachpomor.pl,zagan.pl,zarow.pl,zgora.pl,zgorzelec.pl,pm,pn,co.pn,edu.pn,gov.pn,net.pn,org.pn,post,pr,ac.pr,biz.pr,com.pr,edu.pr,est.pr,gov.pr,info.pr,isla.pr,name.pr,net.pr,org.pr,pro.pr,prof.pr,pro,aaa.pro,aca.pro,acct.pro,avocat.pro,bar.pro,cpa.pro,eng.pro,jur.pro,law.pro,med.pro,recht.pro,ps,com.ps,edu.ps,gov.ps,net.ps,org.ps,plo.ps,sec.ps,pt,com.pt,edu.pt,gov.pt,int.pt,net.pt,nome.pt,org.pt,publ.pt,pw,gov.pw,py,com.py,coop.py,edu.py,gov.py,mil.py,net.py,org.py,qa,com.qa,edu.qa,gov.qa,mil.qa,name.qa,net.qa,org.qa,sch.qa,re,asso.re,com.re,ro,arts.ro,com.ro,firm.ro,info.ro,nom.ro,nt.ro,org.ro,rec.ro,store.ro,tm.ro,www.ro,rs,ac.rs,co.rs,edu.rs,gov.rs,in.rs,org.rs,ru,rw,ac.rw,co.rw,coop.rw,gov.rw,mil.rw,net.rw,org.rw,sa,com.sa,edu.sa,gov.sa,med.sa,net.sa,org.sa,pub.sa,sch.sa,sb,com.sb,edu.sb,gov.sb,net.sb,org.sb,sc,com.sc,edu.sc,gov.sc,net.sc,org.sc,sd,com.sd,edu.sd,gov.sd,info.sd,med.sd,net.sd,org.sd,tv.sd,se,a.se,ac.se,b.se,bd.se,brand.se,c.se,d.se,e.se,f.se,fh.se,fhsk.se,fhv.se,g.se,h.se,i.se,k.se,komforb.se,kommunalforbund.se,komvux.se,l.se,lanbib.se,m.se,n.se,naturbruksgymn.se,o.se,org.se,p.se,parti.se,pp.se,press.se,r.se,s.se,t.se,tm.se,u.se,w.se,x.se,y.se,z.se,sg,com.sg,edu.sg,gov.sg,net.sg,org.sg,sh,com.sh,gov.sh,mil.sh,net.sh,org.sh,si,sj,sk,org.sk,sl,com.sl,edu.sl,gov.sl,net.sl,org.sl,sm,sn,art.sn,com.sn,edu.sn,gouv.sn,org.sn,univ.sn,so,com.so,edu.so,gov.so,me.so,net.so,org.so,sr,ss,biz.ss,co.ss,com.ss,edu.ss,gov.ss,me.ss,net.ss,org.ss,sch.ss,st,co.st,com.st,consulado.st,edu.st,embaixada.st,mil.st,net.st,org.st,principe.st,saotome.st,store.st,su,sv,com.sv,edu.sv,gob.sv,org.sv,red.sv,sx,gov.sx,sy,com.sy,edu.sy,gov.sy,mil.sy,net.sy,org.sy,sz,ac.sz,co.sz,org.sz,tc,td,tel,tf,tg,th,ac.th,co.th,go.th,in.th,mi.th,net.th,or.th,tj,biz.tj,co.tj,com.tj,edu.tj,go.tj,gov.tj,int.tj,mil.tj,name.tj,net.tj,nic.tj,org.tj,test.tj,web.tj,tk,tl,gov.tl,tm,co.tm,com.tm,edu.tm,gov.tm,mil.tm,net.tm,nom.tm,org.tm,tn,com.tn,ens.tn,fin.tn,gov.tn,ind.tn,info.tn,intl.tn,mincom.tn,nat.tn,net.tn,org.tn,perso.tn,tourism.tn,to,com.to,edu.to,gov.to,mil.to,net.to,org.to,tr,av.tr,bbs.tr,bel.tr,biz.tr,com.tr,dr.tr,edu.tr,gen.tr,gov.tr,info.tr,k12.tr,kep.tr,mil.tr,name.tr,net.tr,org.tr,pol.tr,tel.tr,tsk.tr,tv.tr,web.tr,nc.tr,gov.nc.tr,tt,biz.tt,co.tt,com.tt,edu.tt,gov.tt,info.tt,mil.tt,name.tt,net.tt,org.tt,pro.tt,tv,tw,club.tw,com.tw,ebiz.tw,edu.tw,game.tw,gov.tw,idv.tw,mil.tw,net.tw,org.tw,tz,ac.tz,co.tz,go.tz,hotel.tz,info.tz,me.tz,mil.tz,mobi.tz,ne.tz,or.tz,sc.tz,tv.tz,ua,com.ua,edu.ua,gov.ua,in.ua,net.ua,org.ua,cherkassy.ua,cherkasy.ua,chernigov.ua,chernihiv.ua,chernivtsi.ua,chernovtsy.ua,ck.ua,cn.ua,cr.ua,crimea.ua,cv.ua,dn.ua,dnepropetrovsk.ua,dnipropetrovsk.ua,donetsk.ua,dp.ua,if.ua,ivano-frankivsk.ua,kh.ua,kharkiv.ua,kharkov.ua,kherson.ua,khmelnitskiy.ua,khmelnytskyi.ua,kiev.ua,kirovograd.ua,km.ua,kr.ua,kropyvnytskyi.ua,krym.ua,ks.ua,kv.ua,kyiv.ua,lg.ua,lt.ua,lugansk.ua,luhansk.ua,lutsk.ua,lv.ua,lviv.ua,mk.ua,mykolaiv.ua,nikolaev.ua,od.ua,odesa.ua,odessa.ua,pl.ua,poltava.ua,rivne.ua,rovno.ua,rv.ua,sb.ua,sebastopol.ua,sevastopol.ua,sm.ua,sumy.ua,te.ua,ternopil.ua,uz.ua,uzhgorod.ua,uzhhorod.ua,vinnica.ua,vinnytsia.ua,vn.ua,volyn.ua,yalta.ua,zakarpattia.ua,zaporizhzhe.ua,zaporizhzhia.ua,zhitomir.ua,zhytomyr.ua,zp.ua,zt.ua,ug,ac.ug,co.ug,com.ug,edu.ug,go.ug,gov.ug,mil.ug,ne.ug,or.ug,org.ug,sc.ug,us.ug,uk,ac.uk,co.uk,gov.uk,ltd.uk,me.uk,net.uk,nhs.uk,org.uk,plc.uk,police.uk,*.sch.uk,us,dni.us,isa.us,nsn.us,ak.us,al.us,ar.us,as.us,az.us,ca.us,co.us,ct.us,dc.us,de.us,fl.us,ga.us,gu.us,hi.us,ia.us,id.us,il.us,in.us,ks.us,ky.us,la.us,ma.us,md.us,me.us,mi.us,mn.us,mo.us,ms.us,mt.us,nc.us,nd.us,ne.us,nh.us,nj.us,nm.us,nv.us,ny.us,oh.us,ok.us,or.us,pa.us,pr.us,ri.us,sc.us,sd.us,tn.us,tx.us,ut.us,va.us,vi.us,vt.us,wa.us,wi.us,wv.us,wy.us,k12.ak.us,k12.al.us,k12.ar.us,k12.as.us,k12.az.us,k12.ca.us,k12.co.us,k12.ct.us,k12.dc.us,k12.fl.us,k12.ga.us,k12.gu.us,k12.ia.us,k12.id.us,k12.il.us,k12.in.us,k12.ks.us,k12.ky.us,k12.la.us,k12.ma.us,k12.md.us,k12.me.us,k12.mi.us,k12.mn.us,k12.mo.us,k12.ms.us,k12.mt.us,k12.nc.us,k12.ne.us,k12.nh.us,k12.nj.us,k12.nm.us,k12.nv.us,k12.ny.us,k12.oh.us,k12.ok.us,k12.or.us,k12.pa.us,k12.pr.us,k12.sc.us,k12.tn.us,k12.tx.us,k12.ut.us,k12.va.us,k12.vi.us,k12.vt.us,k12.wa.us,k12.wi.us,cc.ak.us,lib.ak.us,cc.al.us,lib.al.us,cc.ar.us,lib.ar.us,cc.as.us,lib.as.us,cc.az.us,lib.az.us,cc.ca.us,lib.ca.us,cc.co.us,lib.co.us,cc.ct.us,lib.ct.us,cc.dc.us,lib.dc.us,cc.de.us,cc.fl.us,lib.fl.us,cc.ga.us,lib.ga.us,cc.gu.us,lib.gu.us,cc.hi.us,lib.hi.us,cc.ia.us,lib.ia.us,cc.id.us,lib.id.us,cc.il.us,lib.il.us,cc.in.us,lib.in.us,cc.ks.us,lib.ks.us,cc.ky.us,lib.ky.us,cc.la.us,lib.la.us,cc.ma.us,lib.ma.us,cc.md.us,lib.md.us,cc.me.us,lib.me.us,cc.mi.us,lib.mi.us,cc.mn.us,lib.mn.us,cc.mo.us,lib.mo.us,cc.ms.us,cc.mt.us,lib.mt.us,cc.nc.us,lib.nc.us,cc.ne.us,lib.ne.us,cc.nh.us,lib.nh.us,cc.nj.us,lib.nj.us,cc.nm.us,lib.nm.us,cc.nv.us,lib.nv.us,cc.ny.us,lib.ny.us,cc.oh.us,lib.oh.us,cc.ok.us,lib.ok.us,cc.or.us,lib.or.us,cc.pa.us,lib.pa.us,cc.pr.us,lib.pr.us,cc.ri.us,lib.ri.us,cc.sc.us,lib.sc.us,cc.sd.us,lib.sd.us,cc.tn.us,lib.tn.us,cc.tx.us,lib.tx.us,cc.ut.us,lib.ut.us,cc.va.us,lib.va.us,cc.vi.us,lib.vi.us,cc.vt.us,lib.vt.us,cc.wa.us,lib.wa.us,cc.wi.us,lib.wi.us,cc.wv.us,cc.wy.us,k12.wy.us,lib.wy.us,chtr.k12.ma.us,paroch.k12.ma.us,pvt.k12.ma.us,ann-arbor.mi.us,cog.mi.us,dst.mi.us,eaton.mi.us,gen.mi.us,mus.mi.us,tec.mi.us,washtenaw.mi.us,uy,com.uy,edu.uy,gub.uy,mil.uy,net.uy,org.uy,uz,co.uz,com.uz,net.uz,org.uz,va,vc,com.vc,edu.vc,gov.vc,mil.vc,net.vc,org.vc,ve,arts.ve,bib.ve,co.ve,com.ve,e12.ve,edu.ve,emprende.ve,firm.ve,gob.ve,gov.ve,ia.ve,info.ve,int.ve,mil.ve,net.ve,nom.ve,org.ve,rar.ve,rec.ve,store.ve,tec.ve,web.ve,vg,edu.vg,vi,co.vi,com.vi,k12.vi,net.vi,org.vi,vn,ac.vn,ai.vn,biz.vn,com.vn,edu.vn,gov.vn,health.vn,id.vn,info.vn,int.vn,io.vn,name.vn,net.vn,org.vn,pro.vn,angiang.vn,bacgiang.vn,backan.vn,baclieu.vn,bacninh.vn,baria-vungtau.vn,bentre.vn,binhdinh.vn,binhduong.vn,binhphuoc.vn,binhthuan.vn,camau.vn,cantho.vn,caobang.vn,daklak.vn,daknong.vn,danang.vn,dienbien.vn,dongnai.vn,dongthap.vn,gialai.vn,hagiang.vn,haiduong.vn,haiphong.vn,hanam.vn,hanoi.vn,hatinh.vn,haugiang.vn,hoabinh.vn,hue.vn,hungyen.vn,khanhhoa.vn,kiengiang.vn,kontum.vn,laichau.vn,lamdong.vn,langson.vn,laocai.vn,longan.vn,namdinh.vn,nghean.vn,ninhbinh.vn,ninhthuan.vn,phutho.vn,phuyen.vn,quangbinh.vn,quangnam.vn,quangngai.vn,quangninh.vn,quangtri.vn,soctrang.vn,sonla.vn,tayninh.vn,thaibinh.vn,thainguyen.vn,thanhhoa.vn,thanhphohochiminh.vn,thuathienhue.vn,tiengiang.vn,travinh.vn,tuyenquang.vn,vinhlong.vn,vinhphuc.vn,yenbai.vn,vu,com.vu,edu.vu,net.vu,org.vu,wf,ws,com.ws,edu.ws,gov.ws,net.ws,org.ws,yt,xn--mgbaam7a8h,xn--y9a3aq,xn--54b7fta0cc,xn--90ae,xn--mgbcpq6gpa1a,xn--90ais,xn--fiqs8s,xn--fiqz9s,xn--lgbbat1ad8j,xn--wgbh1c,xn--e1a4c,xn--qxa6a,xn--mgbah1a3hjkrd,xn--node,xn--qxam,xn--j6w193g,xn--gmqw5a.xn--j6w193g,xn--55qx5d.xn--j6w193g,xn--mxtq1m.xn--j6w193g,xn--wcvs22d.xn--j6w193g,xn--uc0atv.xn--j6w193g,xn--od0alg.xn--j6w193g,xn--2scrj9c,xn--3hcrj9c,xn--45br5cyl,xn--h2breg3eve,xn--h2brj9c8c,xn--mgbgu82a,xn--rvc1e0am3e,xn--h2brj9c,xn--mgbbh1a,xn--mgbbh1a71e,xn--fpcrj9c3d,xn--gecrj9c,xn--s9brj9c,xn--45brj9c,xn--xkc2dl3a5ee0h,xn--mgba3a4f16a,xn--mgba3a4fra,xn--mgbtx2b,xn--mgbayh7gpa,xn--3e0b707e,xn--80ao21a,xn--q7ce6a,xn--fzc2c9e2c,xn--xkc2al3hye2a,xn--mgbc0a9azcg,xn--d1alf,xn--l1acc,xn--mix891f,xn--mix082f,xn--mgbx4cd0ab,xn--mgb9awbf,xn--mgbai9azgqp6j,xn--mgbai9a5eva00b,xn--ygbi2ammx,xn--90a3ac,xn--80au.xn--90a3ac,xn--90azh.xn--90a3ac,xn--d1at.xn--90a3ac,xn--c1avg.xn--90a3ac,xn--o1ac.xn--90a3ac,xn--o1ach.xn--90a3ac,xn--p1ai,xn--wgbl6a,xn--mgberp4a5d4ar,xn--mgberp4a5d4a87g,xn--mgbqly7c0a67fbc,xn--mgbqly7cvafr,xn--mgbpl2fh,xn--yfro4i67o,xn--clchc0ea0b2g2a9gcd,xn--ogbpf8fl,xn--mgbtf8fl,xn--o3cw4h,xn--o3cyx2a.xn--o3cw4h,xn--12co0c3b4eva.xn--o3cw4h,xn--m3ch0j3a.xn--o3cw4h,xn--h3cuzk1di.xn--o3cw4h,xn--12c1fe0br.xn--o3cw4h,xn--12cfi8ixb8l.xn--o3cw4h,xn--pgbs0dh,xn--kpry57d,xn--kprw13d,xn--nnx388a,xn--j1amh,xn--mgb2ddes,xxx,ye,com.ye,edu.ye,gov.ye,mil.ye,net.ye,org.ye,ac.za,agric.za,alt.za,co.za,edu.za,gov.za,grondar.za,law.za,mil.za,net.za,ngo.za,nic.za,nis.za,nom.za,org.za,school.za,tm.za,web.za,zm,ac.zm,biz.zm,co.zm,com.zm,edu.zm,gov.zm,info.zm,mil.zm,net.zm,org.zm,sch.zm,zw,ac.zw,co.zw,gov.zw,mil.zw,org.zw,aaa,aarp,abb,abbott,abbvie,abc,able,abogado,abudhabi,academy,accenture,accountant,accountants,aco,actor,ads,adult,aeg,aetna,afl,africa,agakhan,agency,aig,airbus,airforce,airtel,akdn,alibaba,alipay,allfinanz,allstate,ally,alsace,alstom,amazon,americanexpress,americanfamily,amex,amfam,amica,amsterdam,analytics,android,anquan,anz,aol,apartments,app,apple,aquarelle,arab,aramco,archi,army,art,arte,asda,associates,athleta,attorney,auction,audi,audible,audio,auspost,author,auto,autos,aws,axa,azure,baby,baidu,banamex,band,bank,bar,barcelona,barclaycard,barclays,barefoot,bargains,baseball,basketball,bauhaus,bayern,bbc,bbt,bbva,bcg,bcn,beats,beauty,beer,berlin,best,bestbuy,bet,bharti,bible,bid,bike,bing,bingo,bio,black,blackfriday,blockbuster,blog,bloomberg,blue,bms,bmw,bnpparibas,boats,boehringer,bofa,bom,bond,boo,book,booking,bosch,bostik,boston,bot,boutique,box,bradesco,bridgestone,broadway,broker,brother,brussels,build,builders,business,buy,buzz,bzh,cab,cafe,cal,call,calvinklein,cam,camera,camp,canon,capetown,capital,capitalone,car,caravan,cards,care,career,careers,cars,casa,case,cash,casino,catering,catholic,cba,cbn,cbre,center,ceo,cern,cfa,cfd,chanel,channel,charity,chase,chat,cheap,chintai,christmas,chrome,church,cipriani,circle,cisco,citadel,citi,citic,city,claims,cleaning,click,clinic,clinique,clothing,cloud,club,clubmed,coach,codes,coffee,college,cologne,commbank,community,company,compare,computer,comsec,condos,construction,consulting,contact,contractors,cooking,cool,corsica,country,coupon,coupons,courses,cpa,credit,creditcard,creditunion,cricket,crown,crs,cruise,cruises,cuisinella,cymru,cyou,dad,dance,data,date,dating,datsun,day,dclk,dds,deal,dealer,deals,degree,delivery,dell,deloitte,delta,democrat,dental,dentist,desi,design,dev,dhl,diamonds,diet,digital,direct,directory,discount,discover,dish,diy,dnp,docs,doctor,dog,domains,dot,download,drive,dtv,dubai,dupont,durban,dvag,dvr,earth,eat,eco,edeka,education,email,emerck,energy,engineer,engineering,enterprises,epson,equipment,ericsson,erni,esq,estate,eurovision,eus,events,exchange,expert,exposed,express,extraspace,fage,fail,fairwinds,faith,family,fan,fans,farm,farmers,fashion,fast,fedex,feedback,ferrari,ferrero,fidelity,fido,film,final,finance,financial,fire,firestone,firmdale,fish,fishing,fit,fitness,flickr,flights,flir,florist,flowers,fly,foo,food,football,ford,forex,forsale,forum,foundation,fox,free,fresenius,frl,frogans,frontier,ftr,fujitsu,fun,fund,furniture,futbol,fyi,gal,gallery,gallo,gallup,game,games,gap,garden,gay,gbiz,gdn,gea,gent,genting,george,ggee,gift,gifts,gives,giving,glass,gle,global,globo,gmail,gmbh,gmo,gmx,godaddy,gold,goldpoint,golf,goodyear,goog,google,gop,got,grainger,graphics,gratis,green,gripe,grocery,group,gucci,guge,guide,guitars,guru,hair,hamburg,hangout,haus,hbo,hdfc,hdfcbank,health,healthcare,help,helsinki,here,hermes,hiphop,hisamitsu,hitachi,hiv,hkt,hockey,holdings,holiday,homedepot,homegoods,homes,homesense,honda,horse,hospital,host,hosting,hot,hotel,hotels,hotmail,house,how,hsbc,hughes,hyatt,hyundai,ibm,icbc,ice,icu,ieee,ifm,ikano,imamat,imdb,immo,immobilien,inc,industries,infiniti,ing,ink,institute,insurance,insure,international,intuit,investments,ipiranga,irish,ismaili,ist,istanbul,itau,itv,jaguar,java,jcb,jeep,jetzt,jewelry,jio,jll,jmp,jnj,joburg,jot,joy,jpmorgan,jprs,juegos,kaufen,kddi,kerryhotels,kerryproperties,kfh,kia,kids,kim,kindle,kitchen,kiwi,koeln,komatsu,kosher,kpmg,kpn,krd,kred,kuokgroup,kyoto,lacaixa,lamborghini,lamer,land,landrover,lanxess,lasalle,lat,latino,latrobe,law,lawyer,lds,lease,leclerc,lefrak,legal,lego,lexus,lgbt,lidl,life,lifeinsurance,lifestyle,lighting,like,lilly,limited,limo,lincoln,link,live,living,llc,llp,loan,loans,locker,locus,lol,london,lotte,lotto,love,lpl,lplfinancial,ltd,ltda,lundbeck,luxe,luxury,madrid,maif,maison,makeup,man,management,mango,map,market,marketing,markets,marriott,marshalls,mattel,mba,mckinsey,med,media,meet,melbourne,meme,memorial,men,menu,merck,merckmsd,miami,microsoft,mini,mint,mit,mitsubishi,mlb,mls,mma,mobile,moda,moe,moi,mom,monash,money,monster,mormon,mortgage,moscow,moto,motorcycles,mov,movie,msd,mtn,mtr,music,nab,nagoya,navy,nba,nec,netbank,netflix,network,neustar,new,news,next,nextdirect,nexus,nfl,ngo,nhk,nico,nike,nikon,ninja,nissan,nissay,nokia,norton,now,nowruz,nowtv,nra,nrw,ntt,nyc,obi,observer,office,okinawa,olayan,olayangroup,ollo,omega,one,ong,onl,online,ooo,open,oracle,orange,organic,origins,osaka,otsuka,ott,ovh,page,panasonic,paris,pars,partners,parts,party,pay,pccw,pet,pfizer,pharmacy,phd,philips,phone,photo,photography,photos,physio,pics,pictet,pictures,pid,pin,ping,pink,pioneer,pizza,place,play,playstation,plumbing,plus,pnc,pohl,poker,politie,porn,praxi,press,prime,prod,productions,prof,progressive,promo,properties,property,protection,pru,prudential,pub,pwc,qpon,quebec,quest,racing,radio,read,realestate,realtor,realty,recipes,red,redumbrella,rehab,reise,reisen,reit,reliance,ren,rent,rentals,repair,report,republican,rest,restaurant,review,reviews,rexroth,rich,richardli,ricoh,ril,rio,rip,rocks,rodeo,rogers,room,rsvp,rugby,ruhr,run,rwe,ryukyu,saarland,safe,safety,sakura,sale,salon,samsclub,samsung,sandvik,sandvikcoromant,sanofi,sap,sarl,sas,save,saxo,sbi,sbs,scb,schaeffler,schmidt,scholarships,school,schule,schwarz,science,scot,search,seat,secure,security,seek,select,sener,services,seven,sew,sex,sexy,sfr,shangrila,sharp,shell,shia,shiksha,shoes,shop,shopping,shouji,show,silk,sina,singles,site,ski,skin,sky,skype,sling,smart,smile,sncf,soccer,social,softbank,software,sohu,solar,solutions,song,sony,soy,spa,space,sport,spot,srl,stada,staples,star,statebank,statefarm,stc,stcgroup,stockholm,storage,store,stream,studio,study,style,sucks,supplies,supply,support,surf,surgery,suzuki,swatch,swiss,sydney,systems,tab,taipei,talk,taobao,target,tatamotors,tatar,tattoo,tax,taxi,tci,tdk,team,tech,technology,temasek,tennis,teva,thd,theater,theatre,tiaa,tickets,tienda,tips,tires,tirol,tjmaxx,tjx,tkmaxx,tmall,today,tokyo,tools,top,toray,toshiba,total,tours,town,toyota,toys,trade,trading,training,travel,travelers,travelersinsurance,trust,trv,tube,tui,tunes,tushu,tvs,ubank,ubs,unicom,university,uno,uol,ups,vacations,vana,vanguard,vegas,ventures,verisign,versicherung,vet,viajes,video,vig,viking,villas,vin,vip,virgin,visa,vision,viva,vivo,vlaanderen,vodka,volvo,vote,voting,voto,voyage,wales,walmart,walter,wang,wanggou,watch,watches,weather,weatherchannel,web,webcam,weber,website,wed,wedding,weibo,weir,whoswho,wien,wiki,williamhill,win,windows,wine,winners,wme,woodside,work,works,world,wow,wtc,wtf,xbox,xerox,xihuan,xin,xn--11b4c3d,xn--1ck2e1b,xn--1qqw23a,xn--30rr7y,xn--3bst00m,xn--3ds443g,xn--3pxu8k,xn--42c2d9a,xn--45q11c,xn--4gbrim,xn--55qw42g,xn--55qx5d,xn--5su34j936bgsg,xn--5tzm5g,xn--6frz82g,xn--6qq986b3xl,xn--80adxhks,xn--80aqecdr1a,xn--80asehdb,xn--80aswg,xn--8y0a063a,xn--9dbq2a,xn--9et52u,xn--9krt00a,xn--b4w605ferd,xn--bck1b9a5dre4c,xn--c1avg,xn--c2br7g,xn--cck2b3b,xn--cckwcxetd,xn--cg4bki,xn--czr694b,xn--czrs0t,xn--czru2d,xn--d1acj3b,xn--eckvdtc9d,xn--efvy88h,xn--fct429k,xn--fhbei,xn--fiq228c5hs,xn--fiq64b,xn--fjq720a,xn--flw351e,xn--fzys8d69uvgm,xn--g2xx48c,xn--gckr3f0f,xn--gk3at1e,xn--hxt814e,xn--i1b6b1a6a2e,xn--imr513n,xn--io0a7i,xn--j1aef,xn--jlq480n2rg,xn--jvr189m,xn--kcrx77d1x4a,xn--kput3i,xn--mgba3a3ejt,xn--mgba7c0bbn0a,xn--mgbab2bd,xn--mgbca7dzdo,xn--mgbi4ecexp,xn--mgbt3dhd,xn--mk1bu44c,xn--mxtq1m,xn--ngbc5azd,xn--ngbe9e0a,xn--ngbrx,xn--nqv7f,xn--nqv7fs00ema,xn--nyqy26a,xn--otu796d,xn--p1acf,xn--pssy2u,xn--q9jyb4c,xn--qcka1pmc,xn--rhqv96g,xn--rovu88b,xn--ses554g,xn--t60b56a,xn--tckwe,xn--tiq49xqyj,xn--unup4y,xn--vermgensberater-ctb,xn--vermgensberatung-pwb,xn--vhquv,xn--vuq861b,xn--w4r85el8fhu5dnra,xn--w4rs40l,xn--xhq521b,xn--zfr164b,xyz,yachts,yahoo,yamaxun,yandex,yodobashi,yoga,yokohama,you,youtube,yun,zappos,zara,zero,zip,zone,zuerich,co.krd,edu.krd,art.pl,gliwice.pl,krakow.pl,poznan.pl,wroc.pl,zakopane.pl,cc.ua,inf.ua,ltd.ua,611.to,a2hosted.com,cpserver.com,activetrail.biz,myaddr.dev,myaddr.io,dyn.addr.tools,myaddr.tools,adobeaemcloud.com,*.dev.adobeaemcloud.com,aem.live,hlx.live,adobeaemcloud.net,aem.network,aem.page,hlx.page,aem.reviews,adobeio-static.net,adobeioruntime.net,africa.com,*.auiusercontent.com,beep.pl,aiven.app,*.aivencloud.com,akadns.net,akamai.net,akamai-staging.net,akamaiedge.net,akamaiedge-staging.net,akamaihd.net,akamaihd-staging.net,akamaiorigin.net,akamaiorigin-staging.net,akamaized.net,akamaized-staging.net,edgekey.net,edgekey-staging.net,edgesuite.net,edgesuite-staging.net,barsy.ca,*.compute.estate,*.alces.network,alibabacloudcs.com,*.ztna-dingtalk.com,ms.fun,ms.show,kasserver.com,altervista.org,alwaysdata.net,myamaze.net,execute-api.cn-north-1.amazonaws.com.cn,execute-api.cn-northwest-1.amazonaws.com.cn,execute-api.af-south-1.amazonaws.com,execute-api.ap-east-1.amazonaws.com,execute-api.ap-northeast-1.amazonaws.com,execute-api.ap-northeast-2.amazonaws.com,execute-api.ap-northeast-3.amazonaws.com,execute-api.ap-south-1.amazonaws.com,execute-api.ap-south-2.amazonaws.com,execute-api.ap-southeast-1.amazonaws.com,execute-api.ap-southeast-2.amazonaws.com,execute-api.ap-southeast-3.amazonaws.com,execute-api.ap-southeast-4.amazonaws.com,execute-api.ap-southeast-5.amazonaws.com,execute-api.ca-central-1.amazonaws.com,execute-api.ca-west-1.amazonaws.com,execute-api.eu-central-1.amazonaws.com,execute-api.eu-central-2.amazonaws.com,execute-api.eu-north-1.amazonaws.com,execute-api.eu-south-1.amazonaws.com,execute-api.eu-south-2.amazonaws.com,execute-api.eu-west-1.amazonaws.com,execute-api.eu-west-2.amazonaws.com,execute-api.eu-west-3.amazonaws.com,execute-api.il-central-1.amazonaws.com,execute-api.me-central-1.amazonaws.com,execute-api.me-south-1.amazonaws.com,execute-api.sa-east-1.amazonaws.com,execute-api.us-east-1.amazonaws.com,execute-api.us-east-2.amazonaws.com,execute-api.us-gov-east-1.amazonaws.com,execute-api.us-gov-west-1.amazonaws.com,execute-api.us-west-1.amazonaws.com,execute-api.us-west-2.amazonaws.com,consent-portal.bedrock-agentcore.ap-northeast-1.amazonaws.com,consent-portal.bedrock-agentcore.ap-northeast-2.amazonaws.com,consent-portal.bedrock-agentcore.ap-south-1.amazonaws.com,consent-portal.bedrock-agentcore.ap-south-2.amazonaws.com,consent-portal.bedrock-agentcore.ap-southeast-1.amazonaws.com,consent-portal.bedrock-agentcore.ap-southeast-2.amazonaws.com,consent-portal.bedrock-agentcore.ap-southeast-5.amazonaws.com,consent-portal.bedrock-agentcore.ap-southeast-7.amazonaws.com,consent-portal.bedrock-agentcore.ca-central-1.amazonaws.com,consent-portal.bedrock-agentcore.eu-central-1.amazonaws.com,consent-portal.bedrock-agentcore.eu-north-1.amazonaws.com,consent-portal.bedrock-agentcore.eu-south-1.amazonaws.com,consent-portal.bedrock-agentcore.eu-south-2.amazonaws.com,consent-portal.bedrock-agentcore.eu-west-1.amazonaws.com,consent-portal.bedrock-agentcore.eu-west-2.amazonaws.com,consent-portal.bedrock-agentcore.eu-west-3.amazonaws.com,consent-portal.bedrock-agentcore.sa-east-1.amazonaws.com,consent-portal.bedrock-agentcore.us-east-1.amazonaws.com,consent-portal.bedrock-agentcore.us-east-2.amazonaws.com,consent-portal.bedrock-agentcore.us-gov-west-1.amazonaws.com,consent-portal.bedrock-agentcore.us-west-1.amazonaws.com,consent-portal.bedrock-agentcore.us-west-2.amazonaws.com,cloudfront.net,auth.af-south-1.amazoncognito.com,auth.ap-east-1.amazoncognito.com,auth.ap-east-2.amazoncognito.com,auth.ap-northeast-1.amazoncognito.com,auth.ap-northeast-2.amazoncognito.com,auth.ap-northeast-3.amazoncognito.com,auth.ap-south-1.amazoncognito.com,auth.ap-south-2.amazoncognito.com,auth.ap-southeast-1.amazoncognito.com,auth.ap-southeast-2.amazoncognito.com,auth.ap-southeast-3.amazoncognito.com,auth.ap-southeast-4.amazoncognito.com,auth.ap-southeast-5.amazoncognito.com,auth.ap-southeast-6.amazoncognito.com,auth.ap-southeast-7.amazoncognito.com,auth.ca-central-1.amazoncognito.com,auth.ca-west-1.amazoncognito.com,auth.eu-central-1.amazoncognito.com,auth.eu-central-2.amazoncognito.com,auth.eu-north-1.amazoncognito.com,auth.eu-south-1.amazoncognito.com,auth.eu-south-2.amazoncognito.com,auth.eu-west-1.amazoncognito.com,auth.eu-west-2.amazoncognito.com,auth.eu-west-3.amazoncognito.com,auth.il-central-1.amazoncognito.com,auth.me-central-1.amazoncognito.com,auth.me-south-1.amazoncognito.com,auth.mx-central-1.amazoncognito.com,auth.sa-east-1.amazoncognito.com,auth.us-east-1.amazoncognito.com,auth-fips.us-east-1.amazoncognito.com,auth.us-east-2.amazoncognito.com,auth-fips.us-east-2.amazoncognito.com,auth-fips.us-gov-east-1.amazoncognito.com,auth-fips.us-gov-west-1.amazoncognito.com,auth.us-west-1.amazoncognito.com,auth-fips.us-west-1.amazoncognito.com,auth.us-west-2.amazoncognito.com,auth-fips.us-west-2.amazoncognito.com,auth.cognito-idp.eusc-de-east-1.on.amazonwebservices.eu,*.compute.amazonaws.com.cn,*.compute.amazonaws.com,*.compute-1.amazonaws.com,us-east-1.amazonaws.com,emrappui-prod.cn-north-1.amazonaws.com.cn,emrnotebooks-prod.cn-north-1.amazonaws.com.cn,emrstudio-prod.cn-north-1.amazonaws.com.cn,emrappui-prod.cn-northwest-1.amazonaws.com.cn,emrnotebooks-prod.cn-northwest-1.amazonaws.com.cn,emrstudio-prod.cn-northwest-1.amazonaws.com.cn,emrappui-prod.af-south-1.amazonaws.com,emrnotebooks-prod.af-south-1.amazonaws.com,emrstudio-prod.af-south-1.amazonaws.com,emrappui-prod.ap-east-1.amazonaws.com,emrnotebooks-prod.ap-east-1.amazonaws.com,emrstudio-prod.ap-east-1.amazonaws.com,emrappui-prod.ap-northeast-1.amazonaws.com,emrnotebooks-prod.ap-northeast-1.amazonaws.com,emrstudio-prod.ap-northeast-1.amazonaws.com,emrappui-prod.ap-northeast-2.amazonaws.com,emrnotebooks-prod.ap-northeast-2.amazonaws.com,emrstudio-prod.ap-northeast-2.amazonaws.com,emrappui-prod.ap-northeast-3.amazonaws.com,emrnotebooks-prod.ap-northeast-3.amazonaws.com,emrstudio-prod.ap-northeast-3.amazonaws.com,emrappui-prod.ap-south-1.amazonaws.com,emrnotebooks-prod.ap-south-1.amazonaws.com,emrstudio-prod.ap-south-1.amazonaws.com,emrappui-prod.ap-south-2.amazonaws.com,emrnotebooks-prod.ap-south-2.amazonaws.com,emrstudio-prod.ap-south-2.amazonaws.com,emrappui-prod.ap-southeast-1.amazonaws.com,emrnotebooks-prod.ap-southeast-1.amazonaws.com,emrstudio-prod.ap-southeast-1.amazonaws.com,emrappui-prod.ap-southeast-2.amazonaws.com,emrnotebooks-prod.ap-southeast-2.amazonaws.com,emrstudio-prod.ap-southeast-2.amazonaws.com,emrappui-prod.ap-southeast-3.amazonaws.com,emrnotebooks-prod.ap-southeast-3.amazonaws.com,emrstudio-prod.ap-southeast-3.amazonaws.com,emrappui-prod.ap-southeast-4.amazonaws.com,emrnotebooks-prod.ap-southeast-4.amazonaws.com,emrstudio-prod.ap-southeast-4.amazonaws.com,emrappui-prod.ca-central-1.amazonaws.com,emrnotebooks-prod.ca-central-1.amazonaws.com,emrstudio-prod.ca-central-1.amazonaws.com,emrappui-prod.ca-west-1.amazonaws.com,emrnotebooks-prod.ca-west-1.amazonaws.com,emrstudio-prod.ca-west-1.amazonaws.com,emrappui-prod.eu-central-1.amazonaws.com,emrnotebooks-prod.eu-central-1.amazonaws.com,emrstudio-prod.eu-central-1.amazonaws.com,emrappui-prod.eu-central-2.amazonaws.com,emrnotebooks-prod.eu-central-2.amazonaws.com,emrstudio-prod.eu-central-2.amazonaws.com,emrappui-prod.eu-north-1.amazonaws.com,emrnotebooks-prod.eu-north-1.amazonaws.com,emrstudio-prod.eu-north-1.amazonaws.com,emrappui-prod.eu-south-1.amazonaws.com,emrnotebooks-prod.eu-south-1.amazonaws.com,emrstudio-prod.eu-south-1.amazonaws.com,emrappui-prod.eu-south-2.amazonaws.com,emrnotebooks-prod.eu-south-2.amazonaws.com,emrstudio-prod.eu-south-2.amazonaws.com,emrappui-prod.eu-west-1.amazonaws.com,emrnotebooks-prod.eu-west-1.amazonaws.com,emrstudio-prod.eu-west-1.amazonaws.com,emrappui-prod.eu-west-2.amazonaws.com,emrnotebooks-prod.eu-west-2.amazonaws.com,emrstudio-prod.eu-west-2.amazonaws.com,emrappui-prod.eu-west-3.amazonaws.com,emrnotebooks-prod.eu-west-3.amazonaws.com,emrstudio-prod.eu-west-3.amazonaws.com,emrappui-prod.il-central-1.amazonaws.com,emrnotebooks-prod.il-central-1.amazonaws.com,emrstudio-prod.il-central-1.amazonaws.com,emrappui-prod.me-central-1.amazonaws.com,emrnotebooks-prod.me-central-1.amazonaws.com,emrstudio-prod.me-central-1.amazonaws.com,emrappui-prod.me-south-1.amazonaws.com,emrnotebooks-prod.me-south-1.amazonaws.com,emrstudio-prod.me-south-1.amazonaws.com,emrappui-prod.sa-east-1.amazonaws.com,emrnotebooks-prod.sa-east-1.amazonaws.com,emrstudio-prod.sa-east-1.amazonaws.com,emrappui-prod.us-east-1.amazonaws.com,emrnotebooks-prod.us-east-1.amazonaws.com,emrstudio-prod.us-east-1.amazonaws.com,emrappui-prod.us-east-2.amazonaws.com,emrnotebooks-prod.us-east-2.amazonaws.com,emrstudio-prod.us-east-2.amazonaws.com,emrappui-prod.us-gov-east-1.amazonaws.com,emrnotebooks-prod.us-gov-east-1.amazonaws.com,emrstudio-prod.us-gov-east-1.amazonaws.com,emrappui-prod.us-gov-west-1.amazonaws.com,emrnotebooks-prod.us-gov-west-1.amazonaws.com,emrstudio-prod.us-gov-west-1.amazonaws.com,emrappui-prod.us-west-1.amazonaws.com,emrnotebooks-prod.us-west-1.amazonaws.com,emrstudio-prod.us-west-1.amazonaws.com,emrappui-prod.us-west-2.amazonaws.com,emrnotebooks-prod.us-west-2.amazonaws.com,emrstudio-prod.us-west-2.amazonaws.com,amazonlightsail.com,*.ap-east-1.cs.amazonlightsail.com,*.ap-northeast-1.cs.amazonlightsail.com,*.ap-northeast-2.cs.amazonlightsail.com,*.ap-south-1.cs.amazonlightsail.com,*.ap-southeast-1.cs.amazonlightsail.com,*.ap-southeast-2.cs.amazonlightsail.com,*.ap-southeast-3.cs.amazonlightsail.com,*.ap-southeast-5.cs.amazonlightsail.com,*.ca-central-1.cs.amazonlightsail.com,*.eu-central-1.cs.amazonlightsail.com,*.eu-north-1.cs.amazonlightsail.com,*.eu-south-2.cs.amazonlightsail.com,*.eu-west-1.cs.amazonlightsail.com,*.eu-west-2.cs.amazonlightsail.com,*.eu-west-3.cs.amazonlightsail.com,*.sa-east-1.cs.amazonlightsail.com,*.us-east-1.cs.amazonlightsail.com,*.us-east-2.cs.amazonlightsail.com,*.us-west-2.cs.amazonlightsail.com,*.airflow.af-south-1.on.aws,*.airflow.ap-east-1.on.aws,*.airflow.ap-northeast-1.on.aws,*.airflow.ap-northeast-2.on.aws,*.airflow.ap-northeast-3.on.aws,*.airflow.ap-south-1.on.aws,*.airflow.ap-south-2.on.aws,*.airflow.ap-southeast-1.on.aws,*.airflow.ap-southeast-2.on.aws,*.airflow.ap-southeast-3.on.aws,*.airflow.ap-southeast-4.on.aws,*.airflow.ap-southeast-5.on.aws,*.airflow.ap-southeast-7.on.aws,*.airflow.ca-central-1.on.aws,*.airflow.ca-west-1.on.aws,*.airflow.eu-central-1.on.aws,*.airflow.eu-central-2.on.aws,*.airflow.eu-north-1.on.aws,*.airflow.eu-south-1.on.aws,*.airflow.eu-south-2.on.aws,*.airflow.eu-west-1.on.aws,*.airflow.eu-west-2.on.aws,*.airflow.eu-west-3.on.aws,*.airflow.il-central-1.on.aws,*.airflow.me-central-1.on.aws,*.airflow.me-south-1.on.aws,*.airflow.sa-east-1.on.aws,*.airflow.us-east-1.on.aws,*.airflow.us-east-2.on.aws,*.airflow.us-west-1.on.aws,*.airflow.us-west-2.on.aws,*.cn-north-1.airflow.amazonaws.com.cn,*.cn-northwest-1.airflow.amazonaws.com.cn,*.airflow.cn-north-1.on.amazonwebservices.com.cn,*.airflow.cn-northwest-1.on.amazonwebservices.com.cn,*.af-south-1.airflow.amazonaws.com,*.ap-east-1.airflow.amazonaws.com,*.ap-northeast-1.airflow.amazonaws.com,*.ap-northeast-2.airflow.amazonaws.com,*.ap-northeast-3.airflow.amazonaws.com,*.ap-south-1.airflow.amazonaws.com,*.ap-south-2.airflow.amazonaws.com,*.ap-southeast-1.airflow.amazonaws.com,*.ap-southeast-2.airflow.amazonaws.com,*.ap-southeast-3.airflow.amazonaws.com,*.ap-southeast-4.airflow.amazonaws.com,*.ap-southeast-5.airflow.amazonaws.com,*.ap-southeast-7.airflow.amazonaws.com,*.ca-central-1.airflow.amazonaws.com,*.ca-west-1.airflow.amazonaws.com,*.eu-central-1.airflow.amazonaws.com,*.eu-central-2.airflow.amazonaws.com,*.eu-north-1.airflow.amazonaws.com,*.eu-south-1.airflow.amazonaws.com,*.eu-south-2.airflow.amazonaws.com,*.eu-west-1.airflow.amazonaws.com,*.eu-west-2.airflow.amazonaws.com,*.eu-west-3.airflow.amazonaws.com,*.il-central-1.airflow.amazonaws.com,*.me-central-1.airflow.amazonaws.com,*.me-south-1.airflow.amazonaws.com,*.sa-east-1.airflow.amazonaws.com,*.us-east-1.airflow.amazonaws.com,*.us-east-2.airflow.amazonaws.com,*.us-west-1.airflow.amazonaws.com,*.us-west-2.airflow.amazonaws.com,*.rds.cn-north-1.amazonaws.com.cn,*.rds.cn-northwest-1.amazonaws.com.cn,*.af-south-1.rds.amazonaws.com,*.ap-east-1.rds.amazonaws.com,*.ap-east-2.rds.amazonaws.com,*.ap-northeast-1.rds.amazonaws.com,*.ap-northeast-2.rds.amazonaws.com,*.ap-northeast-3.rds.amazonaws.com,*.ap-south-1.rds.amazonaws.com,*.ap-south-2.rds.amazonaws.com,*.ap-southeast-1.rds.amazonaws.com,*.ap-southeast-2.rds.amazonaws.com,*.ap-southeast-3.rds.amazonaws.com,*.ap-southeast-4.rds.amazonaws.com,*.ap-southeast-5.rds.amazonaws.com,*.ap-southeast-6.rds.amazonaws.com,*.ap-southeast-7.rds.amazonaws.com,*.ca-central-1.rds.amazonaws.com,*.ca-west-1.rds.amazonaws.com,*.eu-central-1.rds.amazonaws.com,*.eu-central-2.rds.amazonaws.com,*.eu-west-1.rds.amazonaws.com,*.eu-west-2.rds.amazonaws.com,*.eu-west-3.rds.amazonaws.com,*.il-central-1.rds.amazonaws.com,*.me-central-1.rds.amazonaws.com,*.me-south-1.rds.amazonaws.com,*.mx-central-1.rds.amazonaws.com,*.sa-east-1.rds.amazonaws.com,*.us-east-1.rds.amazonaws.com,*.us-east-2.rds.amazonaws.com,*.us-gov-east-1.rds.amazonaws.com,*.us-gov-west-1.rds.amazonaws.com,*.us-northeast-1.rds.amazonaws.com,*.us-west-1.rds.amazonaws.com,*.us-west-2.rds.amazonaws.com,s3.dualstack.cn-north-1.amazonaws.com.cn,s3-accesspoint.dualstack.cn-north-1.amazonaws.com.cn,s3-website.dualstack.cn-north-1.amazonaws.com.cn,s3.cn-north-1.amazonaws.com.cn,s3-accesspoint.cn-north-1.amazonaws.com.cn,s3-deprecated.cn-north-1.amazonaws.com.cn,s3-object-lambda.cn-north-1.amazonaws.com.cn,s3-website.cn-north-1.amazonaws.com.cn,s3.dualstack.cn-northwest-1.amazonaws.com.cn,s3-accesspoint.dualstack.cn-northwest-1.amazonaws.com.cn,s3.cn-northwest-1.amazonaws.com.cn,s3-accesspoint.cn-northwest-1.amazonaws.com.cn,s3-object-lambda.cn-northwest-1.amazonaws.com.cn,s3-website.cn-northwest-1.amazonaws.com.cn,s3.dualstack.af-south-1.amazonaws.com,s3-accesspoint.dualstack.af-south-1.amazonaws.com,s3-website.dualstack.af-south-1.amazonaws.com,s3.af-south-1.amazonaws.com,s3-accesspoint.af-south-1.amazonaws.com,s3-object-lambda.af-south-1.amazonaws.com,s3-website.af-south-1.amazonaws.com,s3.dualstack.ap-east-1.amazonaws.com,s3-accesspoint.dualstack.ap-east-1.amazonaws.com,s3.ap-east-1.amazonaws.com,s3-accesspoint.ap-east-1.amazonaws.com,s3-object-lambda.ap-east-1.amazonaws.com,s3-website.ap-east-1.amazonaws.com,s3.dualstack.ap-northeast-1.amazonaws.com,s3-accesspoint.dualstack.ap-northeast-1.amazonaws.com,s3-website.dualstack.ap-northeast-1.amazonaws.com,s3.ap-northeast-1.amazonaws.com,s3-accesspoint.ap-northeast-1.amazonaws.com,s3-object-lambda.ap-northeast-1.amazonaws.com,s3-website.ap-northeast-1.amazonaws.com,s3.dualstack.ap-northeast-2.amazonaws.com,s3-accesspoint.dualstack.ap-northeast-2.amazonaws.com,s3-website.dualstack.ap-northeast-2.amazonaws.com,s3.ap-northeast-2.amazonaws.com,s3-accesspoint.ap-northeast-2.amazonaws.com,s3-object-lambda.ap-northeast-2.amazonaws.com,s3-website.ap-northeast-2.amazonaws.com,s3.dualstack.ap-northeast-3.amazonaws.com,s3-accesspoint.dualstack.ap-northeast-3.amazonaws.com,s3-website.dualstack.ap-northeast-3.amazonaws.com,s3.ap-northeast-3.amazonaws.com,s3-accesspoint.ap-northeast-3.amazonaws.com,s3-object-lambda.ap-northeast-3.amazonaws.com,s3-website.ap-northeast-3.amazonaws.com,s3.dualstack.ap-south-1.amazonaws.com,s3-accesspoint.dualstack.ap-south-1.amazonaws.com,s3-website.dualstack.ap-south-1.amazonaws.com,s3.ap-south-1.amazonaws.com,s3-accesspoint.ap-south-1.amazonaws.com,s3-object-lambda.ap-south-1.amazonaws.com,s3-website.ap-south-1.amazonaws.com,s3.dualstack.ap-south-2.amazonaws.com,s3-accesspoint.dualstack.ap-south-2.amazonaws.com,s3-website.dualstack.ap-south-2.amazonaws.com,s3.ap-south-2.amazonaws.com,s3-accesspoint.ap-south-2.amazonaws.com,s3-object-lambda.ap-south-2.amazonaws.com,s3-website.ap-south-2.amazonaws.com,s3.dualstack.ap-southeast-1.amazonaws.com,s3-accesspoint.dualstack.ap-southeast-1.amazonaws.com,s3-website.dualstack.ap-southeast-1.amazonaws.com,s3.ap-southeast-1.amazonaws.com,s3-accesspoint.ap-southeast-1.amazonaws.com,s3-object-lambda.ap-southeast-1.amazonaws.com,s3-website.ap-southeast-1.amazonaws.com,s3.dualstack.ap-southeast-2.amazonaws.com,s3-accesspoint.dualstack.ap-southeast-2.amazonaws.com,s3-website.dualstack.ap-southeast-2.amazonaws.com,s3.ap-southeast-2.amazonaws.com,s3-accesspoint.ap-southeast-2.amazonaws.com,s3-object-lambda.ap-southeast-2.amazonaws.com,s3-website.ap-southeast-2.amazonaws.com,s3.dualstack.ap-southeast-3.amazonaws.com,s3-accesspoint.dualstack.ap-southeast-3.amazonaws.com,s3-website.dualstack.ap-southeast-3.amazonaws.com,s3.ap-southeast-3.amazonaws.com,s3-accesspoint.ap-southeast-3.amazonaws.com,s3-object-lambda.ap-southeast-3.amazonaws.com,s3-website.ap-southeast-3.amazonaws.com,s3.dualstack.ap-southeast-4.amazonaws.com,s3-accesspoint.dualstack.ap-southeast-4.amazonaws.com,s3-website.dualstack.ap-southeast-4.amazonaws.com,s3.ap-southeast-4.amazonaws.com,s3-accesspoint.ap-southeast-4.amazonaws.com,s3-object-lambda.ap-southeast-4.amazonaws.com,s3-website.ap-southeast-4.amazonaws.com,s3.dualstack.ap-southeast-5.amazonaws.com,s3-accesspoint.dualstack.ap-southeast-5.amazonaws.com,s3-website.dualstack.ap-southeast-5.amazonaws.com,s3.ap-southeast-5.amazonaws.com,s3-accesspoint.ap-southeast-5.amazonaws.com,s3-deprecated.ap-southeast-5.amazonaws.com,s3-object-lambda.ap-southeast-5.amazonaws.com,s3-website.ap-southeast-5.amazonaws.com,s3.dualstack.ca-central-1.amazonaws.com,s3-accesspoint.dualstack.ca-central-1.amazonaws.com,s3-accesspoint-fips.dualstack.ca-central-1.amazonaws.com,s3-fips.dualstack.ca-central-1.amazonaws.com,s3-website.dualstack.ca-central-1.amazonaws.com,s3.ca-central-1.amazonaws.com,s3-accesspoint.ca-central-1.amazonaws.com,s3-accesspoint-fips.ca-central-1.amazonaws.com,s3-fips.ca-central-1.amazonaws.com,s3-object-lambda.ca-central-1.amazonaws.com,s3-website.ca-central-1.amazonaws.com,s3.dualstack.ca-west-1.amazonaws.com,s3-accesspoint.dualstack.ca-west-1.amazonaws.com,s3-accesspoint-fips.dualstack.ca-west-1.amazonaws.com,s3-fips.dualstack.ca-west-1.amazonaws.com,s3-website.dualstack.ca-west-1.amazonaws.com,s3.ca-west-1.amazonaws.com,s3-accesspoint.ca-west-1.amazonaws.com,s3-accesspoint-fips.ca-west-1.amazonaws.com,s3-fips.ca-west-1.amazonaws.com,s3-object-lambda.ca-west-1.amazonaws.com,s3-website.ca-west-1.amazonaws.com,s3.dualstack.eu-central-1.amazonaws.com,s3-accesspoint.dualstack.eu-central-1.amazonaws.com,s3-website.dualstack.eu-central-1.amazonaws.com,s3.eu-central-1.amazonaws.com,s3-accesspoint.eu-central-1.amazonaws.com,s3-object-lambda.eu-central-1.amazonaws.com,s3-website.eu-central-1.amazonaws.com,s3.dualstack.eu-central-2.amazonaws.com,s3-accesspoint.dualstack.eu-central-2.amazonaws.com,s3-website.dualstack.eu-central-2.amazonaws.com,s3.eu-central-2.amazonaws.com,s3-accesspoint.eu-central-2.amazonaws.com,s3-object-lambda.eu-central-2.amazonaws.com,s3-website.eu-central-2.amazonaws.com,s3.dualstack.eu-north-1.amazonaws.com,s3-accesspoint.dualstack.eu-north-1.amazonaws.com,s3.eu-north-1.amazonaws.com,s3-accesspoint.eu-north-1.amazonaws.com,s3-object-lambda.eu-north-1.amazonaws.com,s3-website.eu-north-1.amazonaws.com,s3.dualstack.eu-south-1.amazonaws.com,s3-accesspoint.dualstack.eu-south-1.amazonaws.com,s3-website.dualstack.eu-south-1.amazonaws.com,s3.eu-south-1.amazonaws.com,s3-accesspoint.eu-south-1.amazonaws.com,s3-object-lambda.eu-south-1.amazonaws.com,s3-website.eu-south-1.amazonaws.com,s3.dualstack.eu-south-2.amazonaws.com,s3-accesspoint.dualstack.eu-south-2.amazonaws.com,s3-website.dualstack.eu-south-2.amazonaws.com,s3.eu-south-2.amazonaws.com,s3-accesspoint.eu-south-2.amazonaws.com,s3-object-lambda.eu-south-2.amazonaws.com,s3-website.eu-south-2.amazonaws.com,s3.dualstack.eu-west-1.amazonaws.com,s3-accesspoint.dualstack.eu-west-1.amazonaws.com,s3-website.dualstack.eu-west-1.amazonaws.com,s3.eu-west-1.amazonaws.com,s3-accesspoint.eu-west-1.amazonaws.com,s3-deprecated.eu-west-1.amazonaws.com,s3-object-lambda.eu-west-1.amazonaws.com,s3-website.eu-west-1.amazonaws.com,s3.dualstack.eu-west-2.amazonaws.com,s3-accesspoint.dualstack.eu-west-2.amazonaws.com,s3.eu-west-2.amazonaws.com,s3-accesspoint.eu-west-2.amazonaws.com,s3-object-lambda.eu-west-2.amazonaws.com,s3-website.eu-west-2.amazonaws.com,s3.dualstack.eu-west-3.amazonaws.com,s3-accesspoint.dualstack.eu-west-3.amazonaws.com,s3-website.dualstack.eu-west-3.amazonaws.com,s3.eu-west-3.amazonaws.com,s3-accesspoint.eu-west-3.amazonaws.com,s3-object-lambda.eu-west-3.amazonaws.com,s3-website.eu-west-3.amazonaws.com,s3.dualstack.il-central-1.amazonaws.com,s3-accesspoint.dualstack.il-central-1.amazonaws.com,s3-website.dualstack.il-central-1.amazonaws.com,s3.il-central-1.amazonaws.com,s3-accesspoint.il-central-1.amazonaws.com,s3-object-lambda.il-central-1.amazonaws.com,s3-website.il-central-1.amazonaws.com,s3.dualstack.me-central-1.amazonaws.com,s3-accesspoint.dualstack.me-central-1.amazonaws.com,s3-website.dualstack.me-central-1.amazonaws.com,s3.me-central-1.amazonaws.com,s3-accesspoint.me-central-1.amazonaws.com,s3-object-lambda.me-central-1.amazonaws.com,s3-website.me-central-1.amazonaws.com,s3.dualstack.me-south-1.amazonaws.com,s3-accesspoint.dualstack.me-south-1.amazonaws.com,s3.me-south-1.amazonaws.com,s3-accesspoint.me-south-1.amazonaws.com,s3-object-lambda.me-south-1.amazonaws.com,s3-website.me-south-1.amazonaws.com,s3.amazonaws.com,s3-1.amazonaws.com,s3-ap-east-1.amazonaws.com,s3-ap-northeast-1.amazonaws.com,s3-ap-northeast-2.amazonaws.com,s3-ap-northeast-3.amazonaws.com,s3-ap-south-1.amazonaws.com,s3-ap-southeast-1.amazonaws.com,s3-ap-southeast-2.amazonaws.com,s3-ca-central-1.amazonaws.com,s3-eu-central-1.amazonaws.com,s3-eu-north-1.amazonaws.com,s3-eu-west-1.amazonaws.com,s3-eu-west-2.amazonaws.com,s3-eu-west-3.amazonaws.com,s3-external-1.amazonaws.com,s3-fips-us-gov-east-1.amazonaws.com,s3-fips-us-gov-west-1.amazonaws.com,mrap.accesspoint.s3-global.amazonaws.com,s3-me-south-1.amazonaws.com,s3-sa-east-1.amazonaws.com,s3-us-east-2.amazonaws.com,s3-us-gov-east-1.amazonaws.com,s3-us-gov-west-1.amazonaws.com,s3-us-west-1.amazonaws.com,s3-us-west-2.amazonaws.com,s3-website-ap-northeast-1.amazonaws.com,s3-website-ap-southeast-1.amazonaws.com,s3-website-ap-southeast-2.amazonaws.com,s3-website-eu-west-1.amazonaws.com,s3-website-sa-east-1.amazonaws.com,s3-website-us-east-1.amazonaws.com,s3-website-us-gov-west-1.amazonaws.com,s3-website-us-west-1.amazonaws.com,s3-website-us-west-2.amazonaws.com,s3.dualstack.sa-east-1.amazonaws.com,s3-accesspoint.dualstack.sa-east-1.amazonaws.com,s3-website.dualstack.sa-east-1.amazonaws.com,s3.sa-east-1.amazonaws.com,s3-accesspoint.sa-east-1.amazonaws.com,s3-object-lambda.sa-east-1.amazonaws.com,s3-website.sa-east-1.amazonaws.com,s3.dualstack.us-east-1.amazonaws.com,s3-accesspoint.dualstack.us-east-1.amazonaws.com,s3-accesspoint-fips.dualstack.us-east-1.amazonaws.com,s3-fips.dualstack.us-east-1.amazonaws.com,s3-website.dualstack.us-east-1.amazonaws.com,s3.us-east-1.amazonaws.com,s3-accesspoint.us-east-1.amazonaws.com,s3-accesspoint-fips.us-east-1.amazonaws.com,s3-deprecated.us-east-1.amazonaws.com,s3-fips.us-east-1.amazonaws.com,s3-object-lambda.us-east-1.amazonaws.com,s3-website.us-east-1.amazonaws.com,s3.dualstack.us-east-2.amazonaws.com,s3-accesspoint.dualstack.us-east-2.amazonaws.com,s3-accesspoint-fips.dualstack.us-east-2.amazonaws.com,s3-fips.dualstack.us-east-2.amazonaws.com,s3-website.dualstack.us-east-2.amazonaws.com,s3.us-east-2.amazonaws.com,s3-accesspoint.us-east-2.amazonaws.com,s3-accesspoint-fips.us-east-2.amazonaws.com,s3-deprecated.us-east-2.amazonaws.com,s3-fips.us-east-2.amazonaws.com,s3-object-lambda.us-east-2.amazonaws.com,s3-website.us-east-2.amazonaws.com,s3.dualstack.us-gov-east-1.amazonaws.com,s3-accesspoint.dualstack.us-gov-east-1.amazonaws.com,s3-accesspoint-fips.dualstack.us-gov-east-1.amazonaws.com,s3-fips.dualstack.us-gov-east-1.amazonaws.com,s3-website.dualstack.us-gov-east-1.amazonaws.com,s3.us-gov-east-1.amazonaws.com,s3-accesspoint.us-gov-east-1.amazonaws.com,s3-accesspoint-fips.us-gov-east-1.amazonaws.com,s3-fips.us-gov-east-1.amazonaws.com,s3-object-lambda.us-gov-east-1.amazonaws.com,s3-website.us-gov-east-1.amazonaws.com,s3.dualstack.us-gov-west-1.amazonaws.com,s3-accesspoint.dualstack.us-gov-west-1.amazonaws.com,s3-accesspoint-fips.dualstack.us-gov-west-1.amazonaws.com,s3-fips.dualstack.us-gov-west-1.amazonaws.com,s3-website.dualstack.us-gov-west-1.amazonaws.com,s3.us-gov-west-1.amazonaws.com,s3-accesspoint.us-gov-west-1.amazonaws.com,s3-accesspoint-fips.us-gov-west-1.amazonaws.com,s3-fips.us-gov-west-1.amazonaws.com,s3-object-lambda.us-gov-west-1.amazonaws.com,s3-website.us-gov-west-1.amazonaws.com,s3.dualstack.us-west-1.amazonaws.com,s3-accesspoint.dualstack.us-west-1.amazonaws.com,s3-accesspoint-fips.dualstack.us-west-1.amazonaws.com,s3-fips.dualstack.us-west-1.amazonaws.com,s3-website.dualstack.us-west-1.amazonaws.com,s3.us-west-1.amazonaws.com,s3-accesspoint.us-west-1.amazonaws.com,s3-accesspoint-fips.us-west-1.amazonaws.com,s3-fips.us-west-1.amazonaws.com,s3-object-lambda.us-west-1.amazonaws.com,s3-website.us-west-1.amazonaws.com,s3.dualstack.us-west-2.amazonaws.com,s3-accesspoint.dualstack.us-west-2.amazonaws.com,s3-accesspoint-fips.dualstack.us-west-2.amazonaws.com,s3-fips.dualstack.us-west-2.amazonaws.com,s3-website.dualstack.us-west-2.amazonaws.com,s3.us-west-2.amazonaws.com,s3-accesspoint.us-west-2.amazonaws.com,s3-accesspoint-fips.us-west-2.amazonaws.com,s3-deprecated.us-west-2.amazonaws.com,s3-fips.us-west-2.amazonaws.com,s3-object-lambda.us-west-2.amazonaws.com,s3-website.us-west-2.amazonaws.com,labeling.ap-northeast-1.sagemaker.aws,labeling.ap-northeast-2.sagemaker.aws,labeling.ap-south-1.sagemaker.aws,labeling.ap-southeast-1.sagemaker.aws,labeling.ap-southeast-2.sagemaker.aws,labeling.ca-central-1.sagemaker.aws,labeling.eu-central-1.sagemaker.aws,labeling.eu-west-1.sagemaker.aws,labeling.eu-west-2.sagemaker.aws,labeling.us-east-1.sagemaker.aws,labeling.us-east-2.sagemaker.aws,labeling.us-west-2.sagemaker.aws,notebook.af-south-1.sagemaker.aws,notebook.ap-east-1.sagemaker.aws,notebook.ap-northeast-1.sagemaker.aws,notebook.ap-northeast-2.sagemaker.aws,notebook.ap-northeast-3.sagemaker.aws,notebook.ap-south-1.sagemaker.aws,notebook.ap-south-2.sagemaker.aws,notebook.ap-southeast-1.sagemaker.aws,notebook.ap-southeast-2.sagemaker.aws,notebook.ap-southeast-3.sagemaker.aws,notebook.ap-southeast-4.sagemaker.aws,notebook.ca-central-1.sagemaker.aws,notebook-fips.ca-central-1.sagemaker.aws,notebook.ca-west-1.sagemaker.aws,notebook-fips.ca-west-1.sagemaker.aws,notebook.eu-central-1.sagemaker.aws,notebook.eu-central-2.sagemaker.aws,notebook.eu-north-1.sagemaker.aws,notebook.eu-south-1.sagemaker.aws,notebook.eu-south-2.sagemaker.aws,notebook.eu-west-1.sagemaker.aws,notebook.eu-west-2.sagemaker.aws,notebook.eu-west-3.sagemaker.aws,notebook.il-central-1.sagemaker.aws,notebook.me-central-1.sagemaker.aws,notebook.me-south-1.sagemaker.aws,notebook.sa-east-1.sagemaker.aws,notebook.us-east-1.sagemaker.aws,notebook-fips.us-east-1.sagemaker.aws,notebook.us-east-2.sagemaker.aws,notebook-fips.us-east-2.sagemaker.aws,notebook.us-gov-east-1.sagemaker.aws,notebook-fips.us-gov-east-1.sagemaker.aws,notebook.us-gov-west-1.sagemaker.aws,notebook-fips.us-gov-west-1.sagemaker.aws,notebook.us-west-1.sagemaker.aws,notebook-fips.us-west-1.sagemaker.aws,notebook.us-west-2.sagemaker.aws,notebook-fips.us-west-2.sagemaker.aws,notebook.cn-north-1.sagemaker.com.cn,notebook.cn-northwest-1.sagemaker.com.cn,studio.af-south-1.sagemaker.aws,studio.ap-east-1.sagemaker.aws,studio.ap-northeast-1.sagemaker.aws,studio.ap-northeast-2.sagemaker.aws,studio.ap-northeast-3.sagemaker.aws,studio.ap-south-1.sagemaker.aws,studio.ap-southeast-1.sagemaker.aws,studio.ap-southeast-2.sagemaker.aws,studio.ap-southeast-3.sagemaker.aws,studio.ca-central-1.sagemaker.aws,studio.eu-central-1.sagemaker.aws,studio.eu-central-2.sagemaker.aws,studio.eu-north-1.sagemaker.aws,studio.eu-south-1.sagemaker.aws,studio.eu-south-2.sagemaker.aws,studio.eu-west-1.sagemaker.aws,studio.eu-west-2.sagemaker.aws,studio.eu-west-3.sagemaker.aws,studio.il-central-1.sagemaker.aws,studio.me-central-1.sagemaker.aws,studio.me-south-1.sagemaker.aws,studio.sa-east-1.sagemaker.aws,studio.us-east-1.sagemaker.aws,studio.us-east-2.sagemaker.aws,studio.us-gov-east-1.sagemaker.aws,studio-fips.us-gov-east-1.sagemaker.aws,studio.us-gov-west-1.sagemaker.aws,studio-fips.us-gov-west-1.sagemaker.aws,studio.us-west-1.sagemaker.aws,studio.us-west-2.sagemaker.aws,studio.cn-north-1.sagemaker.com.cn,studio.cn-northwest-1.sagemaker.com.cn,*.experiments.sagemaker.aws,analytics-gateway.ap-northeast-1.amazonaws.com,analytics-gateway.ap-northeast-2.amazonaws.com,analytics-gateway.ap-south-1.amazonaws.com,analytics-gateway.ap-southeast-1.amazonaws.com,analytics-gateway.ap-southeast-2.amazonaws.com,analytics-gateway.eu-central-1.amazonaws.com,analytics-gateway.eu-west-1.amazonaws.com,analytics-gateway.us-east-1.amazonaws.com,analytics-gateway.us-east-2.amazonaws.com,analytics-gateway.us-west-2.amazonaws.com,amplifyapp.com,*.awsapprunner.com,webview-assets.aws-cloud9.af-south-1.amazonaws.com,vfs.cloud9.af-south-1.amazonaws.com,webview-assets.cloud9.af-south-1.amazonaws.com,webview-assets.aws-cloud9.ap-east-1.amazonaws.com,vfs.cloud9.ap-east-1.amazonaws.com,webview-assets.cloud9.ap-east-1.amazonaws.com,webview-assets.aws-cloud9.ap-northeast-1.amazonaws.com,vfs.cloud9.ap-northeast-1.amazonaws.com,webview-assets.cloud9.ap-northeast-1.amazonaws.com,webview-assets.aws-cloud9.ap-northeast-2.amazonaws.com,vfs.cloud9.ap-northeast-2.amazonaws.com,webview-assets.cloud9.ap-northeast-2.amazonaws.com,webview-assets.aws-cloud9.ap-northeast-3.amazonaws.com,vfs.cloud9.ap-northeast-3.amazonaws.com,webview-assets.cloud9.ap-northeast-3.amazonaws.com,webview-assets.aws-cloud9.ap-south-1.amazonaws.com,vfs.cloud9.ap-south-1.amazonaws.com,webview-assets.cloud9.ap-south-1.amazonaws.com,webview-assets.aws-cloud9.ap-southeast-1.amazonaws.com,vfs.cloud9.ap-southeast-1.amazonaws.com,webview-assets.cloud9.ap-southeast-1.amazonaws.com,webview-assets.aws-cloud9.ap-southeast-2.amazonaws.com,vfs.cloud9.ap-southeast-2.amazonaws.com,webview-assets.cloud9.ap-southeast-2.amazonaws.com,webview-assets.aws-cloud9.ca-central-1.amazonaws.com,vfs.cloud9.ca-central-1.amazonaws.com,webview-assets.cloud9.ca-central-1.amazonaws.com,webview-assets.aws-cloud9.eu-central-1.amazonaws.com,vfs.cloud9.eu-central-1.amazonaws.com,webview-assets.cloud9.eu-central-1.amazonaws.com,webview-assets.aws-cloud9.eu-north-1.amazonaws.com,vfs.cloud9.eu-north-1.amazonaws.com,webview-assets.cloud9.eu-north-1.amazonaws.com,webview-assets.aws-cloud9.eu-south-1.amazonaws.com,vfs.cloud9.eu-south-1.amazonaws.com,webview-assets.cloud9.eu-south-1.amazonaws.com,webview-assets.aws-cloud9.eu-west-1.amazonaws.com,vfs.cloud9.eu-west-1.amazonaws.com,webview-assets.cloud9.eu-west-1.amazonaws.com,webview-assets.aws-cloud9.eu-west-2.amazonaws.com,vfs.cloud9.eu-west-2.amazonaws.com,webview-assets.cloud9.eu-west-2.amazonaws.com,webview-assets.aws-cloud9.eu-west-3.amazonaws.com,vfs.cloud9.eu-west-3.amazonaws.com,webview-assets.cloud9.eu-west-3.amazonaws.com,webview-assets.aws-cloud9.il-central-1.amazonaws.com,vfs.cloud9.il-central-1.amazonaws.com,webview-assets.aws-cloud9.me-south-1.amazonaws.com,vfs.cloud9.me-south-1.amazonaws.com,webview-assets.cloud9.me-south-1.amazonaws.com,webview-assets.aws-cloud9.sa-east-1.amazonaws.com,vfs.cloud9.sa-east-1.amazonaws.com,webview-assets.cloud9.sa-east-1.amazonaws.com,webview-assets.aws-cloud9.us-east-1.amazonaws.com,vfs.cloud9.us-east-1.amazonaws.com,webview-assets.cloud9.us-east-1.amazonaws.com,webview-assets.aws-cloud9.us-east-2.amazonaws.com,vfs.cloud9.us-east-2.amazonaws.com,webview-assets.cloud9.us-east-2.amazonaws.com,webview-assets.aws-cloud9.us-west-1.amazonaws.com,vfs.cloud9.us-west-1.amazonaws.com,webview-assets.cloud9.us-west-1.amazonaws.com,webview-assets.aws-cloud9.us-west-2.amazonaws.com,vfs.cloud9.us-west-2.amazonaws.com,webview-assets.cloud9.us-west-2.amazonaws.com,awsapps.com,cn-north-1.eb.amazonaws.com.cn,cn-northwest-1.eb.amazonaws.com.cn,elasticbeanstalk.com,af-south-1.elasticbeanstalk.com,ap-east-1.elasticbeanstalk.com,ap-northeast-1.elasticbeanstalk.com,ap-northeast-2.elasticbeanstalk.com,ap-northeast-3.elasticbeanstalk.com,ap-south-1.elasticbeanstalk.com,ap-south-2.elasticbeanstalk.com,ap-southeast-1.elasticbeanstalk.com,ap-southeast-2.elasticbeanstalk.com,ap-southeast-3.elasticbeanstalk.com,ap-southeast-4.elasticbeanstalk.com,ap-southeast-5.elasticbeanstalk.com,ap-southeast-6.elasticbeanstalk.com,ap-southeast-7.elasticbeanstalk.com,ca-central-1.elasticbeanstalk.com,ca-west-1.elasticbeanstalk.com,eu-central-1.elasticbeanstalk.com,eu-central-2.elasticbeanstalk.com,eu-north-1.elasticbeanstalk.com,eu-south-1.elasticbeanstalk.com,eu-south-2.elasticbeanstalk.com,eu-west-1.elasticbeanstalk.com,eu-west-2.elasticbeanstalk.com,eu-west-3.elasticbeanstalk.com,il-central-1.elasticbeanstalk.com,me-central-1.elasticbeanstalk.com,me-south-1.elasticbeanstalk.com,sa-east-1.elasticbeanstalk.com,us-east-1.elasticbeanstalk.com,us-east-2.elasticbeanstalk.com,us-gov-east-1.elasticbeanstalk.com,us-gov-west-1.elasticbeanstalk.com,us-west-1.elasticbeanstalk.com,us-west-2.elasticbeanstalk.com,*.elb.amazonaws.com.cn,*.elb.amazonaws.com,awsglobalaccelerator.com,lambda-url.af-south-1.on.aws,lambda-url.ap-east-1.on.aws,lambda-url.ap-northeast-1.on.aws,lambda-url.ap-northeast-2.on.aws,lambda-url.ap-northeast-3.on.aws,lambda-url.ap-south-1.on.aws,lambda-url.ap-southeast-1.on.aws,lambda-url.ap-southeast-2.on.aws,lambda-url.ap-southeast-3.on.aws,lambda-url.ca-central-1.on.aws,lambda-url.eu-central-1.on.aws,lambda-url.eu-north-1.on.aws,lambda-url.eu-south-1.on.aws,lambda-url.eu-west-1.on.aws,lambda-url.eu-west-2.on.aws,lambda-url.eu-west-3.on.aws,lambda-url.me-south-1.on.aws,lambda-url.sa-east-1.on.aws,lambda-url.us-east-1.on.aws,lambda-url.us-east-2.on.aws,lambda-url.us-west-1.on.aws,lambda-url.us-west-2.on.aws,*.private.repost.aws,transfer-webapp.af-south-1.on.aws,transfer-webapp.ap-east-1.on.aws,transfer-webapp.ap-east-2.on.aws,transfer-webapp.ap-northeast-1.on.aws,transfer-webapp.ap-northeast-2.on.aws,transfer-webapp.ap-northeast-3.on.aws,transfer-webapp.ap-south-1.on.aws,transfer-webapp.ap-south-2.on.aws,transfer-webapp.ap-southeast-1.on.aws,transfer-webapp.ap-southeast-2.on.aws,transfer-webapp.ap-southeast-3.on.aws,transfer-webapp.ap-southeast-4.on.aws,transfer-webapp.ap-southeast-5.on.aws,transfer-webapp.ap-southeast-6.on.aws,transfer-webapp.ap-southeast-7.on.aws,transfer-webapp.ca-central-1.on.aws,transfer-webapp.ca-west-1.on.aws,transfer-webapp.eu-central-1.on.aws,transfer-webapp.eu-central-2.on.aws,transfer-webapp.eu-north-1.on.aws,transfer-webapp.eu-south-1.on.aws,transfer-webapp.eu-south-2.on.aws,transfer-webapp.eu-west-1.on.aws,transfer-webapp.eu-west-2.on.aws,transfer-webapp.eu-west-3.on.aws,transfer-webapp.il-central-1.on.aws,transfer-webapp.me-central-1.on.aws,transfer-webapp.me-south-1.on.aws,transfer-webapp.mx-central-1.on.aws,transfer-webapp.sa-east-1.on.aws,transfer-webapp.us-east-1.on.aws,transfer-webapp.us-east-2.on.aws,transfer-webapp.us-gov-east-1.on.aws,transfer-webapp-fips.us-gov-east-1.on.aws,transfer-webapp.us-gov-west-1.on.aws,transfer-webapp-fips.us-gov-west-1.on.aws,transfer-webapp.us-west-1.on.aws,transfer-webapp.us-west-2.on.aws,transfer-webapp.cn-north-1.on.amazonwebservices.com.cn,transfer-webapp.cn-northwest-1.on.amazonwebservices.com.cn,transfer-webapp.eusc-de-east-1.on.amazonwebservices.eu,eero.online,eero-stage.online,opentunnel.xyz,antagonist.cloud,claude.app,claudeusercontent.com,frame.claudeusercontent.com,*.cursorusercontent.com,apigee.io,panel.dev,siiites.com,int.apple,*.cloud.int.apple,*.r.cloud.int.apple,*.ap-north-1.r.cloud.int.apple,*.ap-south-1.r.cloud.int.apple,*.ap-south-2.r.cloud.int.apple,*.eu-central-1.r.cloud.int.apple,*.eu-north-1.r.cloud.int.apple,*.us-central-1.r.cloud.int.apple,*.us-central-2.r.cloud.int.apple,*.us-east-1.r.cloud.int.apple,*.us-east-2.r.cloud.int.apple,*.us-west-1.r.cloud.int.apple,*.us-west-2.r.cloud.int.apple,*.us-west-3.r.cloud.int.apple,appspacehosted.com,appspaceusercontent.com,appudo.net,appwrite.global,appwrite.network,*.appwrite.run,on-aptible.com,f5.si,arvanedge.ir,user.aseinet.ne.jp,gv.vc,d.gv.vc,user.party.eus,pimienta.org,poivron.org,potager.org,sweetpepper.org,myasustor.com,*.atlassian-3p.com,*.atlassian-3p-us-gov-mod.com,*.atlassian-isolated-3p.com,cdn.prod.atlassian-dev.net,myfritz.link,myfritz.net,*.awdev.ca,*.advisor.ws,ecommerce-shop.pl,b-data.io,balena-devices.com,base.ec,official.ec,buyshop.jp,fashionstore.jp,handcrafted.jp,kawaiishop.jp,supersale.jp,theshop.jp,shopselect.net,base.shop,beagleboard.io,bearblog.dev,*.beget.app,*.begetcdn.cloud,pages.gay,bnr.la,bitbucket.io,blackbaudcdn.net,of.je,square.site,bluebite.io,boomla.net,boutir.com,boxfuse.io,square7.ch,bplaced.com,bplaced.de,square7.de,bplaced.net,square7.net,brave.app,*.s.brave.app,brave.dev,*.s.brave.dev,brave.io,*.s.brave.io,shop.brendly.ba,shop.brendly.hr,shop.brendly.rs,browsersafetymark.io,radio.am,radio.fm,cdn.bubble.io,bubbleapps.io,*.bwcloud-os-instance.de,cafjs.com,canva-apps.cn,canva-code.cn,my.canvasite.cn,khsj.cn,canva-apps.com,canva-hosted-embed.com,canvacode.com,rice-labs.com,canva.link,canva.run,my.canva.site,drr.ac,uwu.ai,carrd.co,crd.co,ju.mp,api.gov.uk,cdn77-storage.com,rsc.contentproxy9.cz,r.cdn77.net,cdn77-ssl.net,c.cdn77.org,rsc.cdn77.org,ssl.origin.cdn77-secure.org,za.bz,br.com,cn.com,de.com,eu.com,jpn.com,mex.com,ru.com,sa.com,uk.com,us.com,za.com,com.de,gb.net,hu.net,jp.net,se.net,uk.net,ae.org,com.se,cx.ua,discourse.diy,discourse.group,discourse.team,clerk.app,clerkstage.app,*.lcl.dev,*.lclstage.dev,*.stg.dev,*.stgstage.dev,cleverapps.cc,*.services.clever-cloud.com,cleverapps.io,cleverapps.tech,clickrising.net,cloudns.asia,cloudns.be,cloud-ip.biz,cloudns.biz,cloud-ip.cc,cloudns.cc,cloudns.ch,cloudns.cl,cloudns.club,abrdns.com,dnsabr.com,ip-ddns.com,cloudns.cx,cloudns.eu,cloudns.in,cloudns.info,ddns-ip.net,dns-cloud.net,dns-dynamic.net,cloudns.nz,cloudns.org,ip-dynamic.org,cloudns.ph,cloudns.pro,cloudns.pw,cloudns.us,c66.me,cloud66.ws,jdevcloud.com,wpdevcloud.com,cloudaccess.host,freesite.host,cloudaccess.net,cloudbeesusercontent.io,*.cloudera.site,cloudflare.app,cf-ipfs.com,cloudflare-ipfs.com,trycloudflare.com,pages.dev,r2.dev,workers.dev,cloudflare.net,cdn.cloudflare.net,cdn.cloudflareanycast.net,cdn.cloudflarecn.net,cdn.cloudflareglobal.net,cust.cloudscale.ch,objects.lpg.cloudscale.ch,objects.rma.cloudscale.ch,lpg.objectstorage.ch,rma.objectstorage.ch,wnext.app,cnpy.gdn,*.otap.co,co.ca,co.com,sch.ac,dev.cv,store.cv,codeberg.page,codepen.app,codepen.dev,csb.app,preview.csb.app,co.nl,co.no,*.devinapps.com,webhosting.be,site.webhosting.be,prvw.eu,hosting-cluster.nl,site.hosting-cluster.nl,ctfcloud.net,convex.app,convex.cloud,eu-west-1.convex.cloud,us-east-1.convex.cloud,convex.site,eu-west-1.convex.site,us-east-1.convex.site,ac.ru,edu.ru,gov.ru,int.ru,mil.ru,corespeed.app,dyn.cosidns.de,dnsupdater.de,dynamisches-dns.de,internet-dns.de,l-o-g-i-n.de,dynamic-dns.info,feste-ip.net,knx-server.net,static-access.net,craft.me,realm.cz,cfolks.pl,cyon.link,cyon.site,biz.dk,co.dk,firm.dk,reg.dk,store.dk,dyndns.dappnode.io,builtwithdark.com,darklang.io,aws.databricksapps.com,*.azure.databricksapps.com,gcp.databricksapps.com,aws-gov.databricksapps.us,demo.datadetect.com,instance.datadetect.com,edgestack.me,dattolocal.com,dattorelay.com,dattoweb.com,mydatto.com,dattolocal.net,mydatto.net,ddnss.de,dyn.ddnss.de,dyndns.ddnss.de,dyn-ip24.de,dyndns1.de,home-webserver.de,dyn.home-webserver.de,myhome-server.de,ddnss.org,debian.net,definima.io,definima.net,deno.dev,deno-staging.dev,deno.net,sandbox.deno.net,deployagent.com,piebox.site,deployagent.space,dedyn.io,deuxfleurs.eu,deuxfleurs.page,*.at.ply.gg,d6.ply.gg,joinmc.link,playit.plus,*.at.playit.plus,with.playit.plus,icp0.io,*.raw.icp0.io,icp1.io,*.raw.icp1.io,opencloud.me,*.icp.net,caffeine.site,caffeine.xyz,mybox.company,intouch.email,mybox.me,mybox.page,dfirma.pl,dkonto.pl,you2.pl,ondigitalocean.app,*.digitaloceanspaces.com,qzz.io,us.kg,xx.kg,dpdns.org,discordsays.com,discordsez.com,jozi.biz,ccwu.cc,cc.cd,us.ci,de5.net,dnshome.at,resolve.bar,ddns.berlin,dnshome.cloud,ddnssec.de,dnshome.de,dyndnssec.de,heimdns.de,srvdns.de,dnshome.eu,dnshome.it,dyn.now,heimdns.online,ddns.wtf,online.th,shop.th,co.scot,me.scot,org.scot,drayddns.com,shoparena.pl,dreamhosters.com,durumis.com,duckdns.org,dy.fi,tunk.org,dyndns.biz,for-better.biz,for-more.biz,for-some.biz,for-the.biz,selfip.biz,webhop.biz,ftpaccess.cc,game-server.cc,myphotos.cc,scrapping.cc,blogdns.com,cechire.com,dnsalias.com,dnsdojo.com,doesntexist.com,dontexist.com,doomdns.com,dyn-o-saur.com,dynalias.com,dyndns-at-home.com,dyndns-at-work.com,dyndns-blog.com,dyndns-free.com,dyndns-home.com,dyndns-ip.com,dyndns-mail.com,dyndns-office.com,dyndns-pics.com,dyndns-remote.com,dyndns-server.com,dyndns-web.com,dyndns-wiki.com,dyndns-work.com,est-a-la-maison.com,est-a-la-masion.com,est-le-patron.com,est-mon-blogueur.com,from-ak.com,from-al.com,from-ar.com,from-ca.com,from-ct.com,from-dc.com,from-de.com,from-fl.com,from-ga.com,from-hi.com,from-ia.com,from-id.com,from-il.com,from-in.com,from-ks.com,from-ky.com,from-ma.com,from-md.com,from-mi.com,from-mn.com,from-mo.com,from-ms.com,from-mt.com,from-nc.com,from-nd.com,from-ne.com,from-nh.com,from-nj.com,from-nm.com,from-nv.com,from-oh.com,from-ok.com,from-or.com,from-pa.com,from-pr.com,from-ri.com,from-sc.com,from-sd.com,from-tn.com,from-tx.com,from-ut.com,from-va.com,from-vt.com,from-wa.com,from-wi.com,from-wv.com,from-wy.com,getmyip.com,gotdns.com,hobby-site.com,homelinux.com,homeunix.com,iamallama.com,is-a-anarchist.com,is-a-blogger.com,is-a-bookkeeper.com,is-a-bulls-fan.com,is-a-caterer.com,is-a-chef.com,is-a-conservative.com,is-a-cpa.com,is-a-cubicle-slave.com,is-a-democrat.com,is-a-designer.com,is-a-doctor.com,is-a-financialadvisor.com,is-a-geek.com,is-a-green.com,is-a-guru.com,is-a-hard-worker.com,is-a-hunter.com,is-a-landscaper.com,is-a-lawyer.com,is-a-liberal.com,is-a-libertarian.com,is-a-llama.com,is-a-musician.com,is-a-nascarfan.com,is-a-nurse.com,is-a-painter.com,is-a-personaltrainer.com,is-a-photographer.com,is-a-player.com,is-a-republican.com,is-a-rockstar.com,is-a-socialist.com,is-a-student.com,is-a-teacher.com,is-a-techie.com,is-a-therapist.com,is-an-accountant.com,is-an-actor.com,is-an-actress.com,is-an-anarchist.com,is-an-artist.com,is-an-engineer.com,is-an-entertainer.com,is-certified.com,is-gone.com,is-into-anime.com,is-into-cars.com,is-into-cartoons.com,is-into-games.com,is-leet.com,is-not-certified.com,is-slick.com,is-uberleet.com,is-with-theband.com,isa-geek.com,isa-hockeynut.com,issmarterthanyou.com,likes-pie.com,likescandy.com,neat-url.com,saves-the-whales.com,selfip.com,sells-for-less.com,sells-for-u.com,servebbs.com,simple-url.com,space-to-rent.com,teaches-yoga.com,writesthisblog.com,ath.cx,fuettertdasnetz.de,isteingeek.de,istmein.de,lebtimnetz.de,leitungsen.de,traeumtgerade.de,barrel-of-knowledge.info,barrell-of-knowledge.info,dyndns.info,for-our.info,groks-the.info,groks-this.info,here-for-more.info,knowsitall.info,selfip.info,webhop.info,forgot.her.name,forgot.his.name,at-band-camp.net,blogdns.net,broke-it.net,buyshouses.net,dnsalias.net,dnsdojo.net,does-it.net,dontexist.net,dynalias.net,dynathome.net,endofinternet.net,from-az.net,from-co.net,from-la.net,from-ny.net,gets-it.net,ham-radio-op.net,homeftp.net,homeip.net,homelinux.net,homeunix.net,in-the-band.net,is-a-chef.net,is-a-geek.net,isa-geek.net,kicks-ass.net,office-on-the.net,podzone.net,scrapper-site.net,selfip.net,sells-it.net,servebbs.net,serveftp.net,thruhere.net,webhop.net,merseine.nu,mine.nu,shacknet.nu,blogdns.org,blogsite.org,boldlygoingnowhere.org,dnsalias.org,dnsdojo.org,doesntexist.org,dontexist.org,doomdns.org,dvrdns.org,dynalias.org,dyndns.org,go.dyndns.org,home.dyndns.org,endofinternet.org,endoftheinternet.org,from-me.org,game-host.org,gotdns.org,hobby-site.org,homedns.org,homeftp.org,homelinux.org,homeunix.org,is-a-bruinsfan.org,is-a-candidate.org,is-a-celticsfan.org,is-a-chef.org,is-a-geek.org,is-a-knight.org,is-a-linux-user.org,is-a-patsfan.org,is-a-soxfan.org,is-found.org,is-lost.org,is-saved.org,is-very-bad.org,is-very-evil.org,is-very-good.org,is-very-nice.org,is-very-sweet.org,isa-geek.org,kicks-ass.org,misconfused.org,podzone.org,readmyblog.org,selfip.org,sellsyourhome.org,servebbs.org,serveftp.org,servegame.org,stuff-4-sale.org,webhop.org,better-than.tv,dyndns.tv,on-the-web.tv,worse-than.tv,is-by.us,land-4-sale.us,stuff-4-sale.us,dyndns.ws,mypets.ws,1cooldns.com,bumbleshrimp.com,ddnsfree.com,ddnsgeek.com,ddnsguru.com,dynuddns.com,dynuhosting.com,giize.com,gleeze.com,kozow.com,loseyourip.com,ooguy.com,pivohosting.com,theworkpc.com,wiredbladehosting.com,casacam.net,dynu.net,dynuddns.net,mysynology.net,opik.net,spryt.net,accesscam.org,camdvr.org,freeddns.org,mywire.org,roxa.org,webredirect.org,myddns.rocks,dynv6.net,e4.cz,easypanel.app,easypanel.host,*.ewp.live,twmail.cc,twmail.net,twmail.org,mymailer.com.tw,url.tw,at.emf.camp,rt.ht,elementor.cloud,elementor.cool,emergent.cloud,preview.emergentagent.com,emergent.host,mytuleap.com,tuleap-partners.com,encr.app,frontend.encr.app,encoreapi.com,lp.dev,api.lp.dev,objects.lp.dev,eu.encoway.cloud,eu.org,al.eu.org,asso.eu.org,at.eu.org,au.eu.org,be.eu.org,bg.eu.org,ca.eu.org,cd.eu.org,ch.eu.org,cn.eu.org,cy.eu.org,cz.eu.org,de.eu.org,dk.eu.org,edu.eu.org,ee.eu.org,es.eu.org,fi.eu.org,fr.eu.org,gr.eu.org,hr.eu.org,hu.eu.org,ie.eu.org,il.eu.org,in.eu.org,int.eu.org,is.eu.org,it.eu.org,jp.eu.org,kr.eu.org,lt.eu.org,lu.eu.org,lv.eu.org,me.eu.org,mk.eu.org,mt.eu.org,my.eu.org,net.eu.org,ng.eu.org,nl.eu.org,no.eu.org,nz.eu.org,pl.eu.org,pt.eu.org,ro.eu.org,ru.eu.org,se.eu.org,si.eu.org,sk.eu.org,tr.eu.org,uk.eu.org,us.eu.org,eurodir.ru,eu-1.evennode.com,eu-2.evennode.com,eu-3.evennode.com,eu-4.evennode.com,us-1.evennode.com,us-2.evennode.com,us-3.evennode.com,us-4.evennode.com,relay.evervault.app,relay.evervault.dev,exe.xyz,expo.app,on.expo.app,staging.expo.app,on.staging.expo.app,fspages.org,ru.net,adygeya.ru,bashkiria.ru,bir.ru,cbg.ru,com.ru,dagestan.ru,grozny.ru,kalmykia.ru,kustanai.ru,marine.ru,mordovia.ru,msk.ru,mytis.ru,nalchik.ru,nov.ru,pyatigorsk.ru,spb.ru,vladikavkaz.ru,vladimir.ru,abkhazia.su,adygeya.su,aktyubinsk.su,arkhangelsk.su,armenia.su,ashgabad.su,azerbaijan.su,balashov.su,bashkiria.su,bryansk.su,bukhara.su,chimkent.su,dagestan.su,east-kazakhstan.su,exnet.su,georgia.su,grozny.su,ivanovo.su,jambyl.su,kalmykia.su,kaluga.su,karacol.su,karaganda.su,karelia.su,khakassia.su,krasnodar.su,kurgan.su,kustanai.su,lenug.su,mangyshlak.su,mordovia.su,msk.su,murmansk.su,nalchik.su,navoi.su,north-kazakhstan.su,nov.su,obninsk.su,penza.su,pokrovsk.su,sochi.su,spb.su,tashkent.su,termez.su,togliatti.su,troitsk.su,tselinograd.su,tula.su,tuva.su,vladikavkaz.su,vladimir.su,vologda.su,channelsdvr.net,u.channelsdvr.net,edgecompute.app,fastly-edge.com,fastly-terrarium.com,freetls.fastly.net,map.fastly.net,a.prod.fastly.net,global.prod.fastly.net,a.ssl.fastly.net,b.ssl.fastly.net,global.ssl.fastly.net,fastlylb.net,map.fastlylb.net,*.user.fm,fastvps-server.com,fastvps.host,myfast.host,fastvps.site,myfast.space,conn.uk,copro.uk,hosp.uk,fedorainfracloud.org,fedorapeople.org,cloud.fedoraproject.org,app.os.fedoraproject.org,app.os.stg.fedoraproject.org,mydobiss.com,fh-muenster.io,payload.dev,figma.site,figma-gov.site,preview.site,filegear.me,hosted-by-files.com,firebaseapp.com,fldrv.com,on-fleek.app,flutterflow.app,sprites.app,fly.dev,e2b.app,framer.ai,framer.app,framercanvas.com,framer.media,framer.photos,framer.website,framer.wiki,*.0e.vc,freebox-os.com,freeboxos.com,fbx-os.fr,fbxos.fr,freebox-os.fr,freeboxos.fr,freedesktop.org,freemyip.com,*.frusky.de,wien.funkfeuer.at,daemon.asia,dix.asia,mydns.bz,0am.jp,0g0.jp,0j0.jp,0t0.jp,mydns.jp,pgw.jp,wjg.jp,keyword-on.net,live-on.net,server-on.net,mydns.tw,mydns.vc,*.futurecms.at,*.ex.futurecms.at,*.in.futurecms.at,futurehosting.at,futuremailing.at,*.ex.ortsinfo.at,*.kunden.ortsinfo.at,*.statics.cloud,gadget.app,gadget.host,campaign.gov.uk,service.gov.uk,independent-commission.uk,independent-inquest.uk,independent-inquiry.uk,independent-panel.uk,independent-review.uk,public-inquiry.uk,royal-commission.uk,gehirn.ne.jp,usercontent.jp,gentapps.com,gentlentapis.com,cdn-edges.net,gsj.bz,gitbook.io,github.app,githubusercontent.com,githubpreview.dev,github.io,gitlab.io,gitpage.si,glideos.app,nog.community,co.ro,shop.ro,lolipop.io,angry.jp,babyblue.jp,babymilk.jp,backdrop.jp,bambina.jp,bitter.jp,blush.jp,boo.jp,boy.jp,boyfriend.jp,but.jp,candypop.jp,capoo.jp,catfood.jp,cheap.jp,chicappa.jp,chillout.jp,chips.jp,chowder.jp,chu.jp,ciao.jp,cocotte.jp,coolblog.jp,cranky.jp,cutegirl.jp,daa.jp,deca.jp,deci.jp,digick.jp,egoism.jp,fakefur.jp,fem.jp,flier.jp,floppy.jp,fool.jp,frenchkiss.jp,girlfriend.jp,girly.jp,gloomy.jp,gonna.jp,greater.jp,hacca.jp,heavy.jp,her.jp,hiho.jp,hippy.jp,holy.jp,hungry.jp,icurus.jp,itigo.jp,jellybean.jp,kikirara.jp,kill.jp,kilo.jp,kuron.jp,littlestar.jp,lolipopmc.jp,lolitapunk.jp,lomo.jp,lovepop.jp,lovesick.jp,main.jp,mods.jp,mond.jp,mongolian.jp,moo.jp,namaste.jp,nikita.jp,nobushi.jp,noor.jp,oops.jp,parallel.jp,parasite.jp,pecori.jp,peewee.jp,penne.jp,pepper.jp,perma.jp,pigboat.jp,pinoko.jp,punyu.jp,pupu.jp,pussycat.jp,pya.jp,raindrop.jp,readymade.jp,sadist.jp,schoolbus.jp,secret.jp,staba.jp,stripper.jp,sub.jp,sunnyday.jp,thick.jp,tonkotsu.jp,under.jp,upper.jp,velvet.jp,verse.jp,versus.jp,vivian.jp,watson.jp,weblike.jp,whitesnow.jp,zombie.jp,heteml.net,vibehost.space,graphic.design,goip.de,*.hosted.app,*.run.app,*.mtls.run.app,web.app,*.0emm.com,appspot.com,*.r.appspot.com,blogspot.com,codespot.com,googleapis.com,googlecode.com,pagespeedmobilizer.com,withgoogle.com,withyoutube.com,*.gateway.dev,cloud.goog,translate.goog,*.usercontent.goog,cloudfunctions.net,cloud.run,ai.studio,goupile.fr,pymnt.uk,gov.nl,grafana-dev.net,grayjayleagues.com,grebedoc.dev,xn--gnstigbestellen-zvb.de,xn--gnstigliefern-wob.de,gv.uy,hackclub.app,xn--hkkinen-5wa.fi,hashbang.sh,hasura.app,hasura-app.io,hatenablog.com,hatenadiary.com,hateblo.jp,hatenablog.jp,hatenadiary.jp,hatenadiary.org,pages.it.hs-heilbronn.de,pages-research.it.hs-heilbronn.de,heiyu.space,helioho.st,heliohost.us,hepforge.org,onhercules.app,hercules-app.com,hercules-dev.com,here.now,herokuapp.com,*.compute.herokuapp.com,heyflow.page,heyflow.site,ravendb.cloud,ravendb.community,development.run,ravendb.run,hidns.co,hidns.vip,homesklep.pl,*.kin.one,*.id.pub,*.kin.pub,seprox.hooc.me,hoplix.shop,orx.biz,biz.ng,co.biz.ng,dl.biz.ng,go.biz.ng,lg.biz.ng,on.biz.ng,col.ng,firm.ng,gen.ng,ltd.ng,ngo.ng,plc.ng,hstgr.cloud,hostyhosting.io,hf.space,static.hf.space,hypernode.io,iobb.net,co.cz,*.moonscale.io,moonscale.net,gr.com,iki.fi,ibxos.it,iliadboxos.it,imagine.diy,imagine-proxy.work,smushcdn.com,wphostedmail.com,wpmucdn.com,tempurl.host,wpmudev.host,dyn-berlin.de,in-berlin.de,in-brb.de,in-butter.de,in-dsl.de,in-vpn.de,in-dsl.net,in-vpn.net,in-dsl.org,in-vpn.org,oninferno.net,info.cx,ac.leg.br,al.leg.br,am.leg.br,ap.leg.br,ba.leg.br,ce.leg.br,df.leg.br,es.leg.br,go.leg.br,ma.leg.br,mg.leg.br,ms.leg.br,mt.leg.br,pa.leg.br,pb.leg.br,pe.leg.br,pi.leg.br,pr.leg.br,rj.leg.br,rn.leg.br,ro.leg.br,rr.leg.br,rs.leg.br,sc.leg.br,se.leg.br,sp.leg.br,to.leg.br,pixolino.com,na4u.ru,botdash.app,botdash.dev,botdash.gg,botdash.net,botda.sh,botdash.xyz,online-server.cloud,apps-1and1.com,live-website.com,webspace-host.com,apps-1and1.net,websitebuilder.online,app-ionos.space,iopsys.se,*.inbrowser.dev,*.dweb.link,*.inbrowser.link,ipifony.net,home64.de,ipv64.de,ipv64.net,iqhs.pl,ir.md,is-a-good.dev,iservschule.de,mein-iserv.de,schuldock.de,schulplattform.de,schulserver.de,test-iserv.de,iserv.dev,iserv.host,ispmanager.name,mel.cloudlets.com.au,cloud.interhostsolutions.be,alp1.ae.flow.ch,appengine.flow.ch,es-1.axarnet.cloud,diadem.cloud,vip.jelastic.cloud,jele.cloud,it1.eur.aruba.jenv-aruba.cloud,it1.jenv-aruba.cloud,keliweb.cloud,cs.keliweb.cloud,oxa.cloud,tn.oxa.cloud,uk.oxa.cloud,primetel.cloud,uk.primetel.cloud,ca.reclaim.cloud,uk.reclaim.cloud,us.reclaim.cloud,ch.trendhosting.cloud,de.trendhosting.cloud,jele.club,dopaas.com,paas.hosted-by-previder.com,rag-cloud.hosteur.com,rag-cloud-ch.hosteur.com,jcloud.ik-server.com,jcloud-ver-jpc.ik-server.com,demo.jelastic.com,paas.massivegrid.com,jed.wafaicloud.com,ryd.wafaicloud.com,j.scaleforce.com.cy,jelastic.dogado.eu,fi.cloudplatform.fi,jele.host,mircloud.host,paas.beebyte.io,sekd1.beebyteapp.io,jele.io,jc.neen.it,jcloud.kz,cloudjiffy.net,fra1-de.cloudjiffy.net,west1-us.cloudjiffy.net,jls-sto1.elastx.net,jls-sto2.elastx.net,jls-sto3.elastx.net,fr-1.paas.massivegrid.net,lon-1.paas.massivegrid.net,lon-2.paas.massivegrid.net,ny-1.paas.massivegrid.net,ny-2.paas.massivegrid.net,sg-1.paas.massivegrid.net,jelastic.saveincloud.net,nordeste-idc.saveincloud.net,j.scaleforce.net,sdscloud.pl,unicloud.pl,mircloud.ru,enscaled.sg,jele.site,jelastic.team,orangecloud.tn,j.layershift.co.uk,phx.enscaled.us,mircloud.us,myjino.ru,*.hosting.myjino.ru,*.landing.myjino.ru,*.spectrum.myjino.ru,*.vps.myjino.ru,jote.cloud,jotelulu.cloud,eu1-plenit.com,la1-plenit.com,us1-plenit.com,webadorsite.com,jouwweb.site,js.org,elastic.k2.cloud,lb.ru-msk.k2.cloud,s3.ru-msk.k2.cloud,website.ru-msk.k2.cloud,lb.ru-spb.k2.cloud,s3.ru-spb.k2.cloud,website.ru-spb.k2.cloud,s3.k2.cloud,website.k2.cloud,kaas.gg,khplay.nl,kapsi.fi,kdns.fr,ezproxy.kuleuven.be,kuleuven.cloud,keenetic.io,keenetic.link,keenetic.name,keenetic.pro,ae.kg,keymachine.de,kiloapps.ai,kiloapps.io,kinghost.net,uni5.net,koobin.events,webthings.io,krellian.net,co.de,shiptoday.app,shiptoday.build,laravel.cloud,on-forge.com,on-vapor.com,*.eth.limo,*.eth.link,git-repos.de,lcube-server.de,svn-repos.de,leadpages.co,lpages.co,lpusercontent.com,leapcell.app,leapcell.dev,leapcell.online,liara.run,iran.liara.run,libp2p.direct,runcontainers.dev,co.business,co.education,co.events,co.financial,co.network,co.place,co.technology,linkyard-cloud.ch,linkyard.cloud,members.linode.com,*.nodebalancer.linode.com,*.linodeobjects.com,ip.linodeusercontent.com,we.bs,filegear-sg.me,ggff.net,*.user.localcert.dev,localtonet.com,*.localto.net,lodz.pl,pabianice.pl,plock.pl,sieradz.pl,skierniewice.pl,zgierz.pl,loginline.app,loginline.dev,loginline.io,loginline.services,loginline.site,lohmus.me,lovable.app,lovableproject.com,lovable.run,lovable.sh,krasnik.pl,leczna.pl,lubartow.pl,lublin.pl,poniatowa.pl,swidnik.pl,glug.org.uk,lug.org.uk,lugs.org.uk,barsy.bg,barsy.club,barsycenter.com,barsyonline.com,barsy.de,barsy.dev,barsy.eu,barsy.gr,barsy.in,barsy.info,barsy.io,barsy.me,barsy.menu,barsyonline.menu,barsy.mobi,barsy.net,barsy.online,barsy.org,barsy.pro,barsy.pub,barsy.ro,barsy.rs,barsy.shop,barsyonline.shop,barsy.site,barsy.store,barsy.support,barsy.uk,barsy.co.uk,barsyonline.co.uk,*.lutrausercontent.com,luyani.app,luyani.net,*.magentosite.cloud,magicpatterns.app,magicpatternsapp.com,hb.cldmail.ru,matlab.cloud,modelscape.com,mwcloudnonprod.com,polyspace.com,mayfirst.info,mcdir.me,mcdir.ru,vps.mcdir.ru,mcpre.ru,mediatech.by,mediatech.dev,hra.health,medusajs.app,miniserver.com,memset.net,messerli.app,atmeta.com,apps.fbsbx.com,*.metaaiusercontent.com,*.cloud.metacentrum.cz,custom.metacentrum.cz,flt.cloud.muni.cz,usr.cloud.muni.cz,meteorapp.com,eu.meteorapp.com,co.pl,*.azurecontainer.io,azure-api.net,azure-mobile.net,azureedge.net,azurefd.net,azurestaticapps.net,1.azurestaticapps.net,2.azurestaticapps.net,3.azurestaticapps.net,4.azurestaticapps.net,5.azurestaticapps.net,6.azurestaticapps.net,7.azurestaticapps.net,centralus.azurestaticapps.net,eastasia.azurestaticapps.net,eastus2.azurestaticapps.net,westeurope.azurestaticapps.net,westus2.azurestaticapps.net,azurewebsites.net,australiacentral-01.azurewebsites.net,australiacentral2-01.azurewebsites.net,australiaeast-01.azurewebsites.net,australiasoutheast-01.azurewebsites.net,austriaeast-01.azurewebsites.net,belgiumcentral-01.azurewebsites.net,brazilsouth-01.azurewebsites.net,brazilsoutheast-01.azurewebsites.net,canadacentral-01.azurewebsites.net,canadaeast-01.azurewebsites.net,centralindia-01.azurewebsites.net,centralus-01.azurewebsites.net,centraluseuap-01.azurewebsites.net,chilecentral-01.azurewebsites.net,denmarkeast-01.azurewebsites.net,eastasia-01.azurewebsites.net,eastasiastage-01.azurewebsites.net,eastus-01.azurewebsites.net,eastus2-01.azurewebsites.net,eastus2euap-01.azurewebsites.net,eastus3-01.azurewebsites.net,francecentral-01.azurewebsites.net,francesouth-01.azurewebsites.net,germanynorth-01.azurewebsites.net,germanywestcentral-01.azurewebsites.net,indiasouthcentral-01.azurewebsites.net,indonesiacentral-01.azurewebsites.net,israelcentral-01.azurewebsites.net,israelnorthwest-01.azurewebsites.net,italynorth-01.azurewebsites.net,japaneast-01.azurewebsites.net,japanwest-01.azurewebsites.net,jioindiacentral-01.azurewebsites.net,jioindiawest-01.azurewebsites.net,koreacentral-01.azurewebsites.net,koreasouth-01.azurewebsites.net,malaysiawest-01.azurewebsites.net,mexicocentral-01.azurewebsites.net,newzealandnorth-01.azurewebsites.net,northcentralus-01.azurewebsites.net,northcentralusstage-01.azurewebsites.net,northeastus5-01.azurewebsites.net,northeurope-01.azurewebsites.net,norwayeast-01.azurewebsites.net,norwaywest-01.azurewebsites.net,*.p.azurewebsites.net,polandcentral-01.azurewebsites.net,qatarcentral-01.azurewebsites.net,southafricanorth-01.azurewebsites.net,southafricawest-01.azurewebsites.net,southcentralus-01.azurewebsites.net,southcentralus2-01.azurewebsites.net,southeastasia-01.azurewebsites.net,southeastus5-01.azurewebsites.net,southindia-01.azurewebsites.net,spaincentral-01.azurewebsites.net,swedencentral-01.azurewebsites.net,swedensouth-01.azurewebsites.net,switzerlandnorth-01.azurewebsites.net,switzerlandwest-01.azurewebsites.net,taiwannorth-01.azurewebsites.net,taiwannorthwest-01.azurewebsites.net,uaecentral-01.azurewebsites.net,uaenorth-01.azurewebsites.net,uksouth-01.azurewebsites.net,ukwest-01.azurewebsites.net,westcentralus-01.azurewebsites.net,westeurope-01.azurewebsites.net,westindia-01.azurewebsites.net,westus-01.azurewebsites.net,westus2-01.azurewebsites.net,westus3-01.azurewebsites.net,cloudapp.net,trafficmanager.net,blob.core.usgovcloudapi.net,file.core.usgovcloudapi.net,web.core.usgovcloudapi.net,servicebus.usgovcloudapi.net,usgovcloudapp.net,usgovtrafficmanager.net,blob.core.windows.net,file.core.windows.net,web.core.windows.net,servicebus.windows.net,azure-api.us,azurewebsites.us,routingthecloud.com,sn.mynetname.net,routingthecloud.net,routingthecloud.org,same-app.com,same-preview.com,csx.cc,miren.app,miren.systems,mydbserver.com,webspaceconfig.de,mittwald.info,mittwaldserver.info,typo3server.info,project.space,mkm.fan,mocha.app,mochausercontent.com,mocha-sandbox.dev,modx.dev,bmoattachments.org,net.ru,org.ru,pp.ru,my.be,hostedpi.com,caracal.mythic-beasts.com,customer.mythic-beasts.com,fentiger.mythic-beasts.com,lynx.mythic-beasts.com,ocelot.mythic-beasts.com,oncilla.mythic-beasts.com,onza.mythic-beasts.com,sphinx.mythic-beasts.com,vs.mythic-beasts.com,x.mythic-beasts.com,yali.mythic-beasts.com,cust.retrosnub.co.uk,ui.nabu.casa,kr.object.fin-ncloudstorage.com,s3-website.kr.object.fin-ncloudstorage.com,kr.object.private.fin-ncloudstorage.com,kr.gov-ncloudstorage.com,kr-private.gov-ncloudstorage.com,kr.object.gov-ncloudstorage.com,s3-website.kr.object.gov-ncloudstorage.com,krs.object.gov-ncloudstorage.com,s3-website.krs.object.gov-ncloudstorage.com,kr.object.private.gov-ncloudstorage.com,krs.object.private.gov-ncloudstorage.com,kr.objectstorage.ncloud.com,sg.objectstorage.ncloud.com,kr.ncloudstorage.com,kr-naver.ncloudstorage.com,kr-naver-dev.ncloudstorage.com,kr-private.ncloudstorage.com,de.object.ncloudstorage.com,s3-website.de.object.ncloudstorage.com,kr.object.ncloudstorage.com,s3-website.kr.object.ncloudstorage.com,sg.object.ncloudstorage.com,s3-website.sg.object.ncloudstorage.com,us.object.ncloudstorage.com,s3-website.us.object.ncloudstorage.com,kr.object.private.ncloudstorage.com,sg.object.private.ncloudstorage.com,kr.website.ncloudstorage.com,kr-naver.website.ncloudstorage.com,kr-naver-dev.website.ncloudstorage.com,jp.object.ncpstorage.com,jp.jp4.object.ncpstorage.com,jp.jp5.object.ncpstorage.com,kr.object.ncpstorage.com,s3-website-jp.object.ncpstorage.com,jp.object.private.ncpstorage.com,jp.jp4.object.private.ncpstorage.com,jp.jp5.object.private.ncpstorage.com,kr.object.private.ncpstorage.com,needle.run,co.site,cloud.nospamproxy.com,o365.cloud.nospamproxy.com,netlib.re,netlify.app,4u.com,nfshost.com,ipfs.nftstorage.link,ngo.us,ngrok.app,ngrok-free.app,ngrok.dev,ngrok-free.dev,ngrok.io,ap.ngrok.io,au.ngrok.io,eu.ngrok.io,in.ngrok.io,jp.ngrok.io,sa.ngrok.io,us.ngrok.io,ngrok.pizza,ngrok.pro,torun.pl,nh-serv.co.uk,nimsite.uk,mmafan.biz,myftp.biz,no-ip.biz,no-ip.ca,fantasyleague.cc,gotdns.ch,3utilities.com,blogsyte.com,ciscofreak.com,damnserver.com,ddnsking.com,ditchyourip.com,dnsiskinky.com,dynns.com,geekgalaxy.com,health-carereform.com,homesecuritymac.com,homesecuritypc.com,myactivedirectory.com,mysecuritycamera.com,myvnc.com,net-freaks.com,onthewifi.com,point2this.com,quicksytes.com,securitytactics.com,servebeer.com,servecounterstrike.com,serveexchange.com,serveftp.com,servegame.com,servehalflife.com,servehttp.com,servehumour.com,serveirc.com,servemp3.com,servep2p.com,servepics.com,servequake.com,servesarcasm.com,stufftoread.com,unusualperson.com,workisboring.com,dvrcam.info,ilovecollege.info,no-ip.info,brasilia.me,ddns.me,dnsfor.me,hopto.me,loginto.me,noip.me,webhop.me,bounceme.net,ddns.net,eating-organic.net,mydissent.net,myeffect.net,mymediapc.net,mypsx.net,mysecuritycamera.net,nhlfan.net,no-ip.net,pgafan.net,privatizehealthinsurance.net,redirectme.net,serveblog.net,serveminecraft.net,sytes.net,cable-modem.org,collegefan.org,couchpotatofries.org,hopto.org,mlbfan.org,myftp.org,mysecuritycamera.org,nflfan.org,no-ip.org,read-books.org,ufcfan.org,zapto.org,no-ip.co.uk,golffan.us,noip.us,pointto.us,stage.nodeart.io,*.developer.app,noop.app,*.northflank.app,*.build.run,*.code.run,*.database.run,*.migration.run,aberdeen.wa.us,bainbridge-isl.wa.us,bellevue.wa.us,bremerton.wa.us,centralia.wa.us,chehalis.wa.us,forks.wa.us,gig-harbor.wa.us,hoquiam.wa.us,keyport.wa.us,kingston.wa.us,olympia.wa.us,port-angeles.wa.us,port-ludlow.wa.us,port-orchard.wa.us,port-townsend.wa.us,poulsbo.wa.us,redmond.wa.us,renton.wa.us,sea.wa.us,seattle.wa.us,sequim.wa.us,shelton.wa.us,silverdale.wa.us,yarrow-point.wa.us,noticeable.news,notion.site,dnsking.ch,mypi.co,myiphost.com,forumz.info,soundcast.me,tcp4.me,dnsup.net,hicam.net,now-dns.net,ownip.net,vpndns.net,dynserv.org,now-dns.org,x443.pw,ntdll.top,freeddns.us,nsupdate.info,nerdpol.ovh,prvcy.page,observablehq.cloud,static.observableusercontent.com,omg.lol,cloudycluster.net,omniwe.site,123webseite.at,123website.be,simplesite.com.br,123website.ch,simplesite.com,123webseite.de,123hjemmeside.dk,123miweb.es,123kotisivu.fi,123siteweb.fr,simplesite.gr,123homepage.it,123website.lu,123website.nl,123hjemmeside.no,service.one,website.one,simplesite.pl,123paginaweb.pt,123minsida.se,onid.ca,is-a-fullstack.dev,is-cool.dev,is-not-a.dev,localplayer.dev,is-local.org,opensocial.site,*.oaiusercontent.com,chatgpt.site,opencraft.hosting,16-b.it,32-b.it,64-b.it,orsites.com,operaunite.com,*.customer-oci.com,*.oci.customer-oci.com,*.ocp.customer-oci.com,*.ocs.customer-oci.com,*.oraclecloudapps.com,*.oraclegovcloudapps.com,*.oraclegovcloudapps.uk,tech.orange,can.re,authgear-staging.com,authgearapps.com,outsystemscloud.com,*.hosting.ovh.net,*.webpaas.ovh.net,ownprovider.com,own.pm,*.owo.codes,ox.rs,oy.lc,pgfog.com,gotpantheon.com,pantheonsite.io,*.paywhirl.com,*.xmit.co,xmit.dev,madethis.site,srv.us,gh.srv.us,gl.srv.us,mypep.link,pplx.app,forgeblocks.com,id.forgerock.io,support.site,on-web.fr,*.upsun.app,upsunapp.com,ent.platform.sh,eu.platform.sh,us.platform.sh,*.platformsh.site,*.tst.site,playcode.site,pley.games,onporter.run,co.bn,postman-echo.com,pstmn.io,mock.pstmn.io,httpbin.org,prequalifyme.today,xen.prgmr.com,priv.at,c01.kr,eliv-api.kr,eliv-cdn.kr,eliv-dns.kr,mmv.kr,vki.kr,dev.project-study.com,platter-app.dev,e.id,chirurgiens-dentistes-en-france.fr,byen.site,nyc.mn,*.cn.st,pubtls.org,puter.app,puter.site,puter.work,pythonanywhere.com,eu.pythonanywhere.com,qa2.com,qcx.io,*.sys.qcx.io,myqnapcloud.cn,mycloudnas.com,mynascloud.com,myqnapcloud.com,qoto.io,qualifioapp.com,ladesk.com,*.qualyhqpartner.com,*.qualyhqportal.com,qbuser.com,*.quipelements.com,vapor.cloud,vaporcloud.io,rackmaze.com,rackmaze.net,cloudsite.builders,myradweb.net,servername.us,web.in,in.net,myrdbx.io,site.rb-hosting.io,up.railway.app,*.on-rancher.cloud,*.on-k3s.io,*.on-rio.io,ravpage.co.il,readthedocs-hosted.com,readthedocs.io,rhcloud.com,instances.spawn.cc,*.clusters.rdpa.co,*.srvrless.rdpa.co,onrender.com,app.render.com,replit.app,id.replit.app,firewalledreplit.co,id.firewalledreplit.co,repl.co,id.repl.co,*.replit.dev,*.teams.replit.dev,repl.run,resindevice.io,devices.resinstaging.io,retool.app,adimo.co.uk,itcouldbewor.se,aus.basketball,nz.basketball,subsc-pay.com,subsc-pay.net,git-pages.rit.edu,rocketpreview.app,*.builtwithrocket.new,rocky.page,rub.de,ruhr-uni-bochum.de,io.noc.ruhr-uni-bochum.de,xn--90amc.xn--p1acf,xn--j1aef.xn--p1acf,xn--j1ael8b.xn--p1acf,xn--h1ahn.xn--p1acf,xn--j1adp.xn--p1acf,xn--c1avg.xn--p1acf,xn--80aaa0cvac.xn--p1acf,xn--h1aliz.xn--p1acf,xn--90a1af.xn--p1acf,xn--41a.xn--p1acf,ras.ru,nyat.app,180r.com,dojin.com,sakuratan.com,sakuraweb.com,x0.com,2-d.jp,bona.jp,crap.jp,daynight.jp,eek.jp,flop.jp,halfmoon.jp,jeez.jp,matrix.jp,mimoza.jp,ivory.ne.jp,mail-box.ne.jp,mints.ne.jp,mokuren.ne.jp,opal.ne.jp,sakura.ne.jp,sumomo.ne.jp,topaz.ne.jp,netgamers.jp,nyanta.jp,o0o0.jp,rdy.jp,rgr.jp,rulez.jp,s3.isk01.sakurastorage.jp,s3.isk02.sakurastorage.jp,saloon.jp,sblo.jp,skr.jp,tank.jp,uh-oh.jp,undo.jp,rs.webaccel.jp,user.webaccel.jp,websozai.jp,xii.jp,squares.net,jpn.org,kirara.st,x0.to,from.tv,sakura.tv,*.builder.code.com,*.dev-builder.code.com,*.stg-builder.code.com,*.001.test.code-builder-stg.platform.salesforce.com,*.aa.crm.dev,*.ab.crm.dev,*.ac.crm.dev,*.ad.crm.dev,*.ae.crm.dev,*.af.crm.dev,*.ci.crm.dev,*.d.crm.dev,*.pa.crm.dev,*.pb.crm.dev,*.pc.crm.dev,*.pd.crm.dev,*.pe.crm.dev,*.pf.crm.dev,*.w.crm.dev,*.wa.crm.dev,*.wb.crm.dev,*.wc.crm.dev,*.wd.crm.dev,*.we.crm.dev,*.wf.crm.dev,sandcats.io,sav.case,logoip.com,logoip.de,fr-par-1.baremetal.scw.cloud,fr-par-2.baremetal.scw.cloud,nl-ams-1.baremetal.scw.cloud,cockpit.fr-par.scw.cloud,ddl.fr-par.scw.cloud,dtwh.fr-par.scw.cloud,fnc.fr-par.scw.cloud,functions.fnc.fr-par.scw.cloud,ifr.fr-par.scw.cloud,k8s.fr-par.scw.cloud,nodes.k8s.fr-par.scw.cloud,kafk.fr-par.scw.cloud,mgdb.fr-par.scw.cloud,rdb.fr-par.scw.cloud,s3.fr-par.scw.cloud,s3-website.fr-par.scw.cloud,scbl.fr-par.scw.cloud,whm.fr-par.scw.cloud,priv.instances.scw.cloud,pub.instances.scw.cloud,k8s.scw.cloud,cockpit.nl-ams.scw.cloud,ddl.nl-ams.scw.cloud,dtwh.nl-ams.scw.cloud,ifr.nl-ams.scw.cloud,k8s.nl-ams.scw.cloud,nodes.k8s.nl-ams.scw.cloud,kafk.nl-ams.scw.cloud,mgdb.nl-ams.scw.cloud,rdb.nl-ams.scw.cloud,s3.nl-ams.scw.cloud,s3-website.nl-ams.scw.cloud,scbl.nl-ams.scw.cloud,whm.nl-ams.scw.cloud,cockpit.pl-waw.scw.cloud,ddl.pl-waw.scw.cloud,dtwh.pl-waw.scw.cloud,ifr.pl-waw.scw.cloud,k8s.pl-waw.scw.cloud,nodes.k8s.pl-waw.scw.cloud,kafk.pl-waw.scw.cloud,mgdb.pl-waw.scw.cloud,rdb.pl-waw.scw.cloud,s3.pl-waw.scw.cloud,s3-website.pl-waw.scw.cloud,scbl.pl-waw.scw.cloud,scalebook.scw.cloud,smartlabeling.scw.cloud,dedibox.fr,scw.site,ams.scw.site,waw.scw.site,schokokeks.net,gov.scot,service.gov.scot,mygov.scot,scrysec.com,client.scrypted.io,firewall-gateway.com,firewall-gateway.de,my-gateway.de,my-router.de,spdns.de,spdns.eu,firewall-gateway.net,my-firewall.org,myfirewall.org,spdns.org,seidat.net,sellfy.store,minisite.ms,senseering.net,servebolt.cloud,biz.ua,co.ua,pp.ua,as.sh.cn,vicp.fun,yicp.fun,zicp.fun,sheezy.games,myshopblocks.com,myshopify.com,shopitsite.com,shopware.shop,shopware.store,mo-siemens.io,1kapp.com,appchizi.com,applinzi.com,sinaapp.com,vipsinaapp.com,siteleaf.net,small-web.org,aeroport.fr,avocat.fr,chambagri.fr,chirurgiens-dentistes.fr,experts-comptables.fr,medecin.fr,notaires.fr,pharmacien.fr,port.fr,veterinaire.fr,vp4.me,*.snowflake.app,*.privatelink.snowflake.app,streamlit.app,streamlitapp.com,try-snowplow.com,mafelo.net,sol.site,playstation-cloud.com,srht.site,apps.lair.io,*.stolos.io,4.at,my.at,my.de,*.nxa.eu,nx.gw,spawnbase.app,customer.speedpartner.de,myspreadshop.at,myspreadshop.com.au,myspreadshop.be,myspreadshop.ca,myspreadshop.ch,myspreadshop.com,myspreadshop.de,myspreadshop.dk,myspreadshop.es,myspreadshop.fi,myspreadshop.fr,myspreadshop.ie,myspreadshop.it,myspreadshop.net,myspreadshop.nl,myspreadshop.no,myspreadshop.pl,myspreadshop.se,myspreadshop.co.uk,w-corp-staticblitz.com,w-credentialless-staticblitz.com,w-staticblitz.com,bolt.host,stackhero-network.com,runs.onstackit.cloud,stackit.gg,stackit.rocks,stackit.run,stackit.zone,sryze.cc,indevs.in,musician.io,novecore.site,statichost.page,feedback.ac,forms.ac,assessments.cx,calculators.cx,funnels.cx,paynow.cx,quizzes.cx,researched.cx,tests.cx,surveys.so,ipfs.storacha.link,ipfs.w3s.link,storebase.store,strapiapp.com,media.strapiapp.com,vps-host.net,atl.jelastic.vps-host.net,njs.jelastic.vps-host.net,ric.jelastic.vps-host.net,streak-link.com,streaklinks.com,streakusercontent.com,soc.srcf.net,user.srcf.net,utwente.io,temp-dns.com,supabase.co,realtime.supabase.co,storage.supabase.co,supabase.in,supabase.net,surge.sh,syncloud.it,dscloud.biz,direct.quickconnect.cn,dsmynas.com,familyds.com,diskstation.me,dscloud.me,i234.me,myds.me,synology.me,dscloud.mobi,dsmynas.net,familyds.net,dsmynas.org,familyds.org,direct.quickconnect.to,vpnplus.to,mytabit.com,mytabit.co.il,tabitorder.co.il,taifun-dns.de,erp.dev,web.erp.dev,ts.net,*.c.ts.net,gda.pl,gdansk.pl,gdynia.pl,med.pl,sopot.pl,taveusercontent.com,p.tawk.email,p.tawkto.email,tche.br,site.tb-hosting.com,directwp.eu,ec.cc,eu.cc,gu.cc,uk.cc,us.cc,edugit.io,s3.teckids.org,telebit.app,telebit.io,*.telebit.xyz,teleport.sh,*.firenet.ch,*.svc.firenet.ch,reservd.com,thingdustdata.com,cust.dev.thingdust.io,reservd.dev.thingdust.io,cust.disrec.thingdust.io,reservd.disrec.thingdust.io,cust.prod.thingdust.io,cust.testing.thingdust.io,reservd.testing.thingdust.io,tickets.io,t3.storage.dev,t3.storageapi.dev,arvo.network,azimuth.network,tlon.network,torproject.net,pages.torproject.net,townnews-staging.com,12hp.at,2ix.at,4lima.at,lima-city.at,12hp.ch,2ix.ch,4lima.ch,lima-city.ch,trafficplex.cloud,de.cool,12hp.de,2ix.de,4lima.de,lima-city.de,1337.pictures,clan.rip,lima-city.rocks,webspace.rocks,lima.zone,*.transurl.be,*.transurl.eu,site.transip.me,*.transurl.nl,*.triton.zone,tunnelmole.net,tuxfamily.org,typedream.app,pro.typeform.com,uber.space,hk.com,inc.hk,ltd.hk,hk.org,it.com,umso.co,unison-services.cloud,virtual-user.de,virtualuser.de,obj.ag,name.pm,sch.tf,biz.wf,sch.wf,org.yt,rs.ba,bielsko.pl,urown.cloud,dnsupdate.info,us.org,v.ua,val.run,web.val.run,vercel.app,v0.build,vercel.dev,vusercontent.net,tmp.now,vercel.run,now.sh,2038.io,v-info.info,vistablog.ir,deus-canvas.com,vivenushop.com,vivenushop.dev,voorloper.cloud,*.vultrobjects.com,wafflecell.com,wal.app,wasmer.app,webflow.io,webflowtest.io,*.webhare.dev,hotelwithflight.com,reserve-online.net,book.online,cprapid.com,pleskns.com,wp2.host,pdns.page,plesk.page,cpanel.site,wpsquared.site,*.wadl.top,remotewd.com,box.ca,pages.wiardweb.com,toolforge.org,wmcloud.org,beta.wmcloud.org,wmflabs.org,hrsn.dev,is-a.dev,vps.hrsn.net,localcert.net,windsurf.app,windsurf.build,drive-platform.com,drive-platform.io,panel.gg,daemon.panel.gg,base44.app,base44-sandbox.com,wixsite.com,wixstudio.com,editorx.io,wixstudio.io,wix.run,messwithdns.com,woltlab-demo.com,myforum.community,community-pro.de,diskussionsbereich.de,community-pro.net,meinforum.net,affinitylottery.org.uk,raffleentry.org.uk,weeklylottery.org.uk,wpenginepowered.com,js.wpenginepowered.com,grok.me,*.xenonconnect.de,half.host,cistron.nl,demon.nl,xs4all.space,xtooldevice.com,yandexcloud.net,storage.yandexcloud.net,website.yandexcloud.net,sourcecraft.site,official.academy,yolasite.com,ynh.fr,nohost.me,noho.st,za.net,za.org,zap.cloud,zeabur.app,*.zerops.app,prg1-zerops.zone,*.zerops.zone,bss.design,basicserver.io,virtualserver.io,enterprisecloud.nu,zone.id,nett.to,zabc.net";
const buildPslBase = () => {
  const R = new Set(), W = new Set(), X = new Set();
  for (const r of PSL_LIST.split(',')) {
    if (r[0] === '!') X.add(r.slice(1));
    else if (r.startsWith('*.')) W.add(r.slice(2));
    else R.add(r);
  }
  // Anzahl der Teile der öffentlichen Endung (längste passende Regel; Ausnahmen „!“ gehen vor; sonst Regel „*“ = 1)
  const suffixLen = (p) => {
    for (let i = 0; i < p.length; i++) {
      const rest = p.slice(i).join('.');
      if (X.has(rest)) return p.length - i - 1;
      if (R.has(rest)) return p.length - i;
      if (i + 1 < p.length && W.has(p.slice(i + 1).join('.'))) return p.length - i;
    }
    return 1;
  };
  return (host) => {
    const h = String(host || '').toLowerCase().replace(/\.$/, '');
    if (!h || /^[\d.]+$/.test(h) || h.indexOf(':') >= 0 || h.indexOf('[') >= 0) return h;   // IP-Adresse: nur sie selbst
    const p = h.split('.');
    const n = suffixLen(p);
    return n >= p.length ? h : p.slice(-(n + 1)).join('.');
  };
};
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

  // Use the pinned official PSL for every browser-session allow decision.
  // Build the lookup only when resolution/download actually needs it.
  let cachedPslBase = null;
  function baseDomain(host) {
    if (!cachedPslBase) cachedPslBase = buildPslBase();
    return cachedPslBase(host);
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
  // dauerhaft gespeichert, übersteht auch Updates des Skripts. Bis zum Koppeln der feste Startschlüssel.
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
    onVisChange(onLeave, true);
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
      offVisChange(onLeave, true);
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
        // (Anmeldung/Cookies nur an die eigene Seite – fremde Server ohne)
        req = x({ method: 'GET', url: u, headers: { Range: 'bytes=0-0', Referer: location.href }, anonymous: baseDomain(hostOf(u)) !== baseDomain(location.hostname),
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
      x({ method: 'GET', url: u, responseType: 'blob', headers: { Referer: location.href }, timeout: 0, anonymous: baseDomain(hostOf(u)) !== baseDomain(location.hostname),
        onprogress: prog,
        onload: (r) => { const b = r && r.response; if (r && r.status >= 200 && r.status < 300 && big(b)) ok(b); else viaPage(); },
        onerror: viaPage, onabort: fail });
    } catch (e) { viaPage(); }
  }

  let lastIntent = { url: '', t: 0, how: '' };
  let tapAt = 0, openTimes = null;   // Zeitmessung beim Öffnen des Players (Diagnose)
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
.pill .pb:focus-visible{outline:2px solid #fff;outline-offset:1px}
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
    // Play, ±10 und das Menü ohne Lupen-Effekt – so gewünscht
    if (el.classList && (el.classList.contains('vplay') || el.classList.contains('vskip') || el.classList.contains('vmenu'))) return;
    // mit ResizeObserver: der meldet die Größe ohnehin gleich nach dem nächsten Aufbau – dann ohne erzwungenes
    // Vermessen mitten im Öffnen (das kostete auf großen Seiten spürbar Zeit)
    if (lensRO) lensRO.observe(el); else applyLens(el);
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
    if (v !== active) watchSize(v);   // neues Video: wieder klein anfangen
    active = v;
    pill.classList.add('on');
    schedulePlace();
  }
  function hidePill() {
    if (pill) pill.classList.remove('on');
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
        if (!FB_MIRROR || FB_TRY_OWN) { try { fetchHD(v); } catch (e) {} }   // beim (reinen) Spiegeln unnötig – spart Daten
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
  onVisChange(() => { if (document.hidden) stopChecks(); else startChecks(); });
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
    replay: 'M12 5V1L7 6l5 5V7c3.31 0 6 2.69 6 6s-2.69 6-6 6-6-2.69-6-6H4c0 4.42 3.58 8 8 8s8-3.58 8-8-3.58-8-8-8z',
    pause: 'M6 19h4V5H6v14zm8-14v14h4V5h-4z',
    fit: 'M19 12h-2v3h-3v2h5v-5zM7 9h3V7H5v5h2V9zm14-6H3c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h18c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 16.01H3V4.99h18v14.02z',
    rotate: 'M16.48 2.52c3.27 1.55 5.61 4.72 5.97 8.48h1.5C23.44 4.84 18.29 0 12 0l-.66.03 3.81 3.81 1.33-1.32zm-6.25-.77c-.59-.59-1.54-.59-2.12 0L1.75 8.11c-.59.59-.59 1.54 0 2.12l12.02 12.02c.59.59 1.54.59 2.12 0l6.36-6.36c.59-.59.59-1.54 0-2.12L10.23 1.75zm4.6 19.44L2.81 9.17l6.36-6.36 12.02 12.02-6.36 6.36zm-7.31.29C4.25 19.94 1.91 16.76 1.55 13H.05C.56 19.16 5.71 24 12 24l.66-.03-3.81-3.81-1.33 1.32z',
    lock: 'M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zm-6 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zm3.1-9H8.9V6c0-1.71 1.39-3.1 3.1-3.1 1.71 0 3.1 1.39 3.1 3.1v2z',
    cc: 'M19 4H5c-1.11 0-2 .9-2 2v12c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm-8 7H9.5v-.5h-2v3h2V13H11v1c0 .55-.45 1-1 1H7c-.55 0-1-.45-1-1v-4c0-.55.45-1 1-1h3c.55 0 1 .45 1 1v1zm7 0h-1.5v-.5h-2v3h2V13H18v1c0 .55-.45 1-1 1h-3c-.55 0-1-.45-1-1v-4c0-.55.45-1 1-1h3c.55 0 1 .45 1 1v1z',
    hd: 'M19 4H5c-1.11 0-2 .9-2 2v12c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm-8 11H9.5v-2h-2v2H6V9h1.5v2.5h2V9H11v6zm7-1c0 .55-.45 1-1 1h-.75v1.5h-1.5V15H14c-.55 0-1-.45-1-1v-4c0-.55.45-1 1-1h3c.55 0 1 .45 1 1v4zm-3.5-.5h2v-3h-2v3z',
    vol: 'M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z',
    back: 'M15.41 7.41 14 6l-6 6 6 6 1.41-1.41L10.83 12z',
    repeat: 'M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4z',
    mute: 'M16.5 12c0-1.77-1.02-3.29-2.5-4.03v2.21l2.45 2.45c.03-.2.05-.41.05-.63zm2.5 0c0 .94-.2 1.82-.54 2.64l1.51 1.51C20.63 14.91 21 13.5 21 12c0-4.28-2.99-7.86-7-8.77v2.06c2.89.86 5 3.54 5 6.71zM4.27 3L3 4.27 7.73 9H3v6h4l5 5v-6.73l4.25 4.25c-.67.52-1.42.93-2.25 1.18v2.06c1.38-.31 2.63-.95 3.69-1.81L19.73 21 21 19.73l-9-9L4.27 3zM12 4L9.91 6.09 12 8.18V4z',
    sun: 'M20 8.69V4h-4.69L12 .69 8.69 4H4v4.69L.69 12 4 15.31V20h4.69L12 23.31 15.31 20H20v-4.69L23.31 12 20 8.69zM12 18c-3.31 0-6-2.69-6-6s2.69-6 6-6 6 2.69 6 6-2.69 6-6 6zm0-10c-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4-1.79-4-4-4z',
  };
  const VP_CSS = `
.vp{--glass:rgba(255,255,255,.13);--edge1:rgba(255,255,255,.6);--edge2:rgba(255,255,255,.06);--sub:rgba(255,255,255,.74);--ice:#FFFFFF;--trk:rgba(255,255,255,.25);
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
/* Sofortbild beim Öffnen (Bild des Seitenvideos), bis die eigene Kopie läuft */
.vposter{position:absolute;inset:0;width:100%;height:100%;object-fit:contain;pointer-events:none}
/* Ambient-Modus: die Farben des Bildes leuchten weich in die schwarzen Ränder (wie bei YouTube) */
.vamb{position:absolute;left:-12%;top:-12%;width:124%;height:124%;opacity:0;transition:opacity .25s ease;pointer-events:none}
/* Firefox-Handy (zweites Video statt Mini-Bild): Weichzeichnen und Rand per CSS. Sonst steckt beides schon im
   Mini-Bild selbst – der Bildschirm vergrößert es nur (kein teurer Weichzeichner über den ganzen Bildschirm) */
video.vamb{filter:blur(48px) saturate(1.45) brightness(.7);
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
.vchrome{position:absolute;inset:0;pointer-events:none;visibility:hidden;transition:visibility 0s linear .16s}
.vchrome .g{opacity:0;transition:opacity .16s ease-out,transform .32s cubic-bezier(.34,1.56,.64,1)}
.vchrome .vtitle,.vchrome .vrow,.vchrome .vscrim{opacity:0;transition:opacity .16s ease-out}
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
   Play und ±10: nur der feine Glasrand, kein Hintergrund */
.vb.g:not(.vplay):not(.vskip){background:rgba(26,28,38,.14);-webkit-backdrop-filter:var(--lens,blur(0px)) blur(var(--gb,12px)) saturate(1.4) brightness(1.1);backdrop-filter:var(--lens,blur(0px)) blur(var(--gb,12px)) saturate(1.4) brightness(1.1);box-shadow:0 6px 22px rgba(0,0,0,.24)}
.vb:active{transform:scale(.88)}
.vb[hidden]{display:none !important}
.vb svg{width:22px;height:22px;fill:#fff;position:relative;z-index:1;filter:drop-shadow(0 1px 2px rgba(0,0,0,.5))}
.vtext,.vskip span,.vchip span,.vtime,.vind span{text-shadow:0 1px 2px rgba(0,0,0,.45)}
.vmid .g{background:rgba(22,24,32,.16)}
.vb:focus-visible,.vchip:focus-visible,.vmi:focus-visible{outline:2px solid var(--ice);outline-offset:2px}
.vstash{display:none!important}
.vp.ctlnew .vchrome,.vp.ctlnew .vchrome *{pointer-events:none!important}
.vp.vgone{opacity:0!important;transition:none!important}
.vp.vgone,.vp.vgone *{pointer-events:none!important}
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
/* aktiv: einfarbig wie iOS/One UI – helle Fläche hinter dem weißen Symbol (statt Farbe) */
.vchip.on{color:#fff}
.vchip.on::before{content:"";position:absolute;inset:5px;border-radius:50%;background:rgba(255,255,255,.2);z-index:-1}
.vchip.on svg{fill:var(--ice)}
.vchip .vrepn{position:absolute;right:2px;bottom:3px;min-width:14px;height:14px;border-radius:7px;padding:0 3px;box-sizing:border-box;background:#fff;color:#111;box-shadow:0 1px 3px rgba(0,0,0,.35);font:800 10px/14px var(--font);text-align:center}
.vchip .vrepn:empty{display:none}
.vchip[hidden]{display:none}
.vtime{font:600 12px var(--font);font-variant-numeric:tabular-nums;color:var(--sub);min-width:40px;text-align:center}
.vbar{flex:1;-webkit-appearance:none;appearance:none;height:38px;background:transparent;margin:0;cursor:pointer;filter:invert(1);--trk:rgba(0,0,0,.25);pointer-events:none;transition:opacity .15s ease}
.vbar::-webkit-slider-runnable-track{height:6px;border-radius:3px;background:var(--trk);transition:height .18s ease,border-radius .18s ease}
.vbar::-moz-range-track{height:6px;border-radius:3px;background:var(--trk);transition:height .18s ease}
/* Berührungsfläche der Leiste: groß (56 px hoch), über der ganzen Zeile – Spulen siehe 220 */
.vbarhit{position:absolute;left:0;right:0;top:50%;height:56px;transform:translateY(-50%);z-index:2;touch-action:none;cursor:pointer}
/* beim Ziehen: dicke Leiste ohne Punkt (wie Samsung Internet) */
.vp.thick .vbar::-webkit-slider-runnable-track{height:18px;border-radius:9px}
.vp.thick .vbar::-moz-range-track{height:18px;border-radius:9px}
.vp.thick .vbar::-webkit-slider-thumb{transform:scale(0) !important;margin-top:0 !important}
.vp.thick .vbar::-moz-range-thumb{transform:scale(0) !important}
/* Abbrechen-Bereich (Finger weit über der Leiste) und Fein-Spulen (unter der Leiste) */
.vp.scrubx .vbar{opacity:.4}
/* Abbrechen-Bereich: keine kleine Vorschau – das große Video zeigt die laufende Stelle */
.vp.scrubx .vprev .vpbox{display:none}
/* Zeit über der Vorschau: im Glas des Designs (.g, „Schlicht“ = Milchglas) – rot getönt beim Abbrechen, violett mit Lupe */
.vp.scrubx .vprev b.g{background:rgba(205,45,45,.62)!important}
.vp.zoom .vprev b.g{background:rgba(110,86,226,.5)!important}
/* Lupe: Grenzen des Ausschnitts links/rechts als kleine Glas-Schilder */
.vtime.vtz{color:#fff;font-weight:700;padding:4px 6px;border-radius:11px;min-width:34px;box-shadow:0 2px 10px rgba(0,0,0,.22)}
/* alte Stelle beim Ziehen (dorthin zurück rastet ein) */
/* großer Punkt beim Ziehen: immer unter dem Finger, im Glas des Designs (Schlicht = Milchglas), weißer Kern */
.vknob.g{position:absolute;top:50%;left:0;width:34px;height:34px;margin-top:-17px;border-radius:50%;pointer-events:none;z-index:3;opacity:0;transform:scale(.4);transition:opacity .12s ease,transform .2s cubic-bezier(.34,1.56,.64,1)}
.vknob b{position:absolute;inset:9px;border-radius:50%;background:#fff;box-shadow:0 1px 4px rgba(0,0,0,.45);z-index:2}
.vp .vchrome .vknob.g{opacity:0}   /* (stärker als „Bedienung sichtbar → alles Glas sichtbar“) – nur beim Ziehen */
.vp.thick .vchrome .vknob.g{opacity:1;transform:none}
.vp.scrubx .vchrome .vknob.g{opacity:.35}
.vp.zoom .vknob b{background:#e4dcff}
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
/* Bild lädt noch (gezeigt wird ein gemerktes Bild einer anderen Stelle): leicht abgedunkelt, bis das richtige da ist */
.vprev.img.stale .vpc{opacity:.55;filter:brightness(.8)}
.vprev.live .vpv{opacity:1}
.vprev.live .vpc{opacity:0}
.vprev.noimg .vpbox,.vprev.tonly .vpbox,.vprev.timeonly .vpbox{display:none}
.vprev b{white-space:nowrap;font:700 13px var(--font);font-variant-numeric:tabular-nums;color:#fff;text-shadow:0 1px 3px rgba(0,0,0,.6);padding:5px 11px;border-radius:13px;transition:background .15s ease}
/* Spulen per Wischen: große, dünne Zeit mitten im Bild, darunter die Restzeit – nur Zahlen, kein Kasten */
.vseek{position:absolute;left:50%;top:50%;transform:translate(-50%,-50%);text-align:center;color:#fff;pointer-events:none;opacity:0;transition:opacity .14s ease;z-index:3;white-space:nowrap;
 text-shadow:0 2px 14px rgba(0,0,0,.45),0 0 2px rgba(0,0,0,.4);font-variant-numeric:tabular-nums}
.vseek.on{opacity:1}
.vseek b{display:block;font:200 68px/1 var(--font);letter-spacing:.5px}
.vseek small{display:block;margin-top:10px;font:300 21px/1 var(--font);color:rgba(255,255,255,.88)}
.vseek i{display:block;margin-top:8px;font:500 13px/1 var(--font);font-style:normal;color:rgba(255,255,255,.7);letter-spacing:.3px}
/* kleine Leiste beim Wischen: wo im Video (Stelle / Länge) */
.vseekl{display:block;position:relative;width:150px;height:3px;margin:10px auto 0;border-radius:2px;background:rgba(255,255,255,.28);overflow:hidden;box-shadow:0 1px 3px rgba(0,0,0,.3)}
.vseekf{position:absolute;inset:0;background:#fff;transform-origin:0 50%;transform:scaleX(0)}
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
.vmenu .vmh.bk{display:flex;align-items:center;gap:4px;padding:2px 12px 4px 2px;font:600 15px var(--font);color:#fff}
.vmenu .vmb{width:40px;height:40px;border-radius:20px;background:transparent}
.vmenu .vmb svg{width:24px;height:24px;fill:#fff}
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
  // Kadri/Facebook: die MP4 nur nehmen, wenn sie sicher zum laufenden Video gehört – sonst keine (→ Spiegeln)
  // Qualität zuerst: Die fertige Datei nur nehmen, wenn sie mindestens etwa so scharf ist wie das, was Facebook
  // gerade zeigt (kurze Bildseite). Facebook spielt selbst oft HD als getrennte Bild-/Tonspur – die kann nur das Spiegeln.
  function fbRes(u) {
    const tag = encTag(u);
    const m = /(\d{3,4})p\b/.exec(tag) || /\.C3\.(\d{3,4})\./.exec(tag);
    return m ? +m[1] : 0;
  }
  // forPrev: für das Vorschaubild beim Spiegeln – jede Schärfe recht, die kleinste zuerst (lädt am schnellsten)
  function fbSureSources(v, resMap, forPrev) {
    try {
      const af = assetFor(v);
      if (!af.sure || !af.asset) return [];
      let out = playerSources(v, resMap).filter((u) => assetOf(u) === af.asset);
      if (forPrev) return out.sort((a, b) => (fbRes(a) || 1e4) - (fbRes(b) || 1e4));
      const shown = Math.min(v.videoWidth || 0, v.videoHeight || 0);   // was Facebook gerade abspielt
      if (shown > 0) out = out.filter((u) => fbRes(u) >= shown * 0.9);   // unschärfer (oder unbekannt) → spiegeln
      out.sort((a, b) => fbRes(b) - fbRes(a));
      out.forEach((u) => { if (resMap && fbRes(u)) resMap[u] = fbRes(u); });
      return out;
    } catch (e) { return []; }
  }
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
      orig: orig, sources: FB_MIRROR && (orig.captureStream || orig.mozCaptureStream) ? (FB_TRY_OWN ? fbSureSources(orig, srcRes) : []) : playerSources(orig, srcRes), srcRes: srcRes, idx: 0, hls: null, mode: 'own', cur: '',
      capFallback: FB_TRY_OWN,   // passt die Datei doch nicht (andere Länge): sofort spiegeln
      startTime: orig.currentTime || 0, loadTimer: 0, hideTimer: 0, u2: 0, locked: false,
      dim: 0, fit: 'contain', speed: 1, wasFs: false, cleanup: [], barDrag: false, orient: '',
      level: 1,      // Lautstärke 0–2 (über 1 = Verstärkung)
      bright: 1,     // Helligkeit 0,15–1,5 (unter 1 = abdunkeln, über 1 = Bild aufhellen)
      actx: null, gain: null, srcNode: null, boostWarned: false,
      framed: IN_FRAME, rfs: false, fsWait: null,   // eingebettetes Fenster: Hauptseite übernimmt Vollbild/Drehen
    };
    vp = st;
    // Zeitmessung fürs Öffnen (Diagnose): ab dem Antippen des Knopfs
    st.openStat = openTimes = { t0: tapAt && performance.now() - tapAt < 4000 ? tapAt : performance.now(), how: '' };
    // Tonverstärkung vorbereiten, solange der Tipp auf "Player" noch "frisch" ist (sonst startet der Ton stumm)
    try {
      const AC = window.AudioContext || window.webkitAudioContext;
      if (AC) { st.actx = new AC(); if (st.actx.state === 'suspended') st.actx.resume().catch(() => {}); }
    } catch (e) { st.actx = null; }

    // ----- Oberfläche -----
    const wrap = h('div', { class: CONFIG.openUi === 'controls' ? 'vp ctl' : 'vp' });   // „Nur Bild“: ohne Leisten öffnen
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
    const seekFill = h('span', { class: 'vseekf' }), seekLine = h('span', { class: 'vseekl' }, seekFill);
    const seekBox = h('div', { class: 'vseek', 'aria-live': 'polite' }, seekBig, seekRest, seekLine, seekDelta);
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
    // Kadri: Knopf immer – kennt der Browser kein Bild-in-Bild für Webseiten, erklärt er den Weg über den Startbildschirm
    const bPip = document.pictureInPictureEnabled || IS_KADRI ? ib(I.pip, 'Bild-in-Bild', 'vtool') : null;
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
    // Aufteilung („oben schlank“): oben nur Download und ⋮ (Titel bleibt lesbar), unten am Daumen CC,
    // Bildformat, Bild-in-Bild, Drehen. Teilen, In App öffnen, Qualität, Tempo, Tonspur, Standbild, Einstellungen: im ⋮-Menü.
    // Teilen/In App/Qualität/Tempo bleiben als unsichtbare Knöpfe (.vstash) bestehen – das Menü löst sie aus.
    const bMore = ib(I.more, 'Mehr');
    const bSubs = chip(P.cc, 'Untertitel', 'Untertitel');   // unten: Untertitel (leuchtet, wenn an)
    const bRep = chip(P.repeat, 'Wiederholen', 'Wiederholen');   // unten: Aus → 1× → ∞ → Aus
    const repNum = h('b', { class: 'vrepn' }, ''); bRep.append(repNum);
    const subEl = h('div', { class: 'vsub', 'aria-live': 'polite' });
    const bUnlock = ib(P.lock, 'Entsperren', 'vunlock g');
    const menu = h('div', { class: 'vmenu g', role: 'menu' });
    bQual.hidden = true;   // erscheint, sobald es Qualitätsstufen gibt
    wrap.append(amb, vid, dimEl, subEl, gest, spin,
      h('div', { class: 'vchrome' },
        h('div', { class: 'vscrim' }),
        h('div', { class: 'vtop' }, bClose, title, h('div', { class: 'vcluster g ia' }, bDl, bMore)),
        h('div', { class: 'vmid' }, bBack, bPlay, bFwd),
        h('div', { class: 'vbot ia' }, h('div', { class: 'vrow' }, tCur, bar, tDur), h('div', { class: 'vhead' }, h('div', { class: 'vtools g solo' }, bLock), h('div', { class: 'vtools g' }, bSubs, bRep, bFit, bPip, bRot))), h('div', { class: 'vstash' }, bShare, bExt, bQual, bSpeed)),
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
        try { m.volume = clamp(page, 0, 1); m.muted = lv === 0; if ((st.mode === 'capture' && !FF_CAP) || st.bridge) vid.muted = true; } catch (e) {}
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
      if ((st.mode === 'capture' && !FF_CAP) || st.bridge) vid.muted = true;   // (beim nahtlosen Start kommt der Ton noch von der Seite)
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
      onVisChange(onVis);
      st.cleanup.push(() => { clearInterval(beat); offVisChange(onVis); });
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
    // Anzeige beim Wischen: große Zeit, darunter „Stelle / Länge“ mit kleiner Leiste, dann der Sprung („+3 s“)
    const showSeek = (to, dur, delta) => {
      const fin = isFinite(dur) && dur > 0;
      seekBig.textContent = fmtTime(to);
      seekRest.textContent = fin ? '/ ' + fmtTime(dur) : '';   // (große Zahl = Stelle, darunter die Länge)
      seekLine.style.display = fin ? '' : 'none';
      if (fin) seekFill.style.transform = 'scaleX(' + clamp(to / dur, 0, 1).toFixed(3) + ')';
      const a = Math.round(Math.abs(delta));
      seekDelta.textContent = (delta < -0.4 ? '−' : '+') + (a < 60 ? a + ' s' : fmtTime(a));
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

    // Video im Player anordnen: Bildformat (Original/Füllen/Strecken), Zoom und Verschieben
    function layoutVideo() {
      vid.style.objectFit = st.aspect;
      // Mittig per CSS (an allen vier Seiten verankert, Rand "auto"): bleibt auch dann genau in der Mitte, wenn sich die
      // Höhe nachträglich ändert (Vollbild, Adressleiste ein/aus). Ohne Verschiebung per transform – die hat Android
      // in eingebetteten Vollbild-Fenstern falsch umgesetzt (Bild saß zu weit oben).
      vid.style.width = '100%';
      vid.style.height = '100%';
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
      const w = W, hh = H;
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
      // Bild nehmen, das gerade zu sehen ist: beim nahtlosen Start das laufende Bild der Seite, sonst das Sofortbild –
      // bis die eigene Kopie da ist (so ist das Licht gleich beim Öffnen da, nicht erst eine Weile später)
      const br = st.bridge && st.bridge.el;
      const src = br && br.readyState >= 2 && br.videoWidth ? br : vid.readyState >= 2 && vid.videoWidth ? vid :
        st.posterCv ? st.posterCv : orig.readyState >= 2 && orig.videoWidth ? orig : null;
      if (!src) return;
      const vw = src.videoWidth || src.width, vh = src.videoHeight || src.height;
      if (!vw || !vh) return;
      const ch = Math.max(8, Math.min(48, Math.round(32 * vh / vw)));
      if (amb.height !== ch) { amb.height = ch; accC.height = ch; first = true; }
      const w = amb.width, h = amb.height;
      try {
        accX.globalAlpha = first ? 1 : 0.5;   // sanft überblenden statt flackern
        accX.drawImage(src, 0, 0, w, h);
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
    // gleich beim Öffnen (Sofortbild/Bild der Seite) – nicht erst nach dem ersten Takt
    [0, 120, 300].forEach((ms) => setTimeout(() => ambDraw(ms === 0), ms));
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
    function showControls() { const was = wrap.classList.contains('ctl'); wrap.classList.add('ctl'); scheduleHide(); if (!was) updateUI(); }
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
      if (p >= 0.999) b = 1;
      const P1 = (p * 100).toFixed(2), B1 = (Math.max(p, b) * 100).toFixed(2);
      bar.style.setProperty('--trk', 'linear-gradient(to right,#000 ' + P1 + '%,rgba(0,0,0,.42) ' + P1 + '%,rgba(0,0,0,.42) ' + B1 + '%,rgba(0,0,0,.18) ' + B1 + '%)'   /* umgedreht: wird per invert(1) weiß */);
    }

    // ----- Anzeige aktualisieren -----
    // Nur schreiben, was sich geändert hat – und Zeitleiste/Zeiten nur, solange sie zu sehen sind (beim Einblenden
    // aktualisiert showControls sofort). Spart beim normalen Schauen ständige Stil-Neuberechnungen.
    const uiLast = { cur: '', dur: '', play: null, len: null, mini: '' };
    // Am Ende angehalten (Reels: kurz VOR dem Ende, damit die Seite nicht zum nächsten springt) – aber nur, solange es
    // noch dort steht (nicht nach Zurückspulen)
    function heldAtEnd(m) {
      m = m || M();
      const d = m.duration;
      return !!st.endHold && m.paused && isFinite(d) && d > 0 && m.currentTime >= d - 1.5;
    }
    st.heldAtEnd = heldAtEnd;
    // Spulbereich: Datei/Video = 0 … Länge. Live-Stream mit Zurückspulen (z. B. ARD live) = der Bereich, den der Stream
    // zurückhält (ab 1 min; rechts ist „live“). Sonst (reines Live) kein Spulen.
    // (hls.js gibt Live-Streams eine endliche, wachsende Länge – dort fragen, ob die Liste „live“ ist)
    const hlsLive = () => {
      try { const h = st.mode === 'own' && st.hls; if (!h) return false; const dt = h.latestLevelDetails || ((h.levels || [])[h.currentLevel] || {}).details; return !!(dt && dt.live); } catch (e) { return false; }
    };
    function seekRange(m) {
      m = m || M();
      const d = m.duration;
      if (isFinite(d) && d > 0 && !(m === vid && hlsLive())) return { a: 0, b: d, len: d, live: false };
      try {
        const s = m.seekable;
        if (s && s.length) {
          const a = s.start(0), b = s.end(s.length - 1);
          if (isFinite(a) && isFinite(b) && b - a >= 60) return { a: a, b: b, len: b - a, live: true };
        }
      } catch (e) {}
      return null;
    }
    // Live-Rand: wo „live“ ist – hls.js spielt Live bewusst ein paar Stücke hinter dem Ende der Liste (liveSyncPosition)
    const liveEdge = (r) => { try { const p = st.mode === 'own' && st.hls && st.hls.liveSyncPosition; if (isFinite(p) && p > r.a) return Math.min(r.b, p); } catch (e) {} return r.b; };
    // Live: wie weit hinter live („−5:30“), nah am Live-Rand „LIVE“
    const behind = (r, t) => { const e = liveEdge(r); return e - t < 8 ? tr('LIVE') : '−' + fmtTime(e - t); };
    function updateUI() {
      const m = M();
      const held = heldAtEnd(m);
      const d = m.duration, c = held ? d : m.currentTime;   // angehalten am Ende: als fertig zeigen (volle Leiste, Endzeit)
      const hasLen = isFinite(d) && d > 0 && !(m === vid && hlsLive());
      if (uiLast.len !== hasLen) { uiLast.len = hasLen; wrap.classList.toggle('nolen', !hasLen); }   // Live-Stream: keine Linie
      if (hasLen) {
        const mf = 'scaleX(' + Math.min(1, Math.max(0, c / d)).toFixed(3) + ')';
        if (mf !== uiLast.mini) { uiLast.mini = mf; miniFill.style.transform = mf; }
      }
      const pk = !m.paused ? 'pause' : (held || m.ended) ? 'replay' : 'play';   // am Ende: „noch einmal“ statt Play
      if (uiLast.play !== pk) { uiLast.play = pk; setIcon(bPlay, P[pk]); }
      if (!wrap.classList.contains('ctl') && !st.barDrag) return;   // Leisten aus: Zeiten und Zeitleiste ruhen lassen
      const dvr = hasLen ? null : seekRange(m);   // Live mit Zurückspulen
      const tc = dvr ? behind(dvr, c) : fmtTime(c), td = hasLen ? fmtTime(d) : 'Live';
      if (tc !== uiLast.cur) { uiLast.cur = tc; tCur.textContent = tc; }
      if (td !== uiLast.dur) { uiLast.dur = td; tDur.textContent = td; }
      if (!st.barDrag) {
        const p = hasLen ? c / d : dvr ? clamp((c - dvr.a) / dvr.len, 0, 1) : 0;
        bar.value = String(Math.round(p * 1000));
        paintBar(p);
      }
    }
    const onMediaEv = (e) => {
      const fromOrig = e.currentTarget === orig;
      if ((st.mode === 'capture') !== fromOrig) return;
      if (e.type === 'waiting' && !st.bridge) spin.classList.add('on');   // (die stumme Kopie hinter dem nahtlosen Start lädt unsichtbar)
      if (e.type === 'playing' || e.type === 'canplay') spin.classList.remove('on');
      if (e.type === 'pause' && !st.swipeHold) showControls();   // (kurze Pause beim Wischen übers Video: Leisten nicht zeigen)
      updateUI();
    };
    ['timeupdate', 'durationchange', 'play', 'pause', 'waiting', 'playing', 'canplay', 'seeked'].forEach((t) => {
      vid.addEventListener(t, onMediaEv);
      orig.addEventListener(t, onMediaEv);
      st.cleanup.push(() => orig.removeEventListener(t, onMediaEv));
    });

    // Sofortbild: Bis die eigene Kopie das erste Bild hat (neu laden + an die Stelle springen dauert auf langsamen
    // Servern Sekunden), zeigt der Player das aktuelle Bild des Seitenvideos – er wirkt sofort da.
    function showPoster() {
      if (st.posterCv || !(orig.readyState >= 2) || !orig.videoWidth) return;
      try {
        const k = Math.min(1, 1280 / orig.videoWidth), c = document.createElement('canvas');
        c.className = 'vposter';
        c.width = Math.round(orig.videoWidth * k); c.height = Math.round(orig.videoHeight * k);
        c.getContext('2d').drawImage(orig, 0, 0, c.width, c.height);
        vid.after(c);
        st.posterCv = c;
        st.posterTm = setTimeout(dropPoster, 12000);
      } catch (e) {}
    }
    function dropPoster() { clearTimeout(st.posterTm); if (st.posterCv) { st.posterCv.remove(); st.posterCv = null; } }
    // weg, sobald das eigene Video wirklich läuft (an der richtigen Stelle) – oder beim Spiegeln
    vid.addEventListener('playing', () => { if (st.mode === 'own' && (!st.startTime || Math.abs(vid.currentTime - st.startTime) < 3 || vid.currentTime > 0.5)) dropPoster(); });
    st.cleanup.push(dropPoster);
    // ----- Nahtloser Start (auf allen Seiten): Bis die eigene Kopie an derselben Stelle läuft, zeigt der Player das
    // LAUFENDE Bild der Seite (gespiegelt), der Ton kommt so lange weiter von der Seite. Die eigene Kopie läuft stumm
    // dahinter und holt auf – etwas schneller oder langsamer, große Abstände per Sprung. Stimmt es auf unter 0,1 s,
    // wird umgeschaltet: eigener Ton an, Seite still – man merkt nichts. Klappt das nicht (die Seite hält an, du
    // bedienst schon, nach 10 s): sofort umschalten wie früher (Standbild, bis die Kopie läuft).
    // Firefox: das Spiegeln nimmt dort der Seite den Ton – dort bleibt es beim Standbild.
    function startBridge() {
      if (GECKO || orig.paused || orig.ended || !(orig.readyState >= 2) || !orig.captureStream) return false;
      let ms = null;
      try { ms = orig.captureStream(); } catch (e) { return false; }
      if (!ms) return false;
      const el = document.createElement('video');
      el.className = 'vposter vbridge';
      el.muted = true; el.setAttribute('playsinline', '');
      el.srcObject = ms;
      el.play().catch(() => {});
      el.addEventListener('playing', () => { if (!st.openStat.live) st.openStat.live = performance.now(); }, { once: true });
      (st.posterCv || vid).after(el);
      const b = { el: el, ms: ms, at: Date.now(), seeks: 0, seekAt: 0, lead: 0.35, tm: 0, iv: 0 };
      st.bridge = b;
      spin.classList.remove('on');   // das Bild läuft – kein Ladekreis
      vid.muted = true;
      b.tm = setTimeout(() => bridgeEnd('nach 10 s'), 10000);
      b.iv = setInterval(bridgeTick, 50);
      st.openStat.how = 'nahtlos (läuft)';
      return true;
    }
    function bridgeTick() {
      const b = st.bridge;
      if (!b || vp !== st) return;
      if (st.mode !== 'own' || st.handed) { if (st.handed) st.bridgeMuted = true; bridgeDrop(); return; }   // (wird gerade geschlossen: die Seite hat schon übernommen)
      if (orig.paused || orig.ended) { bridgeEnd('Seite hält an'); return; }
      if ((st.userSeekAt || 0) > b.at || (st.userPauseAt || 0) > b.at) { bridgeEnd('du bedienst'); return; }
      if (vid.readyState < 3 || vid.seeking) return;   // eigene Kopie lädt noch
      if (vid.paused) { bridgeEnd('eigene Kopie angehalten'); return; }
      const base = orig.playbackRate || 1;
      const d = orig.currentTime - vid.currentTime;   // > 0: die eigene Kopie ist noch hinten
      if (Math.abs(d) <= 0.08) { bridgeSwap(d); return; }
      if (Math.abs(d) > 1.5) {
        if (b.seeks >= 3) { bridgeEnd('holt nicht auf'); return; }
        b.seeks++; b.seekAt = performance.now(); b.to = orig.currentTime + b.lead * base;
        try { vid.currentTime = b.to; } catch (e) {}   // etwas voraus springen (Springen dauert)
        return;
      }
      vid.playbackRate = base * clamp(1 + d, 0.5, 2);   // aufholen (stumm – die Tonhöhe ist egal)
    }
    vid.addEventListener('seeked', () => {
      const b = st.bridge;
      if (b && b.seekAt) { b.lead = Math.min(3, (performance.now() - b.seekAt) / 1000 + 0.1); b.seekAt = 0; }   // so lange dauert ein Sprung hier
    });
    // Jemand anderes springt im eigenen Video (du, ein Knopf, die Media-Steuerung) – also nicht an die Stelle, die die
    // Brücke selbst gewählt hat? Dann gilt diese Stelle: sofort umschalten
    vid.addEventListener('seeking', () => {
      const b = st.bridge;
      // (vor dem ersten Abspielen springt die Kopie beim Laden selbst an den Anfangspunkt – das zählt nur, wenn es weit weg ist)
      if (!b || (typeof b.to === 'number' && Math.abs(vid.currentTime - b.to) < 0.5)) return;
      if (st.openStat.own || Math.abs(vid.currentTime - orig.currentTime) > 3) bridgeEnd('gespult');
    });
    vid.addEventListener('playing', () => { if (st.mode === 'own' && !st.openStat.own) st.openStat.own = performance.now(); });
    function bridgeSwap(d) {
      if (st.handed) { st.bridgeMuted = true; bridgeDrop(); return; }
      vid.playbackRate = st.speed;
      vid.muted = false;
      try { orig.muted = true; orig.pause(); } catch (e) {}
      st.openStat.swap = performance.now(); st.openStat.gap = d; st.openStat.how = 'nahtlos';
      bridgeDrop();
      dropPoster();
    }
    // sofort umschalten (wie früher): Standbild an der aktuellen Stelle, bis die eigene Kopie dort läuft
    function bridgeEnd(why) {
      const b = st.bridge;
      if (!b) return;
      if (st.handed) { st.bridgeMuted = true; bridgeDrop(); return; }
      const t = orig.currentTime;
      if (st.mode === 'own') {
        const moved = why === 'gespult' || (st.userSeekAt || 0) > b.at;   // du hast schon eine Stelle gewählt – die bleibt
        dropPoster(); if (!moved) showPoster();   // (Standbild der Seite nur, wenn es auch die Stelle ist, an der es weitergeht)
        try { orig.muted = true; orig.pause(); } catch (e) {}
        vid.muted = false; vid.playbackRate = st.speed;
        st.startTime = moved ? vid.currentTime : t;
        if (!moved && vid.readyState >= 1 && Math.abs(vid.currentTime - t) > 0.3) { try { vid.currentTime = t; } catch (e) {} }
        if (vid.readyState >= 3 && !vid.paused) dropPoster(); else spin.classList.add('on');   // (jetzt wartet man wirklich auf die Kopie)
      }
      st.openStat.how = 'umgeschaltet (' + why + ')'; st.openStat.swap = performance.now();
      bridgeDrop();
    }
    function bridgeDrop() {
      const b = st.bridge;
      if (!b) return;
      st.bridge = null;
      clearTimeout(b.tm); clearInterval(b.iv);
      try { b.el.srcObject = null; b.el.remove(); } catch (e) {}
      try { b.ms.getTracks().forEach((tr) => tr.stop()); } catch (e) {}
    }
    st.cleanup.push(bridgeDrop);
    // ----- Laden: Quellen der Reihe nach, zuletzt Bild vom Original übernehmen -----
    function tryNext() {
      clearTimeout(st.loadTimer);
      if (st.hls) { try { st.hls.destroy(); } catch (e) {} st.hls = null; }
      if (st.idx >= st.sources.length) { useCapture(); return; }
      const u = st.sources[st.idx++];
      st.cur = u;
      st.mode = 'own';
      if (st.setPipAllowed) st.setPipAllowed(true);
      kadriShield(false);
      if (!st.bridge) spin.classList.add('on');   // (nahtloser Start: das Bild läuft ja schon – kein Ladekreis)
      st.loadTimer = setTimeout(tryNext, 9000);   // lädt nicht? nächste Quelle
      try { vid.srcObject = null; vid.removeAttribute('src'); } catch (e) {}
      if (PLAYLIST_RE.test(u)) {
        // Kadri lädt hls.js erst jetzt nach (spart auf jeder Seite ~620 KB) – danach dieselbe Quelle noch einmal
        if (typeof Hls === 'undefined' && IS_KADRI && typeof KADRI_HOOKS !== 'undefined' && KADRI_HOOKS.loadHls && !st.hlsTried) {
          st.hlsTried = true; st.idx--;
          const again = () => { if (vp === st && st.cur === u && st.mode === 'own') tryNext(); };
          KADRI_HOOKS.loadHls().then(again, again);
          return;
        }
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
      // Gegenprobe beim Öffnen: Ist das Video gleich lang wie das auf der Seite? Sonst ist es ein anderes (z. B. das
      // vorige nach einem schnellen Wechsel) → gleich die nächste Quelle. Passt keine, zurück zur ersten (z. B. wenn die
      // Seite gerade Werbung zeigt). Nur einmal pro Öffnen, nie bei Live.
      if (!st.lenOk) {
        const od = orig.duration, vd = vid.duration;
        if (isFinite(od) && od > 1 && isFinite(vd) && vd > 1 && Math.abs(od - vd) > lenTol(od)) {
          if (st.capFallback) { st.lenOk = true; (st.fbBad || (st.fbBad = [])).push(st.cur); useCapture(); return; }   // Facebook: falsche Datei → spiegeln (und nicht als Vorschau)
          if (!st.lenFirst) st.lenFirst = st.cur;
          if (st.idx < st.sources.length) { tryNext(); return; }
          st.lenOk = true;
          if (st.lenFirst !== st.cur) { st.sources = [st.lenFirst].concat(st.sources.filter((x) => x !== st.lenFirst)); st.idx = 0; tryNext(); return; }
        }
        st.lenOk = true;
      }
      // (nahtloser Start: gleich an die Stelle, an der die Seite JETZT ist – etwas voraus, Springen dauert)
      const t0 = st.bridge ? orig.currentTime + 0.3 : st.startTime;
      if (!st.hls && t0 > 1 && isFinite(vid.duration) && vid.duration > t0) { if (st.bridge) st.bridge.to = t0; try { vid.currentTime = t0; } catch (e) {} }
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
      bridgeDrop();
      dropPoster();
      if (!st.openStat.swap) st.openStat.how = 'gespiegelt';
      let ms = null;
      try { ms = orig.captureStream ? orig.captureStream() : (orig.mozCaptureStream ? orig.mozCaptureStream() : null); } catch (e) {}
      if (!ms) { toast('Dieses Video lässt sich hier nicht im eigenen Player zeigen'); closePlayer(); return; }
      st.mode = 'capture';
      st.cur = '';
      // gespiegelt: Bild-in-Bild nur in Kadri mit Schild (sonst hielte die Seite ihr Video im Hintergrund an)
      kadriShield(true);
      if (st.setPipAllowed) st.setPipAllowed(kadriPage());
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
      if (st.prevWarm) setTimeout(() => { if (vp === st && st.mode === 'capture') st.prevWarm(); }, 1500);
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
    // dort hält die Seite ihr Video im Hintergrund an, das kleine Fenster stünde still. Dann gibt es weder den
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
    // zurück). Darum: Player hält an, und kommst du zurück, ist er zu – die Seite steht an deiner Stelle.
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
        it('Beim Öffnen', CONFIG.openUi === 'controls' ? 'Mit Bedienung' : 'Nur Bild', () => setSetting('openUi', CONFIG.openUi === 'controls' ? 'quiet' : 'controls')),
        it('Sprungweite', SKIP() + ' s', () => {
          next('skipSeconds', [5, 10, 15, 30]);
          [bFwd, bBack].forEach((b) => { const x = b.querySelector('text'); if (x) x.textContent = String(SKIP()); });
          [sfxL, sfxR].forEach((e) => { if (e.lab) e.lab.textContent = String(SKIP()); });
          bBack.setAttribute('aria-label', tr(SKIP() + ' s zurück')); bFwd.setAttribute('aria-label', tr(SKIP() + ' s vor'));
        }),
        it('Spul-Vorschau', CONFIG.seekPreview === 'thumb' ? 'Kleines Bild' : 'Im Bild', () => setSetting('seekPreview', CONFIG.seekPreview === 'thumb' ? 'live' : 'thumb')),
        it('Spul-Lupe', yn(CONFIG.seekZoom !== false), () => setSetting('seekZoom', CONFIG.seekZoom === false)),
        it('Vibration', yn(CONFIG.seekBuzz !== false), () => setSetting('seekBuzz', CONFIG.seekBuzz === false)),
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

    // ----- Sleep-Timer: nach 15–60 Minuten oder am Ende des Videos sanft leiser werden, dann anhalten -----
    st.sleep = null;   // { at: Zeitpunkt (ms) | 0 = am Ende des Videos, iv, fading }
    function sleepLabel() {
      const s = st.sleep;
      if (!s) return 'Aus';
      if (!s.at) return 'Ende des Videos';
      return 'noch ' + Math.max(1, Math.ceil((s.at - Date.now()) / 60000)) + ' min';
    }
    function sleepOff() {
      const s = st.sleep;
      if (!s) return;
      clearInterval(s.iv);
      if (s.fading) { try { VOL().volume = s.vol0; } catch (e) {} }
      st.sleep = null;
    }
    // Ausblenden über 8 s (Lautstärke des Videos), dann Pause – danach die alte Lautstärke wiederherstellen
    function sleepFire() {
      const s = st.sleep;
      if (!s || s.fading) return;
      clearInterval(s.iv);
      const m = VOL();
      s.fading = true; s.vol0 = m.volume;
      const t0 = Date.now(), D = 8000;
      s.iv = setInterval(() => {
        if (vp !== st) { clearInterval(s.iv); return; }
        const k = Math.min(1, (Date.now() - t0) / D);
        try { m.volume = s.vol0 * (1 - k); } catch (e) {}
        if (k >= 1) {
          clearInterval(s.iv);
          st.userPauseAt = Date.now();   // gewollt (Sleep-Timer) – auch im kleinen Fenster nicht wieder anwerfen
          try { M().pause(); } catch (e) {}
          try { m.volume = s.vol0; } catch (e) {}
          st.sleep = null;
          toast('Sleep-Timer: angehalten');
        }
      }, 100);
    }
    function sleepSet(min) {
      sleepOff();
      if (min == null) { toast('Sleep-Timer aus'); return; }
      st.sleep = { at: min ? Date.now() + min * 60000 : 0, iv: 0, fading: false };
      if (min) st.sleep.iv = setInterval(() => { if (vp !== st) { sleepOff(); return; } if (Date.now() >= st.sleep.at) sleepFire(); }, 1000);
      toast(min ? 'Sleep-Timer: ' + min + ' min' : 'Sleep-Timer: Ende des Videos');
    }
    st.cleanup.push(sleepOff);
    // am Ende des Videos (eigener Player oder gespiegelt): true = Timer hat zugeschlagen → nicht wiederholen
    st.sleepAtEnd = () => {
      if (!st.sleep || st.sleep.at) return false;
      sleepOff(); st.sleepHit = true;
      st.userPauseAt = Date.now();
      try { M().pause(); } catch (e) {}
      toast('Sleep-Timer: angehalten');
      return true;
    };
    function openSleep() {
      const cur = st.sleep ? (st.sleep.at ? -1 : 0) : null;
      const opt = (label, min, isCur) => ({ label: label, cur: isCur, fn: () => sleepSet(min) });
      openMenu('Sleep-Timer', [
        opt('Aus', null, cur === null),
        opt('15 min', 15, false), opt('30 min', 30, false), opt('45 min', 45, false), opt('60 min', 60, false),
        opt('Ende des Videos', 0, cur === 0),
      ].concat(cur === -1 ? [{ label: 'Läuft', sub: sleepLabel(), cur: true, stay: true, fn: () => {} }] : []), false, openMore);
    }

    // ----- Dialog-Verstärker: Stimmen deutlicher, laute Stellen gezähmt (wie „Sprache verbessern“ bei Fernsehern) -----
    // Tiefen unter 120 Hz leiser, Sprachbereich (1–4 kHz) angehoben, Kompressor gleicht Laut und Leise an.
    // Geht nur, wenn der Player den Ton bearbeiten darf (eigene Streams, Dateien derselben Seite) – sonst wäre er stumm.
    st.dlg = null;
    function audioGraph() {
      if (st.srcNode) return true;
      if (!canBoost()) return false;
      try {
        st.srcNode = st.actx.createMediaElementSource(vid);
        st.gain = st.actx.createGain();
        st.srcNode.connect(st.gain);
        st.gain.connect(st.actx.destination);
        if (st.actx.state === 'suspended') st.actx.resume().catch(() => {});
        return true;
      } catch (e) { st.srcNode = null; st.gain = null; return false; }
    }
    function setDialog(on) {
      if (on && !audioGraph()) return false;
      if (!st.srcNode) return !on;
      const a = st.actx;
      try { st.srcNode.disconnect(); } catch (e) {}
      if (st.dlg) { st.dlg.forEach((n) => { try { n.disconnect(); } catch (e) {} }); st.dlg = null; }
      if (on) {
        const hp = a.createBiquadFilter(); hp.type = 'highpass'; hp.frequency.value = 120; hp.Q.value = 0.7;
        const lo = a.createBiquadFilter(); lo.type = 'peaking'; lo.frequency.value = 300; lo.Q.value = 0.9; lo.gain.value = -2;
        const sp = a.createBiquadFilter(); sp.type = 'peaking'; sp.frequency.value = 2400; sp.Q.value = 0.8; sp.gain.value = 6;
        const cp = a.createDynamicsCompressor();
        cp.threshold.value = -26; cp.knee.value = 18; cp.ratio.value = 4; cp.attack.value = 0.004; cp.release.value = 0.25;
        const mk = a.createGain(); mk.gain.value = 1.5;   // Ausgleich: nach dem Kompressor wieder etwa gleich laut
        st.srcNode.connect(hp); hp.connect(lo); lo.connect(sp); sp.connect(cp); cp.connect(mk); mk.connect(st.gain);
        st.dlg = [hp, lo, sp, cp, mk];
      } else st.srcNode.connect(st.gain);
      return true;
    }
    // gespeicherte Einstellung beim Start anwenden, sobald der Ton bearbeitbar ist (eigene Quelle geladen)
    const dlgStart = () => { if (CONFIG.dialogBoost && !st.dlg && vp === st && st.mode === 'own') setDialog(true); };
    vid.addEventListener('playing', dlgStart);
    function toggleDialog() {
      const want = !CONFIG.dialogBoost;
      if (want && !setDialog(true)) { toast('Dialog-Verstärker: hier nicht möglich (die Seite gibt den Ton nicht frei)'); return; }
      if (!want) setDialog(false);
      setSetting('dialogBoost', want);
      toast(want ? 'Dialog-Verstärker an' : 'Dialog-Verstärker aus');
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
      msH('play', () => { const m = M(); if (st.heldAtEnd && st.heldAtEnd(m)) { try { m.currentTime = 0; } catch (e) {} } st.endHold = false; m.play().catch(() => {}); });
      msH('pause', () => { st.userPauseAt = Date.now(); M().pause(); });   // auch der Knopf im kleinen Fenster
      msH('stop', () => { st.userPauseAt = Date.now(); M().pause(); });
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

    // Fortschrittsbalken – Spulen auf der Leiste. Eigene, große Berührungsfläche statt des
    // Schiebereglers des Browsers. Vorbilder: YouTube (Punkt unter dem Finger) und iPhone (Finger nach unten = feiner,
    // in Stufen, wieder hoch = normal):
    //  - kurz antippen = an diese Stelle springen
    //  - ziehen = die Stelle (großer Punkt im Glas des Designs) steht unter dem Finger; die Leiste wird dick. Über der
    //    Vorschau steht, wie weit es von der laufenden Stelle weg ist („0:38 · +18 s“) – das Video läuft beim Spulen
    //    weiter. Kein Einrasten: der Punkt folgt immer nur dem Finger.
    //  - Lupe (nur bei Videos ab 1,5 min, auch bei Live, abschaltbar): Finger beim Ziehen unter die Leiste = „Lupe“
    //    (Ausschnitt ~1 min). Die Leiste zeigt dann nur den Ausschnitt, links/rechts stehen dessen Grenzen, im Schild
    //    „Lupe“, beim Wechsel ein kurzes Vibrieren (abschaltbar). Wieder hoch auf die Leiste = normal (die Stelle gleitet
    //    beim Weiterziehen wieder unter den Finger). Finger an ein Ende der Leiste = der Ausschnitt wandert mit.
    //  - weit nach oben weg vom Balken = abbrechen („Abbrechen“, keine kleine Vorschau – das große Video zeigt
    //    die laufende Stelle); loslassen dort = nichts ändern. Zurück nach unten = weiter spulen.
    //  - Live-Stream mit Zurückspulen: die Leiste ist der Bereich, den der Stream zurückhält; „−5:30“ = so weit hinter
    //    live, ganz rechts = wieder live.
    //  Sicher: Hochwischen ist kein Spulen (erst ~10 px zur Seite). Abgebrochen wird, wenn Android die Geste
    //  übernimmt, der Finger „verloren“ geht, 15 s lang nichts vom Finger kommt, du die App wechselst oder ein anderes
    //  Video zu laufen beginnt (Facebook lädt Reels neu, das nächste startet …). Gezeichnet wird einmal pro Bild.
    const barHit = h('div', { class: 'vbarhit', 'aria-hidden': 'true' });
    const barKnob = h('i', { class: 'vknob g' }, h('b'));
    bar.parentNode.append(barHit, barKnob);
    bar.tabIndex = -1;
    const scr = { on: false, id: 0, x0: 0, y0: 0, lx: 0, my: 0, mx: 0, raf: 0, t0: 0, t: 0, raw: 0, moved: false, first: true, mode: '',
      r: null, key: '', lv: 0, z: 0, ws: 0, zt: 0, zx: 0, off: 0, pan: 0, pt: 0, tk: 0, last: 0, wd: 0 };
    const buzz = (ms) => seekBuzz(ms || 12);
    // Abstand: „+12 s“, „−1:05“
    const delta = (x) => { const a = Math.round(Math.abs(x)); return (x < 0 ? '−' : '+') + (a < 60 ? a + ' s' : fmtTime(a)); };
    const barBack = () => {
      const m = M(), r = seekRange(m);
      if (!r) return;
      const p = clamp((m.currentTime - r.a) / r.len, 0, 1);
      bar.value = String(Math.round(p * 1000)); paintBar(p);
      tCur.textContent = r.live ? behind(r, m.currentTime) : fmtTime(m.currentTime);
    };
    const geom = () => { const br = bar.getBoundingClientRect(); return { br: br, L: br.left + 7, w: Math.max(60, br.width - 14) }; };
    // Ausschnitt der Lupe in Sekunden (0 = ganzer Bereich): Länge ÷ 6, 20–60 s. Nicht bei Videos unter 1,5 min.
    const lupeWhy = (r) => (CONFIG.seekZoom === false ? 'aus (Einstellung)' : r.len < 90 ? 'nein – ' + (r.live ? 'Live-Bereich' : 'Video') + ' kürzer als 1:30 (' + fmtTime(r.len) + ')' : 'ja');
    const spanFor = (lv, len) => (!lv || len < 90 || CONFIG.seekZoom === false ? 0 : clamp(len / 6, 20, 60));
    // Lupe an, wenn der Finger ein Stück unter der Leiste ist (Querformat: weniger Platz → näher), 8 px Spielraum
    function levelFor(dy) {
      const room = Math.max(60, innerHeight - scr.y0 - 8);
      const s1 = Math.min(42, room * 0.4), hy = 8;
      if (dy > s1 + (scr.lv ? -hy : hy)) return 1;
      return 0;
    }
    const posX = (p) => { const g = geom(), rr = bar.parentNode.getBoundingClientRect(); return g.L - rr.left + clamp(p, 0, 1) * g.w; };
    // Läuft noch dasselbe Video? (Quelle, Art, Länge – bei Live nur Quelle und Art)
    const vidKey = () => { const m = M(), r = seekRange(m); return st.mode + '|' + (m.currentSrc || '') + '|' + (r ? (r.live ? 'L' : Math.round(r.len)) : '-'); };
    const tlabel = (t) => (scr.r && scr.r.live ? behind(scr.r, t) : fmtTime(t));
    function scrubShow(t) {
      const r = scr.r;
      if (!r) return;
      let p = clamp((t - r.a) / r.len, 0, 1);
      if (scr.z) {   // Lupe: die Leiste zeigt den Ausschnitt [ws, ws + z]
        p = clamp((t - scr.ws) / scr.z, 0, 1);
        const P1 = (p * 100).toFixed(2);
        bar.style.setProperty('--trk', 'linear-gradient(to right,#000 ' + P1 + '%,rgba(0,0,0,.3) ' + P1 + '%)');   // (umgedreht per invert)
        tCur.textContent = r.live ? behind(r, scr.ws) : fmtTime(scr.ws);
        tDur.textContent = r.live ? behind(r, scr.ws + scr.z) : fmtTime(scr.ws + scr.z);
      } else {
        paintBar(p);
        tCur.textContent = r.live ? behind(r, t) : fmtTime(t);
        if (scr.on) tDur.textContent = r.live ? tr('LIVE') : fmtTime(r.b);
      }
      bar.value = String(Math.round(p * 1000));
      barKnob.style.left = Math.round(posX(p) - 17) + 'px';
      prevShow(p, t);
      wrap.classList.toggle('scrubx', scr.mode === 'cancel');
      wrap.classList.toggle('zoom', !!scr.z);
      if (scr.mode === 'cancel') pvTime.textContent = tr('Abbrechen');
      else if (scr.moved) {
        const tx = r.live ? behind(r, t) : tlabel(t) + ' · ' + delta(t - M().currentTime);
        pvTime.textContent = scr.lv ? tr('Lupe') + ' · ' + tx : tx;
      }
    }
    // Stufe wechseln: der Ausschnitt wird so gelegt, dass die aktuelle Stelle unter dem Finger bleibt
    function setLevel(lv) {
      const r = scr.r, z = spanFor(lv, r.len);
      if (lv === scr.lv || (lv && !z)) return;
      const g = geom(), f = clamp((scr.lx - g.L) / g.w, 0, 1);
      scr.lv = lv; scr.z = z;
      if (z) {
        scr.zt = scr.raw; scr.zx = scr.lx;
        scr.ws = clamp(scr.raw - f * z, r.a, Math.max(r.a, r.b - z));
        tCur.classList.add('g', 'vtz'); tDur.classList.add('g', 'vtz');
      } else {
        scr.off = scr.raw - (r.a + f * r.len);   // zurück zu „normal“: Abstand zum Finger, schrumpft beim Weiterziehen
        tCur.classList.remove('g', 'vtz'); tDur.classList.remove('g', 'vtz');
      }
      buzz();
    }
    // Stelle aus der Fingerposition: normal unter dem Finger, in der Lupe fein im Ausschnitt
    function scrubAt(x, dx) {
      const r = scr.r, g = geom();
      if (scr.z) scr.raw = clamp(scr.zt + (x - scr.zx) * scr.z / g.w, r.a, r.b);
      else {
        if (scr.off) { scr.off *= Math.exp(-Math.abs(dx || 0) / 20); if (Math.abs(scr.off) < 0.05) scr.off = 0; }
        scr.raw = clamp(r.a + clamp((x - g.L) / g.w, 0, 1) * r.len + scr.off, r.a, r.b);
      }
      scr.t = scr.raw;
      scrubShow(scr.t);
    }
    // Im Abbrechen-Bereich läuft die Anzeige mit dem Video mit (auch bei stillem Finger)
    function cancelTick() {
      clearInterval(scr.tk); scr.tk = 0;
      if (!scr.on || scr.mode !== 'cancel') return;
      scr.tk = setInterval(() => {
        if (!scr.on || scr.mode !== 'cancel') { clearInterval(scr.tk); scr.tk = 0; return; }
        scrubShow(M().currentTime);
      }, 250);
    }
    // Lupe am Ende der Leiste: der Ausschnitt wandert mit (je weiter am Rand, desto schneller)
    function panTick(ts) {
      scr.pan = 0;
      if (!scr.on || !scr.z || scr.mode === 'cancel') return;
      const g = geom(), E = 28, x = scr.lx, r = scr.r;
      const dep = x > g.L + g.w - E ? (x - (g.L + g.w - E)) / E : x < g.L + E ? -(g.L + E - x) / E : 0;
      if (!dep) return;
      const dt = scr.pt ? Math.min(50, ts - scr.pt) : 16;
      scr.pt = ts;
      const v = clamp(dep, -1.5, 1.5) * scr.z * 0.6 * dt / 1000;   // bis ~0,6 Ausschnitte pro Sekunde
      scr.zt += v;
      scr.ws = clamp(scr.ws + v, r.a, Math.max(r.a, r.b - scr.z));
      scrubAt(x, 0);
      scr.pan = requestAnimationFrame(panTick);
    }
    // Nach dem Loslassen: Lade-Kreis, bis das Video an der neuen Stelle steht; landet es deutlich woanders (manche
    // Streams), einmal neu versuchen
    function seekWatch(m, target) {
      let tries = 0;
      spin.classList.add('on');
      const fin = () => { m.removeEventListener('seeked', onSk); clearTimeout(tm); if (!m.seeking && m.readyState >= 3) spin.classList.remove('on'); };
      const onSk = () => {
        if (vp !== st || M() !== m) { fin(); return; }
        if (Math.abs(m.currentTime - target) > 3 && tries < 1) { tries++; st.userSeekAt = Date.now(); try { m.currentTime = target; } catch (e) {} return; }
        fin();
        if (m.readyState < 3) { const ok = () => { spin.classList.remove('on'); }; m.addEventListener('canplay', ok, { once: true }); setTimeout(ok, 8000); }
      };
      m.addEventListener('seeked', onSk);
      const tm = setTimeout(() => { m.removeEventListener('seeked', onSk); if (!m.seeking) spin.classList.remove('on'); }, 10000);
    }
    function scrubEnd(how) {   // how: 'seek' | 'tap' | 'cancel'
      if (!scr.on) return;
      scr.on = false;
      if (scr.pan) { cancelAnimationFrame(scr.pan); scr.pan = 0; }
      if (scr.raf) { cancelAnimationFrame(scr.raf); scr.raf = 0; }
      clearInterval(scr.tk); scr.tk = 0; clearInterval(scr.wd); scr.wd = 0;
      offVisChange(onScrubVis);
      scr.z = 0; scr.lv = 0; scr.off = 0;
      wrap.classList.remove('thick', 'scrubx', 'zoom');
      tCur.classList.remove('g', 'vtz'); tDur.classList.remove('g', 'vtz');
      const m = M(), r = seekRange(m) || scr.r;
      uiLast.cur = ''; uiLast.dur = ''; tDur.textContent = r && !r.live ? fmtTime(r.b) : 'Live';   // Zeiten wieder normal
      if (how !== 'cancel' && vidKey() !== scr.key) { how = 'cancel'; showInd('Video hat gewechselt – Spulen abgebrochen'); }
      if (how === 'cancel' || !r) { liveEnd(true); barBack(); }
      else {
        liveEnd(false);
        st.userSeekAt = Date.now();
        // Live: ganz rechts = wieder live (Stream-Bibliothek kennt die beste Stelle)
        let to = r.live ? (scr.t >= liveEdge(r) - 8 ? Math.min(liveEdge(r), r.b - 3) : scr.t) : clamp(scr.t, 0, Math.max(0, r.b - 0.2));
        to = clamp(to, r.a, r.b);
        if (Math.abs(m.currentTime - to) > 0.25) {
          try { m.currentTime = to; } catch (e) {}
          if (!liveMode()) seekWatch(m, to);
        }
        tCur.textContent = r.live ? behind(r, to) : fmtTime(to);
      }
      st.barDrag = false;
      prevHide();
      scheduleHide();
    }
    function onScrubVis() { if (document.hidden && scr.on) scrubEnd('cancel'); }   // App gewechselt (Anruf, Hochwischen …)
    // eine Fingerbewegung verarbeiten (gesammelt, höchstens einmal pro Bild)
    function moveNow() {
      scr.raf = 0;
      if (!scr.on) return;
      if (vidKey() !== scr.key) { scrubEnd('cancel'); showInd('Video hat gewechselt – Spulen abgebrochen'); return; }
      const x = scr.mx, dy = scr.my - scr.y0, dx = x - scr.lx;
      scr.lx = x;
      if (!scr.moved) {
        if (Math.hypot(x - scr.x0, dy) < 8) return;
        scr.moved = true;
        wrap.classList.add('thick');
      }
      if (dy < -Math.max(70, geom().br.height * 1.5)) { const was = scr.mode; scr.mode = 'cancel'; scrubShow(M().currentTime); if (was !== 'cancel') { cancelTick(); buzz(20); } return; }
      scr.mode = '';
      if (scr.first && Math.abs(x - scr.x0) < 10 && dy < 30) { scrubShow(scr.t); return; }   // (noch) Hochwischen
      if (scr.first) { scr.first = false; scrubAt(x, 0); }   // erst unter den Finger, dann ggf. Lupe
      setLevel(levelFor(dy));
      scrubAt(x, dx);
      if (scr.z && !scr.pan) { scr.pt = 0; scr.pan = requestAnimationFrame(panTick); }
    }
    barHit.addEventListener('pointerdown', (e) => {
      stop(e);
      const m = M(), r = seekRange(m);
      if (!r || scr.on) return;
      try { barHit.setPointerCapture(e.pointerId); } catch (x) {}
      Object.assign(scr, { on: true, id: e.pointerId, x0: e.clientX, y0: e.clientY, lx: e.clientX, mx: e.clientX, my: e.clientY, t0: m.currentTime, t: m.currentTime,
        raw: m.currentTime, moved: false, first: true, mode: '', r: r, key: vidKey(), lv: 0, z: 0, off: 0, pt: 0, last: Date.now() });
      barKnob.style.left = Math.round(posX((m.currentTime - r.a) / r.len) - 17) + 'px';
      st.lupeWhy = lupeWhy(r);
      st.barDrag = true; clearTimeout(st.hideTimer);
      onVisChange(onScrubVis);
      // Notbremse: kommt 15 s lang gar nichts vom Finger (Loslassen verloren), sauber abbrechen
      clearInterval(scr.wd);
      scr.wd = setInterval(() => { if (!scr.on) { clearInterval(scr.wd); scr.wd = 0; return; } if (Date.now() - scr.last > 15000) scrubEnd('cancel'); }, 1000);
      if (!liveMode() && !r.live) { pv.preload = 'auto'; pvs.autoGrid = true; if (prevLoad() && pv.readyState >= 1) startGrid(); }
    });
    barHit.addEventListener('pointermove', (e) => {
      if (!scr.on || e.pointerId !== scr.id) return;
      scr.mx = e.clientX; scr.my = e.clientY; scr.last = Date.now();
      if (!scr.raf) scr.raf = requestAnimationFrame(moveNow);
    });
    barHit.addEventListener('pointerup', (e) => {
      if (!scr.on || e.pointerId !== scr.id) return;
      scr.mx = e.clientX; scr.my = e.clientY;
      if (scr.raf) { cancelAnimationFrame(scr.raf); moveNow(); }   // letzte Bewegung noch verarbeiten
      if (!scr.on) return;
      if (!scr.moved) {   // kurz angetippt: an diese Stelle
        const g = geom(), r = scr.r;
        scr.t = r.a + clamp((e.clientX - g.L) / g.w, 0, 1) * r.len;
        scrubShow(scr.t);
        scrubEnd('tap');
        return;
      }
      scrubEnd(scr.mode === 'cancel' ? 'cancel' : 'seek');
    });
    barHit.addEventListener('pointercancel', (e) => { if (scr.on && e.pointerId === scr.id) scrubEnd('cancel'); });   // Android hat die Geste übernommen
    // Finger „verloren“ (Benachrichtigung, Systemgeste …) ohne Loslassen – nach einem echten Loslassen ist scr.on schon aus
    barHit.addEventListener('lostpointercapture', (e) => { if (scr.on && e.pointerId === scr.id) scrubEnd('cancel'); });
    barHit.addEventListener('contextmenu', (e) => e.preventDefault());   // langes Drücken: kein Menü
    barHit.addEventListener('click', stop);

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
    const pvTime = h('b', { class: 'g' }, '0:00');   // im Glas des Designs (Glas/Schlicht)
    const prev = h('div', { class: 'vprev', 'aria-hidden': 'true' }, pvBox, pvTime);
    wrap.append(prev);
    const pvs = { key: '', src: '', hls: null, want: -1, lastWant: -1, busy: false, kind: '', grid: [], gridOn: false, autoGrid: false, userDone: false };
    const thumbs = new Map();   // Zeit-Fach -> { c: kleines Bild, t: genaue Zeit }
    let bucket = 0;             // Fachbreite in Sekunden (≈ Länge / 300), einmal pro Video festgelegt
    const bucketFor = () => {
      if (!bucket) { const d = isFinite(M().duration) && M().duration > 0 ? M().duration : pv.duration; if (isFinite(d) && d > 0) bucket = Math.max(0.5, d / 300); }
      return bucket || 1;
    };
    function prevReset() {
      if (pvs.hls) { try { pvs.hls.destroy(); } catch (e) {} }
      pvs.hls = null; pvs.src = ''; pvs.key = ''; pvs.want = -1; pvs.lastWant = -1; pvs.busy = false; pvs.grid = []; pvs.gridOn = false; pvs.userDone = false;
      pvs.reloaded = false; pvs.loadAt = 0;
      clearInterval(pvs.wd); clearTimeout(pvs.settle); pvs.target = -1; pvs.retried = -1;
      // gemerkte Bilder gehören zum Video, nicht zur Quelle der Vorschau – sie bleiben (auch bei anderer Qualität)
      prev.classList.remove('img', 'live');
      try { pv.removeAttribute('src'); pv.load(); } catch (e) {}
    }
    st.cleanup.push(prevReset);
    st.cleanup.push(() => { thumbs.clear(); bucket = 0; });
    // Kleinste Qualität desselben Videos (lädt viel schneller); Streams nutzen ohnehin die kleinste Stufe
    function previewSrc(key) {
      if (st.mode !== 'own') return key;
      if (st.hls) return st.cur;
      const curRes = st.srcRes[st.cur] || 0;
      const low = st.sources.filter((u) => (st.srcRes[u] || 0) >= 144).sort((x, y) => st.srcRes[x] - st.srcRes[y])[0];
      return low && curRes && st.srcRes[low] < curRes ? low : st.cur;
    }
    // Gespiegelt (Facebook in Kadri): Facebook zeigt das Video schärfer als die fertige MP4 – deshalb spiegelt
    // Kadri. Die MP4 desselben Videos (sicher erkannt, gleiche Video-ID) taugt aber für das kleine Vorschaubild.
    // Passt ihre Länge nicht, wird sie nicht genommen (dann nur die Zeit). Neu geprüft höchstens alle 1,5 s.
    let fbPrevAt = 0;
    function fbPrevSrc() {
      if (!FB_TRY_OWN || st.mode !== 'capture') return '';
      const bad = st.fbBad || [];
      if (Date.now() - fbPrevAt > 1500) {
        fbPrevAt = Date.now();
        let u = fbSureSources(orig, null, true).filter((x) => bad.indexOf(x) < 0)[0];
        if (u) st.fbGuess = '';
        else if (!st.fbPrev || st.fbPrev === st.fbGuess) { u = fbGuessSrc(bad); if (u) st.fbGuess = u; }
        if (u) st.fbPrev = u;
      }
      return st.fbPrev && bad.indexOf(st.fbPrev) < 0 ? st.fbPrev : '';
    }
    // Noch unsicher, welches Video läuft (z. B. Facebook-Startseite: die Stücke wurden schon vor dem Öffnen geladen)?
    // Die Datei aus dem Beitrag rund um das Video nehmen – nur wenn dort genau EIN Video steckt. Ob sie wirklich passt,
    // prüft danach die Länge (auf Bruchteile einer Sekunde genau); passt sie nicht, bleibt es bei der Zeit.
    function fbGuessSrc(bad) {
      const keep = lastNear;
      let c = [];
      try { if (localNear(orig)) c = lastNear.filter((x) => x.kind === 'full' && x.asset && /^https?:/i.test(x.u) && bad.indexOf(x.u) < 0); } catch (e) {}
      lastNear = keep;   // nur nachgesehen – die Diagnose bleibt, wie sie war
      if (!c.length || new Set(c.map((x) => x.asset)).size !== 1) return '';
      return c.map((x) => x.u).sort((a, b) => (fbRes(a) || 1e4) - (fbRes(b) || 1e4))[0];
    }
    function prevLoad() {
      const key = st.mode === 'own' ? st.cur : fbPrevSrc();
      if (!key) { prev.classList.add('noimg'); st.prevWhy = st.mode === 'own' ? 'keine Quelle' : 'nur Zeit (gespiegelt, keine passende Datei)'; return false; }   // gespiegelt ohne passende Datei: keine eigene Quelle → nur die Zeit
      if (pvs.key === key) return !prev.classList.contains('noimg');
      prev.classList.remove('noimg');
      // gespiegelt und ein anderes Video (Facebook spielt schon das nächste Reel)? Gemerkte Bilder gehören zum alten
      if (st.mode !== 'own' && pvs.key && assetOf(pvs.key) !== assetOf(key)) { thumbs.clear(); bucket = 0; }
      prevReset();
      pvs.key = key;
      const u = previewSrc(key);
      pvs.src = u;
      if (st.hls && typeof Hls !== 'undefined') {
        const hp = new Hls({ enableWorker: false, startLevel: 0, maxBufferLength: 2, maxMaxBufferLength: 4, backBufferLength: 0 });
        pvs.hls = hp;
        hp.on(Hls.Events.MANIFEST_PARSED, () => { try { hp.currentLevel = 0; hp.autoLevelEnabled = false; } catch (e) {} });   // kleinste Stufe
        hp.on(Hls.Events.ERROR, (ev, d) => { if (d && d.fatal) { prev.classList.add('noimg'); st.prevWhy = 'nur Zeit (Stream-Fehler der Vorschau)'; } });
        hp.loadSource(u);
        hp.attachMedia(pv);
      } else {
        pv.src = u;
        pvs.loadAt = Date.now();
      }
      return true;
    }
    function thumbDims(v) {
      const vw = v.videoWidth || 16, vh = v.videoHeight || 9;
      return [176, Math.max(40, Math.min(320, Math.round(176 * vh / vw)))];
    }
    // aktuelles Bild eines Videos merken (Vorschau-Video oder – kostenlos – das laufende Video selbst)
    function snap(v) {
      v = v || pv;
      if (!(v.videoWidth > 0)) return null;
      const tt = v.currentTime;
      const d = thumbDims(v);
      const c = document.createElement('canvas');
      c.width = d[0]; c.height = d[1];
      try { c.getContext('2d').drawImage(v, 0, 0, d[0], d[1]); } catch (e) { return null; }
      const k = Math.round(tt / bucketFor());
      thumbs.delete(k); thumbs.set(k, { c: c, t: tt });
      // höchstens ~12 MB Bilder (je Bild Breite × Höhe × 4 Byte – Hochkant-Videos haben größere Bilder, also weniger davon)
      const most = Math.max(48, Math.min(180, Math.floor(12e6 / (d[0] * d[1] * 4))));
      while (thumbs.size > most) thumbs.delete(thumbs.keys().next().value);
      prev.dataset.n = String(thumbs.size);   // (für Diagnose/Tests: so viele Bilder gemerkt)
      return c;
    }
    // Beim Anschauen nebenbei Bilder merken (je Fach eins, höchstens jede Sekunde): Gesehenes erscheint beim Spulen sofort
    let mainSnapAt = 0;
    const mainSnap = () => {
      if (FF_MOBILE || st.mode !== 'own' || vid.readyState < 2 || vid.seeking || prev.classList.contains('on')) return;
      const now = Date.now();
      if (now - mainSnapAt < 1000 || thumbs.has(Math.round(vid.currentTime / bucketFor()))) return;
      mainSnapAt = now;
      // erst, wenn der Browser gerade nichts zu tun hat (kein Ruckeln mitten in einer Bewegung)
      if (window.requestIdleCallback) requestIdleCallback(() => { if (vp === st && !vid.seeking) snap(vid); }, { timeout: 1500 });
      else snap(vid);
    };
    vid.addEventListener('timeupdate', mainSnap);
    vid.addEventListener('seeked', () => setTimeout(mainSnap, 120));
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
      st.prevWhy = st.mode === 'own' ? 'Bilder' : st.fbGuess && st.fbPrev === st.fbGuess ? 'Bilder aus der MP4 des Beitrags (gespiegelt, Länge passt)' : 'Bilder aus der MP4 (gespiegelt)';
      if (pvs.t1st && !pvs.got) { pvs.got = true; st.prevFirst = Date.now() - pvs.t1st; }   // (Diagnose: wie lange bis zum ersten Bild)
      staleCheck();
    }
    // gezeigtes Bild weit weg von der Fingerstelle (das richtige lädt noch)? → abgedunkelt
    function staleCheck() {
      prev.classList.toggle('stale', pvs.shownT != null && pvs.lastWant >= 0 && Math.abs(pvs.shownT - pvs.lastWant) > Math.max(1.5, bucketFor() * 1.5));
    }
    function nearest(t) {
      const k = Math.round(t / bucketFor());
      for (let i = 0; i <= 8; i++) {
        if (thumbs.has(k + i)) { const x = thumbs.get(k + i); return [x.c, x.t]; }
        if (i && thumbs.has(k - i)) { const x = thumbs.get(k - i); return [x.c, x.t]; }
      }
      return null;
    }
    // Einen Sprung des Vorschau-Videos starten (bricht einen laufenden ab – der Browser meldet nur den letzten)
    function seekTo(t, kind) {
      pvs.busy = true; pvs.kind = kind; pvs.target = t; pvs.since = Date.now();
      try { pv.currentTime = t; } catch (e) { pvs.busy = false; return; }
      // Wächter: Ist der Sprung fertig, aber die Meldung („seeked“) kam nicht an (kommt bei Streams vor), selbst
      // weitermachen. Ein langsamer Sprung (schwaches Netz, große Datei) wird NICHT neu gestartet – das würde ihn nie
      // fertig werden lassen; erst nach 6 s ein einziger neuer Versuch.
      clearInterval(pvs.wd);
      pvs.wd = setInterval(() => {
        if (!pvs.busy) { clearInterval(pvs.wd); return; }
        if (!pv.seeking && pv.readyState >= 2 && Date.now() - pvs.since > 400) { clearInterval(pvs.wd); onSeeked(); return; }
        if (Date.now() - pvs.since > 6000) {
          clearInterval(pvs.wd); pvs.busy = false;
          if (pvs.retried !== pvs.target && pvs.kind === 'user' && prev.classList.contains('on')) { pvs.retried = pvs.target; pvs.want = pvs.target; }
          pump();
        }
      }, 400);
    }
    // Reihenfolge: zuerst die Fingerstelle, in Pausen das Raster – beim Spulen das Rasterbild nahe am Finger zuerst
    function pump() {
      if (pvs.busy || pv.readyState < 1) return;
      let t = -1, kind = '';
      if (pvs.want >= 0) { t = pvs.want; kind = 'user'; pvs.want = -1; }
      else {
        if (prev.classList.contains('on') && pvs.lastWant >= 0 && pvs.grid.length > 1) {
          pvs.grid.sort((a, b) => Math.abs(a - pvs.lastWant) - Math.abs(b - pvs.lastWant));
        }
        while (pvs.grid.length && t < 0) { const g = pvs.grid.shift(); if (!thumbs.has(Math.round(g / bucketFor()))) { t = g; kind = 'grid'; } }
      }
      if (t < 0) return;
      seekTo(t, kind);
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
      // Finger hat Vorrang: ein Rasterbild, das gerade lädt, wird abgebrochen (kommt später wieder dran)
      if (pvs.busy && pvs.kind === 'grid') { pvs.grid.push(pvs.target); pvs.want = -1; seekTo(t, 'user'); return; }
      pump();
      // Finger steht still, aber das Vorschau-Video lädt noch eine alte Stelle → gleich zur neuen springen
      clearTimeout(pvs.settle);
      pvs.settle = setTimeout(() => {
        if (pvs.busy && pvs.want >= 0 && Math.abs(pvs.target - pvs.want) > bucketFor() * 1.5) { const w = pvs.want; pvs.want = -1; seekTo(w, 'user'); }
      }, 160);
    }
    pv.addEventListener('loadedmetadata', () => {
      pvs.busy = false;
      // Kleinere Qualität gewählt, aber andere Länge? Dann lieber die laufende Quelle nehmen
      const md = M().duration;
      const guess = st.mode !== 'own' && !!st.fbGuess && pvs.src === previewSrc(st.fbGuess);   // Datei aus dem Beitrag: genauer prüfen
      const off = isFinite(md) && isFinite(pv.duration) && Math.abs(pv.duration - md) > (guess ? 0.6 : 3);
      if (st.mode !== 'own') {   // gespiegelt: Datei passt nicht zum Video → nie wieder nehmen, nur die Zeit zeigen
        if (off || !isFinite(md)) {
          st.prevWhy = isFinite(md) ? 'nur Zeit (Datei ' + Math.round(pv.duration) + ' s, Video ' + Math.round(md) + ' s – anderes Video)' : 'nur Zeit (Länge des Videos unbekannt)';
          (st.fbBad || (st.fbBad = [])).push(pvs.src); const k = pvs.key; prevReset(); pvs.key = k; prev.classList.add('noimg'); prev.classList.remove('img'); return;
        }
      } else if (pvs.src !== st.cur && off) {
        const key = pvs.key; pvs.src = st.cur; pv.src = st.cur; pvs.key = key; return;
      }
      bucketFor();
      if (pvs.autoGrid) startGrid();
      pump();
    });
    pv.addEventListener('seeked', () => { if (pvs.busy) onSeeked(); });
    function onSeeked() {
      pvs.busy = false; clearInterval(pvs.wd);
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
    }
    // Ladefehler: war es eine kleinere Qualität, die laufende Quelle nehmen (einmal); sonst nur die Zeit
    // einmal neu laden (gleiche Quelle) – Server stockt beim ersten Mal manchmal, wenn der Player gerade selbst lädt
    function pvReload() {
      if (pvs.reloaded || !pvs.key || !pvs.src || pvs.hls) return false;
      pvs.reloaded = true;
      const k = pvs.key, s = pvs.src;
      setTimeout(() => { if (pvs.key !== k) return; try { pv.removeAttribute('src'); pv.load(); } catch (e) {} pvs.loadAt = Date.now(); pv.src = s; }, 800);
      return true;
    }
    pv.addEventListener('error', () => {
      if (!pvs.key) return;   // (Quelle wurde absichtlich entfernt)
      if (st.mode === 'own' && pvs.src && st.cur && pvs.src !== st.cur && !st.hls) { const k = pvs.key; pvs.src = st.cur; pv.src = st.cur; pvs.key = k; return; }
      if (pvReload()) return;
      prev.classList.add('noimg');
      st.prevWhy = 'nur Zeit (Ladefehler ' + ((pv.error && pv.error.code) || '?') + ')';
    });
    // Streams: Raster gleich nach dem Start vorholen (kleinste Stufe, wenig Daten)
    // Vorwärmen nach dem Start: Streams holen gleich das Raster (kleinste Stufe, wenig Daten),
    // Dateien nur ihr Inhaltsverzeichnis (wenige KB) – dann wartet auch das erste Ziehen nicht darauf
    st.prevWarm = () => {
      if (liveMode()) return;   // Spulen im Bild: kein Vorschau-Video nötig (spart Daten)
      if (st.mode === 'capture') { if (fbPrevSrc()) { pv.preload = 'metadata'; prevLoad(); } return; }   // Länge gleich prüfen
      if (st.mode !== 'own' || !st.cur) return;
      if (st.hls) {
        let save = false;
        try { save = !!(navigator.connection && navigator.connection.saveData); } catch (e) {}
        if (save) return;   // Datensparmodus: Raster erst, wenn wirklich gespult wird
        pvs.autoGrid = true; pvs.userDone = true; prevLoad(); if (pv.readyState >= 1) startGrid();
      }
      else {
        // Dateien: das Inhaltsverzeichnis (wenige KB). Im WLAN, wenn das Video ein paar Sekunden ruhig läuft, dazu
        // 8 Bilder über die Länge verteilt (wenige MB) – so zeigt schon das erste Spulen überall ein Bild. (Das ganze
        // Raster gleich zu holen belegte bei großen Dateien das Netz – das erste Spulen musste dann warten.)
        pv.preload = 'metadata';
        prevLoad();
        let wifi = false;
        try { const c = navigator.connection; wifi = !!c && !c.saveData && (c.type === 'wifi' || c.type === 'ethernet'); } catch (e) {}
        if (wifi) setTimeout(() => {
          if (vp !== st || st.mode !== 'own' || vid.paused || vid.readyState < 3 || prev.classList.contains('on') || pvs.gridOn || pvs.grid.length) return;
          const d = pv.duration;
          if (!isFinite(d) || d <= 0 || pv.readyState < 1) return;
          for (let i = 0; i < 8; i++) pvs.grid.push((i + 0.5) * d / 8);
          pump();
        }, 3000);
      }
    };
    // ----- Spulen „im Bild“ (wie Samsung Internet) -----
    // Während du ziehst, springt das Video selbst an die Stelle – du siehst das echte Bild in voller Größe. Geht überall
    // gleich, auch beim Spiegeln (dann springt das Video der Seite), ohne zweite Kopie des Videos. Sicher: immer nur EIN
    // Sprung auf einmal; zieht der Finger weiter, wird danach nur die neueste Stelle angesprungen.
    const liveMode = () => CONFIG.seekPreview !== 'thumb';
    const live = { on: false, busy: false, want: -1, t0: 0, tm: 0 };
    // Bild-Ebene beim Spulen im Bild: Chrome auf Android zeichnet ein Video als eigene Ebene am Bildschirm vorbei.
    // Ändert sich darüber etwas (die großen Zahlen beim Wischen), erneuert es beim schnellen Springen manchmal nur das
    // Rechteck um die Zahlen – dort steht dann ein Bild einer anderen Stelle, wie ausgeschnitten. Darum liegt während
    // des Spulens eine gewöhnliche Ebene über dem Video, in die jedes neue Bild ganz hineingezeichnet wird – Zahlen
    // und Bild werden so immer gemeinsam und vollständig erneuert. Nach dem Loslassen weg, sobald das Video an der Stelle sein Bild zeigt. (Firefox-Handy kann
    // Videobilder nicht kopieren – dort ohne Ebene.)
    const layer = { cv: null, id: 0, sizes: new Set(), hideTm: 0 };
    function layerDraw() {
      const c = layer.cv;
      if (!c || !vid.videoWidth || !vid.videoHeight) return;
      const k = Math.min(1, 1280 / Math.max(vid.videoWidth, vid.videoHeight));
      const w = Math.round(vid.videoWidth * k), h = Math.round(vid.videoHeight * k);
      layer.sizes.add(vid.videoWidth + '×' + vid.videoHeight);
      try {
        if (c.width !== w || c.height !== h) { c.width = w; c.height = h; }
        c.getContext('2d').drawImage(vid, 0, 0, w, h);
        c.style.objectFit = vid.style.objectFit || 'contain';
        c.style.transform = vid.style.transform || '';
        c.style.visibility = 'visible';
      } catch (e) {}
    }
    function layerOn() {
      if (FF_MOBILE || !vid.videoWidth) return;
      clearTimeout(layer.hideTm);
      if (!layer.cv) {
        layer.cv = document.createElement('canvas');
        layer.cv.className = 'vposter vlayer';
        layer.cv.style.visibility = 'hidden';
      }
      if (layer.cv.previousSibling !== vid) vid.after(layer.cv);
      layerDraw();
      const id = ++layer.id;
      if (vid.requestVideoFrameCallback) {
        const cb = () => { if (id !== layer.id) return; layerDraw(); vid.requestVideoFrameCallback(cb); };
        vid.requestVideoFrameCallback(cb);
      }
    }
    function layerOff() {
      if (!layer.cv) return;
      const id = ++layer.id, c = layer.cv;
      // erst weg, wenn das Video selbst das Bild an der neuen Stelle zeigt (sonst blitzt kurz das alte auf)
      const gone = () => { if (id === layer.id && c.isConnected) c.remove(); };
      if (vid.requestVideoFrameCallback) vid.requestVideoFrameCallback(() => setTimeout(gone, 30));
      layer.hideTm = setTimeout(gone, 600);
      if (layer.sizes.size > 1) st.scrubSizes = Array.from(layer.sizes).join(', ');
      layer.sizes.clear();
    }
    vid.addEventListener('seeked', () => { if (live.on) layerDraw(); });
    st.cleanup.push(() => { layer.id++; if (layer.cv) layer.cv.remove(); });
    function liveTo(t) {
      const m = M();
      if (!live.on) { live.on = true; live.t0 = m.currentTime; layerOn(); }
      live.want = t;
      if (!live.busy) livePump();
    }
    function livePump() {
      const m = M();
      if (live.want < 0 || !live.on) { live.busy = false; return; }
      const t = live.want;
      live.want = -1; live.busy = true;
      st.userSeekAt = Date.now();
      const done = () => { m.removeEventListener('seeked', done); clearTimeout(live.tm); live.busy = false; if (live.want >= 0) livePump(); };
      m.addEventListener('seeked', done);
      live.tm = setTimeout(done, 1200);   // keine Meldung (kommt bei Streams vor)? trotzdem weiter
      try { m.currentTime = t; } catch (e) { done(); }
    }
    // Ende: back = zurück an die Stelle vor dem Ziehen (Hochwischen über die Leiste war kein Spulen)
    function liveEnd(back) {
      if (!live.on) return;
      live.on = false; live.want = -1;
      layerOff();
      if (back) { st.userSeekAt = Date.now(); try { M().currentTime = live.t0; } catch (e) {} }
    }
    st.liveTo = liveTo; st.liveEnd = liveEnd; st.liveMode = liveMode;
    // p: Stelle auf der Leiste (0…1, für den Platz der Vorschau) · tt: Zeit (mit Lupe nicht p × Länge)
    function prevShow(p, tt) {
      const d = M().duration, fin = isFinite(d) && d > 0;
      if (!fin && tt == null) return;
      const t = tt != null ? tt : p * d;
      pvTime.textContent = fmtTime(t);
      if (liveMode() && !wrap.classList.contains('scrubx')) liveTo(t);
      prev.classList.toggle('tonly', !fin);   // Live-Stream mit Zurückspulen: nur die Zeit (eigene Klasse – bleibt nicht hängen)
      const img = fin && !liveMode() && prevLoad() && !prev.classList.contains('noimg');
      if (img) {
        if (!pvs.t1st) { pvs.t1st = Date.now(); pvs.got = false; }
        // Vorschau-Video lädt seit über 5 s nicht einmal sein Inhaltsverzeichnis? Einmal neu anstoßen
        if (pv.readyState === 0 && pvs.loadAt && Date.now() - pvs.loadAt > 5000) pvReload();
        pvs.lastWant = t;
        const n = nearest(t);
        if (n && better(n[1])) paint(n[0], n[1]);   // sofort das nächstgelegene gemerkte Bild …
        staleCheck();
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
      prev.classList.toggle('timeonly', !img);   // ohne Bild (auch „Im Bild“): nur die Zeit, kein leerer/alter Kasten
      const x = br.left - wr.left + 7 + p * (br.width - 14);   // Mitte des Punkts
      prev.style.width = Math.round(pw) + 'px';
      prev.style.left = Math.round(clamp(x - pw / 2, 8, wr.width - pw - 8)) + 'px';
      prev.style.bottom = Math.round(wr.bottom - br.top + 6) + 'px';
      prev.classList.add('on');
      wrap.classList.add('scrub');   // Knöpfe darüber kurz ausblenden, damit die Vorschau frei steht
    }
    function prevHide() {
      // (Diagnose: Spulen ganz ohne Bild mitzählen)
      if (pvs.t1st && !pvs.got && !prev.classList.contains('noimg')) st.prevMiss = (st.prevMiss || 0) + 1;
      pvs.t1st = 0; pvs.got = false;
      prev.classList.remove('on', 'stale'); wrap.classList.remove('scrub');
    }

    // ----- Gesten (wie Just Player) -----
    const pts = new Map();
    let g = null, pinch = null, lastTapT = 0, lastZone = 9;
    gest.addEventListener('pointerdown', (e) => {
      stop(e);
      pts.set(e.pointerId, { x: e.clientX, y: e.clientY });
      try { gest.setPointerCapture(e.pointerId); } catch (err) {}
      if (pts.size === 2) {
        const a = Array.from(pts.values());
        if (g) clearTimeout(g.holdTimer);
        if (g && g.seekOn) seekAbort(g);   // zweiter Finger mitten im Wischen: Wischen abbrechen, alles wie vorher
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
        } else g.mode = Math.abs(dx) > Math.abs(dy) ? 'seek' : (g.top ? 'none' : (g.x > W / 2
          ? (HIDE_NO_HELPER && !(st.sys && st.sys.vol) ? 'none' : 'vol')            // Lautstärke nur mit Helfer (Kadri)
          : (HIDE_NO_HELPER && !(st.sys && st.sys.bright) ? 'none' : 'bright')));   // Helligkeit nur mit Helfer (Kadri)
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
        // Wischen übers Video (wie Samsung Internet): Das Video hält an, das große Bild springt an die Stelle unter dem
        // Wisch (immer nur ein Sprung auf einmal), die Zahlen zeigen genau den Sprung, der passiert. Loslassen: dort
        // weiter (lief es vorher, läuft es wieder). Zurück zum Ausgangspunkt („+0 s“) = nichts ändern.
        // Ganze Breite = 90 s; bei kurzen Videos die Länge (mindestens 20 s) – so geht es bei Reels nicht zu schnell
        const d = m.duration, fin = isFinite(d) && d > 0;
        if (!g.seekOn) {
          g.seekOn = true; g.wasPlaying = !m.paused; g.edgeHit = 0;
          st.swipeHold = true;
          if (g.wasPlaying) { try { m.pause(); } catch (err) {} }
        }
        const span = fin ? clamp(d, 20, 90) : 90;
        g.delta = dx / W * span;
        const to = fin ? clamp(g.time + g.delta, 0, Math.max(0, d - 0.5)) : Math.max(0, g.time + g.delta);
        const edge = fin && to <= 0 ? -1 : fin && to >= d - 0.5 ? 1 : 0;   // am Anfang/Ende: einmal kurz vibrieren
        if (edge && edge !== g.edgeHit) seekBuzz(10);
        g.edgeHit = edge;
        g.to = to;
        showSeek(to, d, to - g.time);
        if (st.liveTo) st.liveTo(to);   // das große Bild springt mit (das Video steht ja)
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
    // Wischen übers Video abbrechen (zweiter Finger …): zurück an den Ausgangspunkt, weiterlaufen wie vorher
    function seekAbort(cur) {
      hideSeek();
      if (st.liveEnd) st.liveEnd(true);
      st.swipeHold = false;
      if (cur.wasPlaying) M().play().catch(() => {});
    }
    st.cleanup.push(() => { st.swipeHold = false; });
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
        const to = cur.to != null ? cur.to : cur.time;
        // abbrechen: Android hat die Geste übernommen – oder zurück am Ausgangspunkt
        const cancel = e.type === 'pointercancel' || Math.abs(to - cur.time) < 0.5;
        if (st.liveEnd) st.liveEnd(cancel);
        if (!cancel) { st.userSeekAt = Date.now(); try { m.currentTime = to; } catch (err) {} }
        st.swipeHold = false;
        if (cur.wasPlaying) m.play().catch(() => {});
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
        seekBy(zone * SKIP());
        st.skipRun.total += SKIP(); st.skipRun.until = now + 650;
        showSkipFx(zone, st.skipRun.total);
        return;
      }
      if (now - lastTapT < 300 && zone === lastZone) {
        lastTapT = 0;
        // Doppeltipp: der erste Tipp hat die Leisten schon umgeschaltet – zurück auf den Stand davor
        if (wrap.classList.contains('ctl') !== st.tapCtl) { if (st.tapCtl) showControls(); else { wrap.classList.remove('ctl'); clearTimeout(st.hideTimer); } }
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
      // sofort umschalten (wie native Player) statt 280 ms auf einen möglichen zweiten Tipp zu warten. Frisch
      // eingeblendete Knöpfe nehmen 300 ms lang keine Tipps an – sonst träfe der zweite Tipp eines Doppeltipps Play.
      st.tapCtl = wrap.classList.contains('ctl');
      toggleControls();
      if (!st.tapCtl) { wrap.classList.add('ctlnew'); clearTimeout(st.ctlNewT); st.ctlNewT = setTimeout(() => wrap.classList.remove('ctlnew'), 320); }
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
    // ----- App verlassen (Wischgeste, Übersicht, andere App) und Bild-in-Bild -----
    // Android beendet beim Wechsel in eine andere App das Vollbild. Das ist KEIN „Zurück“: Der Player bleibt offen.
    // Erkennen: Die Seite verliert den Fokus oder wird verdeckt – kurz vor oder nach dem Ende des Vollbilds.
    let awayAt = 0;
    const markAway = () => { awayAt = Date.now(); };
    const awaySince = (t) => awayAt >= t - 800 || document.hidden || (!st.framed && !document.hasFocus());
    const pauseAway = () => { const m = M(); if (!m.paused) { st.pausedAway = true; try { m.pause(); } catch (e) {} } };
    const onVisAway = () => {
      if (vp !== st) return;
      // gerade an YTDLnis / eine andere App übergeben (Download): anhalten, solange die andere App vorne ist. Zurück im
      // Browser bleibt der Player offen (angehalten an der Stelle); das Vollbild holt er sich zurück, sobald der Browser
      // es erlaubt – sonst mit dem nächsten Antippen (auch ▶).
      if (document.hidden && st.dlAt && Date.now() - st.dlAt < 5000) {
        markAway(); st.dlAway = true;
        st.userPauseAt = Date.now();
        try { M().pause(); } catch (e) {}
        return;
      }
      if (!document.hidden && st.dlAway) { st.dlAway = false; st.dlAt = 0; st.dlCancel = false; }   // weiter wie nach einem App-Wechsel
      if (document.hidden) {
        markAway();
        if (pipIsOurs(vid)) return;   // läuft im kleinen Fenster weiter – genau dafür ist es da
        // kleines Fenster (Bild-in-Bild) gerade mit ✕ geschlossen → anhalten; sonst je nach Einstellung
        const pipClosed = st.pipLeaveAt && Date.now() - st.pipLeaveAt < 1500;
        if (pipClosed || CONFIG.leaveAction === 'pause') pauseAway();
      } else {
        markAway();
        // zurück im Browser: Vollbild verloren? Gleich selbst versuchen (manche Browser erlauben es beim Zurückkommen,
        // auch ein zweites Mal kurz danach) – sonst holt es die nächste Berührung zurück (Tippen, Wischen, Knopf).
        if (CONFIG.startFullscreen && !isFs() && !pipIsOurs(vid)) {
          st.fsLost = true;
          if (st.pushHist) st.pushHist();   // Zurück-Taste schließt den Player (statt die Seite zu verlassen)
          tryRegain();
          setTimeout(() => { if (vp === st && !isFs()) { showControls(); toast('Antippen für Vollbild'); } }, 900);
        }
      }
    };
    onVisChange(onVisAway);
    // Vollbild selbst zurückholen: sofort, nach 250 ms und nach 700 ms (und wenn das Fenster den Fokus bekommt) –
    // ohne Berührung erlaubt das nicht jeder Browser; dann klappt es mit der nächsten Berührung
    function tryRegain() {
      // nur, wenn der Browser es gerade erlauben würde (frische Berührung) – sonst nur eine Fehlermeldung in der Konsole
      [0, 250, 700].forEach((ms) => setTimeout(() => { if (vp === st && st.fsLost && !isFs() && !document.hidden && !pipIsOurs(vid) && touchFresh()) regainFs(); }, ms));
    }
    const onFocusBack = () => {
      if (vp !== st || document.hidden) return;
      const fromDl = st.dlAway; st.dlAway = false;
      if (!st.fsLost || isFs()) return;
      tryRegain();
      // (YTDLnis als Blatt über dem Browser: die Seite war nie verdeckt, nur ohne Fokus) – Bedienung + Hinweis
      if (fromDl) setTimeout(() => { if (vp === st && !isFs()) { showControls(); toast('Antippen für Vollbild'); } }, 900);
    };
    // Download an eine andere App, die nur als Blatt darüber aufgeht (Seite bleibt sichtbar): auch dann anhalten
    const onBlurDl = () => {
      if (vp !== st || document.hidden || !st.dlAt || Date.now() - st.dlAt > 5000) return;
      st.dlAway = true; st.userPauseAt = Date.now();
      try { M().pause(); } catch (e) {}
    };
    addEventListener('focus', onFocusBack);
    addEventListener('pageshow', onFocusBack);
    addEventListener('blur', onBlurDl);
    st.cleanup.push(() => { removeEventListener('focus', onFocusBack); removeEventListener('pageshow', onFocusBack); removeEventListener('blur', onBlurDl); });
    // Jede Berührung holt ein verlorenes Vollbild zurück – auch Wischen, Spulen, Zoomen (Tippen und Knöpfe tun es
    // schon selbst). Ausgelöst am Ende der Berührung: erst dann erlaubt der Browser das Vollbild.
    const onAnyTouch = (e) => {
      if (vp !== st || !st.fsLost || isFs() || !CONFIG.startFullscreen) return;
      if (e.type === 'pointerup' && e.pointerType === 'mouse') return;
      const path = e.composedPath ? e.composedPath() : [];
      if (path.some((el) => el && (el.tagName === 'BUTTON' || (el.classList && (el.classList.contains('vmenu') || el.classList.contains('vbar')))))) return;
      regainFs();
    };
    wrap.addEventListener('touchend', onAnyTouch, true);
    wrap.addEventListener('pointerup', onAnyTouch, true);
    addEventListener('blur', markAway);
    addEventListener('pagehide', markAway);
    st.cleanup.push(() => {
      offVisChange(onVisAway);
      removeEventListener('blur', markAway);
      removeEventListener('pagehide', markAway);
    });
    // Bild-in-Bild verlassen: ✕ (Seite bleibt im Hintergrund) = anhalten; „Vollbild“/zurück zur App = Player im Vollbild
    vid.addEventListener('leavepictureinpicture', () => {
      st.pipLeaveAt = Date.now();
      // „Vollbild“ im kleinen Fenster: sofort versuchen (der Browser erlaubt es oft nur in diesem Moment)
      if (CONFIG.startFullscreen && !isFs() && touchFresh()) { try { const pr = enterFs(); if (pr && pr.then) pr.then(() => { if (vp === st && isFs()) { st.fsStart = true; st.fsLost = false; if (st.orient) lockO(st.orient); } }); } catch (e) {} }
      setTimeout(() => {
        if (vp !== st) return;
        if (document.hidden) { st.userPauseAt = Date.now(); pauseAway(); return; }   // ✕ im kleinen Fenster = gewollt
        if (CONFIG.startFullscreen && !isFs()) {
          const done = () => {
            if (vp !== st) return;
            if (isFs()) { st.fsStart = true; st.fsLost = false; if (st.orient) lockO(st.orient); }
            else { st.fsLost = true; if (st.pushHist) st.pushHist(); showControls(); toast('Antippen für Vollbild'); }
          };
          if (touchFresh()) enterFs().then(done); else done();
        }
      }, 300);
    });
    // Browser, die von selbst ins kleine Fenster wechseln können (beim Verlassen der App), fragen über die Media Session
    try { if (navigator.mediaSession && document.pictureInPictureEnabled) navigator.mediaSession.setActionHandler('enterpictureinpicture', () => { if (!st.pipOk) return; vid.disablePictureInPicture = false; vid.requestPictureInPicture().catch(() => {}); }); } catch (e) {}
    st.cleanup.push(() => { try { navigator.mediaSession.setActionHandler('enterpictureinpicture', null); } catch (e) {} });
    // ----- Im kleinen Fenster weiterlaufen lassen -----
    // Beim Wischen zum Startbildschirm wechselt der Browser ins kleine Fenster. Kurz davor oder danach halten aber
    // manche Seiten (Facebook: ihr Video, sobald sie verdeckt ist – beim Spiegeln steht dann auch das kleine Fenster),
    // der Browser selbst oder „Beim Verlassen: Anhalten“ das Video an. Hält es etwas anderes als du (Knopf, Knopf im
    // kleinen Fenster, Sleep-Timer, Reel-Ende, ✕) rund um den Wechsel oder im kleinen Fenster an: gleich weiter.
    st.pipEnterAt = 0;
    let pipResumes = 0;
    const byUser = () => Date.now() - (st.userPauseAt || 0) < 800 || !!st.endHold;
    const inPip = () => pipIsOurs(vid) || (st.pipEnterAt && Date.now() - st.pipEnterAt < 2500 && !(st.pipLeaveAt > st.pipEnterAt));
    const resumePip = () => {
      if (vp !== st || !inPip() || st.lastPauseByUser || st.endHold || pipResumes >= 8) return;
      const m = M();
      if (!m.paused) return;
      pipResumes++; st.pausedAway = false;
      try { const pr = m.play(); if (pr && pr.catch) pr.catch(() => {}); } catch (e) {}
    };
    const onAnyPause = (e) => {
      if (vp !== st || e.target !== M()) return;
      st.lastPauseAt = Date.now(); st.lastPauseByUser = byUser();
      if (!st.lastPauseByUser && inPip()) setTimeout(resumePip, 120);
    };
    const onAnyPlay = (e) => { if (e.target === M()) st.lastPauseByUser = false; };
    vid.addEventListener('pause', onAnyPause); orig.addEventListener('pause', onAnyPause);
    vid.addEventListener('play', onAnyPlay); orig.addEventListener('play', onAnyPlay);
    st.cleanup.push(() => { orig.removeEventListener('pause', onAnyPause); orig.removeEventListener('play', onAnyPlay); });
    vid.addEventListener('enterpictureinpicture', () => {
      st.pipEnterAt = Date.now(); pipResumes = 0;
      // kurz vorher angehalten (Seite verdeckt, „Anhalten“-Einstellung …), aber nicht von dir: weiter im kleinen Fenster
      if (M().paused && !st.lastPauseByUser && Date.now() - (st.lastPauseAt || 0) < 3000) setTimeout(resumePip, 120);
    });
    const onFs = () => {
      if (vp !== st || isFs()) return;
      // Zurück-Taste bei offenem Menü (Einstellungen …): nur eine Ebene zurück, Player bleibt offen;
      // das Vollbild holt der nächste Tipp zurück (auch ein Tipp im Menü)
      if (menuOpen() && !document.hidden) {
        menuStepBack();
        st.fsStart = false; st.fsLost = true;
        if (st.pushHist) st.pushHist();
        return;
      }
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
      // Sieht es nach der Zurück-Taste aus (im Vollbild gestartet, nicht gedreht, Seite sichtbar)? Dann den Player
      // SOFORT unsichtbar und stumm machen – man sieht gleich die Seite (wie bei Samsung), statt kurz den Player ohne
      // Vollbild mit den Leisten des Browsers. Geprüft wird unsichtbar weiter; war es doch etwas anderes, kommt er zurück.
      const maybeBack = st.fsStart && !awaySince(exitAt) && (!!st.orient || st.framed || exitAt - turnBefore >= 1500);
      if (maybeBack) veil(true);
      // kurz abwarten: kommt das Vollbild gleich zurück oder war es eine Drehung?
      setTimeout(() => {
        if (vp !== st) return;
        if (isFs()) { veil(false); return; }
        if (pipNow()) { veil(false); st.fsStart = false; st.fsLost = true; return; }
        // (Eingebettetes Fenster: dessen Größe springt beim Vollbild-Ende immer – dort zählt nur die Zurück-Taste.)
        // Gedreht VOR dem Vollbild-Ende (oder praktisch gleichzeitig) = Drehung hat das Vollbild beendet.
        // Gedreht erst DANACH = Folge der Zurück-Taste (das Bild springt nach dem Vollbild zurück ins Hochformat).
        // Über den Drehen-Knopf gesperrt? Dann kann sich das Handy nicht selbst drehen –
        // ein Vollbild-Ende ist dann immer die Zurück-Taste (Chrome dreht dabei oft schon VOR der Meldung zurück).
        const locked = !!st.orient;
        const rotated = !st.framed && !locked && ((exitAt - turnBefore < 1500) || (lastTurn >= exitAt && lastTurn - exitAt < 120));
        if (rotated) {
          veil(false);
          // Vollbild durch Drehen verloren (Android oder die Webseite selbst): Player bleibt offen,
          // der nächste Tipp holt das Vollbild zurück. Zurück-Taste schließt ihn bis dahin über den Verlauf.
          st.fsStart = false;
          st.fsLost = true;
          if (st.pushHist) st.pushHist();   // ohne Vollbild: Zurück-Taste über den Verlauf abfangen (nicht auf Facebook/Instagram)
          return;
        }
        // In eine andere App gewechselt (Fokus weg/Seite verdeckt)? Dann kein „Zurück“ – Player bleibt offen,
        // beim Zurückkommen holt der nächste Tipp das Vollbild zurück
        const keep = () => { veil(false); st.fsStart = false; st.fsLost = true; if (st.pushHist) st.pushHist(); };
        if (awaySince(exitAt)) { keep(); return; }
        // Im Vollbild gestartet: Vollbild verlassen (Zurück-Taste) = Player schließen – nach einem kurzen zweiten Blick,
        // falls die Meldung „Seite verdeckt“ beim App-Wechsel etwas später kommt
        if (st.fsStart) {
          setTimeout(() => { if (vp !== st) return; if (isFs()) { veil(false); return; } if (awaySince(exitAt)) keep(); else closePlayer(); }, 350);
          return;
        }
        veil(false);
        lockO('');
        st.orient = '';
      }, 400);
    };
    // Player kurz unsichtbar und stumm (während geprüft wird, ob „Zurück“ gemeint war)
    function veil(on) {
      if (on === wrap.classList.contains('vgone')) return;
      // Ton des Players aus/an – vor der Übergabe (die gibt der Seite ihren Ton zurück)
      try {
        const m = VOL();
        if (on) { st.veilMuted = m.muted; if (m !== orig) m.muted = true; }
        else if (typeof st.veilMuted === 'boolean') { m.muted = st.veilMuted; st.veilMuted = null; }
      } catch (e) {}
      wrap.classList.toggle('vgone', on);
      // Seite übernimmt sofort (an deiner Stelle, mit Ton) – war es doch kein Zurück, wieder zurücknehmen
      if (on) handBack(st); else unHand(st);
    }
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
    const onPop = () => {
      if (vp !== st) return;
      st.histPushed = false;
      if (menuOpen()) { menuStepBack(); pushHist(); return; }   // Menü offen: nur zurück im Menü
      closePlayer();
    };
    addEventListener('popstate', onPop);
    st.cleanup.push(() => {
      document.removeEventListener('fullscreenchange', onFs);
      document.removeEventListener('webkitfullscreenchange', onFs);
      removeEventListener('popstate', onPop);
    });
    // Auf Wunsch (Einstellung startFullscreen) sofort im Vollbild starten
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
      const fresh = st.capFallback ? fbSureSources(orig, st.srcRes) : playerSources(orig, st.srcRes);   // Kadri/Facebook: nur passende
      fresh.forEach((u) => { if (st.sources.indexOf(u) < 0) st.sources.push(u); });
      refreshQual();
    };

    // Video auf der Seite stumm schalten und anhalten, solange der eigene Player läuft (sonst kurz doppelter Ton).
    // Startet die Seite es von selbst wieder (Facebook macht das manchmal), sofort wieder anhalten.
    st.origMuted = orig.muted;
    // Am Ende anhalten statt Endlosschleife: Facebook/Instagram springen bei Reels still an den Anfang zurück
    // (oder laden neu). Das erkennen wir und halten an – Play-Knopf startet es noch einmal.
    st.endHold = false;
    armNextGuard();   // Countdown „Nächstes Reel“ der Seite sofort abbrechen
    st.cleanup.push(() => disarmNextGuard(10000));   // noch 10 s – die Seite steht danach oft am Ende
    let lastT = 0;
    const origLoop = orig.loop;
    try { orig.loop = false; } catch (e) {}
    const holdEnd = () => {
      if (vp !== st || st.mode !== 'capture') return;
      if (st.repeatNow && st.repeatNow()) { if (!st.sleepHit) return; st.sleepHit = false; }   // Wiederholen: von vorn · Sleep-Timer: anhalten
      st.endHold = true;
      try { orig.pause(); } catch (e) {}
      showControls();
      updateUI();
    };
    // Reels-Seiten (Facebook u. a.) springen beim Ende von selbst zum nächsten Video – manche schon kurz VOR „ended“
    // (sobald die Zeit fast am Ende ist). Dann wäre der Player schwarz und man stünde danach auf einem anderen Reel.
    // Darum: 0,2 s vor dem Ende anhalten (letztes Bild bleibt stehen; das reicht, weil der Facebook-Countdown
    // abgebrochen wird) und – siehe „Schleuse“ unten – der Seite die
    // letzten Meldungen des Videos gar nicht erst zeigen. Nahe am Ende genauer hinschauen als „timeupdate“ (¼ s).
    const END_GAP = 0.2;
    let nearIv = 0;
    const stopNear = () => { clearInterval(nearIv); nearIv = 0; };
    const nearEnd = () => {
      if (vp !== st || st.mode !== 'capture' || st.endHold || orig.paused) { stopNear(); return; }
      const d = orig.duration, t = orig.currentTime;
      if (!isFinite(d) || d < 2) { stopNear(); return; }
      if (t >= d - END_GAP * Math.max(1, orig.playbackRate || 1)) { stopNear(); holdEnd(); }
      else if (t < d - 2) stopNear();
    };
    const onOrigTime = () => {
      if (vp !== st || st.mode !== 'capture') return;
      const d = orig.duration, t = orig.currentTime;
      const userSeek = Date.now() - (st.userSeekAt || 0) < 1500;
      if (!userSeek && isFinite(d) && d > 3 && lastT > d - 1.5 && t < 1.5) holdEnd();   // von hinten nach vorn gesprungen = Schleife
      if (!nearIv && !st.endHold && isFinite(d) && d >= 2 && t > d - 1.5 && !orig.paused) nearIv = setInterval(nearEnd, 40);
      lastT = t;
    };
    st.cleanup.push(stopNear);
    const onOrigEnded = () => holdEnd();
    orig.addEventListener('timeupdate', onOrigTime);
    orig.addEventListener('ended', onOrigEnded);
    st.cleanup.push(() => {
      orig.removeEventListener('timeupdate', onOrigTime);
      orig.removeEventListener('ended', onOrigEnded);
      try { orig.loop = origLoop; } catch (e) {}
    });
    const onOrigPlay = () => {
      if (vp !== st || st.handed) return;   // schon an die Seite übergeben (Zurück-Geste): die Seite darf spielen
      // eigener Player: Original bleibt still · gespiegelt und am Ende angehalten: die Seite darf nicht von selbst neu starten
      if ((st.mode === 'own' && !st.bridge) || (st.mode === 'capture' && st.endHold)) { try { orig.pause(); } catch (e) {} }
    };
    orig.addEventListener('play', onOrigPlay);
    orig.addEventListener('playing', onOrigPlay);
    st.cleanup.push(() => { orig.removeEventListener('play', onOrigPlay); orig.removeEventListener('playing', onOrigPlay); });
    // Schleuse (nur beim Spiegeln): Meldungen vom Ende des Videos erreichen die Seite nicht – „ended“ nie, „timeupdate“
    // und „pause“ in der letzten Sekunde nicht. Abgefangen wird ganz vorn (window, Einfangphase), also vor den
    // Zuhörern der Seite; die eigenen Zuhörer des Players werden hier direkt bedient.
    const nearTail = () => { const d = orig.duration; return isFinite(d) && d >= 2 && orig.currentTime > d - 1; };
    const gate = (e) => {
      if (e.target !== orig || vp !== st || st.mode !== 'capture') return;
      if (e.type === 'ended') { e.stopImmediatePropagation(); onOrigEnded(); updateUI(); return; }
      if (!nearTail()) return;
      e.stopImmediatePropagation();
      if (e.type === 'timeupdate') { onOrigTime(); updateUI(); }
      else if (e.type === 'pause') { showControls(); updateUI(); }
    };
    ['ended', 'timeupdate', 'pause'].forEach((t) => window.addEventListener(t, gate, true));
    st.cleanup.push(() => ['ended', 'timeupdate', 'pause'].forEach((t) => window.removeEventListener(t, gate, true)));

    // Original anhalten (außer wir übernehmen dessen Bild) und mit dem Laden beginnen
    // (alle Seiten: läuft das Video, zeigt der Player gleich sein laufendes Bild – nahtloser Start, siehe startBridge)
    if (st.sources.length) {
      showPoster();
      if (!startBridge()) { st.openStat.how = GECKO ? 'Standbild (Firefox)' : orig.paused ? 'Standbild (Video stand)' : 'Standbild'; try { orig.muted = true; orig.pause(); } catch (e) {} }
      tryNext();
    }
    else useCapture();
    requestAnimationFrame(() => { if (!st.openStat.vis) st.openStat.vis = performance.now(); });
    // Beim Öffnen: „Nur Bild“ (wie Samsung – ruhig, Bedienung erst nach dem ersten Antippen) oder gleich mit Bedienung.
    // Läuft das Video nach 2 s nicht (angehalten, wartet auf dich), kommt die Bedienung trotzdem.
    if (CONFIG.openUi === 'controls') showControls();
    else setTimeout(() => { if (vp === st && M().paused && !wrap.classList.contains('ctl')) showControls(); }, 2000);
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
  // ----- Reels: „Nächstes Reel in 3 … Abbrechen“ -----
  // Facebook zählt am Ende eines Reels selbst herunter und springt dann weiter – auch wenn das Video angehalten ist.
  // Solange der Player offen ist (und kurz danach), tippt das Skript sofort auf „Abbrechen“. Erkannt wird nur ein
  // kleiner Kasten mit Countdown (Reel/Video … Zahl) UND einem Abbrechen-Knopf – sonst wird nichts angetippt.
  const NEXT_RE = /(reel|video|clip)[^\n]{0,40}?\b\d{1,2}\s*(s|sek|sec)?\.?\s*$/i;   // „Next reel in 2“, „Nächstes Video in 3 s“
  const NEXT_WORD_RE = /(next|nächst|weiter|suivant|siguiente|próxim|prossim|następn|volgend|nästa|seuraava|sonraki|следующ|наступн|შემდეგ)|\bin\s+\d/i;
  const CANCEL_RE = /^(cancel|abbrechen|annuler|cancelar|annulla|anuluj|annuleren|avbryt|peruuta|iptal|zrušit|mégse|отмена|скасувати|취소|キャンセル|取消|إلغاء|ביטול|გაუქმება)$/i;
  let nextObs = null, nextOff = 0, nextQ = new Set(), nextT = 0, nextCancels = 0;
  function cancelIn(box) {
    if (!box || box.nodeType !== 1 || (hostEl && (box === hostEl || hostEl.contains(box)))) return false;   // nie im eigenen Player
    const txt = (box.textContent || '').trim();
    if (!txt || txt.length > 140) return false;
    const els = box.querySelectorAll('*');
    let btn = null;
    for (let i = 0; i < els.length && i < 60; i++) {
      const t = (els[i].textContent || '').trim();
      if (t && CANCEL_RE.test(t)) { btn = els[i].closest('[role="button"], button, a') || els[i]; break; }
    }
    if (!btn) return false;
    // Countdown-Text: der Rest ohne den Knopf
    const rest = txt.replace((btn.textContent || '').trim(), ' ').trim();
    if (!NEXT_RE.test(rest) || !NEXT_WORD_RE.test(rest)) return false;
    try { btn.click(); } catch (e) { return false; }
    nextCancels++;
    return true;
  }
  function nextScan() {
    nextT = 0;
    const q = Array.from(nextQ); nextQ.clear();
    for (const n of q) {
      let el = n.nodeType === 1 ? n : n.parentElement;
      // vom geänderten Teil bis zum kleinsten Kasten, der Countdown UND Knopf enthält
      for (let up = 0; el && up < 5; up++, el = el.parentElement) {
        const l = (el.textContent || '').length;
        if (l > 140) break;
        if (cancelIn(el)) return;
      }
    }
  }
  function armNextGuard() {
    clearTimeout(nextOff); nextOff = 0;
    if (nextObs || !document.body) return;
    try {
      nextObs = new MutationObserver((ms) => {
        for (const m of ms) {
          if (m.type === 'characterData') nextQ.add(m.target);
          else for (const a of m.addedNodes) if (a.nodeType === 1 || a.nodeType === 3) nextQ.add(a);
        }
        if (nextQ.size > 400) nextQ = new Set(Array.from(nextQ).slice(-200));
        if (!nextT && nextQ.size) nextT = setTimeout(nextScan, 50);
      });
      nextObs.observe(document.body, { childList: true, subtree: true, characterData: true });
    } catch (e) { nextObs = null; }
  }
  function disarmNextGuard(later) {
    clearTimeout(nextOff);
    nextOff = setTimeout(() => { if (vp) return; if (nextObs) nextObs.disconnect(); nextObs = null; nextQ.clear(); }, later || 0);
  }

  // ----- Übergabe an die Seite -----
  // Das Video der Seite springt an deine Stelle und läuft (mit Ton) weiter. Bis es dort ein Bild hat, liegt dein
  // letztes Bild genau darüber – sonst sähe man kurz den Anfang des Videos und dann einen Sprung.
  // Bei der Zurück-Geste passiert das schon in dem Moment, in dem sie erkannt wird (nicht erst nach der Prüfung).
  function handBack(st) {
    if (st.handed) return;
    st.handed = true;
    const o = st.orig;
    let target = -1, atEnd = !!st.endHold;
    // Deine Stelle ZUERST merken – wird hls.js beendet, springt das eigene Video auf 0 zurück.
    // Gleiche Länge prüfen, damit nicht z. B. eine Werbung oder ein anderes Video verstellt wird.
    try {
      const t = st.vid.currentTime, vd = st.vid.duration, od = o.duration;
      const sameLen = !isFinite(od) || !isFinite(vd) || Math.abs(od - vd) < 3;
      if (st.mode === 'own' && !st.bridge && t > 0 && sameLen) {   // (nahtloser Start noch nicht fertig: die Seite steht schon richtig)
        target = t;
        // am Ende: kurz VOR das Ende stellen und angehalten lassen – sonst springt eine Reels-Seite zum nächsten Video
        if (isFinite(vd) && (st.vid.ended || t >= vd - 1)) { atEnd = true; target = Math.max(0, (isFinite(od) ? od : vd) - 0.3); }
      }
    } catch (e) {}
    if (target >= 0 && Math.abs((o.currentTime || 0) - target) > 0.4) {
      coverOver(o, st.vid);
      try { o.currentTime = target; } catch (e) {}
    }
    try { if (typeof st.origMuted === 'boolean') o.muted = st.origMuted; } catch (e) {}   // ursprünglichen Ton zurückgeben
    // Video auf der Seite weiterlaufen lassen (Instagram & Co. haben oft keinen Play-Knopf)
    if (atEnd || st.closePaused) { try { o.pause(); } catch (e) {} }   // (nach einem Download: angehalten lassen)
    else if (CONFIG.resumeAfterClose && st.mode === 'own') {
      try { const pr = o.play(); if (pr && pr.catch) pr.catch(() => {}); } catch (e) {}
      watchStall(o);
    } else if (CONFIG.resumeAfterClose) {
      setTimeout(() => { try { const pr = o.play(); if (pr && pr.catch) pr.catch(() => {}); } catch (e) {} watchStall(o); }, 200);
    }
  }
  // doch nicht geschlossen (Drehen, App-Wechsel): Seite wieder still und angehalten, Player übernimmt wieder
  function unHand(st) {
    if (!st.handed) return;
    st.handed = false;
    removeCover();
    if (st.mode !== 'own') return;
    try { st.orig.muted = true; st.orig.pause(); } catch (e) {}
    // (der nahtlose Start war beim Übergeben noch nicht fertig: die eigene Kopie war stumm – jetzt hat sie den Ton)
    if (st.bridgeMuted) { st.bridgeMuted = false; try { st.vid.muted = false; st.vid.play().catch(() => {}); } catch (e) {} }
  }
  let coverEl = null;
  function coverOver(o, src) {
    removeCover();
    if (!root) return;
    // Firefox-Handy kann Videobilder nicht kopieren (wäre ein schwarzer Kasten): dann das Video der Seite
    // unsichtbar lassen, bis es an der richtigen Stelle ein Bild hat – lieber kurz dunkel als Anfang + Sprung
    const hideOrig = () => {
      const c = { _hid: o, _op: o.style.getPropertyValue('opacity'), _pr: o.style.getPropertyPriority('opacity'), remove() {}, style: {} };
      o.style.setProperty('opacity', '0', 'important');
      return c;
    };
    try {
      const r = o.getBoundingClientRect();
      if (FF_MOBILE || r.width < 60 || r.height < 40 || !src.videoWidth || !src.videoHeight || src.readyState < 2) {
        const c = hideOrig(); coverEl = c;
        const gone = () => removeCover(c);
        const onSeeked = () => { if (o.requestVideoFrameCallback) o.requestVideoFrameCallback(() => gone()); else setTimeout(gone, 120); };
        o.addEventListener('seeked', onSeeked, { once: true });
        c._off = () => o.removeEventListener('seeked', onSeeked);
        c._t = setTimeout(gone, 2500);
        return;
      }
      const c = document.createElement('canvas');
      c.className = 'evpcover';
      const dpr = Math.min(2, window.devicePixelRatio || 1);
      c.width = Math.round(r.width * dpr); c.height = Math.round(r.height * dpr);
      const x = c.getContext('2d');
      x.fillStyle = '#000'; x.fillRect(0, 0, c.width, c.height);
      const cov = getComputedStyle(o).objectFit === 'cover';
      const s = (cov ? Math.max : Math.min)(c.width / src.videoWidth, c.height / src.videoHeight);
      const w = src.videoWidth * s, hh = src.videoHeight * s;
      x.drawImage(src, (c.width - w) / 2, (c.height - hh) / 2, w, hh);
      const place = () => { const q = o.getBoundingClientRect(); c.style.cssText = 'position:fixed;left:' + q.left + 'px;top:' + q.top + 'px;width:' + q.width + 'px;height:' + q.height + 'px;z-index:2147483646;pointer-events:none;opacity:' + (c._out ? 0 : 1) + ';transition:opacity .15s ease'; };
      place();
      root.append(c);
      coverEl = c;
      // mitgehen, falls sich die Seite nach dem Vollbild noch zurechtrückt (Drehen, Leisten)
      c._place = place;
      addEventListener('resize', place); addEventListener('scroll', place, true);
      requestAnimationFrame(place);
      // weg, sobald die Seite an der neuen Stelle ein Bild zeigt – spätestens nach 3 s
      const gone = () => removeCover(c);
      const onSeeked = () => { if (o.requestVideoFrameCallback) o.requestVideoFrameCallback(() => gone()); else setTimeout(gone, 80); };
      o.addEventListener('seeked', onSeeked, { once: true });
      c._off = () => o.removeEventListener('seeked', onSeeked);
      c._t = setTimeout(gone, 3000);
    } catch (e) {}
  }
  function removeCover(c) {
    c = c || coverEl;
    if (!c || c._out) return;
    c._out = true;
    clearTimeout(c._t);
    if (c._off) c._off();
    if (c._hid) {   // nur versteckt: Sichtbarkeit wie vorher
      try { if (c._op) c._hid.style.setProperty('opacity', c._op, c._pr); else c._hid.style.removeProperty('opacity'); } catch (e) {}
      if (coverEl === c) coverEl = null;
      return;
    }
    removeEventListener('resize', c._place); removeEventListener('scroll', c._place, true);
    c.style.opacity = '0';
    setTimeout(() => c.remove(), 180);
    if (coverEl === c) coverEl = null;
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
    // Schneller zu: Ton aus, Player sofort unsichtbar, Vollbild aus – das Aufräumen (Seite übernimmt, Video und Stream
    // abbauen …) kommt direkt danach, wenn der Bildschirm schon frei ist
    try { st.vid.muted = true; st.vid.pause(); } catch (e) {}
    if (st.framed) {
      // Eingebettetes Fenster (Player einer fremden Seite): Die Hauptseite gibt das Vollbild erst auf unsere Meldung hin
      // frei. Bis dahin wäre sonst kurz der Player der Seite im ganzen Bildschirm zu sehen – also SOFORT melden und unser
      // letztes Bild so lange stehen lassen, bis das Fenster wieder klein ist (spätestens nach 0,4 s)
      try { fpost(window.parent, { t: 'release' }); } catch (e) {}
      let hidden = false;
      const hide = () => { if (hidden) return; hidden = true; removeEventListener('resize', hide); st.wrap.style.display = 'none'; if (st.afterHide) st.afterHide(); };
      addEventListener('resize', hide);
      setTimeout(hide, 400);
      st.hideWait = () => !hidden;
    } else st.wrap.style.display = 'none';
    try { if (document.fullscreenElement && document.exitFullscreen) document.exitFullscreen().catch(() => {}); } catch (e) {}
    try { if (screen.orientation && screen.orientation.unlock) screen.orientation.unlock(); } catch (e) {}
    handBack(st);   // Seite übernimmt an deiner Stelle (bei der Zurück-Geste schon geschehen)
    // Mit ✕ geschlossen: unseren Verlaufseintrag wieder entfernen, damit "Zurück" danach normal funktioniert
    if (st.histPushed) { try { history.back(); } catch (e) {} }
    if (toastEl && root) { root.append(toastEl); toastEl.style.top = ''; toastEl.classList.remove('g'); toastEl.style.removeProperty('--lens'); toastEl.style.removeProperty('--gb'); if (lensRO) lensRO.unobserve(toastEl); }
    const rest = () => {
      if (st.hls) { try { st.hls.destroy(); } catch (e) {} }
      if (st.actx) { try { st.actx.close(); } catch (e) {} }
      try { st.vid.srcObject = null; st.vid.removeAttribute('src'); st.vid.load(); } catch (e) {}
      if (!vp) kadriShield(false);   // Seite bekommt „verdeckt/sichtbar“ wieder selbst (außer ein neuer Player spiegelt schon)
      st.cleanup.forEach((f) => { try { f(); } catch (e) {} });
      st.wrap.remove();
    };
    // nach dem nächsten Bild (Bildschirm ist dann schon frei); im Hintergrund gibt es keine Bilder → gleich
    // (eingebettet: erst aufräumen, wenn das Fenster wieder klein und unser Bild weg ist)
    if (st.hideWait && st.hideWait()) { st.afterHide = () => setTimeout(rest, 0); return; }
    if (document.hidden || typeof requestAnimationFrame !== 'function') rest();
    else { let done = false; const go = () => { if (!done) { done = true; rest(); } }; requestAnimationFrame(() => setTimeout(go, 0)); setTimeout(go, 250); }
  }

  // Tipp auf "Player": eingebauter Player (Standard) oder externe App
  function onPlayerTap() {
    tapAt = performance.now();
    const v = active;
    if (!v) return;
    if (CONFIG.defaultPlayer === 'external' && !DOWNLOAD_ONLY) { onPillMain(); return; }
    const go = () => {
      if (toastEl) toastEl.classList.remove('on');
      // Sofort öffnen – nicht auf die HD-Suche warten. Kommt Facebook-HD danach noch, wechselt der Player nahtlos.
      const p = META && (!FB_MIRROR || FB_TRY_OWN) ? fetchHD(v) : PH ? phMp4() : null;
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
  // Seite für EIN Video: nach /video/ usw. folgt eine Kennung (mind. 3 Ziffern oder lang) – nicht /videos/neu
  const VIDEO_PAGE_RE = /\/(?:videos?|watch|clips?|films?|episoden?|episodes?|folgen?)\/(?=[^?#]*(?:\d[^?#]*){3}|[^?#]{20})[^\/?#]{3,}/i;
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

  // Facebook-Reels: Beim Weiterwischen bleibt die Adresse oft beim ERSTEN Reel stehen. Trägt das laufende Video in
  // seinem eigenen Kasten eine andere Video-ID, gehört der Link zu dieser ID (nicht zur Seitenadresse).
  // Video-ID nur aus dem eigenen Kasten des Videos: Reel-/Video-Links und „video_id“-Daten (keine bloßen Zahlen,
  // nicht die Seitenadresse). Nach oben nur so weit, bis andere Videos dazukommen.
  function fbVideoId(v) {
    let el = v;
    for (let lvl = 0; el && el.nodeType === 1 && lvl < 18; lvl++, el = el.parentElement) {
      if (lvl > 0 && el.getElementsByTagName('video').length > 1) break;
      const ids = [];
      const at = el.attributes;
      for (let j = 0; j < at.length; j++) {
        const val = at[j].value;
        if (val.length > 5000) continue;
        const jm = val.match(/"video_?id"\s*:\s*"?(\d{12,})/i);
        if (jm) ids.push(jm[1]);
        if (at[j].name === 'href') idsIn(val, ids);
      }
      if (lvl > 0) { const as = el.querySelectorAll('a[href]'); for (let i = 0; i < as.length && i < 60; i++) idsIn(as[i].getAttribute('href'), ids); }
      const id = ids.find((x) => /^\d{12,}$/.test(x));
      if (id) return id;
    }
    return '';
  }
  function fbOwnUrl(v) {
    if (!v || !/(^|\.)facebook\.com$/i.test(location.hostname)) return '';
    const id = fbVideoId(v);
    if (!id) return '';
    const here = []; idsIn(location.href, here);
    if (here.includes(id)) return '';   // Adresse passt zum Video
    const reel = /\/reels?\//.test(location.pathname) || !/[?&]v=/.test(location.search);
    return location.origin + (reel ? '/reel/' + id + '/' : '/watch/?v=' + id);
  }
  function postUrlFor(v) {
    const fbu = fbOwnUrl(v);
    if (fbu) { postUrlFor.via = 'Video-ID'; return fbu; }
    postUrlFor.via = 'Seite';
    // Die Seite selbst ist schon die Beitragsseite (z. B. Reel, YouTube-Video)
    if (POST_RE.test(location.href) && !NOT_POST_RE.test(location.href)) return location.href.split('#')[0];
    if (!v) return '';
    // TikTok-Feed: Link genau dieses Videos – NIE die Feed-Adresse (/foryou)
    if (TT) return ttPostUrl(v);
    // Eigene Seite für genau dieses Video (Mediatheken: /video/…, /clip/…) und nur ein Video drauf: die Seitenadresse.
    // Links neben dem Player sind dort oft das nächste oder (nach einem Wechsel ohne Neuladen) das vorige Video.
    if (!META && VIDEO_PAGE_RE.test(location.pathname) && countEligible() <= 1) return location.href.split('#')[0];
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
      // Ergebnis: true = geteilt, false = abgebrochen/fehlgeschlagen
      return navigator.share({ url: url }).then(() => true, (e) => { if (!e || e.name !== 'AbortError') fallback(); return false; });
    }
    fallback();
    return Promise.resolve(false);
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
    return maskUrls(lines.join('\n'));
  }
  // Datenschutz: Adressen in der Diagnose ohne die Werte nach „?“ und „#“ (dort stehen oft Zugangsschlüssel,
  // Sitzungs- oder Nutzerkennungen). Die Namen der Angaben bleiben sichtbar – für die Fehlersuche reicht das.
  // Dazu lange, zufällig aussehende Teile im Pfad (Zugangsschlüssel stehen auch dort: /token/9fA3…/video.m3u8):
  // ab 24 Zeichen mit Ziffern UND Buchstaben → die ersten 4 Zeichen und „…“.
  function maskUrls(text) {
    return String(text).replace(/([?&#;][A-Za-z0-9_.\-\[\]%]{1,40}=)[^&#\s|…'")]+/g, '$1…').replace(/(#)[A-Za-z0-9_\-]{16,}/g, '$1…')
      .replace(/[A-Za-z0-9_\-~.%+=]{24,}/g, (s) => (/[0-9]/.test(s) && /[A-Za-z]/.test(s) && !/^[a-z]+(\.[a-z]+)+$/i.test(s) ? s.slice(0, 4) + '…' : s));
  }
  // Ende einer Adresse für die Diagnose: ERST bereinigen, dann kürzen (sonst bliebe vom „?token=…“ nur der Wert übrig)
  const urlTail = (u, n) => '…' + maskUrls(String(u || '').split('#')[0]).slice(-n);
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
    return maskUrls([
      'Video-Assistent 19.28',
      'Seite: ' + location.host + location.pathname,
      'Video-src: ' + (v ? String(v.currentSrc || v.src || '-').slice(0, 90) : 'kein Video'),
      'Ergebnis: ' + (r.how || 'nichts') + (r.best ? ' – ' + r.best.slice(0, 110) : ''),
      'Zuordnung über: ' + (lastVia || '-') + ' | Laufendes Video: ' + (function () { const f = assetFor(v); return (f.asset || '-') + ' (' + f.via + (f.sure ? ', sicher' : '') + ')'; })() +
        ' | geladene Videos: ' + Array.from(assetHits.keys()).slice(-4).map((a) => a + '×' + assetHits.get(a).length).join(', '),
      'Link zum Video: ' + (function () { try { const u = postUrlFor(v); return (u || '-') + ' (' + (postUrlFor.via || '-') + ') | Video-ID im Kasten: ' + (fbVideoId(v) || '-'); } catch (e) { return '-'; } })(),
      'Spiegeln: ' + (vp && vp.mode === 'capture' ? 'aktiv, ' + (vp.mirrorFix || 0) + '× neu verbunden' + (vp.mirrorWhy ? ' (zuletzt: ' + vp.mirrorWhy + ')' : '') : (FB_MIRROR ? 'bereit' : 'aus')),
      'Puffer-Spur: ' + (function () { if (!v) return '-'; const m = creditsFor(v, 0); const l = (bufCredit.get(v) || []).length; return l + ' Zuwächse | ' + (Array.from(m.entries()).map((e) => e[0].slice(-6) + ':' + e[1].toFixed(1)).join(', ') || '-'); })(),
      'HD nachladen: ' + hdLog + ' | Weiterleitung: ' + lastResolve + (PH ? ' | Pornhub-MP4: ' + phLog : '') + (IG ? ' | Instagram-MP4: ' + igLog : '') + (TT ? ' | TikTok: ' + ttLog + ' · ' + (ttPostUrl(v) || 'kein Link') : ''),
      'Öffnen (zuletzt): ' + (function () {
        const o = openTimes;
        if (!o) return '-';
        const ms = (x) => (x ? Math.round(x - o.t0) + ' ms' : '–');
        return 'sichtbar nach ' + ms(o.vis) + ' · Bild der Seite live nach ' + ms(o.live) + ' · eigenes Video nach ' + ms(o.own) +
          ' · ' + (o.how || '-') + (o.swap ? ' nach ' + ms(o.swap) : '') + (typeof o.gap === 'number' ? ' (Abstand ' + Math.abs(o.gap).toFixed(2) + ' s)' : '');
      })(),
      'Zuletzt geöffnet: ' + (lastIntent.t ? Math.round((Date.now() - lastIntent.t) / 1000) + ' s her | ' + lastIntent.how + ' | ' + hostOf(lastIntent.url) + ' ' + urlTail(lastIntent.url, 50) : '-'),
      'Nächstes-Reel-Countdown abgebrochen: ' + nextCancels + '×',
      'Bild-in-Bild: Browser ' + (document.pictureInPictureEnabled ? 'kann es' : 'kann es für Webseiten nicht (nur Wischen zum Startbildschirm)') +
        ' | im Player: ' + (vp ? (vp.pipOk ? 'erlaubt' : 'aus') + ' (' + (vp.mode === 'capture' ? 'gespiegelt' + (kadriPage() ? ', Schild' : '') : 'eigener Player') + ')' : 'zu') +
        ' | Vorschau: ' + (vp ? (vp.prevWhy || (vp.mode === 'own' ? 'noch nicht gespult' : vp.fbPrev && (vp.fbBad || []).indexOf(vp.fbPrev) < 0 ? 'Bilder aus der MP4 (gespiegelt)' : 'nur Zeit (gespiegelt)')) : '-') +
        (vp && vp.prevFirst ? ' · erstes Bild nach ' + (vp.prevFirst / 1000).toFixed(1) + ' s' : '') + (vp && vp.prevMiss ? ' · ' + vp.prevMiss + '× ohne Bild' : '') +
        ' | Lupe: ' + (vp ? vp.lupeWhy || 'noch nicht gespult' : '-') + (vp && vp.scrubSizes ? ' | Bildgrößen beim Spulen: ' + vp.scrubSizes : '') + (vp && vp.subsWhy ? ' | Untertitel der Seite: ' + vp.subsWhy : ''),
      'Stream-Nachweis: ' + (r.proof || '-') + ' | Listen gelesen: ' + plInfo.size + ' | Stück-Ordner: ' + segHits.size +
        (function () { const p = playingStream(navAt); return p ? ' | lädt gerade: ' + hostOf(p.u) + ' ' + urlTail(p.u, 40) : ''; })(),
      'Kodierung: ' + (r.best ? encTag(r.best) || '-' : '-'),
      'IDs am Video: ' + (r.ids.slice(0, 12).join(', ') || '-'),
      'Meta-Einträge: ' + metaCount + ' (Schlüssel: ' + metaMap.size + ') | API-Antworten: ' + bodiesRead + ' | Netzwerk-Quellen: ' + sources.length,
      'Videos auf der Seite: ' + allVideos().length,
      'Browser: ' + navigator.userAgent.slice(0, 120),
      'Reihenfolge beim Tippen (' + ((r.list || []).length) + '):',
    ].concat((r.list || []).map((x, i) => ' ' + (i + 1) + '. ' + x.how + ' | ' + typeShort(x.u) + ' | ' + hostOf(x.u) + ' ' + urlTail(x.u, 40)))
    .concat([
      'Netzwerk-Quellen (letzte 5):',
    ]).concat(recent().slice(-5).map((x) => ' - ' + typeShort(x) + ' | ' + hostOf(x) + ' ' + urlTail(x, 40)))
    .concat([
      'Adressen zu diesem Video (' + lastNear.length + '):',
    ]).concat(lastNear.slice(0, 12).map((c, i) =>
      ' ' + (i + 1) + '. ' + c.kind + ' ' + (c.res ? c.res + 'p' : '?') + ' | ID ' + (c.asset || '-') + ' | ' + (c.tag || 'kein Tag').slice(0, 50) + ' | ' + String(c.from || '').slice(0, 40)
    )).join('\n'));
  }
})();
