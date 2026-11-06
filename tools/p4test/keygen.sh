#!/usr/bin/env bash
# NUR beim Eigentümer ausführen, einmalig. Erzeugt die DAUERHAFTE P4-Testidentität (eigener Schlüssel, getrennt von
# Produktion, Emulator- und Wegwerfschlüsseln) für de.evp.helfer.p4test. Nie in CI, nie in einem Git-Ordner.
# Aufruf: tools/p4test/keygen.sh ZIELORDNER      (Passwort fragt keytool interaktiv ab, nie als Argument)
# Danach: .p12 und Passwort getrennt sichern (verschlüsselter Stick bzw. lokaler Tresor/Papier, nie Cloud); SHA-256 in
# release/p4test-signer.json eintragen lassen (öffentlich, kein Geheimnis). Kein zweiter Schlüssel für Updates.
set -euo pipefail
. "$(dirname "$0")/lib.sh"
DIR="${1:?usage: keygen.sh TARGET_DIR}"
[ -d "$DIR" ] || die "$DIR does not exist"
KS="$(cd "$DIR" && pwd)/helper-p4-test.p12"
outside_git "$KS"
outside_cloud "$KS"
[ ! -e "$KS" ] || die "$KS exists: the P4 identity is created once and reused (no new key for updates)"
[ -z "${CI:-}${GITHUB_ACTIONS:-}" ] || die "never in CI"
umask 077
keytool -genkeypair -storetype PKCS12 -keystore "$KS" -alias "$P4_ALIAS" -keyalg RSA -keysize 3072 \
  -validity 1826 -dname "$P4_DN"
chmod 600 "$KS"
read -r -s -p "Keystore password (once more, to print the fingerprint): " P4_KS_PASS; echo
export P4_KS_PASS
fp="$(keystore_cert_sha256 "$KS")"; unset P4_KS_PASS
[ ${#fp} -eq 64 ] || die "could not read the certificate"
echo "P4 test identity created: $KS"
echo "certificate_sha256=$fp"
echo "Back up the .p12 and its password separately and offline (never a cloud service). Share only the fingerprint above."
