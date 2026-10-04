/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.wearable;

import android.content.Context;
import android.os.Looper;

import com.google.android.gms.common.api.Status;
import com.google.android.gms.wearable.internal.ChannelReceiveFileResponse;
import com.google.android.gms.wearable.internal.ChannelSendFileResponse;
import com.google.android.gms.wearable.internal.CloseChannelResponse;
import com.google.android.gms.wearable.internal.GetChannelInputStreamResponse;
import com.google.android.gms.wearable.internal.GetChannelOutputStreamResponse;
import com.google.android.gms.wearable.internal.OpenChannelResponse;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.microg.gms.wearable.channel.ChannelStatusCodes;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, shadows = ChannelInitializationCallbackTest.UninitializedWearable.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class ChannelInitializationCallbackTest {
    private WearableServiceImpl service;
    private RecordingCallbacks callbacks;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        WearableImpl wearable = new WearableImpl(context, null, null);
        service = new WearableServiceImpl(context, wearable, context.getPackageName());
        callbacks = new RecordingCallbacks();
    }

    @Test
    public void openBeforeInitializationCompletesWithItsTypedError() throws Exception {
        service.openChannel(callbacks, "node", "/path");
        assertOnlyResponse("open");
    }

    @Test
    public void closeBeforeInitializationCompletesWithItsTypedError() throws Exception {
        service.closeChannelWithError(callbacks, "token", 0);
        assertOnlyResponse("close");
    }

    @Test
    public void inputStreamBeforeInitializationCompletesWithItsTypedError() throws Exception {
        service.getChannelInputStream(callbacks, null, "token");
        assertOnlyResponse("input");
    }

    @Test
    public void outputStreamBeforeInitializationCompletesWithItsTypedError() throws Exception {
        service.getChannelOutputStream(callbacks, null, "token");
        assertOnlyResponse("output");
    }

    @Test
    public void receiveFileBeforeInitializationCompletesWithItsTypedError() throws Exception {
        service.writeChannelInputToFd(callbacks, "token", null);
        assertOnlyResponse("receive");
    }

    @Test
    public void sendFileBeforeInitializationCompletesWithItsTypedError() throws Exception {
        service.readChannelOutputFromFd(callbacks, "token", null, 0, -1);
        assertOnlyResponse("send");
    }

    private void assertOnlyResponse(String expectedType) {
        assertEquals(Collections.singletonList(expectedType), callbacks.types);
        assertEquals(Collections.singletonList(ChannelStatusCodes.INTERNAL_ERROR), callbacks.statuses);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("No second completion is queued", 1, callbacks.types.size());
    }

    private static final class RecordingCallbacks extends BaseWearableCallbacks {
        final List<String> types = new ArrayList<>();
        final List<Integer> statuses = new ArrayList<>();

        private void record(String type, int status) {
            types.add(type);
            statuses.add(status);
        }

        @Override
        public void onOpenChannelResponse(OpenChannelResponse response) {
            record("open", response.statusCode);
        }

        @Override
        public void onCloseChannelResponse(CloseChannelResponse response) {
            record("close", response.status);
        }

        @Override
        public void onGetChannelInputStreamResponse(GetChannelInputStreamResponse response) {
            record("input", response.statusCode);
        }

        @Override
        public void onGetChannelOutputStreamResponse(GetChannelOutputStreamResponse response) {
            record("output", response.statusCode);
        }

        @Override
        public void onChannelReceiveFileResponse(ChannelReceiveFileResponse response) {
            record("receive", response.status);
        }

        @Override
        public void onChannelSendFileResponse(ChannelSendFileResponse response) {
            record("send", response.status);
        }

        @Override
        public void onStatus(Status status) {
            fail("A generic status cannot replace the channel API's typed response");
        }
    }

    @Implements(value = WearableImpl.class, isInAndroidSdk = false)
    public static class UninitializedWearable {
        @Implementation
        protected void __constructor__(Context context, NodeDatabaseHelper nodes,
                                       ConfigurationDatabaseHelper configurations) {
            // Keep the real channelManager field null, before radio/database initialization.
        }
    }
}
