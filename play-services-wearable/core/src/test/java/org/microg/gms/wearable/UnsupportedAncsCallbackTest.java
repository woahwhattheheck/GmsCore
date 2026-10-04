/* SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */
package org.microg.gms.wearable;

import android.content.Context;
import android.os.Looper;

import com.google.android.gms.common.api.Status;
import com.google.android.gms.wearable.internal.AncsNotificationParcelable;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
@LooperMode(LooperMode.Mode.PAUSED)
public class UnsupportedAncsCallbackTest {
    private WearableServiceImpl service;
    private RecordingCallbacks callbacks;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        service = new WearableServiceImpl(context, null, context.getPackageName());
        callbacks = new RecordingCallbacks();
    }

    @Test
    public void injectAncsNotificationCompletesOnceWithFeatureDisabled() throws Exception {
        service.injectAncsNotificationForTesting(callbacks, new AncsNotificationParcelable());
        assertFeatureDisabledOnce();
    }

    @Test
    public void doAncsPositiveActionCompletesOnceWithFeatureDisabled() throws Exception {
        service.doAncsPositiveAction(callbacks, 1);
        assertFeatureDisabledOnce();
    }

    @Test
    public void doAncsNegativeActionCompletesOnceWithFeatureDisabled() throws Exception {
        service.doAncsNegativeAction(callbacks, 1);
        assertFeatureDisabledOnce();
    }

    private void assertFeatureDisabledOnce() {
        assertTrue("The result is dispatched on the main handler", callbacks.statuses.isEmpty());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(Collections.singletonList(4014), callbacks.statuses);
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
}
