package com.google.android.gms.cast.framework;
import android.os.*;
import com.google.android.gms.dynamic.IObjectWrapper;
public interface ISessionManagerListener extends IInterface {
 IObjectWrapper getWrappedThis() throws RemoteException;
 default void onSessionStarting(IObjectWrapper session) throws RemoteException {}
 default void onSessionStarted(IObjectWrapper session,String sessionId) throws RemoteException {}
 default void onSessionStartFailed(IObjectWrapper session,int error) throws RemoteException {}
 default void onSessionEnding(IObjectWrapper session) throws RemoteException {}
 default void onSessionEnded(IObjectWrapper session,int error) throws RemoteException {}
 default void onSessionResuming(IObjectWrapper session,String sessionId) throws RemoteException {}
 default void onSessionResumed(IObjectWrapper session,boolean wasSuspended) throws RemoteException {}
 default void onSessionResumeFailed(IObjectWrapper session,int error) throws RemoteException {}
 default void onSessionSuspended(IObjectWrapper session,int reason) throws RemoteException {}
}
