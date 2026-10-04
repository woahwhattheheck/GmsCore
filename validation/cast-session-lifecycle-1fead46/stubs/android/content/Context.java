package android.content;
import java.util.HashMap;
import java.util.Map;
public class Context {
 public static final int MODE_PRIVATE=0;
 public int preferenceLookups, preferenceReads, preferenceWrites;
 private final Map<String,SharedPreferences> preferences = new HashMap<>();
 public Context getApplicationContext() { return this; }
 public SharedPreferences getSharedPreferences(String name,int mode) { preferenceLookups++; return preferences.computeIfAbsent(name,k -> new MemoryPreferences()); }
 private class MemoryPreferences implements SharedPreferences {
  private final Map<String,String> values = new HashMap<>();
  public synchronized String getString(String key,String fallback) { preferenceReads++; return values.getOrDefault(key,fallback); }
  public Editor edit() { return new Editor() {
   private final Map<String,String> changes = new HashMap<>();
   private boolean clear;
   public Editor putString(String key,String value) { changes.put(key,value); return this; }
   public Editor clear() { clear=true; return this; }
   public void apply() { synchronized(MemoryPreferences.this) { preferenceWrites++; if(clear) values.clear(); values.putAll(changes); } }
  }; }
 }
}
