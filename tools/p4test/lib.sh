# Gemeinsame Prüfungen der P4-Testapp (eingebunden von keygen.sh, sign.sh und selftest.sh).
# Portabel: Linux (CI) und macOS (Eigentümer, bash 3.2, BSD-Werkzeuge, LibreSSL). Kein python, kein sha256sum nötig.
P4_PACKAGE='de.evp.helfer.p4test'
P4_ALIAS='helper-p4-test'
P4_DN='CN=Helper P4 TEST ONLY - durable'
P4_LABEL='P4 TEST Helfer'

die() { echo "REFUSED: $*" >&2; exit 1; }

# Nie Schlüssel oder signierte APKs in einem Git-Arbeitsverzeichnis (auch nicht in ignorierten Ordnern).
outside_git() {  # $1 Pfad (Datei oder Ordner; muss nicht existieren)
  local d; d="$(cd "$(dirname "$1")" 2>/dev/null && pwd -P)" || die "directory of $1 does not exist"
  if git -C "$d" rev-parse --is-inside-work-tree >/dev/null 2>&1; then die "$1 is inside a Git work tree"; fi
}

# Nie in einem synchronisierten Cloud-Ordner (iCloud Drive, Dropbox/OneDrive/Google Drive unter macOS CloudStorage).
# Schreibtisch und Dokumente kann iCloud ebenfalls synchronisieren ("Schreibtisch & Dokumente"): dort nur mit Warnung.
outside_cloud() {  # $1 Pfad
  local d; d="$(cd "$(dirname "$1")" 2>/dev/null && pwd -P)" || die "directory of $1 does not exist"
  local h; h="$(cd "$HOME" 2>/dev/null && pwd -P)" || h="$HOME"
  case "$d/" in
    "$h/Library/Mobile Documents/"*|"$h/Library/CloudStorage/"*|*"/iCloud Drive/"*|*"/Dropbox/"*|*"/OneDrive"*|*"/Google Drive/"*)
      die "$1 is inside a cloud-synced folder" ;;
    "$h/Desktop/"*|"$h/Documents/"*)
      echo "WARNING: $1 is under Desktop/Documents, which iCloud may sync; prefer a folder such as ~/P4-Signing" >&2 ;;
  esac
}

# SHA-256 (64 Hex, klein) einer Datei: sha256sum (Linux), shasum (macOS) oder openssl – immer streng geprüft.
sha256_file() {  # $1 Datei
  local v
  if command -v sha256sum >/dev/null 2>&1; then v="$(sha256sum < "$1" | cut -d' ' -f1)"
  elif command -v shasum >/dev/null 2>&1; then v="$(shasum -a 256 < "$1" | cut -d' ' -f1)"
  else v="$(openssl dgst -sha256 -r < "$1" | cut -d' ' -f1)"; fi
  v="$(printf '%s' "$v" | tr 'A-F' 'a-f')"
  case "$v" in *[!0-9a-f]*|'') die "could not hash $1" ;; esac
  [ ${#v} -eq 64 ] || die "could not hash $1"
  printf '%s\n' "$v"
}

# SHA-256 (64 Hex, klein) des Zertifikats im Schlüsselspeicher; Passwort aus P4_KS_PASS.
keystore_cert_sha256() {  # $1 .p12
  keytool -exportcert -rfc -keystore "$1" -storetype PKCS12 -alias "$P4_ALIAS" -storepass:env P4_KS_PASS 2>/dev/null \
    | openssl x509 -noout -fingerprint -sha256 | cut -d= -f2 | tr -d ':' | tr 'A-F' 'a-f'
}

# Festgelegter Fingerabdruck aus release/p4test-signer.json; leer, solange kein Schlüssel festgelegt ist (null).
# Zeilengenau statt JSON-Bibliothek (portabel): Paket und Alias müssen genau so dastehen, das Feld genau einmal, der
# Wert ist null oder 64 Hex-Zeichen klein – jede andere Form wird abgelehnt.
pinned_sha256() {  # $1 Pin-Datei
  local f="$1" n v
  [ -f "$f" ] || die "pin file $f missing"
  grep -Eq '^  "package": "de\.evp\.helfer\.p4test",$' "$f" || die "pin file: unexpected package"
  grep -Eq '^  "key_alias": "helper-p4-test",$' "$f" || die "pin file: unexpected key alias"
  n="$(grep -c 'certificate_sha256' "$f" || true)"
  [ "$n" = 1 ] || die "pin file: certificate_sha256 must appear exactly once"
  if grep -Eq '^  "certificate_sha256": null,$' "$f"; then printf '\n'; return 0; fi
  v="$(sed -n -E 's/^  "certificate_sha256": "([0-9a-f]{64})",$/\1/p' "$f")"
  [ ${#v} -eq 64 ] || die "pin file: certificate_sha256 is neither null nor 64 lowercase hex characters"
  printf '%s\n' "$v"
}
