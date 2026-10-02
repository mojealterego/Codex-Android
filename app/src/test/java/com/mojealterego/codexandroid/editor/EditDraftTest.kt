package com.mojealterego.codexandroid.editor

import org.junit.Assert.*
import org.junit.Test

class EditDraftTest {
    @Test fun unchangedDraftIsClean() {
        val draft = EditDraft("a.kt", "sha1", "hello", "hello")
        assertFalse(draft.isDirty)
        assertEquals("", draft.diff())
    }

    @Test fun changedDraftProducesReadableDiff() {
        val draft = EditDraft("a.kt", "sha1", "one\ntwo", "one\nthree")
        assertTrue(draft.isDirty)
        assertEquals("  one\n- two\n+ three", draft.diff())
    }
}
