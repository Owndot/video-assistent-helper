#!/usr/bin/env bash
# Host models exercise actual Downloads; --http/--crash need sockets/subprocesses.
set -euo pipefail
cd "$(dirname "$0")/../.."
bash tests/java/build.sh
out=tests/java/lifecycle-classes
rm -rf "$out"
mkdir -p "$out"
mapfile -t fixtures < <(find tests/java/lifecycle -name '*.java' -print)
javac -cp tests/java/classes -d "$out" "${fixtures[@]}" app/src/main/java/de/evp/helfer/Downloads.java app/src/main/java/de/evp/helfer/HelferService.java
classpath="$out:tests/java/classes"
for case in pending blocking blocking-after admission legacy; do
  java -Djava.security.manager=allow -Xmx32m -cp "$classpath" de.evp.helfer.TestLifecycleReview "$case"
done
java -Xmx32m -cp "$classpath" de.evp.helfer.TestDownloadPublication
java -Xmx32m -cp "$classpath" de.evp.helfer.TestDownloadPublication --cancel
java -Xmx32m -cp "$classpath" de.evp.helfer.TestDownloadPublication --legacy
java -Xmx32m -cp "$classpath" de.evp.helfer.TestNativeLifecycle
java -Xmx32m -cp "$classpath" de.evp.helfer.TestDownloadLifecycle
if [[ ${1:-} == --transport ]]; then
  java -Xmx32m -cp "$classpath" de.evp.helfer.TestNativeLifecycle --http
  java -Xmx32m -cp "$classpath" de.evp.helfer.TestDownloadLifecycle --crash
  javac -cp tests/java/classes -d "$out" tests/android_e2e/TestCredentialedStream.java
  java -Xmx32m -cp "$classpath" de.evp.helfer.TestCredentialedStream
fi
