package org.microg.gms.wearable.bluetooth;

import android.content.Context;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class WakeLockManager {
    private static final String TAG = "WakeLockManager";
    private static final long DEFAULT_TIMEOUT_MS = 5 * 60 * 1000L;
    private static final long MAX_TIMEOUT_MS = 10 * 60 * 1000L;

    private final Context context;
    private final PowerManager.WakeLock wakeLock;
    private final Object lock = new Object();
    private final ScheduledExecutorService executor;

    private final AtomicInteger refCount = new AtomicInteger(0);
    private final Map<String, Integer> tagCounts = new HashMap<>();

    private ScheduledFuture<?> timeoutFuture;
    private long acquireTimeMs = 0;
    private long timeoutDeadlineElapsedMs = 0;

    private int totalAcquires = 0;
    private int totalReleases = 0;
    private int forceReleaseCount = 0;
    private boolean isForceReleased = false;

    private volatile boolean isEnabled = true;

    public WakeLockManager(Context context, String tag, ScheduledExecutorService executor) {
        this.context = context;
        this.executor = executor;

        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (pm == null) {
            throw new IllegalStateException("PowerManager not available");
        }

        this.wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "GmsWear:" + tag
        );
        this.wakeLock.setReferenceCounted(false);
    }

    public void acquire() {
        acquire(null, DEFAULT_TIMEOUT_MS);
    }

    public void acquire(long timeoutMs) {
        acquire(null, timeoutMs);
    }

    public void acquire(String tag, long timeoutMs) {
        synchronized (lock) {
            if (!isEnabled) {
                Log.w(TAG, "Wake lock disabled, ignoring acquire request");
                return;
            }

            int count = refCount.incrementAndGet();
            totalAcquires++;

            if (tag != null) {
                Integer tagCount = tagCounts.get(tag);
                tagCounts.put(tag, tagCount == null ? 1 : tagCount + 1);
            }

            Log.d(TAG, String.format("acquire(tag=%s, timeout=%dms) refCount=%d",
                    tag, timeoutMs, count));

            long effectiveTimeoutMs = timeoutMs > 0
                    ? Math.min(timeoutMs, MAX_TIMEOUT_MS)
                    : 0;
            long requestedDeadlineElapsedMs = effectiveTimeoutMs > 0
                    ? SystemClock.elapsedRealtime() + effectiveTimeoutMs
                    : 0;
            long platformTimeoutMs = effectiveTimeoutMs > 0
                    ? Math.max(DEFAULT_TIMEOUT_MS, effectiveTimeoutMs)
                    : DEFAULT_TIMEOUT_MS;

            if (count == 1) {
                try {
                    wakeLock.acquire(platformTimeoutMs);
                    acquireTimeMs = System.currentTimeMillis();
                    isForceReleased = false;

                    Log.d(TAG, "Wake lock acquired (first reference)");
                } catch (Exception e) {
                    Log.e(TAG, "Failed to acquire wake lock", e);
                    refCount.decrementAndGet();
                    throw e;
                }
            }

            if (requestedDeadlineElapsedMs > timeoutDeadlineElapsedMs) {
                if (count > 1) {
                    // The wake lock is not reference counted. Re-acquiring it refreshes the
                    // platform safety timeout so Android cannot release it before our newer
                    // logical deadline.
                    wakeLock.acquire(platformTimeoutMs);
                }
                timeoutDeadlineElapsedMs = requestedDeadlineElapsedMs;
                scheduleTimeout(requestedDeadlineElapsedMs);
            }
        }
    }

    public void release() {
        release(null);
    }

    public void release(String tag) {
        synchronized (lock) {
            int count = refCount.get();

            if (count <= 0) {
                Log.w(TAG, String.format("release(tag=%s) called but refCount=%d (already released)",
                        tag, count));
                return;
            }

            count = refCount.decrementAndGet();
            totalReleases++;

            if (tag != null) {
                Integer tagCount = tagCounts.get(tag);
                if (tagCount != null) {
                    if (tagCount == 1) {
                        tagCounts.remove(tag);
                    } else {
                        tagCounts.put(tag, tagCount - 1);
                    }
                }
            }

            Log.d(TAG, String.format("release(tag=%s) refCount=%d", tag, count));

            if (count == 0) {
                doRelease();
            }
        }
    }

    public void forceRelease() {
        synchronized (lock) {
            int count = refCount.get();
            if (count > 0) {
                Log.w(TAG, String.format("Force releasing wake lock (refCount=%d)", count));
                refCount.set(0);
                tagCounts.clear();
                isForceReleased = true;
                forceReleaseCount++;
                doRelease();
            }
        }
    }

    public void setEnabled(boolean enabled) {
        synchronized (lock) {
            this.isEnabled = enabled;
            if (!enabled) {
                forceRelease();
            }
        }
    }

    public boolean isHeld() {
        synchronized (lock) {
            return refCount.get() > 0;
        }
    }

    public int getRefCount() {
        return refCount.get();
    }

    private void doRelease() {
        try {
            if (timeoutFuture != null) {
                timeoutFuture.cancel(false);
                timeoutFuture = null;
            }
            timeoutDeadlineElapsedMs = 0;

            if (wakeLock.isHeld()) {
                wakeLock.release();
                long heldDurationMs = System.currentTimeMillis() - acquireTimeMs;
                Log.d(TAG, String.format("Wake lock released (held for %dms)", heldDurationMs));
            }

            acquireTimeMs = 0;

        } catch (Exception e) {
            Log.e(TAG, "Error releasing wake lock", e);
        }
    }

    private void scheduleTimeout(long deadlineElapsedMs) {
        if (timeoutFuture != null) {
            timeoutFuture.cancel(false);
        }

        long delayMs = Math.max(0, deadlineElapsedMs - SystemClock.elapsedRealtime());
        timeoutFuture = executor.schedule(() -> {
            synchronized (lock) {
                if (refCount.get() <= 0 || timeoutDeadlineElapsedMs != deadlineElapsedMs) {
                    return;
                }
                Log.w(TAG, "Wake lock timeout - force releasing");
                forceRelease();
            }
        }, delayMs, TimeUnit.MILLISECONDS);

        Log.d(TAG, String.format("Scheduled timeout in %dms", delayMs));
    }

    public void shutdown() {
        synchronized (lock) {
            isEnabled = false;
            forceRelease();
        }
    }
}
