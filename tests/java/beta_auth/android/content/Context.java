package android.content;
/** Test-only facade. No Android app, keystore or device preferences are accessed. */
public abstract class Context {
    public static final int MODE_PRIVATE = 0;
    public Context getApplicationContext() { return this; }
    public abstract SharedPreferences getSharedPreferences(String name, int mode);
}
