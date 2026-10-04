/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.droidguard.core

import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.droidguard.internal.DroidGuardResultsRequest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
class DroidGuardHandleImplRbTest {
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

    @Test
    fun rbAbsent_keepsInitializedHandleForSnapshot() {
        val service = handle(factory { HandleProxy(NoRbVm(), VM_KEY) })

        val reply = service.initWithRequest("test", null)

        assertNull(reply.pfd)
        assertNull(reply.`object`)
        assertArrayEquals(SNAPSHOT, service.snapshot(mutableMapOf()))
        service.close()
    }

    @Test
    fun rbNull_keepsInitializedHandleForSnapshot() {
        val service = handle(factory { HandleProxy(RbVm(null), VM_KEY) })

        val reply = service.initWithRequest("test", null)

        assertNull(reply.pfd)
        assertNull(reply.`object`)
        assertArrayEquals(SNAPSHOT, service.snapshot(mutableMapOf()))
        service.close()
    }

    @Test
    fun rbThrows_fallsBackInsteadOfUsingBrokenHandle() {
        val service = handle(factory { HandleProxy(RbVm(null, throwOnRb = true), VM_KEY) })

        val reply = service.initWithRequest("test", null)

        assertNull(reply.pfd)
        assertNull(reply.`object`)
        assertTrue(service.snapshot(mutableMapOf()).decodeToString().startsWith("ERROR :"))
        service.close()
    }

    @Test
    fun rbParcelableWithApk_returnsFileDescriptorAndObject() {
        val value = Bundle().apply { putString("marker", "rb") }
        val factory = factory { HandleProxy(RbVm(value), VM_KEY) }
        factory.getTheApkFile(VM_KEY).apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val service = handle(factory)

        val reply = service.initWithRequest("test", null)

        assertNotNull(reply.pfd)
        val returned = reply.`object` as Bundle
        assertEquals("rb", returned.getString("marker"))
        assertArrayEquals(SNAPSHOT, service.snapshot(mutableMapOf()))
        reply.pfd?.close()
        service.close()
    }

    @Test
    fun rbParcelableWithoutApk_failsClosedToFallback() {
        val value = Bundle().apply { putString("marker", "rb") }
        val factory = factory { HandleProxy(RbVm(value), VM_KEY) }
        factory.getTheApkFile(VM_KEY).parentFile?.deleteRecursively()
        val service = handle(factory)

        val reply = service.initWithRequest("test", null)

        assertNull(reply.pfd)
        assertNull(reply.`object`)
        assertTrue(service.snapshot(mutableMapOf()).decodeToString().startsWith("ERROR :"))
        service.close()
    }

    @Test
    fun createHandleThrows_returnsFallbackWithoutCrashing() {
        val service = handle(factory { throw IllegalStateException("create failed") })

        val reply = service.initWithRequest("test", null)

        assertNull(reply.pfd)
        assertNull(reply.`object`)
        assertTrue(service.snapshot(mutableMapOf()).decodeToString().startsWith("ERROR :"))
        service.close()
    }

    private class NoRbVm {
        fun init() = true
        fun ss(map: Map<Any?, Any?>): ByteArray = SNAPSHOT
        fun close() = Unit
    }

    private class RbVm(
        private val value: android.os.Parcelable?,
        private val throwOnRb: Boolean = false
    ) {
        fun init() = true

        fun rb(): android.os.Parcelable? {
            if (throwOnRb) throw IllegalStateException("rb failed")
            return value
        }

        fun ss(map: Map<Any?, Any?>): ByteArray = SNAPSHOT
        fun close() = Unit
    }

    companion object {
        private const val VM_KEY = "RBTEST"
        private val SNAPSHOT = byteArrayOf(7, 8, 9)
    }
}
