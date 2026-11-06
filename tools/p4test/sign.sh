#!/usr/bin/env bash
# Signiert die UNSIGNIERTE P4-Testapp lokal mit der dauerhaften P4-Testidentität – fail-closed:
#  * nur Paket de.evp.helfer.p4test (nie de.evp.helfer, nie channeltest), nicht debugfähig, Kanalbibliothek für
#    arm64-v8a + x86_64, keine Testbibliothek, allowBackup=false, Eingabe noch unsigniert;
#  * Zertifikat des Schlüsselspeichers == festgelegter SHA-256 (release/p4test-signer.json), sonst Abbruch;
#  * Schlüssel und Ausgabe nie in einem Git-Arbeitsverzeichnis oder Cloud-Ordner; danach genau ein Signierer mit diesem
#    Zertifikat. Läuft unter Linux und macOS (bash 3.2); braucht JDK (keytool), openssl und Android build-tools 34.0.0.
# Aufruf: tools/p4test/sign.sh UNSIGNED.apk KEYSTORE.p12 OUT.apk [PIN.json]   (Passwort: Abfrage oder P4_KS_PASS)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
. "$ROOT/tools/p4test/lib.sh"
IN="${1:?usage}"; KS="${2:?usage}"; OUT="${3:?usage}"; PIN="${4:-$ROOT/release/p4test-signer.json}"
BT="${ANDROID_HOME:?set ANDROID_HOME}/build-tools/34.0.0"
[ -x "$BT/apksigner" ] && [ -x "$BT/aapt2" ] || die "build-tools 34.0.0 required"
[ -f "$IN" ] && [ -f "$KS" ] || die "input APK or keystore missing"
[ ! -e "$OUT" ] || die "$OUT exists"
outside_git "$KS"; outside_git "$OUT"; outside_cloud "$KS"; outside_cloud "$OUT"
pin="$(pinned_sha256 "$PIN")"
[ -n "$pin" ] || die "no certificate pinned in $(basename "$PIN") (create the key with keygen.sh and pin its SHA-256 first)"

badging="$("$BT/aapt2" dump badging "$IN")"
grep -q "^package: name='$P4_PACKAGE' " <<<"$badging" || die "not the P4 test package"
grep -q "application-label:'$P4_LABEL'" <<<"$badging" || die "unexpected label"
if grep -q 'application-debuggable' <<<"$badging"; then die "debuggable APK"; fi
code="$(sed -n "s/^package: .* versionCode='\([0-9]*\)'.*/\1/p" <<<"$badging")"
[ -n "$code" ] && [ "$code" -ge 1 ] || die "no version code"
manifest="$("$BT/aapt2" dump xmltree --file AndroidManifest.xml "$IN")"
grep -q 'allowBackup.*=false' <<<"$manifest" || die "allowBackup not false"
libs="$(unzip -Z1 "$IN" | grep '^lib/' | sort)"
[ "$libs" = "$(printf 'lib/arm64-v8a/libkadri_pairing_poc.so\nlib/x86_64/libkadri_pairing_poc.so')" ] || die "unexpected native libraries"
if "$BT/apksigner" verify "$IN" >/dev/null 2>&1; then die "input is already signed"; fi

if [ -z "${P4_KS_PASS:-}" ]; then read -r -s -p "P4 keystore password: " P4_KS_PASS; echo; fi
export P4_KS_PASS
fp="$(keystore_cert_sha256 "$KS")"
[ "$fp" = "$pin" ] || { unset P4_KS_PASS; die "keystore certificate $fp does not match the pinned P4 identity"; }
umask 077
"$BT/apksigner" sign --ks "$KS" --ks-type PKCS12 --ks-key-alias "$P4_ALIAS" --ks-pass env:P4_KS_PASS --key-pass env:P4_KS_PASS \
  --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true --out "$OUT" "$IN"
unset P4_KS_PASS
certs="$("$BT/apksigner" verify --verbose --print-certs "$OUT")" || { rm -f "$OUT"; die "signature does not verify"; }
[ "$(grep -c '^Signer #[0-9]* certificate DN' <<<"$certs")" = 1 ] || { rm -f "$OUT"; die "not exactly one signer"; }
got="$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<<"$certs")"
[ "$got" = "$pin" ] || { rm -f "$OUT"; die "signed APK carries $got"; }
echo "SIGNED $P4_PACKAGE versionCode=$code cert_sha256=$got apk_sha256=$(sha256_file "$OUT")"
