/*
 * Copyright (C) 2013-2017 microG Project Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.microg.gms.cast;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import androidx.annotation.NonNull;
import androidx.mediarouter.media.MediaControlIntent;
import androidx.mediarouter.media.MediaItemStatus;
import androidx.mediarouter.media.MediaRouteProvider;
import androidx.mediarouter.media.MediaRouter;

import org.microg.gms.cast.channel.CastChannel;
import org.microg.gms.cast.channel.CastDeviceSession;
import org.microg.gms.cast.channel.ReceiverApplication;
import org.microg.gms.cast.channel.ReceiverStatus;

public class CastMediaRouteController extends MediaRouteProvider.RouteController {
    private static final String TAG = CastMediaRouteController.class.getSimpleName();

    private final CastMediaRouteProvider provider;
    private final String routeId;
    private final String host;
    private final int port;

    // Status replies to an older SET_VOLUME arrive after newer local changes; ignore them for this long.
    private static final long LOCAL_VOLUME_HOLD_MS = 1000;

    // App id of the Default Media Receiver, which implements the media namespace.
    private static final String DEFAULT_MEDIA_RECEIVER_APP_ID = "CC1AD845";
    private static final String MEDIA_NAMESPACE = "urn:x-cast:com.google.cast.media";

    private volatile int volume;
    private volatile boolean volumeKnown;

    // Remote playback state for control requests. The mediaSessionId and last reported
    // status are learned from MEDIA_STATUS messages on the media namespace.
    private volatile long mediaSessionId;
    private volatile int mediaPlaybackState = MediaItemStatus.PLAYBACK_STATE_PENDING;
    private volatile long mediaPositionMs;
    private volatile long mediaDurationMs = -1;
    private long nextMediaRequestId = 1;
    private PendingPlay pendingPlay;
    private final Map<Long, PendingControl> pendingControls = new HashMap<Long, PendingControl>();

    private static class PendingPlay {
        final String contentId;
        final String contentType;
        final long positionMs;
        final String itemId;
        final MediaRouter.ControlRequestCallback callback;

        PendingPlay(String contentId, String contentType, long positionMs, String itemId,
                MediaRouter.ControlRequestCallback callback) {
            this.contentId = contentId;
            this.contentType = contentType;
            this.positionMs = positionMs;
            this.itemId = itemId;
            this.callback = callback;
        }
    }

    private static class PendingControl {
        final String itemId;
        final long positionMs;
        final boolean wantsStatusReply;
        final MediaRouter.ControlRequestCallback callback;

        PendingControl(String itemId, long positionMs, boolean wantsStatusReply,
                MediaRouter.ControlRequestCallback callback) {
            this.itemId = itemId;
            this.positionMs = positionMs;
            this.wantsStatusReply = wantsStatusReply;
            this.callback = callback;
        }
    }
    private volatile int requestedVolume = -1;
    private volatile long requestedVolumeAt;
    private volatile boolean deviceMuted;
    // The control connection of the selected route, used to follow and change the device volume.
    // Guarded by this; callbacks of a replaced session are ignored. Route state changes are posted
    // to the provider while holding the lock, so they arrive in the order the session changed.
    private CastDeviceSession session;
    private boolean sessionConnected;
    private boolean selected;
    private boolean released;

    public CastMediaRouteController(CastMediaRouteProvider provider, String routeId, String address, int port, int volume) {
        super();

        this.provider = provider;
        this.routeId = routeId;
        this.host = address;
        this.port = port > 0 ? port : CastChannel.DEFAULT_PORT;
        this.volume = volume;
    }

    @Override
    public boolean onControlRequest(Intent intent, MediaRouter.ControlRequestCallback callback) {
        String action = intent.getAction();
        if (action == null || !intent.hasCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK)) {
            return false;
        }
        switch (action) {
            case MediaControlIntent.ACTION_PLAY:
                return onPlayRequest(intent, callback);
            case MediaControlIntent.ACTION_PAUSE:
                return sendMediaCommand("PAUSE", intent, callback);
            case MediaControlIntent.ACTION_RESUME:
                return sendMediaCommand("PLAY", intent, callback);
            case MediaControlIntent.ACTION_STOP:
                return sendMediaCommand("STOP", intent, callback);
            case MediaControlIntent.ACTION_SEEK:
                if (!intent.hasExtra(MediaControlIntent.EXTRA_ITEM_POSITION)) {
                    callback.onError("Seek request without " + MediaControlIntent.EXTRA_ITEM_POSITION, null);
                    return true;
                }
                return sendMediaCommand("SEEK", intent, callback);
            case MediaControlIntent.ACTION_GET_STATUS:
                return sendMediaCommand("GET_STATUS", intent, callback);
            default:
                Log.d(TAG, "unimplemented control request " + action + " on " + this.routeId);
                return false;
        }
    }

    private boolean onPlayRequest(Intent intent, MediaRouter.ControlRequestCallback callback) {
        Uri data = intent.getData();
        if (data == null || data.toString().isEmpty()) {
            callback.onError("Play request without content Uri", null);
            return true;
        }
        CastDeviceSession target = usableSession();
        if (target == null) {
            callback.onError("No connection to " + routeId, null);
            return true;
        }
        synchronized (this) {
            pendingPlay = new PendingPlay(data.toString(), intent.getType(),
                    intent.getLongExtra(MediaControlIntent.EXTRA_ITEM_POSITION, 0),
                    intent.getStringExtra(MediaControlIntent.EXTRA_ITEM_ID), callback);
        }
        // The media namespace is served by the receiver application, so make sure the default
        // media receiver is running before the LOAD command is sent from onApplicationConnected.
        target.launchApplication(DEFAULT_MEDIA_RECEIVER_APP_ID, false, null);
        return true;
    }

    private boolean sendMediaCommand(String type, Intent intent, MediaRouter.ControlRequestCallback callback) {
        boolean isGetStatus = "GET_STATUS".equals(type);
        if (!isGetStatus && mediaSessionId == 0) {
            // The receiver rejects media commands that do not name a live media session.
            callback.onError("No active media session on " + routeId, null);
            return true;
        }
        CastDeviceSession target = usableSession();
        if (target == null) {
            callback.onError("No connection to " + routeId, null);
            return true;
        }
        long requestId;
        synchronized (this) {
            requestId = nextMediaRequestId++;
            pendingControls.put(requestId, new PendingControl(
                    intent.getStringExtra(MediaControlIntent.EXTRA_ITEM_ID),
                    intent.getLongExtra(MediaControlIntent.EXTRA_ITEM_POSITION, 0), isGetStatus, callback));
        }
        String message;
        try {
            message = buildMediaCommand(type, requestId, mediaSessionId,
                    intent.getLongExtra(MediaControlIntent.EXTRA_ITEM_POSITION, 0));
        } catch (JSONException e) {
            synchronized (this) {
                pendingControls.remove(requestId);
            }
            callback.onError(e.getMessage(), null);
            return true;
        }
        sendToMediaChannel(target, message, requestId);
        return true;
    }

    private void sendToMediaChannel(CastDeviceSession target, String message, long requestId) {
        target.registerNamespace(MEDIA_NAMESPACE);
        target.sendMessage(MEDIA_NAMESPACE, message, requestId);
    }

    /**
     * Session usable for media commands. Unlike onSetVolume, only this route's own connection is
     * used: send success, send failure and media namespace replies are delivered to the callbacks
     * of the session that sent them, so a session another client opened through the device
     * controller cannot acknowledge or answer our requests. When the route is still selected and
     * no connection exists, open a fresh control connection like onSetVolume does.
     */
    private CastDeviceSession usableSession() {
        CastDeviceSession target;
        CastDeviceSession started = null;
        synchronized (this) {
            target = session;
            if (target == null && selected && !released) target = started = startSessionLocked();
        }
        if (started != null) started.connect();
        return target;
    }

    static String buildMediaCommand(String type, long requestId, long mediaSessionId, long positionMs)
            throws JSONException {
        JSONObject command = new JSONObject();
        command.put("type", type);
        command.put("requestId", requestId);
        if (mediaSessionId > 0) command.put("mediaSessionId", mediaSessionId);
        if ("SEEK".equals(type)) command.put("currentTime", positionMs / 1000.0);
        return command.toString();
    }

    static String buildLoadCommand(String contentId, String contentType, long positionMs, long requestId)
            throws JSONException {
        JSONObject media = new JSONObject();
        media.put("contentId", contentId);
        media.put("streamType", "BUFFERED");
        if (contentType != null) media.put("contentType", contentType);
        JSONObject command = new JSONObject();
        command.put("type", "LOAD");
        command.put("requestId", requestId);
        command.put("media", media);
        command.put("autoplay", true);
        command.put("currentTime", positionMs / 1000.0);
        return command.toString();
    }

    static String buildLoadCommand(PendingPlay play, long requestId) throws JSONException {
        return buildLoadCommand(play.contentId, play.contentType, play.positionMs, requestId);
    }

    /** Snapshot of one media status entry, parsed separately for testability. */
    static class MediaStatusSnapshot {
        final long mediaSessionId;
        final int playbackState;
        final long positionMs;
        final long durationMs;

        MediaStatusSnapshot(long mediaSessionId, int playbackState, long positionMs, long durationMs) {
            this.mediaSessionId = mediaSessionId;
            this.playbackState = playbackState;
            this.positionMs = positionMs;
            this.durationMs = durationMs;
        }
    }

    static MediaStatusSnapshot parseMediaStatus(JSONObject status) {
        long sessionId = status.optLong("mediaSessionId", 0);
        int playbackState = toItemPlaybackState(status.optString("playerState"));
        long positionMs = (long) (status.optDouble("currentTime", 0) * 1000);
        long durationMs = -1;
        JSONObject media = status.optJSONObject("media");
        if (media != null) durationMs = (long) (media.optDouble("duration", -1) * 1000);
        return new MediaStatusSnapshot(sessionId, playbackState, positionMs, durationMs);
    }

    static int toItemPlaybackState(String playerState) {
        if ("PLAYING".equals(playerState)) return MediaItemStatus.PLAYBACK_STATE_PLAYING;
        if ("PAUSED".equals(playerState)) return MediaItemStatus.PLAYBACK_STATE_PAUSED;
        if ("BUFFERING".equals(playerState)) return MediaItemStatus.PLAYBACK_STATE_BUFFERING;
        if ("IDLE".equals(playerState)) return MediaItemStatus.PLAYBACK_STATE_FINISHED;
        return MediaItemStatus.PLAYBACK_STATE_PENDING;
    }

    private void flushPendingPlay(ReceiverApplication application) {
        PendingPlay play;
        synchronized (this) {
            play = pendingPlay;
            if (play == null) return;
            if (!application.getNamespaces().contains(MEDIA_NAMESPACE)
                    && !DEFAULT_MEDIA_RECEIVER_APP_ID.equals(application.getAppId())) return;
            pendingPlay = null;
        }
        CastDeviceSession target = usableSession();
        if (target == null) {
            play.callback.onError("No connection to " + routeId, null);
            return;
        }
        long requestId;
        synchronized (this) {
            requestId = nextMediaRequestId++;
            pendingControls.put(requestId, new PendingControl(play.itemId, play.positionMs, false, play.callback));
        }
        try {
            sendToMediaChannel(target, buildLoadCommand(play, requestId), requestId);
        } catch (JSONException e) {
            synchronized (this) {
                pendingControls.remove(requestId);
            }
            play.callback.onError(e.getMessage(), null);
        }
    }

    private void failPendingPlay(String error) {
        PendingPlay play;
        synchronized (this) {
            play = pendingPlay;
            pendingPlay = null;
        }
        if (play != null) play.callback.onError(error, null);
    }

    private void failPendingControl(long requestId, String error) {
        PendingControl pending;
        synchronized (this) {
            pending = pendingControls.remove(requestId);
        }
        if (pending != null) pending.callback.onError(error, null);
    }

    private void onMediaStatusMessage(JSONObject json) {
        long requestId = json.optLong("requestId", 0);
        PendingControl pending = null;
        synchronized (this) {
            if (requestId != 0) pending = pendingControls.remove(requestId);
        }
        JSONObject status = json.optJSONArray("status") != null
                ? json.optJSONArray("status").optJSONObject(0) : null;
        if (status != null) {
            MediaStatusSnapshot snapshot = parseMediaStatus(status);
            if (snapshot.mediaSessionId != 0) mediaSessionId = snapshot.mediaSessionId;
            mediaPlaybackState = snapshot.playbackState;
            mediaPositionMs = snapshot.positionMs;
            if (snapshot.durationMs != -1) mediaDurationMs = snapshot.durationMs;
        }
        if (pending != null) {
            Bundle result = new Bundle();
            result.putBundle(MediaControlIntent.EXTRA_ITEM_STATUS, new MediaItemStatus.Builder(mediaPlaybackState)
                    .setPlaybackPosition(mediaPositionMs).setContentDuration(mediaDurationMs).build().asBundle());
            if (pending.itemId != null) result.putString(MediaControlIntent.EXTRA_ITEM_ID, pending.itemId);
            if (mediaSessionId != 0) {
                result.putString(MediaControlIntent.EXTRA_MEDIA_SESSION_ID, String.valueOf(mediaSessionId));
            }
            pending.callback.onResult(result);
        }
    }

    @Override
    public void onSelect() {
        CastDeviceSession newSession;
        synchronized (this) {
            if (released) return;
            selected = true;
            if (session != null) return;
            newSession = startSessionLocked();
        }
        newSession.connect();
    }

    private CastDeviceSession startSessionLocked() {
        SessionCallbacks callbacks = new SessionCallbacks();
        CastDeviceSession newSession = new CastDeviceSession(host, port, callbacks);
        callbacks.session = newSession;
        session = newSession;
        sessionConnected = false;
        provider.onRouteStateChanged(CastMediaRouteController.this, routeId, MediaRouter.RouteInfo.CONNECTION_STATE_CONNECTING, -1);
        return newSession;
    }

    @Override
    public void onUnselect() {
        onUnselect(MediaRouter.UNSELECT_REASON_UNKNOWN);
    }

    @Override
    public void onUnselect(int reason) {
        closeSession();
    }

    @Override
    public void onRelease() {
        synchronized (this) {
            if (released) return;
            released = true;
        }
        closeSession();
        provider.onRouteControllerReleased(CastMediaRouteController.this, routeId);
    }

    private void closeSession() {
        CastDeviceSession oldSession;
        synchronized (this) {
            selected = false;
            oldSession = session;
            session = null;
            sessionConnected = false;
            if (oldSession != null) {
                provider.onRouteStateChanged(CastMediaRouteController.this, routeId, MediaRouter.RouteInfo.CONNECTION_STATE_DISCONNECTED, -1);
            }
        }
        if (oldSession != null) oldSession.disconnect();
    }

    @Override
    public void onSetVolume(int volume) {
        int newVolume = Math.max(0, Math.min(CastMediaRouteProvider.VOLUME_MAX, volume));
        this.volume = newVolume;
        requestedVolume = newVolume;
        requestedVolumeAt = SystemClock.elapsedRealtime();
        provider.onRouteStateChanged(CastMediaRouteController.this, routeId, -1, newVolume);

        // Volume is a device setting, so any open connection to the receiver can change it. Prefer
        // the route's own connection, then one an app opened through the device controller.
        CastDeviceSession target;
        CastDeviceSession started = null;
        synchronized (this) {
            target = sessionConnected ? session : null;
        }
        if (target == null) target = CastChannelRegistry.get(routeId);
        if (target == null) {
            synchronized (this) {
                // Still connecting: the request is sent once the connection is up. If the connection
                // dropped while the route stays selected, open it again.
                target = session;
                if (target == null && selected && !released) target = started = startSessionLocked();
            }
        }
        if (started != null) started.connect();
        if (target != null) {
            target.setVolume((double) newVolume / CastMediaRouteProvider.VOLUME_MAX);
            // A level change on a muted receiver would otherwise stay inaudible.
            if (deviceMuted && newVolume > 0) target.setMute(false);
        } else {
            Log.d(TAG, "No connection to " + routeId + " to set volume");
        }
    }

    @Override
    public void onUpdateVolume(int delta) {
        if (!volumeKnown) {
            // A step relative to the placeholder volume would jump the device to an arbitrary level.
            Log.d(TAG, "Volume of " + routeId + " not known yet, ignoring step");
            return;
        }
        onSetVolume(volume + delta);
    }

    private class SessionCallbacks implements CastDeviceSession.Callbacks {
        CastDeviceSession session;

        private boolean isCurrent() {
            synchronized (CastMediaRouteController.this) {
                return CastMediaRouteController.this.session == session;
            }
        }

        private void onSessionEnded(int statusCode) {
            synchronized (CastMediaRouteController.this) {
                if (CastMediaRouteController.this.session != session) return;
                CastMediaRouteController.this.session = null;
                sessionConnected = false;
                mediaSessionId = 0;
                provider.onRouteStateChanged(CastMediaRouteController.this, routeId, MediaRouter.RouteInfo.CONNECTION_STATE_DISCONNECTED, -1);
            }
            Log.d(TAG, "Connection to " + routeId + " ended: " + statusCode);
            failPendingPlay("Connection to " + routeId + " ended: " + statusCode);
            List<Long> pending;
            synchronized (CastMediaRouteController.this) {
                pending = new ArrayList<Long>(pendingControls.keySet());
            }
            for (long requestId : pending) {
                failPendingControl(requestId, "Connection to " + routeId + " ended: " + statusCode);
            }
            session.disconnect();
        }

        @Override
        public void onConnected() {
            synchronized (CastMediaRouteController.this) {
                if (CastMediaRouteController.this.session != session) return;
                sessionConnected = true;
                provider.onRouteStateChanged(CastMediaRouteController.this, routeId, MediaRouter.RouteInfo.CONNECTION_STATE_CONNECTED, -1);
            }
        }

        @Override
        public void onConnectionFailed(int statusCode) {
            onSessionEnded(statusCode);
        }

        @Override
        public void onDisconnected(int statusCode) {
            onSessionEnded(statusCode);
        }

        @Override
        public void onDeviceStatusChanged(@NonNull ReceiverStatus status) {
            if (!isCurrent()) return;
            deviceMuted = status.getMuted();
            if (!status.getHasVolumeLevel()) return;
            // Follow volume changes made on the device or by other senders.
            int deviceVolume = (int) Math.round(status.getVolumeLevel() * CastMediaRouteProvider.VOLUME_MAX);
            volumeKnown = true;
            if (deviceVolume != requestedVolume && SystemClock.elapsedRealtime() - requestedVolumeAt < LOCAL_VOLUME_HOLD_MS) return;
            volume = deviceVolume;
            provider.onRouteStateChanged(CastMediaRouteController.this, routeId, -1, deviceVolume);
        }

        @Override
        public void onApplicationConnected(@NonNull ReceiverApplication application, boolean wasLaunched) {
            if (isCurrent()) flushPendingPlay(application);
        }

        @Override
        public void onApplicationConnectionFailed(int statusCode) {
            if (isCurrent()) failPendingPlay("Application launch failed: " + statusCode);
        }

        @Override
        public void onApplicationStatusChanged(String statusText) {
        }

        @Override
        public void onApplicationDisconnected(int statusCode) {
        }

        @Override
        public void onStopApplicationResult(int statusCode) {
        }

        @Override
        public void onLeaveApplicationResult(int statusCode) {
        }

        @Override
        public void onTextMessage(@NonNull String namespace, @NonNull String message) {
            if (!isCurrent() || !MEDIA_NAMESPACE.equals(namespace)) return;
            try {
                JSONObject json = new JSONObject(message);
                String type = json.optString("type");
                if ("MEDIA_STATUS".equals(type)) {
                    onMediaStatusMessage(json);
                } else if ("INVALID_REQUEST".equals(type) || "INVALID_PLAYER_STATE".equals(type)
                        || "INVALID_MEDIA_SESSION_ID".equals(type) || "LOAD_FAILED".equals(type)
                        || "LOAD_CANCELLED".equals(type)) {
                    failPendingControl(json.optLong("requestId", 0), type);
                }
            } catch (JSONException e) {
                Log.d(TAG, "Bad media message from " + routeId, e);
            }
        }

        @Override
        public void onBinaryMessage(@NonNull String namespace, @NonNull byte[] data) {
        }

        @Override
        public void onSendMessageSuccess(@NonNull String namespace, long requestId) {
            if (!isCurrent() || !MEDIA_NAMESPACE.equals(namespace)) return;
            PendingControl pending;
            synchronized (CastMediaRouteController.this) {
                pending = pendingControls.get(requestId);
                if (pending == null || pending.wantsStatusReply) return;
                pendingControls.remove(requestId);
            }
            Bundle result = new Bundle();
            if (pending.itemId != null) result.putString(MediaControlIntent.EXTRA_ITEM_ID, pending.itemId);
            if (pending.positionMs != 0) {
                result.putLong(MediaControlIntent.EXTRA_ITEM_POSITION, pending.positionMs);
            }
            pending.callback.onResult(result);
        }

        @Override
        public void onSendMessageFailure(@NonNull String namespace, long requestId, int statusCode) {
            if (!MEDIA_NAMESPACE.equals(namespace)) return;
            failPendingControl(requestId, "Send failed: " + statusCode);
        }
    }
}
