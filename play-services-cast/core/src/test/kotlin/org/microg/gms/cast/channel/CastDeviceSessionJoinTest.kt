/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.cast.channel

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.microg.gms.cast.proto.CastMessage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

class CastDeviceSessionJoinTest {
    @Test
    fun rejectedStatusRequestDoesNotJoinAnApplicationFromTheCache() {
        Harness().use { harness ->
            harness.receive(receiverStatus("cached-transport"))
            val requestId = harness.beginJoin()

            harness.receive(JSONObject().put("type", "INVALID_REQUEST").put("requestId", requestId))

            harness.assertFailed(CastDeviceSession.STATUS_INVALID_REQUEST)
        }
    }

    @Test
    fun statusReplyWithoutAnObjectDoesNotJoinAnApplicationFromTheCache() {
        for (status in listOf(null, JSONObject.NULL, "invalid", JSONArray())) {
            Harness().use { harness ->
                harness.receive(receiverStatus("cached-transport"))
                val requestId = harness.beginJoin()

                harness.receive(JSONObject().put("type", "RECEIVER_STATUS").put("requestId", requestId).put("status", status))

                harness.assertFailed(CastDeviceSession.STATUS_INVALID_REQUEST)
            }
        }
    }

    @Test
    fun validStatusJoinsTheFreshApplicationTransport() {
        Harness().use { harness ->
            harness.receive(receiverStatus("cached-transport"))
            val requestId = harness.beginJoin()

            harness.receive(receiverStatus("fresh-transport").put("requestId", requestId))

            assertTrue(harness.callbacks.failures.isEmpty())
            assertEquals(listOf("fresh-transport"), harness.callbacks.connected.map { it.transportId })
            assertEquals(listOf("fresh-transport"), harness.transportConnections())
        }
    }

    @Test
    fun validEmptyStatusDoesNotReuseTheCachedApplication() {
        Harness().use { harness ->
            harness.receive(receiverStatus("cached-transport"))
            val requestId = harness.beginJoin()

            harness.receive(JSONObject().put("type", "RECEIVER_STATUS").put("requestId", requestId).put("status", JSONObject()))

            harness.assertFailed(CastDeviceSession.STATUS_APPLICATION_NOT_RUNNING)
        }
    }

    @Test
    fun rejectedPrelaunchStatusDoesNotJoinAnApplicationFromTheCache() {
        Harness().use { harness ->
            harness.receive(receiverStatus("cached-transport"))
            val requestId = harness.beginLaunch(false)

            harness.receive(JSONObject().put("type", "INVALID_REQUEST").put("requestId", requestId))

            harness.assertFailed(CastDeviceSession.STATUS_INVALID_REQUEST)
            assertTrue(harness.receiverRequests().isEmpty())
        }
    }

    @Test
    fun prelaunchStatusWithoutAnObjectDoesNotReuseTheCachedApplication() {
        for (status in listOf(null, JSONObject.NULL, "invalid", JSONArray())) {
            Harness().use { harness ->
                harness.receive(receiverStatus("cached-transport"))
                val requestId = harness.beginLaunch(false)

                harness.receive(JSONObject().put("type", "RECEIVER_STATUS").put("requestId", requestId).put("status", status))

                harness.assertFailed(CastDeviceSession.STATUS_INVALID_REQUEST)
                assertTrue(harness.receiverRequests().isEmpty())
            }
        }
    }

    @Test
    fun launchStatusWithoutAnObjectDoesNotJoinAnApplicationFromTheCache() {
        for (status in listOf(null, JSONObject.NULL, "invalid", JSONArray())) {
            Harness().use { harness ->
                harness.receive(receiverStatus("cached-transport"))
                val requestId = harness.beginLaunch(true)

                harness.receive(JSONObject().put("type", "RECEIVER_STATUS").put("requestId", requestId).put("status", status))

                harness.assertFailed(CastDeviceSession.STATUS_INVALID_REQUEST)
            }
        }
    }

    @Test
    fun invalidLaunchReplyDoesNotJoinAnApplicationFromTheCache() {
        Harness().use { harness ->
            harness.receive(receiverStatus("cached-transport"))
            val requestId = harness.beginLaunch(true)

            harness.receive(receiverStatus("ignored-transport").put("type", "INVALID_REQUEST").put("requestId", requestId))

            harness.assertFailed(CastDeviceSession.STATUS_INVALID_REQUEST)
        }
    }

    @Test
    fun validLaunchStatusUsesTheFreshApplicationTransport() {
        Harness().use { harness ->
            harness.receive(receiverStatus("cached-transport"))
            val requestId = harness.beginLaunch(true)

            harness.receive(receiverStatus("fresh-transport").put("requestId", requestId))

            harness.assertConnected("fresh-transport", true)
        }
    }

    @Test
    fun validPrelaunchStatusJoinsTheFreshApplicationWithoutLaunchingAgain() {
        Harness().use { harness ->
            harness.receive(receiverStatus("cached-transport"))
            val requestId = harness.beginLaunch(false)

            harness.receive(receiverStatus("fresh-transport").put("requestId", requestId))

            harness.assertConnected("fresh-transport", false)
            assertTrue(harness.receiverRequests().isEmpty())
        }
    }

    @Test
    fun validPrelaunchStatusWithoutAnApplicationSendsLaunchAndUsesItsReply() {
        Harness().use { harness ->
            harness.receive(receiverStatus("cached-transport"))
            val statusRequestId = harness.beginLaunch(false)

            harness.receive(JSONObject().put("type", "RECEIVER_STATUS").put("requestId", statusRequestId).put("status", JSONObject()))

            assertTrue(harness.callbacks.connected.isEmpty())
            assertTrue(harness.callbacks.failures.isEmpty())
            val launchRequestId = harness.takeReceiverRequest("LAUNCH")
            harness.receive(receiverStatus("launched-transport").put("requestId", launchRequestId))

            harness.assertConnected("launched-transport", true)
        }
    }

    private class Harness : AutoCloseable {
        val callbacks = RecordingCallbacks()
        private val session = CastDeviceSession("127.0.0.1", CastChannel.DEFAULT_PORT, callbacks)
        private val channel = CastChannel("127.0.0.1", CastChannel.DEFAULT_PORT, session)
        private val output = ByteArrayOutputStream()
        private val executor = CastDeviceSession::class.java.getDeclaredField("executor").apply { isAccessible = true }
            .get(session) as ScheduledThreadPoolExecutor

        init {
            CastDeviceSession::class.java.getDeclaredField("channel").apply { isAccessible = true }.set(session, channel)
            CastChannel::class.java.getDeclaredField("output").apply { isAccessible = true }.set(channel, DataOutputStream(output))
        }

        fun receive(payload: JSONObject) {
            val message = CastMessage(
                CastMessage.ProtocolVersion.CASTV2_1_0,
                RECEIVER_ID,
                channel.senderId,
                NAMESPACE_RECEIVER,
                CastMessage.PayloadType.STRING,
                payload_utf8 = payload.toString(),
            )
            val framed = ByteArrayOutputStream()
            DataOutputStream(framed).use { stream ->
                val body = message.encode()
                stream.writeInt(body.size)
                stream.write(body)
            }
            val decoded = CastChannel::class.java.getDeclaredMethod("readMessage", DataInputStream::class.java)
                .apply { isAccessible = true }
                .invoke(channel, DataInputStream(ByteArrayInputStream(framed.toByteArray()))) as CastMessage
            session.onMessage(decoded)
            awaitIdle()
        }

        fun beginJoin(): Long {
            session.joinApplication(APP_ID, null)
            awaitIdle()
            return takeReceiverRequest("GET_STATUS")
        }

        fun beginLaunch(relaunchIfRunning: Boolean): Long {
            session.launchApplication(APP_ID, relaunchIfRunning, null)
            awaitIdle()
            return takeReceiverRequest(if (relaunchIfRunning) "LAUNCH" else "GET_STATUS")
        }

        fun receiverRequests(): List<CastMessage> = outgoing().filter { it.namespace == NAMESPACE_RECEIVER }

        fun takeReceiverRequest(type: String): Long {
            val requests = receiverRequests()
            assertEquals(1, requests.size)
            val request = JSONObject(requests.single().payload_utf8!!)
            assertEquals(type, request.getString("type"))
            output.reset()
            return request.getLong("requestId")
        }

        fun transportConnections(): List<String> = outgoing()
            .filter { it.namespace == NAMESPACE_CONNECTION && JSONObject(it.payload_utf8!!).optString("type") == "CONNECT" }
            .map { it.destination_id }

        fun assertFailed(statusCode: Int) {
            assertEquals(emptyList<String>(), callbacks.connected.map { it.transportId })
            assertEquals(listOf(statusCode), callbacks.failures)
            assertTrue(transportConnections().isEmpty())
        }

        fun assertConnected(transportId: String, wasLaunched: Boolean) {
            assertTrue(callbacks.failures.isEmpty())
            assertEquals(listOf(transportId), callbacks.connected.map { it.transportId })
            assertEquals(listOf(wasLaunched), callbacks.wasLaunched)
            assertEquals(listOf(transportId), transportConnections())
        }

        private fun outgoing(): List<CastMessage> {
            val messages = ArrayList<CastMessage>()
            DataInputStream(ByteArrayInputStream(output.toByteArray())).use { stream ->
                while (stream.available() > 0) {
                    val body = ByteArray(stream.readInt())
                    stream.readFully(body)
                    messages.add(CastMessage.ADAPTER.decode(body))
                }
            }
            return messages
        }

        private fun awaitIdle() {
            executor.submit {}.get(2, TimeUnit.SECONDS)
        }

        override fun close() {
            session.disconnect()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    private class RecordingCallbacks : CastDeviceSession.Callbacks {
        val connected = ArrayList<ReceiverApplication>()
        val wasLaunched = ArrayList<Boolean>()
        val failures = ArrayList<Int>()
        override fun onApplicationConnected(application: ReceiverApplication, wasLaunched: Boolean) {
            connected.add(application)
            this.wasLaunched.add(wasLaunched)
        }
        override fun onApplicationConnectionFailed(statusCode: Int) { failures.add(statusCode) }
        override fun onConnected() {}
        override fun onConnectionFailed(statusCode: Int) {}
        override fun onDisconnected(statusCode: Int) {}
        override fun onDeviceStatusChanged(status: ReceiverStatus) {}
        override fun onApplicationStatusChanged(statusText: String?) {}
        override fun onApplicationDisconnected(statusCode: Int) {}
        override fun onStopApplicationResult(statusCode: Int) {}
        override fun onLeaveApplicationResult(statusCode: Int) {}
        override fun onTextMessage(namespace: String, message: String) {}
        override fun onBinaryMessage(namespace: String, data: ByteArray) {}
        override fun onSendMessageSuccess(namespace: String, requestId: Long) {}
        override fun onSendMessageFailure(namespace: String, requestId: Long, statusCode: Int) {}
    }

    companion object {
        private const val APP_ID = "app-id"

        private fun receiverStatus(transportId: String): JSONObject = JSONObject().put("type", "RECEIVER_STATUS").put(
            "status", JSONObject().put("applications", JSONArray().put(
                JSONObject().put("appId", APP_ID).put("sessionId", "session-id").put("transportId", transportId)
            ))
        )
    }
}
