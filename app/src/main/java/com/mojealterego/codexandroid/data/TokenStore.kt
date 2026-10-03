package com.mojealterego.codexandroid.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class TokenStore(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "credentials",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun githubToken(): String? = prefs.getString("github_token", null)

    fun saveGithubToken(value: String) =
        prefs.edit().putString("github_token", value.trim()).apply()

    fun agentBackendUrl(): String? =
        prefs.getString("agent_backend_url", null)

    fun saveAgentBackendUrl(value: String) =
        prefs.edit().putString("agent_backend_url", value.trim()).apply()

    fun agentBackendToken(): String? =
        prefs.getString("agent_backend_token", null)

    fun saveAgentBackendToken(value: String) =
        prefs.edit().putString("agent_backend_token", value.trim()).apply()

    fun clear() = prefs.edit().clear().apply()
}
