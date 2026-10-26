package de.evp.helfer;
import java.io.InputStream;
import java.util.Arrays;
public class TestMemoryBaseline {
 public static void main(String[] args) throws Exception {
  InputStream source=new InputStream(){long remaining=64L<<20;
   public int read(){if(remaining--<=0)return -1;return 0;}
   public int read(byte[] b,int o,int n){if(remaining<=0)return -1;int k=(int)Math.min(n,remaining);Arrays.fill(b,o,o+k,(byte)0);remaining-=k;return k;}};
  try { Fetcher.readAll(source,4L<<20); throw new AssertionError("Expected limited-heap failure not reproduced"); }
  catch(OutOfMemoryError expected){System.out.println("REPRODUCED: actual Fetcher.readAll buffers 64MiB input despite 4MiB hint; exceeds 32MiB heap");}
 }
}
