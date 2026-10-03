package com.mojealterego.codexandroid.agent

class AgentSseDecoder {
    private var eventType: String = "message"
    private val dataLines = mutableListOf<String>()

    fun accept(line: String): AgentStreamEvent? {
        if (line.startsWith(":")) return null

        if (line.isEmpty()) {
            if (dataLines.isEmpty()) {
                eventType = "message"
                return null
            }

            val event = AgentStreamEvent(
                type = eventType,
                data = dataLines.joinToString("\n")
            )
            eventType = "message"
            dataLines.clear()
            return event
        }

        when {
            line.startsWith("event:") -> {
                eventType = line.removePrefix("event:").trim().ifBlank { "message" }
            }
            line.startsWith("data:") -> {
                dataLines += line.removePrefix("data:").trimStart()
            }
        }

        return null
    }
}
