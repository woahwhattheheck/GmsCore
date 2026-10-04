/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.droidguard.core

import android.content.Context
import android.os.Bundle
import android.os.Parcelable
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.droidguard.internal.DroidGuardResultsRequest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.droidguard.GuardCallback
import org.microg.gms.droidguard.HandleProxy
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.charset.StandardCharsets

/**
 * Covers the optional rb() contract in [DroidGuardHandleImpl.initWithRequest].
 *
 * The VM contract is reflective: [HandleProxy] and the init path resolve
 * `init`/`ss`/`rb`/`close` via getDeclaredMethod on the loaded class, so each fake
 * below declares the methods on its own class the way the captured dex declares them
 * directly on com.google.ccc.abuse.droidguard.DroidGuard.
 *
 * rb() is optional on real VM builds. An absent method must not poison an initialized
 * handle (the snapshot must still come from the VM's ss()), while a present rb() that
 * throws remains fatal to the handle and the snapshot falls back instead of crashing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DroidGuardHandleImplRbTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val callback = GuardCallback(context, TEST_PACKAGE)
    private val vmKey = "A1B2C3D4E5F6789012345678ABCDEF012345"
    private val snapshotBytes = "REAL_VM_SNAPSHOT".toByteArray(StandardCharsets.UTF_8)

    /** VM build without rb() — init/ss/close only, as in some deployed VMs. */
    inner class VmWithoutRb {
        fun init(): Boolean = true
        fun ss(map: Map<*, *>): ByteArray = snapshotBytes
        fun close() {}
    }

    /** VM build whose rb() exists but returns no data for this request. */
    inner class VmWithNullRb {
        fun init(): Boolean = true
        fun ss(map: Map<*, *>): ByteArray = snapshotBytes
        fun rb(): Parcelable? = null
        fun close() {}
    }

    /** VM build whose rb() exists and fails at invoke time — remains fatal. */
    inner class VmWithThrowingRb {
        fun init(): Boolean = true
        fun ss(map: Map<*, *>): ByteArray = snapshotBytes
        fun rb(): Parcelable? = throw IllegalStateException("rb failed inside VM")
        fun close() {}
    }

    /** VM build whose rb() returns the fast-path parcel for the on-disk APK. */
    inner class VmWithParcelRb {
        fun init(): Boolean = true
        fun ss(map: Map<*, *>): ByteArray = snapshotBytes
        fun rb(): Parcelable = Bundle()
        fun close() {}
    }

    private inner class FixedHandleFactory(private val proxy: HandleProxy) : NetworkHandleProxyFactory(context) {
        override fun createHandle(packageName: String, flow: String?, callback: GuardCallback, request: DroidGuardResultsRequest?): HandleProxy =
            proxy
    }

    private inner class FailingFactory : NetworkHandleProxyFactory(context) {
        override fun createHandle(packageName: String, flow: String?, callback: GuardCallback, request: DroidGuardResultsRequest?): HandleProxy =
            throw IllegalStateException("no VM in cache")
    }

    private fun impl(factory: NetworkHandleProxyFactory): DroidGuardHandleImpl =
        DroidGuardHandleImpl(context, TEST_PACKAGE, factory, callback)

    @Test
    fun absentRb_leavesInitializedHandleUsable() {
        val handle = impl(FixedHandleFactory(HandleProxy(VmWithoutRb(), vmKey)))
        val reply = handle.initWithRequest("flow", null)

        assertNull(reply.pfd)
        assertNull(reply.`object`)
        // The regression: before the rb probe was optional, an absent method raised
        // NoSuchMethodException into handleInitError and this snapshot returned
        // fallback bytes instead of the VM's real ss() output.
        assertArrayEquals(snapshotBytes, handle.snapshot(mutableMapOf<Any?, Any?>()))
    }

    @Test
    fun nullRbResult_leavesInitializedHandleUsable() {
        val handle = impl(FixedHandleFactory(HandleProxy(VmWithNullRb(), vmKey)))
        val reply = handle.initWithRequest("flow", null)

        assertNull(reply.pfd)
        assertNull(reply.`object`)
        assertArrayEquals(snapshotBytes, handle.snapshot(mutableMapOf<Any?, Any?>()))
    }

    @Test
    fun throwingRb_isFatalToTheHandle_snapshotFallsBack() {
        val handle = impl(FixedHandleFactory(HandleProxy(VmWithThrowingRb(), vmKey)))
        val reply = handle.initWithRequest("flow", null)

        assertNull(reply.pfd)
        assertNull(reply.`object`)
        val out = handle.snapshot(mutableMapOf<Any?, Any?>())
        assertFalse(snapshotBytes.contentEquals(out))
        assertTrue(String(out, StandardCharsets.UTF_8).startsWith("ERROR :"))
    }

    @Test
    fun parcelRb_withCachedApk_returnsPfdReply_andHandleStaysUsable() {
        val factory = FixedHandleFactory(HandleProxy(VmWithParcelRb(), vmKey))
        val theApk = factory.getTheApkFile(vmKey)
        checkNotNull(theApk.parentFile).mkdirs()
        theApk.writeBytes(byteArrayOf(0x50, 0x4B))
        try {
            val handle = impl(factory)
            val reply = handle.initWithRequest("flow", null)

            assertNotNull(reply.pfd)
            assertNotNull(reply.`object`)
            reply.pfd?.close()
            assertArrayEquals(snapshotBytes, handle.snapshot(mutableMapOf<Any?, Any?>()))
        } finally {
            theApk.delete()
        }
    }

    @Test
    fun parcelRb_withoutCachedApk_isFatal_snapshotFallsBack() {
        val factory = FixedHandleFactory(HandleProxy(VmWithParcelRb(), vmKey))
        val theApk = factory.getTheApkFile(vmKey)
        theApk.delete() // rb() promised data for an APK that is not on disk
        val handle = impl(factory)
        val reply = handle.initWithRequest("flow", null)

        assertNull(reply.pfd)
        assertNull(reply.`object`)
        val out = handle.snapshot(mutableMapOf<Any?, Any?>())
        assertFalse(snapshotBytes.contentEquals(out))
        assertTrue(String(out, StandardCharsets.UTF_8).startsWith("ERROR :"))
    }

    @Test
    fun failedInit_snapshotFallsBackInsteadOfCrashing() {
        val handle = impl(FailingFactory())
        val reply = handle.initWithRequest("flow", null)

        assertNull(reply.pfd)
        assertNull(reply.`object`)
        val out = handle.snapshot(mutableMapOf<Any?, Any?>())
        assertTrue(String(out, StandardCharsets.UTF_8).startsWith("ERROR :"))
    }

    private companion object {
        const val TEST_PACKAGE = "com.example.dgtest"
    }
}
