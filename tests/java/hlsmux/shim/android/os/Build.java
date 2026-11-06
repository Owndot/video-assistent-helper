package android.os;
/** JVM-Shim nur für lokale Läufe (nicht auf dem Gerät). */
public class Build {
  public static final String BOARD="jvm", BOOTLOADER="jvm", BRAND="jvm", DEVICE="jvm", DISPLAY="jvm", FINGERPRINT="jvm", HARDWARE="jvm",
    HOST="jvm", ID="jvm", MANUFACTURER="jvm", MODEL="jvm", PRODUCT="jvm", TAGS="jvm", TYPE="user", USER="jvm", SOC_MANUFACTURER="jvm", SOC_MODEL="jvm", SKU="jvm", ODM_SKU="jvm";
  public static final String[] SUPPORTED_ABIS = {"x86_64"};
  public static final long TIME = 0;
  public static final String UNKNOWN = "unknown";
  public static class VERSION { public static final int SDK_INT = 34; public static final String CODENAME = "REL", RELEASE = "14", INCREMENTAL = "jvm", SECURITY_PATCH = ""; public static final int SDK_INT_FULL = 3400000; }
  public static class VERSION_CODES { public static final int CUR_DEVELOPMENT = 10000; }
  public static String getSerial() { return UNKNOWN; }
  public static String getRadioVersion() { return null; }
}
