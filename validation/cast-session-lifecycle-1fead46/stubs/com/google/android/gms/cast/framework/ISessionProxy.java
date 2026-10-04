package com.google.android.gms.cast.framework;
import android.os.*;
import com.google.android.gms.dynamic.IObjectWrapper;
public interface ISessionProxy extends IInterface {
 IObjectWrapper getWrappedSession() throws RemoteException;
 default void start(Bundle extras) throws RemoteException {}
 default void resume(Bundle extras) throws RemoteException {}
 default void end(boolean stopCasting) throws RemoteException {}
 default void onStarting(Bundle extras) throws RemoteException {}
 default void onResuming(Bundle extras) throws RemoteException {}
 default void onRouteInfoUpdated(Bundle extras) throws RemoteException {}
}
