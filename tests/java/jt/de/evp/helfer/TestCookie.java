package de.evp.helfer;
public class TestCookie {
  public static void main(String[] args) {
    if(!PublicSuffix.ready())throw new AssertionError("Pinned PSL resource missing or corrupt");
    Fetcher.Http h=new Fetcher.Http("https://www.example.test/watch/1","UA","sess=test-only");
    String[][] cases={{"https://www.example.test/v","true"},{"https://cdn.example.test/v","true"},
      {"https://example.test/v","true"},{"https://bank.com/v","false"},
      {"https://example.test.evil.com/v","false"},{"https://notexample.test/v","false"}};
    for(String[] c:cases)check(h.cookieFor(c[0])==Boolean.parseBoolean(c[1]),c[0]);
    checkScope("www.bbc.co.uk","media.bbc.co.uk","other.co.uk");
    checkScope("alice.github.io","cdn.alice.github.io","bob.github.io");
    checkScope("alice.eu.org","cdn.alice.eu.org","bob.eu.org");
    checkScope("alice.blogspot.com","cdn.alice.blogspot.com","bob.blogspot.com");
    checkScope("x.y.ck","cdn.x.y.ck","z.y.ck");
    checkScope("www.ck","a.www.ck","other.ck");
    checkScope("city.kawasaki.jp","a.city.kawasaki.jp","other.kawasaki.jp");
    checkScope("xn--bcher-kva.de","www.xn--bcher-kva.de","other.de");
    Fetcher.Http suffix=new Fetcher.Http("https://github.io/","UA","s=test-only");
    check(!suffix.cookieFor("https://alice.github.io/v"),"public suffix cannot authorize tenant");
    Fetcher.Http unicode=new Fetcher.Http("https://faß.de/","UA","s=test-only");
    check(!unicode.cookieFor("https://fass.de/v"),"avoid IDNA2003 credential alias");
    long begin=System.nanoTime();
    for(int i=0;i<10000;i++)PublicSuffix.site("cdn.alice.github.io");
    System.out.println("MEASUREMENT PSL_10000_lookup_ms "+(System.nanoTime()-begin)/1000000.0);
    System.out.println("PASS: cookie site boundaries, wildcards, exceptions and packaged hash");
  }
  static void checkScope(String source,String own,String foreign){
    Fetcher.Http h=new Fetcher.Http("https://"+source+"/","UA","s=test-only");
    check(h.cookieFor("https://"+own+"/"),"own "+own);
    check(!h.cookieFor("https://"+foreign+"/"),"foreign "+foreign);
  }
  static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);}
}
