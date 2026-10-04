/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.wearable.bluetooth;

import android.bluetooth.BluetoothAdapter;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBluetoothAdapter;
import org.robolectric.util.ReflectionHelpers;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 19)
public class BleScannerImplTest {
    private BluetoothAdapter adapter;

    @Before public void setUp() {
        ShadowBluetoothAdapter.setIsBluetoothSupported(true);
        adapter = BluetoothAdapter.getDefaultAdapter();
        assertNotNull(adapter);
        Shadows.shadowOf(adapter).setEnabled(true);
    }

    @Test public void legacyStopClearsScanningState() {
        BleScannerImpl scanner = new BleScannerImpl(adapter);
        ReflectionHelpers.setField(scanner, "scanning", true);

        scanner.stopScan();

        assertFalse("legacy stop must leave the scanner idle", scanner.isScanning());
    }
}
