package de.evp.helfer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Actual Proxy/toFile and optional real MiniServer transport; legacy st=200,total=-1. */
public final class TestLegacyFullResponse {
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 static final class Transport implements AutoCloseable {
  final Map<Integer,Fetcher.Proxy> jobs=new ConcurrentHashMap<>();MiniServer server;int port;
  Transport(boolean http)throws Exception{
   if(http){try(ServerSocket free=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){port=free.getLocalPort();}
    server=new MiniServer(port,"synthetic-legacy-key",new MiniServer.Handler(){
     public String get(String p,Map<String,String> q){return "{}";}
     public String push(Map<String,String> q,byte[] data){return MiniServer.push(jobs,q,data);}
     public String pushStream(Map<String,String> q,InputStream in,long length)throws Exception{return MiniServer.pushStream(jobs,q,in,length);}
    });check(server.start(),"Transport bind");
   }
  }
  void send(int job,Fetcher.Proxy.Req r,int status,long total,InputStream data,long bytes)throws Exception{
   if(server==null){jobs.get(job).pushStream(r.id,status,total,data,bytes);return;}
   HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+port+"/push?job="+job+"&id="+r.id+"&st="+status+"&total="+total).openConnection();
   c.setRequestMethod("POST");c.setDoOutput(true);c.setFixedLengthStreamingMode(bytes);c.setConnectTimeout(3000);c.setReadTimeout(3000);c.setRequestProperty("X-EVP-Key","synthetic-legacy-key");
   try{try(OutputStream out=c.getOutputStream()){byte[] buffer=new byte[65536];long left=bytes;while(left>0){int n=data.read(buffer,0,(int)Math.min(left,buffer.length));if(n<0)throw new EOFException();out.write(buffer,0,n);left-=n;}}
    check(c.getResponseCode()==200,"Push HTTP status");try(InputStream in=c.getInputStream()){in.readNBytes(1024);}
   }finally{c.disconnect();}
  }
  public void close(){for(Fetcher.Proxy px:jobs.values())px.close();if(server!=null)server.stop();}
 }
 static void rejected(Future<?> f,File out,Fetcher.Proxy px)throws Exception{
  try{f.get(3,TimeUnit.SECONDS);try(InputStream prefix=new FileInputStream(out)){throw new AssertionError("Legacy HTTP200 accepted eight-byte prefix: "+new String(prefix.readNBytes(8),StandardCharsets.US_ASCII));}}
  catch(ExecutionException expected){check(expected.getCause() instanceof Fetcher.HttpError&&((Fetcher.HttpError)expected.getCause()).code==422,"Not a permanent integrity error: "+expected.getCause());}
  check(!out.exists(),"Invalid output retained");check(px.pull(1,1).isEmpty(),"Permanent integrity error retried");
 }
 public static void main(String[] args)throws Exception{
  Path dir=Files.createTempDirectory("legacy-full-");ExecutorService pool=Executors.newFixedThreadPool(2);
  try(Transport transport=new Transport(Arrays.asList(args).contains("--http"))){
   // First case is the exact independently reproduced counterexample.
   for(int variant=0;variant<5;variant++){
    final int kind=variant;boolean bad=kind==0;long expected=kind==3?-1:8;int status=kind==2?206:200;long total=kind==2||kind==4?8:-1;String body=kind==0||kind==3?"0123456789":"01234567";
    Fetcher.Proxy px=new Fetcher.Proxy(dir.resolve("v"+kind).toFile(),5000);transport.jobs.put(1,px);File out=dir.resolve("result").toFile();Files.writeString(out.toPath(),"stale");
    Future<?> f=pool.submit(()->Fetcher.toFile(px,"http://fixture/file",out,null));
    Fetcher.Proxy.Req probe=TestProxyMemory.request(px);px.push(probe.id,expected<0||kind==4?200:206,expected,new byte[0]);
    Fetcher.Proxy.Req range=TestProxyMemory.request(px);check(expected<0?range.off==-1:range.off==0&&range.len==8,"Unexpected requested window");
    try{transport.send(1,range,status,total,new ByteArrayInputStream(body.getBytes(StandardCharsets.US_ASCII)),body.length());}catch(IOException e){if(!bad)throw e;}
    if(bad)rejected(f,out,px);else{f.get(3,TimeUnit.SECONDS);check(Files.readString(out.toPath()).equals(body),"Valid legacy response changed");out.delete();}
    px.close();TestProxyMemory.empty(dir);
   }
   // Without a pinned file total, a requested window of a full 200 response remains valid.
   Fetcher.Proxy windows=new Fetcher.Proxy(dir.resolve("window").toFile(),5000);transport.jobs.put(1,windows);Future<byte[]> window=pool.submit(()->windows.fetch("http://fixture/window",3,4));Fetcher.Proxy.Req r=TestProxyMemory.request(windows);
   transport.send(1,r,200,-1,new ByteArrayInputStream("0123456789".getBytes()),10);check(new String(window.get()).equals("3456"),"Unpinned 200 window damaged");windows.close();
   // A valid ignored-Range body is a full representation, not merely the requested 4MiB slice.
   long size=(4L<<20)+3;
   for(boolean grow:new boolean[]{false,true}){
    Fetcher.Proxy px=new Fetcher.Proxy(dir.resolve("large"+grow).toFile(),5000);transport.jobs.put(1,px);File out=dir.resolve("large.part").toFile();Future<?> f=pool.submit(()->Fetcher.toFile(px,"http://fixture/large",out,null));
    r=TestProxyMemory.request(px);px.push(r.id,206,size,new byte[0]);r=TestProxyMemory.request(px);transport.send(1,r,200,-1,new TestMemoryWindows.Generated(size,0),size);
    r=TestProxyMemory.request(px);check(r.off==(4L<<20)&&r.len==3,"Final window request");
    try{transport.send(1,r,200,-1,new TestMemoryWindows.Generated(grow?size+1:size,0),grow?size+1:size);}catch(IOException e){if(!grow)throw e;}
    if(grow)rejected(f,out,px);else{f.get();TestMemoryWindows.fileHash(out,size,0);out.delete();}px.close();TestProxyMemory.empty(dir);
   }
   // Different legacy transfers keep independent expectations and cleanup.
   Fetcher.Proxy px=new Fetcher.Proxy(dir.resolve("parallel").toFile(),5000);transport.jobs.put(1,px);File bad=dir.resolve("bad.part").toFile(),good=dir.resolve("good.part").toFile();
   Future<?> failed=pool.submit(()->Fetcher.toFile(px,"http://fixture/bad",bad,null)),success=pool.submit(()->Fetcher.toFile(px,"http://fixture/good",good,null));
   int sent=0;while(sent<2){for(Fetcher.Proxy.Req req:px.pull(2,2000)){if(req.sizeOnly)px.push(req.id,206,8,new byte[0]);else{boolean invalid=req.url.endsWith("bad");String data=invalid?"0123456789":"01234567";try{transport.send(1,req,200,-1,new ByteArrayInputStream(data.getBytes()),data.length());}catch(IOException e){if(!invalid)throw e;}sent++;}}}
   rejected(failed,bad,px);success.get();check(Files.readString(good.toPath()).equals("01234567"),"Healthy parallel transfer damaged");good.delete();px.close();TestProxyMemory.empty(dir);
   System.out.println("PASS: legacy 200 total=-1 full-length mismatch rejected permanently and cleaned; valid 200/206/unknown/unpinned windows, ignored Range and parallel transfers preserved"+(transport.server!=null?" over real authenticated HTTP":""));
  }finally{pool.shutdownNow();pool.awaitTermination(3,TimeUnit.SECONDS);try(var paths=Files.walk(dir)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
 }
}
