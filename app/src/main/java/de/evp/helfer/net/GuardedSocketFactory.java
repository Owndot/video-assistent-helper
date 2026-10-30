package de.evp.helfer.net;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketAddress;

import javax.net.SocketFactory;

/**
 * Socket-Fabrik, deren Sockets jedes Verbindungsziel unmittelbar vor dem Verbindungsaufbau prüfen – mit genau der
 * Adresse, zu der verbunden wird. Damit greift die Prüfung für jeden Weg, auf dem OkHttp eine TCP-Verbindung öffnet
 * (DNS-Ergebnis, IP-Literal, Fast-Fallback mehrerer Adressen, neue Pool-Verbindung), und DNS-Rebinding zwischen
 * Prüfung und Verbindung ist ausgeschlossen. Sockets entstehen mit {@link Proxy#NO_PROXY}: ein mit {@code new Socket()}
 * erzeugter Socket würde sonst über den ProxySelector einen SOCKS-Proxy (z. B. Systemeigenschaft socksProxyHost)
 * verwenden und damit die Prüfung umgehen.
 */
final class GuardedSocketFactory extends SocketFactory {
    private final NetRules rules;

    GuardedSocketFactory(NetRules rules) { this.rules = rules; }

    @Override public Socket createSocket() { return new GuardedSocket(rules); }

    @Override public Socket createSocket(String host, int port) throws IOException {
        Socket s = createSocket();
        s.connect(new InetSocketAddress(host, port));
        return s;
    }

    @Override public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        Socket s = createSocket();
        s.bind(new InetSocketAddress(localHost, localPort));
        s.connect(new InetSocketAddress(host, port));
        return s;
    }

    @Override public Socket createSocket(InetAddress host, int port) throws IOException {
        Socket s = createSocket();
        s.connect(new InetSocketAddress(host, port));
        return s;
    }

    @Override public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
        Socket s = createSocket();
        s.bind(new InetSocketAddress(localAddress, localPort));
        s.connect(new InetSocketAddress(address, port));
        return s;
    }

    static final class GuardedSocket extends Socket {
        private final NetRules rules;

        GuardedSocket(NetRules rules) {
            super(Proxy.NO_PROXY);
            this.rules = rules;
        }

        @Override public void connect(SocketAddress endpoint) throws IOException { connect(endpoint, 0); }

        @Override public void connect(SocketAddress endpoint, int timeout) throws IOException {
            if (!(endpoint instanceof InetSocketAddress)) {
                throw new NetBlockedException.Connect(NetBlockedException.Reason.ADDRESS, "unsupported endpoint");
            }
            InetSocketAddress target = (InetSocketAddress) endpoint;
            if (target.isUnresolved() || target.getAddress() == null) {
                throw new NetBlockedException.Connect(NetBlockedException.Reason.ADDRESS, "unresolved " + target.getHostString());
            }
            rules.checkEndpoint(target.getAddress(), target.getPort(), false);
            super.connect(target, timeout);
            InetAddress peer = getInetAddress();
            if (peer == null || !peer.equals(target.getAddress()) || getPort() != target.getPort()) {
                close();
                throw new NetBlockedException.Connect(NetBlockedException.Reason.PEER, "connected peer differs from the checked target");
            }
        }
    }
}
