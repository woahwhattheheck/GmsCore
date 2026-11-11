/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.droidguard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Session setup outcomes as RemoteHandleImpl.startRemoteSession classifies them. */
class RemoteDroidGuardSessionSetupTest {
    @Test
    fun sessionServerFailureIsReportedToSnapshotInsteadOfLegacyFallback() {
        for (status in listOf(401, 413, 502, 503, 504)) {
            val failure = beginFailure(status, """{"error":"Enable local Embedded DroidGuard on the server device"}""")
            assertFalse("HTTP $status must not select single-request mode", failure.indicatesLegacyRemoteServer())

            val initialization = RemoteDroidGuardInitialization()
            assertTrue(initialization.start())
            initialization.fail(failure)
            try {
                initialization.await(1)
                fail("Snapshot must report the HTTP $status session setup failure")
            } catch (e: IllegalStateException) {
                assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("HTTP $status"))
            }
            initialization.close(1)
        }
    }

    @Test
    fun transportFailureIsReportedToSnapshotInsteadOfLegacyFallback() {
        val session = RemoteDroidGuardSession(
            RemoteDroidGuardHttpClient("http://example.test/droidguard", 1_000) { throw IOException("Connection refused") },
            mapOf("flow" to "pia_attest_e1", "source" to "com.android.vending")
        )
        try {
            session.begin()
            fail("begin must fail")
        } catch (e: IOException) {
            assertFalse(e.indicatesLegacyRemoteServer())
        }
    }

    @Test
    fun serverWithoutSessionSetupStillUsesSingleRequestMode() {
        // A single-request server answers begin like a snapshot: a result without a session id.
        val noSessionId = beginFailure(200, "bGVnYWN5")
        assertTrue(noSessionId.indicatesLegacyRemoteServer())
        for (status in listOf(400, 404, 405, 501)) {
            assertTrue("HTTP $status", beginFailure(status, "").indicatesLegacyRemoteServer())
        }

        val initialization = RemoteDroidGuardInitialization()
        assertTrue(initialization.start())
        initialization.complete(null)
        assertNull(initialization.await(1))
        initialization.close(1)
    }

    private fun beginFailure(status: Int, body: String): Exception {
        val actions = mutableListOf<String>()
        val session = RemoteDroidGuardSession(
            RemoteDroidGuardHttpClient("http://example.test/droidguard", 1_000) { url -> FixedResponse(url, status, body, actions) },
            mapOf("flow" to "pia_attest_e1", "source" to "com.android.vending")
        )
        try {
            session.begin()
        } catch (e: Exception) {
            assertEquals(listOf("begin"), actions)
            return e
        }
        throw AssertionError("begin unexpectedly opened a session")
    }

    private class FixedResponse(
        url: URL,
        private val status: Int,
        private val body: String,
        private val actions: MutableList<String>
    ) : HttpURLConnection(url) {
        override fun getResponseCode(): Int {
            actions += url.query.split('&').first { it.startsWith("action=") }.substringAfter('=')
            return status
        }

        override fun getInputStream() = ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
    }
}
