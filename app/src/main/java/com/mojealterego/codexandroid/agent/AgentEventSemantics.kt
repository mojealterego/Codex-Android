package com.mojealterego.codexandroid.agent

import com.google.gson.JsonParser

private val ROOT_TURN_TERMINALS = setOf(
    "agent.session.turn.completed",
    "agent.session.turn.failed",
    "agent.session.turn.cancelled"
)

private val SESSION_TERMINALS = setOf(
    "agent.session.failed",
    "agent.session.environment.failed"
)

fun isRootTurnTerminal(event: AgentStreamEvent): Boolean {
    if (event.type in SESSION_TERMINALS) return true
    if (event.type !in ROOT_TURN_TERMINALS) return false

    return runCatching {
        val root = JsonParser.parseString(event.data).asJsonObject
        val turn = root.getAsJsonObject("turn") ?: return@runCatching false
        val subagent = turn.get("subagent_id")
        subagent == null || subagent.isJsonNull
    }.getOrDefault(false)
}
