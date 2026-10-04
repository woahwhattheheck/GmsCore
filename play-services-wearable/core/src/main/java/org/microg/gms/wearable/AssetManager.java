package org.microg.gms.wearable;

import android.util.Log;

import com.google.android.gms.wearable.Wearable;

import org.microg.gms.wearable.proto.AppKey;
import org.microg.gms.wearable.proto.AppKeys;
import org.microg.gms.wearable.proto.FetchAsset;
import org.microg.gms.wearable.proto.FilePiece;
import org.microg.gms.wearable.proto.RootMessage;
import org.microg.gms.wearable.proto.SetAsset;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import okio.ByteString;

public class AssetManager {
    private static final String TAG = "WearAssetManager";

    static final int CHUNK_SIZE = 12215;
    private static final Pattern ASSET_DIGEST_PATTERN = Pattern.compile("[A-Za-z0-9_-]{27}");

    private final WearableImpl wearable;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(
            r -> {
                Thread t = new Thread(r, TAG);
                t.setDaemon(true);
                return t;
            }
    );

    private final Map<String, FetchEntry> entries = new ConcurrentHashMap<>();
    private final Map<String, WearableWriter> writers = new ConcurrentHashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public AssetManager(WearableImpl wearable) {
        this.wearable = wearable;
    }

    public void addWriter(String nodeId, WearableWriter writer) {
        writers.put(nodeId, writer);

        if (entries.isEmpty()) return;

        Log.d(TAG, "addWriter: replaying " + entries.size() + " to " + nodeId);

        for (FetchEntry e : entries.values()) {
            writer.enqueue(buildFetchMessage(e));
        }
    }

    public void removeWriter(String nodeId) {
        writers.remove(nodeId);
        Log.d(TAG, "removeWriter: " + nodeId);
    }

    public void onAssetMissing(String digest, String packageName, String signature) {
		FetchEntry existing = entries.get(digest);
		if (existing != null && !existing.permission) {
			Log.d(TAG, "onAssetMissing: full fetch already pending for " + digest);
			return;
		}
        enqueueFetch(digest, packageName, signature, false);
    }

    public void onAssetPermissionMissing(String digest, String packageName, String signature) {
        Log.d(TAG, "onAssetPermissionMissing: " + digest);
        FetchEntry existing = entries.get(digest);
        if (existing != null && !existing.permission) {
            Log.d(TAG, "onAssetPermissionMissing: full fetch already pending for " + digest);
            return;
        }
        enqueueFetch(digest, packageName, signature, true);
    }

    private void enqueueFetch(String digest, String packageName,
                              String signature, boolean permission) {
        FetchEntry fetchEntry = new FetchEntry(digest, packageName, signature, permission);
        entries.put(digest, fetchEntry);

        RootMessage msg = buildFetchMessage(fetchEntry);
        for (WearableWriter writer: writers.values()) {
            writer.enqueue(msg);
        }
    }

    private static RootMessage buildFetchMessage(FetchEntry entry) {
        return new RootMessage.Builder()
                .fetchAsset(
                        new FetchAsset.Builder()
                                .assetName(entry.digest)
                                .packageName(entry.packageName)
                                .signatureDigest(entry.signatureDigest)
                                .permission(entry.permission)
                                .build()
                ).build();
    }

    public void onAckAsset(String digest) {
        if (digest == null) return;
        Log.d(TAG, "onAckAsset: " + digest);
        entries.remove(digest);
        checkCompletion();
    }

    public void handleFetchAsset(WearableConnection connection, String sourceNodeId, FetchAsset fetchAsset) {
        if (connection == null || fetchAsset == null || !isValidAssetDigest(fetchAsset.assetName)) {
            Log.w(TAG, "handleFetchAsset: ignoring malformed request from " + sourceNodeId);
            return;
        }
        boolean permission = Boolean.TRUE.equals(fetchAsset.permission);
        Log.d(TAG, "handleFetchAsset: digest=" + fetchAsset.assetName
            + " permission=" + permission + " from=" + sourceNodeId);

        ioExecutor.execute(() -> {
            String digest = fetchAsset.assetName;
            List<AppKey> allowedKeys = wearable.getNodeDatabase().getAppKeysForAsset(digest);
            AppKeys appKeys = resolveAppKeys(allowedKeys, fetchAsset.packageName,
                    fetchAsset.signatureDigest);
            if (appKeys == null) {
                Log.w(TAG, "handleFetchAsset: no matching app key for " + digest
                        + " from " + sourceNodeId);
                return;
            }

            File assetFile = wearable.createAssetFile(digest);

            if (!assetFile.exists()){
                Log.d(TAG, "handleFetchAsset: asset not found locally, ignoring: " + digest);
                return;
            }

            if (permission) {
                sendPermissionConfirmation(connection, digest, appKeys);
            } else {
                sendFullAsset(connection, digest, assetFile, appKeys);
            }
        });
    }

    private void sendPermissionConfirmation(WearableConnection connection,
                                            String digest, AppKeys appKeys) {
        try {
            connection.writeMessage(
                    new RootMessage.Builder()
                            .setAsset(
                                    new SetAsset.Builder()
                                            .digest(digest)
                                            .appkeys(appKeys)
                                            .build()
                            )
                            .hasAsset(false)
                            .build()
            );
            Log.d(TAG, "sendPermissionConfirmation: sent for " + digest);
        } catch (IOException e) {
            Log.w(TAG, "sendPermissionConfirmation: failed for " + digest + ": " + e.getMessage());
        }
    }

    private void sendFullAsset(WearableConnection connection, String digest,
                               File assetFile, AppKeys appKeys) {
        try {
            String actualDigest = calculateFileDigest(assetFile);
            if (!digest.equals(actualDigest)) {
                Log.w(TAG, "sendFullAsset: stored content digest mismatch for " + digest);
                return;
            }

            RootMessage msg = new RootMessage.Builder()
                    .setAsset(
                            new SetAsset.Builder()
                                    .digest(digest)
                                    .appkeys(appKeys)
                                    .build()
                    )
                    .hasAsset(true)
                    .build();
            connection.writeMessage(msg);

            String fileName = WearableConnection.calculateDigest(msg.encode());
            streamFilePieces(connection, assetFile, fileName, digest);

            Log.d(TAG, "sendFullAsset: competed for " + digest);
        } catch (IOException e) {
            Log.w(TAG, "sendFullAsset: failed for " + digest + ": " + e.getMessage());
        }
    }

    static void streamFilePieces(WearableConnection connection, File file,
                                 String fileName, String digest) throws IOException {
        if (connection == null || file == null || !isValidAssetDigest(fileName)
                || !isValidAssetDigest(digest)) {
            throw new IOException("Invalid asset transfer metadata");
        }
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buf = new byte[CHUNK_SIZE];
            ByteString pending = null;
            int n;

            while ((n = fis.read(buf)) > 0) {
                if (pending != null) {
                    connection.writeMessage(
                            new RootMessage.Builder()
                                    .filePiece(
                                            new FilePiece(fileName, false, pending, null)
                                    )
                                    .build()
                    );
                }
                pending = ByteString.of(buf, 0, n);
            }

            connection.writeMessage(
                    new RootMessage.Builder()
                            .filePiece(new FilePiece(fileName, true,
                                    pending != null ? pending : ByteString.of(new byte[0]), digest))
                            .build()
            );
        }
    }

    static boolean isValidAssetDigest(String digest) {
        return digest != null && ASSET_DIGEST_PATTERN.matcher(digest).matches();
    }

    static String calculateFileDigest(File file) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-1 is not available", e);
        }

        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) != -1) {
                if (length > 0) digest.update(buffer, 0, length);
            }
        }
        return WearableConnection.base64encode(digest.digest());
    }

    static AppKeys resolveAppKeys(List<AppKey> allowedKeys, String packageName,
                                  String signatureDigest) {
        if (allowedKeys == null || packageName == null || signatureDigest == null) return null;
        for (AppKey key : allowedKeys) {
            if (key != null && packageName.equals(key.packageName)
                    && signatureDigest.equals(key.signatureDigest)) {
                return new AppKeys(Collections.singletonList(key));
            }
        }
        return null;
    }

    public File getReadableAssetFile(String digest, AppKey caller) {
        // Reject malformed paths and unknown callers before touching the asset
        // store. Binder clients may supply a digest they never received.
        if (!isValidAssetDigest(digest) || caller == null
                || caller.packageName == null || caller.signatureDigest == null
                || resolveAppKeys(getAssetAppKeys(digest), caller.packageName,
                caller.signatureDigest) == null) return null;
        File file = getStoredAssetFile(digest);
        return file != null && file.isFile() ? file : null;
    }

    List<AppKey> getAssetAppKeys(String digest) {
        return wearable.getNodeDatabase().getAppKeysForAsset(digest);
    }

    File getStoredAssetFile(String digest) {
        return wearable.createAssetFile(digest);
    }

    static byte[] getFilePieceData(FilePiece filePiece) {
        if (filePiece == null || !isValidAssetDigest(filePiece.fileName)
                || filePiece.finalPiece == null) return null;
        boolean finalPiece = Boolean.TRUE.equals(filePiece.finalPiece);
        if (filePiece.piece == null && !finalPiece) return null;
        if (finalPiece && !isValidAssetDigest(filePiece.digest)) return null;
        return filePiece.piece != null ? filePiece.piece.toByteArray() : new byte[0];
    }

    public void addCompletionListener(Runnable listener) {
        synchronized (listeners) {
            listeners.add(listener);
        }
        checkCompletion();
    }

    private void checkCompletion() {
        final List<Runnable> toFire;
        synchronized (listeners) {
            if (!entries.isEmpty()) return;

            // Claim each registration once before another completion can drain it.
            toFire = new ArrayList<>(listeners);
            listeners.clear();
        }

        for (Runnable r : toFire) {
            try {
                r.run();
            } catch (Exception e) {
                Log.w(TAG, "checkCompletion: " + e.getMessage());
            }
        }
    }

    public void onAssetReceived(String digest) {
        if (digest == null) return;
        completeFetch(digest);
    }

    public void onAssetMetadataReceived(String digest) {
        if (digest == null) return;
        FetchEntry entry = entries.get(digest);
        if (entry != null && shouldCompleteOnMetadata(entry.permission)) completeFetch(digest);
    }

    static boolean shouldCompleteOnMetadata(boolean permissionOnlyFetch) {
        return permissionOnlyFetch;
    }

    private void completeFetch(String digest) {
        entries.remove(digest);
        checkCompletion();
    }

    public void onAssetFetchFailed(String digest) {
        entries.remove(digest);
        checkCompletion();
    }

    public int getFetchCount() {
        return entries.size();
    }

    public void reset() {
        entries.clear();
        writers.clear();
        listeners.clear();
    }

    public void shutdown() {
        reset();
        ioExecutor.shutdownNow();
    }

    private AppKeys resolveAppKeys(String digest, String fallbackPackageName, String fallbackSignature) {
        if (fallbackPackageName == null || fallbackPackageName.isEmpty()) {
            return new AppKeys(Collections.emptyList());
        }

        List<AppKey> keys = new ArrayList<>(1);
        keys.add(new AppKey(fallbackPackageName, fallbackSignature != null ? fallbackSignature : ""));
        return new AppKeys(keys);
    }

    private static final class FetchEntry {
        final String digest;
        final String packageName;
        final String signatureDigest;
        final boolean permission;

        FetchEntry(String digest, String packageName, String signatureDigest, boolean permission) {
            this.digest = digest;
            this.packageName = packageName;
            this.signatureDigest = signatureDigest;
            this.permission = permission;
        }
    }
}
