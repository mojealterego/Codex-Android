package com.mojealterego.codexandroid.github

import java.io.File
import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Streaming
import retrofit2.http.Url

interface GithubArtifactApi {
    @Streaming
    @GET
    suspend fun downloadArtifact(
        @Url path: String,
        @Header("Authorization") authorization: String,
        @Header("Accept") accept: String = "application/vnd.github+json"
    ): ResponseBody
}

class GithubArtifactDownloadService(
    private val api: GithubArtifactApi,
    token: String
) {
    private val authorization = "Bearer " + token.trim()

    init {
        require(token.isNotBlank()) { "GitHub token is required" }
    }

    suspend fun download(
        repoFullName: String,
        artifactId: Long,
        destination: File
    ): File {
        require(repoFullName.isNotBlank()) { "Repository is required" }
        require(artifactId > 0L) { "Artifact id must be positive" }

        val parent = requireNotNull(destination.parentFile) {
            "Destination must have a parent directory"
        }
        parent.mkdirs()
        require(parent.isDirectory) { "Destination directory is not available" }

        val partial = File(parent, destination.name + ".part")
        if (partial.exists()) partial.delete()

        try {
            api.downloadArtifact(
                path = workflowArtifactDownloadPath(repoFullName, artifactId),
                authorization = authorization
            ).use { body ->
                body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }

            require(partial.isFile && partial.length() > 0L) {
                "Downloaded artifact is empty"
            }

            if (destination.exists()) {
                require(destination.delete()) {
                    "Cannot replace existing artifact"
                }
            }

            if (!partial.renameTo(destination)) {
                partial.copyTo(destination, overwrite = true)
                require(partial.delete()) {
                    "Cannot remove temporary artifact file"
                }
            }

            require(destination.isFile && destination.length() > 0L) {
                "Downloaded artifact was not published"
            }

            return destination
        } catch (error: Throwable) {
            partial.delete()
            throw error
        }
    }
}
