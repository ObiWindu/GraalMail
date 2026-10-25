/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.microsoft;

/** Well known Microsoft mail endpoints and their default ports. */
public final class MicrosoftHosts {

    /** IMAP endpoint used by Outlook.com, Microsoft 365 and Exchange Online. */
    public static final String IMAP_HOST = "outlook.office365.com";
    /** IMAP over implicit TLS port. */
    public static final int IMAP_PORT = 993;

    /** SMTP endpoint used for message submission. */
    public static final String SMTP_HOST = "smtp.office365.com";
    /** SMTP submission port using STARTTLS. */
    public static final int SMTP_PORT_STARTTLS = 587;
    /** SMTP submission port using implicit TLS. */
    public static final int SMTP_PORT_SSL = 465;

    private MicrosoftHosts() {
    }
}
