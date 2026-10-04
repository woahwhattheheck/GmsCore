/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.cast.channel

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.microg.gms.cast.proto.CastMessage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Runs the real request/reply executor and serialized channel frames without a socket. */
class CastDeviceSessionTimerTest {
    private val senderId = "timer-test-sender"
    private val output = ByteArrayOutputStream()
    private val events = CopyOnWriteArrayList<Pair<String, Int?>>()
    private lateinit var session: CastDeviceSession
    private lateinit var executor: ScheduledThreadPoolExecutor

    @Before
    fun setUp() {
        val callbackType = CastDeviceSession.Callbacks::class.java
        val callbacks = Proxy.newProxyInstance(callbackType.classLoader, arrayOf(callbackType)) { _, method, args ->
            events.add(method.name to (args?.firstOrNull() as? Int))
            null
        } as CastDeviceSession.Callbacks
        session = CastDeviceSession("127.0.0.1", CastChannel.DEFAULT_PORT, callbacks)
        executor = field(CastDeviceSession::class.java, "executor").get(session) as ScheduledThreadPoolExecutor
        val channel = CastChannel("127.0.0.1", CastChannel.DEFAULT_PORT, session, senderId)
        field(CastChannel::class.java, "output").set(channel, DataOutputStream(output))
        field(CastDeviceSession::class.java, "channel").set(session, channel)
    }

    @After
    fun tearDown() {
        session.disconnect()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
    }

    @Test
    fun repliedRequestsRemoveCanceledTimeouts() {
        val requestsPerKind = 256
        val started = System.nanoTime()
        repeat(requestsPerKind) {
            session.requestStatus()
            session.launchApplication("app-id", true, null)
        }
        val timers = executor.submit<List<ScheduledFuture<*>>> {
            executor.queue.filterIsInstance<ScheduledFuture<*>>()
        }.get(5, TimeUnit.SECONDS)
        assertEquals(requestsPerKind * 2, pendingCount())
        assertEquals(requestsPerKind * 2, timers.size)
        assertEquals(requestsPerKind, timers.count { it.getDelay(TimeUnit.MILLISECONDS) > 15_000 })

        val input = DataInputStream(ByteArrayInputStream(output.toByteArray()))
        var replies = 0
        while (input.available() > 0) {
            val body = ByteArray(input.readInt())
            input.readFully(body)
            val request = CastMessage.ADAPTER.decode(body)
            assertEquals(NAMESPACE_RECEIVER, request.namespace)
            val json = JSONObject(request.payload_utf8!!)
            val reply = JSONObject().put("requestId", json.getLong("requestId"))
            when (json.getString("type")) {
                "GET_STATUS" -> reply.put("type", "RECEIVER_STATUS").put("status", JSONObject())
                "LAUNCH" -> reply.put("type", "LAUNCH_ERROR").put("reason", "NOT_FOUND")
                else -> throw AssertionError("Unexpected request: $json")
            }
            val message = CastMessage(
                CastMessage.ProtocolVersion.CASTV2_1_0, RECEIVER_ID, senderId,
                NAMESPACE_RECEIVER, CastMessage.PayloadType.STRING, payload_utf8 = reply.toString(),
            )
            session.onMessage(CastMessage.ADAPTER.decode(message.encode()))
            replies++
        }
        assertEquals(0, pendingCount())
        val retained = executor.submit<Int> { executor.queue.size }.get(5, TimeUnit.SECONDS)
        assertEquals(requestsPerKind * 2, replies)
        assertEquals(requestsPerKind, events.count { it.first == "onDeviceStatusChanged" })
        assertEquals(requestsPerKind, events.count {
            it == ("onApplicationConnectionFailed" to CastDeviceSession.STATUS_APPLICATION_NOT_FOUND)
        })
        assertEquals(requestsPerKind * 2, events.size)
        assertTrue(timers.all { it.isCancelled })
        println("TIMER_MEASUREMENT " + JSONObject()
            .put("statusRequests", requestsPerKind).put("launchRequests", requestsPerKind)
            .put("timersScheduled", timers.size).put("repliesProcessed", replies)
            .put("pendingRequestsAfterReplies", 0).put("canceledTimersRetained", retained)
            .put("callbacks", events.size).put("elapsedMillis", (System.nanoTime() - started) / 1_000_000))
        assertEquals("Unexpected canceled delayed-task retention for this runtime", 
            Integer.getInteger("cast.timer.expectedRetained", 0).toInt(), retained)
    }

    @Test
    fun requestTimeoutStillCompletesExactlyOnce() {
        val calls = AtomicInteger()
        val completed = CountDownLatch(1)
        val callback: (JSONObject?) -> Unit = { reply ->
            assertNull(reply)
            calls.incrementAndGet()
            completed.countDown()
        }
        // Use the existing timeout parameter to exercise the real deadline path promptly.
        val request = CastDeviceSession::class.java.declaredMethods.single { it.name == "requestReceiver" }
            .apply { isAccessible = true }
        executor.submit {
            request.invoke(session, JSONObject().put("type", "GET_STATUS"), 50L, callback)
        }.get(5, TimeUnit.SECONDS)
        assertTrue("The outstanding request must time out", completed.await(5, TimeUnit.SECONDS))
        assertEquals(0, pendingCount())
        assertEquals(1, calls.get())
        assertEquals(0, executor.submit<Int> { executor.queue.size }.get(5, TimeUnit.SECONDS))
        println("TIMER_TIMEOUT callbacks=${calls.get()} pending=0 queue=0")
    }

    private fun pendingCount(): Int = executor.submit<Int> {
        (field(CastDeviceSession::class.java, "pendingRequests").get(session) as Map<*, *>).size
    }.get(5, TimeUnit.SECONDS)

    private fun field(type: Class<*>, name: String) = type.getDeclaredField(name).apply { isAccessible = true }
}
