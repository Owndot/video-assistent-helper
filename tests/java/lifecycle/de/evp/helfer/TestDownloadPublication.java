package de.evp.helfer;
import android.content.*;import android.database.Cursor;import android.net.Uri;import android.os.Build;import android.provider.MediaStore;
import java.io.*;import java.nio.file.*;import java.util.*;import java.util.concurrent.*;import java.util.function.*;

/** Real Downloads class with explicit host Android/provider models, never an Android lifecycle claim. */
public class TestDownloadPublication {
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 static class Fake extends Context {
  final Path root;final Store store;Fake(Path p)throws Exception{root=p;Files.createDirectories(p);store=new Store(p.resolve("provider"));}
  public Object getSystemService(String s){return s.equals(NOTIFICATION_SERVICE)?new android.app.NotificationManager():new android.app.DownloadManager();}
  File dir(String s){File f=root.resolve(s).toFile();f.mkdirs();return f;}public File getFilesDir(){return dir("files");}public File getCacheDir(){return dir("cache");}public File getExternalFilesDir(String s){return dir("external");}public ContentResolver getContentResolver(){return store;}
 }
 static class Store extends ContentResolver {
  final Path root;volatile boolean failWrite,failClose,nullOutput,zeroUpdate,failQuery,nullQuery,failDelete;volatile Consumer<Uri> writing,updating,updated;int deletes,updates;
  Store(Path p)throws IOException{root=p;Files.createDirectories(p);}
  Path meta(Uri u){return root.resolve(u.toString().split("\\?")[0].substring(u.toString().split("\\?")[0].lastIndexOf('/')+1)+".meta");}
  Path data(Uri u){return Path.of(meta(u).toString().replace(".meta",".data"));}
  Properties props(Uri u){try{Properties p=new Properties();try(InputStream in=Files.newInputStream(meta(u))){p.load(in);}return p;}catch(IOException e){throw new UncheckedIOException(e);}}
  void write(Uri u,Properties p){try(OutputStream out=Files.newOutputStream(meta(u))){p.store(out,"synthetic provider model");}catch(IOException e){throw new UncheckedIOException(e);}}
  long nextId(){return rows().stream().mapToLong(u->Long.parseLong(u.toString().split("\\?")[0].substring(u.toString().split("\\?")[0].lastIndexOf('/')+1))).max().orElse(0)+1;}
  public synchronized Uri insert(Uri collection,ContentValues v){Uri u=Uri.parse(collection+"/"+nextId());Properties p=new Properties();v.forEach((k,x)->p.setProperty(k,String.valueOf(x)));p.setProperty("owner_package_name","de.evp.helfer");write(u,p);return u;}
  public OutputStream openOutputStream(Uri u)throws IOException{if(nullOutput)return null;return new FilterOutputStream(Files.newOutputStream(data(u))){public void write(byte[] b,int o,int n)throws IOException{if(failWrite){out.write(b,o,Math.min(3,n));throw new IOException("synthetic disk full");}out.write(b,o,n);Consumer<Uri> cb=writing;if(cb!=null)cb.accept(u);}public void close()throws IOException{super.close();if(failClose)throw new IOException("synthetic close failure");}};}
  public int update(Uri u,ContentValues v,String q,String[] a){Consumer<Uri> cb=updating;if(cb!=null)cb.accept(u);updates++;if(zeroUpdate)return 0;Properties p=props(u);v.forEach((k,x)->p.setProperty(k,String.valueOf(x)));write(u,p);cb=updated;if(cb!=null)cb.accept(u);return 1;}
  public synchronized int delete(Uri u,String q,String[] a){if(failDelete)return 0;if(q!=null&&!matches(props(u),q,a))return 0;deletes++;try{boolean had=Files.deleteIfExists(meta(u));Files.deleteIfExists(data(u));return had?1:0;}catch(IOException e){throw new UncheckedIOException(e);}}
  List<Uri> rows(){try(var s=Files.list(root)){return s.filter(p->p.toString().endsWith(".meta")).map(p->Uri.parse("content://downloads/"+p.getFileName().toString().replace(".meta",""))).toList();}catch(IOException e){throw new UncheckedIOException(e);}}
  boolean matches(Properties p,String where,String[] args){
   boolean scoped=args[0].equals(p.getProperty("owner_package_name"))&&"1".equals(p.getProperty("is_pending"))&&Arrays.asList(args).subList(1,3).contains(p.getProperty("relative_path"));
   if(args.length>3)scoped&=args[3].equals(p.getProperty(MediaStore.Downloads.DISPLAY_NAME))&&p.getProperty("download_uri","").isEmpty();
   return scoped;
  }
  public Cursor query(Uri u,String[] cols,String where,String[] args,String order){
   if(failQuery)throw new IllegalStateException("synthetic provider query failure");if(nullQuery)return null;
   check(where.contains("owner_package_name")&&where.contains("is_pending")&&where.contains("relative_path"),"unsafe recovery selection");
   // Android Q collection queries hide pending rows unless setIncludePending was requested.
   boolean include=u.toString().contains("includePending=1");
   List<Uri> selected=rows().stream().filter(x->{Properties p=props(x);return (include||!"1".equals(p.getProperty("is_pending")))&&matches(p,where,args);}).toList();
   return new Cursor(){int i=-1;public boolean moveToFirst(){i=0;return i<selected.size();}public boolean moveToNext(){return ++i<selected.size();}
    public int getColumnIndexOrThrow(String n){for(int k=0;k<cols.length;k++)if(cols[k].equals(n))return k;throw new IllegalArgumentException(n);}
    public long getLong(int c){check(cols[c].equals("_id"),"wrong numeric column");return Long.parseLong(selected.get(i).toString().substring(selected.get(i).toString().lastIndexOf('/')+1));}
    public String getString(int c){return props(selected.get(i)).getProperty(cols[c]);}public int getInt(int c){return Integer.parseInt(getString(c));}public void close(){}};
  }
 }
 static void cancel(int id)throws Exception{try{Downloads.class.getDeclaredMethod("cancelJob",int.class).invoke(null,id);}catch(NoSuchMethodException old){Downloads.class.getDeclaredField("cancel").getType();@SuppressWarnings("unchecked")Map<Integer,Boolean> flags=(Map<Integer,Boolean>)Downloads.class.getDeclaredField("cancel").get(null);flags.put(id,true);}}
 static void job(Fake c,int id,String text)throws Exception{
  Fetcher net=new Fetcher(){byte[] data=text.getBytes();public long size(String u){return data.length;}public byte[] fetch(String u,long o,long n){return data;}};
  var m=Downloads.class.getDeclaredMethod("streamJob",Context.class,int.class,String.class,String.class,String.class,Fetcher.class);m.setAccessible(true);m.invoke(null,c,id,"http://synthetic/file.mp4",text,"file",net);
 }
 static void treeEmpty(File f)throws IOException{if(!f.exists())return;try(var paths=Files.walk(f.toPath())){check(paths.noneMatch(Files::isRegularFile),"private files leaked: "+f);}}
 public static void main(String[]args)throws Exception{
  Path root=Files.createTempDirectory("publication-");Fake c=new Fake(root);File source=root.resolve("source").toFile();Files.writeString(source.toPath(),"01234567");
  try{
   if(Arrays.asList(args).contains("--cancel")){
    c.store.writing=u->{try{cancel(-600);}catch(Exception e){throw new RuntimeException(e);}};job(c,-600,"CANCELME");
    check("abgebrochen".equals(Downloads.state.get(-600)),"Cancelled publication reported success: "+Downloads.state.get(-600));check(c.store.rows().isEmpty(),"cancelled publication retained provider row");treeEmpty(c.getCacheDir());
    System.out.println("PASS: actual job cancellation during publication never reports success or retains files");return;
   }
   if(Arrays.asList(args).contains("--legacy")){
    Build.VERSION.SDK_INT=26;File old=new File(c.getExternalFilesDir("Download"),"Video-Assistent/file.mp4");old.getParentFile().mkdirs();Files.writeString(old.toPath(),"KEEP");
    Downloads.save(c,source,"file.mp4","video/mp4");check(Files.readString(old.toPath()).equals("KEEP"),"existing legacy target overwritten");
    System.out.println("PASS: existing API26 target preserved");return;
   }
   for(String failure:new String[]{"write","close","null","update"}){
    c.store.failWrite=failure.equals("write");c.store.failClose=failure.equals("close");c.store.nullOutput=failure.equals("null");c.store.zeroUpdate=failure.equals("update");
    try{Downloads.save(c,source,"file.mp4","video/mp4");throw new AssertionError("Failed publication reported success: "+failure);}catch(Exception expected){}
    check(c.store.rows().isEmpty(),"Failed save retained pending row: "+failure);
   }
   c.store.failWrite=c.store.failClose=c.store.nullOutput=c.store.zeroUpdate=false;
   Uri good=Downloads.save(c,source,"file.mp4","video/mp4");check("0".equals(c.store.props(good).getProperty("is_pending")),"not finalized");check(Files.readString(c.store.data(good)).equals("01234567"),"wrong saved bytes");
   Build.VERSION.SDK_INT=26;File existing=new File(c.getExternalFilesDir("Download"),"Video-Assistent/file.mp4");existing.getParentFile().mkdirs();Files.writeString(existing.toPath(),"KEEP");
   Thread.currentThread().interrupt();try{Downloads.save(c,source,"file.mp4","video/mp4");throw new AssertionError("Interrupted legacy save published success");}catch(Exception expected){}finally{Thread.interrupted();}
   check(Files.readString(existing.toPath()).equals("KEEP"),"existing legacy target damaged");
   Uri second=Downloads.save(c,source,"file.mp4","video/mp4");check(Files.readString(existing.toPath()).equals("KEEP"),"legacy target overwritten");check(Files.readString(Path.of(java.net.URI.create(second.toString()))).equals("01234567"),"new legacy bytes");
   Build.VERSION.SDK_INT=29;c.store.writing=null;
   job(c,-601,"GOODJOB");check("fertig".equals(Downloads.state.get(-601)),"valid job failed");cancel(-601);check("fertig".equals(Downloads.state.get(-601)),"late cancel overwrote completed state");treeEmpty(c.getCacheDir());
   System.out.println("PASS: actual Downloads save rollback/write/close/null/update errors, legacy cancellation/collisions, exact bytes and committed status");
  }finally{Downloads.class.getDeclaredField("jobs").setAccessible(true);for(String n:new String[]{"jobs","credentialedFiles"}){var f=Downloads.class.getDeclaredField(n);f.setAccessible(true);((ExecutorService)f.get(null)).shutdownNow();}Build.VERSION.SDK_INT=29;}
 }
}
