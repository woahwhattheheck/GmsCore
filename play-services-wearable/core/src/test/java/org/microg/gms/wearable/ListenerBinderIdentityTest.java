/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.wearable;

import android.content.IntentFilter;
import android.os.Binder;
import android.os.IBinder;

import com.google.android.gms.wearable.internal.IWearableListener;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, instrumentedPackages = "org.microg.gms.wearable")
public class ListenerBinderIdentityTest {
    private static final String PACKAGE = "test.listener.app";
    private WearableImpl wearable;

    @Before
    public void setUp() {
        // Instrumentation supplies a no-arg constructor without radio/channel startup.
        // The actual registration, removal, and dispatch methods remain under test.
        wearable = Shadow.newInstanceOf(WearableImpl.class);
        ReflectionHelpers.setField(wearable, "listeners", new ConcurrentHashMap<>());
    }

    @Test
    public void aDifferentProxyForTheSameBinderRemovesTheRegistration() {
        IBinder endpoint = new Binder();
        IWearableListener registered = IWearableListener.Stub.asInterface(endpoint);
        IWearableListener removal = IWearableListener.Stub.asInterface(endpoint);
        assertNotSame(registered, removal);
        assertSame(registered.asBinder(), removal.asBinder());

        register(registered);
        assertEquals(Collections.singletonList(registered), dispatch());

        wearable.removeListener(removal);

        assertTrue(dispatch().isEmpty());
    }

    @Test
    public void removingOneBinderPreservesTheOtherRegistration() {
        IBinder endpoint = new Binder();
        IWearableListener target = IWearableListener.Stub.asInterface(endpoint);
        IWearableListener other = IWearableListener.Stub.asInterface(new Binder());
        register(target);
        register(other);

        wearable.removeListener(IWearableListener.Stub.asInterface(endpoint));

        assertEquals(Collections.singletonList(other), dispatch());
    }

    @Test
    public void nullRemovalRemainsANoOp() {
        IWearableListener registered = IWearableListener.Stub.asInterface(new Binder());
        register(registered);

        wearable.removeListener(null);

        assertEquals(Collections.singletonList(registered), dispatch());
    }

    @Test
    public void theOriginalProxyCanRemoveAReRegisteredBinder() {
        IBinder endpoint = new Binder();
        IWearableListener original = IWearableListener.Stub.asInterface(endpoint);
        IWearableListener replacement = IWearableListener.Stub.asInterface(endpoint);
        register(original);
        register(replacement);
        assertEquals(Collections.singletonList(replacement), dispatch());

        wearable.removeListener(original);

        assertTrue(dispatch().isEmpty());
    }

    private void register(IWearableListener listener) {
        wearable.addListener(PACKAGE, listener, new IntentFilter[0]);
    }

    private List<IWearableListener> dispatch() {
        List<IWearableListener> delivered = new ArrayList<>();
        wearable.invokeListeners(null, delivered::add);
        return delivered;
    }
}
