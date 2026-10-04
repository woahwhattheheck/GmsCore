/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.cast;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.mediarouter.media.MediaItemStatus;

import org.json.JSONObject;
import org.junit.Test;

/**
 * Focused checks for the remote playback control surface of {@link CastMediaRouteController}:
 * the Cast media-namespace commands it generates and the status parsing that feeds
 * MediaRouter control-request results.
 */
public class RemotePlaybackControlTest {

    @Test
    public void pauseCommandCarriesMediaSession() throws Exception {
        JSONObject command = new JSONObject(CastMediaRouteController.buildMediaCommand("PAUSE", 5, 42, 0));
        assertEquals("PAUSE", command.getString("type"));
        assertEquals(5, command.getLong("requestId"));
        assertEquals(42, command.getLong("mediaSessionId"));
        assertFalse(command.has("currentTime"));
    }

    @Test
    public void resumeAndStopMapToPlayAndStop() throws Exception {
        assertEquals("PLAY", new JSONObject(CastMediaRouteController.buildMediaCommand("PLAY", 6, 42, 0)).getString("type"));
        assertEquals("STOP", new JSONObject(CastMediaRouteController.buildMediaCommand("STOP", 7, 42, 0)).getString("type"));
    }

    @Test
    public void seekCommandCarriesSecondsPosition() throws Exception {
        JSONObject command = new JSONObject(CastMediaRouteController.buildMediaCommand("SEEK", 8, 42, 12500));
        assertEquals("SEEK", command.getString("type"));
        assertEquals(8, command.getLong("requestId"));
        assertEquals(42, command.getLong("mediaSessionId"));
        assertEquals(12.5, command.getDouble("currentTime"), 0.0001);
    }

    @Test
    public void commandWithoutMediaSessionOmitsSessionId() throws Exception {
        JSONObject command = new JSONObject(CastMediaRouteController.buildMediaCommand("GET_STATUS", 9, 0, 0));
        assertEquals("GET_STATUS", command.getString("type"));
        assertFalse(command.has("mediaSessionId"));
    }

    @Test
    public void loadCommandWrapsMediaInfo() throws Exception {
        JSONObject command = new JSONObject(CastMediaRouteController
                .buildLoadCommand("https://example.com/movie.mp4", "video/mp4", 2500, 11));
        assertEquals("LOAD", command.getString("type"));
        assertEquals(11, command.getLong("requestId"));
        assertTrue(command.getBoolean("autoplay"));
        assertEquals(2.5, command.getDouble("currentTime"), 0.0001);
        JSONObject media = command.getJSONObject("media");
        assertEquals("https://example.com/movie.mp4", media.getString("contentId"));
        assertEquals("BUFFERED", media.getString("streamType"));
        assertEquals("video/mp4", media.getString("contentType"));
    }

    @Test
    public void loadCommandWithoutMimeOmitsContentType() throws Exception {
        JSONObject media = new JSONObject(CastMediaRouteController
                .buildLoadCommand("https://example.com/movie.mp4", null, 0, 12)).getJSONObject("media");
        assertFalse(media.has("contentType"));
    }

    @Test
    public void loadCommandEscapesContentId() throws Exception {
        String contentId = "https://example.com/\"quoted\"/a\\b.mp4";
        JSONObject media = new JSONObject(CastMediaRouteController
                .buildLoadCommand(contentId, null, 0, 13)).getJSONObject("media");
        assertEquals(contentId, media.getString("contentId"));
    }

    @Test
    public void playerStateMapping() {
        assertEquals(MediaItemStatus.PLAYBACK_STATE_PLAYING, CastMediaRouteController.toItemPlaybackState("PLAYING"));
        assertEquals(MediaItemStatus.PLAYBACK_STATE_PAUSED, CastMediaRouteController.toItemPlaybackState("PAUSED"));
        assertEquals(MediaItemStatus.PLAYBACK_STATE_BUFFERING, CastMediaRouteController.toItemPlaybackState("BUFFERING"));
        assertEquals(MediaItemStatus.PLAYBACK_STATE_FINISHED, CastMediaRouteController.toItemPlaybackState("IDLE"));
        assertEquals(MediaItemStatus.PLAYBACK_STATE_PENDING, CastMediaRouteController.toItemPlaybackState("UNKNOWN"));
        assertEquals(MediaItemStatus.PLAYBACK_STATE_PENDING, CastMediaRouteController.toItemPlaybackState(""));
    }

    @Test
    public void parseMediaStatusReadsSessionPositionDuration() throws Exception {
        JSONObject status = new JSONObject(
                "{\"mediaSessionId\":77,\"playerState\":\"PLAYING\",\"currentTime\":12.5,"
                        + "\"media\":{\"duration\":95.0}}");
        CastMediaRouteController.MediaStatusSnapshot snapshot = CastMediaRouteController.parseMediaStatus(status);
        assertEquals(77, snapshot.mediaSessionId);
        assertEquals(MediaItemStatus.PLAYBACK_STATE_PLAYING, snapshot.playbackState);
        assertEquals(12500, snapshot.positionMs);
        assertEquals(95000, snapshot.durationMs);
    }

    @Test
    public void parseMediaStatusWithoutMediaKeepsDurationUnknown() throws Exception {
        CastMediaRouteController.MediaStatusSnapshot snapshot = CastMediaRouteController
                .parseMediaStatus(new JSONObject("{\"mediaSessionId\":4,\"playerState\":\"PAUSED\"}"));
        assertEquals(4, snapshot.mediaSessionId);
        assertEquals(MediaItemStatus.PLAYBACK_STATE_PAUSED, snapshot.playbackState);
        assertEquals(0, snapshot.positionMs);
        assertEquals(-1, snapshot.durationMs);
    }
}
