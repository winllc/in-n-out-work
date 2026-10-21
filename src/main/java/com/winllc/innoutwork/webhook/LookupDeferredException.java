package com.winllc.innoutwork.webhook;

/**
 * A host lookup that never started: ReadyRoom's own lookup pool was full, or the lookup waited in its queue past the
 * try's deadline. Nothing was asked of the endpoint, so the try does not count and does not pause it.
 */
public class LookupDeferredException extends WebhookRefusedException {

    public LookupDeferredException(String message) {
        super(message);
    }
}
