package de.evp.pakepoc;

import android.os.Debug;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Locale;

/** Process memory figures for the report; contains no secrets. */
final class Measurements {
    private Measurements() {}

    static String procStatus(String key) {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/status"))) {
            for (String l; (l = r.readLine()) != null; ) if (l.startsWith(key + ":")) return l.substring(key.length() + 1).trim();
        } catch (Exception ignored) {}
        return "?";
    }

    static String snapshot() {
        Runtime rt = Runtime.getRuntime();
        Debug.MemoryInfo mi = new Debug.MemoryInfo();
        Debug.getMemoryInfo(mi);
        return String.format(Locale.ROOT,
            "{\"javaHeapUsedKiB\":%d,\"javaHeapMaxKiB\":%d,\"nativeHeapAllocKiB\":%d,\"nativeHeapSizeKiB\":%d,\"pssTotalKiB\":%d,\"vmRss\":\"%s\",\"vmHwm\":\"%s\"}",
            (rt.totalMemory() - rt.freeMemory()) / 1024, rt.maxMemory() / 1024,
            Debug.getNativeHeapAllocatedSize() / 1024, Debug.getNativeHeapSize() / 1024,
            mi.getTotalPss(), procStatus("VmRSS"), procStatus("VmHWM"));
    }
}
