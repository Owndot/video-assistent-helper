package de.evp.helfer.net;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.Authenticator;
import okhttp3.Call;
import okhttp3.Connection;
import okhttp3.ConnectionPool;
import okhttp3.CookieJar;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Der einzige HTTP-Client des Helpers für Ziele, die nicht der Helper selbst ist (Phase C). Gehärtet gegen SSRF und
 * DNS-Rebinding in vier unabhängigen Schichten, die jede für sich ein unzulässiges Ziel ablehnen:
 * <ol>
 *   <li>URL-Prüfung (Schema http/https, Port 80/443, keine Zugangsdaten, keine lokalen Namen, IP-Literale) für die
 *       Ausgangs-URL und jede Weiterleitung;</li>
 *   <li>DNS: nur Namen, deren sämtliche Adressen zulässig sind;</li>
 *   <li>Socket: Prüfung der Adresse unmittelbar vor dem Verbindungsaufbau, für jeden Verbindungsweg;</li>
 *   <li>Netzwerk-Interceptor: Prüfung der tatsächlich verbundenen Gegenstelle vor jeder Anfrage, auch über eine
 *       wiederverwendete Pool-Verbindung.</li>
 * </ol>
 * Außerdem: kein Proxy (auch kein System-/SOCKS-Proxy), keine automatischen Weiterleitungen (jede wird hier geprüft;
 * kein Wechsel von HTTPS auf HTTP; höchstens {@link #MAX_REDIRECTS}), keine Cookies, keine Authenticator, keine
 * Zugangsdaten-Header, keine automatischen Wiederholungen (eine zweite Netzwerkanfrage innerhalb eines Aufrufs wird
 * abgelehnt), nur HTTP/1.1 (keine HTTP/2-Verbindungsbündelung über Hostnamen hinweg, Gegendruck über TCP statt eines
 * 16-MiB-Empfangsfensters), begrenzte Zeiten, Header- und Rumpfgrößen, kein transparentes gzip.
 *
 * Der OkHttp-Client ist nicht zugänglich: keine Methode gibt ihn, seinen Builder oder seine Interceptoren heraus.
 */
public final class SafeHttpClient {
    public static final int MAX_REDIRECTS = 5;
    static final int MAX_RESPONSE_HEADER_BYTES = 32 * 1024;
    static final long CONNECT_TIMEOUT_MS = 10_000;
    static final long READ_TIMEOUT_MS = 30_000;
    static final long WRITE_TIMEOUT_MS = 15_000;
    public static final long MAX_CALL_TIMEOUT_MS = TimeUnit.HOURS.toMillis(6);
    static final String USER_AGENT = "Helper/1 (+no-cookies)";
    static final List<String> FORBIDDEN_REQUEST_HEADERS = Collections.unmodifiableList(Arrays.asList(
        "Cookie", "Cookie2", "Authorization", "Proxy-Authorization", "X-Forwarded-For", "Forwarded"));

    /** Namensauflösung; produktiv der Systemresolver. In Tests ersetzbar (z. B. Rebinding-Antworten). */
    interface Resolver { List<InetAddress> resolve(String host) throws UnknownHostException; }

    static final Resolver SYSTEM_RESOLVER = host -> Arrays.asList(InetAddress.getAllByName(host));

    /** Zählt Netzwerkanfragen eines Aufrufs; OkHttp übernimmt Tags in Folgeanfragen (503-Wiederholung, Auth …). */
    static final class Attempts { final AtomicInteger count = new AtomicInteger(); }

    /** NUR TESTS: eigenes TLS-Vertrauen für lokale Testserver. Produktiv immer null (System-Vertrauen). */
    static final class TestTls {
        final SSLSocketFactory socketFactory;
        final X509TrustManager trustManager;
        final HostnameVerifier hostnameVerifier;

        TestTls(SSLSocketFactory socketFactory, X509TrustManager trustManager, HostnameVerifier hostnameVerifier) {
            this.socketFactory = socketFactory;
            this.trustManager = trustManager;
            this.hostnameVerifier = hostnameVerifier;
        }
    }

    private final NetRules rules;
    private final OkHttpClient client;

    SafeHttpClient(NetRules rules, Resolver resolver, TestTls testTls) {
        this.rules = rules;
        OkHttpClient.Builder b = new OkHttpClient.Builder()
            .dns(dns(rules, resolver))
            .socketFactory(new GuardedSocketFactory(rules))
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .cookieJar(CookieJar.NO_COOKIES)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .cache(null)
            .protocols(Collections.singletonList(Protocol.HTTP_1_1))
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .connectionPool(new ConnectionPool(4, 60, TimeUnit.SECONDS))
            .addNetworkInterceptor(networkGuard(rules));
        if (testTls != null) {
            b.sslSocketFactory(testTls.socketFactory, testTls.trustManager);
            if (testTls.hostnameVerifier != null) b.hostnameVerifier(testTls.hostnameVerifier);
        }
        this.client = b.build();
    }

    /**
     * Ruhende Pool-Verbindungen schließen (laufende Anfragen bleiben unberührt). Ohne automatische Wiederholung
     * ({@code retryOnConnectionFailure(false)}) würde eine Anfrage über eine Verbindung, die der Server inzwischen
     * geschlossen hat (Keep-Alive-Zeitgrenze, HTTP/1.0), sonst als Netzwerkfehler enden. Die Download-Engine ruft das
     * vor jedem Auftrag auf; Wiederverwendung innerhalb eines Auftrags (Fortsetzen) und alle Prüfschichten bleiben.
     */
    public void closeIdleConnections() { client.connectionPool().evictAll(); }

    /** Produktiver Client: strenge Regeln, Systemresolver, System-Vertrauen. */
    static SafeHttpClient strict(NetRules.Nat64Source nat64) { return new SafeHttpClient(NetRules.strict(nat64), SYSTEM_RESOLVER, null); }

    static Dns dns(NetRules rules, Resolver resolver) {
        return host -> {
            if (NetRules.isIpLiteral(host) || NetRules.endsInNumber(host) || NetRules.hostNameBlocked(host)) {
                // IP-Literale gelangen bei OkHttp nicht hierher; zur Sicherheit nie über DNS auflösen.
                throw new NetBlockedException.Host(NetBlockedException.Reason.HOST_NAME, host);
            }
            List<InetAddress> addresses = new ArrayList<>(resolver.resolve(host));
            rules.checkResolved(host, addresses);
            return addresses;
        };
    }

    static Interceptor networkGuard(NetRules rules) {
        return chain -> {
            Request request = chain.request();
            Attempts attempts = request.tag(Attempts.class);
            if (attempts == null || attempts.count.incrementAndGet() > 1) {
                throw new NetBlockedException.Intercepted(NetBlockedException.Reason.AUTOMATIC_RETRY, request.url().host());
            }
            for (String h : FORBIDDEN_REQUEST_HEADERS) {
                if (request.header(h) != null) throw new NetBlockedException.Intercepted(NetBlockedException.Reason.CREDENTIAL_HEADER, h);
            }
            Connection c = chain.connection();
            Socket s = c == null ? null : c.socket();
            if (s == null) throw new NetBlockedException.Intercepted(NetBlockedException.Reason.PEER, "no connection");
            rules.checkEndpoint(s.getInetAddress(), s.getPort(), true);
            if (c.protocol() != Protocol.HTTP_1_1) throw new NetBlockedException.Intercepted(NetBlockedException.Reason.PEER, "protocol " + c.protocol());
            Response response = chain.proceed(request);
            if (response.headers().byteCount() > MAX_RESPONSE_HEADER_BYTES) {
                response.close();
                throw new NetBlockedException.Intercepted(NetBlockedException.Reason.HEADERS_TOO_LARGE, String.valueOf(response.headers().byteCount()));
            }
            // 503 mit Retry-After würde OkHttp sonst selbst erneut senden; der Aufrufer entscheidet über Wiederholungen.
            if (response.code() == 503 && response.header("Retry-After") != null) {
                return response.newBuilder().removeHeader("Retry-After").build();
            }
            return response;
        };
    }

    /**
     * Abbruch eines laufenden Aufrufs von einem anderen Thread aus (Verbindungsaufbau, Header und Rumpf). Ein einmal
     * abgebrochenes Token bricht auch jeden späteren Aufruf mit diesem Token sofort ab.
     */
    public static final class Cancellation {
        private Call call;
        private boolean cancelled;

        public synchronized void cancel() {
            cancelled = true;
            if (call != null) call.cancel();
        }

        public synchronized boolean isCancelled() { return cancelled; }

        synchronized void attach(Call c) throws IOException {
            if (cancelled) throw new java.io.InterruptedIOException("cancelled");
            call = c;
        }
    }

    /** GET der ganzen Ressource. */
    public SafeResponse get(String url, long maxBodyBytes, long timeoutMs) throws IOException {
        return get(url, -1, -1, maxBodyBytes, timeoutMs);
    }

    public SafeResponse get(String url, long rangeStart, long rangeEnd, long maxBodyBytes, long timeoutMs) throws IOException {
        return get(url, rangeStart, rangeEnd, maxBodyBytes, timeoutMs, null);
    }

    /**
     * GET mit optionalem Byte-Bereich ({@code rangeStart} &ge; 0; {@code rangeEnd} -1 = bis zum Ende). Folgt
     * höchstens {@link #MAX_REDIRECTS} Weiterleitungen, jede vorher geprüft. Der Rumpf ist nur als Stream lesbar und
     * auf {@code maxBodyBytes} begrenzt; {@code timeoutMs} begrenzt die gesamte Dauer einschließlich Weiterleitungen
     * und Rumpf. Wiederholt nie selbst.
     */
    public SafeResponse get(String url, long rangeStart, long rangeEnd, long maxBodyBytes, long timeoutMs, Cancellation cancellation) throws IOException {
        return get(url, rangeStart, rangeEnd, null, maxBodyBytes, timeoutMs, cancellation);
    }

    /** Starker ETag (RFC 9110 8.8.3): {@code "…"} aus etagc ohne obs-text, kein {@code W/}; höchstens 512 Zeichen. */
    static final java.util.regex.Pattern STRONG_ETAG = java.util.regex.Pattern.compile("\"[\\x21\\x23-\\x7e]{0,510}\"");

    public static boolean isStrongEtag(String etag) { return etag != null && STRONG_ETAG.matcher(etag).matches(); }

    /**
     * Wie oben, mit {@code If-Range}: nur zusammen mit einem Byte-Bereich und nur mit einem starken ETag (RFC 9110 13.1.5
     * verlangt starken Vergleich; ein Datum ist dort kein sicherer Validator). Der Server liefert dann 206 nur für
     * dieselbe Darstellung, sonst 200 mit der ganzen Ressource.
     */
    public SafeResponse get(String url, long rangeStart, long rangeEnd, String ifRange, long maxBodyBytes, long timeoutMs, Cancellation cancellation) throws IOException {
        if (maxBodyBytes <= 0 || timeoutMs <= 0 || timeoutMs > MAX_CALL_TIMEOUT_MS || (rangeStart < 0 && rangeEnd >= 0)
            || (rangeStart >= 0 && rangeEnd >= 0 && rangeEnd < rangeStart)
            || (ifRange != null && (rangeStart < 0 || !isStrongEtag(ifRange)))) {
            throw new NetBlockedException(NetBlockedException.Reason.BAD_ARGUMENT, "limits");
        }
        HttpUrl current = url == null ? null : HttpUrl.parse(url);
        if (current == null) throw new NetBlockedException(NetBlockedException.Reason.BAD_URL, "unparseable");
        rules.checkUrl(current);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        for (int hop = 0; ; hop++) {
            Request.Builder rb = new Request.Builder().url(current).get()
                .header("User-Agent", USER_AGENT)
                .header("Accept-Encoding", "identity")
                .tag(Attempts.class, new Attempts());
            if (rangeStart >= 0) rb.header("Range", "bytes=" + rangeStart + "-" + (rangeEnd >= 0 ? String.valueOf(rangeEnd) : ""));
            if (ifRange != null) rb.header("If-Range", ifRange);
            Call call = client.newCall(rb.build());
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new java.io.InterruptedIOException("timeout");
            call.timeout().timeout(remaining, TimeUnit.NANOSECONDS);
            if (cancellation != null) cancellation.attach(call);
            Response response;
            try {
                response = call.execute();
            } catch (IOException e) {
                NetBlockedException blocked = NetBlockedException.find(e);
                if (blocked != null) throw blocked;
                throw e;
            }
            int code = response.code();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = response.header("Location");
                response.close();
                if (location == null || location.isEmpty()) throw new NetBlockedException(NetBlockedException.Reason.REDIRECT_WITHOUT_LOCATION, String.valueOf(code));
                if (hop >= MAX_REDIRECTS) throw new NetBlockedException(NetBlockedException.Reason.TOO_MANY_REDIRECTS, String.valueOf(hop + 1));
                HttpUrl next = current.resolve(location);
                if (next == null) throw new NetBlockedException(NetBlockedException.Reason.REDIRECT_TARGET, "unparseable");
                if (current.isHttps() && !next.isHttps()) throw new NetBlockedException(NetBlockedException.Reason.DOWNGRADE, next.host());
                rules.checkUrl(next);
                current = next;
                continue;
            }
            return new SafeResponse(response, current.toString(), maxBodyBytes);
        }
    }
}
