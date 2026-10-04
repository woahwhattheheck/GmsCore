/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.droidguard.core

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import okio.ByteString.Companion.of
import org.microg.gms.droidguard.SignedResponse
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

/**
 * Verifies [SignatureVerifier.verifySignature] against a real captured DroidGuard VM
 * download reply: `dg_response_inner.bin` is the decoded `SignedResponse.data_`
 * protobuf field and `dg_response_sig.bin` is its decoded `signature` field, as
 * split by [NetworkHandleProxyFactory.SignedResponse.unpack]. The framed wire reply
 * is rebuilt through [SignedResponse.ADAPTER.encode] from those captured fields. The
 * bytes live as chunked base64 text in the `CapturedReply*` holder objects (JVM
 * literal bound + publisher request bound).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SignatureVerifierCapturedReplyTest {

    private val data: ByteArray by lazy {
        decode(CapturedReplyInnerA.RESPONSE_INNER_A + CapturedReplyInnerB.RESPONSE_INNER_B)
    }
    private val signature: ByteArray by lazy { decode(CapturedReplySig.RESPONSE_SIG) }
    private val signed: SignedResponse by lazy {
        SignedResponse(data_ = of(*data), signature = of(*signature))
    }
    private val framedReply: ByteArray by lazy { SignedResponse.ADAPTER.encode(signed) }

    private fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)

    @Test
    fun capturedDataAndSignature_verifyTrueAgainstEmbeddedGoogleKey() {
        assertTrue(SignatureVerifier.verifySignature(data, signature))
    }

    @Test
    fun singleFlippedDataBit_failsClosed() {
        val tampered = data.clone()
        tampered[0] = (tampered[0].toInt() xor 1).toByte()
        assertFalse(SignatureVerifier.verifySignature(tampered, signature))
    }

    @Test
    fun singleFlippedSignatureBit_failsClosed() {
        val tampered = signature.clone()
        tampered[128] = (tampered[128].toInt() xor 1).toByte()
        assertFalse(SignatureVerifier.verifySignature(data, tampered))
    }

    @Test
    fun truncatedSignature_failsClosedWithoutThrowing() {
        assertFalse(SignatureVerifier.verifySignature(data, signature.copyOf(200)))
    }

    @Test
    fun emptySignature_failsClosedWithoutThrowing() {
        assertFalse(SignatureVerifier.verifySignature(data, ByteArray(0)))
    }

    @Test
    fun signatureBytesPassedAsData_failsClosed() {
        // Swapped-argument order must not verify: the signature is not signed by itself.
        assertFalse(SignatureVerifier.verifySignature(signature, data.copyOf(256)))
    }

    @Test
    fun outerFramedReplyBytes_failsClosed() {
        // SignedResponse.unpack() must verify the decoded `data_` field only; the raw
        // wire reply (protobuf framing included) must not pass verification.
        assertFalse(SignatureVerifier.verifySignature(framedReply, signature))
    }

    @Test
    fun capturedWireReply_unpacksThroughVerifyGateIntoResponse() {
        val factory = NetworkHandleProxyFactory(ApplicationProvider.getApplicationContext())
        val signed = SignedResponse.ADAPTER.decode(framedReply)
        val response = with(factory) { signed.unpack() }
        assertNotNull(response.byteCode)
        assertEquals(70041, response.byteCode!!.size)
        assertEquals(20, response.vmChecksum!!.size)
        assertEquals(10, response.expiryTimeSecs)
        assertNotNull(response.content)
        assertEquals(377439, response.content!!.size)
    }

    @Test
    fun tamperedWireReply_unpackThrowsSecurityException() {
        val tampered = framedReply.clone()
        // Flip a byte inside the embedded data_ payload region (after the field-1 header).
        tampered[16] = (tampered[16].toInt() xor 1).toByte()
        val factory = NetworkHandleProxyFactory(ApplicationProvider.getApplicationContext())
        assertThrows(SecurityException::class.java) {
            with(factory) { SignedResponse.ADAPTER.decode(tampered).unpack() }
        }
    }
}
