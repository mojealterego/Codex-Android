package com.mojealterego.codexandroid.github

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArtifactArchiveTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test fun verifiesArchiveDigestAndExtractsSingleApk() {
        val zip = temp.newFile("artifact.zip")
        val apkBytes = byteArrayOf(1, 2, 3, 4, 5)
        createZip(zip, mapOf("app-debug.apk" to apkBytes, "note.txt" to byteArrayOf(9)))

        val expected = "sha256:" + sha256(zip.readBytes())
        val outDir = temp.newFolder("out")

        val apk = ArtifactArchive.verifyAndExtractApk(zip, expected, outDir)

        assertTrue(apk.name.endsWith(".apk"))
        assertArrayEquals(apkBytes, apk.readBytes())
    }

    @Test fun rejectsDigestMismatch() {
        val zip = temp.newFile("bad-digest.zip")
        createZip(zip, mapOf("app.apk" to byteArrayOf(1)))

        assertIllegalArgument {
            ArtifactArchive.verifyAndExtractApk(
                zip,
                "sha256:" + "0".repeat(64),
                temp.newFolder("bad-digest-out")
            )
        }
    }

    @Test fun rejectsMultipleApks() {
        val zip = temp.newFile("many.zip")
        createZip(
            zip,
            mapOf(
                "a.apk" to byteArrayOf(1),
                "b.apk" to byteArrayOf(2)
            )
        )

        assertIllegalArgument {
            ArtifactArchive.verifyAndExtractApk(
                zip,
                "sha256:" + sha256(zip.readBytes()),
                temp.newFolder("many-out")
            )
        }
    }

    private fun createZip(file: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(FileOutputStream(file)).use { out ->
            entries.forEach { (name, bytes) ->
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }
}
