package android.util;
/** JVM-Shim nur für lokale Läufe: Ausgaben nach stderr. */
public final class Log {
  public static final int VERBOSE=2, DEBUG=3, INFO=4, WARN=5, ERROR=6, ASSERT=7;
  static int p(String l, String t, String m, Throwable e) { System.err.println(l + "/" + t + ": " + m + (e != null ? " " + e : "")); return 0; }
  public static int v(String t, String m) { return 0; } public static int v(String t, String m, Throwable e) { return 0; }
  public static int d(String t, String m) { return 0; } public static int d(String t, String m, Throwable e) { return 0; }
  public static int i(String t, String m) { return p("I", t, m, null); } public static int i(String t, String m, Throwable e) { return p("I", t, m, e); }
  public static int w(String t, String m) { return p("W", t, m, null); } public static int w(String t, String m, Throwable e) { return p("W", t, m, e); }
  public static int w(String t, Throwable e) { return p("W", t, "", e); }
  public static int e(String t, String m) { return p("E", t, m, null); } public static int e(String t, String m, Throwable e) { return p("E", t, m, e); }
  public static String getStackTraceString(Throwable e) { java.io.StringWriter s = new java.io.StringWriter(); if (e != null) e.printStackTrace(new java.io.PrintWriter(s)); return s.toString(); }
  public static boolean isLoggable(String t, int l) { return false; }
  public static int println(int p, String t, String m) { return 0; }
}
