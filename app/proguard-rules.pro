# R8-Regeln des Helpers (release und channeltest). Grundsatz: App- und JNI-Code werden weder entfernt noch
# umbenannt; R8 kürzt nur Bibliothekscode (OkHttp, Okio, Kotlin, AndroidX). OkHttp bringt eigene Regeln mit.

# Gesamter App-Code (Activity/Service/Receiver, Kanal, Downloads, Reflexion, Instrumentierung).
-keep class de.evp.helfer.** { *; }

# Rust-Kern über JNI: native Methoden werden über Klassen- und Methodennamen (Java_poc_Core_*) gebunden.
-keep class poc.** { *; }
