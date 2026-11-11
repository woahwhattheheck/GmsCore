/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.droidguard.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Completion of one init call, including a definite fallback to a legacy server. */
internal class RemoteDroidGuardInitialization {
    private val ready = CountDownLatch(1)
    private var started = false
    private var closed = false
    private var session: RemoteDroidGuardSession? = null
    private var failure: Exception? = null

    @Synchronized
    fun start(): Boolean {
        if (started || closed) return false
        started = true
        return true
    }

    fun complete(session: RemoteDroidGuardSession?) = complete(session, null)

    /** Session setup failed for a reason other than a legacy server; snapshots report it. */
    fun fail(failure: Exception) = complete(null, failure)

    private fun complete(session: RemoteDroidGuardSession?, failure: Exception?) {
        val accepted = synchronized(this) {
            if (closed) false else {
                this.session = session
                this.failure = failure
                true
            }
        }
        try {
            // close() may have timed out while begin was still in flight.
            if (!accepted) session?.close()
        } finally {
            ready.countDown()
        }
    }

    fun await(timeoutMillis: Int): RemoteDroidGuardSession? {
        check(ready.await(timeoutMillis.coerceAtLeast(1).toLong(), TimeUnit.MILLISECONDS)) {
            "Remote DroidGuard initialization timed out"
        }
        return synchronized(this) {
            check(!closed) { "Remote DroidGuard handle is closed" }
            failure?.let { throw IllegalStateException("Remote DroidGuard session setup failed: ${it.message}", it) }
            session
        }
    }

    fun close(timeoutMillis: Int) {
        synchronized(this) {
            closed = true
            if (!started) ready.countDown()
        }
        try {
            ready.await(timeoutMillis.coerceAtLeast(1).toLong(), TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            val session = synchronized(this) {
                this.session.also { this.session = null }
            }
            session?.close()
        }
    }
}
