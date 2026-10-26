package de.evp.helfer;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
public final class TestJobIdentity {
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 static MiniServer start(int port,Map<Integer,Fetcher.Proxy> jobs){
  MiniServer server=new MiniServer(port,"synthetic-persistent-key",new MiniServer.Handler(){
   public String get(String p,Map<String,String> q){return "{}";}
   public String push(Map<String,String> q,byte[] data){return MiniServer.push(jobs,q,data);}
   public String pushStream(Map<String,String> q,InputStream in,long length)throws Exception{return MiniServer.pushStream(jobs,q,in,length);}
  });check(server.start(),"Restart bind failed");return server;
 }
 static String post(int port,Map<String,String> params,String bytes)throws Exception{
  java.net.HttpURLConnection c=(java.net.HttpURLConnection)new java.net.URL("http://127.0.0.1:"+port+"/push?job="+params.get("job")+"&id="+params.get("id")+"&st=200&total=3").openConnection();
  c.setRequestMethod("POST");c.setDoOutput(true);c.setFixedLengthStreamingMode(3);c.setConnectTimeout(3000);c.setReadTimeout(3000);c.setRequestProperty("X-EVP-Key","synthetic-persistent-key");
  try{try(OutputStream out=c.getOutputStream()){out.write(bytes.getBytes());}check(c.getResponseCode()==200,"Push status");try(InputStream in=c.getInputStream()){return new String(in.readNBytes(1024));}}finally{c.disconnect();}
 }
 public static void main(String[] args)throws Exception{
  if(args.length==2&&args[0].equals("--reserve")){System.out.println(JobIds.reserve(new File(args[1])));return;}
  boolean legacy=Arrays.asList(args).contains("--legacy"), http=Arrays.asList(args).contains("--http");
  Path root=Files.createTempDirectory("job-identity-");ExecutorService pool=Executors.newFixedThreadPool(4);
  MiniServer server=null;int port=0;
  try{
   File journal=root.resolve("ids").toFile();Map<Integer,Fetcher.Proxy> jobs=new ConcurrentHashMap<>();
   if(http){try(java.net.ServerSocket socket=new java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))){port=socket.getLocalPort();}server=start(port,jobs);}
   int oldJob=legacy?new java.util.concurrent.atomic.AtomicInteger(100).incrementAndGet():JobIds.reserve(journal);
   Fetcher.Proxy old=new Fetcher.Proxy(root.resolve("old").toFile());jobs.put(oldJob,old);
   Future<byte[]> waiting=pool.submit(()->old.fetch("http://fixture/old",-1,-1));Fetcher.Proxy.Req oldReq=TestProxyMemory.request(old);
   old.close();jobs.clear();try{waiting.get();throw new AssertionError("Old waiter survived");}catch(ExecutionException expected){}
   // New instances/maps, same pairing credential; old browser response is still outstanding.
   if(http){server.stop();server=start(port,jobs);}
   int newJob=legacy?new java.util.concurrent.atomic.AtomicInteger(100).incrementAndGet():JobIds.reserve(journal);
   Fetcher.Proxy fresh=new Fetcher.Proxy(root.resolve("new").toFile());jobs.put(newJob,fresh);
   Future<byte[]> result=pool.submit(()->fresh.fetch("http://fixture/new",-1,-1));Fetcher.Proxy.Req newReq=TestProxyMemory.request(fresh);
   check(newReq.id==oldReq.id,"Request counter reset scenario not reproduced");
   Map<String,String> stale=new HashMap<>(Map.of("job",Integer.toString(oldJob),"id",Integer.toString(oldReq.id),"st","200","total","3"));
   String accepted=http?post(port,stale,"OLD"):MiniServer.push(jobs,stale,"OLD".getBytes());
   if(accepted.contains("true")){byte[] wrong=result.get();throw new AssertionError("Stale upload accepted after process reset: "+new String(wrong));}
   check(!result.isDone(),"Old upload completed new request");
   stale.put("job",Integer.toString(newJob));check((http?post(port,stale,"NEW"):MiniServer.push(jobs,stale,"NEW".getBytes())).contains("true"),"New upload rejected");check(new String(result.get()).equals("NEW"),"Wrong current bytes");fresh.close();
   int reserved=JobIds.reserve(journal),afterRestart=JobIds.reserve(journal);check(afterRestart<reserved,"Reservation reused after unused/crashed job");
   Set<Integer> ids=ConcurrentHashMap.newKeySet();List<Future<?>> fs=new ArrayList<>();
   for(int t=0;t<4;t++)fs.add(pool.submit(()->{for(int i=0;i<16;i++)try{check(ids.add(JobIds.reserve(journal)),"Duplicate reservation");}catch(IOException e){throw new UncheckedIOException(e);}}));
   for(Future<?> f:fs)f.get();check(ids.size()==64,"Parallel IDs missing");
   try(RandomAccessFile f=new RandomAccessFile(journal,"rw")){f.seek(f.length());f.write(1);}
   try{JobIds.reserve(journal);throw new AssertionError("Torn journal reused");}catch(IOException expected){}
   File sync=root.resolve("sync").toFile();java.util.concurrent.atomic.AtomicBoolean attempted=new java.util.concurrent.atomic.AtomicBoolean();
   try{JobIds.reserve(sync,parent->{attempted.set(true);check(sync.length()==8,"Directory sync ran before record write");throw new IOException("synthetic fsync failure");});throw new AssertionError("Unsynced ID published");}catch(IOException expected){}
   check(attempted.get()&&JobIds.reserve(sync)==-3,"Failed reservation reused");
   File corrupt=root.resolve("corrupt").toFile();try(RandomAccessFile f=new RandomAccessFile(corrupt,"rw")){f.writeInt(-100);f.writeInt(~-100);}
   try{JobIds.reserve(corrupt);throw new AssertionError("Inconsistent journal reused");}catch(IOException expected){}
   check(JobIds.candidate((2147483646L)*8)==Integer.MIN_VALUE,"Last legal ID");
   try{JobIds.candidate((2147483647L)*8);throw new AssertionError("Overflow wrapped");}catch(IOException expected){}
   check(MiniServer.identity("4294967294")==-1,"Unsigned value aliased negative job");
   Fetcher.Proxy requests=new Fetcher.Proxy(root.resolve("overflow").toFile());
   var field=Fetcher.Proxy.class.getDeclaredField("ids");field.setAccessible(true);((java.util.concurrent.atomic.AtomicInteger)field.get(requests)).set(Integer.MAX_VALUE);
   try{requests.fetch("http://fixture/file",-1,-1);throw new AssertionError("Request ID wrapped");}catch(Fetcher.ResponseError expected){}requests.close();
   System.out.println("PASS: stale upload rejected after reset, current bytes exact, durable unused reservations,64 parallel IDs,torn journal/overflow fail closed");
  }finally{if(server!=null)server.stop();pool.shutdownNow();pool.awaitTermination(3,TimeUnit.SECONDS);try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
 }
}
