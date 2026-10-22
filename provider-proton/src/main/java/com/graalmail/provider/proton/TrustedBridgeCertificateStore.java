/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.proton;

import com.graalmail.security.store.CredentialStore;

/**
 * Persists the Proton Mail Bridge certificate fingerprint that the user has
 * confirmed. The fingerprint is stored through a {@link CredentialStore}, so it
 * can be kept in the encrypted file store of the security module.
 */
public final class TrustedBridgeCertificateStore implements AutoCloseable {

    /** Storage key used for the pinned fingerprint. */
    public static final String FINGERPRINT_KEY = "bridge.certificate.fingerprint";

    private final CredentialStore store;

    public TrustedBridgeCertificateStore(CredentialStore store) {
        this.store = store;
    }

    /** Stores the fingerprint the user confirmed for the given account. */
    public void pin(String accountId, String fingerprint) {
        String canonical = ProtonBridgeTrust.normalize(fingerprint);
        if (canonical.isEmpty()) {
            throw new IllegalArgumentException("A fingerprint is required");
        }
        store.write(accountId, FINGERPRINT_KEY, canonical);
    }

    /** Returns the confirmed fingerprint or {@code null} when nothing is pinned. */
    public String pinnedFingerprint(String accountId) {
        String value = store.read(accountId, FINGERPRINT_KEY);
        return value == null || value.isBlank() ? null : value;
    }

    /** True when a fingerprint has been confirmed for the account. */
    public boolean isPinned(String accountId) {
        return pinnedFingerprint(accountId) != null;
    }

    /** Removes the confirmed fingerprint for the account. */
    public void clear(String accountId) {
        store.delete(accountId);
    }

    /**
     * Verifies an observed fingerprint against the stored pin.
     *
     * @return the decision; {@link ProtonTrustDecision.Verdict#NOT_PINNED} when no pin exists
     */
    public ProtonTrustDecision verify(String accountId, String observedFingerprint) {
        return ProtonBridgeTrust.verify(pinnedFingerprint(accountId), observedFingerprint);
    }

    @Override
    public void close() {
        store.close();
    }
}
