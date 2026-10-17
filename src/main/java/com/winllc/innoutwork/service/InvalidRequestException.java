package com.winllc.innoutwork.service;

import org.springframework.http.HttpStatus;

/**
 * A request ReadyRoom refuses for what it asks, not for who asks (that is an AccessDeniedException): a date range
 * that is too long or backwards, a status that cannot be set this way, a request that is no longer pending. The
 * JSON APIs that throw it answer with {@link #status()} and {@code {"error": error(), "message": getMessage()}}.
 */
public class InvalidRequestException extends RuntimeException {

    private final HttpStatus status;
    private final String error;

    public InvalidRequestException(HttpStatus status, String error, String message) {
        super(message);
        this.status = status;
        this.error = error;
    }

    public static InvalidRequestException badRequest(String error, String message) {
        return new InvalidRequestException(HttpStatus.BAD_REQUEST, error, message);
    }

    public static InvalidRequestException conflict(String error, String message) {
        return new InvalidRequestException(HttpStatus.CONFLICT, error, message);
    }

    public HttpStatus status() {
        return status;
    }

    public String error() {
        return error;
    }
}
