package de.evp.helfer;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real loopback HTTP, only in CI when sandbox sockets are unavailable. */
public class TestMemoryHttp {
    static void check(boolean ok,String msg){if(!ok)throw new AssertionError(msg);}
    public static void main(String[] args)throws Exception{
        long length=64L<<20;AtomicReference<String> range=new AtomicReference<>(),cookie=new AtomicReference<>(),query=new AtomicReference<>();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        ExecutorService workers=Executors.newFixedThreadPool(4);server.setExecutor(workers);
        server.createContext("/",e->{
            String path=e.getRequestURI().getPath();range.set(e.getRequestHeaders().getFirst("Range"));cookie.set(e.getRequestHeaders().getFirst("Cookie"));query.set(e.getRequestURI().getRawQuery());
            if(path.equals("/unauthorized")){e.sendResponseHeaders(403,-1);e.close();return;}
            if(path.equals("/redirect")){e.getResponseHeaders().set("Location","/large?signature=UNCHANGED%2Fvalue");e.sendResponseHeaders(302,-1);e.close();return;}
            long start=0,size=length;int status=200;
            if(path.equals("/range")||path.equals("/wrong")){
                String raw=e.getRequestHeaders().getFirst("Range");String[] fields=raw.substring(6).split("-");start=Long.parseLong(fields[0]);long end=Math.min(length-1,Long.parseLong(fields[1]));size=end-start+1;status=206;
                e.getResponseHeaders().set("Content-Range","bytes "+(path.equals("/wrong")?start+1:start)+"-"+end+"/"+length);
            }
            if(path.equals("/truncated"))size=100000;
            if(path.equals("/timeout"))size=1;
            boolean chunked=path.equals("/chunked");
            e.sendResponseHeaders(status,chunked?0:size);
            try(OutputStream out=e.getResponseBody();InputStream in=new TestMemoryWindows.Generated(path.equals("/truncated")?size-1:size,start)){
                if(path.equals("/timeout")){try{Thread.sleep(150);}catch(InterruptedException stop){Thread.currentThread().interrupt();}return;}
                byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);
            }catch(IOException aborted){/* Client intentionally closes ignored Range and rejected metadata. */}
            finally{e.close();}
        });
        server.start();String base="http://127.0.0.1:"+server.getAddress().getPort();
        Path temp=Files.createTempDirectory("memory-http-");
        try{
            Fetcher.Http client=new Fetcher.Http(base+"/page", "synthetic-agent", "synthetic-cookie=1");
            byte[] b=client.fetch(base+"/redirect",8L<<20,4L<<20);
            check(b.length==4<<20,"range length");for(int i=0;i<b.length;i++)check(b[i]==TestMemoryWindows.value((8L<<20)+i),"range byte mismatch");b=null;
            check("bytes=8388608-12582911".equals(range.get()),"redirect lost Range");check("synthetic-cookie=1".equals(cookie.get()),"cookie lost");check("signature=UNCHANGED%2Fvalue".equals(query.get()),"signed query changed");
            byte[] valid=client.fetch(base+"/range",31,100);for(int i=0;i<valid.length;i++)check(valid[i]==TestMemoryWindows.value(31+i),"206 slice wrong");
            for(String path:new String[]{"large","chunked"}){
                File out=temp.resolve(path).toFile();client.download(base+"/"+path,-1,-1,out,null);TestMemoryWindows.fileHash(out,length,0);out.delete();
                TestMemoryWindows.rejects(()->client.fetch(base+"/"+path,-1,-1),"oversize "+path);
            }
            TestMemoryWindows.rejects(()->client.fetch(base+"/wrong",0,100),"invalid wire Content-Range");
            File broken=temp.resolve("broken").toFile();try{client.download(base+"/truncated",-1,-1,broken,null);throw new AssertionError("Truncated wire body accepted");}catch(IOException expected){check(!broken.exists(),"Truncated file retained");}
            Fetcher.Http timeout=new Fetcher.Http(null,null,null){@Override HttpURLConnection open(String url)throws IOException{HttpURLConnection c=super.open(url);check(c.getReadTimeout()==30000&&c.getConnectTimeout()==15000,"Production timeouts changed");c.setReadTimeout(30);return c;}};
            try{timeout.download(base+"/timeout",-1,-1,broken,null);throw new AssertionError("Read timeout accepted");}catch(SocketTimeoutException expected){check(!broken.exists(),"Timed-out file retained");}
            try{client.download(base+"/unauthorized",-1,-1,broken,null);throw new AssertionError("403 accepted");}catch(Fetcher.HttpError expected){check(expected.code==403&&!broken.exists(),"Authentication rejection lost");}
            System.out.println("PASS: real HTTP ignored/valid/malformed Range, known/chunked 64MiB, exact hashes, redirect cookies/query, truncation, timeout and 403 under 32MiB heap");
        }finally{server.stop(0);workers.shutdownNow();try(java.util.stream.Stream<Path> paths=Files.walk(temp)){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(IOException ex){throw new UncheckedIOException(ex);}});}}
    }
}
