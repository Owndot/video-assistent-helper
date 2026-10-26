package android.content;
/** Test-only subset used to compile the unchanged production Keys class on the JVM. */
public interface SharedPreferences {
    String getString(String key, String fallback);
    boolean getBoolean(String key, boolean fallback);
    Editor edit();
    interface Editor {
        Editor putString(String key, String value);
        Editor putBoolean(String key, boolean value);
        Editor remove(String key);
        void apply();
    }
}
