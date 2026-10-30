# R8-Regeln des Helpers (release und channeltest). Grundsatz: App- und JNI-Code werden weder entfernt noch
# umbenannt; R8 kürzt nur Bibliothekscode (OkHttp, Okio, Kotlin, AndroidX). OkHttp bringt eigene Regeln mit.

# Gesamter App-Code (Activity/Service/Receiver, Kanal, Downloads, Reflexion, Instrumentierung).
-keep class de.evp.helfer.** { *; }

# Rust-Kern über JNI: native Methoden werden über Klassen- und Methodennamen (Java_poc_Core_*) gebunden.
-keep class poc.** { *; }

# Keine Umbenennung (auch nicht in Bibliotheken): Stacktraces bleiben ohne Mapping-Datei lesbar, und die
# Instrumentierungs-APK verweist auf dieselben Namen wie die App (Grundlage für r8-test-references.pro).
-dontobfuscate
