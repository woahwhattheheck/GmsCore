/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package com.google.android.gms.droidguard

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.droidguard.core.DroidGuardServiceBroker
import org.microg.gms.droidguard.core.NetworkHandleProxyFactory
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the DroidGuardChimeraService entry gates — the bind arming site that
 * flips on attestation blocking, serial unflaking and the dump blockers:
 *  - onBind returns the DroidGuardServiceBroker ONLY for the START action;
 *    null intent and every other action (including PING, which is the
 *    onHandleIntent rail instead) yield null
 *  - the ping-intent rail a() is fail-soft end to end: null intent,
 *    non-PING action, PING with byte[] / int[] / missing "data" all return
 *    without throwing while the VM provisioning path inside c() is allowed
 *    to fail internally (caught + logged)
 *  - the 3-arg constructor installs the injected NetworkHandleProxyFactory
 *    on the public `b` field, and b(pkg) always yields a GuardCallback
 *
 * The Chimera Service base is a ContextWrapper with a null base; tests attach
 * a real base through the protected ContextWrapper.attachBaseContext seam.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DroidGuardChimeraServiceGateTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun newService(): DroidGuardChimeraService {
        val svc = DroidGuardChimeraService(NetworkHandleProxyFactory(context), Any(), Any())
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            .apply { isAccessible = true }.invoke(svc, context)
        return svc
    }

    @Test
    fun onBind_startAction_returnsBroker() {
        val svc = newService()
        val binder = svc.onBind(Intent("com.google.android.gms.droidguard.service.START"))
        assertNotNull(binder)
        assertTrue(binder is DroidGuardServiceBroker)
    }

    @Test
    fun onBind_otherActionsAndNull_returnNull() {
        val svc = newService()
        assertNull(svc.onBind(Intent("com.google.android.gms.droidguard.service.PING")))
        assertNull(svc.onBind(Intent("com.google.android.gms.droidguard.service.OTHER")))
        assertNull(svc.onBind(Intent()))
        assertNull(svc.onBind(null))
    }

    @Test
    fun pingRail_allDataShapes_completeSoftly() {
        val svc = newService()
        // None of these may escape an exception, whatever the payload shape.
        svc.a(null)
        svc.a(Intent("com.google.android.gms.droidguard.service.OTHER"))
        svc.a(Intent("com.google.android.gms.droidguard.service.PING"))
        svc.a(Intent("com.google.android.gms.droidguard.service.PING")
            .putExtra("data", byteArrayOf(0x01, 0x02)))
        svc.a(Intent("com.google.android.gms.droidguard.service.PING")
            .putExtra("data", intArrayOf(0x11FF, 0x42, -1)))
    }

    @Test
    fun threeArgCtor_installsFactory_callbackFactoryYieldsGuardCallback() {
        val factory = NetworkHandleProxyFactory(context)
        val svc = DroidGuardChimeraService(factory, Any(), Any())
        assertSame(factory, svc.b)
        assertNotNull(svc.b("some.package"))
    }
}
