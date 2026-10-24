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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Einstellungsseite (dunkel, wie der Player): oben Status, darunter die Schritte – erledigte Schritte schrumpfen zu
 * einer Zeile mit ✓, offene zeigen Erklärung und Knopf. Unten Einstellungen (Sprache, Autostart, Ausprobieren).
 * Die Seite wird bei jeder Rückkehr neu aufgebaut (Rechte können sich in den Android-Einstellungen geändert haben).
 */
public class MainActivity extends Activity {
    // Farben: dunkel (wie der Player) oder hell – einfarbig wie iOS/One UI: Hauptknöpfe weiß (dunkel) bzw. schwarz (hell)
    private int BG, CARD, LINE, TXT, SUB, ACC, ACC_TXT, OUT, OK, OK_TXT, WARN, SEG;
    private boolean dark;

    /** Design: "auto" (wie das Handy), "light" oder "dark" */
    static String theme(Activity a) { return prefs(a).getString("theme", "auto"); }

    private void palette() {
        String t = theme(this);
        boolean sysDark = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        dark = "dark".equals(t) || (!"light".equals(t) && sysDark);
        if (dark) {
            BG = 0xFF0E1014; CARD = 0xFF171A21; LINE = 0xFF262A33; TXT = 0xFFF2F4F7; SUB = 0xFFA3A9B5;
            ACC = 0xFFF2F4F7; ACC_TXT = 0xFF0E1014; OUT = 0x55F2F4F7; OK = 0xFF34C759; OK_TXT = 0xFF06210F; WARN = 0xFFFFB020; SEG = 0xFF0E1014;
        } else {
            BG = 0xFFF2F3F7; CARD = 0xFFFFFFFF; LINE = 0xFFE3E5EA; TXT = 0xFF111318; SUB = 0xFF5F6470;
            ACC = 0xFF111318; ACC_TXT = 0xFFFFFFFF; OUT = 0x40111318; OK = 0xFF1E9E4A; OK_TXT = 0xFFFFFFFF; WARN = 0xFFB26A00; SEG = 0xFFF2F3F7;
        }
        try {
            getWindow().setStatusBarColor(BG);
            getWindow().setNavigationBarColor(BG);
            View d = getWindow().getDecorView();
            int f = dark ? 0 : (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            d.setSystemUiVisibility(f);
        } catch (Exception ignored) {}
        if (sv != null) sv.setBackgroundColor(BG);
    }
    private ScrollView sv;

    private LinearLayout root;

    static SharedPreferences prefs(Activity a) { return a.getSharedPreferences("helfer", MODE_PRIVATE); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Keys.applyLang(this);
        sv = new ScrollView(this);
        sv.setFillViewport(true);
        palette();
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(26), dp(18), dp(28));
        sv.addView(root);
        setContentView(sv);
        // Beim Öffnen gleich starten (solange nicht ausdrücklich gestoppt)
        if (prefs(this).getBoolean("on", true) && !HelferService.running) HelferService.start(this);
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        root.postDelayed(this::build, 250);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        if (HelferService.running) {   // Benachrichtigung jetzt sichtbar machen
            stopService(new Intent(this, HelferService.class));
            HelferService.start(this);
        }
        build();
    }

    // ---------- Aufbau ----------
    private void build() {
        palette();
        root.removeAllViews();
        boolean ov = Settings.canDrawOverlays(this);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean bat = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        boolean needNotif = Build.VERSION.SDK_INT >= 33;
        boolean notif = !needNotif || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        boolean paired = Keys.paired(this);
        boolean on = HelferService.running;

        // Kopf: Symbol, Name, Status, Start/Stopp
        LinearLayout head = row();
        ImageView ic = new ImageView(this);   // App-Symbol: Vordergrund auf runder Fläche (das adaptive Symbol wäre eckig)
        ic.setImageResource(R.drawable.ic_fg);
        ic.setScaleType(ImageView.ScaleType.FIT_CENTER);
        ic.setBackground(round(0xFF16181D, dp(16), LINE));
        head.addView(ic, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout ht = col();
        ht.setPadding(dp(14), 0, dp(8), 0);
        ht.addView(text(Tr.t("Video-Assistent Helper", "Video Assistant Helper"), 21, true, TXT));
        TextView st = text((on ? "● " + Tr.t("Läuft", "Running") : "○ " + Tr.t("Gestoppt", "Stopped"))
                + (paired ? "  ·  " + Tr.t("Verbunden", "Connected") : ""), 14, true, on ? OK : WARN);
        st.setPadding(0, dp(2), 0, 0);
        ht.addView(st);
        head.addView(ht, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(pill(on ? Tr.t("Stoppen", "Stop") : Tr.t("Starten", "Start"), false, v -> {
            if (HelferService.running) {
                stopService(new Intent(this, HelferService.class));
                prefs(this).edit().putBoolean("on", false).apply();
            } else {
                prefs(this).edit().putBoolean("on", true).apply();
                HelferService.start(this);
            }
            root.postDelayed(this::build, 400);
        }));
        root.addView(head);
        if (HelferService.startupError != null) root.addView(text(HelferService.startupError, 15, false, WARN));

        // Zusammenfassung
        int open = (ov ? 0 : 1) + (bat ? 0 : 1) + (notif ? 0 : 1) + (paired ? 0 : 1);
        TextView sum = text(open == 0
                ? Tr.t("Alles bereit – im Player steuern die Wischgesten jetzt Helligkeit und Lautstärke des Handys.",
                       "All set – swiping in the player now controls your phone's brightness and volume.")
                : Tr.t(open == 1 ? "Noch 1 Schritt" : "Noch " + open + " Schritte", open == 1 ? "1 step left" : open + " steps left")
                  + Tr.t(" – dann steuert der Player Helligkeit und Lautstärke des Handys.", " – then the player controls your phone's brightness and volume."),
                15, false, SUB);
        sum.setPadding(dp(2), dp(16), dp(2), dp(14));
        root.addView(sum);

        // Schritte
        step(1, Tr.t("Helligkeit", "Brightness"), ov, Tr.t("Erlaubt", "Allowed"),
                Tr.t("„Über anderen Apps einblenden“ erlauben. Der Helper legt dann ein unsichtbares 1-Pixel-Fenster über den Bildschirm, das nur die Helligkeit wünscht.",
                     "Allow “Display over other apps”. The helper then places an invisible 1-pixel window on the screen that only requests the brightness."),
                Tr.t("Erlauben", "Allow"),
                v -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))), null);
        step(2, Tr.t("Im Hintergrund bleiben", "Stay in the background"), bat, Tr.t("Ausgenommen", "Excluded"),
                Tr.t("Vom Akku-Sparen ausnehmen, sonst beendet Android den Helper nach einer Weile. Er verbraucht praktisch nichts: er wartet nur auf den Player.",
                     "Exclude it from battery saving, otherwise Android stops the helper after a while. It uses practically nothing: it just waits for the player."),
                Tr.t("Ausnehmen", "Exclude"), v -> {
                    try { startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))); }
                    catch (Exception e) { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
                }, null);
        if (needNotif) step(3, Tr.t("Benachrichtigung", "Notification"), notif, Tr.t("Erlaubt", "Allowed"),
                Tr.t("Android verlangt für Hintergrund-Helfer eine stille Benachrichtigung. Du kannst sie danach in den Android-Einstellungen ausblenden.",
                     "Android requires a silent notification for background helpers. You can hide it afterwards in the Android settings."),
                Tr.t("Erlauben", "Allow"), v -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1), null);
        step(needNotif ? 4 : 3, Tr.t("Mit dem Browser verbinden", "Connect to the browser"), paired, Tr.t("Verbunden", "Connected"),
                Tr.t("Öffnet eine kleine Seite im Browser mit Violentmonkey. Das Skript merkt sich dort den geheimen Schlüssel dieser App – danach kann nur dein Skript den Helper benutzen.",
                     "Opens a small page in the browser with Violentmonkey. The script remembers this app's secret key there – afterwards only your script can use the helper."),
                Tr.t("Verbinden", "Connect"), v -> pair(),
                // erledigt: weiteren Browser verbinden (z. B. Firefox) · lange drücken = neuer Schlüssel
                pill(Tr.t("Weiterer Browser", "Another browser"), false, v -> pair()));

        // Einstellungen
        LinearLayout set = card();
        set.addView(text(Tr.t("Einstellungen", "Settings"), 13, true, SUB));
        LinearLayout lr = row();
        lr.setPadding(0, dp(12), 0, dp(6));
        lr.addView(text("Sprache · Language", 16, false, TXT), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        set.addView(lr);
        set.addView(segmented(new String[]{"Auto", "Deutsch", "English"}, new String[]{"auto", "de", "en"}, Keys.lang(this), l -> {
            Keys.setLang(this, l);
            if (HelferService.running) {   // Benachrichtigung in der neuen Sprache
                stopService(new Intent(this, HelferService.class));
                HelferService.start(this);
            }
            build();
        }));
        LinearLayout tr = row();
        tr.setPadding(0, dp(16), 0, dp(6));
        tr.addView(text(Tr.t("Design", "Theme"), 16, false, TXT), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        set.addView(tr);
        set.addView(segmented(new String[]{"Auto", Tr.t("Hell", "Light"), Tr.t("Dunkel", "Dark")}, new String[]{"auto", "light", "dark"}, theme(this), t -> {
            prefs(this).edit().putString("theme", t).apply();
            build();
        }));
        Switch boot = new Switch(this);
        boot.setText(Tr.t("Beim Einschalten des Handys starten", "Start when the phone is switched on"));
        boot.setTextColor(TXT);
        boot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        boot.setChecked(prefs(this).getBoolean("boot", true));
        boot.setOnCheckedChangeListener((c, isOn) -> prefs(this).edit().putBoolean("boot", isOn).apply());
        boot.setPadding(0, dp(16), 0, dp(8));
        set.addView(boot, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView test = pill(Tr.t("Ausprobieren: 3 Sekunden dunkel", "Try it: 3 seconds dark"), false, v -> {
            if (!HelferService.running) HelferService.start(this);
            startForegroundService(new Intent(this, HelferService.class).setAction("test"));
        });
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.topMargin = dp(8);
        set.addView(test, tl);
        addCard(set);

        // Hinweis unten
        TextView foot = text(Tr.t("Im Userscript ist nichts einzustellen: Sobald der Helper läuft, steuern die Wischgesten links (Helligkeit) und rechts (Lautstärke) das Handy selbst. "
                        + "Läuft er nicht, regelt der Player wie bisher nur das Video.\n\nDownloads: Der Knopf ⬇ im Player schickt Videos hierher – Dateien über Androids Download-Manager, "
                        + "Streams werden Stück für Stück geladen und zu MP4 zusammengesetzt. Gespeichert in Download/Video-Assistent.",
                "Nothing to set up in the userscript: as soon as the helper runs, swiping on the left (brightness) and right (volume) controls the phone itself. "
                        + "If it doesn't run, the player only adjusts the video as before.\n\nDownloads: the ⬇ button in the player sends videos here – files via Android's download manager, "
                        + "streams are loaded piece by piece and joined into an MP4. Saved in Download/Video-Assistent."), 13, false, SUB);
        foot.setAlpha(0.8f);
        foot.setPadding(dp(4), dp(10), dp(4), 0);
        root.addView(foot);
    }

    /** Ein Schritt: offen = Nummer, Titel, Erklärung, Knopf · erledigt = eine Zeile mit ✓ (und optional einem Zusatzknopf) */
    private void step(int n, String title, boolean ok, String okText, String desc, String btn, View.OnClickListener act, View extra) {
        LinearLayout c = card();
        LinearLayout r = row();
        TextView num = new TextView(this);
        num.setText(ok ? "✓" : String.valueOf(n));
        num.setGravity(Gravity.CENTER);
        num.setTextSize(TypedValue.COMPLEX_UNIT_SP, ok ? 15 : 14);
        num.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        num.setTextColor(ok ? OK_TXT : ACC_TXT);
        num.setBackground(round(ok ? OK : ACC, dp(14), 0));
        r.addView(num, new LinearLayout.LayoutParams(dp(28), dp(28)));
        LinearLayout t = col();
        t.setPadding(dp(12), 0, dp(6), 0);
        t.addView(text(title, 16, true, TXT));
        if (ok) t.addView(text(okText, 13, false, OK));
        r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (ok && extra != null) r.addView(extra);
        c.addView(r);
        if (!ok) {
            TextView d = text(desc, 14, false, SUB);
            d.setPadding(0, dp(10), 0, dp(12));
            c.addView(d);
            c.addView(pill(btn, true, act), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        if (extra != null) {   // Verbinden-Karte: lange drücken = neuer Schlüssel
            c.setOnLongClickListener(v -> {
                Keys.reset(this);
                Toast.makeText(this, Tr.t("Neuer Schlüssel – bitte neu verbinden", "New key – please connect again"), Toast.LENGTH_LONG).show();
                build();
                return true;
            });
        }
        addCard(c);
    }

    private interface Pick { void on(String v); }

    /** Auswahl wie bei Android-Einstellungen: drei Felder nebeneinander, das gewählte hervorgehoben */
    private View segmented(String[] labels, String[] values, String cur, Pick pick) {
        LinearLayout s = row();
        s.setBackground(round(SEG, dp(14), LINE));
        s.setPadding(dp(4), dp(4), dp(4), dp(4));
        for (int i = 0; i < labels.length; i++) {
            final String val = values[i];
            boolean sel = val.equals(cur);
            TextView o = text(labels[i], 15, sel, sel ? ACC_TXT : TXT);
            o.setGravity(Gravity.CENTER);
            o.setPadding(0, dp(9), 0, dp(9));
            if (sel) o.setBackground(round(ACC, dp(11), 0));
            o.setOnClickListener(v -> pick.on(val));
            s.addView(o, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        return s;
    }

    private void pair() {
        if (!HelferService.running) HelferService.start(this);
        pairWhenReady(0);
    }

    private void pairWhenReady(int attempt) {
        if (!HelferService.running) {
            if (HelferService.startupError != null || attempt >= 20) {
                Toast.makeText(this, HelferService.startupError != null ? HelferService.startupError :
                    Tr.t("Helper nicht bereit. Bitte erneut starten.", "Helper not ready. Please restart."), Toast.LENGTH_LONG).show();
                build();
                return;
            }
            root.postDelayed(() -> pairWhenReady(attempt + 1), 100);
            return;
        }
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:" + HelferService.PORT + "/pair#" + Keys.key(this)));
        i.addCategory(Intent.CATEGORY_BROWSABLE);
        try { startActivity(Intent.createChooser(i, Tr.t("Browser mit Violentmonkey wählen", "Choose the browser with Violentmonkey"))); } catch (Exception e) { startActivity(i); }
    }

    // ---------- Bausteine ----------
    private LinearLayout card() {
        LinearLayout c = col();
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        c.setBackground(round(CARD, dp(20), LINE));
        return c;
    }

    private void addCard(View c) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        root.addView(c, lp);
    }

    /** Knopf als Kapsel: gefüllt (Hauptaktion) oder mit Rand */
    private TextView pill(String label, boolean filled, View.OnClickListener l) {
        TextView b = text(label, 15, true, filled ? ACC_TXT : TXT);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(16), dp(10), dp(16), dp(10));
        b.setBackground(round(filled ? ACC : 0x00000000, dp(22), filled ? 0 : OUT));
        b.setClickable(true);
        b.setOnClickListener(l);
        return b;
    }

    private GradientDrawable round(int color, int radius, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private LinearLayout col() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        return c;
    }

    private TextView text(String s, int sp, boolean bold, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setLineSpacing(0, 1.12f);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
