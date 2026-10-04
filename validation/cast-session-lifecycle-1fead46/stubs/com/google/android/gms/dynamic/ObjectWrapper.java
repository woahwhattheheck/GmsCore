package com.google.android.gms.dynamic;
public class ObjectWrapper implements IObjectWrapper {
 private final Object value;
 private ObjectWrapper(Object value) { this.value=value; }
 public static IObjectWrapper wrap(Object value) { return new ObjectWrapper(value); }
 public static <T> T unwrap(IObjectWrapper wrapper) { return wrapper == null ? null : (T)((ObjectWrapper)wrapper).value; }
}
