/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.droidguard.core

import android.content.Context
import android.content.pm.ProviderInfo
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.droidguard.internal.DroidGuardResultsRequest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.settings.SettingsContract
import org.microg.gms.settings.SettingsProvider
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Covers RemoteHandleImpl.snapshot() wire behavior against a real local HTTP endpoint.
 * The settings path is exercised end-to-end through the real SettingsProvider (real
 * ContentProvider + real SharedPreferences), not a stub, so DroidGuardPreferences
 * reads the same row a device build would.
 *
 * Discriminating cases:
 * - query carries flow, source, and x-request-* params for STRING bundle entries only
 *   (the request constructor seeds clientVersion as Int and appArchitecture as String;
 *   the String one must appear, the Int one must not)
 * - response body is base64url (-/_ alphabet, no padding) and is decoded, not echoed
 * - an HTTP error status propagates IOException; this layer has no fallback
 * - close() clears request state: a later snapshot sends no x-request-* params and
 *   serializes the null flow as the literal "null" (Uri.encode(null) -> null -> "null")
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemoteHandleImplSnapshotTransportTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Minimal one-shot HTTP/1.1 server capturing the single request and replying with `status`/body. */
    private class FakeDgEndpoint(status: String, responseBody: String) {
        private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        private val done = CountDownLatch(1)
        @Volatile var requestLine: String? = null
        @Volatile var requestHeaders: List<String> = emptyList()
        @Volatile var requestBody: String? = null
        val url = "http://127.0.0.1:${server.localPort}/hook"

        private val worker = thread {
            server.accept().use { conn ->
                val input = conn.getInputStream()
                val head = StringBuilder()
                var b: Int
                // read header block terminated by CRLFCRLF without buffering past it
                val tail = ArrayDeque<Int>()
                while (true) {
                    b = input.read()
                    if (b < 0) break
                    head.append(b.toChar())
                    tail.addLast(b)
                    if (tail.size > 4) tail.removeFirst()
                    if (tail.toList() == listOf(13, 10, 13, 10)) break
                }
                val headText = head.toString()
                val lines = headText.split("\r\n").filter { it.isNotEmpty() }
                requestLine = lines.firstOrNull()
                requestHeaders = lines.drop(1)
                val length = requestHeaders.firstOrNull {
                    it.startsWith("Content-Length:", ignoreCase = true)
                }?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
                val bodyBytes = ByteArray(length)
                var off = 0
                while (off < length) {
                    val r = input.read(bodyBytes, off, length - off)
                    if (r < 0) break
                    off += r
                }
                requestBody = bodyBytes.decodeToString()
                val payload = responseBody.toByteArray(Charsets.UTF_8)
                conn.getOutputStream().write(
                    "HTTP/1.1 $status\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n"
                        .toByteArray(Charsets.US_ASCII)
                )
                conn.getOutputStream().write(payload)
                conn.getOutputStream().flush()
            }
            done.countDown()
        }

        fun await() {
            assertTrue("server did not receive a request", done.await(10, TimeUnit.SECONDS))
            worker.join(10_000)
        }

        fun close() = server.close()
        fun queryParams(): Map<String, String> {
            val query = requestLine!!.substringAfter('?', "").substringBefore(' ')
            return query.split('&').filter { it.isNotEmpty() }.associate {
                it.substringBefore('=') to it.substringAfter('=', "")
            }
        }
    }

    @Before
    fun installSettingsProvider() {
        val authority = SettingsContract.getAuthority(context)
        val provider = SettingsProvider()
        val info = ProviderInfo().apply {
            this.authority = authority
            this.packageName = context.packageName
        }
        provider.attachInfo(context, info)
        ShadowContentResolver.registerProviderInternal(authority, provider)
    }

    @After
    fun clearSettings() {
        DroidGuardPreferences.setNetworkServerUrl(context, null)
    }

    private fun handle(serverUrl: String): RemoteHandleImpl {
        DroidGuardPreferences.setNetworkServerUrl(context, serverUrl)
        return RemoteHandleImpl(context, "com.example.app")
    }

    @Test
    fun snapshot_postsEncodedForm_carriesStringBundleParams_decodesUrlSafeBase64() {
        val server = FakeDgEndpoint("200 OK", "-__-") // urlsafe base64 of bytes FB FF FE
        val handle = handle(server.url)
        val request = DroidGuardResultsRequest().also {
            it.bundle.putString("token", "a b&c")
            it.bundle.putInt("num", 7)
        }
        handle.initWithRequest("shieldFlow", request)
        val result = handle.snapshot(mapOf("k1" to "v 1", "w+key" to "v&1"))
        server.await()
        server.close()

        assertArrayEquals(byteArrayOf(0xFB.toByte(), 0xFF.toByte(), 0xFE.toByte()), result)
        val line = server.requestLine!!
        assertTrue(line, line.startsWith("POST /hook?"))
        val params = server.queryParams()
        assertEquals("shieldFlow", params["flow"])
        assertEquals("com.example.app", params["source"])
        assertEquals("a%20b%26c", params["x-request-token"])
        assertNotNull("ctor-seeded String bundle entry must be forwarded", params["x-request-appArchitecture"])
        assertFalse("Int bundle entries must be dropped", params.containsKey("x-request-num"))
        assertFalse(params.containsKey("x-request-clientVersion"))
        assertTrue(server.requestHeaders.any { it.equals("Content-Type: application/x-www-form-urlencoded; charset=UTF-8", ignoreCase = true) })
        assertEquals("k1=v%201&w%2Bkey=v%261", server.requestBody)
    }

    @Test
    fun snapshot_afterClose_dropsRequestParams_andSerializesNullFlow() {
        val server = FakeDgEndpoint("200 OK", "AA")
        val handle = handle(server.url)
        handle.initWithRequest("f", DroidGuardResultsRequest().also {
            it.bundle.putString("token", "x")
        })
        handle.close()
        handle.snapshot(emptyMap())
        server.await()
        server.close()

        val params = server.queryParams()
        assertEquals("com.example.app", params["source"])
        assertEquals("null", params["flow"])
        assertFalse(params.keys.any { it.startsWith("x-request-") })
        assertEquals("", server.requestBody)
    }

    @Test
    fun snapshot_httpError_propagatesIoException() {
        val server = FakeDgEndpoint("500 Internal Server Error", "boom")
        val handle = handle(server.url)
        handle.init("f")
        assertThrows(IOException::class.java) { handle.snapshot(emptyMap()) }
        server.await()
        server.close()
    }

    @Test
    fun snapshot_nullMap_sendsEmptyBody() {
        val server = FakeDgEndpoint("200 OK", "AQI")
        val handle = handle(server.url)
        handle.init("f2")
        val result = handle.snapshot(null)
        server.await()
        server.close()
        assertArrayEquals(byteArrayOf(0x01, 0x02), result)
        assertEquals("", server.requestBody)
        assertEquals("f2", server.queryParams()["flow"])
    }

    @Test
    fun snapshot_initOnly_noRequestBundleParams() {
        val server = FakeDgEndpoint("200 OK", "")
        val handle = handle(server.url)
        handle.init("onlyFlow")
        handle.snapshot(emptyMap())
        server.await()
        server.close()
        val params = server.queryParams()
        assertEquals("onlyFlow", params["flow"])
        assertFalse(params.keys.any { it.startsWith("x-request-") })
    }
}
