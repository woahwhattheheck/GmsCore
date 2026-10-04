/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.droidguard.core

import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.settings.SettingsContract
import org.microg.gms.settings.SettingsProvider
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/**
 * Pins the DroidGuardPreferences availability matrix through the REAL
 * SettingsProvider + SharedPreferences path (same registerProviderInternal seam
 * the Constellation tests use). Every test writes its own settings explicitly;
 * FORCE_LOCAL_DISABLED is sourced from packaged defaults only, so it is always
 * false in this harness and is not a reachable input here.
 *
 * Discriminating cases:
 *  - default settings leave every gate closed (enabled=false default)
 *  - enabled+Embedded opens both isAvailable and isLocalAvailable
 *  - enabled+Network opens isAvailable but closes isLocalAvailable
 *    (Embedded is the only mode that serves the local path)
 *  - a corrupt stored MODE string falls back to the Embedded default
 *    (Mode.valueOf throws inside the cursor read; getSettings catches -> def)
 *  - setMode round-trips Network -> Embedded
 *  - network server URL round-trips non-null values
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DroidGuardPreferencesGateTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun installSettingsProvider() {
        val authority = SettingsContract.getAuthority(context)
        val provider = SettingsProvider()
        val info = ProviderInfo().apply {
            this.authority = authority
            this.packageName = context.packageName
        }
        provider.attachInfo(context, info)
        ShadowContentResolver.registerProviderInternal(authority, provider)
    }

    private fun writeMode(value: String) {
        // Direct provider update: writes an arbitrary string the typed setter cannot produce.
        val values = ContentValues().apply { put(SettingsContract.DroidGuard.MODE, value) }
        context.contentResolver.update(SettingsContract.DroidGuard.getContentUri(context), values, null, null)
    }

    @Test
    fun defaults_leaveEveryGateClosed() {
        // Explicitly reset to defaults so ordering against other tests cannot leak.
        DroidGuardPreferences.setEnabled(context, false)
        DroidGuardPreferences.setMode(context, DroidGuardPreferences.Mode.Embedded)
        assertFalse(DroidGuardPreferences.isEnabled(context))
        assertFalse(DroidGuardPreferences.isAvailable(context))
        assertFalse(DroidGuardPreferences.isLocalAvailable(context))
    }

    @Test
    fun enabledEmbedded_opensBothGates() {
        DroidGuardPreferences.setEnabled(context, true)
        DroidGuardPreferences.setMode(context, DroidGuardPreferences.Mode.Embedded)
        assertTrue(DroidGuardPreferences.isEnabled(context))
        assertTrue(DroidGuardPreferences.isAvailable(context))
        assertTrue(DroidGuardPreferences.isLocalAvailable(context))
    }

    @Test
    fun enabledNetwork_opensAvailable_closesLocal() {
        DroidGuardPreferences.setEnabled(context, true)
        DroidGuardPreferences.setMode(context, DroidGuardPreferences.Mode.Network)
        assertTrue(DroidGuardPreferences.isAvailable(context))
        assertFalse(DroidGuardPreferences.isLocalAvailable(context))
    }

    @Test
    fun corruptModeString_fallsBackToEmbeddedDefault() {
        writeMode("NOT_A_MODE")
        assertEquals(DroidGuardPreferences.Mode.Embedded, DroidGuardPreferences.getMode(context))
    }

    @Test
    fun setMode_roundTrips() {
        DroidGuardPreferences.setMode(context, DroidGuardPreferences.Mode.Network)
        assertEquals(DroidGuardPreferences.Mode.Network, DroidGuardPreferences.getMode(context))
        DroidGuardPreferences.setMode(context, DroidGuardPreferences.Mode.Embedded)
        assertEquals(DroidGuardPreferences.Mode.Embedded, DroidGuardPreferences.getMode(context))
    }

    @Test
    fun networkServerUrl_roundTrips_nonNull() {
        DroidGuardPreferences.setNetworkServerUrl(context, "https://example.invalid/dg")
        assertEquals("https://example.invalid/dg", DroidGuardPreferences.getNetworkServerUrl(context))
        DroidGuardPreferences.setNetworkServerUrl(context, "https://two.invalid/x")
        assertEquals("https://two.invalid/x", DroidGuardPreferences.getNetworkServerUrl(context))
    }
}
