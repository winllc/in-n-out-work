package com.winllc.innoutwork.data.metrics;

import java.time.ZonedDateTime;

/** The latest event for a lower-cased DN; built directly by a repository query. */
public record LastSeen(String dn, ZonedDateTime lastSeen) {
}
