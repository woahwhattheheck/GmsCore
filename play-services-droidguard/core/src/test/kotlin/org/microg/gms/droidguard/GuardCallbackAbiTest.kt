/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.droidguard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.settings.SettingsContract
import org.microg.gms.settings.SettingsProvider
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the GuardCallback ABI — the Java methods the DroidGuard VM invokes by
 * name; the signatures and names are load-bearing and must not drift:
 *  - a(byte[]) funnels arbitrary VM request payloads through
 *    FallbackCreator.create(map, bytes, "", context, null) and returns the
 *    deterministic error string "ERROR : fallback not available" — the VM
 *    receives error bytes instead of a host-side exception
 *  - b() returns the CheckIn ANDROID_ID as a decimal string when the settings
 *    provider serves it, and falls back to a parseable non-negative random
 *    long when the settings resolver cannot answer (Throwable is swallowed)
 *  - c() echoes the constructor packageName verbatim
 *  - d(mediaDrm, sessionId) performs an unchecked cast to MediaDrm — a
 *    non-MediaDrm argument propagates ClassCastException (no guard)
 *  - e(task) is a silent no-op for every task value (all bodies are TODO)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GuardCallbackAbiTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun a_returnsDeterministicFallbackErrorString() {
        val cb = GuardCallback(context, "com.example.dg")
        assertEquals("ERROR : fallback not available", cb.a(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun a_payloadContentsDoNotAlterErrorContract() {
        val cb = GuardCallback(context, "com.example.dg")
        assertEquals(cb.a(byteArrayOf()), cb.a(ByteArray(256) { it.toByte() }))
    }

    @Test
    fun b_storedAndroidId_returnedAsDecimalString() {
        context.getSharedPreferences(SettingsContract.CheckIn.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().putLong(SettingsContract.CheckIn.ANDROID_ID, 1337L).commit()
        Robolectric.setupContentProvider(
            SettingsProvider::class.java, SettingsContract.getAuthority(context)
        )
        assertEquals("1337", GuardCallback(context, "com.example.dg").b())
    }

    @Test
    fun b_noProvider_fallsBackToParseableLong() {
        // No provider registered for the settings authority -> query fails,
        // Throwable swallowed -> random non-negative long.
        val b = GuardCallback(context, "com.example.dg").b()
        assertTrue(b.toLong() >= 0L)
    }

    @Test
    fun c_returnsConstructorPackageName() {
        assertEquals(
            "com.example.verifier",
            GuardCallback(context, "com.example.verifier").c()
        )
    }

    @Test
    fun d_nonMediaDrmArgument_throwsClassCastException() {
        val cb = GuardCallback(context, "com.example.dg")
        assertThrows(ClassCastException::class.java) {
            cb.d(Any(), byteArrayOf(9, 9, 9))
        }
    }

    @Test
    fun e_anyTaskValue_returnsNormally() {
        val cb = GuardCallback(context, "com.example.dg")
        cb.e(0)
        cb.e(1)
        cb.e(99)
    }
}
