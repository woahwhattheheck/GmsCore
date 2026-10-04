/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.cast;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import androidx.mediarouter.media.MediaControlIntent;
import androidx.mediarouter.media.MediaRouter;
import androidx.mediarouter.media.MediaSessionStatus;
import com.google.android.gms.cast.CastMediaControlIntent;
import java.lang.reflect.Field;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class RemotePlaybackSessionQueueTest {

    @Test
    public void queueLoadCommandStartsABufferedQueue() throws Exception {
        JSONObject command = new JSONObject(CastMediaRouteController.buildQueueLoadCommand(
                "https://example.com/movie.mp4", "video/mp4", 2500, 11));
        assertEquals("QUEUE_LOAD", command.getString("type"));
        assertEquals(11, command.getLong("requestId"));
        assertEquals(0, command.getInt("startIndex"));
        assertEquals("REPEAT_OFF", command.getString("repeatMode"));
        assertEquals(2.5, command.getDouble("currentTime"), 0.0001);
        JSONObject item = command.getJSONArray("items").getJSONObject(0);
        assertTrue(item.getBoolean("autoplay"));
        assertEquals("https://example.com/movie.mp4", item.getJSONObject("media").getString("contentId"));
        assertEquals("video/mp4", item.getJSONObject("media").getString("contentType"));
    }

    @Test
    public void queueInsertCommandAppendsWithoutReplacing() throws Exception {
        JSONObject command = new JSONObject(CastMediaRouteController.buildQueueInsertCommand(
                "https://example.com/next.mp4", "video/mp4", 42, 12));
        assertEquals("QUEUE_INSERT", command.getString("type"));
        assertEquals(42, command.getLong("mediaSessionId"));
        assertFalse(command.getJSONArray("items").getJSONObject(0).getBoolean("autoplay"));
    }

    @Test
    public void queueRemoveCommandCarriesItemIds() throws Exception {
        JSONObject command = new JSONObject(CastMediaRouteController.buildQueueRemoveCommand(42, 7, 13));
        assertEquals("QUEUE_REMOVE", command.getString("type"));
        assertEquals(42, command.getLong("mediaSessionId"));
        JSONArray ids = command.getJSONArray("itemIds");
        assertEquals(1, ids.length());
        assertEquals(7, ids.getInt(0));
    }

    @Test
    public void parseMediaStatusReadsQueueItemId() throws Exception {
        JSONObject status = new JSONObject(
                "{\"mediaSessionId\":77,\"playerState\":\"PLAYING\",\"currentTime\":1,"
                        + "\"currentItemId\":9,\"items\":[{\"itemId\":9}]}");
        CastMediaRouteController.MediaStatusSnapshot snapshot = CastMediaRouteController.parseMediaStatus(status);
        assertEquals(77, snapshot.mediaSessionId);
        assertEquals(Integer.valueOf(9), snapshot.queueItemId);
    }

    @Test
    public void sessionManagementActionsAllowNullCallback() {
        String[] actions = {
                MediaControlIntent.ACTION_START_SESSION,
                MediaControlIntent.ACTION_GET_SESSION_STATUS,
                MediaControlIntent.ACTION_END_SESSION,
                MediaControlIntent.ACTION_ENQUEUE,
                MediaControlIntent.ACTION_REMOVE
        };
        for (String action : actions) {
            CastMediaRouteController controller = new CastMediaRouteController(null, "route", "localhost", 8009, 0);
            Intent request = new Intent(action).addCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK);
            assertTrue(action, controller.onControlRequest(request, null));
        }
    }

    @Test
    public void startSessionCreatesActiveSessionStatus() throws Exception {
        CastMediaRouteController controller = new CastMediaRouteController(null, "route", "192.168.1.40", 8009, 0);
        field("sessionConnected").setBoolean(controller, true);
        RecordingCallback result = new RecordingCallback();
        Intent request = new Intent(MediaControlIntent.ACTION_START_SESSION)
                .addCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK)
                .putExtra(CastMediaControlIntent.EXTRA_CAST_APPLICATION_ID, "CC1AD845");
        assertTrue(controller.onControlRequest(request, result));
        assertEquals(1, result.successes);
        assertEquals(0, result.errors);
        String sessionId = result.result.getString(MediaControlIntent.EXTRA_SESSION_ID);
        assertFalse(sessionId == null || sessionId.isEmpty());
        MediaSessionStatus status = MediaSessionStatus.fromBundle(
                result.result.getBundle(MediaControlIntent.EXTRA_SESSION_STATUS));
        assertEquals(MediaSessionStatus.SESSION_STATE_ACTIVE, status.getSessionState());

        RecordingCallback lookup = new RecordingCallback();
        Intent getStatus = new Intent(MediaControlIntent.ACTION_GET_SESSION_STATUS)
                .addCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK)
                .putExtra(MediaControlIntent.EXTRA_SESSION_ID, sessionId);
        assertTrue(controller.onControlRequest(getStatus, lookup));
        assertEquals(1, lookup.successes);
        assertEquals(sessionId, lookup.result.getString(MediaControlIntent.EXTRA_SESSION_ID));
    }

    @Test
    public void endSessionReportsEndedAndForgetsTheSession() throws Exception {
        CastMediaRouteController controller = new CastMediaRouteController(null, "route", "192.168.1.40", 8009, 0);
        field("remotePlaybackSessionId").set(controller, "session-1");
        field("mediaSessionId").setLong(controller, 42);
        RecordingCallback result = new RecordingCallback();
        Intent request = new Intent(MediaControlIntent.ACTION_END_SESSION)
                .addCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK)
                .putExtra(MediaControlIntent.EXTRA_SESSION_ID, "session-1");
        assertTrue(controller.onControlRequest(request, result));
        assertEquals(1, result.successes);
        MediaSessionStatus status = MediaSessionStatus.fromBundle(
                result.result.getBundle(MediaControlIntent.EXTRA_SESSION_STATUS));
        assertEquals(MediaSessionStatus.SESSION_STATE_ENDED, status.getSessionState());
        assertEquals(0, field("mediaSessionId").getLong(controller));
        assertEquals(null, field("remotePlaybackSessionId").get(controller));
    }

    @Test
    public void unknownSessionIsRejected() {
        CastMediaRouteController controller = new CastMediaRouteController(null, "route", "localhost", 8009, 0);
        RecordingCallback result = new RecordingCallback();
        Intent request = new Intent(MediaControlIntent.ACTION_GET_SESSION_STATUS)
                .addCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK)
                .putExtra(MediaControlIntent.EXTRA_SESSION_ID, "missing");
        assertTrue(controller.onControlRequest(request, result));
        assertEquals(1, result.errors);
        assertEquals(0, result.successes);
    }

    @Test
    public void temporaryDisconnectExtraIsPublished() {
        Bundle extras = CastMediaRouteController.temporaryDisconnectExtras();
        assertEquals(CastMediaControlIntent.ERROR_CODE_TEMPORARILY_DISCONNECTED,
                extras.getInt(CastMediaControlIntent.EXTRA_ERROR_CODE));
    }

    private static class RecordingCallback extends MediaRouter.ControlRequestCallback {
        int successes;
        int errors;
        Bundle result;

        @Override
        public void onResult(Bundle data) {
            successes++;
            result = data;
        }

        @Override
        public void onError(String error, Bundle data) {
            errors++;
        }
    }

    private static Field field(String name) throws Exception {
        Field field = CastMediaRouteController.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
