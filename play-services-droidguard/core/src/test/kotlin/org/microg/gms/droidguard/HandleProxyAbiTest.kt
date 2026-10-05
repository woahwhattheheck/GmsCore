/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.droidguard

import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.InvocationTargetException

/**
 * Pins the HandleProxy VM-ABI rail (the reflective bridge the service impl
 * uses to reach a loaded DroidGuard class):
 *  - run()/init()/close() dispatch by method NAME + ARITY via
 *    findVmMethod over public + declared methods, so a private VM method
 *    is still reachable and a same-name wrong-arity overload is skipped
 *  - every dispatch failure — missing method, throwing VM body, wrong
 *    return type — is wrapped in BytesException carrying the proxy's extra
 *    payload bytes verbatim (the same reference, not a copy)
 *  - the (Context, Parcelable) reflective ctor wraps instantiation failure
 *    in BytesException with an EMPTY extra; the 5-arg network-mode ctor
 *    passes flow/byteCode/callback/bundle through to the VM ctor and keeps
 *    the caller's extra on the proxy
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HandleProxyAbiTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    class RecordingVm {
        var lastData: Map<Any, Any>? = null
        var zeroArgRuns = 0
        var initResult = true
        var closed = false
        fun run() { zeroArgRuns++ }                       // arity decoy: must be skipped
        fun run(data: Map<Any, Any>): ByteArray { lastData = data; return byteArrayOf(4, 5, 6) }
        fun init(): Boolean = initResult
        fun close() { closed = true }
    }

    class VmNoMethods
    class ThrowingVm { fun run(data: Map<Any, Any>): ByteArray = throw IllegalStateException("boom") }
    class StringReturnVm { fun run(data: Map<Any, Any>): String = "nope" }
    class PrivateCloseVm { var closed = false; private fun close() { closed = true } }

    class FiveArgVm(context: Context, flow: String?, byteCode: ByteArray, callback: Any, bundle: Bundle?) {
        val context = context
        val flow = flow
        val byteCode = byteCode
        val callback = callback
        val bundle = bundle
    }

    @Test
    fun run_dispatchesByNameAndArity_returnsBytes() {
        val vm = RecordingVm()
        val proxy = HandleProxy(vm, "k1")
        val data = mapOf<Any, Any>("iidHash" to "abc", "rpc" to "sync")
        assertArrayEquals(byteArrayOf(4, 5, 6), proxy.run(data))
        assertSame(data, vm.lastData)
        assertEquals(0, vm.zeroArgRuns)
    }

    @Test
    fun run_missingMethod_wrapsNoSuchMethodException_inBytesException() {
        val extra = byteArrayOf(7, 7)
        val proxy = HandleProxy(VmNoMethods(), "k2", extra)
        try {
            proxy.run(mapOf())
            fail("expected BytesException")
        } catch (e: BytesException) {
            assertSame(extra, e.bytes)
            assertTrue(e.cause is NoSuchMethodException)
        }
    }

    @Test
    fun run_throwingVm_wrapsInvocationTargetException_keepsExtra() {
        val extra = byteArrayOf(9)
        val proxy = HandleProxy(ThrowingVm(), "k3", extra)
        try {
            proxy.run(mapOf())
            fail("expected BytesException")
        } catch (e: BytesException) {
            assertSame(extra, e.bytes)
            assertTrue(e.cause is InvocationTargetException)
            assertTrue(e.cause!!.cause is IllegalStateException)
        }
    }

    @Test
    fun run_wrongReturnType_wrapsClassCastException() {
        val proxy = HandleProxy(StringReturnVm(), "k4")
        try {
            proxy.run(mapOf())
            fail("expected BytesException")
        } catch (e: BytesException) {
            assertEquals(0, e.bytes.size)
            assertTrue(e.cause is ClassCastException || e.cause is InvocationTargetException)
        }
    }

    @Test
    fun init_dispatchesZeroArg_returnsBoolean() {
        val vm = RecordingVm()
        assertTrue(HandleProxy(vm, "k5").init())
        vm.initResult = false
        assertFalse(HandleProxy(vm, "k5").init())
    }

    @Test
    fun init_missingMethod_throwsBytesException() {
        val extra = byteArrayOf(1)
        try {
            HandleProxy(VmNoMethods(), "k6", extra).init()
            fail("expected BytesException")
        } catch (e: BytesException) {
            assertSame(extra, e.bytes)
            assertTrue(e.cause is NoSuchMethodException)
        }
    }

    @Test
    fun close_dispatches_privateMethodIsReachable() {
        val vm = PrivateCloseVm()
        HandleProxy(vm, "k7").close()
        assertTrue(vm.closed)
    }

    @Test
    fun ctor_missingSignature_wrapsInBytesException_withEmptyExtra() {
        try {
            HandleProxy(String::class.java, context, "k8", Bundle())
            fail("expected BytesException")
        } catch (e: BytesException) {
            assertEquals(0, e.bytes.size)
            assertTrue(e.cause is NoSuchMethodException)
        }
    }

    @Test
    fun ctor2_fiveArg_passesFlowByteCodeCallbackBundle_keepsExtra() {
        val byteCode = byteArrayOf(0xDE.toByte(), 0xAD.toByte())
        val callback = Any()
        val bundle = Bundle().apply { putString("flow", "constellation_verify") }
        val extra = byteArrayOf(0x42)
        val proxy = HandleProxy(FiveArgVm::class.java, context, "constellation_verify", byteCode, callback, "k9", extra, bundle)
        val vm = proxy.handle as FiveArgVm
        assertSame(context, vm.context)
        assertEquals("constellation_verify", vm.flow)
        assertArrayEquals(byteCode, vm.byteCode)
        assertSame(callback, vm.callback)
        assertSame(bundle, vm.bundle)
        assertEquals("k9", proxy.vmKey)
        assertSame(extra, proxy.extra)
    }

    @Test
    fun ctor2_missingSignature_wrapsInBytesException_withCallerExtra() {
        val extra = byteArrayOf(0x33)
        try {
            HandleProxy(String::class.java, context, "f", byteArrayOf(1), Any(), "k10", extra, Bundle())
            fail("expected BytesException")
        } catch (e: BytesException) {
            assertSame(extra, e.bytes)
            assertTrue(e.cause is NoSuchMethodException)
        }
    }
}
