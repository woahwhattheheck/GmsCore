/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.wearable;

import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.WearableStatusCodes;
import com.google.android.gms.wearable.internal.GetFdForAssetResponse;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.microg.gms.wearable.BaseWearableCallbacks;
import org.microg.gms.wearable.proto.AppKey;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class AssetFdAccessTest {
    private static final String DIGEST = "AAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final AppKey CALLER = new AppKey("app.one", "signature.one");
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void authorizedCallerReceivesTheActualStoredBytes() throws Exception {
        byte[] payload = {1, 2, 3, 4};
        File file = temporary.newFile("asset");
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(payload); }
        Store manager = new Store(file, Collections.singletonList(CALLER));
        GetFdForAssetResponse response = WearableServiceImpl.createAssetFdResponse(
                manager, Asset.createFromRef(DIGEST), CALLER);
        assertEquals(CommonStatusCodes.SUCCESS, response.statusCode);
        assertNotNull(response.pfd);
        try (ParcelFileDescriptor.AutoCloseInputStream input =
                     new ParcelFileDescriptor.AutoCloseInputStream(response.pfd)) {
            byte[] received = new byte[payload.length];
            assertEquals(payload.length, input.read(received));
            assertArrayEquals(payload, received);
        }
        assertEquals(1, manager.fileLookups);
    }

    @Test public void foreignPackageAndSignatureCannotOpenAKnownDigest() throws Exception {
        File file = temporary.newFile("private-asset");
        for (AppKey owner : Arrays.asList(new AppKey("app.other", "signature.one"),
                new AppKey("app.one", "signature.other"))) {
            Store manager = new Store(file, Collections.singletonList(owner));
            unavailable(WearableServiceImpl.createAssetFdResponse(
                    manager, Asset.createFromRef(DIGEST), CALLER));
            assertEquals("Denied callers must not resolve a file path", 0, manager.fileLookups);
        }
    }

    @Test public void malformedAndAbsentRequestsNeverReachTheAssetStore() throws Exception {
        Store manager = new Store(temporary.newFile("asset"), Collections.singletonList(CALLER));
        unavailable(WearableServiceImpl.createAssetFdResponse(manager, null, CALLER));
        for (String digest : Arrays.asList("", "../private-file", "short", "AAAAAAAAAAAAAAAAAAAAAAAAAA/")) {
            unavailable(WearableServiceImpl.createAssetFdResponse(
                    manager, Asset.createFromRef(digest), CALLER));
        }
        unavailable(WearableServiceImpl.createAssetFdResponse(manager, Asset.createFromRef(DIGEST), null));
        unavailable(WearableServiceImpl.createAssetFdResponse(manager, Asset.createFromRef(DIGEST),
                new AppKey("app.one", null)));
        assertEquals(0, manager.aclLookups);
        assertEquals(0, manager.fileLookups);
    }

    @Test public void missingAuthorizationOrStoredFileReturnsUnavailable() throws Exception {
        Store noPermission = new Store(temporary.newFile("asset"), Collections.emptyList());
        unavailable(WearableServiceImpl.createAssetFdResponse(
                noPermission, Asset.createFromRef(DIGEST), CALLER));
        assertEquals(0, noPermission.fileLookups);
        Store missing = new Store(new File(temporary.getRoot(), "missing"), Collections.singletonList(CALLER));
        unavailable(WearableServiceImpl.createAssetFdResponse(
                missing, Asset.createFromRef(DIGEST), CALLER));
    }

    @Test public void disappearingFileReturnsUnavailableAtOpen() {
        File vanished = new File(temporary.getRoot(), "vanished") {
            @Override public boolean isFile() { return true; }
        };
        Store manager = new Store(vanished, Collections.singletonList(CALLER));
        unavailable(WearableServiceImpl.createAssetFdResponse(
                manager, Asset.createFromRef(DIGEST), CALLER));
        assertEquals(1, manager.fileLookups);
    }

    @Test public void callbackDeliveryClosesTheLocalDescriptor() throws Exception {
        GetFdForAssetResponse response = readableResponse();
        WearableServiceImpl.deliverAssetFdResponse(new BaseWearableCallbacks() {
            @Override public void onGetFdForAssetResponse(GetFdForAssetResponse delivered) {
                assertSame(response, delivered);
                assertTrue("Descriptor stays open during delivery", delivered.pfd.getFileDescriptor().valid());
            }
        }, response);
        assertThrows(IllegalStateException.class, response.pfd::getFd);
    }

    @Test public void callbackFailureClosesTheDescriptorAndPreservesTheFailure() throws Exception {
        GetFdForAssetResponse response = readableResponse();
        RemoteException failure = new RemoteException("callback unavailable");
        RemoteException thrown = assertThrows(RemoteException.class,
                () -> WearableServiceImpl.deliverAssetFdResponse(new BaseWearableCallbacks() {
                    @Override public void onGetFdForAssetResponse(GetFdForAssetResponse delivered)
                            throws RemoteException {
                        throw failure;
                    }
                }, response));
        assertSame(failure, thrown);
        assertThrows(IllegalStateException.class, response.pfd::getFd);
    }

    private GetFdForAssetResponse readableResponse() throws Exception {
        Store manager = new Store(temporary.newFile(), Collections.singletonList(CALLER));
        GetFdForAssetResponse response = WearableServiceImpl.createAssetFdResponse(
                manager, Asset.createFromRef(DIGEST), CALLER);
        assertEquals(CommonStatusCodes.SUCCESS, response.statusCode);
        assertNotNull(response.pfd);
        return response;
    }

    private static void unavailable(GetFdForAssetResponse response) {
        assertEquals(WearableStatusCodes.ASSET_UNAVAILABLE, response.statusCode);
        assertNull(response.pfd);
    }

    private static final class Store extends AssetManager {
        final File file;
        final List<AppKey> owners;
        int aclLookups;
        int fileLookups;
        Store(File file, List<AppKey> owners) { super(null); this.file = file; this.owners = owners; }
        @Override List<AppKey> getAssetAppKeys(String digest) { aclLookups++; return owners; }
        @Override File getStoredAssetFile(String digest) { fileLookups++; return file; }
    }
}
