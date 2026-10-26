package de.evp.helfer;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Real Keys + MiniServer; only SharedPreferences is an in-memory test substitute. */
public final class TestBetaAuth {
    static final String INITIAL = "0123456789abcdef0123456789abcdef"; // synthetic only
    static final class Prefs implements SharedPreferences {
        final Map<String,Object> values = new HashMap<>();
        synchronized public String getString(String key, String fallback) { return (String) values.getOrDefault(key, fallback); }
        synchronized public boolean getBoolean(String key, boolean fallback) { return (Boolean) values.getOrDefault(key, fallback); }
        public Editor edit() { return new Editor() {
            final Map<String,Object> changes = new HashMap<>();
            public Editor putString(String key, String value) { changes.put(key,value); return this; }
            public Editor putBoolean(String key, boolean value) { changes.put(key,value); return this; }
            public Editor remove(String key) { changes.put(key,null); return this; }
            public void apply() { synchronized(Prefs.this) { changes.forEach((key,value) -> { if(value==null)values.remove(key);else values.put(key,value); }); } }
        }; }
    }
    static final class FixtureContext extends Context {
        final Prefs prefs;
        FixtureContext(Prefs prefs) { this.prefs=prefs; }
        public SharedPreferences getSharedPreferences(String name,int mode) {
            check(name.equals("helfer") && mode==MODE_PRIVATE,"Production storage scope changed"); return prefs;
        }
    }
    static void check(boolean ok,String message) { if(!ok)throw new AssertionError(message); }
    static String request(int port,String key,String path) throws Exception {
        try(Socket socket=new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1",port),1000);socket.setSoTimeout(2000);
            String raw="GET "+path+" HTTP/1.1\r\nHost: 127.0.0.1:"+port+"\r\n"+(key==null?"":"X-EVP-Key: "+key+"\r\n")+"Connection: close\r\n\r\n";
            socket.getOutputStream().write(raw.getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    static void status(int expected,String response) { check(response.startsWith("HTTP/1.1 "+expected+" "),"Unexpected HTTP status: "+response.split("\r\n")[0]); }
    public static void main(String[] args) throws Exception {
        Prefs store=new Prefs();store.edit().putString("key",INITIAL).apply();
        Context context=new FixtureContext(store);AtomicInteger handled=new AtomicInteger();
        MiniServer.Handler handler=new MiniServer.Handler() {
            public String get(String path,Map<String,String> params) { handled.incrementAndGet();return "{\"ok\":true}"; }
            public String push(Map<String,String> params,byte[] body) { handled.incrementAndGet();return "{\"ok\":true}"; }
        };
        int port;
        try(ServerSocket foreign=new ServerSocket(0,32,InetAddress.getByName("127.0.0.1"))) {
            port=foreign.getLocalPort();MiniServer blocked=new MiniServer(port,k->ProductionAuth.accept(context,k),handler);
            try { check(!blocked.start(),"Competing listener reported ready");check(!Keys.paired(context),"Port conflict marked pairing successful");check(handled.get()==0,"Conflict dispatched a helper operation"); }
            finally { blocked.stop(); }
        }
        System.out.println("PASS: competing listener prevents genuine helper bind; no readiness/pairing success");
        MiniServer server=new MiniServer(port,k->ProductionAuth.accept(context,k),handler);
        String rotated;
        try {
            check(server.start(),"Helper failed after competing listener stopped");
            status(403,request(port,null,"/ping"));status(403,request(port,"evp-1","/ping"));
            status(200,request(port,null,"/pair"));check(!Keys.paired(context),"Public pair page authenticates a client");
            check(!request(port,null,"/pair").contains(INITIAL),"Pair HTML contains key");
            status(200,request(port,INITIAL,"/ping"));check(Keys.paired(context),"Correct key did not set paired flag");
            check(Keys.key(context).equals(INITIAL),"Pairing rotated the shared key");
            Keys.reset(context);rotated=Keys.key(context);
            check(rotated.matches("[0-9a-f]{32}") && !rotated.equals(INITIAL),"Reset did not replace 128-bit key");
            check(!Keys.paired(context),"Reset retained paired flag");int before=handled.get();
            status(403,request(port,INITIAL,"/ping"));status(403,request(port,"evp-1","/ping"));
            check(handled.get()==before && !Keys.paired(context),"Invalid old key dispatched or paired");
            status(200,request(port,rotated,"/ping"));check(Keys.paired(context),"New key could not pair running server");
        } finally { server.stop(); }
        System.out.println("PASS: actual Keys.reset replaces key/paired state; old and legacy keys rejected immediately");
        // Recreate the service/listener and Context facade, retaining only the private preference store.
        Context restarted=new FixtureContext(store);check(Keys.key(restarted).equals(rotated),"Restart changed stored key");
        check(Keys.paired(restarted),"Restart forgot successful pairing state");
        MiniServer next=new MiniServer(port,k->ProductionAuth.accept(restarted,k),handler);
        try {
            check(next.start(),"Restart could not bind released endpoint");
            status(403,request(port,INITIAL,"/ping"));status(403,request(port,"evp-1","/ping"));
            status(200,request(port,rotated,"/ping"));check(Keys.paired(restarted),"Existing client lost pairing on restart");
            Keys.reset(restarted);String finalKey=Keys.key(restarted);
            check(!finalKey.equals(rotated) && !Keys.paired(restarted),"Post-restart reset failed");
            status(403,request(port,rotated,"/ping"));status(200,request(port,finalKey,"/ping"));
            check(Keys.paired(restarted),"Re-pairing after restart failed");
        } finally { next.stop(); }
        System.out.println("PASS: listener restart, stored key, rejection of stale key and re-pairing after restart");
    }
}
