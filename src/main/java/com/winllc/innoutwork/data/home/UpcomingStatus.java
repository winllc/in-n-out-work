package com.winllc.innoutwork.data.home;

import java.time.LocalDate;

/** A status someone has entered for a coming day. */
public record UpcomingStatus(LocalDate date, String dn, String name, String label) {
}
