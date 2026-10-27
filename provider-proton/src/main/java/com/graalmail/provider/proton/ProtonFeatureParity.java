/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.proton;

import java.util.List;

/**
 * Enumerates what the direct Proton API integration supports and what it
 * does not, and how the legacy Bridge integration differs.
 */
public final class ProtonFeatureParity {

    private ProtonFeatureParity() {
    }

    /** Features that are not available through the direct API integration. */
    public static List<String> unavailableFeatures() {
        return List.of(
                "Sending mail and saving drafts with a Proton account are not supported yet.",
                "Proton contacts are not exposed.",
                "Proton specific settings such as sieve filters, forwarding rules and custom "
                        + "domain settings are not applicable to this client.",
                "Server-side full-text search cannot match PGP-encrypted bodies; search "
                        + "combines server metadata matching with local filtering of decrypted "
                        + "subjects and senders.",
                "Accounts with two-password mode need the mailbox password in addition to "
                        + "the login password.");
    }

    /** Features that are available through the direct API integration. */
    public static List<String> availableFeatures() {
        return List.of(
                "Direct sign in with the Proton username and password; no Bridge is needed.",
                "Reading mail with subjects and bodies decrypted locally with the mailbox password.",
                "Proton labels as folders, with unread counts.",
                "Message flags such as read, starred and spam.",
                "Moving messages between folders, trash and spam handling.",
                "Attachments embedded in messages.");
    }

    /** Features that are not available through the Bridge integration. */
    public static List<String> bridgeUnavailableFeatures() {
        return List.of(
                "Proton labels are not exposed. Bridge only offers plain IMAP folders, so label "
                        + "semantics, nested label hierarchies and per label colours are missing.",
                "Encrypted search is impossible. Bridge decrypts messages before they reach this "
                        + "client, therefore the search happens in the clear.",
                "Proton contacts are not exposed. There is no contact store behind the Bridge "
                        + "IMAP interface.",
                "Proton end to end encryption is not provided. Bridge decrypts the mailbox locally "
                        + "and GraalMail sees plaintext content.",
                "The free Proton plan limits Bridge usage. Bridge access is subject to the plan "
                        + "limits of the signed in account, so folders, message volume and "
                        + "throughput can be restricted.");
    }

    /** Features that are available through the Bridge integration. */
    public static List<String> bridgeAvailableFeatures() {
        return List.of(
                "Reading and searching mail through the Bridge IMAP endpoint.",
                "Message flags such as read, starred and spam.",
                "Attachments through the Bridge IMAP endpoint.",
                "Drafts, because the Bridge exposes a Drafts folder.",
                "Sending through the Bridge SMTP endpoint.");
    }
}
