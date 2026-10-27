/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.proton;

import java.util.List;

/**
 * Structured status for the Proton integration on Android.
 *
 * <p>This client never asks for Proton credentials on Android, never talks to a
 * private Proton API and never claims feature parity with the official
 * application.</p>
 */
public final class ProtonAndroidIntegration {

    /** Web client of Proton Mail. */
    public static final String WEB_URL = "https://mail.proton.me";

    /** Package name of the official Proton Mail Android application. */
    public static final String APP_PACKAGE = "ch.protonmail.android";

    /** Store listing of the official Proton Mail Android application. */
    public static final String PLAY_STORE_URL =
            "https://play.google.com/store/apps/details?id=ch.protonmail.android";

    /** Alternatives that GraalMail points to instead of pretending to support Proton. */
    public static final List<String> OFFICIAL_ALTERNATIVES = List.of(
            "Open the Proton Mail Android app",
            "Open https://mail.proton.me in a browser");

    private ProtonAndroidIntegration() {
    }

    /** Returns the Android availability status. */
    public static AndroidStatus status() {
        return new AndroidStatus(false,
                "Proton Mail integration on Android is not available; use the Proton Mail app or "
                        + "web client",
                OFFICIAL_ALTERNATIVES, appLink(), WEB_URL);
    }

    /**
     * The official Proton Mail link that opens the Proton application when it is
     * installed and the web client otherwise.
     */
    public static String appLink() {
        return WEB_URL;
    }

    /** The Proton Mail web client URL. */
    public static String webUrl() {
        return WEB_URL;
    }

    /** Android availability status of the Proton integration. */
    public static final class AndroidStatus {

        private final boolean supported;
        private final String reason;
        private final List<String> officialAlternatives;
        private final String appLink;
        private final String webUrl;

        AndroidStatus(boolean supported, String reason, List<String> officialAlternatives,
                      String appLink, String webUrl) {
            this.supported = supported;
            this.reason = reason;
            this.officialAlternatives = List.copyOf(officialAlternatives);
            this.appLink = appLink;
            this.webUrl = webUrl;
        }

        public boolean supported() { return supported; }
        public String reason() { return reason; }
        public List<String> officialAlternatives() { return officialAlternatives; }
        public String appLink() { return appLink; }
        public String webUrl() { return webUrl; }
    }
}
