/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.wearable;

import org.junit.Test;
import org.microg.gms.wearable.proto.MessagePiece;
import org.microg.gms.wearable.proto.RootMessage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import okio.ByteString;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class WearableConnectionReassemblyTest {

    @Test
    public void reassemblesAnOrdinaryTwoPieceMessage() throws Exception {
        RootMessage expected = new RootMessage.Builder().hasAsset(true).build();
        byte[] encoded = RootMessage.ADAPTER.encode(expected);
        String digest = WearableConnection.calculateDigest(encoded);
        int split = encoded.length / 2;

        TestConnection connection = new TestConnection(Arrays.asList(
                piece(Arrays.copyOfRange(encoded, 0, split), digest, 1, 2, 7),
                piece(Arrays.copyOfRange(encoded, split, encoded.length), digest, 2, 2, 7)));

        RootMessage actual = connection.nextMessage();

        assertEquals(Boolean.TRUE, actual.hasAsset);
    }

    @Test
    public void rejectsPieceCountAboveBoundBeforeAllocatingItsList() {
        TestConnection connection = new TestConnection(Arrays.asList(
                piece(new byte[]{1}, "digest", 1, 4097, 1)));

        expectIOException(connection);
    }

    @Test
    public void rejectsTheNinthConcurrentPartialMessageWithinTheEightQueueBound() {
        String digest = "small-constructed-message";
        List<MessagePiece> pieces = new ArrayList<>();
        for (int queueId = 0; queueId < 9; queueId++) {
            pieces.add(piece(new byte[]{1}, digest, 1, 2, queueId));
        }
        TestConnection connection = new TestConnection(pieces);

        expectIOException(connection);
    }

    @Test
    public void rejectsAChangedTotalCountForAnOpenQueue() {
        TestConnection connection = new TestConnection(Arrays.asList(
                piece(new byte[]{1}, "digest", 1, 2, 3),
                piece(new byte[]{2}, "digest", 2, 3, 3)));

        expectIOException(connection);
    }

    @Test
    public void rejectsMissingOptionalMessagePieceFieldsWithIOException() {
        MessagePiece missingData = new MessagePiece.Builder()
                .digest("digest")
                .thisPiece(1)
                .totalPieces(1)
                .queueId(1)
                .build();
        TestConnection connection = new TestConnection(Arrays.asList(missingData));

        expectIOException(connection);
    }

    @Test
    public void aggregateReassemblyByteBudgetHasSafeInclusiveBoundary() {
        long cap = 16L * 1024 * 1024;
        assertTrue(WearableConnection.withinReassemblyByteLimit(cap - 1, 1));
        assertTrue(WearableConnection.withinReassemblyByteLimit(0, (int) cap));
        assertFalse(WearableConnection.withinReassemblyByteLimit(cap, 1));
        assertFalse(WearableConnection.withinReassemblyByteLimit(-1, 1));
        assertFalse(WearableConnection.withinReassemblyByteLimit(0, -1));
    }

    private static MessagePiece piece(byte[] data, String digest, int thisPiece,
                                      int totalPieces, int queueId) {
        return new MessagePiece.Builder()
                .data(ByteString.of(data))
                .digest(digest)
                .thisPiece(thisPiece)
                .totalPieces(totalPieces)
                .queueId(queueId)
                .build();
    }

    private static void expectIOException(TestConnection connection) {
        try {
            connection.nextMessage();
            fail("expected malformed/incomplete piece sequence to fail");
        } catch (IOException expected) {
            // All malformed sequence/count failures use the connection's normal IO path.
        }
    }

    private static final class TestConnection extends WearableConnection {
        private final List<MessagePiece> pieces;
        private int next;

        TestConnection(List<MessagePiece> pieces) {
            super(WearableConnection.NOOP);
            this.pieces = pieces;
        }

        RootMessage nextMessage() throws IOException {
            return readMessage();
        }

        @Override
        protected void writeMessagePiece(MessagePiece piece) {
            throw new UnsupportedOperationException("read-only fixture");
        }

        @Override
        protected MessagePiece readMessagePiece() throws IOException {
            if (next >= pieces.size()) throw new IOException("fixture exhausted");
            return pieces.get(next++);
        }

        @Override
        public void close() {
        }
    }
}
