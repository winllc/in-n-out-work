package com.winllc.innoutwork.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * At most so many of something a minute from one client address, counted in memory. A client's minute starts with
 * the first one counted; the count is dropped when the minute is over. Per instance: with several replicas each one
 * allows the limit.
 */
public class ClientRateLimiter {

    public static final Duration WINDOW = Duration.ofMinutes(1);
    /** Clients tracked at once; beyond this the oldest windows are dropped early, which only ever allows more. */
    static final int MAX_CLIENTS = 100_000;

    /** Where a client stands after one more is counted. */
    public enum Outcome {
        /** Within the limit. */
        WITHIN,
        /** The first over the limit in this client's minute. */
        FIRST_OVER,
        /** Over the limit again, later in the same minute. */
        OVER
    }

    private final int perMinute;
    private final Cache<String, AtomicInteger> windows;

    /** @param perMinute how many a client may have counted per minute; 0 or less turns the limit off */
    public ClientRateLimiter(int perMinute) {
        this(perMinute, Ticker.systemTicker());
    }

    ClientRateLimiter(int perMinute, Ticker ticker) {
        this.perMinute = perMinute;
        // Written once, when a client's minute starts, and only counted up after that: expireAfterWrite ends the
        // window a minute after the first one counted whatever happens in between.
        this.windows = Caffeine.newBuilder()
                .expireAfterWrite(WINDOW)
                .maximumSize(MAX_CLIENTS)
                .ticker(ticker)
                .build();
    }

    public int perMinute() {
        return perMinute;
    }

    /** Counts one more for {@code client} and says whether that keeps it within the limit. */
    public Outcome count(String client) {
        if (perMinute <= 0) {
            return Outcome.WITHIN;
        }
        int count = windows.get(client == null ? "" : client, c -> new AtomicInteger()).incrementAndGet();
        if (count <= perMinute) {
            return Outcome.WITHIN;
        }
        return count == perMinute + 1 ? Outcome.FIRST_OVER : Outcome.OVER;
    }
}
