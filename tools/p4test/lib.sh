# Gemeinsame Prüfungen der P4-Testapp (wird von keygen.sh, sign.sh und selftest.sh eingebunden).
P4_PACKAGE='de.evp.helfer.p4test'
P4_ALIAS='helper-p4-test'
P4_DN='CN=Helper P4 TEST ONLY - durable'
P4_LABEL='P4 TEST Helfer'

die() { echo "REFUSED: $*" >&2; exit 1; }

# Nie Schlüssel oder signierte APKs in einem Git-Arbeitsverzeichnis (auch nicht in ignorierten Ordnern).
outside_git() {  # $1 Pfad (Datei oder Ordner; muss nicht existieren)
  local d; d="$(cd "$(dirname "$1")" 2>/dev/null && pwd)" || die "directory of $1 does not exist"
  if git -C "$d" rev-parse --is-inside-work-tree >/dev/null 2>&1; then die "$1 is inside a Git work tree"; fi
}

# SHA-256 (64 Hex, klein) des Zertifikats im Schlüsselspeicher; Passwort aus P4_KS_PASS.
keystore_cert_sha256() {  # $1 .p12
  keytool -exportcert -rfc -keystore "$1" -storetype PKCS12 -alias "$P4_ALIAS" -storepass:env P4_KS_PASS 2>/dev/null \
    | openssl x509 -noout -fingerprint -sha256 | cut -d= -f2 | tr -d ':' | tr 'A-F' 'a-f'
}

# Festgelegter Fingerabdruck aus release/p4test-signer.json (leer, solange kein Schlüssel festgelegt ist).
pinned_sha256() {  # $1 Pin-Datei
  python3 -I -c 'import json,re,sys
d=json.load(open(sys.argv[1]))
assert d["package"]=="de.evp.helfer.p4test" and d["key_alias"]=="helper-p4-test"
v=d.get("certificate_sha256") or ""
print(v if re.fullmatch("[0-9a-f]{64}",v) else "")' "$1"
}
