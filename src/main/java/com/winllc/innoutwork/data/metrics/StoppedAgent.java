package com.winllc.innoutwork.data.metrics;

import java.time.LocalDate;

/** A user whose agent has gone quiet, and the last day it reported. */
public record StoppedAgent(String dn, String name, LocalDate lastSeen) {
}
