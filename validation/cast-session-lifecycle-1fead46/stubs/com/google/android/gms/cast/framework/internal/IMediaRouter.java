package com.google.android.gms.cast.framework.internal;
import android.os.*;
public interface IMediaRouter {
 boolean isRouteAvailable(Bundle selector,int flags) throws RemoteException;
 void selectRouteById(String id) throws RemoteException;
 void selectDefaultRoute() throws RemoteException;
 Bundle getRouteInfoExtrasById(String id) throws RemoteException;
 String getSelectedRouteId() throws RemoteException;
}
