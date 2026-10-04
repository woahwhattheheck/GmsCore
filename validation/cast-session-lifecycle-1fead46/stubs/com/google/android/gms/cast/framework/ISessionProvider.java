package com.google.android.gms.cast.framework;
import android.os.*;
import com.google.android.gms.dynamic.IObjectWrapper;
public interface ISessionProvider extends IInterface {
 IObjectWrapper getSession(String sessionId) throws RemoteException;
 boolean isSessionRecoverable() throws RemoteException;
}
