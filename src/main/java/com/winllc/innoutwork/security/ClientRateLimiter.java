package com.winllc.innoutwork.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * At most so many requests a minute from one client address, counted in memory. A client's minute starts with
 * its first request; the count is dropped when the minute is over. Per instance: with several replicas each one
 * allows the limit.
 */
public class ClientRateLimiter {

    public static final Duration WINDOW = Duration.ofMinutes(1);
    /** Clients tracked at once; beyond this the oldest windows are dropped early, which only ever allows more. */
    static final int MAX_CLIENTS = 100_000;

    private final int perMinute;
    private final Cache<String, AtomicInteger> windows;

    /** @param perMinute requests allowed per client per minute; 0 or less turns the limit off */
    public ClientRateLimiter(int perMinute) {
        this(perMinute, Ticker.systemTicker());
    }

    ClientRateLimiter(int perMinute, Ticker ticker) {
        this.perMinute = perMinute;
        // Written once, when a client's minute starts, and only counted up after that: expireAfterWrite ends the
        // window a minute after the first request whatever happens in between.
        this.windows = Caffeine.newBuilder()
                .expireAfterWrite(WINDOW)
                .maximumSize(MAX_CLIENTS)
                .ticker(ticker)
                .build();
    }

    /** Counts a request from {@code client}: true while it is within the limit, false once it is over. */
    public boolean tryAcquire(String client) {
        if (perMinute <= 0) {
            return true;
        }
        return windows.get(client == null ? "" : client, c -> new AtomicInteger()).incrementAndGet() <= perMinute;
    }
}
