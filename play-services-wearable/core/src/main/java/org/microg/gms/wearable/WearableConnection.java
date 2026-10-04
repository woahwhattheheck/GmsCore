/*
 * SPDX-FileCopyrightText: 2015, microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.wearable;

import android.util.Log;

import org.microg.gms.wearable.proto.Connect;
import org.microg.gms.wearable.proto.MessagePiece;
import org.microg.gms.wearable.proto.RootMessage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import okio.ByteString;

public abstract class WearableConnection implements Runnable {
    private static String B64ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    private final String TAG = "WearableConnection";

    public static final int DEFAULT_MAX_PIECE_SIZE = 12288;
    private static final int MAX_TOTAL_PIECES = 4096;
    private static final int MAX_OPEN_PIECE_QUEUES = 8;
    private static final long MAX_QUEUED_REASSEMBLY_BYTES = 16L * 1024 * 1024;
    private final Object writeLock = new Object();

    private HashMap<Integer, List<MessagePiece>> piecesQueues = new HashMap<Integer, List<MessagePiece>>();
    private long queuedReassemblyBytes;
    private final Listener listener;
    private Connect peerConnect = null;

    public WearableConnection(Listener listener) {
        this.listener = listener;
    }

    protected int getMaxPieceSize() {
        return DEFAULT_MAX_PIECE_SIZE;
    }

    protected int getWriteQueueId() {
        return 0;
    }

    public static String base64encode(byte[] bytes) {
        int paddingCount = (3 - (bytes.length % 3)) % 3;
        byte[] padded = new byte[bytes.length + paddingCount];
        System.arraycopy(bytes, 0, padded, 0, bytes.length);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i += 3) {
            int j = ((padded[i] & 0xff) << 16) + ((padded[i + 1] & 0xff) << 8) + (padded[i + 2] & 0xff);
            sb.append(B64ALPHABET.charAt((j >> 18) & 0x3f)).append(B64ALPHABET.charAt((j >> 12) & 0x3f))
                    .append(B64ALPHABET.charAt((j >> 6) & 0x3f)).append(B64ALPHABET.charAt(j & 0x3f));
        }
        return sb.substring(0, sb.length() - paddingCount);
    }

    public static String calculateDigest(byte[] bytes) {
        try {
            return base64encode(MessageDigest.getInstance("SHA1").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA1 not supported => platform not supported");
        }
    }

    public void writeMessage(RootMessage message) throws IOException {
        byte[] bytes = RootMessage.ADAPTER.encode(message);
        if (!withinReassemblyByteLimit(0, bytes.length)) {
            throw new IOException("Wearable message exceeds "
                    + MAX_QUEUED_REASSEMBLY_BYTES + " bytes");
        }
        final String digest = calculateDigest(bytes);
        final int maxPieceSize = Math.max(1, getMaxPieceSize());
        final int queueId = getWriteQueueId();
        final int total = Math.max(1, (bytes.length + maxPieceSize - 1) / maxPieceSize);

        synchronized (writeLock) {
            for (int i = 0; i < total; i++) {
                int offset =  i * maxPieceSize;
                int length = Math.min(bytes.length - offset, maxPieceSize);
                writeMessagePiece(new MessagePiece.Builder()
                        .data(ByteString.of(bytes, offset, length))
                        .digest(digest)
                        .thisPiece(i + 1)
                        .totalPieces(total)
                        .queueId(queueId)
                        .build()
                );
            }
        }

        if (total > 1) {
            Log.d(TAG, "writeMessage: sent " + bytes.length + " bytes as " + total
                    + " pieces (queueId=" + queueId + ", digest=" + digest + ")");
        }
    }

    protected abstract void writeMessagePiece(MessagePiece piece) throws IOException;

    protected RootMessage readMessage() throws IOException {
        while (true) {
            MessagePiece piece = readMessagePiece();
            if (piece == null || piece.data == null || piece.digest == null || piece.thisPiece == null
                    || piece.totalPieces == null || piece.queueId == null) {
                throw new IOException("Malformed message piece: missing required fields");
            }
            if (piece.totalPieces == 1) {
                if (piece.thisPiece != 1 || !withinReassemblyByteLimit(0, piece.data.size())) {
                    throw new IOException("Malformed single-piece message");
                }
                byte[] payload = piece.data.toByteArray();
                String calc = calculateDigest(payload);
                if (!calc.equals(piece.digest)) {
                    throw new IOException("Digest mismatch for single-piece message");
                }
                return RootMessage.ADAPTER.decode(piece.data);
            }

            // Bound both list allocation and aggregate retained payload bytes. The byte
            // cap is shared across all in-flight queue IDs, so eight partial messages
            // cannot each independently consume a full-message allowance.
            if (piece.totalPieces < 2 || piece.totalPieces > MAX_TOTAL_PIECES
                    || piece.thisPiece < 1 || piece.thisPiece > piece.totalPieces) {
                throw new IOException("Malformed message piece: thisPiece=" + piece.thisPiece
                        + " totalPieces=" + piece.totalPieces);
            }

            synchronized (piecesQueues) {
                List<MessagePiece> queue = piecesQueues.get(piece.queueId);

                if (piece.thisPiece == 1) {
                    if (queue != null) {
                        removePieceQueue(piece.queueId);
                    }
                    if (piecesQueues.size() >= MAX_OPEN_PIECE_QUEUES) {
                        throw new IOException("Too many open piece queues (" + MAX_OPEN_PIECE_QUEUES + ")");
                    }
                    if (!withinReassemblyByteLimit(queuedReassemblyBytes, piece.data.size())) {
                        throw new IOException("Wearable message reassembly exceeds "
                                + MAX_QUEUED_REASSEMBLY_BYTES + " bytes");
                    }
                    queue = new ArrayList<>(piece.totalPieces);
                    queue.add(piece);
                    piecesQueues.put(piece.queueId, queue);
                    queuedReassemblyBytes += piece.data.size();
                    continue;
                }

                if (queue == null || !queue.get(0).digest.equals(piece.digest)) {
                    removePieceQueue(piece.queueId);
                    throw new IOException("Received " + piece.thisPiece + " before first piece.");
                }

                if (!queue.get(0).totalPieces.equals(piece.totalPieces)) {
                    removePieceQueue(piece.queueId);
                    throw new IOException("Inconsistent totalPieces for queue " + piece.queueId);
                }

                if (queue.size() + 1 != piece.thisPiece) {
                    removePieceQueue(piece.queueId);
                    throw new IOException("Received " + piece.thisPiece + " but expected piece" + queue.size() + 1);
                }

                if (!withinReassemblyByteLimit(queuedReassemblyBytes, piece.data.size())) {
                    removePieceQueue(piece.queueId);
                    throw new IOException("Wearable message reassembly exceeds "
                            + MAX_QUEUED_REASSEMBLY_BYTES + " bytes");
                }
                queue.add(piece);
                queuedReassemblyBytes += piece.data.size();

                if (piece.thisPiece == piece.totalPieces) {
                    removePieceQueue(piece.queueId);
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();

                    for (MessagePiece messagePiece : queue) {
                        messagePiece.data.write(bos);
                    }

                    byte[] bytes = bos.toByteArray();
                    if (!calculateDigest(bytes).equals(piece.digest)) {
                        throw new IOException("Merged pieces have digest " + calculateDigest(bytes) + ", but should be " + piece.digest);
                    }

                    return RootMessage.ADAPTER.decode(bytes);
                }

            }
        }
    }

    static boolean withinReassemblyByteLimit(long queuedBytes, int additionalBytes) {
        return queuedBytes >= 0 && queuedBytes <= MAX_QUEUED_REASSEMBLY_BYTES
                && additionalBytes >= 0
                && additionalBytes <= MAX_QUEUED_REASSEMBLY_BYTES - queuedBytes;
    }

    // Caller holds the piecesQueues monitor.
    private List<MessagePiece> removePieceQueue(int queueId) {
        List<MessagePiece> removed = piecesQueues.remove(queueId);
        if (removed != null) {
            for (MessagePiece piece : removed) {
                queuedReassemblyBytes -= piece.data.size();
            }
        }
        return removed;
    }

    private void clearPieceQueues() {
        synchronized (piecesQueues) {
            piecesQueues.clear();
            queuedReassemblyBytes = 0;
        }
    }

    protected abstract MessagePiece readMessagePiece() throws IOException;

    public abstract void close() throws IOException;

    public Connect getPeerConnect() {
        return peerConnect;
    }

    public void setPeerConnect(Connect peerConnect) {
        this.peerConnect = peerConnect;
    }
    public String getRemoteAddress() { return null; }
    @Override
    public void run() {
        try {
            listener.onConnected(this);
            RootMessage message;
            while ((message = readMessage()) != null) {
                try {
                    listener.onMessage(this, message);
                } catch (Exception e) {
                    Log.e(TAG, "Error processing message", e);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Connection error", e);
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error in connection", e);
        } finally {
            clearPieceQueues();
            System.out.println("WearableConnection closed");
            try {
                listener.onDisconnected();
            } catch (Exception e) {
                Log.e(TAG, "Error in onDisconnected callback", e);
            }
        }
    }

    public static final Listener NOOP = new Listener() {
        @Override public void onConnected(WearableConnection c) {}
        @Override public void onMessage(WearableConnection c, RootMessage m) {}
        @Override public void onDisconnected() {}
    };
    public interface Listener {
        void onConnected(WearableConnection connection);
        void onMessage(WearableConnection connection, RootMessage message);
        void onDisconnected();
    }

    public boolean isClosed() {
        return false;
    }
}
