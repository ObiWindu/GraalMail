/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.proton;

/** Lifecycle state of the local Proton Mail Bridge. */
public enum ProtonBridgeStatus {

    /** The bridge is reachable and its certificate matches the confirmed fingerprint. */
    RUNNING,

    /** The bridge is reachable but its certificate is not the confirmed one. */
    RUNNING_WRONG_CERT,

    /** Nothing is listening on the configured bridge port. */
    NOT_RUNNING,

    /** The state could not be determined. */
    UNKNOWN
}
