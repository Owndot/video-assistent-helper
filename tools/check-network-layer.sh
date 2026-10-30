#!/usr/bin/env bash
# Sperre (CI): Produktivcode erreicht OkHttp ausschließlich über die gehärtete Schicht de.evp.helfer.net.
#  - OkHttp/Okio werden nur in app/src/main/java/de/evp/helfer/net/ importiert;
#  - ein OkHttpClient (new OkHttpClient, OkHttpClient.Builder, newBuilder) entsteht nur in SafeHttpClient.java;
#  - der frühere ungehärtete Standardclient HttpClients existiert nicht mehr;
#  - Testzugänge (NetRules.forLocalTests, SafeHttpClient.TestTls, der Paket-Konstruktor) werden im Produktivcode nur
#    in ihren eigenen Definitionen genannt; produktiv entsteht der Client nur über SafeHttp/SafeHttpClient.strict.
#  Phase C2 (direkte MP4-Downloads):
#  - de.evp.helfer.dl, Mp4Downloads, ControlCommands und ChannelServer nutzen keinen anderen Netzwerkweg
#    (HttpURLConnection/URLConnection, java.net.URL/Socket, DownloadManager, Fetcher, Downloads.start, WebView, Cookies);
#  - de.evp.helfer.dl bleibt ohne Android-Typen (vollständig auf der JVM getestet);
#  - der alte Download-Einstieg Downloads.start (DownloadManager/Fetcher) hat keinen Aufrufer.
# Aufruf: tools/check-network-layer.sh [ROOT]      Selbsttest: tools/check-network-layer.sh --self-test
set -euo pipefail
if [ "${1:-}" = "--self-test" ]; then
  HERE="$(cd "$(dirname "$0")" && pwd)"; SRC_ROOT="$(cd "$HERE/.." && pwd)"
  fails=0
  probe() {  # $1 Beschreibung, $2 Datei relativ zu app/src/main/java, $3 Inhalt
    local w; w="$(mktemp -d)"; mkdir -p "$w/app/src/main"; cp -r "$SRC_ROOT/app/src/main/java" "$w/app/src/main/java"
    mkdir -p "$(dirname "$w/app/src/main/java/$2")"; printf '%s\n' "$3" >> "$w/app/src/main/java/$2"
    if bash "$0" "$w" > /dev/null 2>&1; then echo "SELF-TEST FAIL: not detected: $1"; fails=$((fails+1)); else echo "SELF-TEST ok: detected: $1"; fi
    rm -rf "$w"
  }
  bash "$0" "$SRC_ROOT" > /dev/null || { echo "SELF-TEST FAIL: clean tree rejected"; exit 1; }
  probe "okhttp import outside net" de/evp/helfer/Downloads.java 'import okhttp3.OkHttpClient;'
  probe "okio import outside net" de/evp/helfer/Fetcher.java 'import okio.Buffer;'
  probe "fully qualified okhttp use outside net" de/evp/helfer/MainActivity.java '// x = new okhttp3.Request.Builder();'
  probe "new OkHttpClient inside net" de/evp/helfer/net/SafeHttp.java '// new OkHttpClient();'
  probe "client builder inside net" de/evp/helfer/net/SafeResponse.java '// OkHttpClient.Builder b;'
  probe "newBuilder on a client" de/evp/helfer/net/SafeHttp.java '// shared.client.newBuilder()'
  probe "old HttpClients back" de/evp/helfer/HttpClients.java 'final class HttpClients {}'
  probe "test rules in production" de/evp/helfer/net/SafeHttp.java '// NetRules.forLocalTests(null, null, 1)'
  probe "test TLS in production" de/evp/helfer/net/SafeHttp.java '// new SafeHttpClient.TestTls(null, null, null)'
  probe "package constructor outside SafeHttpClient" de/evp/helfer/net/SafeHttp.java '// new SafeHttpClient(null, null, null)'
  probe "HttpURLConnection in dl" de/evp/helfer/dl/DirectDownloads.java '// java.net.HttpURLConnection c;'
  probe "openConnection in dl" de/evp/helfer/dl/DirectDownloads.java '// new java.net.URL(u).openConnection();'
  probe "openStream in Mp4Downloads" de/evp/helfer/Mp4Downloads.java '// url.openStream();'
  probe "DownloadManager in Mp4Downloads" de/evp/helfer/Mp4Downloads.java '// android.app.DownloadManager dm;'
  probe "Fetcher in dl" de/evp/helfer/dl/DownloadStore.java '// de.evp.helfer.Fetcher f;'
  probe "raw socket in dl" de/evp/helfer/dl/DirectDownloads.java '// new java.net.Socket();'
  probe "old Downloads.start from control commands" de/evp/helfer/ControlCommands.java '// Downloads.start(null, null, null, null, null, null, null, false);'
  probe "old Downloads.start from service" de/evp/helfer/HelferService.java '// Downloads.start(this, u, null, null, null, null, null, false);'
  probe "old Downloads.start inside Downloads" de/evp/helfer/Downloads.java '// int x = start(null, null, null, null, null, null, null, false);'
  probe "Mp4Downloads with another client" de/evp/helfer/HelferService.java '// new Mp4Downloads(this, "x", null);'
  probe "android types in dl" de/evp/helfer/dl/DirectDownloads.java '// android.util.Log.i("x", "y");'
  probe "cookie manager in Mp4Downloads" de/evp/helfer/Mp4Downloads.java '// android.webkit.CookieManager.getInstance();'
  [ "$fails" = 0 ] && echo "RESULT network-layer-guard self-test PASS" || { echo "RESULT network-layer-guard self-test FAIL"; exit 1; }
  exit 0
fi
ROOT="$(cd "${1:-$(dirname "$0")/..}" && pwd)"
MAIN="$ROOT/app/src/main/java"
NET="de/evp/helfer/net"
problems=()
add() { problems+=("$1"); }
while IFS= read -r f; do add "OkHttp/Okio outside $NET: ${f#$MAIN/}"; done < <(grep -rlE '(^|[^A-Za-z0-9_.])(okhttp3|okio)\.' "$MAIN" --include=*.java | grep -v "/$NET/" || true)
while IFS= read -r f; do add "OkHttpClient constructed outside SafeHttpClient.java: ${f#$MAIN/}"; done < <(grep -rlE 'new OkHttpClient|OkHttpClient\.Builder|\.newBuilder\(' "$MAIN" --include=*.java | grep -v "/$NET/SafeHttpClient\.java$" || true)
[ -e "$MAIN/de/evp/helfer/HttpClients.java" ] && add "unhardened HttpClients is back"
while IFS= read -r f; do add "NetRules.forLocalTests used in production: ${f#$MAIN/}"; done < <(grep -rl 'forLocalTests' "$MAIN" --include=*.java | grep -v "/$NET/NetRules\.java$" || true)
while IFS= read -r f; do add "TestTls used in production: ${f#$MAIN/}"; done < <(grep -rl 'TestTls' "$MAIN" --include=*.java | grep -v "/$NET/SafeHttpClient\.java$" || true)
while IFS= read -r f; do add "package-private SafeHttpClient constructor used: ${f#$MAIN/}"; done < <(grep -rl 'new SafeHttpClient(' "$MAIN" --include=*.java | grep -v "/$NET/SafeHttpClient\.java$" || true)
# Phase C2: kein ungeschützter Netzwerkweg für direkte Downloads und den Kanal.
C2_FILES=("$MAIN/de/evp/helfer/Mp4Downloads.java" "$MAIN/de/evp/helfer/ControlCommands.java" "$MAIN/de/evp/helfer/ChannelServer.java")
for f in "$MAIN"/de/evp/helfer/dl/*.java; do C2_FILES+=("$f"); done
C2_FORBIDDEN='HttpURLConnection|URLConnection|openConnection|openStream|java\.net\.(URL|Socket|HttpCookie|CookieHandler)\b|new (URL|Socket)\(|DownloadManager|Fetcher|Downloads\.start|HlsCore|DashCore|WebView|CookieManager'
for f in "${C2_FILES[@]}"; do
  [ -f "$f" ] || { add "C2 file missing: ${f#$MAIN/}"; continue; }
  pattern="$C2_FORBIDDEN"
  # ChannelServer ist selbst der lokale Kanal-Server (ServerSocket/Socket auf 127.0.0.1): nur dort Sockets erlaubt.
  [ "${f##*/}" = ChannelServer.java ] && pattern='HttpURLConnection|URLConnection|openConnection|openStream|java\.net\.(URL|HttpCookie|CookieHandler)\b|new URL\(|DownloadManager|Fetcher|Downloads\.start|HlsCore|DashCore|WebView|CookieManager'
  if grep -qE "$pattern" "$f"; then add "unprotected network path in ${f#$MAIN/}: $(grep -noE "$pattern" "$f" | head -1)"; fi
done
while IFS= read -r f; do add "Mp4Downloads constructed outside Mp4Downloads.get: ${f#$MAIN/}"; done < <(grep -rl 'new Mp4Downloads(' "$MAIN" --include=*.java | grep -v '/de/evp/helfer/Mp4Downloads\.java$' || true)
[ "$(grep -c 'new Mp4Downloads(' "$MAIN/de/evp/helfer/Mp4Downloads.java")" = 1 ] && grep -q 'new Mp4Downloads(app, "mp4-jobs-v1", SafeHttp.client(app))' "$MAIN/de/evp/helfer/Mp4Downloads.java" \
  || add "Mp4Downloads must be created only with SafeHttp.client"
while IFS= read -r f; do add "Android types in de.evp.helfer.dl: ${f#$MAIN/}"; done < <(grep -rlE '(^|[^A-Za-z0-9_])android\.' "$MAIN/de/evp/helfer/dl" --include=*.java || true)
while IFS= read -r f; do add "old Downloads.start called: ${f#$MAIN/}"; done < <(grep -rlE 'Downloads\.start[[:space:]]*\(' "$MAIN" --include=*.java || true)
if grep -nE '(^|[^A-Za-z0-9_.])start[[:space:]]*\(' "$MAIN/de/evp/helfer/Downloads.java" | grep -vq 'static int start('; then add "old Downloads.start called inside Downloads.java"; fi
# In SafeHttpClient.java selbst: genau ein OkHttpClient.Builder, keine Herausgabe des Clients.
SAFE="$MAIN/$NET/SafeHttpClient.java"
[ "$(grep -c 'new OkHttpClient.Builder()' "$SAFE")" = 1 ] || add "SafeHttpClient must build exactly one OkHttpClient"
grep -qE 'public[^;(]*OkHttpClient[^;(]*\(' "$SAFE" && add "SafeHttpClient exposes OkHttpClient"
grep -qE '(public|protected)[^=;(]*(Interceptor|Dns|SocketFactory)[^;(]*\(' "$SAFE" && add "SafeHttpClient exposes interceptors, DNS or socket factory"
if [ "${#problems[@]}" -gt 0 ]; then printf 'NETWORK LAYER GUARD: %s\n' "${problems[@]}" >&2; exit 1; fi
echo "network layer guard: OK (OkHttp only via $NET, one hardened client, no test hooks in production code, C2 downloads only via SafeHttpClient)"
