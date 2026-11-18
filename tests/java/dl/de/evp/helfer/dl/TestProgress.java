package de.evp.helfer.dl;

/** P4c-3 (P1): Anzeige-Regeln von {@link Progress} – Prozent nur aus exaktem Zähler/Nenner, nie eine Schätzung. */
public final class TestProgress {
    static int passed, failed;
    static void check(String name, boolean ok, Object detail) {
        if (ok) { passed++; System.out.println("PASS " + name); }
        else { failed++; System.out.println("FAIL " + name + " :: " + detail); }
    }

    public static void main(String[] args) {
        final long MB = 1L << 20;
        Progress a = Progress.mp4(12 * MB, 32 * MB);
        check("MP4: Prozent aus exakter Länge", a.percent() == 37 && a.text(true).equals("37 % · 12,0 von 32,0 MB") && a.text(false).equals("37 % · 12.0 of 32.0 MB"), a.text(true));
        check("MP4: Balken 100/37", a.bar()[0] == 100 && a.bar()[1] == 37, a);
        check("MP4: fertig = 100 %", Progress.mp4(32 * MB, 32 * MB).percent() == 100, "");
        Progress over = Progress.mp4(5 * MB, 2 * MB);
        check("MP4: Zähler über Nenner → kein Prozent, kein „von“, unbestimmter Balken (nicht auf 100 % abgeschnitten)",
            over.percent() == -1 && over.text(true).equals("5,0 MB geladen") && over.bar()[0] == 0, over.text(true));
        Progress unknown = Progress.mp4(3 * MB, -1);
        check("MP4: ohne Größe → nur geladene MB", unknown.percent() == -1 && unknown.text(true).equals("3,0 MB geladen") && unknown.bar()[0] == 0, unknown.text(true));

        Progress h = Progress.hls(Progress.Phase.FETCH, 12, 25, 5 * MB + MB / 2);
        check("HLS: „12/25 Teile · 5,5 MB geladen“", h.text(true).equals("12/25 Teile · 5,5 MB geladen") && h.text(false).equals("12/25 parts · 5.5 MB loaded"), h.text(true));
        check("HLS: nie Prozent, nie Gesamtgröße", h.percent() == -1 && h.total == -1 && !h.text(true).contains("%"), h);
        check("HLS: Balken aus Teilen (25/12)", h.bar()[0] == 25 && h.bar()[1] == 12, h);
        Progress bad = Progress.hls(Progress.Phase.FETCH, 30, 25, MB);
        check("HLS: mehr fertige als alle Teile → keine Teilangabe (Fehler sichtbar, nicht abgeschnitten)",
            !bad.partsKnown() && bad.text(true).equals("1,0 MB geladen") && bad.bar()[0] == 0, bad.text(true));
        check("HLS: Teilezahl unbekannt → nur MB", Progress.hls(Progress.Phase.FETCH, 0, 0, 2 * MB).text(true).equals("2,0 MB geladen"), "");
        check("HLS: Phase zusammensetzen", Progress.hls(Progress.Phase.MUX, 25, 25, 9 * MB).text(true).equals("Wird zusammengesetzt …")
            && Progress.hls(Progress.Phase.MUX, 25, 25, 9 * MB).bar()[0] == 0, "");
        check("HLS: Phase prüfen", Progress.hls(Progress.Phase.VERIFY, 25, 25, 9 * MB).text(true).equals("Wird geprüft …"), "");
        check("HLS: Phase speichern", Progress.hls(Progress.Phase.SAVE, 25, 25, 9 * MB).text(false).equals("Saving …"), "");
        check("Größe: abgerundet, eine Stelle unter 100 MB, ganze MB darüber",
            Progress.size(MB * 99 + MB * 99 / 100, true).equals("99,9 MB") && Progress.size(250 * MB + 1, true).equals("250 MB") && Progress.size(0, true).equals("0,0 MB"), "");
        System.out.println("RESULT passed=" + passed + " failed=" + failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
