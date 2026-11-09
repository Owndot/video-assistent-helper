package android.app;
/** JVM-Shim nur für lokale Läufe. */
public class ActivityManager { public static boolean isRunningInTestHarness() { return false; } public static boolean isRunningInUserTestHarness() { return false; } public static boolean isUserAMonkey() { return false; } }
