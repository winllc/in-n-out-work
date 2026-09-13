package com.winllc.innoutwork.data.team;

import java.util.List;

/**
 * Absence alerts about a team. One absence produces a notification for each manager told, so alerts
 * are counted once per absence, and an alert is answered once any of those managers records a status.
 *
 * @param raised   absences alerted
 * @param answered alerts someone responded to
 * @param outcomes the statuses recorded in those responses, most common first
 */
public record AbsenceAlerts(int raised, int answered, List<AlertOutcome> outcomes) {

    public int unanswered() {
        return raised - answered;
    }

    /** A recorded response and how many alerts received it. */
    public record AlertOutcome(String label, int count) {
    }
}
