package android.os;
public class Bundle extends java.util.HashMap<String,Object> {
 public String getString(String key) { return (String)get(key); }
 public void putString(String key, String value) { put(key,value); }
 public void putParcelable(String key, Object value) { put(key,value); }
 public <T> T getParcelable(String key) { return (T)get(key); }
}
