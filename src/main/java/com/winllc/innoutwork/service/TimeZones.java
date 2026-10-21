package com.winllc.innoutwork.service;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.model.UserRecord;
import org.springframework.stereotype.Service;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The time zone a person's times are shown in, in reports, exports and the public API (RR-19): the zone they chose
 * on their profile, else their site's (application.reports.site-time-zones, by the directory's location), else the
 * application's (application.timezone). The rest of the UI, and the absence check, still use the application's.
 */
@Service
public class TimeZones {

    private final Map<String, ZoneId> sites;

    public TimeZones(ApplicationProperties properties) {
        this.sites = properties == null ? Map.of() : properties.getReports().siteZones();
    }

    /** The application's zone (application.timezone), which the JVM's default is set to at startup. */
    public ZoneId applicationZone() {
        return ZoneId.systemDefault();
    }

    /** {@code person}'s zone; the application's for nobody. */
    public ZoneId zoneFor(UserRecord person) {
        if (person == null) {
            return applicationZone();
        }
        ZoneId own = parse(person.getTimeZone());
        if (own != null) {
            return own;
        }
        return siteZone(person.getLocation());
    }

    /** A site's zone, by location; the application's for a location with none. */
    public ZoneId siteZone(String location) {
        if (location != null) {
            ZoneId site = sites.get(location.trim().toLowerCase(Locale.ROOT));
            if (site != null) {
                return site;
            }
        }
        return applicationZone();
    }

    /** A zone id as stored, or null for none or one this JVM does not know. */
    public static ZoneId parse(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            return ZoneId.of(id.trim());
        } catch (DateTimeException e) {
            return null;
        }
    }

    /** The zones a person may choose from: region-based IANA ids, sorted. */
    public static List<String> choices() {
        Set<String> ids = new TreeSet<>();
        for (String id : ZoneId.getAvailableZoneIds()) {
            if (id.contains("/") && !id.startsWith("Etc/") && !id.startsWith("SystemV/")) {
                ids.add(id);
            }
        }
        ids.add("UTC");
        return List.copyOf(ids);
    }
}
