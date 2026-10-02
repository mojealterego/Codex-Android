package com.mojealterego.codexandroid.data

import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query
import retrofit2.http.Url

interface GithubApi {
    @GET("user/repos")
    suspend fun repositories(
        @Header("Authorization") authorization: String,
        @Header("Accept") accept: String = "application/vnd.github+json",
        @Query("visibility") visibility: String = "all",
        @Query("affiliation") affiliation: String = "owner,collaborator,organization_member",
        @Query("sort") sort: String = "updated",
        @Query("per_page") perPage: Int = 100,
        @Query("page") page: Int
    ): List<GithubRepo>

    @GET
    suspend fun contents(
        @Url path: String,
        @Header("Authorization") authorization: String,
        @Header("Accept") accept: String = "application/vnd.github+json",
        @Query("ref") ref: String
    ): List<RepoContent>

    @GET
    suspend fun file(
        @Url path: String,
        @Header("Authorization") authorization: String,
        @Header("Accept") accept: String = "application/vnd.github+json",
        @Query("ref") ref: String
    ): GithubFileContent

    @retrofit2.http.PUT
    suspend fun updateFile(
        @Url path: String,
        @Header("Authorization") authorization: String,
        @Header("Accept") accept: String = "application/vnd.github+json",
        @retrofit2.http.Body body: UpdateFileRequest
    ): UpdateFileResponse
}
