/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package com.google.android.gms.droidguard.internal

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.common.Constants
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the DroidGuardResultsRequest bundle contract on a real Bundle
 * (the JUnit-only :play-services-droidguard module would return stub
 * defaults for every Bundle call, making these assertions meaningless):
 * constructor seeding, timeout/openHandles defaults and fluent setters,
 * null fd/network defaults, and toString enumerating the bundle keys.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class DroidGuardResultsRequestBundleTest {

    @Test
    fun constructorSeedsArchitectureAndClientVersion() {
        val req = DroidGuardResultsRequest()
        assertEquals(System.getProperty("os.arch") ?: "?", req.appArchitecture)
        assertEquals(Constants.GMS_VERSION_CODE, req.clientVersion)
    }

    @Test
    fun timeoutDefaultsToSixtySecondsAndSetIsFluent() {
        val req = DroidGuardResultsRequest()
        assertEquals(60000, req.timeoutMillis)
        assertSame(req, req.setTimeoutMillis(5000))
        assertEquals(5000, req.timeoutMillis)
    }

    @Test
    fun openHandlesDefaultsToZeroAndSetIsFluent() {
        val req = DroidGuardResultsRequest()
        assertEquals(0, req.openHandles)
        assertSame(req, req.setOpenHandles(7))
        assertEquals(7, req.openHandles)
    }

    @Test
    fun fdAndNetworkDefaultToNull() {
        val req = DroidGuardResultsRequest()
        assertNull(req.fd)
        assertNull(req.networkToUse)
    }

    @Test
    fun toStringEnumeratesBundleKeys() {
        val req = DroidGuardResultsRequest()
        val text = req.toString()
        assertTrue(text.contains("appArchitecture"))
        assertTrue(text.contains("clientVersion"))
    }
}
