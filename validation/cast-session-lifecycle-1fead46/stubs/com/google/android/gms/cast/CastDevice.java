package com.google.android.gms.cast;
public class CastDevice { public static CastDevice getFromBundle(android.os.Bundle extras) { return extras == null ? null : extras.getParcelable("device"); } }
