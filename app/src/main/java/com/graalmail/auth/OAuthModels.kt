package com.graalmail.auth

import com.graalmail.model.Provider

data class OAuthConfig(
    val provider: Provider,
    val clientId: String,
    val clientSecret: String? = null,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val redirectUri: String,
    val scopes: List<String>
)

data class OAuthToken(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtEpochMs: Long,
    val scope: String? = null
) {
    fun isExpired(skewMs: Long = 60_000) =
        System.currentTimeMillis() + skewMs >= expiresAtEpochMs
}
