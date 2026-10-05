/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.droidguard

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.droidguard.internal.DroidGuardInitReply
import com.google.android.gms.droidguard.internal.DroidGuardResultsRequest
import com.google.android.gms.droidguard.internal.IDroidGuardHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pins the client-side DroidGuardHandleImpl result/close contract:
 *  - error-constructor short-circuits snapshot() to "ERROR : <msg>" bytes,
 *    encoded as URL-SAFE base64 with NO_WRAP and NO_PADDING
 *  - a null remote result becomes a STICKY error ("Received null") — the
 *    remote handle is never consulted again
 *  - a throwing remote becomes a STICKY "Snapshot failed" error
 *  - a snapshot that exceeds request.timeoutMillis yields a NON-sticky
 *    "Snapshot timeout" error — isOpened() stays true and a later snapshot
 *    can still succeed (the timeout does not set the error field)
 *  - close() swallows remote close failures and still marks the handle closed
 *  - openHandle() on an unconnected client returns an error handle rather
 *    than throwing (catch-all -> "Initialization failed: ...")
 *
 * Lives in the core test sourceSet: the client impl classes are public and
 * reachable through core's `api project(':play-services-droidguard')`, and
 * this test needs real Handler/Looper/Binder/Base64 shadows — the non-core
 * module's JUnit-only classpath cannot run it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DroidGuardClientHandleSemanticsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private class FakeHandle : IDroidGuardHandle.Stub() {
        @Volatile var snapshotResult: ByteArray? = byteArrayOf(1, 2, 3)
        @Volatile var snapshotThrow: Throwable? = null
        @Volatile var snapshotSleepMs: Long = 0
        @Volatile var closeThrow: Throwable? = null
        val snapshotCalls = AtomicInteger(0)
        val closeCalls = AtomicInteger(0)
        val closeLatch = CountDownLatch(1)

        override fun init(flow: String?) {}
        override fun snapshot(map: MutableMap<*, *>?): ByteArray? {
            snapshotCalls.incrementAndGet()
            if (snapshotSleepMs > 0) Thread.sleep(snapshotSleepMs)
            snapshotThrow?.let { throw RuntimeException("remote-boom", it) }
            return snapshotResult
        }

        override fun close() {
            closeCalls.incrementAndGet()
            closeLatch.countDown()
            closeThrow?.let { throw RuntimeException("close-boom") }
        }

        override fun initWithRequest(flow: String?, request: DroidGuardResultsRequest?): DroidGuardInitReply? = null
    }

    private fun apiClient() = DroidGuardApiClient(context, null, null)

    private fun decode(b64: String): String = String(Base64.decode(b64, Base64.URL_SAFE))

    @Test
    fun errorCtor_shortCircuitsToErrorBytes_urlSafeNoPad() {
        val impl = DroidGuardHandleImpl(apiClient(), DroidGuardResultsRequest(), "Initialization failed: nope")
        val encoded = impl.snapshot(emptyMap<String, String>())
        // URL_SAFE + NO_WRAP + NO_PADDING alphabet on the wire
        assertTrue(encoded.matches(Regex("[A-Za-z0-9_-]+")))
        assertFalse(encoded.contains('='))
        assertEquals("ERROR : Initialization failed: nope", decode(encoded))
        assertFalse(impl.isOpened)
    }

    @Test
    fun liveHandle_returnsPayload_isOpenedTrue() {
        val fake = FakeHandle()
        val impl = DroidGuardHandleImpl(apiClient(), DroidGuardResultsRequest(), fake)
        assertEquals("AQID", impl.snapshot(emptyMap<String, String>()))
        assertEquals(1, fake.snapshotCalls.get())
        assertTrue(impl.isOpened)
    }

    @Test
    fun nullResult_setsStickyError() {
        val fake = FakeHandle().apply { snapshotResult = null }
        val impl = DroidGuardHandleImpl(apiClient(), DroidGuardResultsRequest(), fake)
        assertEquals("ERROR : Received null", decode(impl.snapshot(emptyMap<String, String>())))
        // Sticky: the remote is not consulted again on retry.
        assertEquals("ERROR : Received null", decode(impl.snapshot(emptyMap<String, String>())))
        assertEquals(1, fake.snapshotCalls.get())
        assertFalse(impl.isOpened)
    }

    @Test
    fun throwingRemote_setsStickyError() {
        val fake = FakeHandle().apply { snapshotThrow = RuntimeException("inner") }
        val impl = DroidGuardHandleImpl(apiClient(), DroidGuardResultsRequest(), fake)
        val decoded = decode(impl.snapshot(emptyMap<String, String>()))
        assertTrue(decoded.startsWith("ERROR : Snapshot failed:"))
        assertTrue(decoded.contains("remote-boom"))
        assertEquals(1, fake.snapshotCalls.get())
        assertFalse(impl.isOpened)
    }

    @Test
    fun timeout_isNotSticky_andHandleStaysOpen() {
        val fake = FakeHandle().apply { snapshotSleepMs = 2000 }
        val request = DroidGuardResultsRequest().setTimeoutMillis(60)
        val impl = DroidGuardHandleImpl(apiClient(), request, fake)
        val decoded = decode(impl.snapshot(emptyMap<String, String>()))
        assertEquals("ERROR : Snapshot timeout: 60 ms", decoded)
        // Timeout does NOT set the sticky error field: handle still reports open.
        fake.snapshotSleepMs = 0
        assertTrue(impl.isOpened)
        // The remote runnable is still sleeping on the handler thread; its late
        // offer also lands in the (capacity-1) result queue. Wait for it to
        // finish before retrying so the retry is not racing the sleeper.
        Thread.sleep(2500)
        assertEquals("AQID", impl.snapshot(emptyMap<String, String>()))
        assertTrue(impl.isOpened)
    }

    @Test
    fun close_swallowsRemoteThrow_andMarksClosed() {
        val fake = FakeHandle().apply { closeThrow = RuntimeException("x") }
        val impl = DroidGuardHandleImpl(apiClient(), DroidGuardResultsRequest(), fake)
        impl.close()
        assertTrue(fake.closeLatch.await(10, TimeUnit.SECONDS))
        assertEquals(1, fake.closeCalls.get())
        assertFalse(impl.isOpened)
    }

    @Test
    fun openHandle_unconnectedClient_returnsErrorHandle() {
        val client = apiClient()
        val request = DroidGuardResultsRequest()
        val impl = client.openHandle("testFlow", request)
        assertNotNull(impl)
        val decoded = decode(impl.snapshot(emptyMap<String, String>()))
        assertTrue(decoded.startsWith("ERROR : Initialization failed:"))
        assertTrue(decoded.contains("interface only available once connected"))
        // The catch-all path returns a closed handle; it never reaches
        // openHandles bookkeeping.
        assertFalse(impl.isOpened)
    }
}
