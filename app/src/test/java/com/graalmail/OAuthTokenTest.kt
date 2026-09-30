package com.graalmail

import com.graalmail.auth.OAuthToken
import org.junit.Assert.*
import org.junit.Test

class OAuthTokenTest {
    @Test fun expiryUsesSkew() {
        val token = OAuthToken("a", "r", System.currentTimeMillis() + 10)
        assertTrue(token.isExpired())
    }
}
