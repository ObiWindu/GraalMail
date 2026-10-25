/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.android.ui;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;

/** Locale and time-zone aware date formatting shared by mail UI rows. */
final class UiDateFormatter {
    private UiDateFormatter() { }

    static String shortDateTime(Instant instant) {
        if (instant == null) return "";
        return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
                .withLocale(Locale.getDefault()).withZone(ZoneId.systemDefault()).format(instant);
    }
}
