/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.api;

/** Outcome of a connection test. */
public final class ConnectionTestResult {

    private final boolean successful;
    private final String message;
    private final String errorCode;

    private ConnectionTestResult(boolean successful, String message, String errorCode) {
        this.successful = successful;
        this.message = message == null ? "" : message;
        this.errorCode = errorCode == null ? "" : errorCode;
    }

    public static ConnectionTestResult success(String message) {
        return new ConnectionTestResult(true, message, "");
    }

    public static ConnectionTestResult failure(String message, String errorCode) {
        return new ConnectionTestResult(false, message, errorCode);
    }

    public boolean successful() { return successful; }
    public String message() { return message; }
    public String errorCode() { return errorCode; }
}
