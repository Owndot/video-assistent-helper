# Real screenshot reference

entry.png is a 90x90 crop (940,1250)-(1030,1340) of real_extension_install.png from real Android API33 run40185452608, source72488de, official Firefox158.0.1, unchanged Kadri f76c209, Pixel2 1080x1920 at420dpi. It contains only the rendered Material Player icon and test-page background, no credentials or personal state. It calibrates screenshot recognition and is not injected into the browser. Material Icons are Apache2.0; the surrounding Kadri UI follows the repository license.

Full-frame positive/negative calibration was also executed in run40186503963 before emulator allocation. That negative control removed the actual pill glyph and rejected matches elsewhere in the original moving-pattern screenshot.

subtitle.png is the actual owned-video frame from API33/Kadri in run40192612290,
subtitle_selection.png. The real cue is visibly rendered although full-frame OCR
missed it. It contains no user media or pairing credential. White-text/crop OCR
was checked independently on both route captures by inspector40197187512.

controls.png is a real Firefox Android/API35 Userscript capture from run40192612290,
player_play_pause.png. It contains only the owned generated media and player UI,
not a pairing key. Visible Close/Pause are positive references; a dimmed copy is
a deliberate negative control for opacity-invariant normalized correlation.
The actual previous c6d0cde matcher accepts that negative; the corrected matcher
rejects it. The regression was executed in run40200226034.
