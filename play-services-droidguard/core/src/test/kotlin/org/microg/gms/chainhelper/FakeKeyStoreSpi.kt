/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.chainhelper

import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreSpi
import java.security.PublicKey
import java.security.cert.Certificate
import java.util.Date
import java.util.Enumeration

/**
 * A plain JCA KeyStoreSpi used to stand in for AndroidKeyStore's SPI in tests.
 * Lives outside any "droidguard" package on purpose: the blocking keystore's
 * stack-walk must not see a droidguard frame contributed by the SPI itself.
 */
class FakeKeyStoreSpi : KeyStoreSpi() {
    val loadCalls = java.util.concurrent.atomic.AtomicInteger(0)
    val certCalls = java.util.concurrent.atomic.AtomicInteger(0)
    val chainCalls = java.util.concurrent.atomic.AtomicInteger(0)
    val aliasCalls = java.util.concurrent.atomic.AtomicInteger(0)

    override fun engineGetKey(alias: String?, password: CharArray?): Key? = null
    override fun engineGetCertificateChain(alias: String?): Array<Certificate>? {
        chainCalls.incrementAndGet()
        return arrayOf(FakeCertificate)
    }

    override fun engineGetCertificate(alias: String?): Certificate? {
        certCalls.incrementAndGet()
        return FakeCertificate
    }

    override fun engineGetCreationDate(alias: String?): Date = Date(0)
    override fun engineSetKeyEntry(alias: String?, key: Key?, password: CharArray?, chain: Array<out Certificate>?) {}
    override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<out Certificate>?) {}
    override fun engineSetCertificateEntry(alias: String?, cert: Certificate?) {}
    override fun engineDeleteEntry(alias: String?) {}
    override fun engineAliases(): Enumeration<String> {
        aliasCalls.incrementAndGet()
        return java.util.Collections.enumeration(listOf("alias1"))
    }

    override fun engineContainsAlias(alias: String?): Boolean = alias == "alias1"
    override fun engineSize(): Int = 1
    override fun engineIsKeyEntry(alias: String?): Boolean = false
    override fun engineIsCertificateEntry(alias: String?): Boolean = true
    override fun engineGetCertificateAlias(cert: Certificate?): String? = "alias1"
    override fun engineStore(stream: OutputStream?, password: CharArray?) {}
    override fun engineLoad(stream: InputStream?, password: CharArray?) {
        loadCalls.incrementAndGet()
    }

    object FakeCertificate : Certificate("FAKE") {
        override fun getEncoded(): ByteArray = byteArrayOf(0x30, 0x03, 0x02, 0x01, 0x00)
        override fun verify(key: PublicKey?) {}
        override fun verify(key: PublicKey?, sigProvider: String?) {}
        override fun toString(): String = "FakeCertificate"
        override fun getPublicKey(): PublicKey = object : PublicKey {
            override fun getAlgorithm(): String = "FAKE"
            override fun getFormat(): String? = null
            override fun getEncoded(): ByteArray? = null
        }
    }
}
