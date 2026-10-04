package com.google.android.gms.cast.framework;
import android.os.*;
import com.google.android.gms.dynamic.IObjectWrapper;
public interface ISessionManager extends IInterface {
 IObjectWrapper getWrappedCurrentSession() throws RemoteException;
 void addSessionManagerListener(ISessionManagerListener listener) throws RemoteException;
 void removeSessionManagerListener(ISessionManagerListener listener) throws RemoteException;
 void addCastStateListener(ICastStateListener listener) throws RemoteException;
 void removeCastStateListener(ICastStateListener listener) throws RemoteException;
 void endCurrentSession(boolean b, boolean stopCasting) throws RemoteException;
 IObjectWrapper getWrappedThis() throws RemoteException;
 int getCastState() throws RemoteException;
 void startSession(Bundle params) throws RemoteException;
 abstract class Stub implements ISessionManager, IBinder { public IBinder asBinder() { return this; } }
}
