/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.wearable;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.wearable.ConnectionConfiguration;
import com.google.android.gms.wearable.MessageOptions;
import com.google.android.gms.wearable.internal.BooleanResponse;
import com.google.android.gms.wearable.internal.GetBackupSettingsSupportedResponse;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, shadows = LegacyConnectionCallbackTest.ConnectionState.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class LegacyConnectionCallbackTest {
    private WearableServiceImpl service;
    private ConnectionState connections;
    private RecordingCallbacks callbacks;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        WearableImpl wearable = new WearableImpl(context, null, null);
        wearable.networkHandler = new Handler(Looper.getMainLooper());
        connections = Shadow.extract(wearable);
        service = new WearableServiceImpl(context, wearable, context.getPackageName());
        callbacks = new RecordingCallbacks();
    }

    @Test
    public void enableWithoutAConfiguredWatchCompletesWithError() throws Exception {
        service.enableConnection(callbacks);
        finishAndAssertStatus(CommonStatusCodes.ERROR);
        assertTrue(connections.enabled.isEmpty());
        assertTrue(connections.disabled.isEmpty());
    }

    @Test
    public void disableWithoutAConfiguredWatchCompletesWithError() throws Exception {
        service.disableConnection(callbacks);
        finishAndAssertStatus(CommonStatusCodes.ERROR);
        assertTrue(connections.enabled.isEmpty());
        assertTrue(connections.disabled.isEmpty());
    }

    @Test
    public void enableStillUsesTheFirstConfigurationAndCompletesOnce() throws Exception {
        addConfigurations();
        service.enableConnection(callbacks);
        finishAndAssertStatus(CommonStatusCodes.SUCCESS);
        assertEquals(Collections.singletonList("first"), connections.enabled);
        assertTrue(connections.disabled.isEmpty());
    }

    @Test
    public void disableStillUsesTheFirstConfigurationAndCompletesOnce() throws Exception {
        addConfigurations();
        service.disableConnection(callbacks);
        finishAndAssertStatus(CommonStatusCodes.SUCCESS);
        assertEquals(Collections.singletonList("first"), connections.disabled);
        assertTrue(connections.enabled.isEmpty());
    }

    @Test
    public void backupEnabledWithoutRouteCompletesWithBooleanError() throws Exception {
        List<BooleanResponse> responses = new ArrayList<>();
        service.getBackupEnabled(new BaseWearableCallbacks() {
            @Override
            public void onBooleanResponse(BooleanResponse response) {
                responses.add(response);
            }

            @Override
            public void onGetBackupSettingsSupportedResponse(GetBackupSettingsSupportedResponse response) {
                throw new AssertionError("Backup-enabled failure used the backup-supported callback");
            }
        }, "missing-node");

        assertTrue("The callback remains asynchronous", responses.isEmpty());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(Collections.singletonList(new BooleanResponse(CommonStatusCodes.ERROR, false)), responses);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("The error completes exactly once", 1, responses.size());
    }

    private void addConfigurations() {
        connections.configurations = new ConnectionConfiguration[]{
                new ConnectionConfiguration("first", "00:00:00:00:00:01", 0, 0, false),
                new ConnectionConfiguration("second", "00:00:00:00:00:02", 0, 0, false)
        };
    }

    private void finishAndAssertStatus(int expectedStatus) {
        assertTrue("The service dispatches its result on the main handler", callbacks.statuses.isEmpty());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(Collections.singletonList(expectedStatus), callbacks.statuses);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("No second callback remains queued", 1, callbacks.statuses.size());
    }

    private static final class RecordingCallbacks extends BaseWearableCallbacks {
        final List<Integer> statuses = new ArrayList<>();

        @Override
        public void onStatus(Status status) {
            statuses.add(status.getStatusCode());
        }
    }

    /** Keep this callback test independent of radio, database, and channel startup. */
    @Implements(value = WearableImpl.class, isInAndroidSdk = false)
    public static class ConnectionState {
        ConnectionConfiguration[] configurations = new ConnectionConfiguration[0];
        final List<String> enabled = new ArrayList<>();
        final List<String> disabled = new ArrayList<>();

        @Implementation
        protected void __constructor__(Context context, NodeDatabaseHelper nodes,
                                       ConfigurationDatabaseHelper configurations) {
            // The real service and main-handler dispatch remain under test.
        }

        @Implementation
        protected ConnectionConfiguration[] getConfigurations() {
            return configurations;
        }

        @Implementation
        protected int sendRequest(String packageName, String targetNodeId, String path,
                                  byte[] data, MessageOptions options) {
            return -1;
        }

        @Implementation
        protected void enableConnection(String name) {
            enabled.add(name);
        }

        @Implementation
        protected void disableConnection(String name) {
            disabled.add(name);
        }
    }
}
