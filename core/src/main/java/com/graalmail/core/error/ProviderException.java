/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.core.error;

/** A failure that can be surfaced to the user with an actionable message. */
public class ProviderException extends RuntimeException {
    private final ErrorCode code;
    private final boolean retryable;
    private final String userMessage;

    public ProviderException(ErrorCode code, String userMessage, boolean retryable, Throwable cause) {
        super(userMessage, cause);
        this.code = code;
        this.userMessage = userMessage;
        this.retryable = retryable;
    }

    public ProviderException(ErrorCode code, String userMessage, boolean retryable) {
        this(code, userMessage, retryable, null);
    }

    public ErrorCode code() { return code; }
    public boolean retryable() { return retryable; }
    public String userMessage() { return userMessage; }

    public static ProviderException authenticationFailed(String message) {
        return new ProviderException(ErrorCode.AUTHENTICATION_FAILED, message, false);
    }

    public static ProviderException authExpired(String message) {
        return new ProviderException(ErrorCode.AUTH_EXPIRED, message, false);
    }

    public static ProviderException network(String message, Throwable cause) {
        return new ProviderException(ErrorCode.NETWORK_UNAVAILABLE, message, true, cause);
    }

    public static ProviderException notImplemented(String message) {
        return new ProviderException(ErrorCode.NOT_IMPLEMENTED, message, false);
    }

    public static ProviderException configuration(String message) {
        return new ProviderException(ErrorCode.CONFIGURATION_ERROR, message, false);
    }
}
