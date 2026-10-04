/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.wearable;

import org.junit.Test;
import java.lang.reflect.Field;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class AssetCompletionListenerTest {
    private static final String DIGEST = "AAAAAAAAAAAAAAAAAAAAAAAAAAA";

    @Test public void concurrentCompletionsClaimEachRegistrationOnce() throws Exception {
        AssetManager manager = new AssetManager(null);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier snapshots = new CyclicBarrier(2);
        CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<Runnable>() {
            @Override public Object[] toArray() {
                Object[] snapshot = super.toArray();
                // Expose overlapping unlocked snapshots; a locked drain is serialized.
                if (snapshot.length > 0 && !Thread.holdsLock(this)) {
                    try { snapshots.await(5, TimeUnit.SECONDS); }
                    catch (Exception e) { throw new AssertionError(e); }
                }
                return snapshot;
            }
        };
        try {
            Field field = AssetManager.class.getDeclaredField("listeners");
            field.setAccessible(true);
            field.set(manager, listeners);
            AtomicInteger callbacks = new AtomicInteger();
            manager.onAssetMissing(DIGEST, "test.app", "test.signature");
            manager.addCompletionListener(() -> {
                assertFalse("Run callbacks outside the listener monitor", Thread.holdsLock(listeners));
                callbacks.incrementAndGet();
            });
            assertEquals(0, callbacks.get());
            Future<?> first = pool.submit(() -> manager.onAssetReceived(DIGEST));
            Future<?> second = pool.submit(() -> manager.onAssetReceived(DIGEST));
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
            assertEquals(1, callbacks.get());
        } finally {
            pool.shutdownNow();
            manager.shutdown();
        }
    }

    @Test public void repeatedRegistrationRemainsTwoCallbacks() {
        AssetManager manager = new AssetManager(null);
        try {
            AtomicInteger callbacks = new AtomicInteger();
            Runnable listener = callbacks::incrementAndGet;
            manager.onAssetMissing(DIGEST, "test.app", "test.signature");
            manager.addCompletionListener(listener);
            manager.addCompletionListener(listener);
            assertEquals(0, callbacks.get());
            manager.onAssetReceived(DIGEST);
            assertEquals(2, callbacks.get());
        } finally {
            manager.shutdown();
        }
    }
}
