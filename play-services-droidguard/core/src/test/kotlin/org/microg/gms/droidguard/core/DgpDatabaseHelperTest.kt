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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the DgpDatabaseHelper storage contract on a real SQLite database:
 * database name dgp.db, version 1, single table t with one NOT NULL BLOB
 * column a, blob round-trip, and NULL rejection.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class DgpDatabaseHelperTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var helper: DgpDatabaseHelper

    @Before
    fun freshDatabase() {
        context.deleteDatabase(DB_NAME)
        helper = DgpDatabaseHelper(context)
    }

    @After
    fun dropDatabase() {
        helper.close()
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun onCreateBuildsNotNullBlobTable() {
        val db = helper.writableDatabase
        db.rawQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name='t'", null)
            .use { cursor ->
                assertTrue("table t must exist", cursor.moveToFirst())
                val sql = cursor.getString(0)
                assertTrue("column a must be a NOT NULL BLOB", sql.contains("a BLOB NOT NULL"))
            }
    }

    @Test
    fun blobRoundTripsByteExact() {
        val db = helper.writableDatabase
        val payload = byteArrayOf(0x00, 0x01, 0x7F, -0x80, -0x01)
        val values = ContentValues().apply { put("a", payload) }

        assertTrue(db.insert("t", null, values) > 0)

        db.rawQuery("SELECT a FROM t", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertArrayEquals(payload, cursor.getBlob(0))
        }
    }

    @Test
    fun nullBlobIsRejected() {
        val db = helper.writableDatabase
        val values = ContentValues().apply { putNull("a") }
        assertEquals(-1L, db.insert("t", null, values))
    }

    @Test
    fun databaseNameAndVersionArePinned() {
        val db = helper.writableDatabase
        assertEquals(1, db.version)
        assertTrue(context.getDatabasePath(DB_NAME).exists())
    }

    private companion object {
        const val DB_NAME = "dgp.db"
    }
}
