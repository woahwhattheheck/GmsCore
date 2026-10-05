/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.droidguard.core

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.common.internal.ConnectionInfo
import com.google.android.gms.common.internal.GetServiceRequest
import com.google.android.gms.common.internal.IGmsCallbacks
import com.google.android.gms.droidguard.DroidGuardChimeraService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the DroidGuardServiceBroker caller-identity gate — the access-control
 * boundary in front of every DroidGuard handle:
 *  - a GetServiceRequest whose packageName matches the binder calling uid's
 *    package resolves and delivers status 0 + a DroidGuardServiceImpl binder
 *    through onPostInitComplete (Robolectric reports the test app's own uid,
 *    so the app's own package name is the accepted caller)
 *  - a foreign packageName fails the uid check and throws SecurityException
 *    before any service impl is created or delivered
 *  - a null packageName resolves to the caller's own package (suggested name
 *    is only an assertion, not the source of truth)
 *  - getService() delegates verbatim to handleServiceRequest
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DroidGuardServiceBrokerGateTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun newBroker(): DroidGuardServiceBroker {
        val svc = DroidGuardChimeraService(NetworkHandleProxyFactory(context), Any(), Any())
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            .apply { isAccessible = true }.invoke(svc, context)
        return DroidGuardServiceBroker(svc)
    }

    private class RecordingGmsCallbacks : IGmsCallbacks.Stub() {
        var statusCode: Int? = null
        var binder: IBinder? = null
        var params: Bundle? = null
        override fun onPostInitComplete(statusCode: Int, binder: IBinder?, params: Bundle?) {
            this.statusCode = statusCode
            this.binder = binder
            this.params = params
        }
        override fun onAccountValidationComplete(statusCode: Int, params: Bundle?) {}
        override fun onPostInitCompleteWithConnectionInfo(statusCode: Int, binder: IBinder?, info: ConnectionInfo?) {}
    }

    private fun request(packageName: String?) =
        GetServiceRequest(org.microg.gms.common.GmsService.DROID_GUARD.SERVICE_ID).apply {
            this.packageName = packageName
        }

    @Test
    fun handleServiceRequest_ownPackage_deliversServiceImpl() {
        val cb = RecordingGmsCallbacks()
        newBroker().handleServiceRequest(cb, request(context.packageName), null)
        assertEquals(0, cb.statusCode)
        assertTrue(cb.binder is DroidGuardServiceImpl)
        assertNull(cb.params)
    }

    @Test
    fun getService_delegatesToHandleServiceRequest() {
        val cb = RecordingGmsCallbacks()
        newBroker().getService(cb, request(context.packageName))
        assertEquals(0, cb.statusCode)
        assertTrue(cb.binder is DroidGuardServiceImpl)
    }

    @Test
    fun handleServiceRequest_foreignPackage_throwsSecurityException() {
        val cb = RecordingGmsCallbacks()
        try {
            newBroker().handleServiceRequest(cb, request("com.example.foreign"), null)
            fail("expected SecurityException")
        } catch (e: SecurityException) {
            // pinned: foreign package never reaches onPostInitComplete
        }
        assertNull(cb.statusCode)
        assertNull(cb.binder)
    }

    @Test
    fun handleServiceRequest_nullPackage_resolvesToCallingPackage() {
        val cb = RecordingGmsCallbacks()
        newBroker().handleServiceRequest(cb, request(null), null)
        assertEquals(0, cb.statusCode)
        assertTrue(cb.binder is DroidGuardServiceImpl)
    }

    @Test
    fun handleServiceRequest_nullRequest_throws() {
        try {
            newBroker().handleServiceRequest(RecordingGmsCallbacks(), null, null)
            fail("expected NPE for null request")
        } catch (e: NullPointerException) {
            // request!!.packageName — pinned null-guard
        }
    }
}
