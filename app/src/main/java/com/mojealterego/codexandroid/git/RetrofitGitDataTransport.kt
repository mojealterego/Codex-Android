package com.mojealterego.codexandroid.git

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonPrimitive
import com.google.gson.annotations.SerializedName
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Url

interface GithubGitDataApi {
    @GET
    suspend fun getRef(
        @Url path: String,
        @Header("Authorization") authorization: String,
        @Header("Accept") accept: String = GITHUB_ACCEPT
    ): GitRefResponse

    @GET
    suspend fun getCommit(
        @Url path: String,
        @Header("Authorization") authorization: String,
        @Header("Accept") accept: String = GITHUB_ACCEPT
    ): GitCommitResponse

    @POST
    suspend fun createBlob(
        @Url path: String,
        @Header("Authorization") authorization: String,
        @Header("Accept") accept: String = GITHUB_ACCEPT,
        @Body body: GitBlobRequest
    ): GitShaResponse

    @POST
    suspend fun createTree(
        @Url path: String,
        @Header("Authorization") authorization: String,
        @Header("Accept") accept: String = GITHUB_ACCEPT,
        @Body body: GitTreeRequest
    ): GitShaResponse

    @POST
    suspend fun createCommit(
        @Url path: String,
        @Header("Authorization") authorization: String,
        @Header("Accept") accept: String = GITHUB_ACCEPT,
        @Body body: GitCommitRequest
    ): GitShaResponse

    @PATCH
    suspend fun updateRef(
        @Url path: String,
        @Header("Authorization") authorization: String,
        @Header("Accept") accept: String = GITHUB_ACCEPT,
        @Body body: GitUpdateRefRequest
    ): GitRefResponse

    companion object {
        const val GITHUB_ACCEPT = "application/vnd.github+json"
    }
}

data class GitObjectResponse(val sha: String)

data class GitRefResponse(
    @SerializedName("object") val gitObject: GitObjectResponse
)

data class GitTreeResponse(val sha: String)

data class GitCommitResponse(
    val sha: String,
    val tree: GitTreeResponse
)

data class GitShaResponse(val sha: String)

data class GitBlobRequest(
    val content: String,
    val encoding: String = "base64"
)

data class GitTreeWireEntry(
    val path: String,
    val mode: String,
    val type: String,
    val sha: JsonElement
)

data class GitTreeRequest(
    @SerializedName("base_tree") val baseTree: String,
    val tree: List<GitTreeWireEntry>
)

data class GitCommitRequest(
    val message: String,
    val tree: String,
    val parents: List<String>
)

data class GitUpdateRefRequest(
    val sha: String,
    val force: Boolean
)

class RetrofitGitDataTransport(
    private val api: GithubGitDataApi,
    token: String
) : GitDataTransport {
    private val authorization = "Bearer " + token.trim()

    init {
        require(token.isNotBlank()) { "GitHub token is required" }
    }

    override suspend fun getRef(repoFullName: String, branch: String): GitRef {
        val response = api.getRef(
            path = gitRefPath(repoFullName, branch),
            authorization = authorization
        )
        return GitRef(response.gitObject.sha)
    }

    override suspend fun getCommit(repoFullName: String, sha: String): GitCommit {
        val response = api.getCommit(
            path = gitCommitPath(repoFullName, sha),
            authorization = authorization
        )
        return GitCommit(response.sha, response.tree.sha)
    }

    override suspend fun createBlob(repoFullName: String, contentBase64: String): String =
        api.createBlob(
            path = gitBlobsPath(repoFullName),
            authorization = authorization,
            body = GitBlobRequest(contentBase64)
        ).sha

    override suspend fun createTree(
        repoFullName: String,
        baseTreeSha: String,
        entries: List<GitTreeEntry>
    ): String {
        val wireEntries = entries.map { entry ->
            GitTreeWireEntry(
                path = entry.path,
                mode = entry.mode,
                type = entry.type,
                sha = entry.sha?.let(::JsonPrimitive) ?: JsonNull.INSTANCE
            )
        }
        return api.createTree(
            path = gitTreesPath(repoFullName),
            authorization = authorization,
            body = GitTreeRequest(baseTreeSha, wireEntries)
        ).sha
    }

    override suspend fun createCommit(
        repoFullName: String,
        message: String,
        treeSha: String,
        parentSha: String
    ): String =
        api.createCommit(
            path = gitCommitsPath(repoFullName),
            authorization = authorization,
            body = GitCommitRequest(message, treeSha, listOf(parentSha))
        ).sha

    override suspend fun updateRef(
        repoFullName: String,
        branch: String,
        newSha: String,
        force: Boolean
    ) {
        require(!force) { "Force-push is disabled" }
        api.updateRef(
            path = gitRefPath(repoFullName, branch),
            authorization = authorization,
            body = GitUpdateRefRequest(newSha, force = false)
        )
    }
}

internal fun gitRefPath(repoFullName: String, branch: String): String =
    "repos/" + repoFullName.trim('/') + "/git/ref/heads/" + branch.trim('/')

internal fun gitCommitPath(repoFullName: String, sha: String): String =
    "repos/" + repoFullName.trim('/') + "/git/commits/" + sha

internal fun gitBlobsPath(repoFullName: String): String =
    "repos/" + repoFullName.trim('/') + "/git/blobs"

internal fun gitTreesPath(repoFullName: String): String =
    "repos/" + repoFullName.trim('/') + "/git/trees"

internal fun gitCommitsPath(repoFullName: String): String =
    "repos/" + repoFullName.trim('/') + "/git/commits"
