package de.evp.helfer;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Actual production readers/cores at -Xmx32m; synthetic transport, no sockets. */
public class TestMemoryWindows {
    static final long LARGE=64L<<20;
    static void check(boolean ok,String msg){if(!ok)throw new AssertionError(msg);}
    static byte value(long i){return (byte)(i*31+7);}
    static class Generated extends InputStream {
        final long length,base;long count;boolean closed;
        Generated(long length,long base){this.length=length;this.base=base;}
        public int read(){return count>=length?-1:value(base+count++)&255;}
        public int read(byte[] b,int o,int n){if(count>=length)return -1;int k=(int)Math.min(n,length-count);for(int i=0;i<k;i++)b[o+i]=value(base+count+i);count+=k;return k;}
        public void close(){closed=true;}
    }
    static class Connection extends HttpURLConnection {
        final Map<String,String> headers=new HashMap<>();InputStream input;int status=200;boolean disconnected;
        Connection(InputStream input)throws Exception{super(new URL("http://fixture.invalid/data"));this.input=input;}
        public void connect(){}public boolean usingProxy(){return false;}public void disconnect(){disconnected=true;try{input.close();}catch(Exception ignored){}}
        public int getResponseCode(){return status;}public String getHeaderField(String name){return headers.get(name);}
        public InputStream getInputStream(){return input;}
    }
    interface Factory {Connection make()throws Exception;}
    static Fetcher.Http client(Factory f){return new Fetcher.Http("http://fixture.invalid/page",null,null){
        @Override HttpURLConnection open(String url)throws IOException{try{return f.make();}catch(Exception e){throw new IOException(e);}}
    };}
    static void rejects(Callable<?> op,String label)throws Exception{try{op.call();throw new AssertionError("Accepted "+label);}catch(Fetcher.ResponseError good){}}
    static byte[] digest(InputStream in)throws Exception{MessageDigest md=MessageDigest.getInstance("SHA-256");byte[] b=new byte[65536];int n;try(InputStream s=in){while((n=s.read(b))!=-1)md.update(b,0,n);}return md.digest();}
    static void fileHash(File file,long length,long base)throws Exception{check(file.length()==length,"file length");check(Arrays.equals(digest(new FileInputStream(file)),digest(new Generated(length,base))),"file bytehash");}
    static Connection full(Generated in,boolean known)throws Exception{Connection c=new Connection(in);if(known)c.headers.put("Content-Length",Long.toString(in.length));return c;}
    public static void main(String[] args)throws Exception{
        Path temp=Files.createTempDirectory("helper-memory-");
        try{
            rejects(()->Fetcher.readAll(new Generated(LARGE,0),4L<<20),"oversize buffered input");
            Generated body=new Generated(LARGE,0);Connection c=full(body,true);
            byte[] range=client(()->c).fetch("http://fixture.invalid/data?signature=UNCHANGED",8L<<20,4L<<20);
            check(range.length==4<<20,"range length");for(int i=0;i<range.length;i++)check(range[i]==value((8L<<20)+i),"range bytes");
            check(body.count==12L<<20&&c.disconnected&&body.closed,"ignored Range was drained instead of stopped");range=null;
            System.out.println("PASS: real readAll rejects oversize; ignored Range reads/discards prefix with bounded memory and returns exact window");
            for(boolean known:new boolean[]{true,false}){
                Generated stream=new Generated(LARGE,0);Connection conn=full(stream,known);File out=temp.resolve("whole"+known).toFile();
                client(()->conn).download("http://fixture.invalid/data",-1,-1,out,null);fileHash(out,LARGE,0);out.delete();
                check(conn.disconnected&&stream.closed,"full response leaked");
                Generated arrayBody=new Generated(LARGE,0);Connection ac=full(arrayBody,known);
                rejects(()->client(()->ac).fetch("http://fixture.invalid/data",-1,-1),"large buffered body");
                check(known?arrayBody.count==0:arrayBody.count<=Fetcher.MAX_ARRAY_BYTES+(64<<10),"array budget read too much");
            }
            System.out.println("PASS: 64MiB known/chunked media files stream under 32MiB heap; oversized metadata rejected without body-sized allocation");
            for(String raw:new String[]{"bytes 1-10/100","bytes 0-10/100","bytes 0-4/100","bytes 0-9/9","bytes 0-9/999999999999999999999999","garbage"}){
                Connection bad=full(new Generated(10,0),true);bad.status=206;bad.headers.put("Content-Range",raw);
                rejects(()->client(()->bad).fetch("http://fixture.invalid/data",0,10),raw);check(bad.disconnected,"bad range leaked");
            }
            for(String length:new String[]{"-1","nonsense","999999999999999999999999"}){
                Connection bad=full(new Generated(10,0),false);bad.headers.put("Content-Length",length);
                rejects(()->client(()->bad).fetch("http://fixture.invalid/data",-1,-1),length);
            }
            Connection valid=full(new Generated(10,20),true);valid.status=206;valid.headers.put("Content-Range","bytes 20-29/100");
            check(client(()->valid).fetch("http://fixture.invalid/data",20,10).length==10,"valid 206 rejected");
            Connection extra=full(new Generated(11,0),false);extra.status=206;extra.headers.put("Content-Range","bytes 0-9/*");
            rejects(()->client(()->extra).fetch("http://fixture.invalid/data",0,10),"206 surplus");
            rejects(()->client(()->{throw new AssertionError("Overflow request reached network");}).download("http://fixture.invalid/data",Long.MAX_VALUE-1,10,temp.resolve("overflow").toFile(),null),"range overflow");
            Connection encoded=full(new Generated(10,0),true);encoded.headers.put("Content-Encoding","gzip");
            rejects(()->client(()->encoded).fetch("http://fixture.invalid/data",-1,-1),"encoded range representation");
            AtomicInteger attempts=new AtomicInteger();File truncated=temp.resolve("truncated").toFile();
            try {client(()->{attempts.incrementAndGet();Connection x=full(new Generated(9,0),true);x.headers.put("Content-Length","10");return x;}).download("http://fixture.invalid/data",-1,-1,truncated,null);throw new AssertionError("truncated accepted");}
            catch(EOFException good){check(attempts.get()==4&&!truncated.exists(),"retry/partial cleanup");}
            System.out.println("PASS: strict Content-Range/Length, valid 206, surplus/truncation rejection and restart-without-append cleanup");
            Generated cancellable=new Generated(LARGE,0);File cancelled=temp.resolve("cancelled").toFile();AtomicInteger requests=new AtomicInteger();
            try {client(()->{requests.incrementAndGet();return full(cancellable,true);}).download("http://fixture.invalid/data",-1,-1,cancelled,new HlsCore.Listener(){public void progress(int a,int b){}public boolean cancelled(){return cancellable.count>131072;}});throw new AssertionError("cancel ignored");}
            catch(IOException expected){check(!cancelled.exists()&&requests.get()==1,"cancel retried or kept partial");}
            check(cancellable.closed,"cancel reader open");
            System.out.println("PASS: cancellation closes reader, deletes partial and does not retry");
            ExecutorService pool=Executors.newFixedThreadPool(2);try{
                List<Future<?>> fs=new ArrayList<>();for(int i=0;i<2;i++){final int id=i;fs.add(pool.submit(()->{try{File o=temp.resolve("parallel"+id).toFile();client(()->full(new Generated(LARGE,id),false)).download("http://fixture.invalid/data",-1,-1,o,null);fileHash(o,LARGE,id);o.delete();return null;}catch(Exception e){throw new RuntimeException(e);}}));}for(Future<?> f:fs)f.get();
            }finally{pool.shutdownNow();}
            System.out.println("PASS: two parallel 64MiB streaming transfers under 32MiB heap");
            // Actual HlsCore and DashCore use stream override, including huge single-file DASH tracks.
            byte[] key=new byte[16],iv=new byte[16];Arrays.fill(key,(byte)3);File encrypted=temp.resolve("ciphertext").toFile();
            Cipher enc=Cipher.getInstance("AES/CBC/PKCS5Padding");enc.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new IvParameterSpec(iv));
            try(InputStream in=new Generated(LARGE,0);OutputStream out=new CipherOutputStream(new FileOutputStream(encrypted),enc)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
            Fetcher.Http media=new Fetcher.Http(null,null,null){@Override HttpURLConnection open(String url)throws IOException{
                try{String text=null;if(url.endsWith("plain.m3u8"))text="#EXTM3U\n#EXTINF:1,\ndata.bin\n#EXT-X-ENDLIST\n";
                    else if(url.endsWith("enc.m3u8"))text="#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\",IV=0x00000000000000000000000000000000\n#EXTINF:1,\nenc.bin\n#EXT-X-ENDLIST\n";
                    else if(url.endsWith("track.mpd"))text="<MPD type=\"static\" mediaPresentationDuration=\"PT1S\"><Period><AdaptationSet mimeType=\"video/mp4\"><Representation id=\"v\" bandwidth=\"1\"><BaseURL>data.bin</BaseURL></Representation></AdaptationSet></Period></MPD>";
                    if(text!=null){byte[] bytes=text.getBytes(java.nio.charset.StandardCharsets.UTF_8);Connection x=new Connection(new ByteArrayInputStream(bytes));x.headers.put("Content-Length",Integer.toString(bytes.length));return x;}
                    if(url.endsWith("key")){Connection x=new Connection(new ByteArrayInputStream(key));x.headers.put("Content-Length","16");return x;}
                    if(url.endsWith("enc.bin")){Connection x=new Connection(new FileInputStream(encrypted));x.headers.put("Content-Length",Long.toString(encrypted.length()));return x;}
                    return full(new Generated(LARGE,0),false);
                }catch(Exception e){throw new IOException(e);}}
            };
            for(String name:new String[]{"plain","enc"}){File dir=temp.resolve(name).toFile();HlsCore.Result result=new HlsCore(media,dir,2).run("http://fixture.invalid/"+name+".m3u8",null);fileHash(result.video,LARGE,0);check(Arrays.stream(dir.list()).noneMatch(s->s.endsWith(".encrypted")),"encrypted staging retained");}
            try(RandomAccessFile corrupt=new RandomAccessFile(encrypted,"rw")){corrupt.setLength(encrypted.length()-1);}
            File badDir=temp.resolve("bad-padding").toFile();
            try{new HlsCore(media,badDir,1).run("http://fixture.invalid/enc.m3u8",null);throw new AssertionError("Invalid AES block accepted");}
            catch(ExecutionException expected){check(expected.getCause() instanceof java.security.GeneralSecurityException,"Unexpected AES failure");}
            check(Arrays.stream(badDir.list()).noneMatch(n->n.endsWith(".encrypted")||n.startsWith("v_")),"Failed decrypt retained staging data");
            HlsCore.Result dash=new DashCore(media,temp.resolve("dash").toFile(),2).run("http://fixture.invalid/track.mpd",null);fileHash(dash.video,LARGE,0);
            System.out.println("PASS: actual HLS plain/AES-128 and single-file DASH stream 64MiB media with exact hashes under 32MiB heap");
        }finally{try(java.util.stream.Stream<Path> s=Files.walk(temp)){s.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(IOException e){throw new java.io.UncheckedIOException(e);}});}}
    }
}
