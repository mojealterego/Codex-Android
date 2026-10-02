package com.mojealterego.codexandroid.data

import org.junit.Assert.assertEquals
import org.junit.Test

class FileContentTest {
    @Test fun decodesBase64FileAndRemovesWhitespace() {
        val file = GithubFileContent(
            name = "hello.txt",
            path = "hello.txt",
            sha = "abc",
            encoding = "base64",
            content = "SGVs\nbG8="
        )
        assertEquals("Hello", file.decodedText())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedEncoding() {
        GithubFileContent("x", "x", "abc", "binary", "AA==").decodedText()
    }
}
