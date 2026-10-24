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

