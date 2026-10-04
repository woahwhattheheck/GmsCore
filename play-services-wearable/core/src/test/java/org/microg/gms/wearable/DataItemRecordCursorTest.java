/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.wearable;

import android.database.MatrixCursor;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class DataItemRecordCursorTest {
    private static final String[] DATA_ITEM_ASSET_COLUMNS = {
            "dataitems_id", "packageName", "signatureDigest", "host", "path", "seqId",
            "deleted", "sourceNode", "data", "timestampMs", "assetsPresent", "assetname",
            "assets_digest", "v1SourceNode", "v1SeqId"
    };

    @Test
    public void materializesContiguousAssetRowsByDataItemIdAndPreservesOuterIteration() {
        MatrixCursor cursor = new MatrixCursor(DATA_ITEM_ASSET_COLUMNS);
        cursor.addRow(row(101, "example.app", "sig-a", "node-a", "/shared", 7,
                "source-a", "asset-one", "digest-one", 70));
        cursor.addRow(row(101, "example.app", "sig-a", "node-a", "/shared", 7,
                "source-a", "asset-two", "digest-two", 70));
        // Sequence IDs belong to a source node and can coincide across sources.
        // This row is a different database item and must remain for the outer loop.
        cursor.addRow(row(102, "example.app", "sig-a", "node-b", "/shared", 7,
                "source-b", "other-item-asset", "digest-other", 71));
        cursor.addRow(row(103, "example.app", "sig-a", "node-c", "/next", 8,
                "source-c", "last-item-asset", "digest-last", 80));

        assertTrue(cursor.moveToFirst());

        DataItemRecord first = DataItemRecord.fromCursor(cursor);
        assertEquals("example.app", first.packageName);
        assertEquals("sig-a", first.signatureDigest);
        assertEquals("node-a", first.dataItem.host);
        assertEquals("/shared", first.dataItem.path);
        assertEquals(7L, first.seqId);
        assertEquals(70L, first.v1SeqId);
        assertEquals("source-a", first.source);
        assertEquals(2, first.dataItem.getAssets().size());
        assertEquals("digest-one", first.dataItem.getAssets().get("asset-one").getDigest());
        assertEquals("digest-two", first.dataItem.getAssets().get("asset-two").getDigest());
        assertEquals("materializer should leave the cursor on the final row for this item",
                101L, cursor.getLong(cursor.getColumnIndexOrThrow("dataitems_id")));

        assertTrue(cursor.moveToNext());
        DataItemRecord second = DataItemRecord.fromCursor(cursor);
        assertEquals("node-b", second.dataItem.host);
        assertEquals(1, second.dataItem.getAssets().size());
        assertEquals("digest-other", second.dataItem.getAssets().get("other-item-asset").getDigest());

        assertTrue(cursor.moveToNext());
        DataItemRecord third = DataItemRecord.fromCursor(cursor);
        assertEquals("/next", third.dataItem.path);
        assertEquals("digest-last", third.dataItem.getAssets().get("last-item-asset").getDigest());
        assertFalse(cursor.moveToNext());
    }

    @Test
    public void missingOrPartialAssetColumnsDoNotBreakBaseRecordMaterialization() {
        String[] baseColumns = {
                "dataitems_id", "packageName", "signatureDigest", "host", "path", "seqId",
                "deleted", "sourceNode", "data", "timestampMs", "assetsPresent"
        };
        MatrixCursor noAssetColumns = new MatrixCursor(baseColumns);
        noAssetColumns.addRow(new Object[]{201L, "example.app", "sig", "node", "/no-assets",
                9L, 0, "source", new byte[]{1, 2}, 123L, 1});
        assertTrue(noAssetColumns.moveToFirst());

        DataItemRecord withoutAssets = DataItemRecord.fromCursor(noAssetColumns);
        assertEquals("/no-assets", withoutAssets.dataItem.path);
        assertEquals(9L, withoutAssets.seqId);
        assertEquals(9L, withoutAssets.v1SeqId);
        assertTrue(withoutAssets.dataItem.getAssets().isEmpty());

        // Twelve columns used to pass the old getColumnCount() >= 12 check even
        // though the code then read index 12. A lone assetname is not an asset pair.
        MatrixCursor partialAssetColumns = new MatrixCursor(
                new String[]{"dataitems_id", "packageName", "signatureDigest", "host", "path",
                        "seqId", "deleted", "sourceNode", "data", "timestampMs", "assetsPresent",
                        "assetname"});
        partialAssetColumns.addRow(new Object[]{202L, "example.app", "sig", "node", "/partial",
                10L, 0, "source", null, 124L, 0, "orphan-key"});
        assertTrue(partialAssetColumns.moveToFirst());

        DataItemRecord partial = DataItemRecord.fromCursor(partialAssetColumns);
        assertEquals("/partial", partial.dataItem.path);
        assertEquals(10L, partial.seqId);
        assertTrue(partial.dataItem.getAssets().isEmpty());
    }

    private static Object[] row(long id, String packageName, String signatureDigest, String host,
                                String path, long seqId, String source, String assetName,
                                String assetDigest, long v1SeqId) {
        return new Object[]{id, packageName, signatureDigest, host, path, seqId, 0, source,
                new byte[]{1}, 123L, 0, assetName, assetDigest, source, v1SeqId};
    }
}
