/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.droidguard.core

import android.app.Application
import android.os.Bundle
import android.os.ParcelFileDescriptor
import com.google.android.gms.droidguard.internal.DroidGuardInitReply
import com.google.android.gms.droidguard.internal.DroidGuardResultsRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reply-boundary tests only. Native core first executes its real empty-reply initialization;
 * the shadow substitutes a controlled reply afterward. Partial replies are not claimed as
 * outputs produced by the normal core implementation.
 */
@Implements(value = DroidGuardHandleImpl::class, isInAndroidSdk = false)
class InitReplyBoundaryCoreShadow {
    @RealObject
    private lateinit var actual: DroidGuardHandleImpl

    @Implementation
    protected fun initWithRequest(flow: String?, request: DroidGuardResultsRequest?): DroidGuardInitReply {
        val original: DroidGuardInitReply = Shadow.directlyOn(actual, DroidGuardHandleImpl::class.java,
            "initWithRequest", ClassParameter.from(String::class.java, flow),
            ClassParameter.from(DroidGuardResultsRequest::class.java, request))
        check(original.pfd == null && original.`object` == null) {
            "Boundary substitution must start from actual empty-reply core initialization"
        }
        return requireNotNull(reply) { "Controlled reply boundary is not configured" }
    }

    companion object {
        @Volatile var reply: DroidGuardInitReply? = null
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class,
    shadows = [InitReplyBoundaryCoreShadow::class],
    instrumentedPackages = ["org.microg.gms.droidguard.core.DroidGuardHandleImpl"])
class DroidGuardSessionProviderPartialReplyTest : DroidGuardSessionProviderTestHarness() {
    @Before
    fun resetBoundaryBefore() {
        InitReplyBoundaryCoreShadow.reply = null
    }

    @After
    fun resetBoundaryAfter() {
        InitReplyBoundaryCoreShadow.reply = null
    }

    @Test
    fun controlledDescriptorOnlyReplyIsRejectedAndClosed() {
        val descriptor = ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            assertEquals(1, openApkDescriptors())
            InitReplyBoundaryCoreShadow.reply = DroidGuardInitReply(descriptor, null)
            assertRejectedWithoutId(begin(), UNSUPPORTED)
            assertEquals(0, openApkDescriptors())
            assertEquals(1, vm.initCalls.get())
            assertEquals(1, vm.rbCalls.get())
            assertEquals(0, apkCalls.get())
        } finally {
            descriptor.close()
        }
    }

    @Test
    fun controlledObjectOnlyReplyIsRejectedWithoutId() {
        InitReplyBoundaryCoreShadow.reply = DroidGuardInitReply(null, Bundle())
        assertRejectedWithoutId(begin(), UNSUPPORTED)
        assertEquals(1, vm.initCalls.get())
        assertEquals(1, vm.rbCalls.get())
        assertEquals(0, apkCalls.get())
    }

    @Test
    fun controlledBothFieldsReplyIsRejectedAndClosed() {
        val descriptor = ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            assertEquals(1, openApkDescriptors())
            InitReplyBoundaryCoreShadow.reply = DroidGuardInitReply(descriptor, Bundle())
            assertRejectedWithoutId(begin(), UNSUPPORTED)
            assertEquals(0, openApkDescriptors())
            assertEquals(1, vm.initCalls.get())
            assertEquals(1, vm.rbCalls.get())
        } finally {
            descriptor.close()
        }
    }

    @Test
    fun controlledDescriptorCloseExceptionStillRejectsAndClosesVm() {
        val closeAttempts = AtomicInteger()
        val failingDescriptor = Mockito.mock(ParcelFileDescriptor::class.java) { invocation ->
            when (invocation.method.name) {
                "close" -> {
                    closeAttempts.incrementAndGet()
                    throw IOException("controlled descriptor close failure")
                }
                else -> Answers.RETURNS_DEFAULTS.answer(invocation)
            }
        }
        InitReplyBoundaryCoreShadow.reply = DroidGuardInitReply(failingDescriptor, Bundle())
        // This tests attempted close plus core cleanup. It cannot prove an OS FD closed
        // after close itself throws: this descriptor is an explicitly controlled mock.
        assertRejectedWithoutId(begin())
        assertEquals(1, closeAttempts.get())
        assertEquals(1, vm.initCalls.get())
        assertEquals(1, vm.rbCalls.get())
    }
}
