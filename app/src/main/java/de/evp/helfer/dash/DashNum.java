package de.evp.helfer.dash;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * S3a: strenge Zahlen und Dauern. Ganze Zahlen: nur Ziffern (XML-Leerraum am Rand erlaubt), höchstens 2^53−1
 * (DashLimits.MAX_SAFE), sonst "number"/"number-range". Dauern (xs:duration): nur Tage, Stunden, Minuten, Sekunden –
 * Jahre und Monate haben keine feste Länge und werden abgelehnt ("duration"); exakt als BigDecimal-Sekunden.
 */
final class DashNum {
    private DashNum() {}

    private static final Pattern DIGITS = Pattern.compile("[0-9]{1,40}");
    private static final Pattern DURATION =
        Pattern.compile("P(?:([0-9]{1,12})D)?(?:T(?:([0-9]{1,12})H)?(?:([0-9]{1,12})M)?(?:([0-9]{1,15}(?:\\.[0-9]{1,15})?)S)?)?");
    private static final BigDecimal SAFE = BigDecimal.valueOf(DashLimits.MAX_SAFE);

    /** Vorzeichenlose ganze Zahl ≤ 2^53−1. */
    static long parseU(String raw) throws DashException {
        String s = DashXml.xmlTrim(raw == null ? "" : raw);
        if (!DIGITS.matcher(s).matches()) throw new DashException("number");
        int i = 0;
        while (i < s.length() - 1 && s.charAt(i) == '0') i++;
        s = s.substring(i);
        if (s.length() > 16) throw new DashException("number-range");
        long v = Long.parseLong(s);
        if (v > DashLimits.MAX_SAFE) throw new DashException("number-range");
        return v;
    }

    /** S@r: ≥ 0 oder genau −1 (bis zum nächsten S bzw. Periodenende). */
    static long parseR(String raw) throws DashException {
        String s = DashXml.xmlTrim(raw == null ? "" : raw);
        if (s.equals("-1")) return -1;
        if (s.startsWith("-")) throw new DashException("number");
        return parseU(s);
    }

    /** Optionales ganzzahliges Attribut. */
    static Long optU(DashXml.El e, String k) throws DashException {
        if (e == null) return null;
        String v = e.attr(k);
        return v == null ? null : parseU(v);
    }

    /** xs:duration → Sekunden (exakt), oder Ausnahme "duration". Mindestens ein Bestandteil. */
    static BigDecimal duration(String raw) throws DashException {
        String s = DashXml.xmlTrim(raw == null ? "" : raw);
        Matcher m = DURATION.matcher(s);
        if (!m.matches() || (m.group(1) == null && m.group(2) == null && m.group(3) == null && m.group(4) == null) || s.endsWith("T"))
            throw new DashException("duration");
        BigDecimal v = BigDecimal.ZERO;
        if (m.group(1) != null) v = v.add(new BigDecimal(m.group(1)).multiply(BigDecimal.valueOf(86400)));
        if (m.group(2) != null) v = v.add(new BigDecimal(m.group(2)).multiply(BigDecimal.valueOf(3600)));
        if (m.group(3) != null) v = v.add(new BigDecimal(m.group(3)).multiply(BigDecimal.valueOf(60)));
        if (m.group(4) != null) v = v.add(new BigDecimal(m.group(4)));
        if (v.compareTo(SAFE) > 0) throw new DashException("number-range");
        return v;
    }

    static BigDecimal optDuration(DashXml.El e, String k) throws DashException {
        String v = e == null ? null : e.attr(k);
        return v == null ? null : duration(v);
    }

    /** Kanonische Schreibweise einer Dauer für den Fingerabdruck: Sekunden ohne Exponent und ohne Nachkommanullen. */
    static String canon(BigDecimal v) {
        if (v == null) return "-";
        if (v.signum() == 0) return "0";
        return v.stripTrailingZeros().toPlainString();
    }

    static long addExact(long a, long b) throws DashException {
        long r;
        try { r = Math.addExact(a, b); } catch (ArithmeticException e) { throw new DashException("number-range"); }
        if (r > DashLimits.MAX_SAFE || r < 0) throw new DashException("number-range");
        return r;
    }

    static long mulExact(long a, long b) throws DashException {
        long r;
        try { r = Math.multiplyExact(a, b); } catch (ArithmeticException e) { throw new DashException("number-range"); }
        if (r > DashLimits.MAX_SAFE || r < 0) throw new DashException("number-range");
        return r;
    }
}
