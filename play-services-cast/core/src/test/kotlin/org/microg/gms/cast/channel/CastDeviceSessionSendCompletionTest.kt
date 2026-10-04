/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.cast.channel

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.microg.gms.cast.proto.CastMessage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Runs the real send paths and executor admission against an in-memory Cast channel. */
class CastDeviceSessionSendCompletionTest {
    private data class Completion(val namespace: String, val requestId: Long, val status: Int)

    private val namespace = "urn:x-cast:test"
    private val completions = CopyOnWriteArrayList<Completion>()
    private val output = ByteArrayOutputStream()
    private lateinit var session: CastDeviceSession
    private lateinit var executor: ScheduledThreadPoolExecutor

    @Before
    fun setUp() {
        val callbackType = CastDeviceSession.Callbacks::class.java
        val callbacks = Proxy.newProxyInstance(callbackType.classLoader, arrayOf(callbackType)) { _, method, args ->
            when (method.name) {
                "onSendMessageSuccess" -> completions.add(Completion(args!![0] as String, args[1] as Long, CastDeviceSession.STATUS_SUCCESS))
                "onSendMessageFailure" -> completions.add(Completion(args!![0] as String, args[1] as Long, args[2] as Int))
            }
            null
        } as CastDeviceSession.Callbacks
        session = CastDeviceSession("127.0.0.1", CastChannel.DEFAULT_PORT, callbacks)
        executor = field(CastDeviceSession::class.java, "executor").get(session) as ScheduledThreadPoolExecutor
        val channel = CastChannel("127.0.0.1", CastChannel.DEFAULT_PORT, session, "sender-under-test")
        field(CastChannel::class.java, "output").set(channel, DataOutputStream(output))
        field(CastDeviceSession::class.java, "channel").set(session, channel)
        field(CastDeviceSession::class.java, "application").set(session,
            ReceiverApplication("app-id", null, "session-id", "transport-id", null, null, listOf(namespace)))
    }

    @After
    fun tearDown() {
        session.disconnect()
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
    }

    @Test
    fun acceptedTextAndBinarySendsWriteFramesAndCompleteOnceWithOriginalIds() {
        val binary = byteArrayOf(0, 1, -1)
        session.sendMessage(namespace, "payload", 42L)
        session.sendBinaryMessage(namespace, binary, Long.MAX_VALUE)
        executor.submit {}.get(2, TimeUnit.SECONDS)

        assertEquals(listOf(Completion(namespace, 42L, 0), Completion(namespace, Long.MAX_VALUE, 0)), completions.toList())
        val frames = DataInputStream(ByteArrayInputStream(output.toByteArray()))
        val text = readFrame(frames)
        val bytes = readFrame(frames)
        assertEquals("transport-id", text.destination_id)
        assertEquals(namespace, text.namespace)
        assertEquals("payload", text.payload_utf8)
        assertEquals(CastMessage.PayloadType.BINARY, bytes.payload_type)
        assertArrayEquals(binary, bytes.payload_binary!!.toByteArray())
        assertEquals(0, frames.available())
    }

    @Test
    fun textAndBinarySendsAfterDisconnectEachFailOnceWithOriginalIds() {
        session.disconnect()
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))

        session.sendMessage(namespace, "payload", 42L)
        session.sendBinaryMessage(namespace, byteArrayOf(1), Long.MAX_VALUE)

        assertEquals(listOf(failure(42L), failure(Long.MAX_VALUE)), completions.toList())
        assertEquals(0, output.size())
    }

    @Test
    fun textSendFailsOnceWhenShutdownWinsTheEnqueueRace() {
        shutDownDuringNextEnqueue()
        session.sendMessage(namespace, "payload", 42L)
        assertEquals(listOf(failure(42L)), completions.toList())
        assertEquals(0, output.size())
    }

    @Test
    fun binarySendFailsOnceWhenShutdownWinsTheEnqueueRace() {
        shutDownDuringNextEnqueue()
        session.sendBinaryMessage(namespace, byteArrayOf(1), Long.MAX_VALUE)
        assertEquals(listOf(failure(Long.MAX_VALUE)), completions.toList())
        assertEquals(0, output.size())
    }

    private fun shutDownDuringNextEnqueue() {
        executor.shutdown()
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        // isShutdown is false when post checks it. The real executor then rejects execute after shutdown.
        executor = object : ScheduledThreadPoolExecutor(1) {
            override fun execute(command: Runnable) {
                shutdown()
                super.execute(command)
            }
        }
        field(CastDeviceSession::class.java, "executor").set(session, executor)
    }

    private fun failure(requestId: Long) = Completion(namespace, requestId, CastDeviceSession.STATUS_APPLICATION_NOT_RUNNING)

    private fun readFrame(input: DataInputStream): CastMessage {
        val body = ByteArray(input.readInt())
        input.readFully(body)
        return CastMessage.ADAPTER.decode(body)
    }

    private fun field(type: Class<*>, name: String) = type.getDeclaredField(name).apply { isAccessible = true }
}
