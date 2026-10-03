package com.mojealterego.codexandroid.agent

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson

data class PersistedAgentSession(
    val sessionId: String,
    val state: String,
    val environmentId: String?,
    val repository: String,
    val baseBranch: String,
    val baseSha: String,
    val eventsPath: String
) {
    fun matches(repository: String, branch: String): Boolean =
        this.repository == repository && baseBranch == branch

    fun toAgentSessionResponse(): AgentSessionResponse =
        AgentSessionResponse(
            sessionId = sessionId,
            state = state,
            environmentId = environmentId,
            repository = repository,
            baseBranch = baseBranch,
            baseSha = baseSha,
            eventsPath = eventsPath
        )
}

fun AgentSessionResponse.toPersistedAgentSession(): PersistedAgentSession =
    PersistedAgentSession(
        sessionId = sessionId,
        state = state,
        environmentId = environmentId,
        repository = repository,
        baseBranch = baseBranch,
        baseSha = baseSha,
        eventsPath = eventsPath
    )

interface AgentSessionPersistence {
    fun load(): PersistedAgentSession?
    fun save(session: PersistedAgentSession)
    fun clear()
}

class EncryptedAgentSessionPersistence(
    context: Context,
    private val gson: Gson = Gson()
) : AgentSessionPersistence {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "agent_session_state",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    override fun load(): PersistedAgentSession? {
        val raw = prefs.getString(KEY_ACTIVE_SESSION, null) ?: return null
        return runCatching {
            gson.fromJson(raw, PersistedAgentSession::class.java)
        }.getOrNull()
    }

    override fun save(session: PersistedAgentSession) {
        require(session.sessionId.isNotBlank()) { "Session id is required" }
        require(session.repository.isNotBlank()) { "Repository is required" }
        require(session.baseBranch.isNotBlank()) { "Base branch is required" }
        require(session.baseSha.isNotBlank()) { "Base SHA is required" }

        prefs.edit()
            .putString(KEY_ACTIVE_SESSION, gson.toJson(session))
            .apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_ACTIVE_SESSION).apply()
    }

    companion object {
        private const val KEY_ACTIVE_SESSION = "active_agent_session"
    }
}
