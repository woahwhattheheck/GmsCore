/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.droidguard.core

import android.content.ContentValues
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the DgDatabaseHelper storage contract that the expiry-window suite
 * does not: repeated put() goes through UPDATE (one row per id, not append),
 * ids are isolated, a second live row with a newer b wins the DESC query,
 * the "NON NULL" typo in the schema is an invalid sqlite constraint so d/e
 * are actually nullable, and the database is pinned at dg.db version 2.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class DgDatabaseHelperStorageTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var helper: DgDatabaseHelper

    @Before
    fun freshDatabase() {
        context.deleteDatabase(DB_NAME)
        helper = DgDatabaseHelper(context)
    }

    @After
    fun dropDatabase() {
        helper.close()
        context.deleteDatabase(DB_NAME)
    }

    private fun rowCount(id: String): Int =
        helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM main WHERE a = ?", arrayOf(id))
            .use { c -> c.moveToFirst(); c.getInt(0) }

    @Test
    fun repeatedPutKeepsExactlyOneRow() {
        val id = "flow/update"
        helper.put(id, 3600, "KEY1", byteArrayOf(1), byteArrayOf(1))
        helper.put(id, 3600, "KEY2", byteArrayOf(2), byteArrayOf(2))
        helper.put(id, 3600, "KEY3", byteArrayOf(3), byteArrayOf(3))

        assertEquals(1, rowCount(id))
        val row = helper.get(id)
        assertEquals("KEY3", row?.first)
        assertArrayEquals(byteArrayOf(3), row?.second)
    }

    @Test
    fun multipleIdsAreIsolated() {
        helper.put("flow/one", 3600, "KEYONE", byteArrayOf(1), byteArrayOf(1))
        helper.put("flow/two", 3600, "KEYTWO", byteArrayOf(2), byteArrayOf(2))

        assertEquals("KEYONE", helper.get("flow/one")?.first)
        assertEquals("KEYTWO", helper.get("flow/two")?.first)

        helper.put("flow/one", 0, "GONE", byteArrayOf(9), byteArrayOf(9))
        assertNull(helper.get("flow/one"))
        assertEquals("KEYTWO", helper.get("flow/two")?.first)
    }

    @Test
    fun newestLiveRowWinsOnDuplicateIds() {
        // The get() query is ORDER BY b DESC LIMIT 1: of two live rows with
        // the same id the newer write timestamp must win.
        val id = "flow/dup"
        val now = System.currentTimeMillis() / 1000
        val older = ContentValues().apply {
            put("a", id); put("b", now - 60); put("c", 3600L)
            put("d", "OLDKEY"); put("e", "")
            put("f", byteArrayOf(1)); put("g", byteArrayOf(1))
        }
        val newer = ContentValues().apply {
            put("a", id); put("b", now); put("c", 3600L)
            put("d", "NEWKEY"); put("e", "")
            put("f", byteArrayOf(2)); put("g", byteArrayOf(2))
        }
        helper.writableDatabase.insert("main", null, older)
        helper.writableDatabase.insert("main", null, newer)

        val row = helper.get(id)
        assertEquals("NEWKEY", row?.first)
        assertArrayEquals(byteArrayOf(2), row?.second)
    }

    @Test
    fun invalidNonNullConstraintStillStoresNullColumns() {
        // The schema declares "d TEXT NON NULL, e TEXT NON NULL" — "NON NULL"
        // is not a real sqlite constraint, so NULLs are storable and the
        // read path returns them unchanged.
        val values = ContentValues().apply {
            put("a", "flow/nullcols"); put("b", System.currentTimeMillis() / 1000)
            put("c", 3600L); putNull("d"); putNull("e")
            put("f", byteArrayOf(7)); put("g", byteArrayOf(8))
        }
        assertTrue(helper.writableDatabase.insert("main", null, values) > 0)

        val row = helper.get("flow/nullcols")
        assertNull(row?.first)
        assertArrayEquals(byteArrayOf(7), row?.second)
        assertArrayEquals(byteArrayOf(8), row?.third)
    }

    @Test
    fun databaseIsVersionTwoWithWal() {
        val db = helper.writableDatabase
        assertEquals(2, db.version)
        assertTrue("onConfigure enables WAL", db.isWriteAheadLoggingEnabled)
        assertTrue(context.getDatabasePath(DB_NAME).exists())
    }

    private companion object {
        const val DB_NAME = "dg.db"
    }
}
