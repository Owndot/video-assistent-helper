# NUR GRÖSSENTEST, NUR für den Instrumentierungslauf (Variante ...r8t): AndroidJUnitRunner/androidx.test nutzen
# Kotlin- und AndroidX-Klassen aus der App, sobald OkHttp sie mitbringt (die Test-APK enthält sie dann nicht selbst);
# R8 hätte sie entfernt (gemessen: NoClassDefFoundError kotlin.jvm.internal.Lambda im Runner, vor dem ersten Test).
-keep class kotlin.** { *; }
-keep class androidx.** { *; }
