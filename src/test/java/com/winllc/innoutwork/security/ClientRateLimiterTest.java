package com.winllc.innoutwork.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientRateLimiterTest {

    private final AtomicLong nanos = new AtomicLong();

    private ClientRateLimiter limiter(int perMinute) {
        return new ClientRateLimiter(perMinute, nanos::get);
    }

    @Test
    void aClientIsRefusedOnceOverTheLimitForTheRestOfItsMinute() {
        ClientRateLimiter limiter = limiter(2);

        assertTrue(limiter.tryAcquire("10.0.0.1"));
        assertTrue(limiter.tryAcquire("10.0.0.1"));
        assertFalse(limiter.tryAcquire("10.0.0.1"));

        nanos.addAndGet(Duration.ofSeconds(59).toNanos());
        assertFalse(limiter.tryAcquire("10.0.0.1"), "still the same minute");

        nanos.addAndGet(Duration.ofSeconds(1).toNanos());
        assertTrue(limiter.tryAcquire("10.0.0.1"), "a new minute");
    }

    @Test
    void eachAddressHasItsOwnCount() {
        ClientRateLimiter limiter = limiter(1);

        assertTrue(limiter.tryAcquire("10.0.0.1"));
        assertFalse(limiter.tryAcquire("10.0.0.1"));
        assertTrue(limiter.tryAcquire("10.0.0.2"));
    }

    @Test
    void zeroTurnsTheLimitOff() {
        ClientRateLimiter limiter = limiter(0);

        for (int i = 0; i < 1000; i++) {
            assertTrue(limiter.tryAcquire("10.0.0.1"));
        }
    }
}
