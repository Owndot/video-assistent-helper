#!/usr/bin/env bash
# CI-Selbsttest des Signierwegs mit einem WEGWERF-Schlüssel und einer Wegwerf-Festlegung (nie die echte Identität).
# Jede Ablehnung muss greifen; danach werden Schlüssel und alle APKs gelöscht. Nichts wird hochgeladen.
# Aufruf: tools/p4test/selftest.sh UNSIGNED_P4.apk OTHER_SIGNED.apk
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
. "$ROOT/tools/p4test/lib.sh"
P4="${1:?unsigned p4test apk}"; OTHER="${2:?another (signed) apk}"
W="$(mktemp -d)"; INREPO="$ROOT/app/build/p4-selftest"
trap 'rm -rf "$W" "$INREPO"' EXIT
export P4_KS_PASS="$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)"
keytool -genkeypair -storetype PKCS12 -keystore "$W/k.p12" -alias "$P4_ALIAS" -keyalg RSA -keysize 3072 -validity 2 \
  -dname "$P4_DN" -storepass:env P4_KS_PASS -keypass:env P4_KS_PASS >/dev/null 2>&1
keytool -genkeypair -storetype PKCS12 -keystore "$W/other.p12" -alias "$P4_ALIAS" -keyalg RSA -keysize 3072 -validity 2 \
  -dname "$P4_DN" -storepass:env P4_KS_PASS -keypass:env P4_KS_PASS >/dev/null 2>&1
fp="$(keystore_cert_sha256 "$W/k.p12")"
pin() { python3 -I -c 'import json,sys; d=json.load(open(sys.argv[1])); d["certificate_sha256"]=sys.argv[3]; json.dump(d,open(sys.argv[2],"w"))' "$ROOT/release/p4test-signer.json" "$1" "$2"; }
pin "$W/pin.json" "$fp"
refused() {  # $1 Erwarteter Text, Rest: sign.sh-Argumente
  local want="$1"; shift
  if out="$(bash "$ROOT/tools/p4test/sign.sh" "$@" 2>&1)"; then echo "FAIL: accepted ($want)" >&2; exit 1; fi
  grep -q "REFUSED: .*$want" <<<"$out" || { echo "FAIL: wrong refusal for '$want': $out" >&2; exit 1; }
  echo "PASS: refused - $want"
}
refused "no certificate pinned" "$P4" "$W/k.p12" "$W/a.apk"                       # echte Festlegung ist noch leer
refused "does not match the pinned" "$P4" "$W/other.p12" "$W/a.apk" "$W/pin.json"  # anderer Schlüssel
refused "not the P4 test package" "$OTHER" "$W/k.p12" "$W/a.apk" "$W/pin.json"     # channeltest/Produktion
mkdir -p "$INREPO"; cp "$W/k.p12" "$INREPO/k.p12"
refused "inside a Git work tree" "$P4" "$INREPO/k.p12" "$W/a.apk" "$W/pin.json"   # Schlüssel im Repo-Ordner
refused "inside a Git work tree" "$P4" "$W/k.p12" "$INREPO/a.apk" "$W/pin.json"   # Ausgabe im Repo-Ordner
bash "$ROOT/tools/p4test/sign.sh" "$P4" "$W/k.p12" "$W/signed.apk" "$W/pin.json" | sed 's/apk_sha256=.*/apk_sha256=(deleted)/'
refused "already signed" "$W/signed.apk" "$W/k.p12" "$W/again.apk" "$W/pin.json"
refused "exists" "$P4" "$W/k.p12" "$W/signed.apk" "$W/pin.json"
if CI=1 bash "$ROOT/tools/p4test/keygen.sh" "$W" >/dev/null 2>&1; then echo "FAIL: keygen ran in CI" >&2; exit 1; fi
echo "PASS: keygen refuses in CI"
grep -q '"certificate_sha256": null' "$ROOT/release/p4test-signer.json" && echo "PASS: no P4 identity pinned yet (KEY_NOT_YET_CREATED)"
echo "PASS: P4 signing path self-test (throwaway key and all APKs deleted)"
