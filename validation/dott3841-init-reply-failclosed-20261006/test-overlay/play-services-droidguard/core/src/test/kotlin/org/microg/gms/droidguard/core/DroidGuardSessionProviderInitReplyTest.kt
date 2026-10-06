/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.droidguard.core

import android.app.Application
import android.net.Uri
import android.os.Bundle
import com.google.android.gms.droidguard.internal.DroidGuardResultsRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.droidguard.GuardCallback
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual provider, core implementation, store, HandleProxy and controlled public VM methods. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class DroidGuardSessionProviderInitReplyTest : DroidGuardSessionProviderTestHarness() {
    @Test
    fun actualCoreReplyDescriptorProbeObservesOpenThenClosed() {
        vm = InitReplyRecordingVm(Bundle().apply { putString("h", "controlled-test-vm") })
        val handle = DroidGuardHandleImpl(context, SOURCE, factory, GuardCallback(context, SOURCE))
        val request = DroidGuardResultsRequest().apply { bundle.putString("testParameter", "test-scalar") }
        val reply = handle.initWithRequest(FLOW, request)
        try {
            assertTrue(handle.isReady())
            assertNotNull(reply.pfd)
            assertSame(vm.reply, reply.`object`)
            assertEquals("Probe must observe an actually open controlled descriptor", 1, openApkDescriptors())
        } finally {
            reply.pfd?.close()
            handle.close()
        }
        assertEquals(0, openApkDescriptors())
        assertEquals(1, apkCalls.get())
        assertEquals(1, vm.initCalls.get())
        assertEquals(1, vm.rbCalls.get())
        assertEquals(0, vm.snapshotCalls.get())
        assertVmClosedOnce()
    }

    @Test
    fun callerBundleReplyIsRejectedWithoutSessionIdAndDescriptorIsClosed() {
        vm = InitReplyRecordingVm(Bundle().apply { putString("h", "controlled-test-vm") })
        assertRejectedWithoutId(begin(), UNSUPPORTED)
        assertEquals(1, factoryCalls.get())
        assertEquals(1, apkCalls.get())
        assertEquals(1, vm.initCalls.get()) // No second/caller-side VM initialization.
        assertEquals(1, vm.rbCalls.get())
        assertEquals(0, openApkDescriptors())
    }

    @Test
    fun evenEmptyCallerBundleReplyIsRejected() {
        vm = InitReplyRecordingVm(Bundle())
        assertRejectedWithoutId(begin(), UNSUPPORTED)
        assertEquals(1, vm.initCalls.get())
        assertEquals(1, apkCalls.get())
        assertEquals(0, openApkDescriptors())
    }

    @Test
    fun nonBundleParcelableReplyIsRejected() {
        vm = InitReplyRecordingVm(Uri.parse("content://controlled.test/opaque"))
        assertRejectedWithoutId(begin(), UNSUPPORTED)
        assertEquals(1, vm.initCalls.get())
        assertEquals(1, apkCalls.get())
        assertEquals(0, openApkDescriptors())
    }

    @Test
    fun emptyReplyRetainsOneVmAcrossRepeatedSnapshotsAndClosesOnce() {
        vm = InitReplyRecordingVm()
        val opened = begin()
        assertEquals("ok", opened.getString("status"))
        val id = opened.getString("sessionId")
        assertTrue(id.isNotBlank())
        for (sequence in 1..2) {
            val response = callProvider("snapshot", JSONObject().put("sessionId", id)
                .put("data", JSONObject().put("sequence", sequence.toString())))
            assertEquals("ok", response.getString("status"))
            val bytes = android.util.Base64.decode(response.getString("result"),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
            assertEquals("controlled-result-$sequence", String(bytes, Charsets.UTF_8))
        }
        assertEquals(listOf(mapOf<Any?, Any?>("sequence" to "1"), mapOf<Any?, Any?>("sequence" to "2")),
            vm.snapshots.toList())
        assertEquals(1, factoryCalls.get())
        assertEquals(1, vm.initCalls.get())
        assertEquals(1, vm.rbCalls.get())
        assertEquals(2, vm.snapshotCalls.get())
        assertEquals(0, vm.closeCalls.get())
        assertEquals(0, apkCalls.get())
        assertEquals("ok", callProvider("close", JSONObject().put("sessionId", id)).getString("status"))
        awaitStoreCapacity()
        assertVmClosedOnce()
        val retired = callProvider("snapshot", JSONObject().put("sessionId", id))
        assertEquals("error", retired.getString("status"))
        assertEquals(404, retired.getInt("code"))
        assertEquals("ok", callProvider("close", JSONObject().put("sessionId", id)).getString("status"))
        assertEquals(1, vm.closeCalls.get())
    }

    @Test
    fun nativeInitFalseReturnsNoIdAndClosesFailedVmOnce() {
        vm = InitReplyRecordingVm(initResult = false)
        assertRejectedWithoutId(begin(), "Native DroidGuard initialization failed")
        assertEquals(1, vm.initCalls.get())
        assertEquals(0, vm.rbCalls.get())
        assertEquals(0, apkCalls.get())
    }

    @Test
    fun nativeRbThrowsReturnsNoIdAndClosesFailedVmOnce() {
        vm = InitReplyRecordingVm(rbFailure = true)
        assertRejectedWithoutId(begin(), "Native DroidGuard initialization failed")
        assertEquals(1, vm.initCalls.get())
        assertEquals(1, vm.rbCalls.get())
        assertEquals(0, apkCalls.get())
    }

    @Test
    fun actualCoreDescriptorOpenFailureReturnsNoIdAndClosesVmOnce() {
        vm = InitReplyRecordingVm(Bundle())
        assertTrue(apk.delete())
        assertRejectedWithoutId(begin(), "Native DroidGuard initialization failed")
        assertEquals(1, apkCalls.get())
        assertEquals(1, vm.initCalls.get())
        assertEquals(1, vm.rbCalls.get())
        assertEquals(0, openApkDescriptors())
    }
}
