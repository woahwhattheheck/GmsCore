"""Apply the scoped outcome translation; no build, test or publication."""
from pathlib import Path
import subprocess

path = Path('play-services-cast/core/src/main/java/org/microg/gms/cast/CastMediaRouteController.java')
assert subprocess.check_output(['git', 'hash-object', str(path)], text=True).strip() == '4602b05d39e9b005fb4e4227fe28f33a41fc2dfc'
s = path.read_text()
old = 'int playbackState = toItemPlaybackState(status.optString("playerState"));'
new = 'int playbackState = toItemPlaybackState(status.optString("playerState"), status.optString("idleReason"));'
assert s.count(old) == 1
s = s.replace(old, new)
old = '    static int toItemPlaybackState(String playerState) {\n'
new = '''    static int toItemPlaybackState(String playerState) {
        return toItemPlaybackState(playerState, null);
    }

    static int toItemPlaybackState(String playerState, String idleReason) {
        if ("IDLE".equals(playerState)) {
            if ("ERROR".equals(idleReason)) return MediaItemStatus.PLAYBACK_STATE_ERROR;
            // Cast spells CANCELLED with two Ls; AndroidX uses CANCELED.
            // A new LOAD interrupts and cancels the previous item, not finishes it.
            if ("CANCELLED".equals(idleReason) || "INTERRUPTED".equals(idleReason)) {
                return MediaItemStatus.PLAYBACK_STATE_CANCELED;
            }
        }
'''
assert s.count(old) == 1
s = s.replace(old, new)
path.write_text(s)

test_path = Path('play-services-cast/core/src/test/java/org/microg/gms/cast/RemotePlaybackIdleOutcomeTest.java')
assert not test_path.exists()
test_path.write_text('''/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.cast;

import static org.junit.Assert.assertEquals;

import androidx.mediarouter.media.MediaItemStatus;
import org.json.JSONObject;
import org.junit.Test;

/** Receiver outcome translation, using the same parser as MEDIA_STATUS callbacks. */
public class RemotePlaybackIdleOutcomeTest {
    private CastMediaRouteController.MediaStatusSnapshot parse(String playerState, String reason) throws Exception {
        JSONObject status = new JSONObject()
                .put("mediaSessionId", 77)
                .put("playerState", playerState)
                .put("currentTime", 12.5)
                .put("media", new JSONObject().put("duration", 95.0));
        if (reason != null) status.put("idleReason", reason);
        CastMediaRouteController.MediaStatusSnapshot snapshot = CastMediaRouteController.parseMediaStatus(status);
        assertEquals(77, snapshot.mediaSessionId);
        assertEquals(12500, snapshot.positionMs);
        assertEquals(95000, snapshot.durationMs);
        return snapshot;
    }

    @Test public void receiverErrorIsNotNormalCompletion() throws Exception {
        assertEquals(MediaItemStatus.PLAYBACK_STATE_ERROR, parse("IDLE", "ERROR").playbackState);
    }

    @Test public void stoppedPlaybackIsCanceled() throws Exception {
        assertEquals(MediaItemStatus.PLAYBACK_STATE_CANCELED, parse("IDLE", "CANCELLED").playbackState);
    }

    @Test public void replacementLoadCancelsTheOldItem() throws Exception {
        assertEquals(MediaItemStatus.PLAYBACK_STATE_CANCELED, parse("IDLE", "INTERRUPTED").playbackState);
    }

    @Test public void finishedAndLegacyUnspecifiedIdleKeepTheirMapping() throws Exception {
        for (String reason : new String[] {"FINISHED", null, "FUTURE_REASON"}) {
            assertEquals(MediaItemStatus.PLAYBACK_STATE_FINISHED, parse("IDLE", reason).playbackState);
        }
        assertEquals(MediaItemStatus.PLAYBACK_STATE_FINISHED, CastMediaRouteController.toItemPlaybackState("IDLE"));
    }

    @Test public void idleReasonDoesNotOverrideAnActivePlayerState() throws Exception {
        assertEquals(MediaItemStatus.PLAYBACK_STATE_PLAYING, parse("PLAYING", "ERROR").playbackState);
        assertEquals(MediaItemStatus.PLAYBACK_STATE_PAUSED, parse("PAUSED", "CANCELLED").playbackState);
        assertEquals(MediaItemStatus.PLAYBACK_STATE_BUFFERING, parse("BUFFERING", "INTERRUPTED").playbackState);
        assertEquals(MediaItemStatus.PLAYBACK_STATE_PENDING, parse("UNKNOWN", "ERROR").playbackState);
    }
}
''')

doc_path = Path('docs/validation/cast-idle-outcomes.md')
assert not doc_path.exists()
doc_path.write_text('''# Cast idle outcome translation

The MediaRouter status parser previously mapped every Cast `IDLE` message to normal completion. A receiver playback error, explicit stop or replacement load therefore looked like a finished item to the requesting client.

The parser now uses `idleReason` only while the player is `IDLE`: `ERROR` becomes AndroidX `PLAYBACK_STATE_ERROR`; wire `CANCELLED` and `INTERRUPTED` become `PLAYBACK_STATE_CANCELED`. `FINISHED` retains normal completion. Other player states ignore idle reasons. To keep this a bounded compatibility repair, absent and unknown idle reasons retain the earlier fallback; the one-argument helper remains available. Session, position, duration, request ownership, lifecycle, direct STOP acknowledgement behavior and transport code are unchanged.

Protocol sources: [Google Cast media messages](https://developers.google.com/cast/docs/media/messages#MediaStatus) defines the wire reasons, including `CANCELLED` with two Ls and `INTERRUPTED` on a replacement LOAD. [AndroidX MediaItemStatus](https://developer.android.com/reference/androidx/mediarouter/media/MediaItemStatus) distinguishes failed, canceled and normally finished playback.

`RemotePlaybackIdleOutcomeTest` adds five focused parser regression methods. The first three assert the previously missing distinctions. The remaining methods preserve normal and legacy fallback outcomes, active states and session/position/duration metadata. Existing tests are unchanged.

This delivery contains source and regression cases. No Android module, APK or physical-receiver execution is asserted here; reuse the existing Cast validation environment for that execution rather than interpreting the source-assembly workflow as a test pass. No new bounty claim, acceptance or payment is implied.
''')
print('Prepared one production file, one regression file and one scope note.')
