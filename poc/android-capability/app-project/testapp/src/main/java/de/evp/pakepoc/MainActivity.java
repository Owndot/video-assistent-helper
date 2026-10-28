package de.evp.pakepoc;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Test-only UI. Every security action (pairing window, revocation, foreign
 * listener simulation, identity reset) is an explicit button press here;
 * there is no intent, socket or broadcast entry point for them (review R5).
 * The window is FLAG_SECURE so the pairing code does not end up in Recents
 * thumbnails or screenshots (review F5).
 */
public final class MainActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView status, codeView, log, memory;

    private Button button(LinearLayout root, String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        root.addView(b);
        return b;
    }

    private void send(String action) {
        Intent i = new Intent(this, PocService.class).setAction(action);
        if (PocService.instance == null) startForegroundService(i); else startService(i);
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false);
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);
        TextView banner = new TextView(this);
        banner.setText("TEST-APP – nicht der Video-Assistent-Helper.\nNur Wegwerf-Testidentitäten, keine echten Medien, Cookies oder URLs.");
        banner.setBackgroundColor(Color.rgb(176, 0, 32));
        banner.setTextColor(Color.WHITE);
        banner.setPadding(24, 24, 24, 24);
        banner.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(banner);
        status = new TextView(this);
        status.setTextIsSelectable(true);
        root.addView(status);
        button(root, "Testserver starten", v -> send(PocService.A_START));
        button(root, "Kopplungscode anzeigen (120 s, ein Versuch)", v -> {
            send(PocService.A_START);
            main.postDelayed(() -> { if (PocService.instance != null) PocService.instance.openWindow(); }, 300);
        });
        codeView = new TextView(this);
        codeView.setTextSize(40);
        codeView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        root.addView(codeView);
        button(root, "Alle Registrierungen widerrufen", v -> send(PocService.A_REVOKE));
        button(root, "Fremd-Listener simulieren: EIN", v -> send(PocService.A_ROGUE_ON));
        button(root, "Fremd-Listener simulieren: AUS (echter Testserver)", v -> send(PocService.A_ROGUE_OFF));
        button(root, "Speicher messen", v -> memory.setText(Measurements.snapshot()));
        memory = new TextView(this);
        memory.setTextIsSelectable(true);
        root.addView(memory);
        button(root, "Testidentität zurücksetzen …", v -> new AlertDialog.Builder(this)
            .setMessage("Neue Testidentität erzeugen und alle Registrierungen löschen? Gekoppelte Test-Erweiterungen müssen neu koppeln.")
            .setPositiveButton("Zurücksetzen", (d, w) -> send(PocService.A_RESET_IDENTITY))
            .setNegativeButton("Abbrechen", null).show());
        button(root, "Testserver beenden", v -> send(PocService.A_STOP));
        log = new TextView(this);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextSize(11);
        log.setTextIsSelectable(true);
        root.addView(log);
        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
    }

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            PocService s = PocService.instance;
            status.setText(s == null ? "Testserver: gestoppt" : s.status());
            long left = s == null || s.code == null ? 0 : (s.codeExpiresAt - s.now()) / 1000;
            codeView.setText(left > 0 ? s.code.substring(0, 4) + " " + s.code.substring(4) + "   " + left + " s" : "");
            log.setText(PocService.logText());
            main.postDelayed(this, 1000);
        }
    };

    @Override protected void onResume() { super.onResume(); main.post(refresh); }
    @Override protected void onPause() { main.removeCallbacks(refresh); super.onPause(); }
}
