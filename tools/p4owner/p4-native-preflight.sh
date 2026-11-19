#!/usr/bin/env bash
# P4-Preflight (F3): 16-KB-Ausrichtung der FERTIG SIGNIERTEN Test-APK. Fail-closed: jede Abweichung, jeder fehlende
# Eintrag, jeder Werkzeug- oder Auslesefehler -> "PREFLIGHT-NATIVE REJECT", Exit 1. Nur "PREFLIGHT-NATIVE PASS" = Exit 0.
# Aufruf: p4-native-preflight.sh <signierte.apk> <erwarteter-sha256>
# Werkzeuge (Pfade per Umgebung): APKSIGNER, ZIPALIGN (build-tools >= 35, kennt -P), READELF (llvm-readelf, NDK).
# Linux und macOS (bash 3.2, shasum).
set -euo pipefail -f
export LC_ALL=C
rej() { echo "PREFLIGHT-NATIVE REJECT: $*" >&2; exit 1; }
trap 'rej "unerwarteter Fehler in Zeile $LINENO"' ERR
[ $# -eq 2 ] || rej "Aufruf: $0 <signierte.apk> <sha256>"
APK="$1"; WANT="$2"
APKSIGNER="${APKSIGNER:-apksigner}"; ZIPALIGN="${ZIPALIGN:-zipalign}"; READELF="${READELF:-llvm-readelf}"
for t in "$APKSIGNER" "$ZIPALIGN" "$READELF" unzip awk paste; do command -v "$t" >/dev/null 2>&1 || rej "Werkzeug fehlt: $t"; done
[ -f "$APK" ] && [ -s "$APK" ] || rej "APK fehlt oder leer"
printf '%s' "$WANT" | grep -Eqx '[0-9a-f]{64}' || rej "erwarteter SHA-256 ungueltig"
if command -v sha256sum >/dev/null 2>&1; then GOT="$(sha256sum < "$APK" | cut -d' ' -f1)"
elif command -v shasum >/dev/null 2>&1; then GOT="$(shasum -a 256 < "$APK" | cut -d' ' -f1)"
else rej "Werkzeug fehlt: sha256sum oder shasum"; fi
printf '%s' "$GOT" | grep -Eqx '[0-9a-f]{64}' || rej "SHA-256 nicht berechenbar"
[ "$GOT" = "$WANT" ] || rej "SHA-256 $GOT != erwartet $WANT"

# 1. Fertig signiert: Signatur gueltig, genau ein Signierer (sonst ist es nicht das auszuliefernde Artefakt)
CERTS="$("$APKSIGNER" verify --verbose --print-certs "$APK" 2>&1)" || rej "Signatur ungueltig oder APK nicht signiert"
[ "$(grep -c '^Signer #[0-9]* certificate DN' <<<"$CERTS" || true)" = 1 ] || rej "nicht genau ein Signierer"

# 2. Genau die erwarteten nativen Bibliotheken, keine weiteren .so irgendwo in der APK
LIST="$(unzip -Z1 "$APK")" || rej "ZIP-Verzeichnis nicht lesbar"
SOS="$(grep -E '\.so$' <<<"$LIST" | sort || true)"
EXPECT="$(printf 'lib/arm64-v8a/libkadri_pairing_poc.so\nlib/x86_64/libkadri_pairing_poc.so')"
[ "$SOS" = "$EXPECT" ] || rej "unerwartete .so-Menge: [$(tr '\n' ' ' <<<"$SOS")]"

# 3. Jede .so unkomprimiert gespeichert (Voraussetzung fuer direktes Laden und fuer -P 16)
for so in $SOS; do
  meth="$(unzip -Zv "$APK" "$so" | awk -F': *' '/compression method/{print $2; exit}')" || rej "Eintrag nicht lesbar: $so"
  [ "$meth" = "none (stored)" ] || rej "$so komprimiert ($meth)"
done

# 4. ZIP-Ausrichtung: 16 KB fuer unkomprimierte .so, 4 Byte sonst
ZA="$("$ZIPALIGN" -c -P 16 -v 4 "$APK" 2>&1)" || rej "zipalign -c -P 16 -v 4 fehlgeschlagen: $(tail -3 <<<"$ZA" | tr '\n' ' ')"
grep -q 'Verification successful' <<<"$ZA" || rej "zipalign meldet keinen Erfolg"
for so in $SOS; do
  grep -Eq "^[[:space:]]*[0-9]+ ${so//./\\.} \(OK\)$" <<<"$ZA" || rej "zipalign: $so nicht als OK ausgewiesen"
done

# 5. ELF je Bibliothek: Kopf, Architektur, Programmkopfe, JEDES LOAD-Segment mit Align >= 0x4000 (Zweierpotenz)
#    und Offset == VirtAddr modulo Align. Reine Bash-Arithmetik (unabhaengig vom awk-Dialekt).
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
n_ok=0
for so in $SOS; do
  abi="$(cut -d/ -f2 <<<"$so")"
  unzip -p "$APK" "$so" > "$W/$abi.so" || rej "Entpacken fehlgeschlagen: $so"
  [ -s "$W/$abi.so" ] || rej "$so leer"
  HDR="$("$READELF" -hW "$W/$abi.so" 2>&1)" || rej "$so: ELF-Kopf nicht lesbar"
  grep -Eq '^ *Class: *ELF64$' <<<"$HDR" || rej "$so: kein ELF64"
  grep -Eq '^ *Type: *DYN' <<<"$HDR" || rej "$so: keine gemeinsam genutzte Bibliothek"
  mach="$(sed -n 's/^ *Machine: *//p' <<<"$HDR")"
  case "$abi:$mach" in arm64-v8a:AArch64|x86_64:*X86-64*) ;; *) rej "$so: Architektur $mach passt nicht zu $abi" ;; esac
  PH="$("$READELF" -lW "$W/$abi.so" 2>&1)" || rej "$so: Programmkopfe nicht lesbar"
  n_load=0; aligns=""
  while IFS= read -r line; do
    set -- $line
    [ "${1:-}" = LOAD ] || continue
    n_load=$((n_load + 1))
    [ $# -ge 8 ] || rej "$so: LOAD-Zeile unvollstaendig: $line"
    off="$2"; va="$3"; eval "al=\${$#}"
    for x in "$off" "$va" "$al"; do [[ "$x" =~ ^0x[0-9a-fA-F]{1,16}$ ]] || rej "$so: LOAD-Feld unlesbar: $line"; done
    a=$((16#${al#0x})); o=$((16#${off#0x})); v=$((16#${va#0x}))
    [ "$a" -ge 16384 ] || rej "$so: LOAD Align $al < 0x4000"
    [ $((a & (a - 1))) -eq 0 ] || rej "$so: LOAD Align $al keine Zweierpotenz"
    [ $((o % a)) -eq $((v % a)) ] || rej "$so: LOAD Offset $off / VirtAddr $va nicht kongruent zu $al"
    aligns="$aligns $al"
  done <<<"$PH"
  [ "$n_load" -ge 1 ] || rej "$so: keine LOAD-Segmente"
  set --
  echo "ELF $so LOAD=$n_load align=$(tr ' ' '\n' <<<"$aligns" | sed '/^$/d' | sort -u | paste -sd, -)"
  n_ok=$((n_ok + 1))
done
[ "$n_ok" = 2 ] || rej "nicht beide Bibliotheken geprueft ($n_ok)"
trap - ERR
echo "PREFLIGHT-NATIVE PASS apk_sha256=$GOT"
