package com.winllc.innoutwork.data.metrics;

import java.util.List;

/**
 * Whether workstation agents are still reporting, across every user the application knows.
 *
 * @param users           users with a record in the application
 * @param reporting       users whose agent sent an event within the last {@code reportingDays} days
 * @param stopped         users whose agent reported before, but not within that window
 * @param neverReported   users with no events in the last {@link #LOOKBACK_DAYS} days
 * @param reportingDays   the window, in days, ending on the day measured
 * @param stoppedUsers    the most recently stopped agents first, capped for display; empty for organisation-wide figures
 */
public record AgentCoverage(int users, int reporting, int stopped, int neverReported, int reportingDays,
                            List<StoppedAgent> stoppedUsers) {

    /** How far back a user's last event is looked for; older activity counts as none. */
    public static final int LOOKBACK_DAYS = 90;

    /** Whole percent of users whose agent is reporting; null with no users. */
    public Integer reportingPercent() {
        return users == 0 ? null : (int) Math.round(reporting * 100.0 / users);
    }
}
