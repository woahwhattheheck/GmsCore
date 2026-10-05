/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.droidguard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.chainhelper.ChainCaller
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreSpi
import java.security.cert.Certificate
import java.util.Date
import java.util.Enumeration

/**
 * Pins HardwareAttestationBlockingKeyStore's caller-sensitive contract:
 *  - engineGetCertificateChain throws UnsupportedOperationException when ANY
 *    stack frame's class name contains "droidguard" (this test class itself is
 *    such a frame, so a direct call is the blocked arm)
 *  - the same call delegates to the real SPI when the stack is foreign
 *    (ChainCaller invokes from a non-droidguard class on a fresh thread)
 *  - every other engine method delegates regardless of caller — only the
 *    certificate chain is guarded
 *  - the @Keep no-arg constructor requires Companion.realSpi (installed by the
 *    provider registration path) and throws IllegalStateException without it
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HardwareAttestationBlockingKeyStoreTest {

    private class FakeCert : Certificate("TEST") {
        override fun getEncoded(): ByteArray = byteArrayOf(1, 2, 3)
        override fun getPublicKey(): java.security.PublicKey? = null
        override fun verify(key: java.security.PublicKey?) {}
        override fun verify(key: java.security.PublicKey?, sigProvider: String?) {}
        override fun toString(): String = "FakeCert"
    }

    private class RecordingSpi : KeyStoreSpi() {
        var chainCalls = 0
        var lastChainAlias: String? = null
        var keyCalls = 0
        var certCalls = 0
        val chain = arrayOf<Certificate>(FakeCert(), FakeCert())
        val key = object : Key {
            override fun getAlgorithm() = "TEST"
            override fun getFormat() = "RAW"
            override fun getEncoded() = byteArrayOf(9)
        }

        override fun engineGetCertificateChain(alias: String?): Array<Certificate> {
            chainCalls++; lastChainAlias = alias; return chain
        }

        override fun engineGetKey(alias: String?, password: CharArray?): Key {
            keyCalls++; return key
        }

        override fun engineGetCertificate(alias: String?): Certificate {
            certCalls++; return FakeCert()
        }

        override fun engineGetCreationDate(alias: String?): Date = Date(0)
        override fun engineSetKeyEntry(alias: String?, key: Key?, password: CharArray?, chain: Array<out Certificate>?) {}
        override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<out Certificate>?) {}
        override fun engineSetCertificateEntry(alias: String?, cert: Certificate?) {}
        override fun engineDeleteEntry(alias: String?) {}
        override fun engineAliases(): Enumeration<String> = emptyList<String>().let { java.util.Collections.enumeration(it) }
        override fun engineContainsAlias(alias: String?) = false
        override fun engineSize() = 0
        override fun engineIsKeyEntry(alias: String?) = false
        override fun engineIsCertificateEntry(alias: String?) = false
        override fun engineGetCertificateAlias(cert: Certificate?): String? = null
        override fun engineStore(stream: OutputStream?, password: CharArray?) {}
        override fun engineLoad(stream: InputStream?, password: CharArray?) {}
    }

    @Test
    fun droidguardFrame_chainThrows_andRealSpiIsUntouched() {
        val real = RecordingSpi()
        val ks = HardwareAttestationBlockingKeyStore(real)
        // This test class's package contains "droidguard": the stack walk must hit.
        assertThrows(UnsupportedOperationException::class.java) {
            ks.engineGetCertificateChain("alias1")
        }
        assertEquals(0, real.chainCalls)
    }

    @Test
    fun foreignFrame_chainDelegatesToRealSpi() {
        val real = RecordingSpi()
        val ks = HardwareAttestationBlockingKeyStore(real)
        val chain = ChainCaller.callGetCertificateChain(ks, "alias2")
        assertSame(real.chain, chain)
        assertEquals(1, real.chainCalls)
        assertEquals("alias2", real.lastChainAlias)
    }

    @Test
    fun droidguardFrame_otherEngineMethodsStillDelegate() {
        val real = RecordingSpi()
        val ks = HardwareAttestationBlockingKeyStore(real)
        // Only engineGetCertificateChain is guarded; engineGetKey must pass through
        // even with a "droidguard" frame on the stack.
        assertSame(real.key, ks.engineGetKey("k", null))
        assertEquals(1, real.keyCalls)
        assertTrue(ks.engineGetCertificate("c") is FakeCert)
        assertEquals(1, real.certCalls)
    }

    @Test
    fun defaultCtor_requiresCompanionRealSpi() {
        HardwareAttestationBlockingKeyStore.realSpi = null
        assertThrows(IllegalStateException::class.java) {
            HardwareAttestationBlockingKeyStore()
        }
        val real = RecordingSpi()
        HardwareAttestationBlockingKeyStore.realSpi = real
        val ks = HardwareAttestationBlockingKeyStore()
        assertSame(real.key, ks.engineGetKey("k", null))
        HardwareAttestationBlockingKeyStore.realSpi = null
    }
}
