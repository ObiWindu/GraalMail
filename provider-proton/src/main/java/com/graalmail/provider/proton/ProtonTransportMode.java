/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.proton;

import java.util.Locale;

/**
 * How GraalMail talks to Proton Mail.
 */
public enum ProtonTransportMode {

    /** Direct Proton API access with the Proton username and password. */
    API,
    /** Local Proton Mail Bridge (legacy mode, desktop only). */
    BRIDGE;

    /** Setting key for the transport mode. */
    public static final String SETTING = "proton.transport";

    /**
     * Reads the mode from settings, defaulting to direct API access.
     *
     * @param value the stored setting, may be {@code null}
     * @return the mode, {@link #API} unless Bridge was explicitly chosen
     */
    public static ProtonTransportMode fromSetting(String value) {
        if (value != null && value.trim().equalsIgnoreCase(BRIDGE.name())) {
            return BRIDGE;
        }
        return API;
    }

    public String settingValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
