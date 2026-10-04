/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.droidguard.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FallbackCreatorContractTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun create_fastFailFlow_returnsNoFallbackMessage() {
        val bytes = FallbackCreator.create("ad_attest", context, emptyMap(), IllegalStateException("boom"))
        assertEquals("ERROR : no fallback for ad_attest", bytes.decodeToString())
    }

    @Test
    fun create_normalFlow_includesErrorMessage() {
        val bytes = FallbackCreator.create("test", context, emptyMap(), IllegalStateException("boom"))
        assertEquals("ERROR : boom", bytes.decodeToString())
    }

    @Test
    fun create_nullFlow_notFastFail_usesErrorMessage() {
        val bytes = FallbackCreator.create(null, context, emptyMap(), IllegalStateException("boom"))
        assertEquals("ERROR : boom", bytes.decodeToString())
    }

    @Test
    fun create_nullError_returnsFallbackNotAvailable() {
        // GuardCallback.a calls this overload with e=null; it must emit error bytes
        // instead of throwing on the null parameter.
        val bytes = FallbackCreator.create(emptyMap(), null, "test", context, null)
        assertEquals("ERROR : fallback not available", bytes.decodeToString())
    }
}
