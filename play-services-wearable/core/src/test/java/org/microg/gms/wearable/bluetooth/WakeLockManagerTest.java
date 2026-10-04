/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.wearable.bluetooth;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class WakeLockManagerTest {
    private RecordingScheduledExecutor executor;
    private WakeLockManager manager;

    @Before public void setUp() {
        executor = new RecordingScheduledExecutor();
        manager = new WakeLockManager(RuntimeEnvironment.getApplication(), "timeout-test", executor);
    }

    @After public void tearDown() {
        if (manager != null) manager.shutdown();
        if (executor != null) executor.shutdownNow();
    }

    @Test public void requestedTimeoutSchedulesAndOnlyLaterDeadlineExtendsIt() {
        manager.acquire("initial", 8_000);
        assertEquals("initial timeout should be scheduled", 1, executor.delaysMs.size());
        assertPositiveAtMost(executor.delaysMs.get(0), 8_000);

        manager.acquire("shorter", 1_000);
        assertEquals("shorter request must not move the existing deadline",
                1, executor.delaysMs.size());

        manager.acquire("longer", 9_000);
        assertEquals("later deadline should replace the scheduled timeout",
                2, executor.delaysMs.size());
        assertPositiveAtMost(executor.delaysMs.get(1), 9_000);
    }

    private static void assertPositiveAtMost(long actualMs, long requestedMs) {
        assertTrue("delay should be positive", actualMs > 0);
        assertTrue("delay must not exceed the requested timeout", actualMs <= requestedMs);
    }

    private static final class RecordingScheduledExecutor extends ScheduledThreadPoolExecutor {
        final List<Long> delaysMs = new ArrayList<>();

        RecordingScheduledExecutor() {
            super(1);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            delaysMs.add(unit.toMillis(delay));
            return super.schedule(command, 1, TimeUnit.DAYS);
        }
    }
}
