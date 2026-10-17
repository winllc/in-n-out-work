package com.winllc.innoutwork.constant;

/** A status request's life: requested, then approved, rejected or cancelled. Only APPROVED puts it on the calendar. */
public enum RequestState {
    REQUESTED,
    APPROVED,
    REJECTED,
    CANCELLED;

    public boolean isOpen() {
        return this == REQUESTED;
    }
}
