package com.gateway.provider.compat;

import com.gateway.common.error.GatewayException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.http.HttpStatus;

/**
 * Local guard that keeps traffic under a provider's free-tier quota: a sliding one-minute request window
 * plus a cap on concurrent calls. Callers wait (cheap on virtual threads) up to {@code maxWait}, then get 429.
 */
final class ProviderThrottle {

    private static final long WINDOW_NANOS = TimeUnit.MINUTES.toNanos(1);

    private final int requestsPerMinute;
    private final Semaphore concurrency;
    private final long maxWaitNanos;
    private final Deque<Long> window = new ArrayDeque<>();
    private final ReentrantLock lock = new ReentrantLock();

    ProviderThrottle(int requestsPerMinute, int maxConcurrent, Duration maxWait) {
        this.requestsPerMinute = requestsPerMinute;
        this.concurrency = maxConcurrent > 0 ? new Semaphore(maxConcurrent, true) : null;
        this.maxWaitNanos = maxWait.toNanos();
    }

    /** Released via try-with-resources when the upstream call (or whole stream) finishes. */
    Permit acquire(String provider) {
        long deadline = System.nanoTime() + maxWaitNanos;
        try {
            if (concurrency != null && !concurrency.tryAcquire(maxWaitNanos, TimeUnit.NANOSECONDS)) {
                throw saturated(provider, Duration.ofSeconds(1));
            }
            try {
                waitForWindowSlot(provider, deadline);
            } catch (RuntimeException ex) {
                release();
                throw ex;
            }
            return this::release;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new GatewayException(HttpStatus.SERVICE_UNAVAILABLE, "server_error",
                    "request_interrupted", "The request was interrupted");
        }
    }

    private void waitForWindowSlot(String provider, long deadline) throws InterruptedException {
        if (requestsPerMinute <= 0) {
            return;
        }
        while (true) {
            long waitNanos;
            lock.lock();
            try {
                long now = System.nanoTime();
                while (!window.isEmpty() && now - window.peekFirst() >= WINDOW_NANOS) {
                    window.pollFirst();
                }
                if (window.size() < requestsPerMinute) {
                    window.addLast(now);
                    return;
                }
                waitNanos = WINDOW_NANOS - (now - window.peekFirst());
            } finally {
                lock.unlock();
            }
            if (System.nanoTime() + waitNanos > deadline) {
                throw saturated(provider, Duration.ofNanos(waitNanos));
            }
            TimeUnit.NANOSECONDS.sleep(waitNanos);
        }
    }

    private void release() {
        if (concurrency != null) {
            concurrency.release();
        }
    }

    private static GatewayException saturated(String provider, Duration retryAfter) {
        return new GatewayException(HttpStatus.TOO_MANY_REQUESTS, "rate_limit_error", "provider_quota_protected",
                "The gateway is protecting the " + provider + " free-tier quota; retry shortly", false, retryAfter);
    }

    @FunctionalInterface
    interface Permit extends AutoCloseable {
        @Override
        void close();
    }
}
