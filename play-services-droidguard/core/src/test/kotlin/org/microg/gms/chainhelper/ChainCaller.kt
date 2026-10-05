/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.chainhelper

import java.security.KeyStoreSpi
import java.security.cert.Certificate
import java.util.concurrent.atomic.AtomicReference

/**
 * Calls a KeyStoreSpi from a stack that contains NO "droidguard" frame.
 * The class lives outside any package containing the substring "droidguard",
 * and the SPI call runs on a fresh thread so the caller's stack cannot leak in
 * (HardwareAttestationBlockingKeyStore walks Thread.currentThread().stackTrace).
 */
object ChainCaller {
    fun callGetCertificateChain(spi: KeyStoreSpi, alias: String?): Array<Certificate>? {
        val result = AtomicReference<Array<Certificate>?>()
        val error = AtomicReference<Throwable?>()
        val t = Thread {
            try {
                result.set(spi.engineGetCertificateChain(alias))
            } catch (e: Throwable) {
                error.set(e)
            }
        }
        t.start()
        t.join(10_000)
        error.get()?.let { throw it }
        return result.get()
    }
}
