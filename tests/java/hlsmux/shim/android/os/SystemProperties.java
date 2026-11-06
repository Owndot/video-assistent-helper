package android.os;
/** JVM-Shim nur für lokale Läufe (nicht auf dem Gerät): Systemeigenschaften leer. */
public class SystemProperties {
  public static String get(String k) { return ""; }
  public static String get(String k, String d) { return d; }
  public static int getInt(String k, int d) { return d; }
  public static long getLong(String k, long d) { return d; }
  public static boolean getBoolean(String k, boolean d) { return d; }
  public static void set(String k, String v) { }
}
