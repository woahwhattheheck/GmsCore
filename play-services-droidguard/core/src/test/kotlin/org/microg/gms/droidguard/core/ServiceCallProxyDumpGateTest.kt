/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.droidguard.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileDescriptor
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow

/**
 * Pins the ServiceCallProxy dump-gate contract against a real instrumented
 * android.os.ServiceManager under Robolectric, using a service name that the
 * ShadowServiceManager pre-seeds (SEEDED_SERVICE):
 *
 *  - enable writes a java.lang.reflect.Proxy into the real ServiceManager
 *    sCache entry for the service;
 *  - the proxy's invocation handler swallows any method named "dump" (returns
 *    null) and delegates every other call to the original binder;
 *  - disable restores the exact original binder instance;
 *  - the proxyEnabled idempotence guard keeps a double-enable from capturing
 *    the proxy itself as the "original";
 *  - a service getService() cannot resolve fails soft (no proxy, no throw);
 *  - maySetBlockDumpForService ENABLES blocking when the app lacks DUMP
 *    and DISABLES it once the permission is granted.
 *
 * Harness boundary pinned here: the shadow's getService() reads only its own
 * binderServices map, so an installed sCache proxy is not observable through
 * getService() under Robolectric - on a real device sCache is the lookup path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class ServiceCallProxyDumpGateTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var smClass: Class<*>
    private lateinit var cache: MutableMap<String, IBinder?>
    private lateinit var getService: Method
    private lateinit var dumpMethod: Method
    private lateinit var pingMethod: Method

    private fun serviceManagerGetService(name: String): IBinder? =
        getService.invoke(null, name) as IBinder?

    @Before
    fun resolveServiceManagerInternals() {
        smClass = Class.forName("android.os.ServiceManager")
        getService = smClass.getDeclaredMethod("getService", String::class.java)
            .apply { isAccessible = true }
        val cacheField = smClass.getDeclaredField("sCache").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        cache = cacheField.get(null) as MutableMap<String, IBinder?>
        dumpMethod =
            IBinder::class.java.getMethod("dump", FileDescriptor::class.java, Array<String>::class.java)
        pingMethod = IBinder::class.java.getMethod("pingBinder")
    }

    @After
    fun restoreServiceManager() {
        ServiceCallProxy.setBlockDumpForService(SEEDED_SERVICE, false)
        cache.remove(SEEDED_SERVICE)
    }

    // ShadowInstrumentation.grantPermissions/denyPermissions are the
    // package-private test hooks behind Context.checkPermission; reach them
    // through the same reflection the production code uses on ServiceManager.
    private fun shadowInstrumentationPermission(method: String, vararg permissions: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val shadow = Shadow.extract<Any>(instrumentation)
        val m = shadow.javaClass.getDeclaredMethod(method, Array<String>::class.java)
        m.isAccessible = true
        m.invoke(shadow, arrayOf(*permissions) as Any)
    }

    @Test
    fun enableInstallsProxyIntoServiceCache() {
        val original = serviceManagerGetService(SEEDED_SERVICE)
        assertTrue("seeded service should resolve", original != null)

        ServiceCallProxy.setBlockDumpForService(SEEDED_SERVICE, true)

        val installed = cache[SEEDED_SERVICE]
        assertTrue("expected a dynamic proxy in sCache", installed != null && Proxy.isProxyClass(installed.javaClass))
        assertNotSame(original, installed)
    }

    @Test
    fun proxyHandlerSwallowsDumpAndDelegatesRest() {
        ServiceCallProxy.setBlockDumpForService(SEEDED_SERVICE, true)
        val installed = cache[SEEDED_SERVICE]!!
        val handler = Proxy.getInvocationHandler(installed)

        val dumpResult = handler.invoke(installed, dumpMethod, arrayOf(FileDescriptor(), arrayOf<String>()))
        assertNull("dump() must be swallowed, not delegated", dumpResult)

        val pingResult = handler.invoke(installed, pingMethod, emptyArray<Any?>())
        assertEquals("pingBinder() must delegate to the original", true, pingResult)
    }

    @Test
    fun disableRestoresOriginalBinder() {
        val original = serviceManagerGetService(SEEDED_SERVICE)
        ServiceCallProxy.setBlockDumpForService(SEEDED_SERVICE, true)
        assertTrue(Proxy.isProxyClass(cache[SEEDED_SERVICE]!!.javaClass))

        ServiceCallProxy.setBlockDumpForService(SEEDED_SERVICE, false)

        assertSame(original, cache[SEEDED_SERVICE])
        assertFalse(Proxy.isProxyClass(cache[SEEDED_SERVICE]!!.javaClass))
    }

    @Test
    fun enableTwiceThenDisableStillRestoresOriginal() {
        // Without the proxyEnabled guard the second enable would re-wrap the
        // installed proxy and record IT as the "original"; disable would then
        // leave a proxy permanently installed. This pins the guard.
        val original = serviceManagerGetService(SEEDED_SERVICE)
        ServiceCallProxy.setBlockDumpForService(SEEDED_SERVICE, true)
        val proxy = cache[SEEDED_SERVICE]
        ServiceCallProxy.setBlockDumpForService(SEEDED_SERVICE, true)
        assertSame("second enable must be a no-op", proxy, cache[SEEDED_SERVICE])

        ServiceCallProxy.setBlockDumpForService(SEEDED_SERVICE, false)
        assertSame(original, cache[SEEDED_SERVICE])
    }

    @Test
    fun disableWithoutPriorEnableIsNoop() {
        cache.remove(SEEDED_SERVICE)
        ServiceCallProxy.setBlockDumpForService(SEEDED_SERVICE, false)
        assertNull(cache[SEEDED_SERVICE])
    }

    @Test
    fun enableOnAbsentServiceFailsSoft() {
        val missing = "dg_absent_service_zz"
        ServiceCallProxy.setBlockDumpForService(missing, true)
        assertNull(cache[missing])
        ServiceCallProxy.setBlockDumpForService(missing, true) // still no throw
    }

    @Test
    fun deniedDumpPermissionEnablesBlocking() {
        // Denied explicitly through the shadow's permission map so this does
        // not depend on the default grant state of a signature permission.
        shadowInstrumentationPermission("denyPermissions", Manifest.permission.DUMP)
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
        shadowInstrumentationPermission("grantPermissions", Manifest.permission.DUMP)
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            ContextCompat.checkSelfPermission(context, Manifest.permission.DUMP)
        )

        ServiceCallProxy.maySetBlockDumpForService(context, SEEDED_SERVICE)

        assertNull("granted DUMP must leave sCache untouched", cache[SEEDED_SERVICE])
    }

    private companion object {
        const val SEEDED_SERVICE = "audio"
    }
}
