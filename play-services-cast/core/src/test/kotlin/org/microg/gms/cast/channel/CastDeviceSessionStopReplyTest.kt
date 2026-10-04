/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.cast.channel

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.microg.gms.cast.proto.CastMessage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Exercises STOP replies through real Cast frames and the session's asynchronous request handling. */
class CastDeviceSessionStopReplyTest {
    @Test
    fun receiverStatusWithoutStatusDoesNotReportStopSuccess() {
        Fixture().use {
            assertEquals(CastDeviceSession.STATUS_INVALID_REQUEST, it.stopWithReply(JSONObject().put("type", "RECEIVER_STATUS")))
        }
    }

    @Test
    fun receiverStatusWithNonObjectStatusDoesNotReportStopSuccess() {
        for (status in listOf(JSONObject.NULL, JSONArray(), "not a receiver status")) {
            Fixture().use {
                val reply = JSONObject().put("type", "RECEIVER_STATUS").put("status", status)
                assertEquals(CastDeviceSession.STATUS_INVALID_REQUEST, it.stopWithReply(reply))
            }
        }
    }

    @Test
    fun receiverStatusWithTargetStillRunningDoesNotReportStopSuccess() {
        Fixture().use {
            assertEquals(CastDeviceSession.STATUS_FAILED, it.stopWithReply(statusReply("target-session")))
        }
    }

    @Test
    fun receiverStatusWithTargetGoneReportsStopSuccess() {
        for (reply in listOf(statusReply(), statusReply("other-session"))) {
            Fixture().use {
                assertEquals(CastDeviceSession.STATUS_SUCCESS, it.stopWithReply(reply))
            }
        }
    }

    @Test
    fun invalidRequestReplyRetainsFailureResult() {
        Fixture().use {
            assertEquals(CastDeviceSession.STATUS_INVALID_REQUEST, it.stopWithReply(JSONObject().put("type", "INVALID_REQUEST")))
        }
    }

    @Test
    fun missingChannelRetainsTimeoutResult() {
        Fixture(connected = false).use {
            it.session.stopApplication("target-session")
            assertEquals(CastDeviceSession.STATUS_TIMEOUT, it.result.get(2, TimeUnit.SECONDS))
        }
    }

    private fun statusReply(vararg sessionIds: String): JSONObject {
        val applications = JSONArray()
        for (id in sessionIds) {
            applications.put(JSONObject().put("appId", "test-app").put("sessionId", id).put("transportId", "transport-$id"))
        }
        return JSONObject().put("type", "RECEIVER_STATUS")
            .put("status", JSONObject().put("applications", applications))
    }

    private class Fixture(connected: Boolean = true) : AutoCloseable {
        val result = CompletableFuture<Int>()
        private val callbacks = object : CastDeviceSession.Callbacks {
            override fun onConnected() = Unit
            override fun onConnectionFailed(statusCode: Int) = Unit
            override fun onDisconnected(statusCode: Int) = Unit
            override fun onDeviceStatusChanged(status: ReceiverStatus) = Unit
            override fun onApplicationConnected(application: ReceiverApplication, wasLaunched: Boolean) = Unit
            override fun onApplicationConnectionFailed(statusCode: Int) = Unit
            override fun onApplicationStatusChanged(statusText: String?) = Unit
            override fun onApplicationDisconnected(statusCode: Int) = Unit
            override fun onStopApplicationResult(statusCode: Int) { result.complete(statusCode) }
            override fun onLeaveApplicationResult(statusCode: Int) = Unit
            override fun onTextMessage(namespace: String, message: String) = Unit
            override fun onBinaryMessage(namespace: String, data: ByteArray) = Unit
            override fun onSendMessageSuccess(namespace: String, requestId: Long) = Unit
            override fun onSendMessageFailure(namespace: String, requestId: Long, statusCode: Int) = Unit
        }
        val session = CastDeviceSession("127.0.0.1", CastChannel.DEFAULT_PORT, callbacks)
        private val channel = CastChannel("127.0.0.1", CastChannel.DEFAULT_PORT, session)
        private val sent = ByteArrayOutputStream()
        private val executor = CastDeviceSession::class.java.getDeclaredField("executor").apply { isAccessible = true }
            .get(session) as ScheduledThreadPoolExecutor

        init {
            CastChannel::class.java.getDeclaredField("output").apply { isAccessible = true }
                .set(channel, DataOutputStream(sent))
            if (connected) CastDeviceSession::class.java.getDeclaredField("channel").apply { isAccessible = true }
                .set(session, channel)
        }

        fun stopWithReply(reply: JSONObject): Int {
            session.stopApplication("target-session")
            // A queued barrier waits for the actual outbound request, without a timing sleep.
            executor.submit {}.get(2, TimeUnit.SECONDS)
            val requestFrame = DataInputStream(ByteArrayInputStream(sent.toByteArray()))
            val requestBytes = ByteArray(requestFrame.readInt()).also { requestFrame.readFully(it) }
            val request = JSONObject(CastMessage.ADAPTER.decode(requestBytes).payload_utf8!!)
            assertEquals("STOP", request.getString("type"))
            assertEquals("target-session", request.getString("sessionId"))
            reply.put("requestId", request.getLong("requestId"))
            val response = CastMessage(
                CastMessage.ProtocolVersion.CASTV2_1_0,
                RECEIVER_ID,
                channel.senderId,
                NAMESPACE_RECEIVER,
                CastMessage.PayloadType.STRING,
                payload_utf8 = reply.toString(),
            ).encode()
            val framed = ByteArrayOutputStream().also {
                DataOutputStream(it).apply { writeInt(response.size); write(response) }
            }
            CastChannel::class.java.getDeclaredMethod("readLoop", DataInputStream::class.java)
                .apply { isAccessible = true }
                .invoke(channel, DataInputStream(ByteArrayInputStream(framed.toByteArray())))
            return result.get(2, TimeUnit.SECONDS)
        }

        override fun close() {
            session.disconnect()
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow()
                throw IOException("Cast session executor did not stop")
            }
        }
    }
}
