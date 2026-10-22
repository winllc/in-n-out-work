package com.winllc.innoutwork.api;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What a public API token may read. A token holds one or more; each endpoint needs one. Whatever its scopes say, a
 * token never sees more than the administrator who created it may see now (AccessPolicy, re-checked on every call).
 */
public enum ApiScope {

    /** {@code /api/v1/people}: tracked people and their status today, as the token's owner may see them. */
    PEOPLE_READ("people:read", "People and their current status"),
    /** {@code /api/v1/attendance/**}: check-in, lock, unlock and check-out events, and daily attendance. */
    ATTENDANCE_READ("attendance:read", "Attendance records by date range"),
    /** {@code /api/v1/absences/**}: statuses on the calendar (leave, telework, absences) and leave requests. */
    ABSENCES_READ("absences:read", "Absences, leave and leave requests");

    /** The authority every request signed in with a public API token holds, whatever its scopes. */
    public static final String API_TOKEN_AUTHORITY = "API_TOKEN";

    private final String id;
    private final String description;

    ApiScope(String id, String description) {
        this.id = id;
        this.description = description;
    }

    public String id() {
        return id;
    }

    public String getId() {
        return id;
    }

    public String getDescription() {
        return description;
    }

    /** The authority a token with this scope holds: {@code SCOPE_people:read}. */
    public String authority() {
        return "SCOPE_" + id;
    }

    public static Optional<ApiScope> of(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String wanted = id.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(s -> s.id.equals(wanted)).findFirst();
    }

    /** Scopes as stored: their ids, space separated, in declaration order. */
    public static String join(Collection<ApiScope> scopes) {
        return EnumSet.copyOf(scopes).stream().map(ApiScope::id).collect(Collectors.joining(" "));
    }

    /** Stored scopes back; unknown ids (from a later release) are ignored. */
    public static Set<ApiScope> parse(String stored) {
        Set<ApiScope> scopes = new LinkedHashSet<>();
        if (stored != null) {
            for (String part : stored.trim().split("\\s+")) {
                of(part).ifPresent(scopes::add);
            }
        }
        return scopes;
    }
}
