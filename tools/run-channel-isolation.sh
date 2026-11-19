# shellcheck shell=bash
# NUR TESTS, nur Wegwerf-Emulator. Testisolation der drei Gerätephasen von helper-channel.yml (P4-DELIVERY-READINESS.md,
# Abschnitt "Runde 5 - Testisolation der Gerätephasen"). Wird von tools/run-channel-publication-regression.sh (mutant),
# tools/run-channel-instrumentation.sh (main) und tools/run-flaky-repeat.sh (repeat) per "source" eingebunden.
#
# Hintergrund (A/B-Lauf 44405696921): Wird die Testvariante während der Boot-Scans des MediaProviders deinstalliert und
# sofort wieder installiert, kann das verspätet verarbeitete PACKAGE_FULLY_REMOVED Zeilen der NEUEN Installation verwaisen
# ("Orphaned N items belonging to de.evp.helfer.channeltest"). Deshalb läuft jede Phase auf einem eigenen, frisch
# erzeugten Emulator (-wipe-data); hier wird das belegt, nicht angenommen. Keine Wartezeit auf den MediaProvider: Der
# Protokollstrom wird ausschließlich mit eigenen Markierungen synchronisiert (begrenzt, sonst Fehlschlag).
#
# Ausgabe (Job-Log, von tools/device_gate.py streng geprüft):
#   ISOLATION-FRESH   phase= api= boot= devices=1 listed= app=absent test=absent
#       vor der ersten Installation: genau ein Gerät, eigene Boot-ID, Paketliste lesbar, beide Testpakete fehlen
#       (auch nicht als "deinstalliert mit Daten", pm list packages -u)
#   ISOLATION-OWNER   phase= api= t= window=before-install|measurement|after-uninstall event=orphaned pkg= count=
#                     bzw. event=owner-changed
#       JEDE MediaProvider-Besitzänderung im Strom (seit Boot, soweit gepuffert) mit Gerätezeit, Zeitfenster und Phase
#   ISOLATION-SUMMARY phase= api= boot= begin= install= uninstall= end= stream=complete|incomplete orphan_before=
#                     orphan_measurement= orphan_after= owner_changed_before= owner_changed_measurement= owner_changed_after=
#       orphan_* zählt nur Meldungen für die beiden Testpakete. Erwartbar sind sie NUR nach der eigenen Deinstallation
#       (after-uninstall, dokumentiert, kein Fehler); vor der Installation oder während der Messung sind sie ein
#       Isolationsbruch (Rückgabe 1).
#   ISOLATION-FAILED  phase= reason=   Vorbedingung verletzt (Abbruch vor jeder Installation, Exit 2)
# Rohdaten: <Bericht>/isolation-<phase>/ (Geräteliste, Paketliste, gefilterter Protokollstrom).

ISO_PKGS="de.evp.helfer.channeltest de.evp.helfer.channeltest.test"
ISO_PID=""; ISO_PHASE=""; ISO_DIR=""; ISO_STREAM=""; ISO_NONCE=""; ISO_BOOT=""; ISO_API=""; ISO_BAD=""

iso_stop() {
  if [ -n "${ISO_PID:-}" ]; then
    kill "$ISO_PID" 2>/dev/null || true
    wait "$ISO_PID" 2>/dev/null || true
    ISO_PID=""
  fi
}

iso_die() {   # Vorbedingung verletzt: nichts installieren
  echo "ISOLATION-FAILED phase=${ISO_PHASE:-unknown} reason=$1"
  iso_stop
  exit 2
}

# Markierung schreiben und warten, bis sie im eigenen Protokollstrom steht (höchstens 30 s). Synchronisiert nur den
# Strom mit dem Skript, nicht den MediaProvider. Fehlschlag wird in ISO_BAD vermerkt (stream=incomplete).
iso_mark() {
  local tag="ISOLATION-MARK:phase=$ISO_PHASE:event=$1:nonce=$ISO_NONCE" i
  if ! adb shell log -t ISOLATION -p i "$tag" > /dev/null 2>&1; then ISO_BAD="$ISO_BAD mark-$1"; return 0; fi
  for i in $(seq 1 150); do
    if grep -qF " ISOLATION: $tag" "$ISO_STREAM" 2>/dev/null; then return 0; fi
    if ! kill -0 "$ISO_PID" 2>/dev/null; then break; fi
    sleep 0.2
  done
  ISO_BAD="$ISO_BAD mark-$1"
  return 0
}

# iso_begin PHASE BERICHTSVERZEICHNIS: Frischenachweis vor der ersten Installation, Start des Protokollstroms.
iso_begin() {
  local n_all n_dev listed p
  ISO_PHASE="$1"; ISO_DIR="$2/isolation-$1"; ISO_BAD=""
  case "$ISO_PHASE" in mutant|main|repeat) ;; *) iso_die bad-phase ;; esac
  mkdir -p "$ISO_DIR"
  if ! adb devices > "$ISO_DIR/devices.txt" 2>&1; then iso_die adb-devices; fi
  n_all="$(tr -d '\r' < "$ISO_DIR/devices.txt" | awk 'f && NF {n++} /^List of devices attached/ {f=1} END {print n+0}')"
  n_dev="$(tr -d '\r' < "$ISO_DIR/devices.txt" | awk 'f && $2 == "device" {n++} /^List of devices attached/ {f=1} END {print n+0}')"
  if [ "$n_all" != 1 ] || [ "$n_dev" != 1 ]; then iso_die device-count; fi
  ISO_API="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
  case "$ISO_API" in ''|*[!0-9]*) iso_die api ;; esac
  ISO_BOOT="$(adb shell cat /proc/sys/kernel/random/boot_id | tr -d '\r')"
  if ! printf '%s\n' "$ISO_BOOT" | grep -Eqx '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}'; then iso_die boot-id; fi
  if ! adb shell pm list packages -u > "$ISO_DIR/packages.txt" 2>&1; then iso_die package-list; fi
  tr -d '\r' < "$ISO_DIR/packages.txt" > "$ISO_DIR/packages.clean"
  if ! grep -qx 'package:android' "$ISO_DIR/packages.clean"; then iso_die package-list; fi
  for p in $ISO_PKGS; do
    if grep -qx "package:$p" "$ISO_DIR/packages.clean"; then iso_die "package-present:$p"; fi
  done
  listed="$(grep -c '^package:' "$ISO_DIR/packages.clean" || true)"
  ISO_STREAM="$ISO_DIR/stream.txt"; : > "$ISO_STREAM"
  adb logcat -v epoch -s MediaProvider:V ISOLATION:V > "$ISO_STREAM" 2>&1 &
  ISO_PID=$!
  ISO_NONCE="$(date +%s)${RANDOM}${RANDOM}"
  iso_mark begin
  case "$ISO_BAD" in *mark-begin*) iso_die stream-begin ;; esac
  echo "ISOLATION-FRESH phase=$ISO_PHASE api=$ISO_API boot=$ISO_BOOT devices=1 listed=$listed app=absent test=absent"
}

# iso_end: Strom abschließen und auswerten. Rückgabe 0 nur bei vollständigem Strom und ohne Verwaisung der Testpakete
# vor der Installation oder während der Messung.
iso_end() {
  local alive=1
  iso_mark end
  if ! kill -0 "${ISO_PID:-0}" 2>/dev/null; then alive=0; fi
  iso_stop
  if [ "$alive" != 1 ]; then ISO_BAD="$ISO_BAD stream-died"; fi
  tr -d '\r' < "$ISO_STREAM" > "$ISO_DIR/stream.clean"
  awk -v phase="$ISO_PHASE" -v api="$ISO_API" -v boot="$ISO_BOOT" -v nonce="$ISO_NONCE" -v bad="$ISO_BAD" -v pkgs="$ISO_PKGS" '
    BEGIN { np = split(pkgs, P, " "); for (i = 1; i <= np; i++) ours[P[i]] = 1; n = 0 }
    $5 == "ISOLATION:" {
      if ($6 ~ ("^ISOLATION-MARK:phase=" phase ":event=[a-z]+:nonce=" nonce "$")) {
        split($6, f, ":"); ev = substr(f[3], 7); cnt[ev]++; tm[ev] = $1
      }
      next
    }
    $5 == "MediaProvider:" && $1 ~ /^[0-9]+\.[0-9]+$/ {
      if (match($0, /Orphaned [0-9]+ items belonging to [^ ]+/)) {
        split(substr($0, RSTART, RLENGTH), w, " "); pk = w[6]; sub(/[^A-Za-z0-9_]+$/, "", pk)
        n++; T[n] = $1; K[n] = "orphaned pkg=" pk " count=" w[2]; O[n] = (pk in ours)
      } else if (index($0, "Reason: owner_package_changed:") > 0) {
        n++; T[n] = $1; K[n] = "owner-changed"; O[n] = 0
      }
    }
    END {
      ok = (bad == "")
      split("begin install uninstall end", E, " ")
      for (i = 1; i <= 4; i++) if (cnt[E[i]] != 1) { ok = 0; tm[E[i]] = "-" }
      if (ok && !(tm["begin"] + 0 <= tm["install"] + 0 && tm["install"] + 0 <= tm["uninstall"] + 0 && tm["uninstall"] + 0 <= tm["end"] + 0)) ok = 0
      split("before measurement after", W, " ")
      for (i = 1; i <= 3; i++) { orph[W[i]] = 0; chg[W[i]] = 0 }
      for (i = 1; i <= n; i++) {
        if (!ok) win = "before"
        else if (T[i] + 0 < tm["install"] + 0) win = "before"
        else if (T[i] + 0 < tm["uninstall"] + 0) win = "measurement"
        else win = "after"
        label = (win == "before") ? "before-install" : (win == "after") ? "after-uninstall" : "measurement"
        if (ok) printf "ISOLATION-OWNER phase=%s api=%s t=%s window=%s event=%s\n", phase, api, T[i], label, K[i]
        if (K[i] == "owner-changed") chg[win]++; else if (O[i]) orph[win]++
      }
      printf "ISOLATION-SUMMARY phase=%s api=%s boot=%s begin=%s install=%s uninstall=%s end=%s stream=%s orphan_before=%d orphan_measurement=%d orphan_after=%d owner_changed_before=%d owner_changed_measurement=%d owner_changed_after=%d\n", \
        phase, api, boot, tm["begin"], tm["install"], tm["uninstall"], tm["end"], ok ? "complete" : "incomplete", \
        orph["before"], orph["measurement"], orph["after"], chg["before"], chg["measurement"], chg["after"]
      exit (ok && orph["before"] == 0 && orph["measurement"] == 0) ? 0 : 1
    }' "$ISO_DIR/stream.clean"
}
