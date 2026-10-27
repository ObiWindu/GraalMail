/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.android.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AvatarColorsTest {

    @Test
    void initialsUseNameParts() {
        assertEquals("OW", AvatarColors.initials("Obi Windu", "obi@proton.me"));
        assertEquals("O", AvatarColors.initials("Obi", "").substring(0, 1));
    }

    @Test
    void initialsFallBackToEmail() {
        assertEquals("OW", AvatarColors.initials("", "obi.wandu@proton.me"));
        assertEquals("?", AvatarColors.initials("", ""));
        assertEquals("?", AvatarColors.initials(null, null));
    }

    @Test
    void colorIsStableAndInPalette() {
        int first = AvatarColors.colorFor("Obi Windu", "obi@proton.me");
        int second = AvatarColors.colorFor("Obi Windu", "obi@proton.me");
        assertEquals(first, second);
        boolean known = false;
        for (int color : AvatarColors.PALETTE) {
            if (color == first) {
                known = true;
                break;
            }
        }
        assertTrue(known);
        assertNotEquals(first, AvatarColors.colorFor("Someone Else", "someone@example.com"));
    }
}
