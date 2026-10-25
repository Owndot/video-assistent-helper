import de.evp.helfer.*;
import java.io.File;
import java.util.List;

public class TestMain {
  static String base = "http://127.0.0.1:8791/";
  static HlsCore.Listener L = new HlsCore.Listener() { public void progress(int d, int t) {} public boolean cancelled() { return false; } };

  static void run(String name, String path, String kind, Fetcher f) {
    File w = new File("out/" + name.split(" ")[0]); w.mkdirs();
    boolean expectedRejection = name.equals("dash_drm") || name.equals("dash_live")
        || name.startsWith("ck_ohne_cookie ") || name.startsWith("ck_fremde_seite ");
    try {
      if (kind.equals("file")) { File o = Fetcher.toFile(f, base + path, new File(w, "video.mp4"), L); if (expectedRejection) throw new AssertionError("Expected rejection accepted: " + name);
        if (!o.isFile() || o.length() == 0) throw new AssertionError("Missing file: " + name);
        System.out.println("RESULT " + name + " video=" + o + " bytes=" + o.length()); return; }
      HlsCore.Result r = kind.equals("dash") ? new DashCore(f, w, 4).run(base + path, L) : new HlsCore(f, w, 4).run(base + path, L);
      if (expectedRejection) throw new AssertionError("Expected rejection accepted: " + name);
      if (r.video == null || !r.video.isFile() || r.video.length() == 0)
        throw new AssertionError("Missing video: " + name);
      System.out.println("RESULT " + name + " video=" + r.video + " audio=" + r.audio + " q=" + r.quality);
    } catch (Exception e) {
      String message = String.valueOf(e.getMessage());
      boolean correct = name.equals("dash_drm") && message.contains("DRM")
          || name.equals("dash_live") && message.toLowerCase(java.util.Locale.ROOT).contains("live")
          || (name.startsWith("ck_ohne_cookie ") || name.startsWith("ck_fremde_seite "))
             && e instanceof Fetcher.HttpError && ((Fetcher.HttpError)e).code == 403;
      if (!correct) throw new AssertionError("Unexpected core failure: " + name, e);
      System.out.println("EXPECTED_REJECTION " + name + ": " + message);
    }
  }

  public static void main(String[] a) throws Exception {
    Fetcher ok = new Fetcher.Http("https://example.test/watch/1", "Mozilla/5.0 Test", "");
    run("dash_timeline", "d1/manifest.mpd", "dash", ok);
    run("dash_duration", "d2/manifest.mpd", "dash", ok);
    run("dash_singlefile", "d3/manifest.mpd", "dash", ok);
    run("dash_drm", "d4/manifest.mpd", "dash", ok);
    run("dash_live", "d5/manifest.mpd", "dash", ok);
    run("hls_enc", "enc/master.m3u8", "hls", ok);
    // Erwartet: ohne Cookie 403 · Cookie einer FREMDEN Seite wird nicht mitgeschickt → 403 · eigene Seite → Datei
    run("ck_ohne_cookie (erwartet 403)", "ck/film.mp4", "file", ok);
    run("ck_fremde_seite (erwartet 403)", "ck/film.mp4", "file", new Fetcher.Http("https://example.test/watch/1", "Mozilla/5.0 Test", "a=1; sess=abc"));
    run("ck_eigene_seite", "ck/film.mp4", "file", new Fetcher.Http("http://127.0.0.1:8791/watch/1", "Mozilla/5.0 Test", "a=1; sess=abc"));
    // Über den "Browser": der Helfer fragt, ein zweiter Faden (wie das Skript) holt mit Sitzung und liefert
    Fetcher.Proxy px = new Fetcher.Proxy();
    Fetcher browser = new Fetcher.Http("http://127.0.0.1:8791/watch/1", "Mozilla/5.0 Browser", "sess=abc");
    Thread script = new Thread(() -> {
      while (!Thread.currentThread().isInterrupted()) {
        List<Fetcher.Proxy.Req> rs = px.pull(4, 500);
        for (Fetcher.Proxy.Req r : rs) {
          try {
            if (r.sizeOnly) px.push(r.id, 206, browser.size(r.url), new byte[0]);
            else px.push(r.id, r.len > 0 ? 206 : 200, -1, browser.fetch(r.url, r.off, r.len));
          } catch (Fetcher.HttpError e) { px.push(r.id, 403, -1, null); } catch (Exception e) { px.push(r.id, 502, -1, null); }
        }
      }
    });
    script.setDaemon(true);
    script.start();
    run("proxy_datei", "ck/film.mp4", "file", px);
    run("proxy_dash", "d1/manifest.mpd", "dash", px);
    script.interrupt();
  }
}
