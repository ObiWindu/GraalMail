/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.proton;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProtonAndroidIntegrationTest {

    @Test
    void statusReportsAvailabilityWithAlternatives() {
        ProtonAndroidIntegration.AndroidStatus status = ProtonAndroidIntegration.status();
        assertTrue(status.supported());
        assertFalse(status.reason().isBlank());
        assertFalse(status.officialAlternatives().isEmpty());
        assertEquals(ProtonAndroidIntegration.WEB_URL, status.webUrl());
        assertEquals(ProtonAndroidIntegration.WEB_URL, status.appLink());
    }

    @Test
    void officialLinksAreWellFormed() {
        assertTrue(ProtonAndroidIntegration.webUrl().startsWith("https://"));
        assertTrue(ProtonAndroidIntegration.appLink().startsWith("https://"));
        assertTrue(ProtonAndroidIntegration.PLAY_STORE_URL
                .endsWith("id=" + ProtonAndroidIntegration.APP_PACKAGE));
        assertFalse(ProtonAndroidIntegration.APP_PACKAGE.isBlank());
    }
}
