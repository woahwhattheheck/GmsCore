/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.google.android.gms.wearable.internal;

import android.os.Parcel;
import android.os.Parcelable;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class RequestParcelableRoundTripTest {
    @Test
    public void acceptTermsRequestRoundTripsAllFields() {
        List<String> firstList = Arrays.asList("consent-one", "consent-two");
        List<String> secondList = Arrays.asList("region-a", "region-b");
        AcceptTermsRequest request = new AcceptTermsRequest(
                37, firstList, "terms-version", "account-name", "node-id", "source", secondList, true);

        AcceptTermsRequest result = roundTrip(request, AcceptTermsRequest.CREATOR);

        assertEquals(37, result.statusCode);
        assertEquals(firstList, result.unk2);
        assertEquals("terms-version", result.unk3);
        assertEquals("account-name", result.unk4);
        assertEquals("node-id", result.unk5);
        assertEquals("source", result.unk6);
        assertEquals(secondList, result.unk7);
        assertTrue(result.unk8);
    }

    @Test
    public void recordTermConsentRequestRoundTripsAllFields() {
        RecordTermConsentRequest request = new RecordTermConsentRequest(
                19, 23, true, "consent-token", "node-id", "source");

        RecordTermConsentRequest result = roundTrip(request, RecordTermConsentRequest.CREATOR);

        assertEquals(19, result.unk1);
        assertEquals(23, result.unk2);
        assertTrue(result.unk3);
        assertEquals("consent-token", result.unk4);
        assertEquals("node-id", result.unk5);
        assertEquals("source", result.unk6);
    }

    @Test
    public void consentStatusRequestRoundTripsStatus() {
        ConsentStatusRequest request = new ConsentStatusRequest("requested-status");

        ConsentStatusRequest result = roundTrip(request, ConsentStatusRequest.CREATOR);

        assertEquals("requested-status", result.status);
    }

    private static <T extends Parcelable> T roundTrip(T value, Parcelable.Creator<T> creator) {
        Parcel parcel = Parcel.obtain();
        try {
            value.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return creator.createFromParcel(parcel);
        } finally {
            parcel.recycle();
        }
    }
}
