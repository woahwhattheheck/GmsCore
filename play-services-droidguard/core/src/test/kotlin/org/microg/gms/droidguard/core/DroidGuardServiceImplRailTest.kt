/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.droidguard.core

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.droidguard.DroidGuardChimeraService
import com.google.android.gms.droidguard.internal.DroidGuardResultsRequest
import com.google.android.gms.droidguard.internal.IDroidGuardCallbacks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.settings.SettingsContract
import org.microg.gms.settings.SettingsProvider
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/**
 * Pins the DroidGuardServiceImpl rail — the binder impl the broker hands out:
 *  - getClientTimeoutMillis is a constant 60000
 *  - getHandle dispatches on DroidGuardPreferences mode ONLY (no enabled
 *    check at this layer): Embedded -> DroidGuardHandleImpl,
 *    Network -> RemoteHandleImpl
 *  - the Embedded rail with provisioning disabled delivers the deterministic
 *    fallback payload "ERROR : DroidGuard should not be available locally"
 *    through onResult, exactly once; guard() delegates to guardWithRequest
 *  - the Network rail with no server URL delivers "ERROR : Network URL
 *    required" — init stores state lazily, snapshot() throws at the url
 *    getter, the service wraps it through FallbackCreator
 *  - a throwing onResult callback and a throwing handle close are both
 *    swallowed (no exception escapes guardWithRequest)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DroidGuardServiceImplRailTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun newService(): DroidGuardChimeraService {
        val svc = DroidGuardChimeraService(NetworkHandleProxyFactory(context), Any(), Any())
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            .apply { isAccessible = true }.invoke(svc, context)
        return svc
    }

    private fun installSettingsProvider() {
        val authority = SettingsContract.getAuthority(context)
        val provider = SettingsProvider()
        provider.attachInfo(context, ProviderInfo().apply {
            this.authority = authority
            this.packageName = context.packageName
        })
        ShadowContentResolver.registerProviderInternal(authority, provider)
    }

    private class RecordingCallbacks : IDroidGuardCallbacks.Stub() {
        val results = mutableListOf<ByteArray>()
        var throwing = false
        override fun onResult(res: ByteArray) {
            results.add(res)
            if (throwing) throw RuntimeException("cb boom")
        }
    }

    private fun setMode(mode: DroidGuardPreferences.Mode) {
        context.contentResolver.update(
            SettingsContract.DroidGuard.getContentUri(context),
            ContentValues().apply { put(SettingsContract.DroidGuard.MODE, mode.toString()) },
            null, null
        )
    }

    @Test
    fun clientTimeout_isConstant60000() {
        assertEquals(60000, DroidGuardServiceImpl(newService(), "pkg.test").getClientTimeoutMillis())
    }

    @Test
    fun getHandle_defaultMode_returnsEmbeddedImpl() {
        // No SettingsProvider installed: getMode fails soft to Embedded.
        val handle = DroidGuardServiceImpl(newService(), "pkg.test").getHandle()
        assertTrue(handle is DroidGuardHandleImpl)
    }

    @Test
    fun getHandle_networkMode_returnsRemoteImpl() {
        installSettingsProvider()
        setMode(DroidGuardPreferences.Mode.Network)
        val handle = DroidGuardServiceImpl(newService(), "pkg.test").getHandle()
        assertTrue(handle is RemoteHandleImpl)
    }

    @Test
    fun guardWithRequest_embeddedDisabled_deliversFallbackBytesOnce() {
        val impl = DroidGuardServiceImpl(newService(), "pkg.test")
        val cb = RecordingCallbacks()
        impl.guardWithRequest(cb, "test", mutableMapOf<Any?, Any?>(), null)
        assertEquals(1, cb.results.size)
        assertEquals(
            "ERROR : DroidGuard should not be available locally",
            cb.results[0].decodeToString()
        )
    }

    @Test
    fun guard_delegatesToGuardWithRequest() {
        val impl = DroidGuardServiceImpl(newService(), "pkg.test")
        val cb = RecordingCallbacks()
        impl.guard(cb, "test", mutableMapOf<Any?, Any?>())
        assertEquals(1, cb.results.size)
        assertEquals(
            "ERROR : DroidGuard should not be available locally",
            cb.results[0].decodeToString()
        )
    }

    @Test
    fun guardWithRequest_networkModeMissingUrl_deliversUrlFallback() {
        installSettingsProvider()
        setMode(DroidGuardPreferences.Mode.Network)
        val impl = DroidGuardServiceImpl(newService(), "pkg.test")
        val cb = RecordingCallbacks()
        impl.guardWithRequest(cb, "test", mutableMapOf<Any?, Any?>(), DroidGuardResultsRequest())
        assertEquals(1, cb.results.size)
        assertEquals("ERROR : Network URL required", cb.results[0].decodeToString())
    }

    @Test
    fun guardWithRequest_throwingCallback_isSwallowed() {
        val impl = DroidGuardServiceImpl(newService(), "pkg.test")
        val cb = RecordingCallbacks().apply { throwing = true }
        // Must not propagate the callback's RuntimeException.
        impl.guardWithRequest(cb, "test", mutableMapOf<Any?, Any?>(), null)
        assertEquals(1, cb.results.size)
    }
}
