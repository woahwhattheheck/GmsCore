/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.wearable;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.os.Looper;
import com.google.android.gms.wearable.CapabilityApi;
import com.google.android.gms.wearable.ConnectionConfiguration;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.internal.GetAllCapabilitiesResponse;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.microg.gms.common.PackageUtils;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Exercises the actual Binder entrypoints, main queue and capability SQLite queries. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
@LooperMode(LooperMode.Mode.PAUSED)
public class CapabilityServiceTest {
    private static final String PACKAGE = "test.wear.capabilities";
    private static final String CAPABILITY = "media_controls";
    private Context context;
    private NodeDatabaseHelper nodes;
    private ConfigurationDatabaseHelper configurations;
    private TestWearable wearable;
    private WearableServiceImpl service;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        PackageInfo info = new PackageInfo();
        info.packageName = PACKAGE;
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = PACKAGE;
        info.signatures = new Signature[]{new Signature("01020304")};
        shadowOf(context.getPackageManager()).installPackage(info);
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

    @Test public void emptyRegistryCompletesWithOneSuccessfulResponse() throws Exception {
        GetAllCapabilitiesResponse response = all(CapabilityApi.FILTER_ALL);
        assertEquals(0, response.statusCode);
        assertTrue(response.capabilities.isEmpty());
    }

    @Test public void allAndReachableQueriesCompleteWithTheRequestedNodes() throws Exception {
        putCapability("connected");
        putCapability("disconnected");
        putCapability("unknown");
        wearable.live = new ConnectionConfiguration[]{configuration("connected", true),
                configuration("disconnected", false)};

        GetAllCapabilitiesResponse all = all(CapabilityApi.FILTER_ALL);
        assertEquals(0, all.statusCode);
        assertEquals(1, all.capabilities.size());
        assertEquals(CAPABILITY, all.capabilities.get(0).getName());
        assertEquals(new HashSet<>(Arrays.asList("connected", "disconnected", "unknown")),
                nodeIds(all.capabilities.get(0).getNodes()));

        GetAllCapabilitiesResponse reachable = all(CapabilityApi.FILTER_REACHABLE);
        assertEquals(0, reachable.statusCode);
        assertEquals(1, reachable.capabilities.size());
        assertEquals(new HashSet<>(Arrays.asList("connected")),
                nodeIds(reachable.capabilities.get(0).getNodes()));


    }

    @Test public void reachableQueryWithNoConnectedNodesCompletesEmpty() throws Exception {
        putCapability("unknown");
        GetAllCapabilitiesResponse response = all(CapabilityApi.FILTER_REACHABLE);
        assertEquals(0, response.statusCode);
        assertTrue(response.capabilities.isEmpty());
    }

    private GetAllCapabilitiesResponse all(int filter) throws Exception {
        List<GetAllCapabilitiesResponse> responses = new ArrayList<>();
        service.getAllCapabilities(new BaseWearableCallbacks() {
            @Override public void onGetAllCapabilitiesResponse(GetAllCapabilitiesResponse response) {
                responses.add(response);
            }
        }, filter);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("Every successful request must complete exactly once", 1, responses.size());
        return responses.get(0);
    }

    private void putCapability(String host) {
        CapabilityManager capabilities = new CapabilityManager(context, wearable, PACKAGE);
        DataItemRecord record = new DataItemRecord();
        record.packageName = PACKAGE;
        record.signatureDigest = PackageUtils.firstSignatureDigest(context, PACKAGE);
        record.source = host;
        record.seqId = 1;
        record.dataItem = new DataItemInternal(
                capabilities.buildCapabilityUri(CAPABILITY, false).buildUpon().authority(host).build());
        nodes.putRecord(record);
    }

    private static ConnectionConfiguration configuration(String node, boolean connected) {
        ConnectionConfiguration config = new ConnectionConfiguration(node, node, 1, 1, true);
        config.peerNodeId = node;
        config.connected = connected;
        return config;
    }

    private static Set<String> nodeIds(Set<Node> nodes) {
        Set<String> ids = new HashSet<>();
        for (Node node : nodes) ids.add(node.getId());
        return ids;
    }

    private static class TestWearable extends WearableImpl {
        ConnectionConfiguration[] live = new ConnectionConfiguration[0];
        TestWearable(Context context, NodeDatabaseHelper nodes, ConfigurationDatabaseHelper configs) {
            super(context, nodes, configs);
        }
        @Override public synchronized ConnectionConfiguration[] getConfigurations() {
            return live;
        }
    }
}
