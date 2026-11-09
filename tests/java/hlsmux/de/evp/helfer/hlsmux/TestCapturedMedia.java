package de.evp.helfer.hlsmux;

import de.evp.helfer.hls.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/** Test-only address-free staging + the UNMODIFIED production Media3 remux/verification implementation. */
public final class TestCapturedMedia {
    static int checks;
    static File root;
    static List<byte[]> data;
    static List<CapturedMediaInbox.Part> metadata;
    static void check(String label, boolean ok) {
        if (!ok) throw new AssertionError(label);
        checks++; System.out.println("PASS capture-inbox " + label);
    }
    static CapturedMediaInbox inbox() throws IOException { return new CapturedMediaInbox(root, metadata, 20); }
    static void send(CapturedMediaInbox box) throws IOException {
        for (int i=0; i<data.size(); i++) {
            byte[] bytes = data.get(i);
            for (int at=0; at<bytes.length; at+=CapturedMediaInbox.CHUNK)
                box.accept(i, at, Arrays.copyOfRange(bytes, at, Math.min(bytes.length, at+CapturedMediaInbox.CHUNK)));
        }
    }
    interface Attempt { void run() throws Exception; }
    static void rejects(String label, Attempt attempt) throws Exception {
        boolean rejected = false;
        try { attempt.run(); } catch (IOException | HlsException expected) { rejected = true; }
        check(label, rejected);
    }
    static boolean noMedia(CapturedMediaInbox box) throws IOException {
        try (var files = Files.list(box.directoryForTest().toPath())) {
            return files.noneMatch(p -> !p.getFileName().toString().equals("cancelled"));
        }
    }
    static final class Gate implements HlsRemux {
        final HlsRemux delegate = new Media3HlsRemuxer(false);
        final CountDownLatch reached = new CountDownLatch(1), release = new CountDownLatch(1);
        final boolean afterVerify;
        Gate(boolean afterVerify) { this.afterVerify = afterVerify; }
        void barrier() throws IOException {
            reached.countDown();
            try { if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("test_timeout"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("test_interrupted"); }
        }
        public Result remux(Input in, File out, BooleanSupplier cancelled) throws IOException, HlsException {
            if (!afterVerify) barrier(); return delegate.remux(in,out,cancelled);
        }
        public void verify(File out, Result r, double vs, double as, double t, BooleanSupplier cancelled) throws IOException, HlsException {
            delegate.verify(out,r,vs,as,t,cancelled); if (afterVerify) barrier();
        }
    }
    public static void main(String[] args) throws Exception {
        root = new File(args[1]); root.mkdirs();
        data = new ArrayList<>();
        File fixture = new File(args[0], "fmp4_muxed");
        if (args.length > 2 && !args[2].isEmpty()) {
            // Local test artifact exported by the browser probe. No URLs, headers or chosen file names.
            Path export = Path.of(args[2]);
            List<String> lines = Files.readAllLines(export.resolve("fixture.txt"));
            if (lines.size()<4 || !lines.get(0).equals("capture-fixture-v1") || !lines.get(1).equals("20000")) throw new IOException("test_fixture");
            int count=Integer.parseInt(lines.get(2));
            if (count<1 || count>CapturedMediaInbox.MAX_PARTS || lines.size()!=count+3) throw new IOException("test_fixture");
            for (int i=0;i<count;i++) {
                byte[] b=Files.readAllBytes(export.resolve(String.format(Locale.ROOT,"%04d.bin",i)));
                String expected=b.length+" "+HexFormat.of().formatHex(CapturedMediaInbox.sha().digest(b));
                if (!lines.get(i+3).equals(expected)) throw new IOException("test_fixture_integrity");
                data.add(b);
            }
            check("input is actual exported browser capture", data.size()==6);
        } else {
            for (File f : TestMedia3Remux.res(fixture, "v0.m3u8")) data.add(Files.readAllBytes(f.toPath()));
        }
        metadata = new ArrayList<>();
        for (byte[] b : data) metadata.add(new CapturedMediaInbox.Part(b.length,CapturedMediaInbox.sha().digest(b)));
        CapturedMediaInbox good=inbox(); send(good); good.verify(new Media3HlsRemuxer(false));
        check("real remux and strict duration/track/sample verification",good.state().equals("verified")&&good.outputForTest().length()>0);
        Process decode = new ProcessBuilder("ffmpeg","-v","error","-xerror","-i",good.outputForTest().getPath(),"-map","0:v:0","-map","0:a:0","-f","null","-").redirectErrorStream(true).start();
        byte[] output=decode.getInputStream().readAllBytes();
        check("independent full video/audio decode",decode.waitFor()==0&&output.length==0);
        rejects("descriptor bytes bounded before directory creation",()->new CapturedMediaInbox(root,List.of(new CapturedMediaInbox.Part(Long.MAX_VALUE,new byte[32])),20));
        rejects("descriptor hash bounded before copy",()->new CapturedMediaInbox.Part(1,new byte[33]));
        rejects("descriptor duration finite",()->new CapturedMediaInbox(root,metadata,Double.NaN));
        CapturedMediaInbox missing=inbox(); rejects("incomplete never remuxed",()->missing.verify(new Media3HlsRemuxer(false))); check("incomplete cleaned",noMedia(missing));
        CapturedMediaInbox order=inbox(); rejects("wrong resource order",()->order.accept(1,0,new byte[1])); check("order failure cleaned",noMedia(order));
        CapturedMediaInbox offset=inbox(); rejects("wrong byte offset",()->offset.accept(0,1,new byte[1]));
        CapturedMediaInbox oversized=inbox(); rejects("bounded chunk",()->oversized.accept(0,0,new byte[CapturedMediaInbox.CHUNK+1]));
        CapturedMediaInbox corrupt=inbox(); byte[] changed=data.get(0).clone(); changed[changed.length-1]^=1;
        rejects("resource digest mismatch",()->corrupt.accept(0,0,changed)); check("bad digest cleaned",noMedia(corrupt));
        CapturedMediaInbox disk=inbox(); send(disk);
        try(RandomAccessFile f=new RandomAccessFile(new File(disk.directoryForTest(),"0001.bin"),"rw")){f.seek(20);f.write(0);}
        // Ensure a definite mutation even if the original byte happened to be zero.
        try(RandomAccessFile f=new RandomAccessFile(new File(disk.directoryForTest(),"0001.bin"),"rw")){f.seek(0);int b=f.read();f.seek(0);f.write(b^1);}
        rejects("on-disk data rechecked before remux",()->disk.verify(new Media3HlsRemuxer(false)));check("disk corruption cleaned",noMedia(disk));
        CapturedMediaInbox cancelled=inbox(); cancelled.accept(0,0,new byte[]{data.get(0)[0]});cancelled.cancel();
        rejects("late chunks cannot revive cancellation",()->cancelled.accept(0,1,new byte[1]));
        check("cancel marker retained, private bytes cleared",noMedia(cancelled)&&new File(cancelled.directoryForTest(),"cancelled").isFile());
        CapturedMediaInbox recovered=CapturedMediaInbox.recoverForTest(cancelled.directoryForTest());
        rejects("cancel marker wins after reopen",()->recovered.accept(0,0,new byte[1]));check("reopen remains cancelled",recovered.state().equals("cancelled"));
        CapturedMediaInbox interrupted=inbox();send(interrupted);
        CapturedMediaInbox reopened=CapturedMediaInbox.recoverForTest(interrupted.directoryForTest());
        rejects("no unimplemented restart/resume fallback",()->reopened.accept(0,0,new byte[1]));check("interrupted bytes cleaned",noMedia(reopened));
        for(boolean after:new boolean[]{false,true}) {
            CapturedMediaInbox box=inbox();send(box);Gate gate=new Gate(after);AtomicReference<Throwable> error=new AtomicReference<>();
            Thread work=new Thread(()->{try{box.verify(gate);}catch(Throwable e){error.set(e);}});work.start();
            if(!gate.reached.await(10,TimeUnit.SECONDS))throw new AssertionError("test gate not reached");
            box.cancel();gate.release.countDown();work.join(10000);
            check(after?"cancel after verification wins commit race":"cancel while remux starts wins",!work.isAlive()&&error.get()!=null&&box.state().equals("cancelled")&&noMedia(box));
        }
        byte[] html="<html>not media</html>".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        CapturedMediaInbox nonmedia=new CapturedMediaInbox(root,List.of(new CapturedMediaInbox.Part(html.length,CapturedMediaInbox.sha().digest(html))),20);
        nonmedia.accept(0,0,html);rejects("valid digest does not turn HTML into media",()->nonmedia.verify(new Media3HlsRemuxer(false)));check("non-media cleaned",noMedia(nonmedia));
        System.out.println("RESULT capture-inbox EVIDENCE_PASS "+checks+"; NO_NETWORK_ENDPOINT; NO_PUBLICATION");
    }
}
