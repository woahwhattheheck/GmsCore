/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.wearable.bluetooth;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.content.Context;
import android.os.HandlerThread;

import com.google.android.gms.wearable.ConnectionConfiguration;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class BleLifecycleTest {
    private static final long TIMEOUT_SECONDS = 2;

    @Test
    public void managerStartsInServiceStateAndProcessesInitialDisabledConfig() throws Exception {
        TestRig rig = new TestRig();
        try {
            assertTrue("startup state was not processed", rig.awaitState("ServiceOffState"));
            assertFalse(rig.manager.isReceiverRegistered.get());
            assertTrue(rig.gattHelper.listenerRegistered);
        } finally {
            rig.shutdown();
        }
    }

    @Test
    public void quitSafelyRunsBleCleanupBeforeStoppingHandlerThread() throws Exception {
        TestRig rig = new TestRig();
        try {
            assertTrue("startup state was not processed", rig.awaitState("ServiceOffState"));

            CountDownLatch resourcesPrepared = new CountDownLatch(1);
            rig.manager.post(() -> {
                rig.manager.config.set(configuration(true));
                rig.manager.syncReceiverRegistration();
                rig.scanner.scanning.set(true);
                rig.gattHelper.connected.set(true);
                resourcesPrepared.countDown();
            });
            assertTrue("BLE resources were not prepared", resourcesPrepared.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertTrue(rig.manager.isReceiverRegistered.get());

            rig.manager.quitSafely();

            assertTrue("service cleanup did not run", rig.servicesHandler.cleaned.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            rig.thread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));

            assertFalse("handler thread remained alive after safe quit", rig.thread.isAlive());
            assertEquals(1, rig.scanner.stopCalls.get());
            assertEquals(1, rig.gattHelper.disconnectCalls.get());
            assertEquals(1, rig.servicesHandler.cleanupCalls.get());
            assertFalse(rig.manager.isReceiverRegistered.get());
        } finally {
            rig.shutdown();
        }
    }

    private static boolean isState(BleConnectionManager manager, String expected) {
        BleState current = manager.currentState();
        return current != null && expected.equals(current.getName());
    }

    private static ConnectionConfiguration configuration(boolean enabled) {
        return new ConnectionConfiguration("watch", "00:11:22:33:44:55", 1, 1, enabled);
    }

    private static final class TestRig {
        final HandlerThread thread = new HandlerThread("BleLifecycleTest");
        final TestScanner scanner = new TestScanner();
        final TestGattHelper gattHelper = new TestGattHelper();
        final TestServicesHandler servicesHandler = new TestServicesHandler();
        final BleConnectionManager manager;

        TestRig() {
            thread.start();
            Context context = RuntimeEnvironment.getApplication();
            manager = new BleConnectionManager(context, null, scanner, gattHelper,
                    servicesHandler, thread.getLooper(), configuration(false));
        }

        boolean awaitState(String expected) throws InterruptedException {
            CountDownLatch stateProcessed = new CountDownLatch(1);
            AtomicBoolean stateReached = new AtomicBoolean();
            // Startup posts its configuration message behind the first marker.
            // The second observes the result without waiting for virtual time.
            manager.post(() -> manager.post(() -> {
                stateReached.set(isState(manager, expected));
                stateProcessed.countDown();
            }));
            return stateProcessed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS) && stateReached.get();
        }

        void shutdown() throws InterruptedException {
            manager.quitSafely();
            thread.quitSafely();
            thread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        }
    }

    private static final class TestScanner implements BleScanner {
        final AtomicBoolean scanning = new AtomicBoolean();
        final AtomicInteger stopCalls = new AtomicInteger();

        @Override
        public boolean isScanning() {
            return scanning.get();
        }

        @Override
        public void stopScan() {
            scanning.set(false);
            stopCalls.incrementAndGet();
        }

        @Override
        public void startScan(String address, ScanListener listener) {
            scanning.set(true);
        }
    }

    private static final class TestGattHelper implements BluetoothGattHelper {
        final AtomicBoolean connected = new AtomicBoolean();
        final AtomicInteger disconnectCalls = new AtomicInteger();
        volatile boolean listenerRegistered;

        @Override
        public boolean isConnected() {
            return connected.get();
        }

        @Override
        public void connect(BluetoothDevice device) {
        }

        @Override
        public void disconnect() {
            disconnectCalls.incrementAndGet();
            connected.set(false);
        }

        @Override
        public void discoverServices() {
        }

        @Override
        public void refreshGatt() {
        }

        @Override
        public void setGattEventListener(GattEventListener listener) {
            listenerRegistered = listener != null;
        }

        @Override
        public BluetoothGatt getGatt() {
            return null;
        }

        @Override
        public void close() {
        }
    }

    private static final class TestServicesHandler implements BleServicesHandler {
        final AtomicInteger cleanupCalls = new AtomicInteger();
        final CountDownLatch cleaned = new CountDownLatch(1);

        @Override
        public void cleanup() {
            cleanupCalls.incrementAndGet();
            cleaned.countDown();
        }

        @Override
        public void updateCurrentTime() {
            throw new UnsupportedOperationException("not used in this test");
        }
    }
}
