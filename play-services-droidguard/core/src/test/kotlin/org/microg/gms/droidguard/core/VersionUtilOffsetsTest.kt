/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.droidguard.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins VersionUtil.getVersionOffset / isKnown against the compiled BUILD_MAP.
 *
 * Hand-computed expectations from BUILD_MAP:
 *  - "000302": v1="00"->i1=0, v2="03"->i2=0, v3="02" index 1 in [00,02,04,06,08]
 *    -> offset 0+0+1 = 1
 *  - "190408": v1="19"->i1=6, o1 = 6 + 9*5 = 51, v2="04"->i2=1, o2 = 2,
 *    v3="08" index 1 in [00,08] -> 51+2+1 = 54
 *  - "000300" / "020700": v3 at index 0 is rejected by `takeIf { it > 0 }`
 *    (a genuine quirk: position-0 dpi codes read as UNKNOWN even though listed)
 *  - "999999": no matching version-code row -> null
 *  - len<6 types throw StringIndexOutOfBoundsException (substring bounds)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VersionUtilOffsetsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val util get() = VersionUtil(context)

    @Test
    fun offset_firstEntries_areSmall() {
        assertEquals(1, util.getVersionOffset("000302"))
        assertTrue(util.isKnown("000302"))
    }

    @Test
    fun offset_lastVersionRow_accumulates() {
        assertEquals(54, util.getVersionOffset("190408"))
        assertTrue(util.isKnown("190408"))
    }

    @Test
    fun dpiAtIndexZero_readsAsUnknown() {
        // BUILD_MAP position 0 passes `it > 0` nowhere: listed-but-index-0 dpi
        // codes are effectively UNKNOWN. Pin current behavior.
        assertNull(util.getVersionOffset("000300"))
        assertNull(util.getVersionOffset("020700"))
        assertFalse(util.isKnown("000300"))
    }

    @Test
    fun unknownVersionCode_isNotKnown() {
        assertNull(util.getVersionOffset("999999"))
        assertFalse(util.isKnown("999999"))
    }

    @Test
    fun unknownArchGroup_isNotKnown() {
        // "09" arch does not exist under "00"
        assertNull(util.getVersionOffset("000900"))
    }

    @Test
    fun shortType_throwsIndexOutOfBounds() {
        assertThrows(StringIndexOutOfBoundsException::class.java) {
            util.getVersionOffset("19")
        }
    }
}
