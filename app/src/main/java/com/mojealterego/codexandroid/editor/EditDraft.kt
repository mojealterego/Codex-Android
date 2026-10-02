package com.mojealterego.codexandroid.editor

data class EditDraft(
    val path: String,
    val baseSha: String,
    val original: String,
    val edited: String
) {
    val isDirty: Boolean get() = original != edited

    fun diff(): String {
        if (!isDirty) return ""
        val before = original.lines()
        val after = edited.lines()
        val out = mutableListOf<String>()
        val max = maxOf(before.size, after.size)
        for (i in 0 until max) {
            val old = before.getOrNull(i)
            val new = after.getOrNull(i)
            when {
                old == new && old != null -> out += "  " + old
                old != null && new != null -> {
                    out += "- " + old
                    out += "+ " + new
                }
                old != null -> out += "- " + old
                new != null -> out += "+ " + new
            }
        }
        return out.joinToString("\n")
    }
}
