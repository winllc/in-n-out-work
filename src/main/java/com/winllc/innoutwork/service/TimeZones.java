package com.winllc.innoutwork.service;

import com.winllc.innoutwork.model.UserRecord;
import org.springframework.stereotype.Service;

import java.time.ZoneId;

/** The time zone a person's times are shown in. */
@Service
public class TimeZones {

    /** The application's zone (application.timezone), which the JVM's default is set to at startup. */
    public ZoneId applicationZone() {
        return ZoneId.systemDefault();
    }

    /** {@code person}'s zone; the application's for nobody. */
    public ZoneId zoneFor(UserRecord person) {
        return applicationZone();
    }
}
