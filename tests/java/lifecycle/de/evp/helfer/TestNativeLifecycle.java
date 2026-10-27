package de.evp.helfer;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.*;
import com.sun.net.httpserver.HttpServer;

/** Actual credentialed production reader, synthetic transport faults; no Android claim. */
public final class TestNativeLifecycle {
    static final String URL="http://fixture.invalid/file";
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    static TestMemoryWindows.Connection body(String bytes, String tag) throws Exception {
        TestMemoryWindows.Connection c=new TestMemoryWindows.Connection(new ByteArrayInputStream(bytes.getBytes())) {
            public String getHeaderField(String name){return headers.entrySet().stream().filter(e->e.getKey().equalsIgnoreCase(name)).map(Map.Entry::getValue).findFirst().orElse(null);}
        };
        c.headers.put("Content-Length",Integer.toString(bytes.length()));c.headers.put("ETag",tag);return c;
    }
    static void fails(Fetcher.Http client,File file)throws Exception {
        try{client.streamToFile(URL,file,null);throw new AssertionError("Unvalidated EOF reported success");}
        catch(IOException expected){check(!file.exists(),"failed stream left partial output");}
    }
    static void http() throws Exception {
        Path root=Files.createTempDirectory("native-http-lifecycle-");File out=root.resolve("file").toFile();
        try(TestResponseFraming.RawServer raw=new TestResponseFraming.RawServer()) {
            Fetcher.Http client=new Fetcher.Http(null,null,null);
            for(String path:List.of("/conflict","/reverse","/equal-conflict","/duplicate-conflict","/length-conflict")) {
                try{client.streamToFile(raw.base()+path,out,null);throw new AssertionError("Conflicting file framing accepted");}
                catch(Fetcher.ResponseError expected){check(!out.exists(),"framing left file");}
            }
            for(String path:List.of("/known","/chunked")) {
                client.streamToFile(raw.base()+path,out,null);check(Files.readString(out.toPath()).equals("0123456789"),"real HTTP bytes changed");out.delete();
            }
            System.out.println("PASS: real HttpURLConnection credentialed framing rejection, valid length/chunked exact bytes");
        } finally {out.delete();Files.delete(root);}
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        ExecutorService executor=Executors.newCachedThreadPool();server.setExecutor(executor);
        CountDownLatch blocked=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();
        server.createContext("/stall",exchange->{calls.incrementAndGet();exchange.sendResponseHeaders(200,8);
            try(OutputStream stream=exchange.getResponseBody()){stream.write("012".getBytes());stream.flush();blocked.countDown();release.await(5,TimeUnit.SECONDS);}
            catch(Exception expected){}finally{exchange.close();}});
        server.createContext("/ok",exchange->{exchange.sendResponseHeaders(200,8);try(OutputStream stream=exchange.getResponseBody()){stream.write("01234567".getBytes());}finally{exchange.close();}});server.start();
        root=Files.createTempDirectory("native-stall-");out=root.resolve("cancel").toFile();File cancelOut=out;
        String origin="http://127.0.0.1:"+server.getAddress().getPort();AtomicBoolean cancelled=new AtomicBoolean();
        // Test-only shortened read timeout; production keeps its existing 30s read / 15s connect bound.
        Fetcher.Http client=new Fetcher.Http(null,null,null){HttpURLConnection open(String u)throws IOException{HttpURLConnection c=super.open(u);c.setReadTimeout(150);return c;}};
        try {
            Future<?> cancel=executor.submit(()->{try{client.streamToFile(origin+"/stall",cancelOut,new HlsCore.Listener(){public void progress(int a,int b){}public boolean cancelled(){return cancelled.get();}});throw new AssertionError("blocked cancelled read succeeded");}catch(IOException expected){check(!cancelOut.exists(),"cancelled blocked output retained");}});
            check(blocked.await(3,TimeUnit.SECONDS),"server did not block");cancelled.set(true);
            File healthy=root.resolve("healthy").toFile();client.streamToFile(origin+"/ok",healthy,null);
            cancel.get(3,TimeUnit.SECONDS);check(calls.get()==1,"cancelled timeout retried");check(Files.readString(healthy.toPath()).equals("01234567"),"parallel healthy transfer damaged");healthy.delete();
            failsUrl(client,origin+"/stall",cancelOut);check(calls.get()==5,"network timeout exceeded four attempts");
            System.out.println("PASS: real blocked read cancellation/timeout cleanup, bounded retries and independent healthy HTTP transfer");
        } finally {release.countDown();server.stop(0);executor.shutdownNow();try(var files=Files.walk(root)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
    static void failsUrl(Fetcher.Http client,String url,File file)throws Exception {
        try{client.streamToFile(url,file,null);throw new AssertionError("stalled stream succeeded");}catch(IOException expected){check(!file.exists(),"stalled partial retained");}
    }
    public static void main(String[] args)throws Exception {
        if(args.length>0&&args[0].equals("--http")){http();return;}
        Path dir=Files.createTempDirectory("native-lifecycle-");File out=dir.resolve("result").toFile();
        try {
            AtomicInteger calls=new AtomicInteger();
            Fetcher.Http failedEof=TestMemoryWindows.client(()->{
                calls.incrementAndGet();TestMemoryWindows.Connection c=body("01234567","\"one\"");
                c.input=new InputStream(){int p;public int read()throws IOException{if(p==8)throw new IOException("connection failed before EOF");return '0'+p++;}
                    public int read(byte[] b,int o,int n)throws IOException{if(p==8)throw new IOException("connection failed before EOF");int count=Math.min(n,8-p);for(int i=0;i<count;i++)b[o+i]=(byte)('0'+p++);return count;}};
                return c;
            });
            fails(failedEof,out);check(calls.get()==4,"failed EOF was not bounded to four attempts");
            System.out.println("PASS: declared byte count after failed read is not successful EOF; four attempts and cleanup");
            calls.set(0);
            Fetcher.Http conflict=TestMemoryWindows.client(()->{calls.incrementAndGet();return new TestMemoryWindows.Connection(new ByteArrayInputStream("0123456789".getBytes())){
                public Map<String,List<String>> getHeaderFields(){return Map.of("Transfer-Encoding",List.of("chunked"),"Content-Length",List.of("4"));}
                public String getHeaderField(String name){return name.equals("Transfer-Encoding")?"chunked":name.equals("Content-Length")?"4":null;}};});
            fails(conflict,out);check(calls.get()==1,"permanent framing error retried");
            System.out.println("PASS: credentialed file path rejects TE/CL conflict permanently before publication");
            calls.set(0);
            // A changed validator on a resumed 206 discards the prefix and retries a fresh 200.
            Fetcher.Http changed=TestMemoryWindows.client(()->{
                int nth=calls.incrementAndGet();TestMemoryWindows.Connection c=body(nth==1?"0123":nth==2?"EFGH":"ABCDEFGH",nth==1?"\"old\"":"\"new\"");
                if(nth==1)c.headers.put("Content-Length","8");
                if(nth==2){c.status=206;c.headers.put("Content-Range","bytes 4-7/8");}
                return c;
            });
            changed.streamToFile(URL,out,null);check(calls.get()==3&&Files.readString(out.toPath()).equals("ABCDEFGH"),"changed bytes were appended to old prefix");out.delete();
            System.out.println("PASS: changed validator never mixes bytes; fresh download has exact new representation");
            AtomicBoolean cancelled=new AtomicBoolean();calls.set(0);
            Fetcher.Http cancel=TestMemoryWindows.client(()->{calls.incrementAndGet();TestMemoryWindows.Connection c=body("01234567","\"one\"");c.input=new ByteArrayInputStream("01234567".getBytes()){
                public synchronized int read(byte[] b,int o,int n){int got=super.read(b,o,n);cancelled.set(true);return got;}};return c;});
            try{cancel.streamToFile(URL,out,new HlsCore.Listener(){public boolean cancelled(){return cancelled.get();}public void progress(int a,int b){}});throw new AssertionError("cancelled stream succeeded");}
            catch(IOException expected){check(calls.get()==1&&!out.exists(),"cancel retried or retained file");}
            System.out.println("PASS: cancellation after a body read prevents success, retry and partial retention");
        } finally {try(var files=Files.walk(dir)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
}
