/*
 * SPDX-License-Identifier: Apache-2.0
 * Validation-only harness: no downloaded VM, live service, or device execution.
 */

package org.microg.gms.droidguard.core

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.os.Process
import android.util.Base64
import com.google.android.gms.droidguard.internal.DroidGuardResultsRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.microg.gms.droidguard.HandleProxy
import org.microg.gms.settings.SettingsContract
import org.mockito.Answers
import org.mockito.Mockito
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.android.controller.ContentProviderController
import org.robolectric.shadows.ShadowBinder
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real ContentResolver/preferences calls remain available to the store's worker threads. */
class InitReplyTestSettingsProvider : ContentProvider() {
    val queryThreads = ConcurrentLinkedQueue<String>()

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        check(uri.path == "/${SettingsContract.DroidGuard.ID}")
        val columns = requireNotNull(projection)
        queryThreads.add(Thread.currentThread().name)
        val values = columns.map { column ->
            when (column) {
                SettingsContract.DroidGuard.ENABLED -> 1
                SettingsContract.DroidGuard.FORCE_LOCAL_DISABLED -> 0
                SettingsContract.DroidGuard.MODE -> "Embedded"
                SettingsContract.DroidGuard.HARDWARE_ATTESTATION_BLOCKED -> 0
                else -> error("Unexpected settings projection: $column")
            }
        }.toTypedArray()
        return MatrixCursor(columns).apply { addRow(values) }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Test settings are read-only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        error("Test settings are read-only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<out String>?): Int = error("Test settings are read-only")
}

/** These public methods are invoked by the unmodified, reflection-based HandleProxy/core. */
class InitReplyRecordingVm(
    val reply: Parcelable? = null,
    private val initResult: Boolean = true,
    private val rbFailure: Boolean = false
) {
    val initCalls = AtomicInteger()
    val rbCalls = AtomicInteger()
    val snapshotCalls = AtomicInteger()
    val closeCalls = AtomicInteger()
    val snapshots = ConcurrentLinkedQueue<Map<Any?, Any?>>()
    val closed = CountDownLatch(1)

    fun init(): Boolean {
        initCalls.incrementAndGet()
        return initResult
    }

    fun rb(): Parcelable? {
        rbCalls.incrementAndGet()
        if (rbFailure) throw IllegalStateException("controlled rb failure")
        return reply
    }

    fun ss(data: Map<Any?, Any?>): ByteArray {
        snapshots.add(LinkedHashMap(data))
        return "controlled-result-${snapshotCalls.incrementAndGet()}".toByteArray(Charsets.UTF_8)
    }

    fun close() {
        closeCalls.incrementAndGet()
        closed.countDown()
    }
}

abstract class DroidGuardSessionProviderTestHarness {
    protected lateinit var context: Application
    protected lateinit var settings: InitReplyTestSettingsProvider
    protected lateinit var provider: DroidGuardSessionProvider
    protected lateinit var factory: NetworkHandleProxyFactory
    protected lateinit var apk: File
    protected var vm = InitReplyRecordingVm()
    protected val factoryCalls = AtomicInteger()
    protected val apkCalls = AtomicInteger()
    private lateinit var settingsController: ContentProviderController<InitReplyTestSettingsProvider>
    private lateinit var providerController: ContentProviderController<DroidGuardSessionProvider>

    @Before
    fun attachRealProviders() {
        context = RuntimeEnvironment.getApplication()
        ShadowBinder.setCallingUid(Process.myUid())
        Shadows.shadowOf(context).grantPermissions(Manifest.permission.DUMP)
        assertEquals(PackageManager.PERMISSION_GRANTED,
            context.checkCallingOrSelfPermission(Manifest.permission.DUMP))
        settingsController = Robolectric.buildContentProvider(InitReplyTestSettingsProvider::class.java)
            .create(SettingsContract.getAuthority(context))
        settings = settingsController.get()
        assertTrue(DroidGuardPreferences.isLocalAvailable(context))
        assertFalse(DroidGuardPreferences.isHardwareAttestationBlocked(context))
        apk = File.createTempFile("controlled-init-reply-", ".apk", context.cacheDir)
        apk.writeBytes(byteArrayOf(1, 2, 3)) // Never loaded as executable code.
        assertEquals(0, openApkDescriptors())
        factory = Mockito.mock(NetworkHandleProxyFactory::class.java) { invocation ->
            when (invocation.method.name) {
                "createHandle" -> {
                    assertEquals(SOURCE, invocation.arguments[0])
                    assertEquals(FLOW, invocation.arguments[1])
                    val request = invocation.arguments[3] as DroidGuardResultsRequest
                    assertEquals("test-scalar", request.bundle.getString("testParameter"))
                    factoryCalls.incrementAndGet()
                    HandleProxy(vm, "controlled-test-vm")
                }
                "getTheApkFile" -> {
                    assertEquals("controlled-test-vm", invocation.arguments[0])
                    apkCalls.incrementAndGet()
                    apk
                }
                "toString", "hashCode", "equals" -> Answers.RETURNS_DEFAULTS.answer(invocation)
                else -> error("Unexpected factory operation: ${invocation.method.name}")
            }
        }
        providerController = Robolectric.buildContentProvider(DroidGuardSessionProvider::class.java)
            .create(context.packageName + ".controlled.droidguard.session")
        provider = providerController.get()
        // Only the native factory boundary is controlled; provider/core/store remain unchanged.
        DroidGuardSessionProvider::class.java.getDeclaredField("factory\$delegate").apply {
            isAccessible = true
            set(provider, lazy { factory })
        }
    }

    @After
    fun tearDownRealProviders() {
        if (::providerController.isInitialized) {
            providerController.shutdown()
            // A completed test must not leave its native VM or session worker cleanup pending.
            awaitStoreCapacity()
        }
        if (::settingsController.isInitialized) settingsController.shutdown()
        if (::apk.isInitialized) {
            assertEquals("Controlled APK descriptor leaked", 0, openApkDescriptors())
            apk.delete()
        }
        ShadowBinder.reset()
    }

    protected fun begin(): JSONObject = callProvider("begin", JSONObject()
        .put("flow", FLOW).put("source", SOURCE)
        .put("request", JSONObject().put("testParameter", "test-scalar")))

    protected fun callProvider(method: String, input: JSONObject): JSONObject {
        val arg = Base64.encodeToString(input.toString().toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val encoded = requireNotNull(provider.call(method, arg, null).getString("response"))
        return JSONObject(String(Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8))
    }

    protected fun assertRejectedWithoutId(response: JSONObject, expectedMessage: String? = null) {
        assertEquals("error", response.getString("status"))
        assertEquals(502, response.getInt("code"))
        assertFalse("Failed begin must never expose a session ID", response.has("sessionId"))
        if (expectedMessage != null) assertEquals(expectedMessage, response.getString("error"))
        assertEquals(0, vm.snapshotCalls.get())
        assertTrue("Actual settings query did not run on the store worker",
            settings.queryThreads.any { it.startsWith("DroidGuardSession") })
        awaitStoreCapacity()
        assertVmClosedOnce()
        assertEquals("No failed session should remain registered", 0, retainedSessionCount())
    }

    protected fun assertVmClosedOnce() {
        assertTrue("VM cleanup did not finish", vm.closed.await(2, TimeUnit.SECONDS))
        assertEquals(1, vm.closeCalls.get())
    }

    protected fun openApkDescriptors(): Int {
        val directory = File("/proc/self/fd")
        assertTrue("This controlled-descriptor check requires Linux /proc/self/fd", directory.isDirectory)
        val expected = apk.canonicalPath
        return requireNotNull(directory.listFiles()).count { descriptor ->
            runCatching { Files.readSymbolicLink(descriptor.toPath()).toString() }
                .getOrNull() == expected
        }
    }

    protected fun awaitStoreCapacity() {
        val capacity = DroidGuardSessionStore::class.java.getDeclaredField("capacity").apply {
            isAccessible = true
        }.get(realStore()) as Semaphore
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (capacity.availablePermits() != 8 && System.nanoTime() < deadline) Thread.yield()
        assertEquals("Session cleanup did not release its actual capacity", 8, capacity.availablePermits())
    }

    private fun retainedSessionCount(): Int {
        val sessions = DroidGuardSessionStore::class.java.getDeclaredField("sessions").apply {
            isAccessible = true
        }.get(realStore()) as Map<*, *>
        return synchronized(sessions) { sessions.size }
    }

    private fun realStore(): DroidGuardSessionStore =
        DroidGuardSessionProvider::class.java.getDeclaredField("sessions").apply {
            isAccessible = true
        }.get(provider) as DroidGuardSessionStore

    companion object {
        const val FLOW = "controlled-test-flow"
        const val SOURCE = "org.microg.controlled.test"
        const val UNSUPPORTED = "Caller-side DroidGuard initialization is not supported by remote sessions"
    }
}
