package de.evp.helfer;

import android.content.Context;

import okhttp3.OkHttp;
import okhttp3.OkHttpClient;

/**
 * Einziger Ort, an dem der Helper OkHttp-Clients erzeugt. Phase B: eingebunden und auf dem Gerät getestet, aber von
 * keiner Funktion genutzt. Nicht für Downloads verwenden, bevor die sichere Netzwerkschicht (Phase C) SSRF/DNS-
 * Rebinding, Proxys, Weiterleitungen, Wiederholungen und Größenlimits festlegt.
 *
 * Die AndroidX-Startkomponente von OkHttp ist im Manifest entfernt; den Anwendungs-Context (für die Public-Suffix-
 * Liste) setzt stattdessen {@link OkHttp#initialize}, die von OkHttp dafür vorgesehene API.
 */
final class HttpClients {
    private static OkHttpClient shared;

    private HttpClients() {}

    static synchronized OkHttpClient shared(Context context) {
        if (shared == null) {
            OkHttp.INSTANCE.initialize(context.getApplicationContext());
            shared = new OkHttpClient();
        }
        return shared;
    }
}
