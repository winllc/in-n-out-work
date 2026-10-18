package com.winllc.innoutwork.constant;

/**
 * Who may put a status on a person's calendar, and how. See {@code service.StatusPolicy}, which classes each
 * {@link UserStatusEnum}.
 */
public enum StatusClass {

    /** The person sets it on their own calendar, and it takes effect at once: it excuses nothing. */
    USER_SELECTABLE,

    /**
     * The person asks for it; it takes effect, and excuses an absence, only once their manager, one of their
     * delegates or an administrator approves it ({@code application.events.request-statuses}).
     */
    REQUEST,

    /** Only an administrator, or the person's manager or delegate, records it, for someone else. */
    ADMIN_ONLY
}
