/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.droidguard.core

import android.content.ContentValues
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the stored-VM row window contract in [DgDatabaseHelper]:
 * a row is visible to `get(id)` only while `b <= nowSeconds < b + c`
 * (write timestamp / seconds-until-expiry), and `put` with a non-positive
 * expiry deletes the row instead of storing it.
 *
 * Rows are crafted directly on `writableDatabase` so the b/c window edges are
 * deterministic — no wall-clock sleeps and no one-second-boundary races.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DgDatabaseHelperExpiryWindowTest {

    private val helper = DgDatabaseHelper(ApplicationProvider.getApplicationContext())

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

    private fun insertRaw(id: String, writtenAt: Long, ttlSeconds: Long, vmKey: String = "DEADBEEF") {
        val values = ContentValues().apply {
            put("a", id)
            put("b", writtenAt)
            put("c", ttlSeconds)
            put("d", vmKey)
            put("e", "")
            put("f", byteArrayOf(1, 2, 3))
            put("g", byteArrayOf(9))
        }
        helper.writableDatabase.insert("main", null, values)
    }

    @Test
    fun liveRow_returnsStoredVmKeyByteCodeAndExtra() {
        val id = "flow/live"
        helper.put(id, 3600, "ABCDEF0123", byteArrayOf(4, 5, 6), byteArrayOf(7))
        val row = helper.get(id)
        assertEquals("ABCDEF0123", row?.first)
        assertArrayEquals(byteArrayOf(4, 5, 6), row?.second)
        assertArrayEquals(byteArrayOf(7), row?.third)
    }

    @Test
    fun rowAtExpiryBoundary_isAlreadyInvisible() {
        // b + c == now: the window is half-open, so this row must not be returned.
        val id = "flow/expired-edge"
        insertRaw(id, writtenAt = nowSeconds() - 10, ttlSeconds = 10)
        assertNull(helper.get(id))
    }

    @Test
    fun rowWrittenInTheFuture_isNotYetVisible() {
        // b > now (clock skew or stale restore): the b <= now guard rejects it.
        val id = "flow/future"
        insertRaw(id, writtenAt = nowSeconds() + 600, ttlSeconds = 3600)
        assertNull(helper.get(id))
    }

    @Test
    fun putWithZeroExpiry_deletesExistingRow() {
        val id = "flow/revoked"
        helper.put(id, 3600, "KEYONE", byteArrayOf(1), byteArrayOf(2))
        assertTrue(helper.get(id) != null)
        helper.put(id, 0, "KEYTWO", byteArrayOf(3), byteArrayOf(4))
        assertNull(helper.get(id))
    }

    @Test
    fun putOnExistingId_replacesStoredValues() {
        val id = "flow/refresh"
        helper.put(id, 3600, "OLDKEY", byteArrayOf(1), byteArrayOf(1))
        helper.put(id, 3600, "NEWKEY", byteArrayOf(2), byteArrayOf(2))
        val row = helper.get(id)
        assertEquals("NEWKEY", row?.first)
        assertArrayEquals(byteArrayOf(2), row?.second)
    }

    @Test
    fun expiredThenReputRow_becomesVisibleAgain() {
        val id = "flow/resurrected"
        insertRaw(id, writtenAt = nowSeconds() - 7200, ttlSeconds = 3600)
        assertNull(helper.get(id))
        helper.put(id, 3600, "FRESHKEY", byteArrayOf(8), byteArrayOf(8))
        assertEquals("FRESHKEY", helper.get(id)?.first)
    }
}
