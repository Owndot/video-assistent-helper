package de.evp.helfer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/** Standalone disposable-APK Android runtime probe, not compiled into the application. */
public class NetworkCookieProbe {
  public static void main(String[] args)throws Exception {
    boolean tenant=new Fetcher.Http("https://alice.github.io/","UA","native-test-only").cookieFor("https://bob.github.io/v");
    boolean ready=false;
    try{ready=(Boolean)Class.forName("de.evp.helfer.PublicSuffix").getDeclaredMethod("ready").invoke(null);}
    catch(ClassNotFoundException oldClient){}
    AtomicReference<String> cookie=new AtomicReference<>(),range=new AtomicReference<>();
    ServerSocket server=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));
    int port=server.getLocalPort();
    Thread worker=new Thread(()->{
      try {
        for(int i=0;i<4;i++)try(Socket socket=server.accept()){
          socket.setSoTimeout(5000);
          BufferedReader in=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.ISO_8859_1));
          String first=in.readLine(),path=first.split(" ")[1],line,c=null,r=null;
          while((line=in.readLine())!=null&&!line.isEmpty()){
            if(line.toLowerCase().startsWith("cookie:"))c=line.substring(7).trim();
            if(line.toLowerCase().startsWith("range:"))r=line.substring(6).trim();
          }
          String response;
          if(path.equals("/same")||path.equals("/cross")){
            String location=path.equals("/same")?"/target":"http://localhost:"+port+"/target";
            response="HTTP/1.1 302 Found\r\nLocation: "+location+"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
          }else{
            cookie.set(c);range.set(r);
            response="HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok";
          }
          socket.getOutputStream().write(response.getBytes(StandardCharsets.ISO_8859_1));
        }
      }catch(IOException e){if(!server.isClosed())throw new RuntimeException(e);}
    },"native-cookie-probe");
    worker.setDaemon(true);worker.start();
    boolean same,cross,ranged;
    try{
      Fetcher.Http net=new Fetcher.Http("http://127.0.0.1:"+port+"/page","UA","native-test-only");
      net.fetch("http://127.0.0.1:"+port+"/same",0,2);
      same="native-test-only".equals(cookie.get());ranged="bytes=0-1".equals(range.get());
      net.fetch("http://127.0.0.1:"+port+"/cross",0,2);
      cross=cookie.get()!=null&&!cookie.get().isEmpty();ranged&="bytes=0-1".equals(range.get());
    }finally{server.close();worker.join(1000);}
    System.out.println("{\"tenantCookieAllowed\":"+tenant+",\"crossCookiePresent\":"+cross+
      ",\"sameCookiePreserved\":"+same+",\"rangePreserved\":"+ranged+",\"pslReady\":"+ready+"}");
  }
}
