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
/* Ambient-Modus: die Farben des Bildes leuchten weich in die schwarzen Ränder (wie bei YouTube) */
.vamb{position:absolute;left:-12%;top:-12%;width:124%;height:124%;opacity:0;transition:opacity .8s ease;pointer-events:none}
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
.vp.ctlnew .vchrome,.vp.ctlnew .vchrome *{pointer-events:none!important}
.vp.vgone{opacity:0!important;transition:opacity .1s ease}
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
  // Qualität zuerst (19.3): Die fertige Datei nur nehmen, wenn sie mindestens etwa so scharf ist wie das, was Facebook
  // gerade zeigt (kurze Bildseite). Facebook spielt selbst oft HD als getrennte Bild-/Tonspur – die kann nur das Spiegeln.
  function fbRes(u) {
    const tag = encTag(u);
    const m = /(\d{3,4})p\b/.exec(tag) || /\.C3\.(\d{3,4})\./.exec(tag);
    return m ? +m[1] : 0;
  }
  function fbSureSources(v, resMap) {
    try {
      const af = assetFor(v);
      if (!af.sure || !af.asset) return [];
      let out = playerSources(v, resMap).filter((u) => assetOf(u) === af.asset);
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

