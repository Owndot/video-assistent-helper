package de.evp.helfer;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
public class TestRedirectCookies {
  public static void main(String[] args)throws Exception{
    AtomicReference<String> cookie=new AtomicReference<>(),range=new AtomicReference<>();
    HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    int port=server.getAddress().getPort();
    server.createContext("/cross",e->{e.getResponseHeaders().set("Location","http://localhost:"+port+"/target");e.sendResponseHeaders(302,-1);e.close();});
    server.createContext("/same",e->{e.getResponseHeaders().set("Location","/target");e.sendResponseHeaders(307,-1);e.close();});
    for(int status:new int[]{300,301,302,303,307,308}){
      final int code=status;
      server.createContext("/status"+code,e->{e.getResponseHeaders().set("Location","/target");e.sendResponseHeaders(code,-1);e.close();});
    }
    server.createContext("/loop",e->{e.getResponseHeaders().set("Location","/loop");e.sendResponseHeaders(302,-1);e.close();});
    server.createContext("/unsafe",e->{e.getResponseHeaders().set("Location","file:///tmp/not-http");e.sendResponseHeaders(302,-1);e.close();});
    server.createContext("/target",e->{cookie.set(e.getRequestHeaders().getFirst("Cookie"));range.set(e.getRequestHeaders().getFirst("Range"));byte[] b="ok".getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,b.length);e.getResponseBody().write(b);e.close();});
    server.start();
    try{
      Fetcher.Http net=new Fetcher.Http("http://127.0.0.1:"+port+"/page","UA","session=test-only");
      net.fetch("http://127.0.0.1:"+port+"/same",0,2);
      if(!"session=test-only".equals(cookie.get()))throw new AssertionError("Same-site authentication lost");
      if(!"bytes=0-1".equals(range.get()))throw new AssertionError("Range lost across redirect");
      net.fetch("http://127.0.0.1:"+port+"/cross",0,2);
      if(cookie.get()!=null&&!cookie.get().isEmpty())throw new AssertionError("Cookie crossed redirect site boundary");
      if(!"bytes=0-1".equals(range.get()))throw new AssertionError("Range lost across cross-site redirect");
      for(int status:new int[]{300,301,302,303,307,308}){
        byte[] body=net.fetch("http://127.0.0.1:"+port+"/status"+status,0,2);
        if(!"ok".equals(new String(body,StandardCharsets.UTF_8)))throw new AssertionError("HTTP "+status+" redirect failed");
        if(!"session=test-only".equals(cookie.get())||!"bytes=0-1".equals(range.get()))throw new AssertionError("Redirect lost authentication/Range: "+status);
      }
      try { net.response("http://127.0.0.1:"+port+"/loop",0,2);throw new AssertionError("Redirect loop accepted"); }
      catch(java.io.IOException expected){if(!expected.getMessage().contains("Too many redirects"))throw expected;}
      try { net.response("http://127.0.0.1:"+port+"/unsafe",0,2);throw new AssertionError("Non-HTTP redirect accepted"); }
      catch(java.io.IOException expected){if(!expected.getMessage().contains("Only HTTP(S)"))throw expected;}
      System.out.println("PASS: real HTTP redirect strips foreign cookie and preserves Range");
    }finally{server.stop(0);}
  }
}
