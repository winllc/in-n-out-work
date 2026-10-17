package com.winllc.innoutwork.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static com.winllc.innoutwork.security.ClientRateLimiter.Outcome.FIRST_OVER;
import static com.winllc.innoutwork.security.ClientRateLimiter.Outcome.OVER;
import static com.winllc.innoutwork.security.ClientRateLimiter.Outcome.WITHIN;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientRateLimiterTest {

    private final AtomicLong nanos = new AtomicLong();

    private ClientRateLimiter limiter(int perMinute) {
        return new ClientRateLimiter(perMinute, nanos::get);
    }

    @Test
    void aClientIsOverTheLimitForTheRestOfItsMinute() {
        ClientRateLimiter limiter = limiter(2);

        assertEquals(WITHIN, limiter.count("10.0.0.1"));
        assertEquals(WITHIN, limiter.count("10.0.0.1"));
        assertEquals(FIRST_OVER, limiter.count("10.0.0.1"), "the one that goes over is told apart");
        assertEquals(OVER, limiter.count("10.0.0.1"));

        nanos.addAndGet(Duration.ofSeconds(59).toNanos());
        assertEquals(OVER, limiter.count("10.0.0.1"), "still the same minute");

        nanos.addAndGet(Duration.ofSeconds(1).toNanos());
        assertEquals(WITHIN, limiter.count("10.0.0.1"), "a new minute");
    }

    @Test
    void eachAddressHasItsOwnCount() {
        ClientRateLimiter limiter = limiter(1);

        assertEquals(WITHIN, limiter.count("10.0.0.1"));
        assertEquals(FIRST_OVER, limiter.count("10.0.0.1"));
        assertEquals(WITHIN, limiter.count("10.0.0.2"));
    }

    @Test
    void zeroTurnsTheLimitOff() {
        ClientRateLimiter limiter = limiter(0);

        for (int i = 0; i < 1000; i++) {
            assertEquals(WITHIN, limiter.count("10.0.0.1"));
        }
    }
}
