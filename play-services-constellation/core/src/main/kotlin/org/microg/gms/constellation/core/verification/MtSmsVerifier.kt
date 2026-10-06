package org.microg.gms.constellation.core.verification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.microg.gms.constellation.core.proto.ChallengeResponse
import org.microg.gms.constellation.core.proto.MTChallenge
import org.microg.gms.constellation.core.proto.MTChallengeResponseData
import java.util.concurrent.TimeUnit
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

private const val TAG = "MtSmsVerifier"

suspend fun MTChallenge.verify(
    subId: Int,
    timeoutMillis: Long? = null
): ChallengeResponse? {
    val expectedBody = sms.takeIf { it.isNotEmpty() } ?: return null
    val inbox = MtSmsInboxRegistry.get(subId)
    val effectiveTimeoutMillis = timeoutMillis?.coerceAtLeast(0L) ?: TimeUnit.MINUTES.toMillis(30)

    Log.d(TAG, "Waiting for MT SMS containing challenge string")

    val match = withTimeoutOrNull(effectiveTimeoutMillis) {
        inbox.awaitMatch(expectedBody)
    }

    if (match == null) {
        Log.w(TAG, "Timed out waiting for MT SMS, proceeding with empty response")
    }

    val response = match ?: ReceivedSms(body = "", sender = "")
    return ChallengeResponse(
        mt_response = MTChallengeResponseData(
            sms = response.body,
            sender = response.sender
        )
    )
}

/**
 * Handle to a per-request MT SMS inbox. Extracted as an interface so the request-scoping logic in
 * [MtSmsInboxScope] can be unit tested without registering a real [BroadcastReceiver].
 */
internal interface MtSmsInboxHandle {
    suspend fun awaitMatch(expectedBody: String): ReceivedSms?
    fun dispose()
}

/**
 * Per-request registry for [MtSmsInbox] instances.
 *
 * The inboxes are owned by an [MtSmsInboxScope] carried in the request's coroutine context, which
 * the Constellation service installs when it dispatches a request. Because every request has its
 * own scope, [dispose] only tears down the inboxes of the request that calls it. That is what makes
 * the cleanup safe under concurrency: a slow or cancelled request running its `finally { dispose() }`
 * can never unregister the SMS receivers of a newer request that started in the meantime.
 */
internal object MtSmsInboxRegistry {
    suspend fun prepare(context: Context, subIds: Iterable<Int>) {
        currentScope().prepare(context, subIds)
    }

    suspend fun get(subId: Int): MtSmsInboxHandle = currentScope().get(subId)

    suspend fun dispose() {
        // Null-safe so it is a harmless no-op (never throws) when called from a finally block,
        // including during cancellation unwinding.
        coroutineContext[MtSmsInboxScope]?.dispose()
    }

    private suspend fun currentScope(): MtSmsInboxScope =
        coroutineContext[MtSmsInboxScope] ?: error(
            "No MtSmsInboxScope in the current coroutine context. Constellation requests must be " +
                "dispatched through ConstellationRequestDispatcher so MT SMS inboxes are scoped per request."
        )
}

/**
 * Coroutine-context element that owns the MT SMS inboxes of a single Constellation request.
 *
 * Each dispatched request carries its own instance, so the registry operations above resolve to the
 * inboxes of the calling request only. [inboxFactory] is overridable for tests; production uses the
 * real [MtSmsInbox].
 */
internal class MtSmsInboxScope(
    private val inboxFactory: (Context, Int) -> MtSmsInboxHandle = { context, subId ->
        MtSmsInbox(context, subId)
    }
) : AbstractCoroutineContextElement(MtSmsInboxScope) {
    companion object Key : CoroutineContext.Key<MtSmsInboxScope>

    private val lock = Any()
    private val inboxes = HashMap<Int, MtSmsInboxHandle>()
    private var disposed = false

    fun prepare(context: Context, subIds: Iterable<Int>) {
        val effectiveSubIds = subIds.distinct().ifEmpty { listOf(-1) }
        val replaced: List<MtSmsInboxHandle>
        synchronized(lock) {
            check(!disposed) { "MtSmsInboxScope already disposed" }
            val replacements = HashMap<Int, MtSmsInboxHandle>()
            try {
                for (subId in effectiveSubIds) {
                    replacements[subId] = inboxFactory(context, subId)
                }
            } catch (failure: Throwable) {
                for (inbox in replacements.values) {
                    try {
                        inbox.dispose()
                    } catch (cleanupFailure: Throwable) {
                        if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
                    }
                }
                throw failure
            }
            // Only ever touches THIS request's own inboxes.
            replaced = inboxes.values.toList()
            inboxes.clear()
            inboxes.putAll(replacements)
        }
        replaced.forEach { it.dispose() }
    }

    fun get(subId: Int): MtSmsInboxHandle = synchronized(lock) {
        inboxes[subId] ?: error("MT SMS inbox for subId=$subId was not initialized")
    }

    fun dispose() {
        val current: List<MtSmsInboxHandle>
        synchronized(lock) {
            if (disposed) return
            disposed = true
            current = inboxes.values.toList()
            inboxes.clear()
        }
        current.forEach { it.dispose() }
    }
}

internal data class ReceivedSms(
    val body: String,
    val sender: String
)

/**
 * Joins the parts of one received broadcast into a single message.
 *
 * A concatenated SMS arrives as one [SmsMessage] per PDU part, so buffering the parts separately
 * loses any challenge string that spans a part boundary. Takes (sender, body) pairs rather than
 * [SmsMessage] so the joining can be tested without manufacturing Android PDU bytes.
 */
internal fun joinParts(parts: List<Pair<String?, String?>>): ReceivedSms? {
    val bodies = parts.mapNotNull { it.second }
    if (bodies.isEmpty()) return null
    return ReceivedSms(
        body = bodies.joinToString(separator = ""),
        sender = parts.firstNotNullOfOrNull { it.first } ?: ""
    )
}

private data class PendingMatch(
    val expectedBody: String,
    val continuation: CancellableContinuation<ReceivedSms?>
)

@OptIn(InternalCoroutinesApi::class)
internal class MtSmsInbox(
    context: Context,
    private val subId: Int
) : MtSmsInboxHandle {
    private val context = context.applicationContext
    private val lock = Any()
    private val bufferedMessages = mutableListOf<ReceivedSms>()
    private val pendingMatches = mutableListOf<PendingMatch>()
    private var disposed = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

            val receivedSubId = intent.getIntExtra(
                "android.telephony.extra.SUBSCRIPTION_INDEX",
                intent.getIntExtra("subscription", -1)
            )
            if (subId != -1 && receivedSubId != subId) return

            onMessagesReceived(Telephony.Sms.Intents.getMessagesFromIntent(intent))
        }
    }

    init {
        val filter = IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION).apply {
            priority = 1000
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    override suspend fun awaitMatch(expectedBody: String): ReceivedSms? {
        return suspendCancellableCoroutine { continuation ->
            synchronized(lock) {
                if (disposed) {
                    continuation.cancel(CancellationException("MT SMS inbox disposed"))
                    return@suspendCancellableCoroutine
                }

                val bufferedIndex = bufferedMessages.indexOfFirst { it.body.contains(expectedBody) }
                if (bufferedIndex >= 0) {
                    val match = bufferedMessages[bufferedIndex]
                    // tryResume is the atomic acceptance point. Keep the buffered SMS if
                    // cancellation wins before acceptance; restore it if prompt cancellation
                    // wins after the value was reserved but before the waiter receives it.
                    val token = continuation.tryResume(match, null) { _, _, _ ->
                        restoreUndelivered(match)
                    }
                    if (token != null) {
                        bufferedMessages.removeAt(bufferedIndex)
                        continuation.completeResume(token)
                    }
                    return@suspendCancellableCoroutine
                }

                val pendingMatch = PendingMatch(expectedBody, continuation)
                pendingMatches += pendingMatch
                continuation.invokeOnCancellation {
                    synchronized(lock) {
                        pendingMatches.remove(pendingMatch)
                    }
                }
            }
        }
    }

    private fun onMessagesReceived(messages: Array<SmsMessage>) {
        onReceivedMessages(
            listOfNotNull(joinParts(messages.map { it.originatingAddress to it.messageBody }))
        )
    }

    /**
     * Adds normalized SMS messages to this inbox and resolves at most one pending challenge per
     * message. Kept internal so the real matching/buffering behavior can be tested without
     * manufacturing Android PDU bytes.
     */
    internal fun onReceivedMessages(receivedMessages: List<ReceivedSms>) {
        if (receivedMessages.isEmpty()) return

        synchronized(lock) {
            if (disposed) return

            for (receivedMessage in receivedMessages) {
                val iterator = pendingMatches.iterator()
                var delivered = false
                while (iterator.hasNext()) {
                    val pendingMatch = iterator.next()
                    if (!receivedMessage.body.contains(pendingMatch.expectedBody)) continue

                    // Do not consume an SMS for a continuation that cancellation already won.
                    val token = pendingMatch.continuation.tryResume(receivedMessage, null) { _, _, _ ->
                        restoreUndelivered(receivedMessage)
                    }
                    iterator.remove()
                    if (token == null) continue

                    pendingMatch.continuation.completeResume(token)
                    Log.d(TAG, "Matching MT SMS received")
                    delivered = true
                    break
                }
                if (!delivered && bufferedMessages.none { it === receivedMessage }) {
                    bufferedMessages += receivedMessage
                }
            }
        }
    }

    private fun restoreUndelivered(message: ReceivedSms) {
        synchronized(lock) {
            if (!disposed && bufferedMessages.none { it === message }) {
                bufferedMessages += message
            }
        }
    }

    override fun dispose() {
        val waiting: List<CancellableContinuation<ReceivedSms?>>
        synchronized(lock) {
            if (disposed) return
            disposed = true
            waiting = pendingMatches.map { it.continuation }
            pendingMatches.clear()
            bufferedMessages.clear()
        }
        waiting.forEach { it.cancel(CancellationException("MT SMS inbox disposed")) }
        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
        }
    }
}
