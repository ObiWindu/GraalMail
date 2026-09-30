package com.graalmail.auth

import android.content.Context
import com.graalmail.model.Provider
import com.graalmail.security.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class OAuthTokenManager(context: Context) {
    private val store = TokenStore(context)
    private val http = OkHttpClient()

    suspend fun accessToken(accountId: Long, config: OAuthConfig): String =
        withContext(Dispatchers.IO) {
            val current = store.read(accountId)
                ?: error("No token for account $accountId")
            if (!current.isExpired()) return@withContext current.accessToken

            val refresh = current.refreshToken ?: error("Refresh token missing")
            val body = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refresh)
                .add("client_id", config.clientId)
                .apply { config.clientSecret?.let { add("client_secret", it) } }
                .build()
            val request = Request.Builder()
                .url(config.tokenEndpoint)
                .post(body)
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("OAuth refresh failed: HTTP ${response.code}")
                val json = JSONObject(response.body!!.string())
                val token = OAuthToken(
                    accessToken = json.getString("access_token"),
                    refreshToken = json.optString("refresh_token").ifBlank { refresh },
                    expiresAtEpochMs = System.currentTimeMillis() +
                        json.optLong("expires_in", 3600) * 1000
                )
                store.save(accountId, token)
                token.accessToken
            }
        }
}
