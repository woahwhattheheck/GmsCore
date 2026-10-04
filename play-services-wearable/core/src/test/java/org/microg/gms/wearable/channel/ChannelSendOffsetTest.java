/*
 * SPDX-License-Identifier: Apache-2.0
 */
package org.microg.gms.wearable.channel;

import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.microg.gms.wearable.proto.AppKey;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class ChannelSendOffsetTest {
    @Test
    public void unavailableOffsetInputYieldsAndResumesWithoutSkippingPayload() throws Exception {
        RecordingManager manager = new RecordingManager();
        PausingTransport transport = new PausingTransport();
        ChannelStateMachine channel = channel(manager, transport);
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        try {
            channel.setOutputStream(pipe[0], null, 5, 3);
            channel.processOutgoingData();
            assertEquals(1, transport.readCalls);
            assertEquals(ChannelStateMachine.SENDING_STATE_WAITING_TO_READ, channel.sendingState);
            assertNull(channel.sendPendingOp);
            assertTrue(manager.sentData.isEmpty());
            assertEquals(0L, channel.sequenceNumber);

            transport.feed(new byte[]{10, 11});
            channel.processOutgoingData();
            assertEquals(3, transport.readCalls);
            assertEquals(ChannelStateMachine.SENDING_STATE_WAITING_TO_READ, channel.sendingState);
            assertNull(channel.sendPendingOp);
            assertTrue(manager.sentData.isEmpty());
            assertEquals(0L, channel.totalBytesSent);

            transport.feed(new byte[]{12, 13, 14, 1, 2, 3});
            channel.processOutgoingData();
            assertEquals(5, transport.readCalls);
            assertEquals(1, manager.sentData.size());
            assertArrayEquals(new byte[]{1, 2, 3}, manager.sentData.get(0));
            assertTrue(manager.lastFinal);
            assertEquals(0L, manager.lastRequestId);
            assertEquals(3L, channel.totalBytesSent);
            assertEquals(8L, channel.currentSendOffset);
            assertEquals(ChannelStateMachine.SENDING_STATE_WAITING_FOR_ACK, channel.sendingState);
        } finally {
            channel.forceClose(false);
            pipe[0].close();
            pipe[1].close();
        }
    }

    @Test
    public void endOfInputBeforeOffsetRemainsAnErrorWithoutSendingData() throws Exception {
        RecordingManager manager = new RecordingManager();
        PausingTransport transport = new PausingTransport();
        ChannelStateMachine channel = channel(manager, transport);
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        try {
            channel.setOutputStream(pipe[0], null, 5, 3);
            transport.feed(new byte[]{10, 11});
            transport.eof = true;
            try {
                channel.processOutgoingData();
                fail("Expected EOF before the requested offset");
            } catch (IOException expected) {
                assertEquals("EOF while skipping bytes", expected.getMessage());
            }
            assertEquals(2, transport.readCalls);
            assertTrue(manager.sentData.isEmpty());
            assertEquals(0L, channel.sequenceNumber);
            assertEquals(0L, channel.totalBytesSent);
            assertNull(channel.sendPendingOp);
        } finally {
            channel.forceClose(false);
            pipe[0].close();
            pipe[1].close();
        }
    }

    private static ChannelStateMachine channel(RecordingManager manager, PausingTransport transport) {
        AppKey appKey = new AppKey.Builder().packageName("org.example.channel-offset-test")
                .signatureDigest("test-signature").build();
        ChannelToken token = new ChannelToken("peer-node", appKey, 42L, true);
        ChannelStateMachine channel = new ChannelStateMachine(token, manager, transport, null,
                ChannelAssetApiEnum.ORIGIN_CHANNEL_API, false, true, null,
                new Handler(Looper.getMainLooper()));
        channel.connectionState = ChannelStateMachine.CONNECTION_STATE_ESTABLISHED;
        return channel;
    }

    private static final class RecordingManager extends ChannelManager {
        final List<byte[]> sentData = new ArrayList<>();
        boolean lastFinal;
        long lastRequestId;

        RecordingManager() { super(null, null, "local-node", null); }

        @Override
        public boolean sendData(ChannelStateMachine channel, byte[] data, boolean isFinal, long requestId) {
            sentData.add(data);
            lastFinal = isFinal;
            lastRequestId = requestId;
            return true;
        }
    }

    private static final class PausingTransport extends ChannelTransport {
        byte[] input = new byte[0];
        int position;
        int readCalls;
        boolean returnedUnavailable;
        boolean eof;

        void feed(byte[] bytes) {
            assertEquals(input.length, position);
            input = bytes;
            position = 0;
            returnedUnavailable = false;
        }

        @Override
        public int read(ParcelFileDescriptor fd, byte[] buffer, int offset, int length) {
            if (length == 0) return 0;
            readCalls++;
            if (position == input.length) {
                if (eof) return -1;
                // Fail deterministically instead of allowing the old busy loop
                // to hang the runner or depending on a wall-clock timeout.
                assertFalse("The channel must yield after an unavailable read", returnedUnavailable);
                returnedUnavailable = true;
                return 0;
            }
            int count = Math.min(length, input.length - position);
            System.arraycopy(input, position, buffer, offset, count);
            position += count;
            return count;
        }

        @Override public void register(ParcelFileDescriptor fd) {}
        @Override public void unregister(ParcelFileDescriptor fd) {}
        @Override public void setMode(ParcelFileDescriptor fd, IOMode mode) {}
    }
}
