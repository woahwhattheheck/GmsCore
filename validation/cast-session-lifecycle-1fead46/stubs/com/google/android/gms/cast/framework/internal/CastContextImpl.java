package com.google.android.gms.cast.framework.internal;
import android.content.Context;
import android.os.Looper;
import androidx.mediarouter.media.MediaRouteSelector;
import com.google.android.gms.cast.framework.*;
import java.util.ArrayDeque;
import java.util.Queue;
// Host-only wiring. Real session algorithms are compiled separately, without modification.
public class CastContextImpl {
 public static final String CATEGORY="cast-category";
 public ISessionProvider defaultSessionProvider;
 public SessionManagerImpl manager;
 public IMediaRouter router;
 public final Context context=new Context();
 public final CastOptions options=new CastOptions();
 private final Queue<Runnable> mainQueue=new ArrayDeque<>();
 public String getDefaultCategory() { return CATEGORY; }
 public CastOptions getOptions() { return options; }
 public ISessionProvider getSessionProvider(String category) { return CATEGORY.equals(category) ? defaultSessionProvider : null; }
 public Context getContext() { return context; }
 public SessionManagerImpl getSessionManagerImpl() { return manager; }
 public IMediaRouter getRouter() { return router; }
 public MediaRouteSelector getMergedSelector() { return new MediaRouteSelector(); }
 void runOnMainThread(Runnable action) {
  if(Looper.myLooper()==Looper.getMainLooper()) action.run();
  else synchronized(mainQueue) { mainQueue.add(action); }
 }
 public int queuedActions() { synchronized(mainQueue) { return mainQueue.size(); } }
 public void drainMainQueue() {
  if(Looper.myLooper()!=Looper.getMainLooper()) throw new AssertionError("Not the main host thread");
  for(int count=0;count<100;count++) {
   Runnable action; synchronized(mainQueue) { action=mainQueue.poll(); }
   if(action==null) return;
   action.run();
  }
  throw new AssertionError("Unbounded main queue");
 }
}
