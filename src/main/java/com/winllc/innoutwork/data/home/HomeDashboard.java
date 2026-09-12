package com.winllc.innoutwork.data.home;

/**
 * Everything on the home page for the signed-in user.
 *
 * @param me   the user's own day and recent attendance
 * @param team their direct reports, or null when nobody reports to them
 */
public record HomeDashboard(PersonalSummary me, TeamSummary team) {

    public boolean manager() {
        return team != null;
    }
}
