package com.winllc.innoutwork.webhook;

/** A webhook address that may not be used, or a delivery that may not be sent there; the message says why. */
public class WebhookRefusedException extends Exception {

    public WebhookRefusedException(String message) {
        super(message);
    }
}
