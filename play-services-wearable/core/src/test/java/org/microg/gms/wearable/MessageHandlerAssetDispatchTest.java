/* SPDX-License-Identifier: Apache-2.0 */

package org.microg.gms.wearable;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.microg.gms.wearable.proto.AckAsset;
import org.microg.gms.wearable.proto.AppKey;
import org.microg.gms.wearable.proto.FetchAsset;
import org.microg.gms.wearable.proto.MessagePiece;
import org.microg.gms.wearable.proto.RootMessage;
import org.microg.gms.wearable.proto.FilePiece;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class MessageHandlerAssetDispatchTest {
    private RecordingAssetManager assetManager;
    private WearableConnection connection;

    @Before
    public void setUp() {
        assetManager = new RecordingAssetManager();
        connection = new WearableConnection(null) {
            @Override
            protected void writeMessagePiece(MessagePiece piece) {
            }

            @Override
            protected MessagePiece readMessagePiece() {
                return null;
            }

            @Override
            public void close() {
            }
        };
    }

    @After
    public void tearDown() {
        assetManager.shutdown();
    }

    @Test
    public void fetchAssetIsRoutedToTheActivePeerManager() {
        FetchAsset fetch = new FetchAsset.Builder()
                .assetName("asset-digest")
                .packageName("example.app")
                .signatureDigest("signature")
                .build();

        assertTrue(MessageHandler.dispatchFetchAsset(assetManager, connection, "peer-1", fetch));
        assertSame(connection, assetManager.connection);
        assertEquals("peer-1", assetManager.sourceNodeId);
        assertSame(fetch, assetManager.fetchAsset);
    }

    @Test
    public void fetchAssetWithoutAnActiveConnectionIsIgnored() {
        FetchAsset fetch = new FetchAsset.Builder().assetName("asset-digest").build();

        assertFalse(MessageHandler.dispatchFetchAsset(assetManager, null, "peer-1", fetch));
        assertNull(assetManager.connection);
        assertNull(assetManager.fetchAsset);
    }

    @Test
    public void assetAcknowledgementClearsThroughTheAssetManager() {
        assertTrue(MessageHandler.dispatchAssetAck(assetManager, new AckAsset("asset-digest")));
        assertEquals("asset-digest", assetManager.acknowledgedDigest);
    }

    @Test
    public void missingAcknowledgementIsIgnored() {
        assertFalse(MessageHandler.dispatchAssetAck(assetManager, null));
        assertNull(assetManager.acknowledgedDigest);
    }

    @Test
    public void receivedAssetCompletesThroughTheAssetManager() {
        assertTrue(MessageHandler.dispatchAssetReceived(assetManager, "asset-digest"));
        assertEquals("asset-digest", assetManager.receivedDigest);
    }

    @Test
    public void missingReceivedAssetDigestIsIgnored() {
        assertFalse(MessageHandler.dispatchAssetReceived(assetManager, null));
        assertNull(assetManager.receivedDigest);
    }

    @Test
    public void assetDigestValidationRejectsPathComponents() {
        String digest = WearableConnection.calculateDigest(new byte[0]);
        assertTrue(AssetManager.isValidAssetDigest(digest));
        assertFalse(AssetManager.isValidAssetDigest("../outside"));
        assertFalse(AssetManager.isValidAssetDigest("short"));
        assertFalse(AssetManager.isValidAssetDigest(null));
    }

    @Test
    public void storedFileContentsMustMatchTheAnnouncedDigest() throws Exception {
        File file = File.createTempFile("wear-asset-hash-", ".bin");
        byte[] original = new byte[]{1, 2, 3};
        try {
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(original);
            }
            assertEquals(WearableConnection.calculateDigest(original),
                    AssetManager.calculateFileDigest(file));

            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(new byte[]{3, 2, 1});
            }
            assertFalse(WearableConnection.calculateDigest(original)
                    .equals(AssetManager.calculateFileDigest(file)));
        } finally {
            file.delete();
        }
    }

    @Test
    public void retrySelectionFallsBackToAnotherActivePeer() {
        String failedPeer = "peer-a";
        Map<String, WearableConnection> connections = new LinkedHashMap<>();
        connections.put("peer-a", new CapturingConnection());
        connections.put("peer-b", new CapturingConnection());

        assertEquals("peer-b", AssetFetcher.selectRetryPeer(failedPeer,
                Collections.singleton(failedPeer), connections));
    }

    @Test
    public void missingAssetResponseTimesOutRetriesAndThenCompletes() {
        ManualScheduler scheduler = new ManualScheduler();
        AssetFetcher fetcher = new AssetFetcher(null, scheduler, new SilentAssetFetchLog());
        DataItemRecord record = new DataItemRecord();
        record.source = "peer-a";
        record.packageName = "example.app";
        record.signatureDigest = "signature";
        String digest = WearableConnection.calculateDigest(new byte[]{4, 5, 6});
        CapturingConnection connection = new CapturingConnection();
        CapturingConnection alternateConnection = new CapturingConnection();
        Map<String, WearableConnection> activeConnections = new LinkedHashMap<>();
        activeConnections.put("peer-a", connection);
        activeConnections.put("peer-b", alternateConnection);
        List<com.google.android.gms.wearable.Asset> missing = Collections.singletonList(
                com.google.android.gms.wearable.Asset.createFromRef(digest));

        fetcher.fetchMissingAssetsForRecord(connection, "peer-a", record, missing,
                activeConnections, null);
        assertEquals(1, connection.messages.size());
        assertEquals(1, fetcher.getStats().currentlyFetching);
        assertEquals(AssetFetcher.FETCH_RESPONSE_TIMEOUT_MS, (long) scheduler.delays.get(0));

        fetcher.onAssetTransferStarted(digest, "peer-a"); // a data-null SetAsset header is not completion
        assertEquals(1, fetcher.getStats().currentlyFetching);
        assertEquals(AssetFetcher.FETCH_TRANSFER_TIMEOUT_MS, (long) scheduler.delays.get(1));

        scheduler.runNext();
        assertEquals(1, fetcher.getStats().currentlyFetching); // stale response timer did not cancel transfer
        assertEquals(0, fetcher.getStats().retrying);

        scheduler.runNext();
        assertEquals(0, fetcher.getStats().currentlyFetching);
        assertEquals(1, fetcher.getStats().retrying);
        assertEquals(5000L, (long) scheduler.delays.get(0));

        scheduler.runNext();
        assertEquals(1, connection.messages.size());
        assertEquals(1, alternateConnection.messages.size());
        assertEquals(1, fetcher.getStats().currentlyFetching);

        fetcher.onAssetReceived(digest);
        scheduler.runNext(); // stale timeout from the retried request is ignored
        assertEquals(0, fetcher.getStats().currentlyFetching);
        assertEquals(0, fetcher.getStats().retrying);
    }

    @Test
    public void assetFetchRetriesStopAtTheConfiguredLimit() {
        ManualScheduler scheduler = new ManualScheduler();
        AssetFetcher fetcher = new AssetFetcher(null, scheduler, new SilentAssetFetchLog());
        DataItemRecord record = new DataItemRecord();
        record.source = "peer-a";
        record.packageName = "example.app";
        record.signatureDigest = "signature";
        String digest = WearableConnection.calculateDigest(new byte[]{7, 8, 9});
        CapturingConnection connection = new CapturingConnection();
        List<com.google.android.gms.wearable.Asset> missing = Collections.singletonList(
                com.google.android.gms.wearable.Asset.createFromRef(digest));

        fetcher.fetchMissingAssetsForRecord(connection, record, missing);
        for (int attempt = 1; attempt <= AssetFetcher.MAX_RETRY_COUNT; attempt++) {
            scheduler.runNext(); // response/transfer timeout
            if (attempt < AssetFetcher.MAX_RETRY_COUNT) {
                scheduler.runNext(); // bounded retry after cooldown
            }
        }

        assertEquals(AssetFetcher.MAX_RETRY_COUNT, connection.messages.size());
        assertEquals(0, fetcher.getStats().currentlyFetching);
        assertEquals(1, fetcher.getStats().failed);
        assertTrue(scheduler.tasks.isEmpty());
    }

    @Test
    public void lateFailureFromTimedOutPeerDoesNotFailTheReplacementPeer() {
        ManualScheduler scheduler = new ManualScheduler();
        AssetFetcher fetcher = new AssetFetcher(null, scheduler, new SilentAssetFetchLog());
        DataItemRecord record = new DataItemRecord();
        record.source = "peer-a";
        record.packageName = "example.app";
        record.signatureDigest = "signature";
        String digest = WearableConnection.calculateDigest(new byte[]{2, 4, 6});
        CapturingConnection connection = new CapturingConnection();
        CapturingConnection alternateConnection = new CapturingConnection();
        Map<String, WearableConnection> activeConnections = new LinkedHashMap<>();
        activeConnections.put("peer-a", connection);
        activeConnections.put("peer-b", alternateConnection);
        List<com.google.android.gms.wearable.Asset> missing = Collections.singletonList(
                com.google.android.gms.wearable.Asset.createFromRef(digest));

        fetcher.fetchMissingAssetsForRecord(connection, "peer-a", record, missing,
                activeConnections, null);
        scheduler.runNext(); // peer A times out and queues the retry
        scheduler.runNext(); // retry installs a pending request for peer B
        assertEquals(1, alternateConnection.messages.size());
        assertEquals(1, fetcher.getStats().currentlyFetching);

        fetcher.onAssetFetchFailed(digest, "peer-a"); // late A piece must not clear B's request
        assertEquals(1, fetcher.getStats().currentlyFetching);
        assertEquals(1, fetcher.getStats().retrying);

        fetcher.onAssetReceived(digest);
        assertEquals(0, fetcher.getStats().currentlyFetching);
        assertEquals(0, fetcher.getStats().retrying);
    }

    @Test
    public void transferStartFromOldPeerDoesNotExtendReplacementPeerTimeout() {
        ManualScheduler scheduler = new ManualScheduler();
        AssetFetcher fetcher = new AssetFetcher(null, scheduler, new SilentAssetFetchLog());
        DataItemRecord record = new DataItemRecord();
        record.source = "peer-a";
        record.packageName = "example.app";
        record.signatureDigest = "signature";
        String digest = WearableConnection.calculateDigest(new byte[]{8, 6, 4});
        CapturingConnection connection = new CapturingConnection();
        CapturingConnection alternateConnection = new CapturingConnection();
        Map<String, WearableConnection> activeConnections = new LinkedHashMap<>();
        activeConnections.put("peer-a", connection);
        activeConnections.put("peer-b", alternateConnection);
        List<com.google.android.gms.wearable.Asset> missing = Collections.singletonList(
                com.google.android.gms.wearable.Asset.createFromRef(digest));

        fetcher.fetchMissingAssetsForRecord(connection, "peer-a", record, missing,
                activeConnections, null);
        scheduler.runNext(); // peer A response timeout
        scheduler.runNext(); // retry installs a pending request for peer B
        assertEquals(1, alternateConnection.messages.size());
        assertEquals(1, scheduler.tasks.size());
        assertEquals(AssetFetcher.FETCH_RESPONSE_TIMEOUT_MS, (long) scheduler.delays.get(0));

        fetcher.onAssetTransferStarted(digest, "peer-a"); // stale metadata from A
        assertEquals(1, scheduler.tasks.size());
        assertEquals(AssetFetcher.FETCH_RESPONSE_TIMEOUT_MS, (long) scheduler.delays.get(0));

        fetcher.onAssetTransferStarted(digest, "peer-b"); // current peer may extend to transfer timeout
        assertEquals(2, scheduler.tasks.size());
        assertEquals(AssetFetcher.FETCH_TRANSFER_TIMEOUT_MS, (long) scheduler.delays.get(1));

        scheduler.runNext(); // stale peer B response timer is now superseded
        assertEquals(1, fetcher.getStats().currentlyFetching);
        scheduler.runNext(); // peer B transfer timeout remains authoritative
        assertEquals(0, fetcher.getStats().currentlyFetching);
        assertEquals(1, fetcher.getStats().retrying);
    }

    @Test
    public void retryableFailuresKeepFetchOpenAndNotifyOnlyAfterExhaustion() {
        ManualScheduler scheduler = new ManualScheduler();
        List<String> exhausted = new ArrayList<>();
        AssetFetcher fetcher = new AssetFetcher(null, scheduler, new SilentAssetFetchLog(), exhausted::add);
        DataItemRecord record = new DataItemRecord();
        record.source = "peer-a";
        record.packageName = "example.app";
        record.signatureDigest = "signature";
        String digest = WearableConnection.calculateDigest(new byte[]{3, 5, 7});
        CapturingConnection connection = new CapturingConnection();
        List<com.google.android.gms.wearable.Asset> missing = Collections.singletonList(
                com.google.android.gms.wearable.Asset.createFromRef(digest));

        fetcher.fetchMissingAssetsForRecord(connection, record, missing);
        for (int attempt = 1; attempt <= AssetFetcher.MAX_RETRY_COUNT; attempt++) {
            fetcher.onAssetFetchFailed(digest, "peer-a");
            assertTrue("attempt=" + attempt, exhausted.isEmpty() || attempt == AssetFetcher.MAX_RETRY_COUNT);
            if (attempt < AssetFetcher.MAX_RETRY_COUNT) {
                int expectedRequests = attempt + 1;
                while (connection.messages.size() < expectedRequests) scheduler.runNext();
            }
        }

        assertEquals(Collections.singletonList(digest), exhausted);
        assertEquals(AssetFetcher.MAX_RETRY_COUNT, connection.messages.size());
        assertEquals(0, fetcher.getStats().currentlyFetching);
        assertEquals(1, fetcher.getStats().failed);
    }

    @Test
    public void metadataCompletesPermissionFetchButNotContentFetch() {
        assertTrue(AssetManager.shouldCompleteOnMetadata(true));
        assertFalse(AssetManager.shouldCompleteOnMetadata(false));
    }

    @Test
    public void fetchRespondsOnlyForAnExactStoredAppKey() {
        List<AppKey> allowed = Arrays.asList(
                new AppKey("example.app", "signature-one"),
                new AppKey("other.app", "signature-two"));

        assertEquals("example.app", AssetManager.resolveAppKeys(allowed,
                "example.app", "signature-one").appKeys.get(0).packageName);
        assertNull(AssetManager.resolveAppKeys(allowed, "example.app", "wrong-signature"));
        assertNull(AssetManager.resolveAppKeys(allowed, "unknown.app", "signature-one"));
        assertNull(AssetManager.resolveAppKeys(allowed, "example.app", null));
    }

    @Test
    public void emptyFinalFilePieceIsAcceptedButMalformedPiecesAreRejected() {
        String fileName = WearableConnection.calculateDigest(new byte[]{1});
        String digest = WearableConnection.calculateDigest(new byte[0]);

        byte[] empty = AssetManager.getFilePieceData(new FilePiece(fileName, true, null, digest));
        assertNotNull(empty);
        assertEquals(0, empty.length);
        assertNull(AssetManager.getFilePieceData(new FilePiece(fileName, false, null, null)));
        assertNull(AssetManager.getFilePieceData(new FilePiece("../outside", true, null, digest)));
        assertNull(AssetManager.getFilePieceData(new FilePiece(fileName, true, null, "bad-digest")));
    }

    @Test
    public void outgoingFilePiecesPreserveEmptyAndChunkBoundaryPayloads() throws Exception {
        int[] lengths = {0, 1, AssetManager.CHUNK_SIZE - 1, AssetManager.CHUNK_SIZE,
                AssetManager.CHUNK_SIZE + 1, 2 * AssetManager.CHUNK_SIZE,
                2 * AssetManager.CHUNK_SIZE + 1};
        String fileName = WearableConnection.calculateDigest(new byte[]{9, 8, 7});

        for (int length : lengths) {
            byte[] input = new byte[length];
            for (int i = 0; i < input.length; i++) input[i] = (byte) (i * 31);
            String digest = WearableConnection.calculateDigest(input);
            File file = File.createTempFile("wear-asset-", ".bin");
            CapturingConnection capture = new CapturingConnection();
            try {
                try (FileOutputStream output = new FileOutputStream(file)) {
                    output.write(input);
                }
                assertEquals(digest, AssetManager.calculateFileDigest(file));

                AssetManager.streamFilePieces(capture, file, fileName, digest);

                int expectedPieceCount = Math.max(1,
                        (length + AssetManager.CHUNK_SIZE - 1) / AssetManager.CHUNK_SIZE);
                assertEquals("length=" + length, expectedPieceCount, capture.messages.size());
                ByteArrayOutputStream joined = new ByteArrayOutputStream();
                for (int i = 0; i < capture.messages.size(); i++) {
                    FilePiece piece = capture.messages.get(i).filePiece;
                    assertEquals(fileName, piece.fileName);
                    assertEquals(i == capture.messages.size() - 1, piece.finalPiece);
                    assertEquals(i == capture.messages.size() - 1 ? digest : null, piece.digest);
                    assertNotNull(piece.piece);
                    byte[] pieceBytes = piece.piece.toByteArray();
                    joined.write(pieceBytes, 0, pieceBytes.length);
                }
                assertTrue("length=" + length, Arrays.equals(input, joined.toByteArray()));
            } finally {
                file.delete();
                capture.close();
            }
        }
    }

    private static final class RecordingAssetManager extends AssetManager {
        WearableConnection connection;
        String sourceNodeId;
        FetchAsset fetchAsset;
        String acknowledgedDigest;
        String receivedDigest;

        RecordingAssetManager() {
            super(null);
        }

        @Override
        public void handleFetchAsset(WearableConnection connection, String sourceNodeId,
                                     FetchAsset fetchAsset) {
            this.connection = connection;
            this.sourceNodeId = sourceNodeId;
            this.fetchAsset = fetchAsset;
        }

        @Override
        public void onAckAsset(String digest) {
            this.acknowledgedDigest = digest;
        }

        @Override
        public void onAssetReceived(String digest) {
            this.receivedDigest = digest;
        }
    }

    private static final class CapturingConnection extends WearableConnection {
        final List<RootMessage> messages = new ArrayList<>();

        CapturingConnection() {
            super(null);
        }

        @Override
        public void writeMessage(RootMessage message) {
            messages.add(message);
        }

        @Override
        protected void writeMessagePiece(MessagePiece piece) {
        }

        @Override
        protected MessagePiece readMessagePiece() {
            return null;
        }

        @Override
        public void close() {
        }
    }

    private static final class ManualScheduler implements AssetFetcher.RetryScheduler {
        final List<Runnable> tasks = new ArrayList<>();
        final List<Long> delays = new ArrayList<>();

        @Override
        public boolean postDelayed(Runnable runnable, long delayMs) {
            tasks.add(runnable);
            delays.add(delayMs);
            return true;
        }

        void runNext() {
            Runnable task = tasks.remove(0);
            delays.remove(0);
            task.run();
        }
    }

    private static final class SilentAssetFetchLog implements AssetFetcher.FetchLog {
        @Override public void debug(String message) { }
        @Override public void warning(String message) { }
        @Override public void warning(String message, Throwable error) { }
        @Override public void verbose(String message) { }
    }
}
