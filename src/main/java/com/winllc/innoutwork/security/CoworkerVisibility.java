package com.winllc.innoutwork.security;

/**
 * What a coworker sees of someone whose details they may not see ({@code application.privacy.coworker-visibility};
 * AccessPolicy decides who is a coworker). Never their times, notes, average login time or history: those are
 * details, for themselves, the people responsible for them and administrators.
 */
public enum CoworkerVisibility {

    /**
     * Who they are (directory details) and the day's status as it stands: in, away, out, or a calendar status such as
     * telework or leave (a status a manager recorded reads as out of office). The default, and the behaviour before
     * the setting existed.
     */
    FULL,

    /** Who they are, and only whether they are in or out today ("IN" or "OUT"): no calendar status, no away. */
    STATUS,

    /**
     * Who they are, and nothing of their attendance: no status, and no present counts on the org chart for viewers
     * without the ADMIN or MANAGER role.
     */
    NONE
}
