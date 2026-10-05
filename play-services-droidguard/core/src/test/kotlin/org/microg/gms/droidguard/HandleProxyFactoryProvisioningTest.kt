/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.droidguard

import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.chainhelper.FakeProvisionVm
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Pins the VM-provisioning contract of the API-level HandleProxyFactory
 * (:play-services-droidguard, reachable via core's api project dependency —
 * same cross-module pattern as DroidGuardClientHandleSemanticsTest; this test
 * needs the real Context/ParcelFileDescriptor shadows the non-core module's
 * JUnit-only classpath cannot run):
 *  - cache layout: <context dir>/dg_cache/<vmKey>/the.apk + opt/ dir; the
 *    deprecated CACHE_FOLDER_NAME alias still resolves to "dg_cache"
 *  - isValidCache needs BOTH the.apk file and opt/ directory
 *  - loadClass on an absent/invalid cache throws BytesException carrying the
 *    caller's payload bytes verbatim plus "VM key <vmKey> not found in cache"
 *  - a class-map hit short-circuits BEFORE the signature check (pinned via a
 *    reflectively-primed CLASS_MAP), but still requires a writable cache dir
 *    for the 't' timestamp side file
 *  - a valid-looking cache holding a bogus apk fails signature verification:
 *    ClassNotFoundException("APK signature verification failed") and the whole
 *    vmKey cache directory is deleted
 *  - createHandle provisions the.apk byte-exact from the supplied
 *    ParcelFileDescriptor and materializes opt/ + t before class resolution
 *
 * JVM-harness boundary (documented, not asserted): the positive DexClassLoader
 * load path needs real dex; it is not exercised here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HandleProxyFactoryProvisioningTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // isValidCache/loadClass are protected on the open class — expose via probe.
    private class ProbeFactory(context: Context) : HandleProxyFactory(context) {
        fun validCache(key: String) = isValidCache(key)
        fun load(key: String, bytes: ByteArray = ByteArray(0)) = loadClass(key, bytes)
    }

    private val factory = ProbeFactory(context)

    @Suppress("unchecked_cast")
    private fun classMap(): MutableMap<String, Class<*>> {
        val outer = runCatching {
            HandleProxyFactory::class.java.getDeclaredField("CLASS_MAP")
                .let { it.isAccessible = true; it.get(null) as MutableMap<String, Class<*>> }
        }
        return outer.getOrElse {
            Class.forName("org.microg.gms.droidguard.HandleProxyFactory\$Companion")
                .getDeclaredField("CLASS_MAP")
                .let { it.isAccessible = true; it.get(HandleProxyFactory.Companion) as MutableMap<String, Class<*>> }
        }
    }

    private fun cacheDirFor(key: String) =
        File(context.getDir(DG_CACHE_FOLDER_NAME, Context.MODE_PRIVATE), key)

    private fun pfdOf(bytes: ByteArray): ParcelFileDescriptor {
        val f = File.createTempFile("dgf", ".bin")
        f.writeBytes(bytes)
        f.deleteOnExit()
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    @After
    fun tearDown() {
        context.getDir(DG_CACHE_FOLDER_NAME, Context.MODE_PRIVATE)
            .listFiles()?.forEach { it.deleteRecursively() }
        classMap().clear()
    }

    @Test
    fun cacheLayout_isValidCache_needsApkAndOptDir() {
        val key = "LAYOUT1"
        val dir = cacheDirFor(key)
        assertEquals(File(dir, "the.apk"), factory.getTheApkFile(key))
        @Suppress("DEPRECATION")
        assertEquals("dg_cache", HandleProxyFactory.CACHE_FOLDER_NAME)
        assertFalse(factory.validCache(key))
        dir.mkdirs()
        File(dir, "the.apk").writeBytes(byteArrayOf(1, 2, 3))
        assertFalse(factory.validCache(key))
        File(dir, "the.apk").delete()
        File(dir, "opt").mkdirs()
        assertFalse(factory.validCache(key))
        File(dir, "the.apk").writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(factory.validCache(key))
    }

    @Test
    fun loadClass_absentCache_throwsBytesExceptionCarryingPayload() {
        val payload = byteArrayOf(9, 8, 7)
        try {
            factory.load("NO_SUCH_KEY", payload)
            fail("expected BytesException")
        } catch (e: BytesException) {
            assertSame(payload, e.bytes)
            assertEquals("VM key NO_SUCH_KEY not found in cache", e.message)
        }
    }

    @Test
    fun loadClass_primedClassMap_missingCacheDir_throwsTimestampException() {
        classMap()["PRIMED0"] = FakeProvisionVm::class.java
        // Cache-hit still updates the 't' timestamp; with no cache dir the
        // createNewFile fails and a plain Exception propagates.
        try {
            factory.load("PRIMED0")
            fail("expected timestamp Exception")
        } catch (e: Exception) {
            assertEquals("Failed to touch last-used file for PRIMED0.", e.message)
        }
    }

    @Test
    fun loadClass_primedClassMap_returnsCachedClassAndTouchesTimestamp() {
        val key = "PRIMED1"
        cacheDirFor(key).mkdirs()
        classMap()[key] = FakeProvisionVm::class.java
        assertSame(FakeProvisionVm::class.java, factory.load(key))
        assertTrue(File(cacheDirFor(key), "t").isFile)
    }

    @Test
    fun loadClass_bogusApk_failsSignatureVerify_andDeletesCache() {
        val key = "BOGUS01"
        val dir = cacheDirFor(key)
        dir.mkdirs()
        File(dir, "the.apk").writeBytes(byteArrayOf(0x50, 0x4B, 1, 2, 3, 4))
        File(dir, "opt").mkdirs()
        assertTrue(factory.validCache(key))
        try {
            factory.load(key)
            fail("expected ClassNotFoundException")
        } catch (e: ClassNotFoundException) {
            assertEquals("APK signature verification failed", e.message)
        }
        assertFalse(dir.exists())
    }

    @Test
    fun createHandle_primedClassMap_provisionsApk_skipsSignatureVerify() {
        val key = "PRIMED2"
        val vmBytes = byteArrayOf(0x55, 0x66, 0x77, 0x00, 0x11)
        classMap()[key] = FakeProvisionVm::class.java
        val proxy = factory.createHandle(key, pfdOf(vmBytes), Bundle())
        assertSame(FakeProvisionVm::class.java, proxy.handle.javaClass)
        assertEquals(key, proxy.vmKey)
        assertArrayEquals(ByteArray(0), proxy.extra)
        // fetchFromFileDescriptor materialized the apk verbatim + opt dir.
        val dir = cacheDirFor(key)
        assertArrayEquals(vmBytes, File(dir, "the.apk").readBytes())
        assertTrue(File(dir, "opt").isDirectory)
        assertTrue(File(dir, "t").isFile)
    }

    @Test
    fun createHandle_unprimed_provisionsThenFailsOnSignature() {
        val key = "BOGUS02"
        val vmBytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x55)
        try {
            factory.createHandle(key, pfdOf(vmBytes), Bundle())
            fail("expected ClassNotFoundException")
        } catch (e: ClassNotFoundException) {
            assertEquals("APK signature verification failed", e.message)
        }
        // Provisioning ran (BytesException would mean the cache shortcut hit);
        // the failed-verify cleanup removed the whole key directory.
        assertFalse(cacheDirFor(key).exists())
    }
}
