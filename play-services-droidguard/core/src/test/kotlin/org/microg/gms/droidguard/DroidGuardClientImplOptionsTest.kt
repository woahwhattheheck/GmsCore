/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.droidguard

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.common.api.Api
import android.os.Bundle
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.api.internal.ConnectionCallbacks
import com.google.android.gms.common.api.internal.OnConnectionFailedListener
import com.google.android.gms.common.internal.ClientSettings
import com.google.android.gms.droidguard.DroidGuard
import com.google.android.gms.droidguard.DroidGuardClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the DroidGuardClientImpl factory wiring — the caller-identity plumbing
 * that decides which packageName the broker's getAndCheckCallingPackage sees:
 *  - DroidGuard.getClient(context[, pkg]) returns DroidGuardClientImpl
 *    instances and never shares one between calls
 *  - Options carries packageName verbatim (public final field)
 *  - the registered Api.ClientBuilder builds a DroidGuardApiClient and applies
 *    Options.packageName via setPackageName when non-null; a null Options
 *    packageName leaves the constructor default (context.getPackageName())
 *  - init()/getResults() return Tasks without throwing (the apiCall only runs
 *    once a connection exists; none does here, so results stay pending or fail
 *    asynchronously — only the synchronous contract is pinned)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DroidGuardClientImplOptionsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Suppress("UNCHECKED_CAST")
    private fun api(): Api<DroidGuardClientImpl.Options> {
        val f = DroidGuardClientImpl::class.java.getDeclaredField("API")
        f.isAccessible = true
        return f.get(null) as Api<DroidGuardClientImpl.Options>
    }

    private fun packageNameOf(client: Any): String? {
        val f = org.microg.gms.common.GmsClient::class.java.getDeclaredField("packageName")
        f.isAccessible = true
        return f.get(client) as String?
    }

    private object NoopCallbacks : ConnectionCallbacks {
        override fun onConnected(bundle: Bundle?) {}
        override fun onConnectionSuspended(i: Int) {}
    }

    private object NoopFailedListener : OnConnectionFailedListener {
        override fun onConnectionFailed(result: ConnectionResult) {}
    }

    @Test
    fun getClient_returnsDistinctDroidGuardClientImplInstances() {
        val a = DroidGuard.getClient(context)
        val b = DroidGuard.getClient(context, "com.vendor.other")
        assertTrue(a is DroidGuardClientImpl)
        assertTrue(b is DroidGuardClientImpl)
        assertTrue(a !== b)
    }

    @Test
    fun options_carriesPackageNameVerbatim() {
        assertEquals("com.vendor.caller", DroidGuardClientImpl.Options("com.vendor.caller").packageName)
        assertNull(DroidGuardClientImpl.Options(null).packageName)
    }

    @Test
    fun clientBuilder_appliesOptionsPackageName() {
        val client = api().clientBuilder.buildClient(
            context, Looper.getMainLooper(), ClientSettings.createDefault(context),
            DroidGuardClientImpl.Options("com.vendor.caller"),
            NoopCallbacks, NoopFailedListener
        )
        assertTrue(client is DroidGuardApiClient)
        assertEquals("com.vendor.caller", packageNameOf(client))
    }

    @Test
    fun clientBuilder_nullOptionsPackageName_keepsContextDefault() {
        val client = api().clientBuilder.buildClient(
            context, Looper.getMainLooper(), ClientSettings.createDefault(context),
            DroidGuardClientImpl.Options(null),
            NoopCallbacks, NoopFailedListener
        )
        assertEquals(context.packageName, packageNameOf(client))
    }

    @Test
    fun initAndGetResults_returnTasksWithoutThrowing() {
        val client: DroidGuardClient = DroidGuardClientImpl(context)
        assertNotNull(client.init("test-flow", null))
        assertNotNull(client.getResults("test-flow", mapOf("k" to "v"), null))
    }
}
