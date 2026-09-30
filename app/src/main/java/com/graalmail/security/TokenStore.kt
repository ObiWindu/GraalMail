package com.graalmail.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class TokenStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context, "oauth_tokens",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    fun save(id: Long, access: String, refresh: String? = null) {
        prefs.edit().putString("access_$id", access).apply {
            if (refresh != null) putString("refresh_$id", refresh)
        }.apply()
    }
    fun access(id: Long) = prefs.getString("access_$id", null)
    fun refresh(id: Long) = prefs.getString("refresh_$id", null)
}
