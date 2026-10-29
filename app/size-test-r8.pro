# NUR GRÖSSENTEST (Zweig tmp/okhttp-size-test). App-Code inkl. JNI-Klassen bleibt vollständig und unbenannt erhalten
# (JNI-Symbole Java_poc_*, Reflexion, Instrumentierung); R8 kürzt und benennt nur Bibliothekscode.
-keep class de.evp.helfer.** { *; }
-keep class poc.** { *; }
-keepattributes SourceFile,LineNumberTable
