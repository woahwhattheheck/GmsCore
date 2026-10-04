/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.wearable.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothSocket;
import android.content.Context;

import com.google.android.gms.wearable.ConnectionConfiguration;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.microg.gms.wearable.ConnectHandshake;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowBluetoothAdapter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, shadows = BluetoothConnectionThreadShutdownTest.BlockingBluetoothSocketShadow.class)
public class BluetoothConnectionThreadShutdownTest {
    private static final String ADDRESS = "02:00:00:00:00:01";

    private ScheduledExecutorService executor;
    private BluetoothAdapter adapter;
    private TestConnectionThread thread;

    @Before public void setUp() {
        BlockingBluetoothSocketShadow.reset();
        ShadowBluetoothAdapter.setIsBluetoothSupported(true);
        adapter = BluetoothAdapter.getDefaultAdapter();
        assertNotNull(adapter);
        Shadows.shadowOf(adapter).setEnabled(true);
        executor = Executors.newSingleThreadScheduledExecutor();
    }

    @After public void tearDown() throws Exception {
        if (thread != null && thread.isAlive()) {
            thread.close();
            BluetoothSocket socket = thread.createdSocket.get();
            if (socket != null) socket.close();
            thread.join(TimeUnit.SECONDS.toMillis(2));
        }
        if (executor != null) executor.shutdownNow();
        BlockingBluetoothSocketShadow.releaseBlockedRead();
    }

    @Test public void closeCancelsAThreadBlockedInTheActiveSocketRead() throws Exception {
        thread = new TestConnectionThread(RuntimeEnvironment.getApplication(), adapter, executor, false);
        thread.start();

        assertTrue("handshake should reach the blocking socket read",
                BlockingBluetoothSocketShadow.readStarted.await(3, TimeUnit.SECONDS));
        BluetoothSocket activeSocket = thread.createdSocket.get();
        assertNotNull(activeSocket);

        thread.close();

        assertTrue("close should close the active socket", BlockingBluetoothSocketShadow.closed.await(
                2, TimeUnit.SECONDS));
        thread.join(TimeUnit.SECONDS.toMillis(2));
        assertFalse("connection thread should leave its blocking read", thread.isAlive());
        assertEquals("the active socket should be closed once", 1,
                BlockingBluetoothSocketShadow.closeCalls.get());
    }

    @Test public void socketReturnedAfterCloseIsClosedWithoutStartingAnAttempt() throws Exception {
        thread = new TestConnectionThread(RuntimeEnvironment.getApplication(), adapter, executor, true);
        thread.start();

        assertTrue("socket factory should be entered", thread.factoryEntered.await(2, TimeUnit.SECONDS));
        thread.close();
        thread.allowFactoryReturn.countDown();

        thread.join(TimeUnit.SECONDS.toMillis(2));
        assertFalse("closed thread should not begin a late attempt", thread.isAlive());
        assertNotNull(thread.createdSocket.get());
        assertEquals("late socket should be closed exactly once", 1,
                BlockingBluetoothSocketShadow.closeCalls.get());
        assertEquals("close must not trigger a retry", 1, thread.socketFactoryCalls.get());
    }

    private static final class TestConnectionThread extends BluetoothConnectionThread {
        final CountDownLatch factoryEntered = new CountDownLatch(1);
        final CountDownLatch allowFactoryReturn = new CountDownLatch(1);
        final AtomicReference<BluetoothSocket> createdSocket = new AtomicReference<>();
        final AtomicInteger socketFactoryCalls = new AtomicInteger();
        final boolean blockFactory;

        TestConnectionThread(Context context, BluetoothAdapter adapter,
                             ScheduledExecutorService executor, boolean blockFactory) {
            super(context, new ConnectionConfiguration("shutdown-test", ADDRESS, 1, 1, true),
                    adapter, null, executor, null);
            this.blockFactory = blockFactory;
        }

        @Override protected BluetoothSocket createSocket() throws IOException {
            socketFactoryCalls.incrementAndGet();
            factoryEntered.countDown();
            if (blockFactory) awaitUninterruptibly(allowFactoryReturn);
            BluetoothSocket socket = super.createSocket();
            createdSocket.set(socket);
            return socket;
        }

        @Override protected ConnectHandshake.LocalIdentity createLocalIdentity() {
            return new ConnectHandshake.LocalIdentity(
                    "local-node", "local", 1L, null, null, false, null);
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    @Implements(value = BluetoothSocket.class, isInAndroidSdk = true)
    public static class BlockingBluetoothSocketShadow {
        static volatile CountDownLatch readStarted = new CountDownLatch(1);
        static volatile CountDownLatch closed = new CountDownLatch(1);
        static final AtomicInteger closeCalls = new AtomicInteger();

        static void reset() {
            readStarted = new CountDownLatch(1);
            closed = new CountDownLatch(1);
            closeCalls.set(0);
        }

        static void releaseBlockedRead() {
            closed.countDown();
        }

        @Implementation protected void connect() {}

        @Implementation protected boolean isConnected() { return true; }

        @Implementation protected InputStream getInputStream() {
            return new InputStream() {
                @Override public int read() {
                    readStarted.countDown();
                    awaitUninterruptibly(closed);
                    return -1;
                }

                @Override public void close() {
                    // The simulated Bluetooth read exits only when the socket closes.
                }
            };
        }

        @Implementation protected OutputStream getOutputStream() {
            return new OutputStream() {
                @Override public void write(int value) {}
            };
        }

        @Implementation protected void close() {
            closeCalls.incrementAndGet();
            closed.countDown();
        }
    }
}
