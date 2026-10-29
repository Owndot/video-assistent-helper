package de.evp.helfer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Connection;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * NUR GRÖSSENTEST (temporärer Zweig tmp/okhttp-size-test): nie aufgerufen, keine Download-Funktion.
 * Nutzt genau die OkHttp-Teile, die ein gehärteter Direkt-Download bräuchte – damit R8 sie nicht entfernt und die
 * Messung realistisch ist: eigene DNS-Auflösung (nur öffentliche Adressen), kein Proxy, keine automatischen
 * Weiterleitungen, Prüfung der tatsächlich verbundenen Adresse, Streaming in eine Datei.
 */
public final class OkHttpSizeProbe {
    private OkHttpSizeProbe() {}

    static boolean publicAddress(InetAddress a) {
        return !(a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
            || a.isMulticastAddress());
    }

    static OkHttpClient client() {
        Dns publicOnly = host -> {
            List<InetAddress> out = new ArrayList<>();
            for (InetAddress a : InetAddress.getAllByName(host)) {
                if (!publicAddress(a)) throw new UnknownHostException("non-public address");
                out.add(a);
            }
            return out;
        };
        return new OkHttpClient.Builder()
            .dns(publicOnly)
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addNetworkInterceptor(chain -> {
                Connection c = chain.connection();
                InetSocketAddress remote = c == null ? null : c.route().socketAddress();
                if (remote == null || !publicAddress(remote.getAddress())) throw new IOException("non-public peer");
                return chain.proceed(chain.request());
            })
            .build();
    }

    static long fetchTo(String url, File out) throws IOException {
        HttpUrl u = HttpUrl.parse(url);
        if (u == null) throw new IOException("bad url");
        Request req = new Request.Builder().url(u).header("Accept-Encoding", "identity").build();
        try (Response r = client().newCall(req).execute()) {
            ResponseBody body = r.body();
            long n = 0;
            byte[] buf = new byte[64 << 10];
            try (InputStream in = body.byteStream(); OutputStream os = new FileOutputStream(out)) {
                int k;
                while ((k = in.read(buf)) > 0) { os.write(buf, 0, k); n += k; }
            }
            return n;
        }
    }
}
