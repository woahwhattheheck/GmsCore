/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.droidguard.core

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the remote-handle fail-closed path: without a configured network server URL
 * (`DroidGuardPreferences.getNetworkServerUrl` returns null when unset or when the
 * settings provider is unreachable), `snapshot()` must throw rather than emit a
 * request to a null/relative endpoint.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemoteHandleImplLifecycleTest {

    private val handle = RemoteHandleImpl(
        ApplicationProvider.getApplicationContext(), "com.example.app"
    )

    @Test
    fun initWithRequest_returnsNullReply() {
        assertNull(handle.initWithRequest("shield", null))
    }

    @Test
    fun snapshot_withoutConfiguredServerUrl_throwsIllegalStateException() {
        handle.init("droidguard")
        assertThrows(IllegalStateException::class.java) { handle.snapshot(emptyMap()) }
    }
}
