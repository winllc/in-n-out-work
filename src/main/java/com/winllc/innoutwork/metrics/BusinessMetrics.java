package com.winllc.innoutwork.metrics;

import com.enssolutions.license.LicenseCheck;
import com.enssolutions.license.LicenseManager;
import com.enssolutions.license.LicenseState;
import com.winllc.innoutwork.constant.CheckInOutEnum;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * The gauges, read when Prometheus scrapes: the license's mode and days remaining, and how many people are in
 * now. The people count is a database query, so it is kept for a minute between scrapes.
 */
@Component
public class BusinessMetrics implements MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(BusinessMetrics.class);

    static final Duration PEOPLE_IN_CACHE = Duration.ofMinutes(1);

    private final ObjectProvider<LicenseManager> licenses;
    private final CheckInOutRecordRepository checkIns;
    private final Clock clock;

    private volatile Instant peopleInAt = Instant.MIN;
    private volatile double peopleIn = Double.NaN;

    @Autowired
    public BusinessMetrics(ObjectProvider<LicenseManager> licenses, CheckInOutRecordRepository checkIns) {
        this(licenses, checkIns, Clock.systemDefaultZone());
    }

    BusinessMetrics(ObjectProvider<LicenseManager> licenses, CheckInOutRecordRepository checkIns, Clock clock) {
        this.licenses = licenses;
        this.checkIns = checkIns;
        this.clock = clock;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (LicenseState.Mode mode : LicenseState.Mode.values()) {
            // ens_license_mode{mode}: 1 for the mode in effect, 0 for the others.
            Gauge.builder("ens.license.mode", () -> currentMode() == mode ? 1 : 0)
                    .tag("mode", mode.name().toLowerCase(Locale.ROOT))
                    .description("1 for the license mode in effect")
                    .register(registry);
        }
        Gauge.builder("ens.license.days.remaining", (Supplier<Number>) this::daysRemaining)
                .description("Days until the license expires (or its grace period ends); absent for a perpetual one")
                .register(registry);
        Gauge.builder("ens.readyroom.people.in", (Supplier<Number>) this::peopleIn)
                .description("People whose latest event today is a check-in or unlock")
                .register(registry);
    }

    private LicenseState.Mode currentMode() {
        LicenseCheck check = check();
        return check == null ? null : check.mode();
    }

    private LicenseCheck check() {
        LicenseManager manager = licenses.getIfAvailable();
        if (manager == null) {
            return null;
        }
        try {
            return manager.current();
        } catch (RuntimeException e) {
            log.debug("License state unavailable for metrics: {}", e.toString());
            return null;
        }
    }

    /** NaN, which Prometheus shows as no value, for a perpetual license or none. */
    double daysRemaining() {
        LicenseCheck check = check();
        return check == null ? Double.NaN : check.daysLeft().map(Long::doubleValue).orElse(Double.NaN);
    }

    double peopleIn() {
        Instant now = clock.instant();
        if (now.isBefore(peopleInAt.plus(PEOPLE_IN_CACHE))) {
            return peopleIn;
        }
        try {
            ZoneId zone = clock.getZone();
            ZonedDateTime from = LocalDate.now(clock).atStartOfDay(zone);
            peopleIn = checkIns.countLatestByDnWithActionIn(
                    List.of(CheckInOutEnum.CHECK_IN, CheckInOutEnum.UNLOCK), from, ZonedDateTime.now(clock));
        } catch (RuntimeException e) {
            log.debug("Could not count the people in for metrics: {}", e.toString());
            peopleIn = Double.NaN;
        }
        peopleInAt = now;
        return peopleIn;
    }
}
