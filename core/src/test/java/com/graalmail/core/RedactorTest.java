/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.graalmail.core.util.Redactor;
import org.junit.jupiter.api.Test;

class RedactorTest {

    @Test
    void removesPasswords() {
        String redacted = Redactor.redact("login password=hunter2 done");
        assertFalse(redacted.contains("hunter2"));
        assertTrue(redacted.contains("[redacted]"));
    }

    @Test
    void removesBearerTokens() {
        String redacted = Redactor.redact("Authorization: Bearer abcdef123456");
        assertFalse(redacted.contains("abcdef123456"));
    }

    @Test
    void removesJwtLikeTokens() {
        String redacted = Redactor.redact("token eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N");
        assertFalse(redacted.contains("dozjgNryP4J3jVmNHl0w5N"));
    }
}
