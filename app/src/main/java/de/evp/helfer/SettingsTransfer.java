package de.evp.helfer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Portable non-secret settings only. Pairing/authentication and runtime state never cross installations. */
public final class SettingsTransfer {
    private static final int LIMIT = 8192;
    private SettingsTransfer() {}
    public static Map<String,String> decode(String text) throws IOException {
        if (text.length() > LIMIT) throw new IOException("Settings file too large");
        Properties p = new Properties();
        try { p.load(new StringReader(text)); }
        catch (IllegalArgumentException e) { throw new IOException("Invalid settings file"); }
        if (!"video-assistent-settings-v1".equals(p.getProperty("format"))) throw new IOException("Unknown settings format");
        Map<String,String> out = new HashMap<>();
        for (String k : p.stringPropertyNames()) {
            String v = p.getProperty(k);
            if ("format".equals(k)) continue;
            if ("lang".equals(k) && Arrays.asList("auto","de","en").contains(v) ||
                "theme".equals(k) && Arrays.asList("auto","light","dark").contains(v) ||
                "boot".equals(k) && Arrays.asList("true","false").contains(v)) out.put(k,v);
            else throw new IOException("Unsupported setting");
        }
        return out;
    }
    public static String encode(String lang, String theme, boolean boot) throws IOException {
        Properties p = new Properties();
        p.setProperty("format","video-assistent-settings-v1");
        p.setProperty("lang",lang); p.setProperty("theme",theme); p.setProperty("boot",String.valueOf(boot));
        StringWriter w = new StringWriter(); p.store(w,null);
        decode(w.toString());
        return w.toString();
    }
    public static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[1024]; int n;
        while ((n = in.read(b)) != -1) {
            if (out.size()+n > LIMIT) throw new IOException("Settings file too large");
            out.write(b,0,n);
        }
        return new String(out.toByteArray(),StandardCharsets.UTF_8);
    }
}
