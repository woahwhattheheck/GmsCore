/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.cast;

import androidx.mediarouter.media.MediaControlIntent;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class RemotePlaybackAdvertisedActionsTest {
    @Test
    public void onlyImplementedAdditionalActionsAreAdvertised() {
        Set<String> actual = new HashSet<>(RemotePlaybackControlActions.advertisedAdditionalActions());
        Set<String> expected = new HashSet<>(Arrays.asList(
                MediaControlIntent.ACTION_PAUSE,
                MediaControlIntent.ACTION_RESUME,
                MediaControlIntent.ACTION_STOP,
                MediaControlIntent.ACTION_SEEK,
                MediaControlIntent.ACTION_GET_STATUS));

        assertEquals(expected, actual);
        assertEquals(5, actual.size());
        assertFalse(actual.contains(MediaControlIntent.ACTION_START_SESSION));
        assertFalse(actual.contains(MediaControlIntent.ACTION_GET_SESSION_STATUS));
        assertFalse(actual.contains(MediaControlIntent.ACTION_END_SESSION));
    }
}
