package android.app;
import android.content.*;import android.os.IBinder;import java.io.File;
public class Service extends Context {
 public static final int START_STICKY=1,START_NOT_STICKY=2;public boolean destroyed;
 public void onCreate(){} public int onStartCommand(Intent i,int f,int id){return 0;}public IBinder onBind(Intent i){return null;}public void onDestroy(){destroyed=true;}public void stopSelf(){}public void startForeground(int id,Notification n){}public void startForeground(int id,Notification n,int t){}
 public Object getSystemService(String n){throw new UnsupportedOperationException("not exercised");}public File getFilesDir(){throw new UnsupportedOperationException();}public File getCacheDir(){throw new UnsupportedOperationException();}public File getExternalFilesDir(String n){throw new UnsupportedOperationException();}public ContentResolver getContentResolver(){throw new UnsupportedOperationException();}
}
