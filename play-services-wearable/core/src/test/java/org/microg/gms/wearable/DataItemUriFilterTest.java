/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.wearable;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.database.Cursor;
import android.net.Uri;
import android.os.Looper;
import com.google.android.gms.common.data.DataHolder;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.internal.DeleteDataItemsResponse;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.microg.gms.common.PackageUtils;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Exercises public URI filters through Binder queues and the production SQLite schema. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
@LooperMode(LooperMode.Mode.PAUSED)
public class DataItemUriFilterTest {
    private static final String PACKAGE = "test.wear.datafilters";
    private static final int LITERAL = 0;
    private static final int PREFIX = 1;
    private Context context;
    private NodeDatabaseHelper nodes;
    private ConfigurationDatabaseHelper configurations;
    private TestWearable wearable;
    private WearableServiceImpl service;
    private String signature;
    private long nextSequence = 1;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        PackageInfo info = new PackageInfo();
        info.packageName = PACKAGE;
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = PACKAGE;
        info.signatures = new Signature[]{new Signature("01020304")};
        shadowOf(context.getPackageManager()).installPackage(info);
        signature = PackageUtils.firstSignatureDigest(context, PACKAGE);
        context.deleteDatabase("node.db");
        context.deleteDatabase("connectionconfig.db");
        nodes = new NodeDatabaseHelper(context);
        configurations = new ConfigurationDatabaseHelper(context);
        wearable = new TestWearable(context, nodes, configurations);
        service = new WearableServiceImpl(context, wearable, PACKAGE);
    }

    @After public void tearDown() {
        if (wearable != null) {
            if (wearable.getChannelManager() != null) wearable.getChannelManager().stop();
            wearable.networkHandler.getLooper().quit();
        }
        if (nodes != null) nodes.close();
        if (configurations != null) configurations.close();
        if (context != null) {
            context.deleteDatabase("node.db");
            context.deleteDatabase("connectionconfig.db");
        }
    }

    @Test public void publicReadDistinguishesLiteralPrefixTrailingSlashAndCase() throws Exception {
        for (String path : Arrays.asList("/foo", "/foo/", "/foo/bar", "/foobar", "/Foo")) {
            put(PACKAGE, signature, "peer", path);
        }
        assertEquals(keys("peer|/foo/"), read("peer", "/foo/", null));
        assertEquals(keys("peer|/foo"), read("peer", "/foo", LITERAL));
        assertEquals(keys("peer|/foo", "peer|/foo/", "peer|/foo/bar", "peer|/foobar"),
                read("peer", "/foo", PREFIX));
        assertEquals(keys("peer|/foo/", "peer|/foo/bar"), read("peer", "/foo/", PREFIX));
    }

    @Test public void pathMetacharactersStayLiteralAndAreDecodedOnlyOnce() throws Exception {
        put(PACKAGE, signature, "peer", "/tagX");
        for (String path : Arrays.asList("/tag%", "/tag_", "/tag*", "/tag\\", "/encoded%2F", "/café")) {
            put(PACKAGE, signature, "peer", path);
            put(PACKAGE, signature, "peer", path + "/child");
            assertEquals(keys("peer|" + path), read("peer", path, LITERAL));
            assertEquals(keys("peer|" + path, "peer|" + path + "/child"), read("peer", path, PREFIX));
        }
    }

    @Test public void readsKeepAppCertificateAndExactHostScope() throws Exception {
        put(PACKAGE, signature, "peer_%*", "/scope");
        put(PACKAGE, signature, "peerABone", "/scope");
        put(PACKAGE, signature, "remote", "/scope");
        put("other.app", signature, "peer_%*", "/scope");
        put(PACKAGE, "other.signature", "peer_%*", "/scope");

        assertEquals(keys("peer_%*|/scope"), read("peer_%*", "/scope", LITERAL));
        Set<String> allOwnerNodes = keys("peer_%*|/scope", "peerABone|/scope", "remote|/scope");
        assertEquals(allOwnerNodes, read("*", "/scope", LITERAL));
        assertEquals(allOwnerNodes, read(null, "/scope", PREFIX));
        assertTrue(read("peer*", "/scope", PREFIX).isEmpty());
    }

    @Test public void publicDeleteUsesLiteralByDefaultAndKeepsCallerAndHostScope() throws Exception {
        DataItemRecord exact = put(PACKAGE, signature, "peer_%*", "/foo");
        DataItemRecord child = put(PACKAGE, signature, "peer_%*", "/foo/child");
        DataItemRecord sibling = put(PACKAGE, signature, "peer_%*", "/foobar");
        List<DataItemRecord> untouched = Arrays.asList(
                put(PACKAGE, signature, "peer_%*", "/Foo"),
                put(PACKAGE, signature, "peerABone", "/foo"),
                put("other.app", signature, "peer_%*", "/foo"),
                put(PACKAGE, "other.signature", "peer_%*", "/foo"));

        assertEquals(1, delete("peer_%*", "/foo", null));
        assertTrue(deleted(exact));
        assertFalse(deleted(child));
        assertFalse(deleted(sibling));
        assertEquals(2, delete("peer_%*", "/foo", PREFIX));
        assertTrue(deleted(child));
        assertTrue(deleted(sibling));
        for (DataItemRecord record : untouched) assertFalse(deleted(record));
        assertEquals(3, wearable.synced.size());
    }

    @Test public void prefixDeleteCountsEachItemOnceIncludingPendingMultiAssetFinalRow() throws Exception {
        DataItemRecord ready = put(PACKAGE, signature, "peer", "/delete/ready");
        DataItemRecord pending = put(PACKAGE, signature, "peer", "/delete/zpending",
                "AAAAAAAAAAAAAAAAAAAAAAAAAAA", "BBBBBBBBBBBBBBBBBBBBBBBBBBB");
        DataItemRecord gone = put(PACKAGE, signature, "remote", "/delete/gone");
        assertEquals(1, delete("remote", "/delete/gone", LITERAL));
        wearable.synced.clear();
        assertEquals(keys("peer|/delete/ready"), read("*", "/delete/", PREFIX));

        assertEquals(2, delete("*", "/delete/", PREFIX));
        assertEquals(2, wearable.synced.size());
        for (DataItemRecord tombstone : wearable.synced) {
            assertTrue(tombstone.deleted);
            assertTrue(tombstone.assetsAreReady);
            assertNull(tombstone.dataItem.data);
            assertEquals(tombstone.seqId, tombstone.v1SeqId);
        }
        assertEquals(2, wearable.synced.get(1).dataItem.getAssets().size());
        assertTrue(deleted(ready));
        assertTrue(deleted(pending));
        assertTrue(deleted(gone));
        assertTrue(read("*", "/delete/", PREFIX).isEmpty());
        assertEquals(0, delete("*", "/delete/", PREFIX));
        assertEquals(2, wearable.synced.size());
        assertFalse(nodes.getWritableDatabase().inTransaction());
    }

    @Test public void legacyInternalCapabilityPrefixOverloadsRemainAvailable() throws Exception {
        put(PACKAGE, signature, "peer", "/capabilities/media");
        put(PACKAGE, signature, "peer", "/capabilities/audio");
        Uri uri = uri(null, "/capabilities/");
        assertEquals(keys("peer|/capabilities/media", "peer|/capabilities/audio"),
                holderKeys(wearable.getDataItemsByUriAsHolder(uri, PACKAGE)));
        assertTrue(read(null, "/capabilities/", LITERAL).isEmpty());
        assertEquals(2, wearable.deleteDataItems(uri, PACKAGE));
    }

    @Test public void invalidFiltersAndMissingPathsFailBeforePostingWork() throws Exception {
        DataItemRecord record = put(PACKAGE, signature, "peer", "/keep");
        List<Object> responses = new ArrayList<>();
        BaseWearableCallbacks callbacks = new BaseWearableCallbacks() {
            @Override public void onDataItemChanged(DataHolder holder) {
                responses.add(holder);
                holder.close();
            }
            @Override public void onDeleteDataItemsResponse(DeleteDataItemsResponse response) {
                responses.add(response);
            }
        };
        assertThrows(IllegalArgumentException.class,
                () -> service.getDataItemsByUriWithFilter(callbacks, uri("peer", "/keep"), 2));
        assertThrows(IllegalArgumentException.class,
                () -> service.deleteDataItemsWithFilter(callbacks, uri("peer", "/keep"), -1));
        assertThrows(IllegalArgumentException.class,
                () -> service.getDataItemsByUriWithFilter(callbacks, Uri.parse("wear://peer"), PREFIX));
        assertThrows(IllegalArgumentException.class,
                () -> service.deleteDataItemsWithFilter(callbacks, Uri.parse("wear://peer"), LITERAL));
        assertThrows(IllegalArgumentException.class, () -> service.getDataItemsByUri(callbacks, null));
        assertThrows(IllegalArgumentException.class, () -> service.deleteDataItems(callbacks, null));
        shadowOf(Looper.getMainLooper()).idle();
        shadowOf(wearable.networkHandler.getLooper()).idle();
        assertTrue(responses.isEmpty());
        assertFalse(deleted(record));
        assertFalse(nodes.getWritableDatabase().inTransaction());
    }

    private Set<String> read(String host, String path, Integer filter) throws Exception {
        List<DataHolder> responses = new ArrayList<>();
        BaseWearableCallbacks callbacks = new BaseWearableCallbacks() {
            @Override public void onDataItemChanged(DataHolder holder) { responses.add(holder); }
        };
        if (filter == null) service.getDataItemsByUri(callbacks, uri(host, path));
        else service.getDataItemsByUriWithFilter(callbacks, uri(host, path), filter);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("Read request must complete exactly once", 1, responses.size());
        return holderKeys(responses.get(0));
    }

    private int delete(String host, String path, Integer filter) throws Exception {
        List<DeleteDataItemsResponse> responses = new ArrayList<>();
        BaseWearableCallbacks callbacks = new BaseWearableCallbacks() {
            @Override public void onDeleteDataItemsResponse(DeleteDataItemsResponse response) { responses.add(response); }
        };
        if (filter == null) service.deleteDataItems(callbacks, uri(host, path));
        else service.deleteDataItemsWithFilter(callbacks, uri(host, path), filter);
        shadowOf(wearable.networkHandler.getLooper()).idle();
        assertEquals("Delete request must complete exactly once", 1, responses.size());
        assertEquals(0, (int) ReflectionHelpers.getField(responses.get(0), "status"));
        return ReflectionHelpers.getField(responses.get(0), "count");
    }

    private static Set<String> holderKeys(DataHolder holder) {
        assertNotNull(holder);
        try {
            assertEquals(0, holder.getStatusCode());
            Set<String> result = new HashSet<>();
            for (int row = 0; row < holder.getCount(); row++) {
                int window = holder.getWindowIndex(row);
                result.add(holder.getString("host", row, window) + "|" + holder.getString("path", row, window));
            }
            return result;
        } finally {
            holder.close();
        }
    }

    private DataItemRecord put(String app, String certificate, String host, String path, String... assets) {
        DataItemRecord record = new DataItemRecord();
        record.packageName = app;
        record.signatureDigest = certificate;
        record.source = UUID.randomUUID().toString();
        record.seqId = nextSequence++;
        record.v1SeqId = record.seqId;
        record.dataItem = new DataItemInternal(host, path);
        record.dataItem.data = new byte[]{1, 2};
        for (int i = 0; i < assets.length; i++) {
            nodes.getWritableDatabase().execSQL(
                    "INSERT OR IGNORE INTO assets(digest,dataPresent,timestampMs) VALUES(?,0,0)",
                    new Object[]{assets[i]});
            record.dataItem.addAsset("asset" + i, Asset.createFromRef(assets[i]));
        }
        nodes.putRecord(record);
        assertFalse("Fixture must be persisted and live", deleted(record));
        return record;
    }

    private boolean deleted(DataItemRecord record) {
        try (Cursor cursor = nodes.getReadableDatabase().rawQuery(
                "SELECT deleted FROM appKeyDataItems WHERE packageName=? AND signatureDigest=? AND host=? AND path=?",
                new String[]{record.packageName, record.signatureDigest, record.dataItem.host, record.dataItem.path})) {
            assertTrue("Fixture must have a persisted item", cursor.moveToFirst());
            return cursor.getInt(0) != 0;
        }
    }

    private static Uri uri(String host, String path) {
        Uri.Builder builder = new Uri.Builder().scheme("wear").path(path);
        if (host != null) builder.authority(host);
        return builder.build();
    }

    private static Set<String> keys(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    private static class TestWearable extends WearableImpl {
        final List<DataItemRecord> synced = new ArrayList<>();
        TestWearable(Context context, NodeDatabaseHelper nodes, ConfigurationDatabaseHelper configurations) {
            super(context, nodes, configurations);
        }
        @Override void syncRecordToAll(DataItemRecord record) { synced.add(record); }
    }
}
