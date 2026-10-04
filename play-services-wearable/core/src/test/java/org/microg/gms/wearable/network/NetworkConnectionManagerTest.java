/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.wearable.network;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.os.Looper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class NetworkConnectionManagerTest {
    @Test
    public void receiverRoutesConnectivityActionAndIgnoresExtraKeyAsAction() {
        Context context = RuntimeEnvironment.getApplication();
        AtomicInteger networkAvailableCalls = new AtomicInteger();
        BroadcastReceiver receiver = NetworkConnectionManager.registerConnectivityReceiver(
                context, networkAvailableCalls::incrementAndGet);

        try {
            context.sendBroadcast(new Intent(ConnectivityManager.EXTRA_NO_CONNECTIVITY)
                    .putExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, false));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("extra key is not a broadcast action", 0,
                    networkAvailableCalls.get());

            context.sendBroadcast(new Intent(ConnectivityManager.CONNECTIVITY_ACTION)
                    .putExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, true));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("loss of all connectivity must not start connections", 0,
                    networkAvailableCalls.get());

            context.sendBroadcast(new Intent(ConnectivityManager.CONNECTIVITY_ACTION)
                    .putExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, false));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("connectivity return must restart deferred/retrying configs", 1,
                    networkAvailableCalls.get());
        } finally {
            context.unregisterReceiver(receiver);
        }
    }
}
