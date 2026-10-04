/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.cast

import android.os.Bundle
import com.google.android.gms.cast.ApplicationMetadata
import com.google.android.gms.cast.ApplicationStatus
import com.google.android.gms.cast.CastDevice
import com.google.android.gms.cast.CastDeviceStatus
import com.google.android.gms.cast.internal.ICastDeviceControllerListener
import com.google.android.gms.common.api.GoogleApiClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.cast.channel.CastDeviceSession
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.util.ArrayDeque
import java.util.concurrent.ScheduledThreadPoolExecutor

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CastDeviceControllerReconnectTest {
    private val events = mutableListOf<String>()
    private val executors = mutableListOf<SessionExecutor>()

    @After
    fun closeExecutors() {
        executors.forEach { it.shutdownNow() }
    }

    @Test
    fun unexpectedDropSuspendsAndReconnectsInsteadOfEnding() {
        val listener = RecordingListener()
        val controller = controller(listener)
        val session = session(controller)
        val callbacks = callbacks(session)

        callbacks.onDisconnected(CastDeviceSession.STATUS_NETWORK_ERROR)

        assertEquals(listOf("onConnectionSuspended"), listener.events)
        assertFalse(listener.events.contains("onDisconnected"))
        assertEquals(1, executors.single().pending.size)
        // Drop the real socket connect; this test only checks binder session ownership.
        executors.single().pending.clear()

        callbacks.onConnected()

        assertTrue(listener.events.contains("onConnectedWithResult:0"))
        assertFalse(listener.events.contains("onDisconnected"))
    }

    @Test
    fun reconnectRejoinsTheAttachedApplication() {
        val listener = RecordingListener()
        val controller = controller(listener)
        val session = session(controller)
        val callbacks = callbacks(session)
        val application = org.microg.gms.cast.channel.ReceiverApplication(
            "CC1AD845", "Default Media Receiver", "app-session", "transport", "Ready", null, emptyList()
        )
        callbacks.onApplicationConnected(application, true)
        assertEquals("CC1AD845", attached(controller, "attachedApplicationId"))
        assertEquals("app-session", attached(controller, "attachedSessionId"))

        callbacks.onDisconnected(CastDeviceSession.STATUS_NETWORK_ERROR)
        executors.single().pending.clear()
        assertEquals(listOf("onApplicationConnectionSuccess", "onConnectionSuspended"), listener.events)

        callbacks.onConnected()

        assertTrue(listener.events.contains("onConnectedWithResult:0"))
        // Rejoin is posted on the session executor and must not open a socket in this test.
        assertEquals(1, executors.single().pending.size)
        executors.single().pending.clear()
    }

    @Test
    fun exhaustedReconnectsDisconnect() {
        val listener = RecordingListener()
        val controller = controller(listener)
        val session = session(controller)
        val callbacks = callbacks(session)
        repeat(CastDeviceControllerImpl.MAX_RECONNECT_ATTEMPTS) {
            callbacks.onDisconnected(CastDeviceSession.STATUS_NETWORK_ERROR)
            executors.single().pending.clear()
        }
        callbacks.onDisconnected(CastDeviceSession.STATUS_NETWORK_ERROR)
        assertTrue(listener.events.contains("onDisconnected"))
        // Connectionless clients keep the binder so they can call connect() again.
        assertFalse(events.contains("release"))
    }

    @Test
    fun userDisconnectStillEndsWithoutReconnect() {
        val listener = RecordingListener()
        val controller = controller(listener)
        controller.disconnect()
        executors.single().runPending()
        assertEquals(listOf("release"), events)
        assertFalse(listener.events.contains("onConnectionSuspended"))
    }

    private fun controller(listener: RecordingListener): CastDeviceControllerImpl {
        val device = CastDevice(
            "test-device", "test-device", InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)),
            8009, "1", "Test receiver", "Test model", null, 0, 1,
        )
        val extras = Bundle().also { device.putInBundle(it) }
        return CastDeviceControllerImpl("test.client", extras, {
            events += "release"
        }, {
            events += "reopen"
            controlSessionQueue(it)
        }).also {
            controlSessionQueue(it)
            it.addListener(listener)
        }
    }

    private fun controlSessionQueue(controller: CastDeviceControllerImpl) {
        val session = session(controller)
        val executorField = CastDeviceSession::class.java.getDeclaredField("executor").apply { isAccessible = true }
        (executorField.get(session) as ScheduledThreadPoolExecutor).shutdownNow()
        val executor = SessionExecutor()
        executorField.set(session, executor)
        executors += executor
    }

    private fun session(controller: CastDeviceControllerImpl): CastDeviceSession {
        return CastDeviceControllerImpl::class.java.getDeclaredField("session")
            .apply { isAccessible = true }.get(controller) as CastDeviceSession
    }

    private fun callbacks(session: CastDeviceSession): CastDeviceSession.Callbacks {
        return CastDeviceSession::class.java.getDeclaredField("callbacks")
            .apply { isAccessible = true }.get(session) as CastDeviceSession.Callbacks
    }

    private fun attached(controller: CastDeviceControllerImpl, name: String): String? {
        return CastDeviceControllerImpl::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(controller) as String?
    }

    private class SessionExecutor : ScheduledThreadPoolExecutor(1) {
        val pending = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            pending.addLast(command)
        }

        fun runPending() {
            while (pending.isNotEmpty()) pending.removeFirst().run()
        }
    }

    private class RecordingListener : ICastDeviceControllerListener.Stub() {
        val events = mutableListOf<String>()

        override fun onDisconnected(reason: Int) {
            events += "onDisconnected"
        }

        override fun onApplicationConnectionSuccess(
            applicationMetadata: ApplicationMetadata?,
            applicationStatus: String?,
            sessionId: String?,
            wasLaunched: Boolean,
        ) {
            events += "onApplicationConnectionSuccess"
        }

        override fun onApplicationConnectionFailure(statusCode: Int) {
            events += "onApplicationConnectionFailure"
        }

        override fun onTextMessageReceived(namespace: String?, message: String?) {}
        override fun onBinaryMessageReceived(namespace: String?, data: ByteArray?) {}
        override fun onLeaveApplicationResult(statusCode: Int) {}
        override fun onStopApplicationResult(statusCode: Int) {}
        override fun onApplicationDisconnected(statusCode: Int) {
            events += "onApplicationDisconnected"
        }

        override fun onSendMessageFailure(namespace: String?, requestId: Long, statusCode: Int) {}
        override fun onSendMessageSuccess(namespace: String?, requestId: Long) {}
        override fun onApplicationStatusChanged(applicationStatus: ApplicationStatus?) {}
        override fun onDeviceStatusChanged(deviceStatus: CastDeviceStatus?) {}
        override fun onConnectedWithResult(statusCode: Int) {
            events += "onConnectedWithResult:$statusCode"
        }

        override fun onConnectionSuspended(reason: Int) {
            assertEquals(GoogleApiClient.ConnectionCallbacks.CAUSE_NETWORK_LOST, reason)
            events += "onConnectionSuspended"
        }
    }
}
