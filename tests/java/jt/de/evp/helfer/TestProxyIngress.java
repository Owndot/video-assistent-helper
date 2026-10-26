package de.evp.helfer;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Actual authenticated MiniServer /push -> Proxy -> file, no in-memory body at sender. */
public final class TestProxyIngress {
    static final long LARGE=64L<<20;
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    static String post(String base,int id,int status,long total,long length,long pattern)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(base+"/push?job=1&id="+id+"&st="+status+"&total="+total).openConnection();
        c.setRequestMethod("POST");c.setDoOutput(true);c.setConnectTimeout(3000);c.setReadTimeout(3000);
        c.setRequestProperty("X-EVP-Key","synthetic-proxy-key");c.setFixedLengthStreamingMode(length);
        try{
            try(OutputStream out=c.getOutputStream();InputStream in=new TestMemoryWindows.Generated(length,pattern)){
                byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
            }
            check(c.getResponseCode()==200,"Upload HTTP status");try(InputStream in=c.getInputStream()){return new String(in.readNBytes(1024),StandardCharsets.UTF_8);}
        }finally{c.disconnect();}
    }
    static String raw(int port,String headers)throws Exception{
        try(Socket s=new Socket("127.0.0.1",port)){
            s.setSoTimeout(3000);s.getOutputStream().write(("POST /push?job=1&id=999&st=200 HTTP/1.1\r\nHost: 127.0.0.1\r\n"+headers+"\r\n").getBytes(StandardCharsets.US_ASCII));
            return new BufferedReader(new InputStreamReader(s.getInputStream(),StandardCharsets.US_ASCII)).readLine();
        }
    }
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("proxy-ingress-");Fetcher.Proxy px=new Fetcher.Proxy(root.resolve("spool").toFile(),5000);
        Map<Integer,Fetcher.Proxy> proxies=new ConcurrentHashMap<>();proxies.put(1,px);Map<Integer,String> states=new ConcurrentHashMap<>();
        int port;try(ServerSocket unused=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){port=unused.getLocalPort();}
        MiniServer server=new MiniServer(port,"synthetic-proxy-key",new MiniServer.Handler(){
            public String get(String path,Map<String,String> p){return MiniServer.pull(proxies,states,p);}
            public String push(Map<String,String> p,byte[] body){return MiniServer.push(proxies,p,body);}
            public String pushStream(Map<String,String> p,InputStream in,long length)throws Exception{return MiniServer.pushStream(proxies,p,in,length);}
        });
        ExecutorService threads=Executors.newFixedThreadPool(4);check(server.start(),"MiniServer bind");String base="http://127.0.0.1:"+port;
        try{
            for(long total:new long[]{LARGE,-1}){
                File out=root.resolve("file"+total).toFile();Future<?> f=threads.submit(()->px.download("http://fixture/media",-1,-1,out,null));
                Fetcher.Proxy.Req r=TestProxyMemory.request(px);check(post(base,r.id,200,total,LARGE,0).contains("true"),"Stream upload rejected");
                f.get(5,TimeUnit.SECONDS);TestMemoryWindows.fileHash(out,LARGE,0);out.delete();TestProxyMemory.empty(root);
            }
            Future<byte[]> range=threads.submit(()->px.fetch("http://fixture/media",(8L<<20)+13,4L<<20));Fetcher.Proxy.Req r=TestProxyMemory.request(px);
            check(post(base,r.id,200,LARGE,LARGE,0).contains("true"),"Ignored Range upload");byte[] bytes=range.get();
            for(int i=0;i<bytes.length;i++)check(bytes[i]==TestMemoryWindows.value(((8L<<20)+13)+i),"Ignored Range bytes");bytes=null;
            Future<byte[]> valid=threads.submit(()->px.fetch("http://fixture/media",10,20));r=TestProxyMemory.request(px);check(post(base,r.id,206,100,20,10).contains("true"),"206 upload");check(valid.get().length==20,"206 length");
            List<Future<?>> consumers=new ArrayList<>();for(int i=0;i<2;i++){final int id=i;consumers.add(threads.submit(()->{File out=root.resolve("parallel"+id).toFile();px.download("http://fixture/"+id,-1,-1,out,null);TestMemoryWindows.fileHash(out,LARGE,id);out.delete();return null;}));}
            List<Fetcher.Proxy.Req> pending=new ArrayList<>();while(pending.size()<2)pending.addAll(px.pull(2,2000));List<Future<?>> producers=new ArrayList<>();
            for(Fetcher.Proxy.Req item:pending){int pattern=Integer.parseInt(item.url.substring(item.url.length()-1));producers.add(threads.submit(()->post(base,item.id,200,-1,LARGE,pattern)));}
            for(Future<?> f:producers)check(f.get().toString().contains("true"),"Parallel upload");for(Future<?> f:consumers)f.get();TestProxyMemory.empty(root);
            check(raw(port,"Content-Length: 67108864\r\n").contains("403"),"Unauthenticated body accepted");
            check(raw(port,"X-EVP-Key: synthetic-proxy-key\r\nTransfer-Encoding: chunked\r\nContent-Length: 4\r\n").contains("400"),"Conflicting push framing accepted");
            check(raw(port,"X-EVP-Key: synthetic-proxy-key\r\nContent-Length: 4\r\nContent-Length: 10\r\n").contains("400"),"Conflicting push lengths accepted");
            System.out.println("PASS: real authenticated /push handles known/unknown, ignored Range, valid206 and two parallel64MiB uploads with exact bytes at -Xmx32m; malformed framing and unauthenticated bodies rejected");
        }finally{px.close();server.stop();threads.shutdownNow();threads.awaitTermination(5,TimeUnit.SECONDS);try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
}
