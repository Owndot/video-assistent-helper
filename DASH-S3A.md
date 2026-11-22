# DASH S3a – MPD-Parser, S3-Profil, vollständiger Plan, Strukturfingerabdruck v1 (nur JVM)

**Stand:** S3a. Es gibt nur den reinen Kern im Paket `de.evp.helfer.dash`:

- keine Engine, kein Download, kein Remux;
- keine Kanal-Nachrichten und keine Anbindung an App oder Dienst;
- keine Änderung am HLS-/MP4-Produktcode.

Aus dem HLS-Kern werden nur `HlsUri` (RFC-3986-Auflösung) und `HlsException` unverändert mitbenutzt. Kadri-Produktcode ist unverändert.

| Datei | Aufgabe |
|---|---|
| `DashXml` | gehärtetes Lesen zu einem kompakten Elementbaum |
| `DashNum` | strenge ganze Zahlen (≤ 2^53−1) und Dauern (exakt, BigDecimal) |
| `DashUrl` | Auflösung über `HlsUri`, kanonische Form passend zu WHATWG, fail-closed bei Mehrdeutigkeit |
| `DashTemplate` | `$$`, `$RepresentationID$`, `$Bandwidth$`, `$Number$`, `$Time$`, `%0[w]d` |
| `DashManifest` | Vererbung, BaseURL-Kette, Adressierung, Stückfolge je Darstellung im Periodenfenster |
| `DashProfile` | S3-Profil mit festen Gründen |
| `DashPlan` | vollständiger, begrenzter VOD-Plan (Spuren, Optionen, jedes Stück) |
| `DashFingerprint` | Strukturfingerabdruck v1 |
| `DashLimits`, `DashException` | Grenzen und feste Gründe |

Tests: `tests/java/dash/run.sh` (293 Prüfungen, Heap 64 MB) und `tests/java/dash/mutation.py` (58 Proben, alle KILLED). CI: `.github/workflows/dash-core.yml`.

## 1. Erkennungsindex ≠ Downloadplan

- **Kadri** `dashSegmentIndex` (src/038) dient nur der **Bindung**. Er darf Ausschnitte enthalten:
  - `r=-1` am Ende bis zu 20000 Einträgen;
  - `$Number$` mit `@duration` unbegrenzt nach oben;
  - kein Periodenfenster, kein PTO.
- **Helper** `DashPlan` beschreibt **jedes** Stück jeder angebotenen Spur **innerhalb der Periode**: Ordinalzahl, Nummer, Zeit, Dauer, Adresse. Er ist vollständig und begrenzt.
- **Ordinalzahl** = Position in der vollständigen Adressfolge einer Darstellung. Sie entspricht Kadris `idx`:
  - `$Number$`: Nummer − startNumber;
  - Zeitleiste: Position des S-Eintrags;
  - SegmentList: Position in der Liste.

  Das Periodenfenster ist `first … first+count−1`. Stücke vor dem PTO bzw. nach dem Periodenende gehören nicht zum Plan.
- **Querprüfung** (Abschnitt 5): Sie vergleicht gemeinsame Adressen und deren Semantik (Darstellung, Ordinalzahl, Init, Adressierungsart). Gleiche Stückzahlen werden **nicht** vorausgesetzt.
- **Keine Bindungsevidenz:** Weder Plan noch Fingerabdruck sind Bindungsevidenz.

## 2. Fail-closed-Regeln

### Fatal – das ganze MPD wird abgelehnt (`DashException.reason`)

| Bereich | Gründe |
|---|---|
| XML | `empty`, `too-large` (> 4 000 000 Bytes), `encoding` (kein UTF-8, UTF-16-BOM, NUL, andere deklarierte Kodierung), `doctype` (jedes `<!` außer Kommentar/CDATA, also DOCTYPE, ENTITY, ELEMENT, ATTLIST – vor dem Parser geprüft), `xml`, `not-mpd`, `xinclude`, `xlink` (jedes Attribut im XLink-Namensraum), `namespace` (DASH-Elementname in fremdem Namensraum oder DASH-Element in fremdem Element – Kadri ordnet nach dem lokalen Namen zu), `structure` |
| Grenzen | `limits` (Elemente 200 000 inkl. fremder, Perioden 64, Sets 1024, Darstellungen 4096, S 100 000, SegmentURL 100 000; über alle Darstellungen zusammen: 200 000 neu aufgelöste SegmentURL-Einträge und 1 000 000 ausgewertete Stücke), `depth` (32), `attrs` (64), `attr-length` (8192), `text-length` (4096) |
| Zahlen | `number` (nur Ziffern; XML-Leerraum am Rand erlaubt; kein Vorzeichen, Exponent oder Hex; `S@r` zusätzlich −1), `number-range` (> 2^53−1; timescale 0 oder > 2^32−1; bandwidth > 10^9), `duration` (xs:duration nur mit D/H/M/S, Bruchteile nur bei Sekunden; Jahre, Monate, Wochen abgelehnt) |
| Adressen | `uri-char`, `uri-ambiguous`, `uri-empty`, `uri-length`, `uri-host`, `uri-port`, sowie aus `HlsUri`: `uri-scheme`, `uri-userinfo`, `uri-path`, `uri-fragment` |
| BaseURL | `baseurl-multiple` (mehr als eine BaseURL auf einer Ebene, ganz ohne Auswahl), `baseurl-attr` (erlaubt nur `serviceLocation`, `availabilityTimeOffset`, `availabilityTimeComplete` und fremde Namensräume; `byteRange` wird abgelehnt), `baseurl-empty` |
| Adressierung | `addressing-mixed` (verschiedene Arten entlang der Kette), `addressing-multiple` (zwei auf einer Ebene, zwei Initialization/SegmentTimeline), `addressing-ambiguous` (@duration und SegmentTimeline zugleich; @initialization und Initialization zugleich; SegmentURL auf zwei Ebenen), `addressing-attr` (unbekanntes Attribut an Vorlage/Liste/Zeitleiste/S/SegmentURL/Initialization, z. B. `S@n`, `S@k`) |
| Vorlagen | `template` (unbekannter Bezeichner, `$SubNumber$`, Format nicht `%0[1-20]d`, Format an `$RepresentationID$`, offenes `$`, Medienvorlage ohne oder mit zwei Zahlenplatzhaltern, Init mit Zahlenplatzhalter, Platzhalter im Host oder Port), `rep-id` (`$RepresentationID$` nur aus `[A-Za-z0-9._~=,;!*()+&:@-]`, 1–256 Zeichen) |

**Mehrdeutige Adressen.** Wo RFC 3986 (Helper) und WHATWG (Kadri) verschieden kodieren oder auflösen würden, wird abgelehnt (`uri-ambiguous`), statt eine der beiden Deutungen zu wählen:

- die Zeichen `" < > \ ^ `` { | } ' # [ ]`;
- `%` ohne zwei Hex-Ziffern;
- `%2e` (bei WHATWG ein Punktsegment);
- Schema ohne `//`.

**Hosts** sind nur `[a-z0-9.-]` (Großbuchstaben werden klein) oder eine kanonische IPv4-Adresse. Numerische Kurzformen wie `127.1`, `0x7f…` oder `010…` werden abgelehnt. Standardports entfallen wie bei WHATWG.

### Je Darstellung – nicht nutzbar, das MPD bleibt lesbar (`Representation.excluded`)

| Grund | Bedeutung |
|---|---|
| `addressing-single` | nur BaseURL, keine Stückfolge |
| `byte-range` | SegmentBase, `mediaRange`, `Initialization@range` (S4) |
| `time-without-timeline` | `$Time$` mit @duration – mehrdeutige Zeitbasis |
| `addressing-media`, `addressing-duration` | Vorlage ohne @media; @duration fehlt |
| `addressing-attr` | bekanntes, aber nicht unterstütztes Attribut oder Kind: `endNumber`, `index`, `indexRange`, `indexRangeExact`, `bitstreamSwitching`, `presentationDuration`, `eptDelta`, `pdDelta`, RepresentationIndex, BitstreamSwitching, FailoverContent |
| `list-duplicate`, `list-timeline` | doppelte SegmentURL; Zahl der Einträge ≠ Zeitleiste |
| `timeline-gap`, `timeline-overlap`, `timeline-repeat`, `timeline-empty`, `zero-duration` | Lücke oder Überlappung; `r=-1` geht nicht glatt bis zum nächsten `S@t` auf; Zeitleiste leer; d bzw. @duration gleich 0 |
| `duration-unknown` | Periodendauer unbekannt |
| `segments` | mehr als 20 000 Stücke, auch durch `r`, `r=-1` oder kleine @duration |
| `segment-duration` | Stück länger als 3600 s |
| `coverage-mismatch` | abgedeckte Zeit weicht um mehr als 1 s von der Periodendauer ab |
| `number-range` | Überlauf beim Rechnen |
| `uri-length` | erzeugte Adresse zu lang |

### Profil S3 (`DashProfile`)

Das Profil ist „statisches VOD, eine Periode, unverschlüsselt, fMP4, H.264 + AAC“.

**Gründe für das ganze MPD** (in Prüfreihenfolge):

| Grund | Bedeutung |
|---|---|
| `dash-live` | `type="dynamic"` |
| `dash-type` | anderer Typ |
| `dash-multiperiod` | ≠ 1 Periode |
| `dash-protected` | ContentProtection **irgendwo** im MPD: jede Ebene und jede Platzierung, auch nicht standardkonforme (z. B. direkt unter Period, in einer Vorlage, unter Role), ContentComponent, SubRepresentation und Text-/Bild-Sets. Gezählt wird über den ganzen Elementbaum, nicht nur an bekannten Stellen |
| `dash-duration-unknown` | Periodendauer unbekannt |
| `dash-duration-limit` | länger als 48 h |
| `dash-no-video` | kein Video-Set |
| `dash-codec` | Video- oder Ton-Sets vorhanden, aber keine Spur nutzbar wegen Codec/Container |
| `dash-addressing` | dasselbe, wegen Adressierung oder Zeitleiste |
| `dash-unsupported` | dasselbe, aus sonstigem Grund |

**Nutzbare Spuren:**
- Video: `video/mp4` mit `avc1`/`avc3.XXXXXX`.
- Ton: `audio/mp4` mit `mp4a.40.2`/`.5`/`.29`.
- Gemeinsame Bedingungen: nur ein Codec (sonst `muxed`), Bandbreite > 0, Init vorhanden.

**Ausschluss je Darstellung:** `set-kind` (contentType widerspricht MIME), `set-essential`/`rep-essential` (jede EssentialProperty in Video-/Ton-Sets), `subrepresentation` (auch ContentComponent), `codec`, `container`, `muxed`, `bandwidth`, `init-missing`.

**Ton wird nie still weggelassen:** Gibt es Ton-Sets, aber keine nutzbare Tonspur, wird das ganze MPD abgelehnt. Text-, Bild- und sonstige Sets werden nicht angeboten; ihr Schutz zählt trotzdem.

### Plan (`DashPlan`)

| Grund | Bedeutung |
|---|---|
| `track-collision` | zwei angebotene Adressen könnten dieselbe Ressource anfordern – Ressourcenidentität mehrdeutig. Das gilt zwischen Spuren **und innerhalb einer Spur** (Init == eigenes Stück; Init passt auf die eigene Vorlage). Geprüft wird ohne Aufzählung der Vorlagen-Stücke: zwei Vorlagen kollidieren (fail-closed), wenn ein Präfix Präfix des anderen und ein Suffix Suffix des anderen ist; Listen- und Init-Adressen werden gegen alle Vorlagen und gegeneinander geprüft; eine von mehreren Spuren geteilte Liste fällt beim ersten Eintrag auf |
| `options-limit` | mehr als 256 Optionen (Video × Ton) |
| `size-limit` | jede Option über 16 GiB (Bandbreite × Dauer / 8) |
| `segments-limit` | Spur mit 0 oder mehr als 20 000 Stücken |

**Speicher:**
- Stückfolgen bleiben als Läufe `(t, d, Anzahl)` bzw. als die gelesene Liste gespeichert. Adressen entstehen erst mit `segment(i)`.
- Geerbte SegmentLists werden je (Element, aufgelöste Basis) nur einmal aufgelöst und von allen Darstellungen geteilt; geerbte Zeitleisten werden nicht je Darstellung kopiert, und gleiche Stückfolgen (gleiche Zeitleiste/Liste, Dauer, Timescale, PTO, startNumber, Periode) nur einmal ausgewertet.
- Was trotzdem neu aufgelöst oder ausgewertet werden muss, ist über das ganze MPD begrenzt (Abschnitt 4); darüber `limits`, nie Speichererschöpfung.
- Der Fingerabdruck wird fortlaufend gehasht, ohne den kanonischen Text aufzubauen.
- Geprüft bei 64 MB Heap: 100 000 S-Einträge; 160 Darstellungen, die 20 000 SegmentURL erben (≈ 690 KB); dieselbe Zeitleiste mit 20 000 S für 160 Darstellungen.

## 3. Strukturfingerabdruck v1 (`kadri-dash-fp/1`)

**Zweck:** Konsistenzprüfung „gleiches MPD bei Kadri und beim Helper“. Er ist **keine** Bindungsevidenz.

**Hash:** SHA-256 über den kanonischen Text (UTF-8), klein-hex.

**Kanonischer Text:**
- **Zeilenform:** eine Zeile `schlüssel=wert` + LF je Feld, in der unten festgelegten Reihenfolge.
- **Reihenfolge der Elemente:** Dokumentreihenfolge – Perioden, darin Sets, darin Darstellungen.
- **Indizes:** `period`, `set`, `rep` zählen global ab 0.

**Werte:**

| Regel | Festlegung |
|---|---|
| Fehlt | `-` |
| Wörtliches `-` | `\-` |
| Maskierung | `\` → `\\`, `|` → `\|`, LF → `\n`, CR → `\r` |
| Attributwerte | wie vom XML-Parser geliefert (Attributnormalisierung des XML-Standards), sonst unverändert |
| Texte (Label) | an den Rändern nur von XML-Leerraum (SP, TAB, CR, LF) befreit – **nicht** JavaScript-`trim()` |
| Ganze Zahlen | dezimal ohne führende Nullen |
| Dauern | Sekunden als Dezimalzahl ohne Exponent und ohne Nachkomma-Nullen (`PT1H32M5.120S` → `5525.12`, 0 → `0`) |
| Adressen | aufgelöst und kanonisch (Abschnitt 2); Basis der Auflösung ist die **finale** MPD-Adresse ohne Fragment |
| Medienvorlage | Präfix + `{Number}` / `{Number:05}` / `{Time}` / `{Time:08}` + Suffix |
| Eigenschaften | `schemeIdUri|value|default_KID` (default_KID = Attribut `default_KID` in beliebigem Namensraum) |

**Felder (alle, in dieser Reihenfolge):**

```
fp=kadri-dash-fp/1
mpd.type  mpd.mediaPresentationDuration  mpd.base  mpd.contentProtection(Anzahl auf MPD-Ebene)
[mpd.contentProtectionNested=<Anzahl>]   (nur wenn > 0: ContentProtection, die nicht direktes Kind von MPD, AdaptationSet oder
                                          Representation ist – Period, Vorlage, ContentComponent, SubRepresentation …)
je Periode:  period  p.id  p.start  p.duration  p.base
  je Set:    set  s.id  s.group  s.contentType  s.mimeType  s.codecs  s.lang  s.label  s.base
             s.contentProtection*  s.contentComponents  s.essential*  s.supplemental*  s.role*  s.accessibility*  s.audioChannels*
    je Darstellung:
             rep  r.id  r.bandwidth  r.mimeType(wirksam)  r.codecs(wirksam)  r.width  r.height  r.frameRate  r.audioSamplingRate  r.base
             r.contentProtection*  r.essential*  r.supplemental*  r.audioChannels*  r.subRepresentations
             r.segmentElements ("rep:<Element|->,set:<Element|->,period:<Element|->", Element ∈ SegmentBase/SegmentList/SegmentTemplate)
             r.timescale  r.presentationTimeOffset  r.startNumber  r.duration          (wirksam = geerbt, Rohwert, fehlt = "-")
             r.media  r.init  r.initRange  r.unsupported (gesetzte, nicht unterstützte Vorlagen-Attribute/-Kinder, Kommaliste)
             [r.timeline=<Anzahl S>, je S: r.S=<t|->,<d>,<r>]                             (nur mit Zeitleiste; t roh, "-" wenn fehlend)
             [r.list=<Anzahl>[,ranges], je Eintrag: r.SegmentURL=<Adresse>|<mediaRange|->]  (nur mit SegmentList)
```
`*` = eine Zeile je Element, in Dokumentreihenfolge.

**Abgedeckt sind die geforderten Bereiche:**
- Ressourcenidentität: aufgelöste BaseURL-Kette jeder Ebene, Vorlagen samt Query, Init, Listeneinträge, Byte-Bereiche.
- Stückfolge: vollständige Zeitleiste roh, startNumber, @duration, Listen.
- Spurwahl: Codec, MIME, Größe, Bandbreite, Sprache, Label, Role, Accessibility, Kanäle, Gruppe.
- Zeitbasis: timescale, presentationTimeOffset, Period@start/@duration, MPD-Dauer.
- Container: MIME, Codec.
- Sicherheitsentscheidung: ContentProtection aller Ebenen samt Schema/Wert/KID, Essential-/SupplementalProperty, SubRepresentation/ContentComponent, Typ.

**Nicht aufgenommen (mit Begründung):**
- Ändern weder Ressourcen noch Folge, Spurwahl oder Sicherheit: `minBufferTime`, `profiles`, `segmentAlignment`, `startWithSAP`, `maxWidth` usw., UTCTiming, Location, Kommentare, Attributreihenfolge, Namensraum-Präfixe.
- Abgeleitete Werte (Fenster, Abdeckung, Ausschlussgründe): Sie folgen aus den Eingaben und müssten sonst in beiden Implementierungen bitgleich berechnet werden.

**Dokumentadresse:** Sie geht nur über aufgelöste Adressen ein. Ein MPD mit absoluten BaseURLs hat unabhängig von seiner Abrufadresse denselben Abdruck.

**Version:** Neue Merkmale, die eines der Kriterien berühren, erfordern eine neue Version (`kadri-dash-fp/2`).

**Festschreibung:**
- `tests/java/dash/fixtures/fp-v1-golden-segmentlist.txt` enthält den kanonischen Text von `vod-segmentlist.mpd`.
- `fp-v1-vectors.tsv` enthält 24 Testvektoren (12 MPDs × 2 Dokumentadressen) für die spätere Kadri-Implementierung (S3c).

## 4. Grenzen

| Grenze | Wert |
|---|---|
| MPD-Größe | 4 000 000 Bytes |
| Elemente | 200 000 |
| Tiefe | 32 |
| Attribute je Element | 64 |
| Attributlänge | 8192 Zeichen |
| Textlänge | 4096 Zeichen |
| Perioden / Sets / Darstellungen | 64 / 1024 / 4096 |
| S-Einträge / SegmentURL | je 100 000 |
| Neu aufgelöste SegmentURL-Einträge (alle Darstellungen) | 200 000 |
| Ausgewertete Stücke (alle Darstellungen, Gleiches einmal) | 1 000 000 |
| Stücke je Spur | 20 000 |
| Stückdauer | ≤ 3600 s |
| Gesamtdauer | ≤ 48 h |
| Abdeckungstoleranz | 1 s |
| Ganze Zahlen | ≤ 2^53−1 |
| timescale | ≤ 2^32−1 |
| Bandbreite | ≤ 10^9 |
| Formatbreite | ≤ 20 |
| Optionen | ≤ 256 |
| Größe je Option | ≤ 16 GiB |
| Adresse | ≤ 4096 Zeichen |

## 5. Querprüfung mit Kadri und dokumentierte Abweichungen

**Ablauf:**
1. Die Fixtures sind Kadris `tests/web/dash/*` unverändert aus Kommit b4a8289, mit SHA-256 in `fixtures/KADRI-SOURCES.txt`, dazu zwei eigene S3a-Fixtures.
2. `TestDash --dump-keys` erzeugt Adressen aus einer Plan-Stichprobe: alle bei ≤ 60 Stücken, sonst Anfang, Ende und jede 50., dazu jedes Init und Kadris eigene Testadressen aus `test_dash_mpd.py`.
3. `kadri_index.py` lässt Kadris **unveränderten** src/038 im echten DOMParser von Chromium darüber laufen. Es erzeugt `fixtures/kadri-index.tsv` mit Kadri-Kommit und SHA-256 von src/038 im Kopf.
4. `TestDash` prüft, ohne gleiche Stückzahlen vorauszusetzen:
   - Jeder Kadri-Treffer hat dieselbe Ordinalzahl im Plan.
   - Die Zuordnung der Darstellungen ist eindeutig und einheitlich.
   - Jede Stichproben-Adresse des Plans trifft in Kadri.
   - Kein Kadri-Nicht-Treffer ist ein Plan-Stück.
   - Kadri-Inits sind Plan-Inits.
   - Die Adressierungsart je Darstellung stimmt mit Kadris `summary.tracks[].addressing` überein.

**Ergebnis:** 947 gemeinsame Stückadressen in 12 MPDs, 11 gemeinsame Nicht-Treffer, 0 unerklärte Unterschiede.

**Erlaubte, dokumentierte Abweichungen:**

| # | Abweichung | Wirkung |
|---|---|---|
| 1 | Kadris Index reicht über das Periodenfenster hinaus (`r=-1` bis 20 000, `$Number$`+@duration unbegrenzt, Stücke vor dem PTO) | Kadri-Treffer ohne Plan-Stück, nur außerhalb `first … first+count−1`; bindungsseitig unkritisch |
| 2 | Kadri setzt `$Bandwidth%0Nd$` **ohne** Auffüllen ein (Kadri-Init `init-96000`, Plan `init-00096000`) | Kadri-Fehler; Korrektur in S3c. Eigener Test sichert den heutigen Stand ab |
| 3 | Darstellungen, die der Plan ausschließt (z. B. Live: `duration-unknown`), indiziert Kadri weiter | nur Erkennung |
| 4 | Mehrere BaseURLs: Kadri nimmt die erste, der Helper lehnt das MPD ab | kein Plan |
| 5 | `$Time$` mit @duration: Kadri indiziert, der Helper schließt aus | kein Angebot |
| 6 | DASH-Namen in fremdem Namensraum: Kadri ordnet nach dem lokalen Namen zu, der Helper lehnt ab | kein Plan |
| 7 | Doppelte SegmentURL: Kadri dedupliziert, der Helper schließt aus; einelementige SegmentList ohne @duration erlaubt nur der Helper | Kadri bindet so etwas ohnehin nicht |
| 8 | Lücken/Überlappungen der Zeitleiste: Kadri indiziert weiter, der Helper schließt aus | kein Angebot |
| 9 | Kadri prüft Dauern und Zahlen großzügiger und akzeptiert Zeichen, die WHATWG kodiert | der Helper lehnt ab, kein Plan |
| 10 | Kadri indiziert höchstens 64 Darstellungen, der Helper liest bis 4096 | nur Erkennung |

Alle Abweichungen 3–10 führen zu **weniger** Angeboten, nie zu einer weiteren Bindung oder einem anderen Download.

## 6. Ausführen

```
bash tests/java/dash/run.sh                          # 293 Prüfungen, nur JDK (javac -Werror), Heap 64 MB
python3 -I tests/java/dash/mutation.py               # 58 Mutationsproben, alle müssen KILLED sein
# nur bei bewusster Änderung (Fixture/Format):
bash tests/java/dash/run.sh --dump-keys KEYS.tsv
KADRI_CHROMIUM=<chromium> python3 -I tests/java/dash/kadri_index.py <Kadri-Checkout> tests/java/dash/fixtures KEYS.tsv tests/java/dash/fixtures/kadri-index.tsv
bash tests/java/dash/run.sh --write-fp               # Golden-Text/Testvektoren v1 (Formatänderung ⇒ neue Version!)
```

## 7. Offen für S3b/S3c (nicht Teil von S3a)

- **S3b:**
  - Verhalten von `DashXml` auf Android: Expat-SAX ohne Xerces-Merkmale, im Emulator auf API 26/29/34 zu prüfen; die Vorprüfung macht die DTD-Abwehr unabhängig davon.
  - Engine, Abruf über die gehärtete Netzschicht, Journal.
  - `Fmp4Inspect` (encv/enca/sinf/tenc/pssh, Codec im stsd gegen MPD), Remux, Protokoll `dashplan`/`dashstart`.
- **S3c:**
  - Kadri implementiert Fingerabdruck v1 nach Abschnitt 3 und muss `fp-v1-vectors.tsv` reproduzieren.
  - Korrektur von Abweichung 2 (`$Bandwidth%0Nd$`).
  - Prüfen, ob Kadris feste Marke `KADRIIDXTOKEN9Q` (src/038) mit einer gleichlautenden `$RepresentationID$` oder BaseURL kollidieren kann (im Helper in S3a-Fix 1 behoben: die Marke wird je Vorlage eindeutig gewählt).
  - Auswahl-Gate und Oberfläche.
- **Gemeinsamer Init über mehrere Spuren:** wird derzeit fail-closed abgelehnt (`track-collision`); bei Bedarf mit Prüfung des Init-Inhalts in S3b neu bewerten.
