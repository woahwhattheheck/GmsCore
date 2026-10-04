/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.wearable;

import android.content.Context;
import android.database.Cursor;
import com.google.android.gms.wearable.Asset;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static org.junit.Assert.*;

/** Exercises production readiness queries and updates against the actual SQLite schema. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class AssetReadinessDatabaseTest {
    private static final String ARRIVING = "AAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String MISSING = "BBBBBBBBBBBBBBBBBBBBBBBBBBB";
    private Context context;
    private NodeDatabaseHelper database;

    @Before public void createDatabase() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("node.db");
        database = new NodeDatabaseHelper(context);
        // Seed asset identities before adding references, including on databases
        // that enforce the production schema's foreign keys.
        database.getWritableDatabase().execSQL(
                "INSERT INTO assets(digest,dataPresent,timestampMs) VALUES(?,0,0)",
                new Object[]{ARRIVING});
        database.getWritableDatabase().execSQL(
                "INSERT INTO assets(digest,dataPresent,timestampMs) VALUES(?,0,0)",
                new Object[]{MISSING});
    }

    @After public void closeDatabase() {
        if (database != null) database.close();
        if (context != null) context.deleteDatabase("node.db");
    }

    @Test public void readinessUsesExactAppCertificateHostAndPath() {
        DataItemRecord owner = put("app.one", "sig.one", "peer", "/literal_%", 7, ARRIVING);
        DataItemRecord otherApp = put("app.two", "sig.one", "peer", "/literal_%", 7, ARRIVING);
        DataItemRecord otherCertificate = put("app.one", "sig.two", "peer", "/literal_%", 7, ARRIVING);
        DataItemRecord otherHost = put("app.one", "sig.one", "other-peer", "/literal_%", 7, ARRIVING);
        DataItemRecord otherPath = put("app.one", "sig.one", "peer", "/literalXvalue", 7, ARRIVING);
        assertFalse(ready(owner));
        assertEquals("wear://peer/literal_%25", owner.dataItem.uri.toString());

        DataItemRecord mismatchedApp = record("app.two", "sig.one", "peer", "/literal_%", 7, ARRIVING);
        mismatchedApp.source = owner.source;
        assertEquals(0, database.updateAssetsReady(mismatchedApp, true));
        assertFalse(ready(owner));
        DataItemRecord mismatchedCertificate = record("app.one", "sig.two", "peer", "/literal_%", 7, ARRIVING);
        mismatchedCertificate.source = owner.source;
        assertEquals(0, database.updateAssetsReady(mismatchedCertificate, true));
        assertFalse(ready(owner));

        assertEquals(1, database.updateAssetsReady(owner, true));
        assertTrue(ready(owner));
        for (DataItemRecord denied : Arrays.asList(otherApp, otherCertificate, otherHost, otherPath)) {
            assertFalse("A different item must retain its readiness", ready(denied));
        }
    }

    @Test public void waitingItemsIncludeAllAssetsAndRemainSeparateAtEqualSequenceIds() {
        put("app.one", "sig.one", "peer", "/shared", 11, ARRIVING, MISSING);
        put("app.two", "sig.two", "peer", "/shared", 11, ARRIVING, MISSING);
        put("app.unrelated", "sig.three", "peer", "/shared", 11, MISSING);
        List<DataItemRecord> waiting = new ArrayList<>();
        try (Cursor cursor = database.getDataItemsWaitingForAsset(ARRIVING)) {
            while (cursor.moveToNext()) waiting.add(DataItemRecord.fromCursor(cursor));
        }
        assertEquals(2, waiting.size());
        Set<String> packages = new HashSet<>();
        for (DataItemRecord record : waiting) {
            packages.add(record.packageName);
            assertFalse(record.assetsAreReady);
            assertEquals(2, record.dataItem.getAssets().size());
            Set<String> digests = new HashSet<>();
            for (Asset asset : record.dataItem.getAssets().values()) digests.add(asset.getDigest());
            assertEquals(new HashSet<>(Arrays.asList(ARRIVING, MISSING)), digests);
        }
        assertEquals(new HashSet<>(Arrays.asList("app.one", "app.two")), packages);
    }

    @Test public void staleCompletionCannotMarkANewerOrDeletedRecordReady() {
        DataItemRecord current = put("app.one", "sig.one", "peer", "/current", 19, ARRIVING);
        DataItemRecord stale = record("app.one", "sig.one", "peer", "/current", 18, ARRIVING);
        stale.source = current.source;
        assertEquals(0, database.updateAssetsReady(stale, true));
        assertFalse(ready(current));
        DataItemRecord deleted = record("app.one", "sig.one", "peer", "/deleted", 19, ARRIVING);
        deleted.deleted = true;
        database.putRecord(deleted);
        assertEquals(0, database.updateAssetsReady(deleted, true));
        assertFalse(ready(deleted));
    }

    private DataItemRecord put(String app, String signature, String host, String path,
                               long seqId, String... digests) {
        DataItemRecord record = record(app, signature, host, path, seqId, digests);
        database.putRecord(record);
        assertFalse("Fixture with assets must be persisted as pending", ready(record));
        return record;
    }

    private static DataItemRecord record(String app, String signature, String host,
                                          String path, long seqId, String... digests) {
        DataItemRecord record = new DataItemRecord();
        record.packageName = app;
        record.signatureDigest = signature;
        // Distinct origins can have equal sequence IDs. Respect the production
        // unique index on sourceNode/deleted/seqId while testing those collisions.
        record.source = UUID.randomUUID().toString();
        record.seqId = seqId;
        record.v1SeqId = seqId;
        record.dataItem = new DataItemInternal(host, path);
        for (int i = 0; i < digests.length; i++) {
            record.dataItem.addAsset("asset" + i, Asset.createFromRef(digests[i]));
        }
        return record;
    }

    private boolean ready(DataItemRecord record) {
        try (Cursor cursor = database.getReadableDatabase().rawQuery(
                "SELECT assetsPresent FROM appKeyDataItems " +
                        "WHERE packageName=? AND signatureDigest=? AND host=? AND path=? AND seqId=?",
                new String[]{record.packageName, record.signatureDigest, record.dataItem.host,
                        record.dataItem.path, String.valueOf(record.seqId)})) {
            assertTrue("Fixture must have a persisted item", cursor.moveToFirst());
            return cursor.getInt(0) != 0;
        }
    }
}
