package android.os;
public interface IInterface { default IBinder asBinder() { throw new UnsupportedOperationException("No Binder IPC in host harness"); } }
