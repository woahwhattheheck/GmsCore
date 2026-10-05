/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package com.google.android.gms.framework.tracing.wrapper

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.droidguard.core.VersionUtil
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Pins the PackageManager-spoofing rail TracingIntentService installs for the
 * DroidGuard VM: getPackageManager() hands back a PackageManagerWrapper that
 * rewrites com.google.android.gms package info to look like stock GMS —
 * versionCode/versionName from VersionUtil and the com.google.uid.shared
 * sharedUserId — while every other package passes through unmodified.
 *  - onHandleIntent forwards to the abstract a(Intent) rail verbatim,
 *    including a null intent
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TracingIntentServicePackageSpoofTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private class ProbeService : TracingIntentService("probe") {
        val received = mutableListOf<Intent?>()
        override fun a(intent: Intent?) {
            received.add(intent)
        }
    }

    private fun newService(): ProbeService {
        val svc = ProbeService()
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            .apply { isAccessible = true }.invoke(svc, context)
        return svc
    }

    private fun installGmsPackage() {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = "com.google.android.gms"
            versionCode = 1
            versionName = "stock"
            applicationInfo = ApplicationInfo().apply {
                packageName = "com.google.android.gms"
            }
        })
    }

    @Test
    fun getPackageManager_rewritesGmsPackageInfo() {
        installGmsPackage()
        val svc = newService()
        val info = svc.packageManager.getPackageInfo("com.google.android.gms", 0)
        assertEquals("com.google.uid.shared", info.sharedUserId)
        assertEquals(VersionUtil(context).versionCode, info.versionCode)
        assertEquals(VersionUtil(context).versionString, info.versionName)
        assertNotEquals("stock", info.versionName)
        assertNotEquals(1, info.versionCode)
    }

    @Test
    fun getPackageManager_nonGmsPackage_passesThroughUnmodified() {
        val svc = newService()
        val info = svc.packageManager.getPackageInfo(context.packageName, 0)
        assertEquals(context.packageName, info.packageName)
        assertNotEquals("com.google.uid.shared", info.sharedUserId)
    }

    @Test
    fun getPackageManager_unknownPackage_propagatesNameNotFound() {
        val svc = newService()
        assertThrows(PackageManager.NameNotFoundException::class.java) {
            svc.packageManager.getPackageInfo("com.does.not.exist.anywhere", 0)
        }
    }

    @Test
    fun onHandleIntent_forwardsToAbstractRail() {
        val svc = newService()
        val intent = Intent("probe.action")
        svc.onHandleIntent(intent)
        svc.onHandleIntent(null)
        assertSame(intent, svc.received[0])
        assertEquals(listOf(intent, null), svc.received)
        assertTrue(svc.received.size == 2)
    }
}
