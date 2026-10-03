package com.mojealterego.codexandroid.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentSseDecoderTest {
    @Test fun decodesEventAndMultilineDataAtFrameBoundary() {
        val decoder = AgentSseDecoder()

        assertNull(decoder.accept("event: agent.session.turn.output_text.delta"))
        assertNull(decoder.accept("data: {\"delta\":\"hello\"}"))
        val event = decoder.accept("")

        assertEquals("agent.session.turn.output_text.delta", event?.type)
        assertEquals("{\"delta\":\"hello\"}", event?.data)
    }

    @Test fun ignoresCommentsAndJoinsMultipleDataLines() {
        val decoder = AgentSseDecoder()

        assertNull(decoder.accept(": keepalive"))
        assertNull(decoder.accept("event: message"))
        assertNull(decoder.accept("data: first"))
        assertNull(decoder.accept("data: second"))

        val event = decoder.accept("")

        assertEquals("message", event?.type)
        assertEquals("first\nsecond", event?.data)
    }
}
