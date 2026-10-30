# NUR GRÖSSENTEST (Zweig tmp/okhttp-size-test). App-Code inkl. JNI-Klassen bleibt vollständig und unbenannt erhalten
# (JNI-Symbole Java_poc_*, Reflexion, Instrumentierung); R8 kürzt und benennt nur Bibliothekscode.
-keep class de.evp.helfer.** { *; }
-keep class poc.** { *; }
-keepattributes SourceFile,LineNumberTable
# Nur für die Instrumentierung: AndroidJUnitRunner nutzt androidx.tracing aus der App (kommt mit androidx.startup);
# die Test-APK enthält es dann nicht selbst. Ohne diese Regel bleibt der Runner hängen.
-keep class androidx.tracing.** { *; }
