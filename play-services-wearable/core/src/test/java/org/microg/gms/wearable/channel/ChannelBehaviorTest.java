/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.wearable.channel;

import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.OsConstants;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.microg.gms.wearable.proto.AppKey;
import org.microg.gms.wearable.proto.ChannelControlRequest;
import org.microg.gms.wearable.proto.ChannelDataAckRequest;
import org.microg.gms.wearable.proto.ChannelDataHeader;
import org.microg.gms.wearable.proto.ChannelDataRequest;
import org.microg.gms.wearable.proto.ChannelRequest;
import org.microg.gms.wearable.proto.Request;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicReference;

import okio.ByteString;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class ChannelBehaviorTest {
    private static final long CHANNEL_ID = 42L;
    private static final String PEER = "peer-node";

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void omittedDataScalarsUseProtoDefaultsAndAckKeepsZeroIdentity() throws Exception {
        RecordingManager manager = new RecordingManager();
        RecordingTransport transport = new RecordingTransport();
        ChannelToken token = token(true);
        ChannelStateMachine channel = new ChannelStateMachine(token, manager, transport, null,
                ChannelAssetApiEnum.ORIGIN_CHANNEL_API, false, true, null, null);
        channel.connectionState = ChannelStateMachine.CONNECTION_STATE_ESTABLISHED;
        manager.channelTable.put(token, channel);

        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        try {
            channel.inputFd = pipe[1];
            ChannelDataHeader header = new ChannelDataHeader.Builder()
                    .channelId(CHANNEL_ID)
                    // fromChannelOperator and requestId absent: false and zero defaults.
                    .build();
            ChannelDataRequest request = new ChannelDataRequest.Builder()
                    .header(header)
                    .payload(ByteString.of(new byte[]{1, 2, 3}))
                    // finalMessage absent: false default.
                    .build();

            new OnChannelDataTask(manager, PEER, request).execute();
            channel.processIncomingBuffer();

            assertEquals("the omitted operator bit should route to the false/default side",
                    1, manager.dataAcks.size());
            assertEquals(0L, manager.dataAcks.get(0).requestId);
            assertFalse(manager.dataAcks.get(0).isFinal);
            assertArrayEquals(new byte[]{1, 2, 3}, transport.lastWrite);
        } finally {
            pipe[0].close();
            pipe[1].close();
            channel.inputFd = null;
        }
    }

    @Test
    public void queuedDataDistinguishesAbsentIdsFromExplicitDuplicateIds() throws Exception {
        RecordingManager manager = new RecordingManager();
        RecordingTransport transport = new RecordingTransport();
        ChannelToken token = token(true);
        ChannelStateMachine channel = new ChannelStateMachine(token, manager, transport, null,
                ChannelAssetApiEnum.ORIGIN_CHANNEL_API, false, true, null, null);
        channel.connectionState = ChannelStateMachine.CONNECTION_STATE_ESTABLISHED;
        manager.channelTable.put(token, channel);

        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        try {
            channel.inputFd = pipe[1];

            enqueueData(manager, absentRequestIdData(new byte[]{1}));
            enqueueData(manager, absentRequestIdData(new byte[]{2}));
            enqueueData(manager, explicitRequestIdData(0L, new byte[]{3}));
            enqueueData(manager, explicitRequestIdData(9L, new byte[]{4}));
            enqueueData(manager, explicitRequestIdData(9L, new byte[]{99}));

            assertTrue("two absent IDs and distinct explicit IDs should stay queued", channel.hasPendingIncoming());
            channel.processIncomingBuffer();

            assertFalse("the queued chunks should drain", channel.hasPendingIncoming());
            assertEquals(Arrays.asList(0L, 0L, 0L, 9L), ackRequestIds(manager.dataAcks));
            assertEquals(4, transport.writes.size());
            assertArrayEquals(new byte[]{1}, transport.writes.get(0));
            assertArrayEquals(new byte[]{2}, transport.writes.get(1));
            assertArrayEquals(new byte[]{3}, transport.writes.get(2));
            assertArrayEquals(new byte[]{4}, transport.writes.get(3));
        } finally {
            pipe[0].close();
            pipe[1].close();
            channel.inputFd = null;
        }
    }

    private static void enqueueData(RecordingManager manager, ChannelDataRequest request) throws Exception {
        new OnChannelDataTask(manager, PEER, request).execute();
    }

    private static ChannelDataRequest absentRequestIdData(byte[] data) {
        return new ChannelDataRequest.Builder()
                .header(new ChannelDataHeader.Builder().channelId(CHANNEL_ID).build())
                .payload(ByteString.of(data))
                .build();
    }

    private static ChannelDataRequest explicitRequestIdData(long requestId, byte[] data) {
        return new ChannelDataRequest.Builder()
                .header(new ChannelDataHeader.Builder()
                        .channelId(CHANNEL_ID)
                        .requestId(requestId)
                        .build())
                .payload(ByteString.of(data))
                .build();
    }

    private static List<Long> ackRequestIds(List<Ack> acks) {
        List<Long> requestIds = new ArrayList<>();
        for (Ack ack : acks) requestIds.add(ack.requestId);
        return requestIds;
    }

    @Test
    public void omittedAckScalarsReachTheMatchingChannelWithZeroAndFalse() throws Exception {
        RecordingManager manager = new RecordingManager();
        RecordingChannel channel = new RecordingChannel(token(true), manager);
        manager.channelTable.put(channel.token, channel);
        ChannelDataAckRequest ack = new ChannelDataAckRequest.Builder()
                .header(new ChannelDataHeader.Builder().channelId(CHANNEL_ID).build())
                // fromChannelOperator, requestId, and finalMessage all use proto defaults.
                .build();

        new OnChannelDataAckTask(manager, PEER, ack).execute();

        assertEquals(0L, channel.ackRequestId);
        assertFalse(channel.ackIsFinal);
    }

    @Test
    public void omittedCloseErrorAndOperatorScalarsDeliverNormalRemoteClose() throws Exception {
        RecordingManager manager = new RecordingManager();
        RecordingChannel channel = new RecordingChannel(token(true), manager);
        manager.channelTable.put(channel.token, channel);
        ChannelControlRequest control = new ChannelControlRequest.Builder()
                .type(ChannelManager.CHANNEL_CONTROL_TYPE_CLOSE)
                .channelId(CHANNEL_ID)
                // closeErrorCode and fromChannelOperator absent: zero and false defaults.
                .build();
        Request request = new Request.Builder()
                .request(new ChannelRequest.Builder().channelControlRequest(control).build())
                .build();

        new OnChannelControlTask(manager, PEER, null, request).execute();

        assertEquals(0, channel.remoteCloseErrorCode);
        assertEquals(0, manager.channelTable.size());
    }

    @Test
    public void localCloseSendsOneRequestedCodeAndForceCloseDefaultStillNotifies() throws Exception {
        RecordingManager manager = new RecordingManager();
        ChannelToken token = token(true);
        ChannelStateMachine channel = new ChannelStateMachine(token, manager, manager.getTransport(), null,
                ChannelAssetApiEnum.ORIGIN_CHANNEL_API, false, true, null, null);
        channel.connectionState = ChannelStateMachine.CONNECTION_STATE_ESTABLISHED;
        manager.channelTable.put(token, channel);

        invokeDoClose(manager, channel, 7);

        assertEquals(Arrays.asList(7), manager.closeCodes);
        assertEquals(ChannelStateMachine.CONNECTION_STATE_CLOSED, channel.connectionState);
        assertEquals(0, manager.channelTable.size());

        ChannelStateMachine fallback = new ChannelStateMachine(token(true), manager, manager.getTransport(), null,
                ChannelAssetApiEnum.ORIGIN_CHANNEL_API, false, true, null, null);
        fallback.connectionState = ChannelStateMachine.CONNECTION_STATE_ESTABLISHED;
        fallback.forceClose();
        assertEquals(Arrays.asList(7, ChannelStatusCodes.CLOSE_REASON_LOCAL_CLOSE), manager.closeCodes);
    }

    @Test
    public void failedExplicitCloseStillCleansUpAndTriesOneFallbackClose() throws Exception {
        RecordingManager manager = new RecordingManager();
        ChannelToken token = token(true);
        ChannelStateMachine channel = new ChannelStateMachine(token, manager, manager.getTransport(), null,
                ChannelAssetApiEnum.ORIGIN_CHANNEL_API, false, true, null, null);
        channel.connectionState = ChannelStateMachine.CONNECTION_STATE_ESTABLISHED;
        manager.channelTable.put(token, channel);
        manager.failNextClose = true;

        try {
            invokeDoClose(manager, channel, 7);
            fail("expected original close write failure");
        } catch (IOException expected) {
            assertEquals("first close write failed", expected.getMessage());
        }

        assertEquals(Arrays.asList(ChannelStatusCodes.CLOSE_REASON_LOCAL_CLOSE), manager.closeCodes);
        assertEquals(ChannelStateMachine.CONNECTION_STATE_CLOSED, channel.connectionState);
        assertEquals(0, manager.channelTable.size());
    }

    @Test
    public void nestedEpipeCauseIsRecognizedButOtherErrnoIsNot() {
        ErrnoException epipe = new ErrnoException("write", OsConstants.EPIPE);
        ErrnoException eagain = new ErrnoException("write", OsConstants.EAGAIN);
        assertTrue(ChannelTransport.isBrokenPipe(new IOException("outer", new IOException("inner", epipe))));
        assertFalse(ChannelTransport.isBrokenPipe(new IOException("outer", eagain)));
    }

    @Test
    public void boundedFdQueueBlocksProducerUntilThePumpFreesSpace() throws Exception {
        final int queueLimit = 32;
        LinkedBlockingDeque<byte[]> queue = ChannelTransport.newFdReadQueue();
        assertEquals(queueLimit, queue.remainingCapacity());
        for (int i = 0; i < queueLimit; i++) {
            assertTrue(queue.offer(new byte[]{(byte) i}));
        }

        AtomicReference<Throwable> producerFailure = new AtomicReference<>();
        Thread producer = new Thread(() -> {
            try {
                queue.put(new byte[]{99});
            } catch (Throwable t) {
                producerFailure.set(t);
            }
        }, "wearable-bounded-queue-test-producer");
        producer.start();
        long deadline = System.currentTimeMillis() + 5000;
        while (producer.getState() != Thread.State.WAITING && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertTrue("producer should wait while the bounded queue is full", producer.isAlive());
        assertEquals(Thread.State.WAITING, producer.getState());

        queue.take();
        producer.join(5000);
        assertFalse("producer should resume when the pump frees one slot", producer.isAlive());
        assertEquals(null, producerFailure.get());
        assertEquals(queueLimit, queue.size());
    }

    @Test
    public void partialChannelTransportReadsPreserveTheChunkRemainder() throws Exception {
        ChannelTransport transport = new ChannelTransport();
        byte[] expected = new byte[8193];
        for (int i = 0; i < expected.length; i++) expected[i] = (byte) (i * 31);
        File input = temporaryFolder.newFile("channel-input.bin");
        try (FileOutputStream output = new FileOutputStream(input)) {
            output.write(expected);
        }
        ParcelFileDescriptor readFd = ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY);

        try {
            // Exercise the real producer. Injecting a chunk into a running
            // pipe reader can put it behind the reader's EOF marker.
            transport.register(readFd);

            byte[] actual = new byte[expected.length];
            byte[] readBuffer = new byte[777];
            int total = 0;
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (total < actual.length) {
                int count = transport.read(readFd, readBuffer, 0, readBuffer.length);
                if (count == 0) {
                    assertTrue("reader should produce the input before the deadline", System.nanoTime() < deadline);
                    Thread.sleep(1);
                    continue;
                }
                assertTrue("queued chunk should remain readable", count > 0);
                System.arraycopy(readBuffer, 0, actual, total, count);
                total += count;
            }
            assertArrayEquals(expected, actual);
        } finally {
            transport.unregister(readFd);
            readFd.close();
        }
    }

    @Test
    public void zeroLengthSendEmitsFinalFrameAndClosesOnlyAfterAck() throws Exception {
        RecordingManager manager = new RecordingManager();
        RecordingTransport transport = new RecordingTransport();
        ChannelStateMachine channel = new ChannelStateMachine(token(true), manager, transport, null,
                ChannelAssetApiEnum.ORIGIN_CHANNEL_API, false, true, null,
                new Handler(Looper.getMainLooper()));
        channel.connectionState = ChannelStateMachine.CONNECTION_STATE_ESTABLISHED;
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        try {
            channel.setOutputStream(pipe[0], null, 0, 0);
            channel.processOutgoingData();

            assertEquals(1, manager.sentData.size());
            assertArrayEquals(new byte[0], manager.sentData.get(0));
            assertEquals(0L, manager.sentHeaders.get(0).requestId);
            assertTrue(manager.sentHeaders.get(0).isFinal);
            assertEquals("a zero-length send must not read the input", 0, transport.readCalls);
            assertTrue("the output stays open until the final ACK", channel.hasOutputStream());
            assertEquals(ChannelStateMachine.SENDING_STATE_WAITING_FOR_ACK, channel.sendingState);

            channel.processOutgoingData();
            assertEquals("waiting for the ACK must not resend", 1, manager.sentData.size());
            channel.onDataAckReceived(0L, true);

            assertFalse(channel.hasOutputStream());
            assertEquals(ChannelStateMachine.SENDING_STATE_CLOSED, channel.sendingState);
            assertEquals(null, channel.sendPendingOp);
            assertEquals(0L, channel.totalBytesSent);
        } finally {
            if (channel.hasOutputStream()) {
                channel.onChannelOutputClosed(ChannelStatusCodes.CLOSE_REASON_NORMAL, 0);
            }
            pipe[0].close();
            pipe[1].close();
        }
    }

    @Test
    public void optionalScalarDefaultHelpersKeepWireIdentity() {
        assertFalse(ChannelProtocolDefaults.fromChannelOperator(null));
        assertFalse(ChannelProtocolDefaults.finalMessage(null));
        assertEquals(0L, ChannelProtocolDefaults.requestId(null));
        assertEquals(0, ChannelProtocolDefaults.closeErrorCode(null));
        assertEquals(0L, ChannelProtocolDefaults.requestId(0L));
        assertEquals(17L, ChannelProtocolDefaults.requestId(17L));
        assertTrue(ChannelProtocolDefaults.fromChannelOperator(Boolean.TRUE));
        assertTrue(ChannelProtocolDefaults.finalMessage(Boolean.TRUE));
        assertEquals(9, ChannelProtocolDefaults.closeErrorCode(9));
    }

    private static ChannelToken token(boolean localOpener) {
        AppKey appKey = new AppKey.Builder()
                .packageName("org.example.channel-test")
                .signatureDigest("test-signature")
                .build();
        return new ChannelToken(PEER, appKey, CHANNEL_ID, localOpener);
    }

    private static void invokeDoClose(ChannelManager manager, ChannelStateMachine channel, int errorCode)
            throws Exception {
        Method method = ChannelManager.class.getDeclaredMethod(
                "doCloseChannel", ChannelStateMachine.class, int.class);
        method.setAccessible(true);
        try {
            method.invoke(manager, channel, errorCode);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception) throw (Exception) e.getCause();
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    private static LinkedBlockingDeque<byte[]> queueFor(ChannelTransport transport,
                                                         ParcelFileDescriptor fd) throws Exception {
        Field readersField = ChannelTransport.class.getDeclaredField("fdReaders");
        readersField.setAccessible(true);
        Map<ParcelFileDescriptor, ?> readers = (Map<ParcelFileDescriptor, ?>) readersField.get(transport);
        Object reader = readers.get(fd);
        Field queueField = reader.getClass().getDeclaredField("queue");
        queueField.setAccessible(true);
        return (LinkedBlockingDeque<byte[]>) queueField.get(reader);
    }

    private static final class RecordingManager extends ChannelManager {
        final List<Integer> closeCodes = new ArrayList<>();
        final List<Ack> dataAcks = new ArrayList<>();
        final List<byte[]> sentData = new ArrayList<>();
        final List<Ack> sentHeaders = new ArrayList<>();
        boolean failNextClose;

        RecordingManager() {
            super(null, null, "local-node", null);
        }

        @Override
        public void sendCloseRequest(ChannelStateMachine channel, int errorCode) throws IOException {
            if (failNextClose) {
                failNextClose = false;
                throw new IOException("first close write failed");
            }
            closeCodes.add(errorCode);
        }

        @Override
        public boolean sendData(ChannelStateMachine channel, byte[] data, boolean isFinal, long requestId) {
            sentData.add(data);
            sentHeaders.add(new Ack(requestId, isFinal));
            return true;
        }

        @Override
        public void sendDataAck(ChannelStateMachine channel, long offset, boolean isFinal) {
            dataAcks.add(new Ack(offset, isFinal));
        }
    }

    private static final class RecordingTransport extends ChannelTransport {
        byte[] lastWrite;
        final List<byte[]> writes = new ArrayList<>();
        int readCalls;

        @Override
        public void register(ParcelFileDescriptor fd) {
        }

        @Override
        public void unregister(ParcelFileDescriptor fd) {
        }

        @Override
        public int read(ParcelFileDescriptor fd, byte[] buffer, int offset, int length) {
            readCalls++;
            return 0;
        }

        @Override
        public int write(ParcelFileDescriptor fd, byte[] buffer, int offset, int length) {
            lastWrite = Arrays.copyOfRange(buffer, offset, offset + length);
            writes.add(lastWrite);
            return length;
        }

        @Override
        public void setMode(ParcelFileDescriptor fd, IOMode mode) {
        }
    }

    private static final class RecordingChannel extends ChannelStateMachine {
        long ackRequestId = Long.MIN_VALUE;
        boolean ackIsFinal;
        int remoteCloseErrorCode = Integer.MIN_VALUE;

        RecordingChannel(ChannelToken token, ChannelManager manager) {
            super(token, manager, manager.getTransport(), null,
                    ChannelAssetApiEnum.ORIGIN_CHANNEL_API, false, true, null, null);
        }

        @Override
        public void onDataAckReceived(long ackOffset, boolean isFinal) {
            ackRequestId = ackOffset;
            ackIsFinal = isFinal;
        }

        @Override
        public void onRemoteCloseReceived(int errorCode) {
            remoteCloseErrorCode = errorCode;
        }
    }

    private static final class Ack {
        final long requestId;
        final boolean isFinal;

        Ack(long requestId, boolean isFinal) {
            this.requestId = requestId;
            this.isFinal = isFinal;
        }
    }
}
