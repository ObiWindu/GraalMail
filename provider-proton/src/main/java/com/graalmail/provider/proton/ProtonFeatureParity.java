/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.proton;

import java.util.List;

/**
 * Enumerates the Proton features that are not available through Proton Mail
 * Bridge. Bridge exposes an IMAP view of the mailbox, not the Proton feature
 * set.
 */
public final class ProtonFeatureParity {

    private ProtonFeatureParity() {
    }

    /** Features that are not available through the Bridge integration. */
    public static List<String> unavailableFeatures() {
        return List.of(
                "Proton labels are not exposed. Bridge only offers plain IMAP folders, so label "
                        + "semantics, nested label hierarchies and per label colours are missing.",
                "Proton search is approximated by IMAP SEARCH over decrypted message text, so "
                        + "results differ from the search inside the Proton Mail application.",
                "Encrypted search is impossible. Bridge decrypts messages before they reach this "
                        + "client, therefore the search happens in the clear.",
                "Proton contacts are not exposed. There is no contact store behind the Bridge "
                        + "IMAP interface.",
                "Proton specific settings such as the VPN, the Tor configuration, bridge rules, "
                        + "sieve filters, forwarding rules and custom domain settings are not "
                        + "applicable to this client.",
                "Proton end to end encryption is not provided. Bridge decrypts the mailbox locally "
                        + "and GraalMail sees plaintext content.",
                "The free Proton plan limits Bridge usage. Bridge access is subject to the plan "
                        + "limits of the signed in account, so folders, message volume and "
                        + "throughput can be restricted.");
    }

    /** Features that are available through the Bridge integration. */
    public static List<String> availableFeatures() {
        return List.of(
                "Reading and searching mail through the Bridge IMAP endpoint.",
                "Message flags such as read, starred and spam.",
                "Attachments through the Bridge IMAP endpoint.",
                "Drafts, because the Bridge exposes a Drafts folder.",
                "Sending through the Bridge SMTP endpoint.");
    }
}
