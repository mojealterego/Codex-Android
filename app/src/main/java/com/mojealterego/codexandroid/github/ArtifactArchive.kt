package com.mojealterego.codexandroid.github

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

object ArtifactArchive {
    private const val SHA256_PREFIX = "sha256:"
    private const val MAX_APK_BYTES = 512L * 1024L * 1024L

    fun verifyAndExtractApk(
        archive: File,
        expectedDigest: String,
        outputDirectory: File
    ): File {
        require(archive.isFile) { "Artifact archive does not exist" }

        val expected = expectedDigest
            .trim()
            .lowercase()
            .removePrefix(SHA256_PREFIX)

        require(expected.matches(Regex("[0-9a-f]{64}"))) {
            "Artifact digest must be sha256:<64 hex chars>"
        }

        val actual = sha256(archive)
        require(actual == expected) {
            "Artifact digest mismatch"
        }

        outputDirectory.mkdirs()
        require(outputDirectory.isDirectory) {
            "Artifact output directory is not available"
        }

        ZipFile(archive).use { zip ->
            val apkEntries = zip.entries().asSequence()
                .filterNot { it.isDirectory }
                .filter { it.name.lowercase().endsWith(".apk") }
                .toList()

            require(apkEntries.size == 1) {
                "Artifact must contain exactly one APK"
            }

            val entry = apkEntries.single()
            if (entry.size >= 0) {
                require(entry.size <= MAX_APK_BYTES) {
                    "APK exceeds extraction size limit"
                }
            }

            val output = File(
                outputDirectory,
                File(entry.name).name.ifBlank { "artifact.apk" }
            )

            zip.getInputStream(entry).use { input ->
                FileOutputStream(output).use { out ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L

                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break

                        total += read
                        require(total <= MAX_APK_BYTES) {
                            "APK exceeds extraction size limit"
                        }

                        out.write(buffer, 0, read)
                    }
                }
            }

            require(output.isFile && output.length() > 0L) {
                "Extracted APK is empty"
            }

            return output
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")

        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }

        return digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }
}
