/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.cast.channel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.microg.gms.cast.proto.CastMessage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Feeds malformed framed input through the real CastChannel reader while a
 * session request is pending. Reader failure must close the session exactly
 * once, drain the pending request, and cancel its timeout.
 */
class CastChannelInputFailureLifecycleTest {
    @Test
    fun truncatedLengthPrefixesCloseAndDrainPendingRequest() {
        for (size in 1..3) {
            Fixture().use { fixture ->
                fixture.assertInputFailure(ByteArray(size))
            }
        }
    }

    @Test
    fun shortBodyClosesAndDrainsPendingRequest() {
        Fixture().use {
            it.assertInputFailure(declaredFrame(4, byteArrayOf(0x08, 0x00)))
        }
    }

    @Test
    fun invalidLengthsCloseBeforeBodyAllocationAndDrainPendingRequest() {
        for (length in listOf(-1, MAX_PAYLOAD_SIZE + 1)) {
            Fixture().use {
                it.assertInputFailure(declaredFrame(length, ByteArray(0)))
            }
        }
    }

    @Test
    fun malformedAndMissingRequiredProtoFieldsCloseAndDrainPendingRequest() {
        // Truncated varint and an empty proto2 CastMessage respectively.
        for (body in listOf(byteArrayOf(0x80.toByte()), ByteArray(0))) {
            Fixture().use {
                it.assertInputFailure(frame(body))
            }
        }
    }

    @Test
    fun validFrameDispatchesBeforeEndOfStreamClosesChannel() {
        val messages = CopyOnWriteArrayList<CastMessage>()
        val closes = CopyOnWriteArrayList<IOException?>()
        val channel = CastChannel(
            "127.0.0.1",
            CastChannel.DEFAULT_PORT,
            object : CastChannel.Listener {
                override fun onMessage(message: CastMessage) {
                    messages.add(message)
                }

                override fun onClosed(error: IOException?) {
                    closes.add(error)
                }
            },
            "sender-under-test",
        )
        val body = CastMessage(
            CastMessage.ProtocolVersion.CASTV2_1_0,
            RECEIVER_ID,
            channel.senderId,
            "urn:x-cast:com.example.fixture",
            CastMessage.PayloadType.STRING,
            payload_utf8 = "fixture",
        ).encode()

        invokeReadLoop(channel, frame(body))

        assertEquals(1, messages.size)
        assertEquals("fixture", messages.single().payload_utf8)
        assertEquals(1, closes.size)
        assertTrue(closes.single() != null)
    }

    private class Fixture : AutoCloseable {
        val stopResults = CopyOnWriteArrayList<Int>()
        val disconnectResults = CopyOnWriteArrayList<Int>()
        val session = CastDeviceSession(
            "127.0.0.1",
            CastChannel.DEFAULT_PORT,
            object : CastDeviceSession.Callbacks {
                override fun onConnected() = Unit
                override fun onConnectionFailed(statusCode: Int) = Unit
                override fun onDisconnected(statusCode: Int) {
                    disconnectResults.add(statusCode)
                }

                override fun onDeviceStatusChanged(status: ReceiverStatus) = Unit
                override fun onApplicationConnected(application: ReceiverApplication, wasLaunched: Boolean) = Unit
                override fun onApplicationConnectionFailed(statusCode: Int) = Unit
                override fun onApplicationStatusChanged(statusText: String?) = Unit
                override fun onApplicationDisconnected(statusCode: Int) = Unit
                override fun onStopApplicationResult(statusCode: Int) {
                    stopResults.add(statusCode)
                }

                override fun onLeaveApplicationResult(statusCode: Int) = Unit
                override fun onTextMessage(namespace: String, message: String) = Unit
                override fun onBinaryMessage(namespace: String, data: ByteArray) = Unit
                override fun onSendMessageSuccess(namespace: String, requestId: Long) = Unit
                override fun onSendMessageFailure(namespace: String, requestId: Long, statusCode: Int) = Unit
            },
        )
        private val channel = CastChannel("127.0.0.1", CastChannel.DEFAULT_PORT, session, "sender-under-test")
        private val executor = field(CastDeviceSession::class.java, "executor").get(session) as ScheduledThreadPoolExecutor
        private val pendingField = field(CastDeviceSession::class.java, "pendingRequests")

        init {
            field(CastChannel::class.java, "output").set(channel, DataOutputStream(ByteArrayOutputStream()))
            field(CastDeviceSession::class.java, "channel").set(session, channel)
        }

        fun assertInputFailure(input: ByteArray) {
            session.stopApplication("pending-session")
            barrier()

            val pending = pendingField.get(session) as Map<*, *>
            assertEquals(1, pending.size)
            val request = pending.values.single()!!
            val timeout = field(request.javaClass, "timeout").get(request) as ScheduledFuture<*>

            invokeReadLoop(channel, input)
            barrier()

            assertEquals(listOf(CastDeviceSession.STATUS_TIMEOUT), stopResults.toList())
            assertEquals(listOf(CastDeviceSession.STATUS_NETWORK_ERROR), disconnectResults.toList())
            assertTrue((pendingField.get(session) as Map<*, *>).isEmpty())
            assertTrue(timeout.isCancelled)

            // A later invocation on the already-closed channel must not repeat
            // close notification or request completion.
            invokeReadLoop(channel, input)
            barrier()
            assertEquals(listOf(CastDeviceSession.STATUS_TIMEOUT), stopResults.toList())
            assertEquals(listOf(CastDeviceSession.STATUS_NETWORK_ERROR), disconnectResults.toList())
        }

        private fun barrier() {
            executor.submit {}.get(2, TimeUnit.SECONDS)
        }

        override fun close() {
            session.disconnect()
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow()
                throw IOException("Cast session executor did not stop")
            }
        }
    }

    companion object {
        private fun frame(body: ByteArray): ByteArray = declaredFrame(body.size, body)

        private fun declaredFrame(length: Int, body: ByteArray): ByteArray =
            ByteArrayOutputStream().also { bytes ->
                DataOutputStream(bytes).use { output ->
                    output.writeInt(length)
                    output.write(body)
                }
            }.toByteArray()

        private fun invokeReadLoop(channel: CastChannel, bytes: ByteArray) {
            CastChannel::class.java
                .getDeclaredMethod("readLoop", DataInputStream::class.java)
                .apply { isAccessible = true }
                .invoke(channel, DataInputStream(ByteArrayInputStream(bytes)))
        }

        private fun field(type: Class<*>, name: String) =
            type.getDeclaredField(name).apply { isAccessible = true }
    }
}
