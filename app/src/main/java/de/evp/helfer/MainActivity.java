package de.evp.helfer;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** Einstellungsseite: Rechte erteilen, Dienst starten/stoppen, ausprobieren. */
public class MainActivity extends Activity {
    private TextView stOverlay, stBattery, stService, stNotif, stPair;
    private Button bOverlay, bBattery, bService, bNotif, bPair;
    private LinearLayout root;

    static SharedPreferences prefs(Activity a) { return a.getSharedPreferences("helfer", MODE_PRIVATE); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, dp(28), pad, pad);
        sv.addView(root);
        setContentView(sv);

        TextView title = text("Video-Assistent Helfer", 24, true);
        root.addView(title);
        TextView intro = text("Damit der Videoplayer im Browser die echte Bildschirmhelligkeit und die Medienlautstärke "
                + "deines Handys steuern kann. Der Helfer läuft still im Hintergrund und ist nur vom Handy selbst "
                + "erreichbar. Die Helligkeit gilt nur, solange der Player offen ist – danach ist alles wieder wie vorher.", 15, false);
        intro.setAlpha(0.8f);
        intro.setPadding(0, dp(8), 0, dp(18));
        root.addView(intro);

        // 1. Helligkeit
        stOverlay = status();
        bOverlay = button("Erlauben", v -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()))));
        card("1 · Helligkeit", "„Über anderen Apps einblenden“ erlauben. Der Helfer legt dann ein unsichtbares 1-Pixel-Fenster "
                + "über den Bildschirm, das nur die Helligkeit wünscht.", stOverlay, bOverlay);

        // 2. Akku
        stBattery = status();
        bBattery = button("Ausnehmen", v -> {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
        });
        card("2 · Im Hintergrund bleiben", "Vom Akku-Sparen ausnehmen, sonst beendet Android den Helfer nach einer Weile. "
                + "Er verbraucht praktisch nichts: er wartet nur auf den Player.", stBattery, bBattery);

        // 3. Benachrichtigung (Android 13+)
        if (Build.VERSION.SDK_INT >= 33) {
            stNotif = status();
            bNotif = button("Erlauben", v -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1));
            card("3 · Benachrichtigung", "Android verlangt für Hintergrund-Helfer eine stille Benachrichtigung. "
                    + "Du kannst sie in den Android-Einstellungen danach ausblenden.", stNotif, bNotif);
        }

        // Koppeln: eigener Schlüssel statt des festen – dann kann keine andere App den Helfer benutzen
        stPair = status();
        bPair = button("Verbinden", v -> pair());
        card("4 · Mit dem Browser verbinden", "Öffnet eine kleine Seite im Browser mit Violentmonkey. Das Skript merkt sich dort "
                + "den geheimen Schlüssel dieser App. Danach kann nur noch dein Skript den Helfer benutzen, keine andere App.", stPair, bPair);
        stPair.setOnLongClickListener(v -> { Keys.reset(this); refresh(); android.widget.Toast.makeText(this, "Neuer Schlüssel – bitte neu verbinden", android.widget.Toast.LENGTH_LONG).show(); return true; });

        // Dienst
        stService = status();
        bService = button("Starten", v -> {
            if (HelferService.running) {
                stopService(new Intent(this, HelferService.class));
                prefs(this).edit().putBoolean("on", false).apply();
            } else {
                prefs(this).edit().putBoolean("on", true).apply();
                HelferService.start(this);
            }
            root.postDelayed(this::refresh, 400);
        });
        card("Helfer", "Startet auch automatisch nach dem Einschalten des Handys.", stService, bService);

        Switch boot = new Switch(this);
        boot.setText("Beim Einschalten automatisch starten");
        boot.setChecked(prefs(this).getBoolean("boot", true));
        boot.setOnCheckedChangeListener((c, on) -> prefs(this).edit().putBoolean("boot", on).apply());
        boot.setPadding(dp(4), dp(10), dp(4), dp(10));
        root.addView(boot);

        Button test = button("Ausprobieren: 3 Sekunden dunkel", v -> {
            if (!HelferService.running) HelferService.start(this);
            Intent i = new Intent(this, HelferService.class).setAction("test");
            startForegroundService(i);
        });
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.topMargin = dp(12);
        root.addView(test, tl);

        TextView foot = text("Im Userscript ist nichts einzustellen: Sobald der Helfer läuft, steuern die Wischgesten links "
                + "(Helligkeit) und rechts (Lautstärke) das Handy selbst. Läuft er nicht, regelt der Player wie bisher nur das Video.\n\n"
                + "Downloads: Der Knopf ⬇ im Player schickt Videos hierher – Dateien über Androids Download-Manager, Streams werden "
                + "Stück für Stück geladen und zu MP4 zusammengesetzt. Gespeichert in Download/Video-Assistent.", 13, false);
        foot.setAlpha(0.6f);
        foot.setPadding(0, dp(18), 0, 0);
        root.addView(foot);

        // Beim Öffnen gleich starten (solange nicht ausdrücklich gestoppt)
        if (prefs(this).getBoolean("on", true) && !HelferService.running) HelferService.start(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        root.postDelayed(this::refresh, 250);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        refresh();
        if (HelferService.running) {   // Benachrichtigung jetzt sichtbar machen
            stopService(new Intent(this, HelferService.class));
            HelferService.start(this);
        }
    }

    private void pair() {
        if (!HelferService.running) HelferService.start(this);
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:" + HelferService.PORT + "/pair#" + Keys.key(this)));
        i.addCategory(Intent.CATEGORY_BROWSABLE);
        try { startActivity(Intent.createChooser(i, "Browser mit Violentmonkey wählen")); } catch (Exception e) { startActivity(i); }
    }

    private void refresh() {
        boolean pd = Keys.paired(this);
        stPair.setText(pd ? "✓ Verbunden (eigener Schlüssel)" : "• Noch nicht verbunden");
        stPair.setTextColor(pd ? 0xFF4CD964 : 0xFFFF9F0A);
        bPair.setText(pd ? "Erneut" : "Verbinden");
        boolean ov = Settings.canDrawOverlays(this);
        set(stOverlay, bOverlay, ov, ov ? "Erlaubt" : "Noch nicht erlaubt");
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean bat = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        set(stBattery, bBattery, bat, bat ? "Ausgenommen" : "Android könnte den Helfer beenden");
        if (stNotif != null) {
            boolean n = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
            set(stNotif, bNotif, n, n ? "Erlaubt" : "Noch nicht erlaubt");
        }
        boolean on = HelferService.running;
        stService.setText(on ? "● Läuft" : "○ Gestoppt");
        stService.setTextColor(on ? 0xFF4CD964 : 0xFFFF9F0A);
        bService.setText(on ? "Stoppen" : "Starten");
    }

    private void set(TextView st, Button b, boolean ok, String msg) {
        st.setText((ok ? "✓ " : "• ") + msg);
        st.setTextColor(ok ? 0xFF4CD964 : 0xFFFF9F0A);
        b.setVisibility(ok ? View.GONE : View.VISIBLE);
    }

    private void card(String head, String body, TextView status, Button action) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(18));
        bg.setColor(0x14FFFFFF);
        bg.setStroke(dp(1), 0x22FFFFFF);
        c.setBackground(bg);
        c.addView(text(head, 17, true));
        TextView t = text(body, 14, false);
        t.setAlpha(0.75f);
        t.setPadding(0, dp(4), 0, dp(8));
        c.addView(t);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(action);
        c.addView(row);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        root.addView(c, lp);
    }

    private TextView status() { return text("", 14, true); }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
