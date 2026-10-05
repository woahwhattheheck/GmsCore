/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.droidguard.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.chainhelper.FakeKeyStoreSpi
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyStore
import java.security.Provider
import java.security.Security

/**
 * Pins the JCA provider half of the attestation block — the wiring that the
 * existing HardwareAttestationBlockingKeyStoreTest does not cover:
 *  - construction copies name/version/info and registers the
 *    "KeyStore.AndroidKeyStore" service mapping while publishing the real SPI
 *    through the Companion realSpi slot the no-arg keystore ctor reads
 *  - a KeyStore resolved through JCA delegates ordinary calls to the real SPI
 *    while engineGetCertificateChain throws unconditionally (the self-frame
 *    match already pinned on the keystore itself)
 *  - ensureEnabled(true) swaps a provider named AndroidKeyStore for the
 *    blocking provider and ensureEnabled(false) restores the original;
 *    a second enable attempt while enabled is a no-op
 *  - the enabled->disabled end state never leaves a blocking provider
 *    registered, whether or not an AndroidKeyStore provider exists
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HardwareAttestationBlockingProviderTest {

    private class FakeAksProvider : Provider("AndroidKeyStore", 1.0, "fake aks") {
        init {
            this["KeyStore.AndroidKeyStore"] = FakeKeyStoreSpi::class.java.name
        }
    }

    private fun currentlyEnabled(): Boolean {
        val outer = runCatching {
            HardwareAttestationBlockingProvider::class.java
                .getDeclaredField("currentlyEnabled")
                .let { it.isAccessible = true; it.getBoolean(null) }
        }
        return outer.getOrElse {
            Class.forName("org.microg.gms.droidguard.core.HardwareAttestationBlockingProvider\$Companion")
                .getDeclaredField("currentlyEnabled")
                .let { it.isAccessible = true; it.getBoolean(HardwareAttestationBlockingProvider.Companion) }
        }
    }

    private fun blockingProviderPresent() =
        Security.getProvider("AndroidKeyStore") is HardwareAttestationBlockingProvider

    @After
    fun tearDown() {
        // Leave global JCA state and the Companion slot exactly as found.
        HardwareAttestationBlockingProvider.ensureEnabled(false)
        var p = Security.getProvider("AndroidKeyStore")
        while (p != null && p.info == "fake aks") {
            Security.removeProvider("AndroidKeyStore")
            p = Security.getProvider("AndroidKeyStore")
        }
        HardwareAttestationBlockingKeyStore.realSpi = null
    }

    @Test
    fun ctor_copiesIdentity_registersService_setsRealSpi() {
        val spi = FakeKeyStoreSpi()
        val real = Provider("RealKS", 2.5, "real info")
        val blocking = HardwareAttestationBlockingProvider(real, spi)
        assertEquals("RealKS", blocking.name)
        assertEquals(2.5, blocking.version, 0.0)
        assertEquals("real info", blocking.info)
        val svc = blocking.getService("KeyStore", "AndroidKeyStore")
        assertNotNull(svc)
        assertEquals(HardwareAttestationBlockingKeyStore::class.java.name, svc!!.className)
        assertSame(spi, HardwareAttestationBlockingKeyStore.realSpi)
    }

    @Test
    fun jcaResolvedKeyStore_blocksChain_delegatesOthers() {
        val spi = FakeKeyStoreSpi()
        val blocking = HardwareAttestationBlockingProvider(Provider("RealKS2", 1.0, "r"), spi)
        Security.addProvider(blocking)
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore", "RealKS2")
            ks.load(null, null)
            assertEquals(1, spi.loadCalls.get())
            assertSame(FakeKeyStoreSpi.FakeCertificate, ks.getCertificate("alias1"))
            assertEquals(1, spi.certCalls.get())
            assertTrue(ks.isCertificateEntry("alias1"))
            assertTrue(ks.aliases().hasMoreElements())
            // engineGetCertificateChain is unconditionally blocked: the guard
            // matches its own "droidguard" frame before any caller is reached.
            try {
                ks.getCertificateChain("alias1")
                fail("engineGetCertificateChain must throw")
            } catch (e: UnsupportedOperationException) {
                // pinned current behavior
            }
            assertEquals(0, spi.chainCalls.get())
        } finally {
            Security.removeProvider("RealKS2")
        }
    }

    @Test
    fun ensureEnabled_swapsAndRestores_ignoresRepeatEnable() {
        val fake = FakeAksProvider()
        Security.insertProviderAt(fake, 1)
        try {
            HardwareAttestationBlockingProvider.ensureEnabled(true)
            assertTrue(currentlyEnabled())
            assertTrue(blockingProviderPresent())
            // The blocking provider took the AndroidKeyStore name over JCA and
            // still delegates non-chain calls to the extracted real SPI.
            assertSame(
                FakeKeyStoreSpi.FakeCertificate,
                KeyStore.getInstance("AndroidKeyStore").getCertificate("alias1")
            )
            try {
                KeyStore.getInstance("AndroidKeyStore").getCertificateChain("alias1")
                fail("chain must be blocked while enabled")
            } catch (e: UnsupportedOperationException) {
                // pinned
            }
            // Repeat enable is a no-op: same provider instance stays installed.
            val installed = Security.getProvider("AndroidKeyStore")
            HardwareAttestationBlockingProvider.ensureEnabled(true)
            assertSame(installed, Security.getProvider("AndroidKeyStore"))

            HardwareAttestationBlockingProvider.ensureEnabled(false)
            assertFalse(currentlyEnabled())
            assertSame(fake, Security.getProvider("AndroidKeyStore"))
            // Chain resolution is unblocked again through the restored SPI.
            assertNotNull(KeyStore.getInstance("AndroidKeyStore").getCertificateChain("alias1"))
        } finally {
            Security.removeProvider("AndroidKeyStore")
        }
    }

    @Test
    fun ensureEnabled_endState_neverLeavesBlockingProvider() {
        HardwareAttestationBlockingProvider.ensureEnabled(true)
        HardwareAttestationBlockingProvider.ensureEnabled(false)
        assertFalse(currentlyEnabled())
        assertFalse(blockingProviderPresent())
    }
}
