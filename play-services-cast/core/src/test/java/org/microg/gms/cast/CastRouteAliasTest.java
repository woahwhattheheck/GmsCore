/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.cast;

import android.net.nsd.NsdServiceInfo;
import androidx.mediarouter.media.MediaRouteDescriptor;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class CastRouteAliasTest {
    private CastMediaRouteProvider provider;
    private Method resolved;
    private Method lost;

    @Before
    public void setUp() throws Exception {
        provider = new CastMediaRouteProvider(RuntimeEnvironment.getApplication());
        resolved = CastMediaRouteProvider.class.getDeclaredMethod("onServiceResolvedInternal", NsdServiceInfo.class);
        resolved.setAccessible(true);
        lost = CastMediaRouteProvider.class.getDeclaredMethod("onChromeCastLost", String.class);
        lost.setAccessible(true);
    }

    @Test
    public void losingOneServiceKeepsTheReceiverAdvertisedByAnotherService() throws Exception {
        discover("receiver-old", "receiver-id");
        discover("receiver-new", "receiver-id");
        assertRoutes("receiver-id");
        lost.invoke(provider, "receiver-old");
        assertRoutes("receiver-id");
        lost.invoke(provider, "receiver-new");
        assertRoutes();
    }

    @Test
    public void losingTheOnlyServiceStillRemovesItsReceiver() throws Exception {
        discover("single-service", "single-id");
        assertRoutes("single-id");
        lost.invoke(provider, "single-service");
        assertRoutes();
    }

    @Test
    public void losingAnotherOrUnknownServiceKeepsTheOtherReceiver() throws Exception {
        discover("first-service", "first-id");
        discover("second-service", "second-id");
        lost.invoke(provider, "unknown-service");
        assertRoutes("first-id", "second-id");
        lost.invoke(provider, "first-service");
        assertRoutes("second-id");
    }

    private void discover(String name, String id) throws Exception {
        NsdServiceInfo service = new NsdServiceInfo();
        service.setServiceName(name);
        service.setServiceType("_googlecast._tcp.");
        service.setHost(InetAddress.getByName("127.0.0.1"));
        service.setPort(8009);
        service.setAttribute("id", id);
        service.setAttribute("fn", "Cast receiver");
        resolved.invoke(provider, service);
    }

    private void assertRoutes(String... expected) {
        List<String> actual = new ArrayList<>();
        for (MediaRouteDescriptor route : provider.getDescriptor().getRoutes()) actual.add(route.getId());
        assertEquals(Arrays.asList(expected), actual);
    }
}
