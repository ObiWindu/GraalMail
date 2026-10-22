/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.api;

/** Well-known provider identifiers. */
public enum ProviderType {
    GMAIL("gmail", "Gmail / Google Workspace"),
    MICROSOFT("microsoft", "Outlook / Hotmail / Microsoft 365"),
    PROTON("proton", "Proton Mail"),
    YAHOO("yahoo", "Yahoo Mail"),
    GENERIC("generic", "Other IMAP/SMTP provider");

    private final String id;
    private final String displayName;

    ProviderType(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String id() { return id; }
    public String displayName() { return displayName; }

    public static ProviderType fromId(String id) {
        for (ProviderType type : values()) {
            if (type.id.equalsIgnoreCase(id)) {
                return type;
            }
        }
        return GENERIC;
    }
}
