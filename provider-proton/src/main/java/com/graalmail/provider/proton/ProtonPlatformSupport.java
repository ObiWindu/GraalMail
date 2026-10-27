/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.proton;

import java.util.List;

/**
 * Platform availability of the Proton Mail Bridge integration.
 *
 * <p>Direct API login works on every platform. Only the legacy Bridge
 * transport is desktop-bound: Proton Mail Bridge is a desktop application
 * that does not run on Android, therefore Bridge mode is available on Linux
 * desktops only.</p>
 */
public final class ProtonPlatformSupport {

    /** System property that overrides the detected platform, mainly for tests. */
    public static final String PLATFORM_PROPERTY = "graalmail.platform";

    /** Platform key for Linux desktops. */
    public static final String LINUX = "linux";
    /** Platform key for Android. */
    public static final String ANDROID = "android";

    private ProtonPlatformSupport() {
    }

    /** The platform key of the current runtime, {@code "linux"} or {@code "android"}. */
    public static String currentPlatform() {
        String override = System.getProperty(PLATFORM_PROPERTY);
        if (override != null && !override.isBlank()) {
            return override.trim().toLowerCase(java.util.Locale.ROOT);
        }
        return detectPlatform();
    }

    /** True when the Proton integration can be used on the given platform. */
    public static boolean isAvailable(String platform) {
        return LINUX.equalsIgnoreCase(platform);
    }

    /** Availability report for the current platform. */
    public static AvailabilityReport availability() {
        return availability(currentPlatform());
    }

    /** Availability report for an explicit platform. */
    public static AvailabilityReport availability(String platform) {
        if (LINUX.equalsIgnoreCase(platform)) {
            return AvailabilityReport.linux();
        }
        if (ANDROID.equalsIgnoreCase(platform)) {
            return AvailabilityReport.android();
        }
        return AvailabilityReport.unsupported(platform);
    }

    private static String detectPlatform() {
        String os = String.valueOf(System.getProperty("os.name")).toLowerCase(java.util.Locale.ROOT);
        String runtime = String.valueOf(System.getProperty("java.runtime.name"))
                .toLowerCase(java.util.Locale.ROOT);
        String vm = String.valueOf(System.getProperty("java.vm.name"))
                .toLowerCase(java.util.Locale.ROOT);
        if (runtime.contains("android") || vm.contains("dalvik") || vm.contains("art")) {
            return ANDROID;
        }
        if (os.contains("linux")) {
            return LINUX;
        }
        return os.replaceAll("[^a-z0-9]+", "-");
    }

    /** Structured description of the availability of the Proton integration. */
    public static final class AvailabilityReport {

        private final String platform;
        private final boolean available;
        private final String headline;
        private final List<String> notes;
        private final List<String> limitations;

        private AvailabilityReport(String platform, boolean available, String headline,
                                   List<String> notes, List<String> limitations) {
            this.platform = platform;
            this.available = available;
            this.headline = headline;
            this.notes = List.copyOf(notes);
            this.limitations = List.copyOf(limitations);
        }

        static AvailabilityReport linux() {
            return new AvailabilityReport(LINUX, true,
                    "Proton Mail is available through Proton Mail Bridge on this machine.",
                    List.of(
                            "Proton Mail Bridge must be installed, started and unlocked.",
                            "A paid Proton Mail plan is required for full Bridge access.",
                            "Bridge decrypts the mailbox locally, so GraalMail receives already "
                                    + "decrypted content.",
                            "GraalMail does not provide end-to-end encryption for Proton mail."),
                    List.of(
                            "Bridge support on the free plan is limited; expect reduced throughput "
                                    + "and limited feature coverage.",
                            "Messages are visible to the local machine and to any process that can "
                                    + "read the Bridge cache."));
        }

        static AvailabilityReport android() {
            return new AvailabilityReport(ANDROID, false,
                    "Proton Mail is not available in GraalMail on Android.",
                    List.of(
                            "Proton Mail does not publish IMAP or SMTP endpoints for third party "
                                    + "mobile clients.",
                            "Proton Mail Bridge is a desktop application and does not run on Android.",
                            "No unofficial or private Proton API is used by this client."),
                    List.of(
                            "Use the official Proton Mail Android app.",
                            "Use the Proton Mail web client in a browser."));
        }

        static AvailabilityReport unsupported(String platform) {
            return new AvailabilityReport(platform, false,
                    "Proton Mail is not available on this platform.",
                    List.of("Proton Mail Bridge runs on supported desktop operating systems only."),
                    List.of("Use the official Proton Mail app or the web client."));
        }

        public String platform() { return platform; }
        public boolean available() { return available; }
        public String headline() { return headline; }
        public List<String> notes() { return notes; }
        public List<String> limitations() { return limitations; }
    }
}
