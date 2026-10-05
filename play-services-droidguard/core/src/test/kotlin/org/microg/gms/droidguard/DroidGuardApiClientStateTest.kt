/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.droidguard

import android.content.Context
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.droidguard.internal.DroidGuardResultsRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Pins DroidGuardApiClient's connection-independent state machine — the parts
 * that run before/without a live IDroidGuardService:
 *  - markHandleClosed floors at zero (warns + returns, never negative, never
 *    disconnects on 0 -> 0) and decrements + disconnects at 1 -> 0
 *  - runOnHandler executes inline on the "DG" HandlerThread and posts from any
 *    other thread
 *  - setPackageName installs on the inherited GmsClient.packageName field and
 *    the constructor defaults it to context.getPackageName()
 *  - a failed openHandle (no service) returns an error handle AND never writes
 *    openHandles into the request bundle — getServiceInterface() throws before
 *    request.setOpenHandles is reached — and the client counter stays 0
 *  - interfaceFromBinder delegates to IDroidGuardService.Stub.asInterface:
 *    null yields null; a descriptor-less binder is wrapped in a remote proxy
 *    whose asBinder() identity is preserved
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DroidGuardApiClientStateTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private class Probe(context: Context) : DroidGuardApiClient(context, null, null) {
        fun pkg() = packageName
        fun fromBinder(binder: IBinder?) = interfaceFromBinder(binder)
    }

    private fun openHandles(client: DroidGuardApiClient): Int {
        val f = DroidGuardApiClient::class.java.getDeclaredField("openHandles")
        f.isAccessible = true
        return f.getInt(client)
    }

    private fun setOpenHandles(client: DroidGuardApiClient, value: Int) {
        val f = DroidGuardApiClient::class.java.getDeclaredField("openHandles")
        f.isAccessible = true
        f.setInt(client, value)
    }

    @Test
    fun markHandleClosed_atZero_staysZero() {
        val client = DroidGuardApiClient(context, null, null)
        client.markHandleClosed()
        client.markHandleClosed()
        assertEquals(0, openHandles(client))
    }

    @Test
    fun markHandleClosed_decrementsToZero() {
        val client = DroidGuardApiClient(context, null, null)
        setOpenHandles(client, 1)
        client.markHandleClosed()
        assertEquals(0, openHandles(client))
        // disconnect() on a never-connected client must be a safe no-op here.
        client.markHandleClosed()
        assertEquals(0, openHandles(client))
    }

    @Test
    fun runOnHandler_fromTestThread_postsToDGThread() {
        val client = DroidGuardApiClient(context, null, null)
        val latch = CountDownLatch(1)
        val threadName = AtomicReference<String>()
        client.runOnHandler {
            threadName.set(Thread.currentThread().name)
            latch.countDown()
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals("DG", threadName.get())
    }

    @Test
    fun runOnHandler_onDGThread_runsInline() {
        val client = DroidGuardApiClient(context, null, null)
        val handlerField = DroidGuardApiClient::class.java.getDeclaredField("handler")
        handlerField.isAccessible = true
        val handler = handlerField.get(client) as Handler
        val latch = CountDownLatch(1)
        val ranInline = AtomicBoolean(false)
        handler.post {
            val flag = AtomicBoolean(false)
            client.runOnHandler { flag.set(true) }
            ranInline.set(flag.get())
            latch.countDown()
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertTrue(ranInline.get())
    }

    @Test
    fun setPackageName_overridesInheritedField() {
        val probe = Probe(context)
        assertEquals(context.packageName, probe.pkg())
        probe.setPackageName("com.vendor.caller")
        assertEquals("com.vendor.caller", probe.pkg())
    }

    @Test
    fun openHandle_withoutService_returnsErrorHandleAndLeavesRequestUntouched() {
        val client = DroidGuardApiClient(context, null, null)
        val request = DroidGuardResultsRequest()
        val handle = client.openHandle("test-flow", request)
        assertNotNull(handle)
        assertFalse(handle.isOpened)
        // getServiceInterface() threw before request.setOpenHandles ran.
        assertFalse(request.bundle.containsKey("openHandles"))
        assertEquals(0, openHandles(client))
    }

    @Test
    fun interfaceFromBinder_null_returnsNull() {
        assertNull(Probe(context).fromBinder(null))
    }

    @Test
    fun interfaceFromBinder_descriptorlessBinder_wrapsInRemoteProxy() {
        val binder = Binder()
        val iface = Probe(context).fromBinder(binder)
        assertNotNull(iface)
        assertSame(binder, iface.asBinder())
    }
}
