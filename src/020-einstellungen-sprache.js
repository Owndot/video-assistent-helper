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
    dialogBoost: false,       // Dialog-Verstärker (Stimmen deutlicher) – im ⋮-Menü umschaltbar
    // Spul-Vorschau: 'thumb' = kleines Vorschaubild über der Leiste (Standard), 'live' = das Video selbst springt beim
    // Ziehen mit (geht überall, auch beim Spiegeln)
    seekPreview: 'thumb',
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
    'skipSeconds', 'systemHelper', 'downloader', 'autoRotate', 'subSize', 'subHigh', 'language', 'leaveAction', 'dialogBoost', 'seekPreview',
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
    'Fortschrittslinie': 'Progress line', 'Sprungweite': 'Skip', 'Halten': 'Hold', 'Loslassen = abbrechen': 'Release to cancel', 'fein': 'fine', 'Spul-Vorschau': 'Seek preview', 'Im Bild': 'In video', 'Kleines Bild': 'Thumbnail', 'Wischsperre oben': 'Swipe guard top', 'Wischsperre unten': 'Swipe guard bottom', 'Schmal': 'Narrow', 'Mittel': 'Medium', 'Breit': 'Wide', 'Sehr breit': 'Extra wide',
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

