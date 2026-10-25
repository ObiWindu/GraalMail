/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.proton;

/**
 * Outcome of comparing the certificate presented by Proton Mail Bridge with the
 * fingerprint the user confirmed.
 */
public final class ProtonTrustDecision {

    /** The verification result. */
    public enum Verdict {
        /** The observed certificate matches the confirmed pin. */
        TRUSTED,
        /** No fingerprint has been confirmed yet. */
        NOT_PINNED,
        /** The observed certificate differs from the confirmed pin. */
        MISMATCH
    }

    /** Reminder shown whenever a pin is missing or has changed. */
    public static final String CHANGE_EXPLANATION =
            "If the bridge certificate changed you must confirm the new fingerprint; "
                    + "GraalMail will not silently accept it.";

    private final Verdict verdict;
    private final String message;
    private final String pinnedFingerprint;
    private final String observedFingerprint;

    ProtonTrustDecision(Verdict verdict, String message, String pinnedFingerprint,
                        String observedFingerprint) {
        this.verdict = verdict;
        this.message = message;
        this.pinnedFingerprint = pinnedFingerprint == null ? "" : pinnedFingerprint;
        this.observedFingerprint = observedFingerprint == null ? "" : observedFingerprint;
    }

    public Verdict verdict() { return verdict; }
    public String message() { return message; }
    public String pinnedFingerprint() { return pinnedFingerprint; }
    public String observedFingerprint() { return observedFingerprint; }

    public boolean trusted() {
        return verdict == Verdict.TRUSTED;
    }

    public boolean requiresConfirmation() {
        return verdict != Verdict.TRUSTED;
    }

    /** Always returns the explanation about changed certificates. */
    public String explanation() {
        return CHANGE_EXPLANATION;
    }

    @Override
    public String toString() {
        return "ProtonTrustDecision{" + verdict + ", message=" + message + '}';
    }
}
