package com.graalmail.p2p;

public interface BackgroundExecution {
    void scheduleNetworkSync();
    void requestImmediateSync();
    boolean isBackgroundExecutionAvailable();
}