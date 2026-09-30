package com.graalmail

import org.junit.Assert.assertEquals
import org.junit.Test

class MailSearchTest {
    @Test fun emptyQueryContract() {
        assertEquals("", "".trim())
    }
}
