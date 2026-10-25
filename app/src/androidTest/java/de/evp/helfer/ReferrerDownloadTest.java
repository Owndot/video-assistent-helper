package de.evp.helfer;

import android.app.DownloadManager;
import android.content.Context;
import android.database.Cursor;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Actual Android DownloadManager and production fileDownload(), including a cross-port redirect. */
@RunWith(AndroidJUnit4.class)
public class ReferrerDownloadTest {
    static final String PATH = "/movie.mp4?sig=SIGNED%2Ffixture%2Bvalue&order=2&order=1";
    static class Endpoint implements AutoCloseable {
        final ServerSocket socket = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        final List<String> requests = new CopyOnWriteArrayList<>();
        final List<String> refs = new CopyOnWriteArrayList<>();
        final CountDownLatch hit = new CountDownLatch(1);
        final Thread thread;
        Endpoint(String redirect) throws Exception {
            thread = new Thread(() -> {
                while (!socket.isClosed()) try (Socket s = socket.accept()) {
                    s.setSoTimeout(5000);
                    BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
                    String first = in.readLine(), ref = "", line;
                    while ((line = in.readLine()) != null && !line.isEmpty())
                        if (line.toLowerCase(Locale.ROOT).startsWith("referer:")) ref = line.substring(8).trim();
                    requests.add(first); refs.add(ref);
                    String response = redirect == null ? "HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
                        : "HTTP/1.1 302 Found\r\nLocation: " + redirect + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
                    s.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));s.getOutputStream().flush();hit.countDown();
                } catch (IOException ignored) { if (socket.isClosed()) return; }
            }, "TEST-ONLY HTTP fixture");
            thread.start();
        }
        String url(String path) { return "http://127.0.0.1:" + socket.getLocalPort() + path; }
        public void close() throws Exception { socket.close(); thread.join(2000); }
    }
    @Test public void downloadManagerNeverReplaysPageReferrer() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        try (Endpoint target = new Endpoint(null); Endpoint redirect = new Endpoint(target.url(PATH))) {
            java.lang.reflect.Method method = Downloads.class.getDeclaredMethod("fileDownload", Context.class,
                String.class, String.class, String.class, String.class);
            method.setAccessible(true);
            long id = (Long) method.invoke(null, context, redirect.url(PATH),
                "https://user:USERINFO_SECRET@example.test/PRIVATE_PATH_SECRET?token=QUERY_SECRET#FRAGMENT_SECRET",
                "TEST-ONLY", "referrer-test-" + System.nanoTime());
            try {
                assertTrue("Android DownloadManager did not reach redirect", redirect.hit.await(40, TimeUnit.SECONDS));
                assertTrue("Android DownloadManager did not follow redirect", target.hit.await(40, TimeUnit.SECONDS));
                long until = System.currentTimeMillis() + 15000; boolean complete = false;
                while (System.currentTimeMillis() < until) {
                    try (Cursor c = dm.query(new DownloadManager.Query().setFilterById(id))) {
                        if (c.moveToFirst()) {
                            int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                            if (status == DownloadManager.STATUS_FAILED) fail("DM failed: " + c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)));
                            if (status == DownloadManager.STATUS_SUCCESSFUL) { complete = true; break; }
                        }
                    }
                    Thread.sleep(100);
                }
                assertTrue("Android DownloadManager did not finish", complete);
                assertEquals(Collections.singletonList("GET " + PATH + " HTTP/1.1"), redirect.requests);
                assertEquals(Collections.singletonList("GET " + PATH + " HTTP/1.1"), target.requests);
                for (String ref : redirect.refs) assertEquals("", ref);
                for (String ref : target.refs) assertEquals("", ref);
                try (InputStream in = new FileInputStream(dm.openDownloadedFile(id).getFileDescriptor())) {
                    assertEquals('o', in.read());assertEquals('k', in.read());assertEquals(-1, in.read());
                }
            } finally { dm.remove(id); }
        }
    }
}
