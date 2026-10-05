/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.droidguard

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the two wire encodings produced by org.microg.gms.droidguard.Utils —
 * the bytes that actually flow into constellation's droidguardResult field
 * and the client-side error rail:
 *  - toBase64 is Base64.URL_SAFE | NO_WRAP | NO_PADDING: output uses the -_
 *    alphabet (never +/), emits no '=' padding and no line breaks — the
 *    base64url_nopad shape already pinned at the API surface by
 *    DroidGuardClientHandleSemanticsTest
 *  - empty input encodes to "" — the known-good empty-token value replayed
 *    for a safe-failed snapshot
 *  - getErrorBytes produces the exact ASCII prefix "ERROR : " (colon padded
 *    by spaces on both sides) followed by the message in UTF-8
 *
 * Lives in the core test sourceSet for the real android.util.Base64 shadow.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UtilsEncodingTest {

    @Test
    fun toBase64_urlSafeAlphabet_noStandardChars() {
        // Bytes that encode to +/ under standard base64 must come out -_
        assertEquals("-__-", Utils.toBase64(byteArrayOf(0xFB.toByte(), 0xFF.toByte(), 0xFE.toByte())))
        assertEquals("-w", Utils.toBase64(byteArrayOf(0xFB.toByte())))
        val encoded = Utils.toBase64(ByteArray(64) { it.toByte() })
        assertFalse(encoded.contains('+'))
        assertFalse(encoded.contains('/'))
    }

    @Test
    fun toBase64_noPadding_noWrap() {
        // length % 3 == 1 would pad to "==" under MIME/basic encoding
        assertEquals("AA", Utils.toBase64(byteArrayOf(0)))
        assertEquals("AAA", Utils.toBase64(byteArrayOf(0, 0)))
        // 95 bytes (mod 3 == 2) would emit a trailing '=' under padded
        // encoding, and 127 chars would wrap at 76 under MIME encoding
        val encoded = Utils.toBase64(ByteArray(95) { (it * 7).toByte() })
        assertFalse(encoded.contains('\n'))
        assertFalse(encoded.contains('\r'))
        assertFalse(encoded.contains('='))
        assertEquals(127, encoded.length)
    }

    @Test
    fun toBase64_emptyInput_emptyString() {
        assertEquals("", Utils.toBase64(ByteArray(0)))
    }

    @Test
    fun toBase64_roundTripsThroughUrlDecoder() {
        val src = byteArrayOf(0x10, 0x22, 0x7F.toByte(), 0x80.toByte(), 0xF1.toByte(), 0x00, 0x5A)
        val decoded = java.util.Base64.getUrlDecoder().withoutPadding().decode(Utils.toBase64(src))
        assertArrayEquals(src, decoded)
    }

    @Test
    fun getErrorBytes_exactAsciiPrefix() {
        assertArrayEquals("ERROR : oops".toByteArray(Charsets.UTF_8), Utils.getErrorBytes("oops"))
        assertArrayEquals("ERROR : ".toByteArray(Charsets.UTF_8), Utils.getErrorBytes(""))
        // The literal layout: ERROR + space + colon + space — not "ERROR:" etc.
        assertEquals("ERROR : x".toByteArray(Charsets.UTF_8).size, Utils.getErrorBytes("x").size)
    }

    @Test
    fun getErrorBytes_messageIsUtf8() {
        val bytes = Utils.getErrorBytes("ää")
        assertArrayEquals(("ERROR : " + "ää").toByteArray(Charsets.UTF_8), bytes)
    }
}
