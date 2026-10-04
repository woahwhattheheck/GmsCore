/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.droidguard.core

import androidx.annotation.NonNull
import androidx.annotation.Nullable
import com.google.android.gms.droidguard.DroidGuardClient
import com.google.android.gms.droidguard.DroidGuardHandle
import com.google.android.gms.droidguard.internal.DroidGuardResultsRequest
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Session-transport client that uses the public handle lifecycle.
 *
 * [init] returns [Task]<[DroidGuardHandle]>. The handle then uses [DroidGuardHandle.snapshot],
 * [DroidGuardHandle.isOpened], and [DroidGuardHandle.close] against a retained-session server.
 * This is not a single-request/attest fallback client and does not mint Play Integrity verdicts.
 */
class RemoteDroidGuardHandleClient(
    private val serverUrl: String,
    private val packageName: String,
    private val timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    private val executor: Executor = DEFAULT_EXECUTOR
) : DroidGuardClient {
    @NonNull
    override fun init(@NonNull flow: String, @Nullable request: DroidGuardResultsRequest?): Task<DroidGuardHandle> {
        val completion = TaskCompletionSource<DroidGuardHandle>()
        executor.execute {
            try {
                val timeout = (request?.timeoutMillis ?: timeoutMillis).coerceAtLeast(1)
                val session = RemoteDroidGuardSession(
                    RemoteDroidGuardHttpClient(serverUrl, timeout),
                    buildRequestParameters(flow, request)
                )
                session.begin()
                completion.setResult(RemoteDroidGuardHandle(session))
            } catch (e: Exception) {
                completion.setException(e)
            }
        }
        return completion.task
    }

    @NonNull
    override fun getResults(
        @NonNull flow: String,
        @Nullable data: Map<String, String>?,
        @Nullable request: DroidGuardResultsRequest?
    ): Task<String> {
        val completion = TaskCompletionSource<String>()
        init(flow, request).addOnCompleteListener(executor) { task ->
            if (!task.isSuccessful) {
                completion.setException(task.exception ?: IllegalStateException("Remote DroidGuard init failed"))
                return@addOnCompleteListener
            }
            val handle = task.result
            try {
                completion.setResult(handle.snapshot(data ?: emptyMap()))
            } catch (e: Exception) {
                completion.setException(e)
            } finally {
                handle.close()
            }
        }
        return completion.task
    }

    private fun buildRequestParameters(flow: String, request: DroidGuardResultsRequest?): Map<String, String> {
        val parameters = linkedMapOf("flow" to flow, "source" to packageName)
        for (key in request?.bundle?.keySet().orEmpty().sorted()) {
            val value = request?.bundle?.get(key) ?: continue
            val scalar = when (value) {
                is String -> value
                is Number, is Boolean -> value.toString()
                else -> null
            } ?: continue
            parameters["x-request-$key"] = scalar
        }
        return parameters
    }

    private class RemoteDroidGuardHandle(
        private val session: RemoteDroidGuardSession
    ) : DroidGuardHandle {
        @Volatile
        private var opened = true

        override fun snapshot(data: Map<String, String>): String {
            check(opened) { "Remote DroidGuard handle is closed" }
            return session.snapshot(LinkedHashMap<Any?, Any?>(data))
        }

        override fun isOpened(): Boolean = opened

        override fun close() {
            if (!opened) return
            opened = false
            session.close()
        }
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MILLIS = 60000
        private val DEFAULT_EXECUTOR: Executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "RemoteDroidGuardHandle").apply { isDaemon = true }
        }
    }
}
