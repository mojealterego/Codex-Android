package com.mojealterego.codexandroid

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mojealterego.codexandroid.data.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MainUiState(
    val repositories: List<GithubRepo> = emptyList(),
    val query: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val selectedRepo: GithubRepo? = null,
    val path: String = "",
    val contents: List<RepoContent> = emptyList(),
    val openedFile: GithubFileContent? = null,
    val fileText: String = "",
    val editing: Boolean = false,
    val draftText: String = "",
    val commitMessage: String = "",
    val saved: Boolean = false
) {
    val visibleRepositories get() = filterRepositories(repositories, query)
}

class MainViewModel(private val repository: GithubRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = mutableState.asStateFlow()
    private var token: String = ""

    fun search(value: String) { mutableState.value = mutableState.value.copy(query = value) }

    fun load(value: String) = viewModelScope.launch {
        token = value
        mutableState.value = mutableState.value.copy(loading = true, error = null)
        runCatching { repository.loadAll(value) }
            .onSuccess { mutableState.value = mutableState.value.copy(repositories = it, loading = false) }
            .onFailure { mutableState.value = mutableState.value.copy(loading = false, error = it.message ?: "GitHub request failed") }
    }

    fun openRepo(repo: GithubRepo) = openPath(repo, "")

    fun edit() { mutableState.value = mutableState.value.copy(editing = true, draftText = mutableState.value.fileText, saved = false) }

    fun updateDraft(value: String) { mutableState.value = mutableState.value.copy(draftText = value, saved = false) }
    fun updateCommitMessage(value: String) { mutableState.value = mutableState.value.copy(commitMessage = value) }

    fun save() = viewModelScope.launch {
        val s = mutableState.value
        val repo = s.selectedRepo ?: return@launch
        val file = s.openedFile ?: return@launch
        if (s.draftText == s.fileText) return@launch
        mutableState.value = s.copy(loading = true, error = null)
        runCatching { repository.updateFile(token, repo, file, s.draftText, s.commitMessage) }
            .onSuccess { response ->
                mutableState.value = mutableState.value.copy(
                    openedFile = file.copy(sha = response.content.sha, content = ""),
                    fileText = s.draftText, editing = false, commitMessage = "", saved = true, loading = false
                )
            }
            .onFailure { mutableState.value = mutableState.value.copy(loading = false, error = it.message ?: "Cannot save file") }
    }

    fun openItem(item: RepoContent) {
        if (item.type == "file") { openFile(item); return }
        val repo = mutableState.value.selectedRepo ?: return
        if (item.type == "dir") openPath(repo, item.path)
    }

    fun back() {
        val s = mutableState.value
        if (s.selectedRepo == null) return
        if (s.openedFile != null) {
            mutableState.value = s.copy(openedFile = null, fileText = "", editing = false, draftText = "", commitMessage = "", saved = false, error = null)
            return
        }
        if (s.path.isBlank()) {
            mutableState.value = s.copy(selectedRepo = null, contents = emptyList(), error = null)
        } else {
            val parent = s.path.substringBeforeLast('/', "")
            openPath(s.selectedRepo, parent)
        }
    }

    private fun openFile(item: RepoContent) = viewModelScope.launch {
        val repo = mutableState.value.selectedRepo ?: return@launch
        mutableState.value = mutableState.value.copy(loading = true, error = null)
        runCatching { repository.file(token, repo, item.path) }
            .onSuccess { mutableState.value = mutableState.value.copy(openedFile = it, fileText = it.decodedText(), editing = false, draftText = "", commitMessage = "", saved = false, loading = false) }
            .onFailure { mutableState.value = mutableState.value.copy(loading = false, error = it.message ?: "Cannot load file") }
    }

    private fun openPath(repo: GithubRepo, path: String) = viewModelScope.launch {
        mutableState.value = mutableState.value.copy(loading = true, error = null, selectedRepo = repo, path = path)
        runCatching { repository.contents(token, repo, path) }
            .onSuccess { mutableState.value = mutableState.value.copy(contents = it, loading = false) }
            .onFailure { mutableState.value = mutableState.value.copy(loading = false, error = it.message ?: "Cannot load repository") }
    }
}
