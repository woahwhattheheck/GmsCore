/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.droidguard.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.droidguard.internal.DroidGuardResultsRequest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.droidguard.GuardCallback
import org.microg.gms.droidguard.HandleProxy
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DroidGuardHandleImplSnapshotTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val callback = GuardCallback(context, "org.microg.test")

    private fun factory(provider: () -> HandleProxy) = object : NetworkHandleProxyFactory(context) {
        override fun createHandle(
            packageName: String,
            flow: String?,
            callback: GuardCallback,
            request: DroidGuardResultsRequest?
        ): HandleProxy = provider()
    }

    private fun handle(factory: NetworkHandleProxyFactory) =
        DroidGuardHandleImpl(context, "org.microg.test", factory, callback)

    private fun assertFallback(bytes: ByteArray) =
        assertTrue(bytes.decodeToString().startsWith("ERROR :"))

    @Test
    fun ssMissing_fallsBackOnSnapshot() {
        val service = handle(factory { HandleProxy(NoSsVm(), VM_KEY) })

        val reply = service.initWithRequest("test", null)

        assertNull(reply.pfd)
        assertNull(reply.`object`)
        assertFallback(service.snapshot(mutableMapOf()))
        service.close()
    }

    @Test
    fun ssThrows_fallsBackOnSnapshot() {
        val service = handle(factory { HandleProxy(ThrowingSsVm(), VM_KEY) })

        val reply = service.initWithRequest("test", null)

        assertNull(reply.pfd)
        assertNull(reply.`object`)
        assertFallback(service.snapshot(mutableMapOf()))
        service.close()
    }

    @Test
    fun reinitWithoutClose_keepsPriorErrorUntilCloseResets() {
        var fail = true
        val service = handle(factory {
            if (fail) throw IllegalStateException("create failed")
            HandleProxy(GoodVm(), VM_KEY)
        })

        val firstReply = service.initWithRequest("test", null)
        assertNull(firstReply.pfd)
        assertFallback(service.snapshot(mutableMapOf()))

        // Second init succeeds against a healthy VM, but handleInitError is never
        // cleared inside initWithRequest: the stale error keeps failing closed
        // and skips the rb probe, so the reply stays empty and snapshot falls back.
        fail = false
        val secondReply = service.initWithRequest("test", null)
        assertNull(secondReply.pfd)
        assertNull(secondReply.`object`)
        assertFallback(service.snapshot(mutableMapOf()))

        // close() is the reset path: it clears both the handle and the error, so a
        // following init recovers fully and snapshot reaches the VM again.
        service.close()
        val thirdReply = service.initWithRequest("test", null)
        assertNull(thirdReply.pfd)
        assertNull(thirdReply.`object`)
        assertArrayEquals(SNAPSHOT, service.snapshot(mutableMapOf()))
        service.close()
    }

    @Test
    fun close_clearsHandle_snapshotAfterCloseFallsBack() {
        val service = handle(factory { HandleProxy(GoodVm(), VM_KEY) })
        service.initWithRequest("test", null)
        assertArrayEquals(SNAPSHOT, service.snapshot(mutableMapOf()))

        service.close()

        assertFallback(service.snapshot(mutableMapOf()))
    }

    @Test
    fun rbNonParcelable_treatedAsAbsent_keepsHandle() {
        val service = handle(factory { HandleProxy(StringRbVm(), VM_KEY) })

        val reply = service.initWithRequest("test", null)

        assertNull(reply.pfd)
        assertNull(reply.`object`)
        assertArrayEquals(SNAPSHOT, service.snapshot(mutableMapOf()))
        service.close()
    }

    private class GoodVm {
        fun init() = true
        fun ss(map: Map<Any?, Any?>): ByteArray = SNAPSHOT
        fun close() = Unit
    }

    private class NoSsVm {
        fun init() = true
        fun close() = Unit
    }

    private class ThrowingSsVm {
        fun init() = true
        fun ss(map: Map<Any?, Any?>): ByteArray = throw IllegalStateException("ss failed")
        fun close() = Unit
    }

    private class StringRbVm {
        fun init() = true
        fun rb(): String = "not-a-parcelable"
        fun ss(map: Map<Any?, Any?>): ByteArray = SNAPSHOT
        fun close() = Unit
    }

    companion object {
        private const val VM_KEY = "SNAPTEST"
        private val SNAPSHOT = byteArrayOf(4, 5, 6)
    }
}
