/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.droidguard.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.droidguard.DroidGuardChimeraService
import com.google.android.gms.droidguard.internal.DroidGuardResultsRequest
import com.google.android.gms.droidguard.internal.IDroidGuardCallbacks
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.droidguard.GuardCallback
import org.microg.gms.droidguard.HandleProxy
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DroidGuardServiceImplGuardTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private class RecordingCallbacks : IDroidGuardCallbacks.Stub() {
        var received: ByteArray? = null
        var throwOnResult = false
        override fun onResult(res: ByteArray?) {
            received = res
            if (throwOnResult) throw IllegalStateException("onResult failed")
        }
    }

    private fun chimeraWith(factory: NetworkHandleProxyFactory): DroidGuardChimeraService {
        val service: DroidGuardChimeraService =
            Robolectric.buildService<DroidGuardChimeraService>(DroidGuardChimeraService::class.java).create().get()
        // `b` is the embedded factory used by getHandle(). It is not a public setter
        // (the previous assignment did not compile), so inject the test double by field.
        val field = DroidGuardChimeraService::class.java.declaredFields.first { candidate ->
            candidate.type.isAssignableFrom(factory.javaClass) || candidate.name == "b"
        }
        field.isAccessible = true
        field.set(service, factory)
        return service
    }

    private fun factory(provider: () -> HandleProxy) = object : NetworkHandleProxyFactory(context) {
        override fun createHandle(
            packageName: String,
            flow: String?,
            callback: GuardCallback,
            request: DroidGuardResultsRequest?
        ): HandleProxy = provider()
    }

    @Test
    fun guardWithRequest_embedded_deliversVmBytesAndClosesHandle() {
        val closed = AtomicBoolean(false)
        val service = chimeraWith(factory { HandleProxy(RecordingVm(SNAPSHOT, closed), VM_KEY) })
        val callbacks = RecordingCallbacks()

        DroidGuardServiceImpl(service, "org.microg.test")
            .guardWithRequest(callbacks, "test", mutableMapOf<Any?, Any?>(), null)

        assertArrayEquals(SNAPSHOT, callbacks.received)
        assertTrue(closed.get())
    }

    @Test
    fun guard_embedded_delegatesAndDelivers() {
        val closed = AtomicBoolean(false)
        val service = chimeraWith(factory { HandleProxy(RecordingVm(SNAPSHOT, closed), VM_KEY) })
        val callbacks = RecordingCallbacks()

        DroidGuardServiceImpl(service, "org.microg.test")
            .guard(callbacks, "test", mutableMapOf<Any?, Any?>())

        assertArrayEquals(SNAPSHOT, callbacks.received)
        assertTrue(closed.get())
    }

    @Test
    fun guardWithRequest_handleInitFails_callbackStillReceivesFallback() {
        val service = chimeraWith(factory { throw IllegalStateException("create failed") })
        val callbacks = RecordingCallbacks()

        DroidGuardServiceImpl(service, "org.microg.test")
            .guardWithRequest(callbacks, "test", mutableMapOf<Any?, Any?>(), null)

        // initWithRequest fails closed inside the handle, snapshot falls back, and the
        // callback still receives error bytes rather than the call propagating.
        assertTrue(callbacks.received!!.decodeToString().startsWith("ERROR :"))
    }

    @Test
    fun guardWithRequest_callbackThrows_handleStillCloses() {
        val closed = AtomicBoolean(false)
        val service = chimeraWith(factory { HandleProxy(RecordingVm(SNAPSHOT, closed), VM_KEY) })
        val callbacks = RecordingCallbacks().also { it.throwOnResult = true }

        DroidGuardServiceImpl(service, "org.microg.test")
            .guardWithRequest(callbacks, "test", mutableMapOf<Any?, Any?>(), null)

        assertTrue(closed.get())
    }

    @Test
    fun getClientTimeoutMillis_is60Seconds() {
        val service = chimeraWith(factory { HandleProxy(RecordingVm(SNAPSHOT, AtomicBoolean()), VM_KEY) })
        assertEquals(60000, DroidGuardServiceImpl(service, "org.microg.test").getClientTimeoutMillis())
    }

    private class RecordingVm(
        private val snapshot: ByteArray,
        private val closed: AtomicBoolean
    ) {
        fun init() = true
        fun ss(map: Map<Any?, Any?>): ByteArray = snapshot
        fun close() {
            closed.set(true)
        }
    }

    companion object {
        private const val VM_KEY = "GUARDTEST"
        private val SNAPSHOT = byteArrayOf(11, 12, 13)
    }
}
