package de.evp.helfer;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
public final class TestTransferIntegrity {
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 public static void main(String[] args)throws Exception{
  Path dir=Files.createTempDirectory("transfer-integrity-");ExecutorService worker=Executors.newSingleThreadExecutor();
  try{
   Fetcher.Proxy px=new Fetcher.Proxy(dir.resolve("spool").toFile(),1000);File out=dir.resolve("file.part").toFile();Files.writeString(out.toPath(),"stale");
   Future<?> f=worker.submit(()->Fetcher.toFile(px,"http://fixture/file",out,null));
   Fetcher.Proxy.Req probe=TestProxyMemory.request(px);check(probe.sizeOnly,"Missing size probe");px.push(probe.id,206,8,new byte[0]);
   Fetcher.Proxy.Req range=TestProxyMemory.request(px);check(range.off==0&&range.len==8,"Expected bytes=0-7");
   try{px.pushStream(range.id,206,6,new ByteArrayInputStream("012345".getBytes()),6);}catch(IOException rejected){}
   try{f.get(2,TimeUnit.SECONDS);throw new AssertionError("Eight-byte transfer accepted "+out.length()+" bytes");}catch(ExecutionException expected){}
   check(!out.exists(),"Failed output retained");check(px.pull(1,1).isEmpty(),"Integrity error retried");px.close();
   for(long total:new long[]{8,-1,10}){
    Fetcher.Proxy current=new Fetcher.Proxy(dir.resolve("total"+total).toFile(),1000);
    Future<?> transfer=worker.submit(()->Fetcher.toFile(current,"http://fixture/file",out,null));
    Fetcher.Proxy.Req size=TestProxyMemory.request(current);current.push(size.id,206,8,new byte[0]);
    Fetcher.Proxy.Req window=TestProxyMemory.request(current);
    try{current.pushStream(window.id,206,total,new ByteArrayInputStream("01234567".getBytes()),8);}catch(IOException rejected){}
    if(total==10){try{transfer.get();throw new AssertionError("Grown total accepted");}catch(ExecutionException expected){}check(!out.exists(),"Grown total output retained");}
    else {transfer.get();check(out.length()==8,"Exact window with known/missing total failed");out.delete();}
    current.close();
   }
   for(int status:new int[]{200,206})for(boolean declared:new boolean[]{true,false})for(int length:new int[]{6,8,10}){
    Fetcher.Http http=new Fetcher.Http(null,null,null){
     @Override public long size(String u){return 8;}
     @Override java.net.HttpURLConnection open(String u)throws IOException{
      try{TestMemoryWindows.Connection c=TestMemoryWindows.full(new TestMemoryWindows.Generated(length,0),declared);c.status=status;if(status==206)c.headers.put("Content-Range","bytes 0-"+(length-1)+"/"+length);return c;}catch(Exception e){throw new IOException(e);}
     }
    };
    if(length==8){Fetcher.toFile(http,"http://fixture/file",out,null);check(out.length()==8,"Native valid transfer");out.delete();}
    else {try{Fetcher.toFile(http,"http://fixture/file",out,null);throw new AssertionError("Native changed total accepted");}catch(Fetcher.ResponseError expected){}check(!out.exists(),"Native partial retained");}
   }
   final long large=(4L<<20)+3;
   for(boolean change:new boolean[]{false,true}){
    Fetcher.Proxy current=new Fetcher.Proxy(dir.resolve("multi"+change).toFile(),1000);
    Future<?> transfer=worker.submit(()->Fetcher.toFile(current,"http://fixture/windows",out,null));
    Fetcher.Proxy.Req size=TestProxyMemory.request(current);current.push(size.id,206,large,new byte[0]);
    Fetcher.Proxy.Req first=TestProxyMemory.request(current);current.pushStream(first.id,206,large,new TestMemoryWindows.Generated(first.len,first.off),first.len);
    Fetcher.Proxy.Req last=TestProxyMemory.request(current);check(last.off==(4L<<20)&&last.len==3,"Expected final follow-up window");
    try{current.pushStream(last.id,206,change?large+1:large,new TestMemoryWindows.Generated(3,last.off),3);}catch(IOException rejected){}
    if(change){try{transfer.get();throw new AssertionError("Changed second-window total accepted");}catch(ExecutionException expected){}check(!out.exists(),"Written prefix retained");}
    else{transfer.get();TestMemoryWindows.fileHash(out,large,0);out.delete();}current.close();
   }
   Fetcher windows=new Fetcher(){public long size(String u){return large;}public byte[] fetch(String u,long off,long len)throws IOException{byte[] data=new byte[(int)len];new TestMemoryWindows.Generated(len,off).read(data);return data;}};
   Fetcher.toFile(windows,"http://fixture/windows",out,null);TestMemoryWindows.fileHash(out,large,0);out.delete();
   for(long known:new long[]{8,-1,0}){
    Fetcher data=new Fetcher(){public long size(String u){return known;}public byte[] fetch(String u,long off,long len){return known==0?new byte[0]:"01234567".getBytes();}};
    Fetcher.toFile(data,"http://fixture/file",out,null);check(out.length()==(known==0?0:8),"Valid file length");out.delete();
   }
   for(int length:new int[]{6,9}){
    Fetcher data=new Fetcher(){public long size(String u){return 8;}public byte[] fetch(String u,long off,long len){return new byte[length];}};
    try{Fetcher.toFile(data,"http://fixture/file",out,null);throw new AssertionError("Wrong window accepted");}catch(Fetcher.ResponseError expected){}
    check(!out.exists(),"Wrong window retained");
   }
   System.out.println("PASS: eight-versus-six transfer rejected, no retry/output; exact windows/final size, unknown and empty files preserved");
  }finally{worker.shutdownNow();try(var paths=Files.walk(dir)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
 }
}
