package de.evp.helfer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** Nach dem Einschalten (und nach einem Update der App) den Helfer wieder starten. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        SharedPreferences p = c.getSharedPreferences("helfer", Context.MODE_PRIVATE);
        if (!p.getBoolean("on", true)) return;
        if (Intent.ACTION_BOOT_COMPLETED.equals(i.getAction()) && !p.getBoolean("boot", true)) return;
        try { HelferService.start(c); } catch (Exception ignored) {}
    }
}
