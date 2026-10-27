/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.api;

import com.graalmail.provider.api.ProviderType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProviderDeductionTest {

    @Test
    void wellKnownDomains() {
        assertEquals(ProviderType.GMAIL, ProviderDeduction.deduceProvider("me@gmail.com"));
        assertEquals(ProviderType.GMAIL, ProviderDeduction.deduceProvider("me@googlemail.com"));
        assertEquals(ProviderType.MICROSOFT, ProviderDeduction.deduceProvider("me@outlook.com"));
        assertEquals(ProviderType.MICROSOFT, ProviderDeduction.deduceProvider("me@hotmail.co.uk"));
        assertEquals(ProviderType.MICROSOFT, ProviderDeduction.deduceProvider("me@live.fr"));
        assertEquals(ProviderType.MICROSOFT, ProviderDeduction.deduceProvider("me@msn.com"));
        assertEquals(ProviderType.YAHOO, ProviderDeduction.deduceProvider("me@yahoo.com"));
        assertEquals(ProviderType.YAHOO, ProviderDeduction.deduceProvider("me@yahoo.co.jp"));
        assertEquals(ProviderType.YAHOO, ProviderDeduction.deduceProvider("me@ymail.com"));
        assertEquals(ProviderType.PROTON, ProviderDeduction.deduceProvider("me@proton.me"));
        assertEquals(ProviderType.PROTON, ProviderDeduction.deduceProvider("me@protonmail.com"));
        assertEquals(ProviderType.PROTON, ProviderDeduction.deduceProvider("me@pm.me"));
    }

    @Test
    void unknownAndCustomDomainsFallBackToGeneric() {
        assertEquals(ProviderType.GENERIC, ProviderDeduction.deduceProvider("me@example.com"));
        assertEquals(ProviderType.GENERIC, ProviderDeduction.deduceProvider("me@mycompany.ch"));
        assertEquals(ProviderType.GENERIC, ProviderDeduction.deduceProvider("me@icloud.com"));
        assertEquals(ProviderType.GENERIC, ProviderDeduction.deduceProvider(""));
        assertEquals(ProviderType.GENERIC, ProviderDeduction.deduceProvider("   "));
        assertEquals(ProviderType.GENERIC, ProviderDeduction.deduceProvider(null));
        assertEquals(ProviderType.GENERIC, ProviderDeduction.deduceProvider("not-an-email"));
        assertEquals(ProviderType.GENERIC, ProviderDeduction.deduceProvider("me@"));
    }

    @Test
    void matchingIsCaseInsensitiveAndTrims() {
        assertEquals(ProviderType.GMAIL, ProviderDeduction.deduceProvider("  ME@GMail.COM  "));
        assertEquals(ProviderType.PROTON, ProviderDeduction.deduceProvider("ME@PM.ME"));
    }

    @Test
    void lookalikeDomainsDoNotMatch() {
        assertEquals(ProviderType.GENERIC, ProviderDeduction.deduceProvider("me@notgmail.com"));
        assertEquals(ProviderType.GENERIC, ProviderDeduction.deduceProvider("me@gmail.com.evil.ch"));
        assertEquals(ProviderType.GENERIC, ProviderDeduction.deduceProvider("me@proton.me.evil.ch"));
    }
}
