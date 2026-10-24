#!/usr/bin/env bash
# Übersetzt den Android-freien Kern des Helfers (Fetcher, HlsCore, DashCore, MiniServer) und die Tests
set -euo pipefail
cd "$(dirname "$0")"
SRC=../../app/src/main/java/de/evp/helfer
rm -rf classes && mkdir classes
javac -d classes $SRC/Tr.java $SRC/Fetcher.java $SRC/HlsCore.java $SRC/DashCore.java $SRC/MiniServer.java \
  jt/TestMain.java jt/TestHelper.java jt/TestPortConflict.java jt/de/evp/helfer/TestCookie.java
echo "Übersetzt nach classes/"
