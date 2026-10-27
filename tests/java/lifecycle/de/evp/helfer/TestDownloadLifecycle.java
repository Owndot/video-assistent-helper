package de.evp.helfer;

import android.net.Uri;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static de.evp.helfer.TestDownloadPublication.*;

/** Actual Downloads orchestration. Provider/notifications are explicit host models. */
public final class TestDownloadLifecycle {
    static void invoke(Fake c,int id,Fetcher net)throws Exception {
        var method=Downloads.class.getDeclaredMethod("streamJob",android.content.Context.class,int.class,String.class,String.class,String.class,Fetcher.class);
        method.setAccessible(true);method.invoke(null,c,id,"http://synthetic/file.mp4","fixture","file",net);
    }
    static void shutdown()throws Exception {
        for(String name:new String[]{"jobs","credentialedFiles"}){var f=Downloads.class.getDeclaredField(name);f.setAccessible(true);((ExecutorService)f.get(null)).shutdownNow();}
    }
    static void remove(Path root)throws IOException {try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    static void parallel()throws Exception {
        Path root=Files.createTempDirectory("parallel-lifecycle-");Fake c=new Fake(root);
        Downloads.serviceStarted(c);
        ExecutorService pool=Executors.newFixedThreadPool(3);
        try {
            CountDownLatch started=new CountDownLatch(1);AtomicInteger reads=new AtomicInteger();
            Fetcher blocked=new Fetcher(){public long size(String u){return -1;}public byte[] fetch(String u,long o,long n)throws IOException {
                reads.incrementAndGet();started.countDown();try{new CountDownLatch(1).await();throw new AssertionError();}
                catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new IOException("interrupted",stopped);}}};
            Future<?> stopped=pool.submit(()->{invoke(c,-701,blocked);return null;});
            check(started.await(3,TimeUnit.SECONDS),"blocked job did not start");
            Future<?> good=pool.submit(()->{job(c,-702,"OTHER_JOB_BYTES");return null;});
            Downloads.cancelJob(-701);stopped.get(3,TimeUnit.SECONDS);good.get(3,TimeUnit.SECONDS);
            check("abgebrochen".equals(Downloads.state.get(-701)),"cancelled job wrong status");
            check("fertig".equals(Downloads.state.get(-702)),"cancel affected other job");
            check(reads.get()==1&&c.store.rows().size()==1,"unexpected retries/publication");
            check(Files.readString(c.store.data(c.store.rows().get(0))).equals("OTHER_JOB_BYTES"),"other job bytes damaged");treeEmpty(c.getCacheDir());
            // Real start/queue route: both worker slots occupied, cancel before the queued job begins.
            var jobsField=Downloads.class.getDeclaredField("jobs");jobsField.setAccessible(true);ExecutorService jobs=(ExecutorService)jobsField.get(null);
            CountDownLatch occupied=new CountDownLatch(2),unblock=new CountDownLatch(1);
            try {
                for(int i=0;i<2;i++)jobs.submit(()->{occupied.countDown();try{unblock.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
                check(occupied.await(3,TimeUnit.SECONDS),"worker slots not occupied");
                int queued=Downloads.start(c,"http://synthetic/queued.mp4",null,null,null,"queued","file",true);
                Fetcher.Proxy waiting=Downloads.proxies.get(queued);Downloads.cancelJob(queued);
                check(waiting.pull(1,1).isEmpty(),"cancelled queued job requested bytes");unblock.countDown();
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
                while(Downloads.proxies.containsKey(queued)&&System.nanoTime()<deadline)Thread.sleep(5);
                check(!Downloads.proxies.containsKey(queued)&&"abgebrochen".equals(Downloads.state.get(queued)),"queued cancellation not cleaned");
                check(c.store.rows().size()==1,"queued cancellation published a file");
            } finally {unblock.countDown();}
            CountDownLatch proxyStarted=new CountDownLatch(1);Fetcher.Proxy px=new Fetcher.Proxy(root.resolve("cache/dl-703").toFile());Downloads.proxies.put(-703,px);
            Future<?> proxy=pool.submit(()->{proxyStarted.countDown();invoke(c,-703,px);return null;});proxyStarted.await();
            List<Fetcher.Proxy.Req> requests=px.pull(1,2000);check(requests.size()==1,"proxy request missing");
            Downloads.stopAll();proxy.get(3,TimeUnit.SECONDS);
            check("abgebrochen".equals(Downloads.state.get(-703)),"service stop did not cancel proxy");
            check(!px.push(requests.get(0).id,200,8,"LATEBYTE".getBytes()),"late push accepted");treeEmpty(c.getCacheDir());
            try{Downloads.start(c,"http://synthetic/file.mp4",null,null,null,"late","file",false);throw new AssertionError("stopped service accepted a job");}
            catch(IOException expected){check(expected.getMessage().contains("stopping"),"wrong stop rejection");}
            System.out.println("PASS: cancel one active worker, preserve parallel bytes, stopAll closes proxy and rejects late upload");
        }finally{pool.shutdownNow();shutdown();remove(root);}
    }
    static Uri row(Fake c,String owner,String folder,int pending)throws Exception {
        android.content.ContentValues v=new android.content.ContentValues();v.put("relative_path",folder);v.put("is_pending",pending);
        Uri u=c.store.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,v);Properties p=c.store.props(u);p.setProperty("owner_package_name",owner);c.store.write(u,p);Files.writeString(c.store.data(u),"KEEP");return u;
    }
    static Process launch(String mode,Path root)throws IOException {
        return new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Xmx32m","-cp",System.getProperty("java.class.path"),TestDownloadLifecycle.class.getName(),mode,root.toString()).inheritIO().start();
    }
    static void crash()throws Exception {
        Path root=Files.createTempDirectory("killed-download-");Process child=null,restarted=null;
        try {
            Fake c=new Fake(root);row(c,"de.evp.helfer","Download/Video-Assistent/",0);
            row(c,"foreign.app","Download/Video-Assistent/",1);row(c,"de.evp.helfer","Download/Other/",1);
            Files.writeString(c.getCacheDir().toPath().resolve("keep.txt"),"KEEP");
            Path legacy=c.getExternalFilesDir("Download").toPath().resolve("Video-Assistent");Files.createDirectories(legacy);
            Files.writeString(legacy.resolve("completed.mp4"),"KEEP");Files.writeString(legacy.resolve(".evp-pending-crashed.part"),"unfinished");
            child=launch("--child",root);long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(!Files.exists(root.resolve("READY"))&&child.isAlive()&&System.nanoTime()<until)Thread.sleep(20);
            check(Files.exists(root.resolve("READY")),"child did not reach pending publication");
            child.destroyForcibly();check(child.waitFor(5,TimeUnit.SECONDS)&&child.exitValue()!=0,"child was not killed");
            check(c.store.rows().size()==4,"kill unexpectedly ran cleanup");
            check(Files.exists(root.resolve("cache/dl-710/datei.mp4")),"kill did not leave actual private transfer");
            Uri pending=c.store.rows().stream().filter(u->!"KEEP".equals(read(c.store.data(u)))).findFirst().orElseThrow();
            check(Files.size(c.store.data(pending))>0&&Files.size(c.store.data(pending))<(1<<20),"provider fault was not mid-copy");
            restarted=launch("--recover",root);check(restarted.waitFor(10,TimeUnit.SECONDS)&&restarted.exitValue()==0,"fresh process recovery failed");
            System.out.println("PASS: separate JVM killed during actual save; new JVM discards owned pending/cache/staging, preserves completed/foreign files, never resumes");
        }finally{if(child!=null&&child.isAlive())child.destroyForcibly();if(restarted!=null&&restarted.isAlive())restarted.destroyForcibly();remove(root);}
    }
    static String read(Path p){try{return Files.readString(p);}catch(IOException e){throw new UncheckedIOException(e);}}
    public static void main(String[] args)throws Exception {
        if(args.length==0){parallel();return;}
        if(args[0].equals("--crash")){crash();return;}
        Fake c=new Fake(Path.of(args[1]));
        if(args[0].equals("--child")){
            c.store.writing=u->{try{Files.writeString(c.root.resolve("READY"),"pending");new CountDownLatch(1).await();}catch(Exception e){throw new RuntimeException(e);}};
            job(c,-710,"A".repeat(1<<20));throw new AssertionError("child escaped controlled blocking point");
        }
        if(args[0].equals("--recover")){
            Downloads.initialize(c);Downloads.initialize(c);
            check(Downloads.state.isEmpty(),"restart resurrected process-only state");
            check(c.store.rows().size()==3,"recovery removed completed/foreign/unrelated rows or retained own pending");
            for(Uri u:c.store.rows())check(read(c.store.data(u)).equals("KEEP"),"recovery damaged saved bytes");
            check(!Files.exists(c.root.resolve("cache/dl-710"))&&read(c.root.resolve("cache/keep.txt")).equals("KEEP"),"private cleanup scope");
            check(!Files.exists(c.root.resolve("external/Video-Assistent/.evp-pending-crashed.part")),"legacy staging retained");
            check(read(c.root.resolve("external/Video-Assistent/completed.mp4")).equals("KEEP"),"legacy completed removed");shutdown();return;
        }
        throw new AssertionError("unknown mode");
    }
}
