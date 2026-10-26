package de.evp.helfer;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Real Proxy/file consumers under -Xmx32m; generated transport does not allocate the body. */
public final class TestProxyMemory {
    static final long LARGE=64L<<20;
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    static Fetcher.Proxy.Req request(Fetcher.Proxy px)throws Exception{
        List<Fetcher.Proxy.Req> rs=px.pull(1,2000);check(rs.size()==1,"Missing request");return rs.get(0);
    }
    static void empty(Path dir)throws Exception{try(var paths=Files.walk(dir)){check(paths.noneMatch(p->p.toString().endsWith(".part")),"Spool leak");}}
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("proxy-memory-");ExecutorService threads=Executors.newFixedThreadPool(3);
        try{
            for(long total:new long[]{LARGE,-1}){
                Fetcher.Proxy px=new Fetcher.Proxy(root.resolve("body"+total).toFile());File out=root.resolve("result").toFile();
                Future<?> f=threads.submit(()->{try{return px.download("http://fixture/media",-1,-1,out,null);}catch(Exception e){throw new RuntimeException(e);}});
                Fetcher.Proxy.Req r=request(px);check(px.pushStream(r.id,200,total,new TestMemoryWindows.Generated(LARGE,0),LARGE),"Upload rejected");
                f.get(5,TimeUnit.SECONDS);TestMemoryWindows.fileHash(out,LARGE,0);out.delete();px.close();empty(root);
            }
            System.out.println("PASS: Proxy streams known/unknown 64MiB bodies and exact bytes at 32MiB heap");
            Fetcher.Proxy px=new Fetcher.Proxy(root.resolve("ranges").toFile());
            Future<byte[]> range=threads.submit(()->px.fetch("http://fixture/media",(8L<<20)+13,4L<<20));
            Fetcher.Proxy.Req r=request(px);px.pushStream(r.id,200,LARGE,new TestMemoryWindows.Generated(LARGE,0),LARGE);
            byte[] bytes=range.get(5,TimeUnit.SECONDS);check(bytes.length==4<<20,"Window length");for(int i=0;i<bytes.length;i++)check(bytes[i]==TestMemoryWindows.value(((8L<<20)+13)+i),"Window bytes");bytes=null;
            Future<byte[]> valid=threads.submit(()->px.fetch("http://fixture/media",10,20));r=request(px);px.pushStream(r.id,206,100,new TestMemoryWindows.Generated(20,10),20);
            check(valid.get().length==20,"Valid 206");
            Future<byte[]> bad=threads.submit(()->px.fetch("http://fixture/media",10,20));r=request(px);
            try{px.pushStream(r.id,206,100,new TestMemoryWindows.Generated(19,10),19);throw new AssertionError("Short 206 accepted");}catch(Fetcher.ResponseError expected){}
            try{bad.get();throw new AssertionError("Bad response accepted");}catch(ExecutionException expected){check(expected.getCause() instanceof Fetcher.HttpError,"Wrong range error");}
            check(px.pull(1,1).isEmpty(),"Invalid response retried");
            Future<byte[]> metadata=threads.submit(()->px.fetch("http://fixture/manifest",-1,-1));r=request(px);px.pushStream(r.id,200,-1,new TestMemoryWindows.Generated(LARGE,0),LARGE);
            try{metadata.get();throw new AssertionError("Oversize array accepted");}catch(ExecutionException expected){check(expected.getCause() instanceof Fetcher.ResponseError,"Wrong budget error");}
            px.close();empty(root);
            System.out.println("PASS: ignored Range slices the spool, valid 206 preserved, malformed length/oversize metadata rejected and cleaned");
            java.util.concurrent.CyclicBarrier directoryRace=new java.util.concurrent.CyclicBarrier(2);
            java.util.concurrent.atomic.AtomicInteger probes=new java.util.concurrent.atomic.AtomicInteger();
            File racedDirectory=new File(root.toFile(),"parallel"){
                @Override public boolean isDirectory(){
                    if(probes.getAndIncrement()<2){boolean observed=super.isDirectory();try{directoryRace.await(5,TimeUnit.SECONDS);}catch(Exception e){throw new AssertionError(e);}return observed;}
                    return super.isDirectory();
                }
                @Override public synchronized boolean mkdirs(){return super.mkdirs();}
            };
            Fetcher.Proxy parallel=new Fetcher.Proxy(racedDirectory);List<Future<?>> fs=new ArrayList<>();
            for(int i=0;i<2;i++){final int id=i;fs.add(threads.submit(()->{File out=root.resolve("parallel"+id).toFile();parallel.download("http://fixture/"+id,-1,-1,out,null);TestMemoryWindows.fileHash(out,LARGE,id);out.delete();return null;}));}
            List<Fetcher.Proxy.Req> pending=new ArrayList<>();while(pending.size()<2)pending.addAll(parallel.pull(2,2000));
            List<Future<?>> writers=new ArrayList<>();for(Fetcher.Proxy.Req item:pending){int id=Integer.parseInt(item.url.substring(item.url.length()-1));writers.add(CompletableFuture.runAsync(()->{try{parallel.pushStream(item.id,200,-1,new TestMemoryWindows.Generated(LARGE,id),LARGE);}catch(Exception e){throw new RuntimeException(e);}}));}
            for(Future<?> f:writers)f.get();for(Future<?> f:fs)f.get();parallel.close();empty(root);
            System.out.println("PASS: two concurrent 64MiB Proxy transfers and bytehashes at 32MiB heap");
            Fetcher.Proxy timeout=new Fetcher.Proxy(root.resolve("timeout").toFile(),80);
            Future<byte[]> timed=threads.submit(()->timeout.fetch("http://fixture/missing",-1,-1));r=request(timeout);
            try{timed.get(3,TimeUnit.SECONDS);throw new AssertionError("Timeout missing");}catch(ExecutionException expected){check(expected.getCause() instanceof java.net.SocketTimeoutException,"Wrong timeout error");}
            check(timeout.pull(1,1).isEmpty()&&!timeout.push(r.id,200,-1,new byte[0]),"Timed out request retained");
            File stale=root.resolve("stale.part").toFile();Files.writeString(stale.toPath(),"stale");
            Future<?> timedFile=threads.submit(()->timeout.download("http://fixture/missing",-1,-1,stale,null));request(timeout);
            try{timedFile.get(3,TimeUnit.SECONDS);throw new AssertionError("File timeout missing");}catch(ExecutionException expected){}
            check(!stale.exists(),"Timed-out output retained");timeout.close();
            Fetcher.Proxy cancelled=new Fetcher.Proxy(root.resolve("cancel").toFile());File out=root.resolve("cancelled").toFile();
            Future<?> stopped=threads.submit(()->cancelled.download("http://fixture/cancel",-1,-1,out,null));r=request(cancelled);
            InputStream abort=new TestMemoryWindows.Generated(LARGE,0){@Override public int read(byte[] b,int o,int n){int got=super.read(b,o,n);cancelled.close();return got;}};
            try{cancelled.pushStream(r.id,200,-1,abort,LARGE);throw new AssertionError("Cancelled upload accepted");}catch(IOException expected){}
            try{stopped.get(3,TimeUnit.SECONDS);throw new AssertionError("Cancelled download succeeded");}catch(ExecutionException expected){}
            check(!out.exists(),"Cancelled output retained");empty(root);
            System.out.println("PASS: timeout/late push, active-upload cancellation and temporary-file cleanup");
            for(String kind:new String[]{"hls","dash"}){
                Fetcher.Proxy media=new Fetcher.Proxy(root.resolve(kind+"-spool").toFile());File work=root.resolve(kind).toFile();
                Future<HlsCore.Result> result=threads.submit(()->kind.equals("hls")?new HlsCore(media,work,2).run("http://fixture/index.m3u8",null):new DashCore(media,work,2).run("http://fixture/index.mpd",null));
                r=request(media);String manifest=kind.equals("hls")?"#EXTM3U\n#EXTINF:1,\nmedia.bin\n#EXT-X-ENDLIST\n":"<MPD type=\"static\" mediaPresentationDuration=\"PT1S\"><Period><AdaptationSet mimeType=\"video/mp4\"><Representation id=\"v\" bandwidth=\"1\"><BaseURL>media.bin</BaseURL></Representation></AdaptationSet></Period></MPD>";
                media.push(r.id,200,-1,manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8));r=request(media);media.pushStream(r.id,200,-1,new TestMemoryWindows.Generated(LARGE,0),LARGE);
                TestMemoryWindows.fileHash(result.get(5,TimeUnit.SECONDS).video,LARGE,0);media.close();empty(root);
            }
            System.out.println("PASS: actual HlsCore/DashCore consume 64MiB Proxy segments without byte-array materialization");
        }finally{threads.shutdownNow();try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
}
