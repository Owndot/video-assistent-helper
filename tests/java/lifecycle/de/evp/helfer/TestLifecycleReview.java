package de.evp.helfer;

import android.content.*;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import java.io.*;
import java.nio.file.*;
import java.security.Permission;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static de.evp.helfer.TestDownloadPublication.*;

/** Actual Downloads and actual Service callbacks, explicit host API/provider/main-executor models. */
public final class TestLifecycleReview {
    static final String FOLDER="Download/Video-Assistent/";
    static void resetRecovery()throws Exception {var f=Downloads.class.getDeclaredField("initialized");f.setAccessible(true);f.setBoolean(null,false);}
    static Path proof(Fake c,String token)throws Exception {
        Path p=c.getFilesDir().toPath().resolve("download-pending-v1").resolve(token);Files.createDirectories(p.getParent());Files.writeString(p,"");return p;
    }
    static Uri row(Fake c,String token,String owner,int pending,String folder,String downloadUri)throws Exception {
        ContentValues v=new ContentValues();v.put(MediaStore.Downloads.DISPLAY_NAME,token.startsWith("evp-publish-")?token+"--fixture (1).mp4":token);v.put("relative_path",folder);v.put("is_pending",pending);
        if(downloadUri!=null)v.put("download_uri",downloadUri);
        Uri u=c.store.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v);Properties p=c.store.props(u);p.setProperty("owner_package_name",owner);c.store.write(u,p);Files.writeString(c.store.data(u),"KEEP");return u;
    }
    static int visible(Fake c,Uri collection)throws Exception {
        int count=0;try(var rows=c.store.query(collection,new String[]{"_id"},"owner_package_name=? AND is_pending=1 AND (relative_path=? OR relative_path=?)",new String[]{c.getPackageName(),FOLDER,FOLDER.substring(0,FOLDER.length()-1)},null)){while(rows.moveToNext())count++;}return count;
    }
    static void pending()throws Exception {
        Path root=Files.createTempDirectory("pending-review-");Fake c=new Fake(root);
        try {
            String own="evp-publish-101.pending";Path marker=proof(c,own);Uri abandoned=row(c,own,c.getPackageName(),1,FOLDER,null);
            check(visible(c,MediaStore.Downloads.EXTERNAL_CONTENT_URI)==0,"ordinary collection query exposed pending");
            check(visible(c,MediaStore.setIncludePending(MediaStore.Downloads.EXTERNAL_CONTENT_URI))==1,"includePending did not expose same row");
            List<Uri> keep=new ArrayList<>();
            proof(c,"evp-publish-104.pending");keep.add(row(c,"evp-publish-104.pending",c.getPackageName(),0,FOLDER,null));
            proof(c,"evp-publish-105.pending");keep.add(row(c,"evp-publish-105.pending","another.app",1,FOLDER,null));
            keep.add(row(c,"no-proof.mp4",c.getPackageName(),1,FOLDER,null));
            keep.add(row(c,"outside.mp4",c.getPackageName(),1,"Download/Other/",null));
            String dm="evp-publish-102.pending";proof(c,dm);keep.add(row(c,dm,c.getPackageName(),1,FOLDER,"https://synthetic/dm"));
            Downloads.initialize(c);
            check(!Files.exists(c.store.meta(abandoned)),"owned pending row survived recovery");
            check(!Files.exists(marker),"completed recovery retained marker");
            for(Uri u:keep)check(Files.readString(c.store.data(u)).equals("KEEP"),"recovery damaged retained file "+u);
            resetRecovery();Downloads.initialize(c);check(c.store.rows().size()==keep.size(),"repeated recovery damaged rows");
            String retry="evp-publish-103.pending";Path retryProof=proof(c,retry);Uri retryRow=row(c,retry,c.getPackageName(),1,FOLDER,null);
            for(String fault:List.of("query","null-query","delete")) {
                resetRecovery();c.store.failQuery=fault.equals("query");c.store.nullQuery=fault.equals("null-query");c.store.failDelete=fault.equals("delete");
                try{Downloads.initialize(c);throw new AssertionError("provider "+fault+" failure reported recovery success");}catch(Exception expected){}
                check(Files.exists(retryProof)&&Files.exists(c.store.meta(retryRow)),"failed recovery discarded evidence");
                c.store.failQuery=c.store.nullQuery=c.store.failDelete=false;
            }
            Downloads.initialize(c);check(!Files.exists(c.store.meta(retryRow)),"failed recovery was not retryable");
            System.out.println("PASS: Android pending filter/include, owned-proof recovery, completed/foreign/DM/unproved preservation, repeat and provider-error retry");
        } finally {TestDownloadLifecycle.remove(root);}
    }
    static void awaitUninterruptibly(CountDownLatch latch) {
        boolean done=false;while(!done)try{done=latch.await(15,TimeUnit.SECONDS);check(done,"controlled provider was not released");}catch(InterruptedException ignored){} // Binder-like provider ignores thread interrupts.
    }
    static void mainCall(ExecutorService main,Callable<?> action,String why)throws Exception {
        Future<?> f=main.submit(action);try{f.get(1,TimeUnit.SECONDS);}catch(TimeoutException e){throw new AssertionError(why,e);}
    }
    static void blocking(boolean after) throws Exception {
        Path root=Files.createTempDirectory("commit-review-");Fake c=new Fake(root);Downloads.serviceStarted(c);
        ExecutorService workers=Executors.newFixedThreadPool(2),main=Executors.newSingleThreadExecutor(r->new Thread(r,"android-main-model"));
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),bStarted=new CountDownLatch(1);
        AtomicReference<Uri> blocked=new AtomicReference<>();HelferService service=new HelferService();HelferService.running=true;
        Future<?> a=null,b=null;
        try {
            java.util.function.Consumer<Uri> pause=u->{if(blocked.compareAndSet(null,u)){entered.countDown();awaitUninterruptibly(release);}};
            if(after)c.store.updated=pause;else c.store.updating=pause;
            a=workers.submit(()->{job(c,-801,"COMMIT_A");return null;});check(entered.await(3,TimeUnit.SECONDS),"A did not enter provider");
            Fetcher waiting=new Fetcher(){public long size(String u){return -1;}public byte[] fetch(String u,long o,long n)throws IOException{bStarted.countDown();try{new CountDownLatch(1).await();throw new AssertionError();}catch(InterruptedException e){throw new IOException("cancelled B",e);}}};
            b=workers.submit(()->{TestDownloadLifecycle.invoke(c,-802,waiting);return null;});check(bStarted.await(3,TimeUnit.SECONDS),"B did not start");
            mainCall(main,()->service.onStartCommand(new Intent("cancel").putExtra("id",-801),0,1),"main-thread cancel blocked behind provider");
            mainCall(main,()->service.onStartCommand(new Intent("cancel").putExtra("id",-802),0,2),"other job cancellation blocked");
            b.get(3,TimeUnit.SECONDS);check("abgebrochen".equals(Downloads.state.get(-802)),"B not cancelled independently");
            mainCall(main,()->{service.onDestroy();return null;},"service stop blocked behind provider");
            check(service.destroyed,"actual service callback did not reach super");
            mainCall(main,()->{Downloads.serviceStarted(c);HelferService.running=true;return null;},"same-process restart blocked on old commit");
            check(Files.exists(root.resolve("cache/dl-801/datei.mp4")),"restart deleted still-unwinding job");
            release.countDown();a.get(3,TimeUnit.SECONDS);
            check("abgebrochen".equals(Downloads.state.get(-801))&&c.store.rows().isEmpty(),"pre-commit cancellation published A");
            job(c,-803,"COMPLETED");
            for(int i=0;i<3;i++)mainCall(main,()->service.onStartCommand(new Intent("cancel").putExtra("id",-803),0,3),"late cancel blocked");
            check("fertig".equals(Downloads.state.get(-803))&&c.store.rows().size()==1,"late cancel undid completed job");treeEmpty(c.getCacheDir());
            System.out.println("PASS: actual onStartCommand/onDestroy remain responsive during blocked provider; B cancellation, restart, A rollback and late/repeated cancel settle");
        } finally {release.countDown();workers.shutdownNow();main.shutdownNow();try{if(a!=null)a.get(4,TimeUnit.SECONDS);if(b!=null)b.get(4,TimeUnit.SECONDS);}finally{TestDownloadLifecycle.shutdown();TestDownloadLifecycle.remove(root);}}
    }
    static void admission()throws Exception {
        Path root=Files.createTempDirectory("admission-review-");CountDownLatch blocked=new CountDownLatch(1),release=new CountDownLatch(1);AtomicBoolean delay=new AtomicBoolean();
        Fake c=new Fake(root){public File getFilesDir(){if(delay.get()){blocked.countDown();awaitUninterruptibly(release);}return super.getFilesDir();}};
        Downloads.serviceStarted(c);ExecutorService worker=Executors.newSingleThreadExecutor(),main=Executors.newSingleThreadExecutor();
        HelferService service=new HelferService();HelferService.running=true;
        try {
            delay.set(true);Future<?> start=worker.submit(()->{try{Downloads.start(c,"http://synthetic/file.mp4",null,null,null,"late","file",true);throw new AssertionError("old admission survived service restart");}catch(IOException expected){check(expected.getMessage().contains("stopping"),"wrong stale admission error");}});
            check(blocked.await(3,TimeUnit.SECONDS),"start did not reach file work");
            mainCall(main,()->{service.onDestroy();return null;},"service stop blocked on admission file work");
            mainCall(main,()->{Downloads.serviceStarted(c);return null;},"service restart blocked on admission file work");
            delay.set(false);release.countDown();start.get(3,TimeUnit.SECONDS);
            check(Downloads.proxies.isEmpty(),"old start leaked a proxy after restart");
            System.out.println("PASS: service stop/restart do not wait on admission file work; stale generation cannot register");
        } finally {delay.set(false);release.countDown();worker.shutdownNow();main.shutdownNow();TestDownloadLifecycle.shutdown();TestDownloadLifecycle.remove(root);}
    }
    static void interruptRace()throws Exception {
        Path root=Files.createTempDirectory("interrupt-lifetime-");Fake c=new Fake(root);
        CountDownLatch fetched=new CountDownLatch(1),finishRead=new CountDownLatch(1),signalEntered=new CountDownLatch(1),deliverSignal=new CountDownLatch(1),jobExited=new CountDownLatch(1);
        AtomicReference<Thread> thread=new AtomicReference<>();AtomicBoolean gate=new AtomicBoolean(true);
        // Test-only interruption gate exposes the exact signal/worker-reuse interleaving.
        ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"controlled-download-worker"){
            public void interrupt(){if(gate.get()){signalEntered.countDown();awaitUninterruptibly(deliverSignal);}super.interrupt();}
        };thread.set(t);return t;}),main=Executors.newSingleThreadExecutor();
        HelferService service=new HelferService();HelferService.running=true;
        try {
            Fetcher net=new Fetcher(){public long size(String u){return 8;}public byte[] fetch(String u,long o,long n){fetched.countDown();awaitUninterruptibly(finishRead);return "01234567".getBytes();}};
            Future<?> a=worker.submit(()->{try{TestDownloadLifecycle.invoke(c,-804,net);}finally{jobExited.countDown();}return null;});check(fetched.await(3,TimeUnit.SECONDS),"A did not fetch");
            Future<?> cancel=main.submit(()->service.onStartCommand(new Intent("cancel").putExtra("id",-804),0,4));
            check(signalEntered.await(3,TimeUnit.SECONDS),"cancel did not reach signal");finishRead.countDown();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(jobExited.getCount()!=0&&thread.get().getState()!=Thread.State.BLOCKED&&System.nanoTime()<deadline)Thread.yield();
            check(jobExited.getCount()!=0&&thread.get().getState()==Thread.State.BLOCKED,"worker exited before pending interrupt; could hit next job");
            deliverSignal.countDown();cancel.get(3,TimeUnit.SECONDS);a.get(3,TimeUnit.SECONDS);gate.set(false);
            check(worker.submit(()->!Thread.currentThread().isInterrupted()).get(3,TimeUnit.SECONDS),"next job inherited stale interrupt");
            System.out.println("PASS: pending cancellation signal cannot outlive its worker ownership or interrupt a reused worker");
        } finally {gate.set(false);finishRead.countDown();deliverSignal.countDown();worker.shutdownNow();main.shutdownNow();worker.awaitTermination(3,TimeUnit.SECONDS);TestDownloadLifecycle.shutdown();TestDownloadLifecycle.remove(root);}
    }
    @SuppressWarnings("removal")
    static void legacy()throws Exception {
        Path root=Files.createTempDirectory("legacy-review-");Fake c=new Fake(root);Build.VERSION.SDK_INT=26;
        File dir=new File(c.getExternalFilesDir("Download"),"Video-Assistent");String target=dir.getPath();Path source=root.resolve("source");Files.writeString(source,"CORRECT_BYTES");
        ExecutorService pool=Executors.newFixedThreadPool(2);SecurityManager previous=System.getSecurityManager();
        CountDownLatch mkdirs=new CountDownLatch(2),created=new CountDownLatch(1);AtomicBoolean creator=new AtomicBoolean();ThreadLocal<Boolean> nested=ThreadLocal.withInitial(()->false);
        // JDK 17/21 test-only filesystem interposition: both real save calls observed missing dir;
        // before their actual mkdirs(), a competing creator makes it exist. No product/test hook.
        SecurityManager interleave=new SecurityManager(){public void checkPermission(Permission p){}public void checkWrite(String p){
            if(p.equals(target)&&!nested.get()) {
                mkdirs.countDown();try{check(mkdirs.await(3,TimeUnit.SECONDS),"both saves did not reach mkdirs");
                    if(creator.compareAndSet(false,true)){nested.set(true);Files.createDirectory(dir.toPath());Files.writeString(dir.toPath().resolve("same.mp4"),"KEEP");created.countDown();}else check(created.await(3,TimeUnit.SECONDS),"creator did not finish");
                }catch(Exception e){throw new AssertionError(e);}finally{nested.set(false);}
            }
        }};
        try {
            System.setSecurityManager(interleave);
            List<Future<Uri>> results=new ArrayList<>();for(int i=0;i<2;i++)results.add(pool.submit(()->Downloads.save(c,source.toFile(),"same.mp4","video/mp4")));
            Set<String> paths=new HashSet<>();for(Future<Uri> result:results){Uri u;try{u=result.get(5,TimeUnit.SECONDS);}catch(ExecutionException e){throw new AssertionError("concurrent first legacy save failed",e.getCause());}paths.add(u.toString());check(Files.readString(Path.of(java.net.URI.create(u.toString()))).equals("CORRECT_BYTES"),"legacy bytes damaged");}
            check(paths.size()==2&&Files.readString(dir.toPath().resolve("same.mp4")).equals("KEEP"),"same-name collision overwrote data");
            System.setSecurityManager(previous);
            Path failureRoot=root.resolve("failure");Fake denied=new Fake(failureRoot);Files.writeString(denied.getExternalFilesDir("Download").toPath().resolve("Video-Assistent"),"not a directory");
            try{Downloads.save(denied,source.toFile(),"no.mp4","video/mp4");throw new AssertionError("invalid directory accepted");}catch(IOException expected){}
            Fake permission=new Fake(root.resolve("permission"));String deniedDir=new File(permission.getExternalFilesDir("Download"),"Video-Assistent").getPath();
            System.setSecurityManager(new SecurityManager(){public void checkPermission(Permission p){}public void checkWrite(String p){if(p.equals(deniedDir))throw new SecurityException("synthetic permission denial");}});
            try{Downloads.save(permission,source.toFile(),"denied.mp4","video/mp4");throw new AssertionError("permission denial ignored");}catch(SecurityException expected){}finally{System.setSecurityManager(previous);}
            try{Downloads.save(c,root.resolve("missing-source").toFile(),"no.mp4","video/mp4");throw new AssertionError("missing input accepted");}catch(IOException expected){}
            try(var files=Files.list(dir.toPath())){check(files.noneMatch(p->p.getFileName().toString().startsWith(".evp-pending-")),"failed staging leaked");}
            System.out.println("PASS: deterministic concurrent first mkdir/save, exact separate bytes, existing file preservation, real directory/input failures and staging cleanup");
        } finally {System.setSecurityManager(previous);Build.VERSION.SDK_INT=29;pool.shutdownNow();TestDownloadLifecycle.remove(root);}
    }
    public static void main(String[] args)throws Exception {
        switch(args[0]) {case "pending":pending();break;case "blocking":blocking(false);break;case "blocking-after":blocking(true);break;case "admission":admission();break;case "interrupt-race":interruptRace();break;case "legacy":legacy();break;default:throw new AssertionError("unknown case");}
    }
}
