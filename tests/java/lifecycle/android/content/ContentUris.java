package android.content;import android.net.Uri;public class ContentUris{public static Uri withAppendedId(Uri u,long id){return Uri.parse(u+"/"+id);}}
