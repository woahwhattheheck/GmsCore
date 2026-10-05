/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.droidguard.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Process
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import java.io.FileDescriptor
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the ServiceCallProxy dump-gate contract against a real instrumented
 * android.os.ServiceManager under Robolectric:
 *
 *  - maySetBlockDumpForService ENABLES dump-blocking when the app lacks the
 *    DUMP permission, and DISABLES it when the permission is granted.
 *  - setBlockDumpForService installs a java.lang.reflect.Proxy over the
 *    service's real sCache entry: dump() is swallowed, every other call
 *    delegates to the original binder.
 *  - disable restores the ORIGINAL binder (not a proxy of a proxy), an
 *    absent service fails soft, and disabling a never-enabled service is a
 *    no-op.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class ServiceCallProxyDumpGateTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private class FlagBinder : Binder() {
        var dumpCalled = false
        var pingCount = 0

        override fun dump(fd: FileDescriptor, fout: PrintWriter, args: Array<out String>?) {
            dumpCalled = true
            fout.print(REAL_DUMP_MARKER)
            fout.flush()
        }

        override fun pingBinder(): Boolean {
            pingCount++
            return true
        }
    }

    private lateinit var smClass: Class<*>
    private lateinit var cache: MutableMap<String, IBinder?>
    private lateinit var fake: FlagBinder

    @Before
    fun registerFakeService() {
        smClass = Class.forName("android.os.ServiceManager")
        val cacheField = smClass.getDeclaredField("sCache").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        cache = cacheField.get(null) as MutableMap<String, IBinder?>
        fake = FlagBinder()
        val addService: Method =
            smClass.getDeclaredMethod("addService", String::class.java, IBinder::class.java)
        addService.isAccessible = true
        addService.invoke(null, FAKE_SERVICE, fake)
    }

    @After
    fun restoreServiceManager() {
        // Disable any proxy this test installed, then evict the fake entry so no
        // proxyEnabled/originalServices state leaks into the next test.
        ServiceCallProxy.setBlockDumpForService(FAKE_SERVICE, false)
        ServiceCallProxy.setBlockDumpForService(SEEDED_SERVICE, false)
        cache.remove(FAKE_SERVICE)
    }

    @Test
    fun enableInstallsProxyThatBlocksDump() {
        ServiceCallProxy.setBlockDumpForService(FAKE_SERVICE, true)

        val installed = cache[FAKE_SERVICE]
        assertTrue("expected a dynamic proxy in sCache", Proxy.isProxyClass(installed!!.javaClass))

        val out = StringWriter()
        IBinder::class.java
            .getMethod("dump", FileDescriptor::class.java, PrintWriter::class.java, Array<String>::class.java)
            .invoke(installed, FileDescriptor(), PrintWriter(out), arrayOf<String>())

        assertFalse("dump() must not reach the original binder", fake.dumpCalled)
        assertEquals("", out.toString())
    }

    @Test
    fun proxyDelegatesNonDumpCallsToOriginal() {
        ServiceCallProxy.setBlockDumpForService(FAKE_SERVICE, true)
        val installed = cache[FAKE_SERVICE]!!

        assertTrue(installed.pingBinder())
        assertEquals(1, fake.pingCount)
    }

    @Test
    fun disableRestoresOriginalBinder() {
        ServiceCallProxy.setBlockDumpForService(FAKE_SERVICE, true)
        ServiceCallProxy.setBlockDumpForService(FAKE_SERVICE, false)

        assertSame(fake, cache[FAKE_SERVICE])
        val out = StringWriter()
        cache[FAKE_SERVICE]!!.dump(FileDescriptor(), PrintWriter(out), arrayOf<String>())
        assertTrue(fake.dumpCalled)
        assertEquals(REAL_DUMP_MARKER, out.toString())
    }

    @Test
    fun enableTwiceThenDisableStillRestoresOriginal() {
        // Without the proxyEnabled idempotence guard the second enable would
        // re-wrap the installed proxy and record IT as the "original", so a
        // later disable would leave a proxy in place. This pins the guard.
        ServiceCallProxy.setBlockDumpForService(FAKE_SERVICE, true)
        ServiceCallProxy.setBlockDumpForService(FAKE_SERVICE, true)
        ServiceCallProxy.setBlockDumpForService(FAKE_SERVICE, false)

        assertSame(fake, cache[FAKE_SERVICE])
    }

    @Test
    fun disableWithoutPriorEnableIsNoop() {
        val before = cache[FAKE_SERVICE]
        ServiceCallProxy.setBlockDumpForService(FAKE_SERVICE, false)
        assertSame(before, cache[FAKE_SERVICE])
    }

    @Test
    fun enableOnAbsentServiceFailsSoft() {
        val missing = "dg_absent_service_zz"
        ServiceCallProxy.setBlockDumpForService(missing, true)
        assertNull(cache[missing])
        // Second call must not throw either.
        ServiceCallProxy.setBlockDumpForService(missing, true)
    }

    @Test
    fun deniedDumpPermissionEnablesBlocking() {
        // The module manifest declares android.permission.DUMP so the shadow
        // grants it by default; revoke it for this test through the real
        // (hidden) PackageManager API the shadow implements.
        val pm = context.packageManager
        val revoke = pm.javaClass.getMethod(
            "revokeRuntimePermission",
            String::class.java, String::class.java, android.os.UserHandle::class.java
        )
        revoke.invoke(pm, context.packageName, Manifest.permission.DUMP, Process.myUserHandle())
        assertEquals(
            PackageManager.PERMISSION_DENIED,
            ContextCompat.checkSelfPermission(context, Manifest.permission.DUMP)
        )

        ServiceCallProxy.maySetBlockDumpForService(context, SEEDED_SERVICE)

        assertTrue(
            "denied DUMP permission should install the dump-blocking proxy",
            Proxy.isProxyClass(cache[SEEDED_SERVICE]!!.javaClass)
        )
    }

    @Test
    fun grantedDumpPermissionLeavesOriginalService() {
        // DUMP is declared in the module manifest, so the default check is
        // PERMISSION_GRANTED and maySetBlockDumpForService takes the disable arm.
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            ContextCompat.checkSelfPermission(context, Manifest.permission.DUMP)
        )
        val before = cache[SEEDED_SERVICE]

        ServiceCallProxy.maySetBlockDumpForService(context, SEEDED_SERVICE)

        assertSame(before, cache[SEEDED_SERVICE])
    }

    private companion object {
        const val FAKE_SERVICE = "dg_dump_gate_test"
        const val SEEDED_SERVICE = "audio"
        const val REAL_DUMP_MARKER = "REAL_DUMP_REACHED"
    }
}
