package android.util;
public class Log {
 public static int i(String t,String m){return 0;}
 public static int d(String t,String m){return 0;}
 public static int w(String t,String m){System.err.println(m);return 0;}
 public static int w(String t,String m,Throwable e){System.err.println(m+": "+e);return 0;}
 public static int e(String t,String m,Throwable e){System.err.println(m+": "+e);return 0;}
 public static int e(String t,String m){System.err.println(m);return 0;}
}