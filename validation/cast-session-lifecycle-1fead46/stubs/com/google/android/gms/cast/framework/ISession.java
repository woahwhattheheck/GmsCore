package com.google.android.gms.cast.framework;
import android.os.*;
import com.google.android.gms.dynamic.IObjectWrapper;
public interface ISession extends IInterface {
 IObjectWrapper getWrappedObject() throws RemoteException;
 String getCategory() throws RemoteException;
 String getSessionId() throws RemoteException;
 String getRouteId() throws RemoteException;
 boolean isConnected() throws RemoteException;
 boolean isConnecting() throws RemoteException;
 boolean isDisconnecting() throws RemoteException;
 boolean isDisconnected() throws RemoteException;
 boolean isResuming() throws RemoteException;
 boolean isSuspended() throws RemoteException;
 void notifySessionStarted(String sessionId) throws RemoteException;
 void notifyFailedToStartSession(int error) throws RemoteException;
 void notifySessionEnded(int error) throws RemoteException;
 void notifySessionResumed(boolean wasSuspended) throws RemoteException;
 void notifyFailedToResumeSession(int error) throws RemoteException;
 void notifySessionSuspended(int reason) throws RemoteException;
 int getSupportedVersion() throws RemoteException;
 int getSessionStartType() throws RemoteException;
 abstract class Stub implements ISession, IBinder { public IBinder asBinder() { return this; } }
}
