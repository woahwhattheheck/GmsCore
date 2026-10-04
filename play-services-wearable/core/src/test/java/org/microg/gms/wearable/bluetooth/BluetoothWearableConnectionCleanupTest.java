/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.wearable.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.HandlerThread;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.microg.gms.wearable.ConnectHandshake;
import org.microg.gms.wearable.WearableConnection;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowBluetoothAdapter;
import org.robolectric.util.ReflectionHelpers;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, shadows = BluetoothWearableConnectionCleanupTest.FaultingBluetoothSocketShadow.class)
public class BluetoothWearableConnectionCleanupTest {
    private static final String ADDRESS = "02:00:00:00:00:02";
    private static final UUID SERVICE_UUID = UUID.fromString("5e8945b0-9525-11e3-a5e2-0800200c9a66");

    @Before public void setUp() {
        FaultingBluetoothSocketShadow.reset();
        ShadowBluetoothAdapter.setIsBluetoothSupported(true);
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        assertNotNull(adapter);
        Shadows.shadowOf(adapter).setEnabled(true);
    }

    @Test public void ioMarkedClosedConnectionStillCleansResourcesOnce() throws Exception {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        BluetoothDevice device = adapter.getRemoteDevice(ADDRESS);
        BluetoothSocket socket = device.createRfcommSocketToServiceRecord(SERVICE_UUID);
        BluetoothWearableConnection connection = new BluetoothWearableConnection(socket,
                new ConnectHandshake.LocalIdentity("local-node", "local", 1L,
                        null, null, false, null), WearableConnection.NOOP);
        HandlerThread watchdog = ReflectionHelpers.getField(connection, "watchdogThread");

        try {
            assertThrows(IOException.class, connection::readMessagePiece);
            assertTrue("read failure marks the transport closed", connection.isClosed());
            assertEquals(0, FaultingBluetoothSocketShadow.inputCloseCalls.get());
            assertEquals(0, FaultingBluetoothSocketShadow.outputCloseCalls.get());
            assertEquals(0, FaultingBluetoothSocketShadow.socketCloseCalls.get());

            connection.close();
            connection.close();
            watchdog.join(2_000);

            assertEquals("input stream closes exactly once", 1,
                    FaultingBluetoothSocketShadow.inputCloseCalls.get());
            assertEquals("output stream closes exactly once", 1,
                    FaultingBluetoothSocketShadow.outputCloseCalls.get());
            assertEquals("socket closes exactly once", 1,
                    FaultingBluetoothSocketShadow.socketCloseCalls.get());
            assertFalse("watchdog thread terminates", watchdog.isAlive());
        } finally {
            connection.close();
        }
    }

    @Implements(value = BluetoothSocket.class, isInAndroidSdk = true)
    public static class FaultingBluetoothSocketShadow {
        static final AtomicInteger inputCloseCalls = new AtomicInteger();
        static final AtomicInteger outputCloseCalls = new AtomicInteger();
        static final AtomicInteger socketCloseCalls = new AtomicInteger();

        static void reset() {
            inputCloseCalls.set(0);
            outputCloseCalls.set(0);
            socketCloseCalls.set(0);
        }

        @Implementation protected InputStream getInputStream() {
            return new InputStream() {
                @Override public int read() throws IOException {
                    throw new IOException("bt socket closed by peer");
                }

                @Override public void close() {
                    inputCloseCalls.incrementAndGet();
                }
            };
        }

        @Implementation protected OutputStream getOutputStream() {
            return new OutputStream() {
                @Override public void write(int value) {}

                @Override public void close() {
                    outputCloseCalls.incrementAndGet();
                }
            };
        }

        @Implementation protected void close() {
            socketCloseCalls.incrementAndGet();
        }
    }
}
