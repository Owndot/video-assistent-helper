import de.evp.helfer.*;
import java.io.File;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Helfer-App am Rechner (ohne Android): echter MiniServer + echte Lade-Kerne, Ergebnis landet in out_e2e/ */
public class TestHelper {
  static final Map<Integer, Fetcher.Proxy> proxies = new ConcurrentHashMap<>();
  static final Map<Integer, String> state = new ConcurrentHashMap<>();
  static final AtomicInteger ids = new AtomicInteger(100);
  static final StringBuilder log = new StringBuilder();

  static void job(int id, String u, String kind, Fetcher f, boolean viaDm) {
    File w = new File("out_e2e/job" + id); w.mkdirs();
    HlsCore.Listener l = new HlsCore.Listener() { public void progress(int d, int t) { state.put(id, d + "/" + t); } public boolean cancelled() { return false; } };
    try {
      // "bösartige Playlist": schiebt eine Adresse einer fremden Seite unter – dorthin darf keine Sitzung mitgehen
      if (!viaDm && u.contains("film.webm")) { try { f.fetch("http://localhost:8792/ck/film.webm", -1, -1); } catch (Exception ignored) {} }
      if (kind.equals("file")) {
        if (viaDm) {   // wie Androids Download-Manager: ein Abruf, Fehlerstatus merken
          byte[] b = f.fetch(u, -1, -1);
          Files.write(new File(w, "video.mp4").toPath(), b);
        } else Fetcher.toFile(f, u, new File(w, "video.mp4"), l);
      } else if (kind.equals("dash")) new DashCore(f, w, 4).run(u, l);
      else new HlsCore(f, w, 4).run(u, l);
      state.put(id, "fertig");
    } catch (Exception e) {
      state.put(id, "Fehler: " + e.getMessage());
    } finally {
      Fetcher.Proxy px = proxies.remove(id);
      if (px != null) px.close();
    }
  }

  public static void main(String[] a) throws Exception {
    final String KEY = "0123456789abcdef0123456789abcdef";
    final boolean[] paired = {false};
    MiniServer s = new MiniServer(47811, (k) -> {
      if (k.equals(KEY)) { paired[0] = true; return true; }
      return k.equals("evp-1") && !paired[0];
    }, new MiniServer.Handler() {
      public String get(String path, Map<String, String> p) {
        synchronized (log) { log.append(path).append(' ').append(p.containsKey("p") ? "p=1 " : "").append(p.containsKey("c") ? "c=" + p.get("c") : "").append('\n'); }
        switch (path) {
          case "/ping": case "/beat": case "/bright": case "/vol":
            return "{\"ok\":true,\"v\":3,\"dl\":true,\"dash\":true,\"proxy\":true,\"overlay\":true,\"vol\":0.5,\"volStep\":7,\"volMax\":15,\"bright\":0.5}";
          case "/end": return "{\"ok\":true}";
          case "/log": synchronized (log) { return "{\"ok\":true,\"log\":" + MiniServer.js(log.toString()) + "}"; }
          case "/download": {
            int id = ids.incrementAndGet();
            boolean proxy = "1".equals(p.get("p"));
            String kind = p.getOrDefault("k", "file");
            Fetcher f;
            if (proxy) { Fetcher.Proxy px = new Fetcher.Proxy(); proxies.put(id, px); f = px; }
            else f = new Fetcher.Http(p.get("ref"), p.get("ua"), p.get("c"));
            state.put(id, "startet");
            final Fetcher ff = f;
            new Thread(() -> job(id, p.get("u"), kind, ff, !proxy)).start();
            return "{\"ok\":true,\"id\":" + id + "}";
          }
          case "/pull": return MiniServer.pull(proxies, state, p);
          case "/dlstate": return "{\"ok\":true,\"state\":" + MiniServer.js(state.get((int) MiniServer.num(p.get("id"), -1))) + "}";
          default: return "{\"ok\":false}";
        }
      }
      public String push(Map<String, String> p, byte[] body) { return MiniServer.push(proxies, p, body); }
    });
    s.start();
    System.out.println("TestHelper läuft");
    Thread.sleep(Long.MAX_VALUE);
  }
}
