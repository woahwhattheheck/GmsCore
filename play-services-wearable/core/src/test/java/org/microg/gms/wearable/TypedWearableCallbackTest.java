/* SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */
package org.microg.gms.wearable;

import android.content.Context;
import android.os.Looper;

import com.google.android.gms.common.api.Status;
import com.google.android.gms.wearable.internal.AppRecommendationsResponse;
import com.google.android.gms.wearable.internal.ConsentResponse;
import com.google.android.gms.wearable.internal.ConsentStatusRequest;
import com.google.android.gms.wearable.internal.GetAppThemeResponse;
import com.google.android.gms.wearable.internal.GetEapIdResponse;
import com.google.android.gms.wearable.internal.GetFastpairAccountKeyByAccountResponse;
import com.google.android.gms.wearable.internal.GetFastpairAccountKeysResponse;
import com.google.android.gms.wearable.internal.GetTermsResponse;
import com.google.android.gms.wearable.internal.PerformEapAkaResponse;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
@LooperMode(LooperMode.Mode.PAUSED)
public class TypedWearableCallbackTest {
    private WearableServiceImpl service;
    private RecordingCallbacks callbacks;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        service = new WearableServiceImpl(context, null, context.getPackageName());
        callbacks = new RecordingCallbacks();
    }

    @Test
    public void getTermsCompletesWithTypedDisabledResponse() throws Exception {
        service.getTerms(callbacks, 0);
        finishAndAssertTypes("terms");
        assertEquals(4014, callbacks.terms.get(0).statusCode);
        assertTrue(callbacks.terms.get(0).consents == null
                || callbacks.terms.get(0).consents.isEmpty());
    }

    @Test
    public void getConsentStatusForRequestCompletesWithTypedDisabledResponse() throws Exception {
        service.getConsentStatusForRequest(callbacks, new ConsentStatusRequest("test"));
        finishAndAssertTypes("consent");
        assertEquals(4014, callbacks.consents.get(0).statusCode);
    }

    @Test
    public void getEapIdCompletesWithTypedResponse() throws Exception {
        service.getEapId(callbacks, 1);
        finishAndAssertTypes("eap");
    }

    @Test
    public void performEapAkaCompletesWithTypedResponse() throws Exception {
        service.performEapAka(callbacks, 1, "identity");
        finishAndAssertTypes("aka");
    }

    @Test
    public void getFastpairAccountKeysCompletesWithTypedResponse() throws Exception {
        service.getFastpairAccountKeys(callbacks);
        finishAndAssertTypes("fastpair-keys");
    }

    @Test
    public void getFastpairAccountKeyByAccountCompletesWithTypedResponse() throws Exception {
        service.getFastpairAccountKeyByAccount(callbacks, null);
        finishAndAssertTypes("fastpair-account");
    }

    @Test
    public void getAppRecommendationsCompletesWithTypedResponse() throws Exception {
        service.getAppRecommendations(callbacks, null);
        finishAndAssertTypes("recommendations");
    }

    @Test
    public void getThemeForAppCompletesWithTypedResponse() throws Exception {
        service.getThemeForApp(callbacks, "org.example.app");
        finishAndAssertTypes("theme");
    }

    private void finishAndAssertTypes(String expectedType) {
        assertTrue("The result is dispatched on the main handler", callbacks.types.isEmpty());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(Collections.singletonList(expectedType), callbacks.types);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("No second callback remains queued", 1, callbacks.types.size());
    }

    private static final class RecordingCallbacks extends BaseWearableCallbacks {
        final List<String> types = new ArrayList<>();
        final List<GetTermsResponse> terms = new ArrayList<>();
        final List<ConsentResponse> consents = new ArrayList<>();

        @Override
        public void onGetTermsResponse(GetTermsResponse response) {
            types.add("terms");
            terms.add(response);
        }

        @Override
        public void onConsentResponse(ConsentResponse response) {
            types.add("consent");
            consents.add(response);
        }

        @Override
        public void onGetEapIdResponse(GetEapIdResponse response) {
            types.add("eap");
        }

        @Override
        public void onPerformEapAkaResponse(PerformEapAkaResponse response) {
            types.add("aka");
        }

        @Override
        public void onGetFastpairAccountKeysResponse(GetFastpairAccountKeysResponse response) {
            types.add("fastpair-keys");
        }

        @Override
        public void onGetFastpairAccountKeyByAccountResponse(
                GetFastpairAccountKeyByAccountResponse response) {
            types.add("fastpair-account");
        }

        @Override
        public void onAppRecommendationsResponse(AppRecommendationsResponse response) {
            types.add("recommendations");
        }

        @Override
        public void onGetAppThemeResponse(GetAppThemeResponse response) {
            types.add("theme");
        }

        @Override
        public void onStatus(Status status) {
            fail("A generic status cannot replace the API's typed response");
        }
    }
}
