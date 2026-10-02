package com.mojealterego.codexandroid

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mojealterego.codexandroid.data.GithubRepo
import com.mojealterego.codexandroid.data.GithubRepository
import com.mojealterego.codexandroid.data.filterRepositories
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MainUiState(
    val repositories: List<GithubRepo> = emptyList(),
    val query: String = "",
    val loading: Boolean = false,
    val error: String? = null
) {
    val visibleRepositories get() = filterRepositories(repositories, query)
}

class MainViewModel(private val repository: GithubRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = mutableState.asStateFlow()

    fun search(value: String) { mutableState.value = mutableState.value.copy(query = value) }

    fun load(token: String) = viewModelScope.launch {
        mutableState.value = mutableState.value.copy(loading = true, error = null)
        runCatching { repository.loadAll(token) }
            .onSuccess { mutableState.value = mutableState.value.copy(repositories = it, loading = false) }
            .onFailure { mutableState.value = mutableState.value.copy(loading = false, error = it.message ?: "GitHub request failed") }
    }
}
