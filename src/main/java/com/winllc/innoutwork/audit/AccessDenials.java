package com.winllc.innoutwork.audit;

import org.springframework.beans.factory.annotation.Autowired;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records {@code access.denied} for every 403, collapsed: the first denial of a given actor, path and reason is
 * recorded at once; identical ones within the next 60 seconds are only counted, and recorded afterwards as one
 * event with {@code details.count}. A polling page refused every few seconds therefore costs one event a
 * minute, not hundreds.
 *
 * <p>The same collapsing serves other repetitive refusals: failed pull-API tokens and the license's size limit.
 */
@Component
public class AccessDenials {

    static final Duration WINDOW = Duration.ofSeconds(60);

    private final AuditLog audit;
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    @Autowired
    public AccessDenials(AuditLog audit) {
        this(audit, Clock.systemUTC());
    }

    AccessDenials(AuditLog audit, Clock clock) {
        this.audit = audit;
        this.clock = clock;
    }

    private static final class Window {
        final Instant started;
        final AuditEntry first;
        long suppressed;

        Window(Instant started, AuditEntry first) {
            this.started = started;
            this.first = first;
        }
    }

    /**
     * A 403. {@code reason} is the machine code: {@code permission_denied}, {@code license_required},
     * {@code license_read_only}, {@code feature_not_licensed}, {@code license_limit}, {@code demo_read_only}.
     */
    public void denied(HttpServletRequest request, String reason, String message, Map<String, Object> extra) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        AuditEntry.Actor actor = AuditRequestInfo.actor(auth);
        String path = request.getRequestURI().substring(request.getContextPath().length());
        AuditEntry entry = AuditEntry.of(AuditEntry.Category.AUTHORIZATION, "access.denied")
                .denied(reason)
                // Fixed now: a collapsed repeat is recorded later, from another thread.
                .actor(actor != null ? actor : new AuditEntry.Actor("anonymous", null, null, null))
                .source(AuditRequestInfo.source(request))
                .detail("method", request.getMethod())
                .detail("path", path)
                .detail("reason", reason);
        if (message != null) {
            entry.detail("message", message);
        }
        if (extra != null) {
            extra.forEach(entry::detail);
        }
        collapse((actor != null ? actor.id() : "-") + "|" + path + "|" + reason, entry);
    }

    /**
     * Records {@code entry} now, unless an identical one (same key) was recorded in the last 60 seconds, in which
     * case it is counted and recorded later with {@code details.count}.
     */
    public void collapse(String key, AuditEntry entry) {
        Instant now = clock.instant();
        flushExpired(now);
        boolean[] first = {false};
        windows.compute(key, (k, w) -> {
            if (w == null) {
                first[0] = true;
                return new Window(now, entry);
            }
            w.suppressed++;
            return w;
        });
        if (first[0]) {
            audit.recordQuietly(entry.withCount(1));
        }
    }

    /** Records the counted repeats of every window older than 60 seconds. */
    @Scheduled(fixedDelay = 15000, initialDelay = 15000)
    public void flush() {
        flushExpired(clock.instant());
    }

    void flushExpired(Instant now) {
        Iterator<Map.Entry<String, Window>> it = windows.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Window> e = it.next();
            Window w = e.getValue();
            if (!w.started.plus(WINDOW).isAfter(now)) {
                it.remove();
                if (w.suppressed > 0) {
                    AuditEntry repeat = w.first.withCount(w.suppressed);
                    repeat.detail("collapsed", true);
                    repeat.detail("windowStart", AuditJson.time(w.started));
                    audit.recordQuietly(repeat);
                }
            }
        }
    }
}
