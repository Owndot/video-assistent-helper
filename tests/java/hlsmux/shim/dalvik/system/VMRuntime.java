package dalvik.system;
/** JVM-Shim nur für lokale Läufe. */
public final class VMRuntime {
  private static final VMRuntime R = new VMRuntime();
  public static VMRuntime getRuntime() { return R; }
  public boolean is64Bit() { return true; }
  public Object newUnpaddedArray(Class<?> c, int n) { return java.lang.reflect.Array.newInstance(c, n); }
  public Object newNonMovableArray(Class<?> c, int n) { return java.lang.reflect.Array.newInstance(c, n); }
  public static int getCurrentInstructionSet() { return 0; }
  public int getTargetSdkVersion() { return 34; }
}
