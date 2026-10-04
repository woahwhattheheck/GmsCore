package org.microg.gms.wearable;

import android.database.Cursor;
import android.os.Handler;
import android.util.Log;

import com.google.android.gms.wearable.Asset;

import org.microg.gms.wearable.channel.ChannelManager;
import org.microg.gms.wearable.proto.FetchAsset;
import org.microg.gms.wearable.proto.RootMessage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class AssetFetcher {
    private static final String TAG = "GmsWearAssetFetch";

    private final NodeDatabaseHelper nodeDatabase;
    private final RetryScheduler retryScheduler;
    private final FetchLog fetchLog;
    private final FailureListener failureListener;

    private final Set<String> fetchingAssets = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private final Map<String, AssetFetchAttempt> failedAssets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PendingFetch> pendingFetches = new ConcurrentHashMap<>();

    private static final int ASSET_BATCH_SIZE = 4;
    static final int MAX_RETRY_COUNT = 3;
    private static final long RETRY_COOLDOWN_MS = 5000; // 5 seconds before retry
    private static final long FAILED_ASSET_EXPIRY_MS = 300000; // 5 minutes
    private static final int MAX_INFLIGHT_FETCHES = 8;
    private static final long INFLIGHT_RETRY_DELAY_MS = 750;
    static final long FETCH_RESPONSE_TIMEOUT_MS = 30000;
    static final long FETCH_TRANSFER_TIMEOUT_MS = 300000;

    public AssetFetcher(NodeDatabaseHelper nodeDatabase, Handler networkHandler) {
        this(nodeDatabase, networkHandler, digest -> { });
    }

    AssetFetcher(NodeDatabaseHelper nodeDatabase, Handler networkHandler,
                 FailureListener failureListener) {
        this(nodeDatabase, (runnable, delayMs) -> networkHandler.postDelayed(runnable, delayMs),
                new AndroidFetchLog(), failureListener);
    }

    AssetFetcher(NodeDatabaseHelper nodeDatabase, RetryScheduler retryScheduler) {
        this(nodeDatabase, retryScheduler, new AndroidFetchLog());
    }

    AssetFetcher(NodeDatabaseHelper nodeDatabase, RetryScheduler retryScheduler, FetchLog fetchLog) {
        this(nodeDatabase, retryScheduler, fetchLog, digest -> { });
    }

    AssetFetcher(NodeDatabaseHelper nodeDatabase, RetryScheduler retryScheduler, FetchLog fetchLog,
                 FailureListener failureListener) {
        this.nodeDatabase = nodeDatabase;
        this.retryScheduler = retryScheduler;
        this.fetchLog = fetchLog;
        this.failureListener = failureListener;
    }

    interface RetryScheduler {
        boolean postDelayed(Runnable runnable, long delayMs);
    }

    interface FetchLog {
        void debug(String message);
        void warning(String message);
        void warning(String message, Throwable error);
        void verbose(String message);
    }

    interface FailureListener {
        void onFetchExhausted(String digest);
    }

    private static final class AndroidFetchLog implements FetchLog {
        @Override public void debug(String message) { Log.d(TAG, message); }
        @Override public void warning(String message) { Log.w(TAG, message); }
        @Override public void warning(String message, Throwable error) { Log.w(TAG, message, error); }
        @Override public void verbose(String message) { Log.v(TAG, message); }
    }

    public void fetchMissingAssets(String nodeId, WearableConnection connection,
                                   Map<String, WearableConnection> activeConnections,
                                   ChannelManager channelManager) {
        fetchMissingAssets(nodeId, connection, activeConnections, channelManager, false);
    }

    private void fetchMissingAssets(String nodeId, WearableConnection connection,
                                    Map<String, WearableConnection> activeConnections,
                                    ChannelManager channelManager, boolean retry) {
        if (connection == null) {
            Log.d(TAG, "Connection no longer active for node: " + nodeId);
            return;
        }

        cleanupExpiredFailures();

        Cursor cursor = nodeDatabase.listMissingAssets();
        if (cursor == null) {
            return;
        }

        final int colPackageName = cursor.getColumnIndexOrThrow("packageName");
        final int colSignatureDigest = cursor.getColumnIndexOrThrow("signatureDigest");
        final int colAssetDigest = cursor.getColumnIndexOrThrow("assets_digest");

        try {
            int fetchCount = 0;
            int skippedCount = 0;
            int alreadyFetchingCount = 0;

            while (cursor.moveToNext()) {
                if (!activeConnections.containsKey(nodeId)) {
                    Log.d(TAG, "Connection closed during asset fetch, stopping (fetched="
                            + fetchCount + ", skipped=" + skippedCount + ")");
                    break;
                }

                if (fetchingAssets.size() >= MAX_INFLIGHT_FETCHES) {
                    Log.d(TAG, "fetchMissingAssets: In-flight FetchAsset cap reached ("
                            + fetchingAssets.size() + "); deferring remaining for node " + nodeId);

                    retryScheduler.postDelayed(() -> fetchMissingAssets(
                            nodeId, activeConnections.get(nodeId), activeConnections, channelManager, retry),
                            INFLIGHT_RETRY_DELAY_MS);
                    break;
                }

                String assetDigest = cursor.getString(colAssetDigest);
                String packageName = cursor.getString(colPackageName);
                String signatureDigest = cursor.getString(colSignatureDigest);

                if (fetchingAssets.contains(assetDigest)) {
                    alreadyFetchingCount++;
                    continue;
                }

                AssetFetchAttempt attempt = failedAssets.get(assetDigest);
                if (attempt != null) {
                    if (attempt.retryCount >= MAX_RETRY_COUNT) {
                        skippedCount++;
                        continue;
                    }
                    long timeSinceLastAttempt = System.currentTimeMillis() - attempt.lastAttemptTime;
                    if (!retry && timeSinceLastAttempt < RETRY_COOLDOWN_MS) {
                        skippedCount++;
                        continue;
                    }
                }

                FetchAsset fetchAsset = new FetchAsset.Builder()
                        .assetName(assetDigest)
                        .packageName(packageName)
                        .signatureDigest(signatureDigest)
                        .build();
                Runnable retryRequest = () -> retryGlobalFetch(assetDigest, nodeId,
                        activeConnections, channelManager);

                try {
                    if (!fetchingAssets.add(assetDigest)) {
                        alreadyFetchingCount++;
                        continue;
                    }
                    PendingFetch pending = startPendingFetch(assetDigest, nodeId, retryRequest);
                    connection.writeMessage(new RootMessage.Builder().fetchAsset(fetchAsset).build());
                    schedulePendingTimeout(pending, FETCH_RESPONSE_TIMEOUT_MS);

                    fetchCount++;

                    if (fetchCount % ASSET_BATCH_SIZE == 0) {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            Log.d(TAG, "Asset fetch interrupted");
                            break;
                        }
                    }

                } catch (IOException e) {
                    Log.w(TAG, "Error fetching asset " + assetDigest +
                            " (fetched " + fetchCount + " so far): " + e.getMessage());

                    failFetch(assetDigest, nodeId, retryRequest);

                    if (isConnectionError(e)) {
                        break;
                    }
                }
            }

            if (fetchCount > 0 || skippedCount > 0 || alreadyFetchingCount > 0) {
                Log.d(TAG, "Asset fetch summary: fetched=" + fetchCount +
                        ", skipped=" + skippedCount +
                        ", alreadyFetching=" + alreadyFetchingCount);
            }

            if (fetchCount > 100 && channelManager != null) {
                Log.d(TAG, "Large asset batch (" + fetchCount + "), applying cooldown");
                channelManager.setOperationCooldown(1000);
            }

        } finally {
            cursor.close();
        }
    }

    public void fetchMissingAssetsForRecord(WearableConnection connection,
                                            DataItemRecord record,
                                            List<Asset> missingAssets) {
        fetchMissingAssetsForRecord(connection, record.source, record, missingAssets,
                Collections.emptyMap(), null);
    }

    public void fetchMissingAssetsForRecord(WearableConnection connection, String nodeId,
                                            DataItemRecord record, List<Asset> missingAssets,
                                            Map<String, WearableConnection> activeConnections,
                                            ChannelManager channelManager) {
        int successCount = 0;
        int skipCount = 0;

        for (Asset asset : missingAssets) {
            if (sendRecordFetch(connection, nodeId, record, asset, activeConnections,
                    channelManager, false)) successCount++;
            else skipCount++;
        }

        if (successCount > 0 || skipCount > 0) {
            fetchLog.debug("Record asset fetch: success=" + successCount + ", skipped=" + skipCount);
        }
    }

    private boolean sendRecordFetch(WearableConnection connection, String nodeId,
                                    DataItemRecord record, Asset asset,
                                    Map<String, WearableConnection> activeConnections,
                                    ChannelManager channelManager, boolean retry) {
        String digest = asset != null ? asset.getDigest() : null;
        if (connection == null || digest == null || fetchingAssets.contains(digest)) return false;

        AssetFetchAttempt attempt = failedAssets.get(digest);
        if (attempt != null) {
            if (attempt.retryCount >= MAX_RETRY_COUNT) {
                fetchLog.debug("Asset " + digest + " failed too many times, skipping");
                return false;
            }
            long timeSinceLastAttempt = System.currentTimeMillis() - attempt.lastAttemptTime;
            if (!retry && timeSinceLastAttempt < RETRY_COOLDOWN_MS) return false;
        }

        if (!fetchingAssets.add(digest)) return false;
        Runnable retryRequest = () -> retryRecordFetch(digest, nodeId, connection, record,
                asset, activeConnections, channelManager);
        FetchAsset fetchAsset = new FetchAsset.Builder()
                .assetName(digest)
                .packageName(record.packageName)
                .signatureDigest(record.signatureDigest)
                .permission(false)
                .build();

        try {
            fetchLog.debug("Fetching missing asset for record: " + digest + " from " + nodeId);
            PendingFetch pending = startPendingFetch(digest, nodeId, retryRequest);
            connection.writeMessage(new RootMessage.Builder().fetchAsset(fetchAsset).build());
            schedulePendingTimeout(pending, FETCH_RESPONSE_TIMEOUT_MS);
            return true;
        } catch (IOException e) {
            fetchLog.warning("Error fetching asset " + digest + " for record", e);
            failFetch(digest, nodeId, retryRequest);
            return false;
        }
    }

    private void retryRecordFetch(String digest, String failedNodeId, WearableConnection failedConnection,
                                  DataItemRecord record, Asset asset,
                                  Map<String, WearableConnection> activeConnections,
                                  ChannelManager channelManager) {
        AssetFetchAttempt attempt = failedAssets.get(digest);
        if (attempt == null || attempt.retryCount >= MAX_RETRY_COUNT) return;
        String nextNodeId = selectRetryPeer(failedNodeId, attempt.attemptedNodes, activeConnections);
        WearableConnection nextConnection = activeConnections != null && nextNodeId != null
                ? activeConnections.get(nextNodeId) : null;
        if (nextConnection == null) {
            nextNodeId = failedNodeId;
            nextConnection = failedConnection;
        }
        sendRecordFetch(nextConnection, nextNodeId, record, asset,
                activeConnections, channelManager, true);
    }

    public void onAssetReceived(String digest) {
        pendingFetches.remove(digest);
        fetchingAssets.remove(digest);
        failedAssets.remove(digest);
        fetchLog.verbose("Asset received and tracked: " + digest);
    }

    public void onAssetTransferStarted(String digest) {
        PendingFetch pending = pendingFetches.get(digest);
        if (pending == null) return;
        PendingFetch transfer = startPendingFetch(digest, pending.nodeId, pending.retry);
        schedulePendingTimeout(transfer, FETCH_TRANSFER_TIMEOUT_MS);
    }

    public void onAssetFetchFailed(String digest, String sourceNodeId) {
        PendingFetch pending = pendingFetches.get(digest);
        if (pending == null || !Objects.equals(pending.nodeId, sourceNodeId)
                || !pendingFetches.remove(digest, pending)) return;
        fetchingAssets.remove(digest);
        AssetFetchAttempt attempt = recordFailure(digest, pending.nodeId);
        fetchLog.warning("FetchAsset failed for peer=" + pending.nodeId + " digest=" + digest
                + " attempt=" + attempt.retryCount);
        scheduleRetry(pending, attempt);
    }

    private PendingFetch startPendingFetch(String digest, String nodeId, Runnable retry) {
        PendingFetch pending = new PendingFetch(digest, nodeId, retry);
        pendingFetches.put(digest, pending);
        return pending;
    }

    private void schedulePendingTimeout(PendingFetch pending, long timeoutMs) {
        retryScheduler.postDelayed(() -> {
            if (!pendingFetches.remove(pending.digest, pending)) return;
            fetchingAssets.remove(pending.digest);
            AssetFetchAttempt attempt = recordFailure(pending.digest, pending.nodeId);
            fetchLog.warning("FetchAsset timed out waiting for peer=" + pending.nodeId
                    + " digest=" + pending.digest + " attempt=" + attempt.retryCount);
            scheduleRetry(pending, attempt);
        }, timeoutMs);
    }

    private void failFetch(String digest, String nodeId, Runnable retry) {
        PendingFetch pending = pendingFetches.remove(digest);
        fetchingAssets.remove(digest);
        AssetFetchAttempt attempt = recordFailure(digest, nodeId);
        fetchLog.warning("FetchAsset failed for digest=" + digest + " attempt=" + attempt.retryCount);
        scheduleRetry(pending != null ? pending : new PendingFetch(digest, nodeId, retry), attempt);
    }

    private void scheduleRetry(PendingFetch pending, AssetFetchAttempt attempt) {
        if (pending.retry == null || attempt.retryCount >= MAX_RETRY_COUNT) {
            notifyFetchExhausted(attempt);
            return;
        }
        int retryCount = attempt.retryCount;
        retryScheduler.postDelayed(() -> {
            if (failedAssets.get(pending.digest) != attempt || attempt.retryCount != retryCount
                    || fetchingAssets.contains(pending.digest)) return;
            pending.retry.run();
        }, RETRY_COOLDOWN_MS);
    }

    private void notifyFetchExhausted(AssetFetchAttempt attempt) {
        synchronized (attempt) {
            if (attempt.terminalNotified) return;
            attempt.terminalNotified = true;
        }
        failureListener.onFetchExhausted(attempt.digest);
    }

    private void retryGlobalFetch(String digest, String failedNodeId,
                                  Map<String, WearableConnection> activeConnections,
                                  ChannelManager channelManager) {
        AssetFetchAttempt attempt = failedAssets.get(digest);
        if (attempt == null || attempt.retryCount >= MAX_RETRY_COUNT || activeConnections == null) return;
        String nextNodeId = selectRetryPeer(failedNodeId, attempt.attemptedNodes, activeConnections);
        WearableConnection connection = nextNodeId != null ? activeConnections.get(nextNodeId) : null;
        if (connection != null) {
            fetchMissingAssets(nextNodeId, connection, activeConnections, channelManager, true);
        }
    }

    static String selectRetryPeer(String failedNodeId, Set<String> attemptedNodes,
                                  Map<String, WearableConnection> activeConnections) {
        if (activeConnections == null || activeConnections.isEmpty()) return null;
        for (Map.Entry<String, WearableConnection> entry : activeConnections.entrySet()) {
            if (entry.getValue() != null && !attemptedNodes.contains(entry.getKey())) {
                return entry.getKey();
            }
        }
        attemptedNodes.clear();
        for (Map.Entry<String, WearableConnection> entry : activeConnections.entrySet()) {
            if (entry.getValue() != null && !entry.getKey().equals(failedNodeId)) {
                return entry.getKey();
            }
        }
        return activeConnections.containsKey(failedNodeId) ? failedNodeId
                : activeConnections.keySet().iterator().next();
    }

    private AssetFetchAttempt recordFailure(String digest, String nodeId) {
        AssetFetchAttempt attempt = failedAssets.get(digest);
        if (attempt == null) {
            attempt = new AssetFetchAttempt(digest);
            failedAssets.put(digest, attempt);
        }
        attempt.recordFailure(nodeId);
        return attempt;
    }

    private boolean isConnectionError(IOException e) {
        String message = e.getMessage();
        if (message == null) return false;

        return message.contains("Connection") ||
                message.contains("Broken pipe") ||
                message.contains("Socket closed") ||
                message.contains("Connection reset");
    }

    private void cleanupExpiredFailures() {
        long now = System.currentTimeMillis();
        List<String> toRemove = new ArrayList<>();

        for (Map.Entry<String, AssetFetchAttempt> entry : failedAssets.entrySet()) {
            if (now - entry.getValue().firstAttemptTime > FAILED_ASSET_EXPIRY_MS) {
                toRemove.add(entry.getKey());
            }
        }

        for (String digest : toRemove) {
            failedAssets.remove(digest);
        }

        if (!toRemove.isEmpty()) {
            Log.d(TAG, "Cleaned up " + toRemove.size() + " expired failed asset records");
        }
    }

    public AssetFetchStats getStats() {
        int failedCount = 0;
        int retryingCount = 0;

        for (AssetFetchAttempt attempt : failedAssets.values()) {
            if (attempt.retryCount >= MAX_RETRY_COUNT) {
                failedCount++;
            } else {
                retryingCount++;
            }
        }

        return new AssetFetchStats(
                fetchingAssets.size(),
                retryingCount,
                failedCount
        );
    }

    public void resetTracking() {
        fetchingAssets.clear();
        pendingFetches.clear();
        failedAssets.clear();
        Log.d(TAG, "Asset fetch tracking reset");
    }

    private static class AssetFetchAttempt {
        final String digest;
        final long firstAttemptTime;
        final Set<String> attemptedNodes = Collections.newSetFromMap(
                new ConcurrentHashMap<String, Boolean>());
        long lastAttemptTime;
        int retryCount;
        boolean terminalNotified;

        AssetFetchAttempt(String digest) {
            this.digest = digest;
            this.firstAttemptTime = System.currentTimeMillis();
            this.lastAttemptTime = firstAttemptTime;
            this.retryCount = 0;
        }

        void recordFailure(String nodeId) {
            this.lastAttemptTime = System.currentTimeMillis();
            this.retryCount++;
            if (nodeId != null) attemptedNodes.add(nodeId);
        }
    }

    private static final class PendingFetch {
        final String digest;
        final String nodeId;
        final Runnable retry;

        PendingFetch(String digest, String nodeId, Runnable retry) {
            this.digest = digest;
            this.nodeId = nodeId;
            this.retry = retry;
        }
    }

    public static class AssetFetchStats {
        public final int currentlyFetching;
        public final int retrying;
        public final int failed;

        AssetFetchStats(int currentlyFetching, int retrying, int failed) {
            this.currentlyFetching = currentlyFetching;
            this.retrying = retrying;
            this.failed = failed;
        }

        @Override
        public String toString() {
            return "AssetFetchStats" +
                    "{fetching="+currentlyFetching+", " +
                    "retrying="+retrying+", " +
                    "failed="+failed+"}";
        }
    }
}
