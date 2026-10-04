/* SPDX-License-Identifier: Apache-2.0 */

package org.microg.gms.wearable;

import android.content.Context;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.microg.gms.common.PackageUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Exercises CapabilityManager against the production SQLite database on Android. */
@RunWith(AndroidJUnit4.class)
public final class CapabilityLookupIsolationTest {

    @Test
    public void testCapabilityLookupIsolation() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        NodeDatabaseHelper nodeDatabase = null;
        ConfigurationDatabaseHelper configurationDatabase = null;
        WearableImpl wearable = null;
        try {
            context.deleteDatabase("node.db");
            context.deleteDatabase("connectionconfig.db");
            nodeDatabase = new NodeDatabaseHelper(context);
            configurationDatabase = new ConfigurationDatabaseHelper(context);
            wearable = new WearableImpl(context, nodeDatabase, configurationDatabase);

            List<String> failures = new ArrayList<>();
            verifyScopedLookup(context, wearable, failures);
            verifyLiteralUnderscore(context, wearable, failures);
            verifyLiteralPercent(context, wearable, failures);
            verifyEncodedCapabilityName(context, wearable, failures);
            if (!failures.isEmpty()) {
                throw new AssertionError(String.join("; ", failures));
            }
        } finally {
            if (wearable != null) {
                wearable.stop();
            }
            if (nodeDatabase != null) {
                nodeDatabase.close();
            }
            if (configurationDatabase != null) {
                configurationDatabase.close();
            }
        }
    }

    private static void verifyScopedLookup(Context context, WearableImpl wearable,
                                           List<String> failures) {
        String app = context.getPackageName();
        String signature = PackageUtils.firstSignatureDigest(context, app);
        check(signature != null && !signature.isEmpty(), "test APK signature digest unavailable");

        CapabilityManager manager = new CapabilityManager(context, wearable, app);
        String capability = "scope-sensitive-capability";
        check(manager.add(capability) == 0, "could not store local capability");
        String exactPath = manager.buildCapabilityUri(capability, false).getPath();
        String localNode = wearable.getLocalNodeId();

        putRecord(wearable, app, signature, exactPath, "remote-valid", false);

        String otherPackage = app + ".other";
        String otherSignature = "different-signature";
        putRecord(wearable, otherPackage, signature, exactPath,
                "remote-other-package", false);
        putRecord(wearable, app, otherSignature, exactPath,
                "remote-other-signature", false);

        // A peer can supply mismatched path/app-key metadata. Keep the app-key predicate
        // independently observable even when the URI path collides with the caller's.
        putRecord(wearable, otherPackage, signature, exactPath,
                "remote-package-path-collision", false);
        putRecord(wearable, app, otherSignature, exactPath,
                "remote-signature-path-collision", false);
        putRecord(wearable, app, signature, exactPath,
                "remote-deleted", true);

        Set<String> nodes = manager.getNodesForCapability(capability);
        expectPresent(nodes, localNode, "valid local capability result", failures);
        expectPresent(nodes, "remote-valid", "valid remote capability result", failures);
        expectAbsent(nodes, "remote-other-package", "foreign package with path collision", failures);
        expectAbsent(nodes, "remote-other-signature", "foreign signature with path collision", failures);
        expectAbsent(nodes, "remote-package-path-collision", "foreign package-key collision", failures);
        expectAbsent(nodes, "remote-signature-path-collision", "foreign signature-key collision", failures);
        expectAbsent(nodes, "remote-deleted", "deleted capability row", failures);

        Uri rootCapabilities = Uri.parse("wear:/capabilities/");
        com.google.android.gms.common.data.DataHolder allCapabilities =
                wearable.getDataItemsByUriAsHolder(rootCapabilities, app);
        try {
            check(allCapabilities.getCount() > 0,
                    "getAllCapabilities root prefix no longer reaches capability children");
        } finally {
            allCapabilities.close();
        }
    }

    private static void verifyLiteralUnderscore(Context context, WearableImpl wearable,
                                                List<String> failures) {
        String app = context.getPackageName();
        String signature = PackageUtils.firstSignatureDigest(context, app);
        CapabilityManager manager = new CapabilityManager(context, wearable, app);
        String target = "literal_under_score";
        String lookalike = "literalXunderYscore";
        check(manager.add(target) == 0, "could not add underscore capability");
        check(manager.add(lookalike) == 0, "could not add underscore lookalike capability");
        putRecord(wearable, app, signature, manager.buildCapabilityUri(target, false).getPath(),
                "remote-underscore-target", false);
        putRecord(wearable, app, signature, manager.buildCapabilityUri(lookalike, false).getPath(),
                "remote-underscore-lookalike", false);

        Set<String> nodes = manager.getNodesForCapability(target);
        expectPresent(nodes, "remote-underscore-target", "exact underscore capability", failures);
        expectAbsent(nodes, "remote-underscore-lookalike", "underscore is literal, not LIKE wildcard", failures);
    }

    private static void verifyLiteralPercent(Context context, WearableImpl wearable,
                                             List<String> failures) {
        String app = context.getPackageName();
        String signature = PackageUtils.firstSignatureDigest(context, app);
        CapabilityManager manager = new CapabilityManager(context, wearable, app);
        String target = "literal%capability";
        String lookalike = "literal-long-capability";
        check(manager.add(target) == 0, "could not add percent capability");
        check(manager.add(lookalike) == 0, "could not add percent lookalike capability");
        putRecord(wearable, app, signature, manager.buildCapabilityUri(target, false).getPath(),
                "remote-percent-target", false);
        putRecord(wearable, app, signature, manager.buildCapabilityUri(lookalike, false).getPath(),
                "remote-percent-lookalike", false);

        Set<String> nodes = manager.getNodesForCapability(target);
        expectPresent(nodes, "remote-percent-target", "exact percent capability", failures);
        expectAbsent(nodes, "remote-percent-lookalike", "percent is literal, not LIKE wildcard", failures);
    }

    private static void verifyEncodedCapabilityName(Context context, WearableImpl wearable,
                                                    List<String> failures) {
        String app = context.getPackageName();
        String signature = PackageUtils.firstSignatureDigest(context, app);
        CapabilityManager manager = new CapabilityManager(context, wearable, app);
        String capability = "encoded/name";
        check(manager.add(capability) == 0, "could not add encoded capability");
        String path = manager.buildCapabilityUri(capability, false).getPath();
        putRecord(wearable, app, signature, path, "remote-encoded-name", false);

        expectPresent(manager.getNodesForCapability(capability), "remote-encoded-name",
                "remote capability path uses the same URI encoding as local storage", failures);
    }

    private static void putRecord(WearableImpl wearable, String packageName,
                                  String signatureDigest, String path, String nodeId,
                                  boolean deleted) {
        DataItemRecord record = new DataItemRecord();
        record.packageName = packageName;
        record.signatureDigest = signatureDigest;
        record.deleted = deleted;
        record.source = nodeId;
        record.seqId = Math.abs(nodeId.hashCode()) + 10L;
        record.v1SeqId = record.seqId;
        record.lastModified = System.currentTimeMillis();
        record.dataItem = new DataItemInternal(nodeId, path);
        wearable.putDataItem(record);
    }

    private static void expectPresent(Set<String> actual, String expected, String context,
                                      List<String> failures) {
        if (!actual.contains(expected)) {
            failures.add(context + ": expected " + expected + " in " + new TreeSet<>(actual));
        }
    }

    private static void expectAbsent(Set<String> actual, String unexpected, String context,
                                     List<String> failures) {
        if (actual.contains(unexpected)) {
            failures.add(context + ": unexpected " + unexpected + " in " + new TreeSet<>(actual));
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
