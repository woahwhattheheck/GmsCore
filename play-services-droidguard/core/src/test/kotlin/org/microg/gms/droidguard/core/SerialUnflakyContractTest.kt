/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.droidguard.core

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins SerialUnflaky's contract: it exists purely to pre-fire the flaky
 * Build.getSerial() so DroidGuard sees a consistent result — the whole
 * contract is that fetch() never propagates anything, whatever
 * Build.getSerial() does (success, exception, differing results between the
 * two internal reads) and below SDK 26 it must not touch getSerial at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SerialUnflakyContractTest {

    @Test
    fun fetch_neverThrows_andIsIdempotent() {
        SerialUnflaky.fetch()
        SerialUnflaky.fetch()
    }

    @Test
    @Config(sdk = [25])
    fun fetch_belowSdk26_noopsWithoutThrowing() {
        SerialUnflaky.fetch()
    }
}
