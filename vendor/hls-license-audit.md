# hls.js 1.7.3 licensing check

The JavaScript and `hls.lock.json` are unchanged. `hls.LICENSE` now supplies the
complete shared Apache-2.0 text and retained component notices. `tools/build.js`
embeds that file as a readable comment after the manager metadata, before code.
It does not modify the library or the pre-existing executable userscript bytes.

Checked upstream hls.js tag `v1.7.3`, commit
`e5ff3583965e3af16c4a4b4d2b5f7bd1ffb5b7de`: root `LICENSE`, `package.json`,
`package-lock.json`, and the license/copyright comments in all 140 source JS/TS
files. This tag has no separate NOTICE file. The root notice retains Dailymotion
and Brightcove (upstream's full 2013–2015 range); the previous distribution's
2013–2014 summary is also retained verbatim.

Additional relevant source notices cover DASH Industry Forum's CEA-608 parser
(BSD conditions/disclaimer), vtt.js Contributors / Mozilla WebVTT code
(Apache-2.0), and Masanao Izumo's UTF conversion notice.

The full bundle also includes EventEmitter3 5.0.4 (MIT), url-toolkit 2.2.5
(Apache-2.0; Tom Jenkinson), and Babel helpers/regenerator (MIT; Sebastian
McKenzie/contributors and Facebook). Their upstream license notices are included.
The hls.js lockfile identifies the included CML modules as cmcd 2.4.0, id3 1.0.6,
structured-field-values 1.1.3 and utils 1.5.0 (Apache-2.0). Relevant Common Media
Library notices were checked at repository snapshot
`f580247746bc3c2d9c16faa044e6fb4585f6d388`: root LICENSE/NOTICE and the ID3 and
structured-field-values NOTICE sections. These include SVTA's 2023 copyright,
Dailymotion/Brightcove and Jxck's complete MIT notice. Unrelated root NOTICE
sections for CML components not present in this bundle are not included.

The copied texts carry their upstream source URLs in `hls.LICENSE`. Apache's full
license is provided once for the Apache-licensed components; the separate MIT,
BSD and UTF notices retain their respective terms. No license is replaced by a
mere URL.

Focused checks:

```
node tools/build.js
node tools/build.js --check
node tests/security/hls_license.js
```

For this correction, the last command also accepts the saved previous artifact
as its sole argument and verifies byte-for-byte equality after removal of only
the added legal comment. Missing copyright and truncated-license negative
controls must fail. The existing payload test accounts for the legal comment
separately, leaving its executable-byte budget unchanged.
