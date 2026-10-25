#!/usr/bin/env bash
# Übersetzt den Android-freien Kern des Helfers (Fetcher, HlsCore, DashCore, MiniServer) und die Tests
set -euo pipefail
cd "$(dirname "$0")"
SRC=../../app/src/main/java/de/evp/helfer
rm -rf classes && mkdir classes
javac -d classes $SRC/Tr.java $SRC/Fetcher.java $SRC/PublicSuffix.java $SRC/HlsCore.java $SRC/DashCore.java $SRC/MiniServer.java $SRC/SettingsTransfer.java \
  jt/TestMain.java jt/TestCoreHarnessFailure.java jt/TestHelper.java jt/TestPortConflict.java jt/TestSettingsTransfer.java jt/de/evp/helfer/TestCookie.java jt/de/evp/helfer/TestRedirectCookies.java jt/de/evp/helfer/TestTlsCookieBoundary.java jt/de/evp/helfer/TestReferrerPrivacy.java jt/TestMiniServerLimits.java
cp ../../app/src/main/resources/de/evp/helfer/public_suffix_rules.txt classes/de/evp/helfer/
echo "Übersetzt nach classes/"
