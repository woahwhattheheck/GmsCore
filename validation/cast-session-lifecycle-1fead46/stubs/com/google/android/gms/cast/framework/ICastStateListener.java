package com.google.android.gms.cast.framework;
import android.os.*;
import com.google.android.gms.dynamic.IObjectWrapper;
public interface ICastStateListener extends IInterface {
 IObjectWrapper getWrappedThis() throws RemoteException;
 void onCastStateChanged(int state) throws RemoteException;
}
