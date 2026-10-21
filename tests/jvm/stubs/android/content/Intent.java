package android.content; public class Intent {
 public static final String ACTION_MAIN="main",ACTION_VIEW="view",CATEGORY_HOME="home";
 public static final int FLAG_ACTIVITY_NEW_TASK=1,FLAG_ACTIVITY_CLEAR_TOP=2;
 public Intent(String s){} public Intent(String s,Object o){} public Intent addCategory(String s){return this;} public Intent setFlags(int n){return this;}
}