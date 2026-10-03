/*
 * SPDX-FileCopyrightText: 2026, microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.constellation.core

import android.os.IBinder
import com.google.android.gms.common.api.ApiMetadata
import com.google.android.gms.common.api.Status
import com.google.android.gms.constellation.GetIidTokenRequest
import com.google.android.gms.constellation.GetIidTokenResponse
import com.google.android.gms.constellation.GetPnvCapabilitiesResponse
import com.google.android.gms.constellation.PhoneNumberInfo
import com.google.android.gms.constellation.VerifyPhoneNumberResponse
import com.google.android.gms.constellation.internal.IConstellationCallbacks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import java.io.IOException

/**
 * Drives the REAL handleGetIidToken handler (its real withContext(Dispatchers.IO) body and
 * exception mapping) through the module's own IidCredentialProvider injection seam — not a mock
 * body. Proves that a cancellation surfacing inside the handler propagates and is NOT turned into
 * an INTERNAL_ERROR delivery to a (dead) caller, while genuine failures still map to INTERNAL_ERROR.
 */
class GetIidTokenCancellationTest {

    private class RecordingCallbacks : IConstellationCallbacks {
        var iidStatus: Status? = null
        var deliveries = 0
        override fun onPhoneNumberVerified(
            status: Status?, phoneNumbers: List<PhoneNumberInfo?>?, apiMetadata: ApiMetadata?
        ) { deliveries++ }
        override fun onPhoneNumberVerificationsCompleted(
            status: Status?, response: VerifyPhoneNumberResponse?, apiMetadata: ApiMetadata?
        ) { deliveries++ }
        override fun onIidTokenGenerated(
            status: Status?, response: GetIidTokenResponse?, apiMetadata: ApiMetadata?
        ) { iidStatus = status; deliveries++ }
        override fun onGetPnvCapabilitiesCompleted(
            status: Status?, response: GetPnvCapabilitiesResponse?, apiMetadata: ApiMetadata?
        ) { deliveries++ }
        override fun asBinder(): IBinder = Mockito.mock(IBinder::class.java)
    }

    private fun provider(getToken: () -> String) = object : IidCredentialProvider {
        override fun getIidToken(projectNumber: String?): String = getToken()
        override fun getFid(): String = "fid"
        override fun signIidToken(iidToken: String): Pair<ByteArray, Long> = byteArrayOf(1, 2, 3) to 1234L
    }

    @Test
    fun cancellationSurfacingInHandler_propagates_andDoesNotDeliver() {
        val callbacks = RecordingCallbacks()
        val thrown = runCatching {
            runBlocking {
                handleGetIidToken(
                    callbacks,
                    GetIidTokenRequest(null),
                    provider { throw CancellationException("caller process died") }
                )
            }
        }.exceptionOrNull()

        assertTrue("cancellation must propagate, not be mapped to a Status", thrown is CancellationException)
        assertEquals("a cancelled request must not deliver anything to the caller", 0, callbacks.deliveries)
        assertNull(callbacks.iidStatus)
    }

    @Test
    fun genuineFailure_stillMapsToInternalError_andDelivers() {
        val callbacks = RecordingCallbacks()
        runBlocking {
            handleGetIidToken(callbacks, GetIidTokenRequest(null), provider { throw IOException("boom") })
        }
        assertEquals(1, callbacks.deliveries)
        assertEquals(Status.INTERNAL_ERROR.statusCode, callbacks.iidStatus?.statusCode)
    }

    @Test
    fun success_delivers() {
        val callbacks = RecordingCallbacks()
        runBlocking {
            handleGetIidToken(callbacks, GetIidTokenRequest(null), provider { "iid-token" })
        }
        assertEquals(1, callbacks.deliveries)
        assertEquals(Status.SUCCESS.statusCode, callbacks.iidStatus?.statusCode)
    }
}
