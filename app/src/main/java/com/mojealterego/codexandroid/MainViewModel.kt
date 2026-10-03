package com.mojealterego.codexandroid

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mojealterego.codexandroid.data.*
import com.mojealterego.codexandroid.github.*
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
    val branches: List<GithubBranch> = emptyList(),
    val selectedBranch: String = "",
    val path: String = "",
    val contents: List<RepoContent> = emptyList(),
    val openedFile: GithubFileContent? = null,
    val fileText: String = "",
    val editing: Boolean = false,
    val draftText: String = "",
    val commitMessage: String = "",
    val saved: Boolean = false,
    val workspaceSection: WorkspaceSection = WorkspaceSection.FILES,
    val commits: List<GithubCommitItem> = emptyList(),
    val workflowRuns: List<GithubWorkflowRun> = emptyList(),
    val selectedRunId: Long? = null,
    val workflowJobs: List<GithubWorkflowJob> = emptyList(),
    val artifacts: List<GithubArtifact> = emptyList(),
    val selectedJobId: Long? = null,
    val jobLog: String = "",
    val prTitle: String = "",
    val prBody: String = "",
    val createdPullRequest: GithubPullRequestResponse? = null
) {
    val visibleRepositories get() = filterRepositories(repositories, query)
}

class MainViewModel(
    private val repository: GithubRepository,
    private val workspaceApi: GithubWorkspaceApi
) : ViewModel() {
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

    fun openRepo(repo: GithubRepo) = viewModelScope.launch {
        mutableState.value = mutableState.value.copy(
            loading = true,
            error = null,
            selectedRepo = repo,
            selectedBranch = repo.defaultBranch,
            workspaceSection = WorkspaceSection.FILES,
            commits = emptyList(),
            workflowRuns = emptyList(),
            workflowJobs = emptyList(),
            artifacts = emptyList(),
            selectedRunId = null,
            selectedJobId = null,
            jobLog = "",
            createdPullRequest = null
        )
        runCatching {
            val branches = repository.branches(token, repo)
            val contents = repository.contents(token, repo, repo.defaultBranch, "")
            branches to contents
        }.onSuccess { (branches, contents) ->
            mutableState.value = mutableState.value.copy(branches = branches, contents = contents, path = "", loading = false)
        }.onFailure {
            mutableState.value = mutableState.value.copy(loading = false, error = it.message ?: "Cannot load repository")
        }
    }

    fun selectBranch(branch: String) {
        val repo = mutableState.value.selectedRepo ?: return
        if (branch == mutableState.value.selectedBranch) return
        mutableState.value = mutableState.value.copy(
            selectedBranch = branch,
            workspaceSection = WorkspaceSection.FILES,
            openedFile = null,
            editing = false,
            draftText = "",
            commitMessage = "",
            saved = false,
            commits = emptyList(),
            workflowRuns = emptyList(),
            workflowJobs = emptyList(),
            artifacts = emptyList(),
            selectedRunId = null,
            selectedJobId = null,
            jobLog = "",
            createdPullRequest = null
        )
        openPath(repo, branch, "")
    }

    fun selectWorkspaceSection(section: WorkspaceSection) {
        val s = mutableState.value
        if (s.selectedRepo == null) return
        mutableState.value = s.copy(
            workspaceSection = section,
            error = null,
            selectedRunId = if (section == WorkspaceSection.CI) s.selectedRunId else null,
            selectedJobId = if (section == WorkspaceSection.CI) s.selectedJobId else null,
            jobLog = if (section == WorkspaceSection.CI) s.jobLog else ""
        )
        when (section) {
            WorkspaceSection.COMMITS -> loadCommits()
            WorkspaceSection.CI -> loadRuns()
            WorkspaceSection.FILES, WorkspaceSection.PULL_REQUEST -> Unit
        }
    }

    fun refreshWorkspace() {
        when (mutableState.value.workspaceSection) {
            WorkspaceSection.COMMITS -> loadCommits()
            WorkspaceSection.CI -> loadRuns()
            WorkspaceSection.FILES -> {
                val s = mutableState.value
                val repo = s.selectedRepo ?: return
                openPath(repo, s.selectedBranch, s.path)
            }
            WorkspaceSection.PULL_REQUEST -> Unit
        }
    }

    fun edit() { mutableState.value = mutableState.value.copy(editing = true, draftText = mutableState.value.fileText, saved = false) }
    fun updateDraft(value: String) { mutableState.value = mutableState.value.copy(draftText = value, saved = false) }
    fun updateCommitMessage(value: String) { mutableState.value = mutableState.value.copy(commitMessage = value) }
    fun updatePrTitle(value: String) { mutableState.value = mutableState.value.copy(prTitle = value, createdPullRequest = null) }
    fun updatePrBody(value: String) { mutableState.value = mutableState.value.copy(prBody = value, createdPullRequest = null) }

    fun save() = viewModelScope.launch {
        val s = mutableState.value
        val repo = s.selectedRepo ?: return@launch
        val file = s.openedFile ?: return@launch
        if (s.draftText == s.fileText) return@launch
        mutableState.value = s.copy(loading = true, error = null)
        runCatching { repository.updateFile(token, repo, s.selectedBranch, file, s.draftText, s.commitMessage) }
            .onSuccess { response ->
                mutableState.value = mutableState.value.copy(
                    openedFile = file.copy(sha = response.content.sha, content = ""),
                    fileText = s.draftText,
                    editing = false,
                    commitMessage = "",
                    saved = true,
                    loading = false
                )
            }
            .onFailure { mutableState.value = mutableState.value.copy(loading = false, error = it.message ?: "Cannot save file") }
    }

    fun createPullRequest() = viewModelScope.launch {
        val s = mutableState.value
        val repo = s.selectedRepo ?: return@launch
        if (!canCreatePullRequest(s.selectedBranch, repo.defaultBranch, s.prTitle)) return@launch

        mutableState.value = s.copy(loading = true, error = null, createdPullRequest = null)
        runCatching {
            workspaceController().createPullRequest(
                repoFullName = repo.fullName,
                head = s.selectedBranch,
                base = repo.defaultBranch,
                title = s.prTitle,
                body = s.prBody
            )
        }.onSuccess {
            mutableState.value = mutableState.value.copy(
                createdPullRequest = it,
                loading = false
            )
        }.onFailure {
            mutableState.value = mutableState.value.copy(
                loading = false,
                error = it.message ?: "Cannot create pull request"
            )
        }
    }

    fun openRun(run: GithubWorkflowRun) = viewModelScope.launch {
        val s = mutableState.value
        val repo = s.selectedRepo ?: return@launch
        mutableState.value = s.copy(
            loading = true,
            error = null,
            selectedRunId = run.id,
            selectedJobId = null,
            jobLog = ""
        )
        runCatching { workspaceController().loadRunDetails(repo.fullName, run.id) }
            .onSuccess { details ->
                mutableState.value = mutableState.value.copy(
                    workflowJobs = details.jobs,
                    artifacts = details.artifacts,
                    loading = false
                )
            }
            .onFailure {
                mutableState.value = mutableState.value.copy(
                    loading = false,
                    error = it.message ?: "Cannot load workflow run"
                )
            }
    }

    fun openJob(job: GithubWorkflowJob) = viewModelScope.launch {
        val s = mutableState.value
        val repo = s.selectedRepo ?: return@launch
        mutableState.value = s.copy(loading = true, error = null, selectedJobId = job.id, jobLog = "")
        runCatching { workspaceController().loadJobLog(repo.fullName, job.id) }
            .onSuccess {
                mutableState.value = mutableState.value.copy(jobLog = it, loading = false)
            }
            .onFailure {
                mutableState.value = mutableState.value.copy(
                    loading = false,
                    error = it.message ?: "Cannot load workflow log"
                )
            }
    }

    fun openItem(item: RepoContent) {
        if (item.type == "file") { openFile(item); return }
        val repo = mutableState.value.selectedRepo ?: return
        if (item.type == "dir") openPath(repo, mutableState.value.selectedBranch, item.path)
    }

    fun back() {
        val s = mutableState.value
        if (s.selectedRepo == null) return

        if (s.workspaceSection == WorkspaceSection.CI && s.selectedJobId != null) {
            mutableState.value = s.copy(selectedJobId = null, jobLog = "", error = null)
            return
        }
        if (s.workspaceSection == WorkspaceSection.CI && s.selectedRunId != null) {
            mutableState.value = s.copy(
                selectedRunId = null,
                selectedJobId = null,
                workflowJobs = emptyList(),
                artifacts = emptyList(),
                jobLog = "",
                error = null
            )
            return
        }
        if (s.workspaceSection != WorkspaceSection.FILES) {
            mutableState.value = s.copy(workspaceSection = WorkspaceSection.FILES, error = null)
            return
        }
        if (s.openedFile != null) {
            mutableState.value = s.copy(
                openedFile = null,
                fileText = "",
                editing = false,
                draftText = "",
                commitMessage = "",
                saved = false,
                error = null
            )
            return
        }
        if (s.path.isBlank()) {
            mutableState.value = s.copy(
                selectedRepo = null,
                branches = emptyList(),
                selectedBranch = "",
                contents = emptyList(),
                commits = emptyList(),
                workflowRuns = emptyList(),
                workflowJobs = emptyList(),
                artifacts = emptyList(),
                selectedRunId = null,
                selectedJobId = null,
                jobLog = "",
                createdPullRequest = null,
                error = null
            )
        } else {
            val parent = s.path.substringBeforeLast('/', "")
            openPath(s.selectedRepo, s.selectedBranch, parent)
        }
    }

    private fun loadCommits() = viewModelScope.launch {
        val s = mutableState.value
        val repo = s.selectedRepo ?: return@launch
        mutableState.value = s.copy(loading = true, error = null)
        runCatching { workspaceController().loadCommits(repo.fullName, s.selectedBranch) }
            .onSuccess {
                mutableState.value = mutableState.value.copy(commits = it, loading = false)
            }
            .onFailure {
                mutableState.value = mutableState.value.copy(
                    loading = false,
                    error = it.message ?: "Cannot load commits"
                )
            }
    }

    private fun loadRuns() = viewModelScope.launch {
        val s = mutableState.value
        val repo = s.selectedRepo ?: return@launch
        mutableState.value = s.copy(
            loading = true,
            error = null,
            selectedRunId = null,
            selectedJobId = null,
            workflowJobs = emptyList(),
            artifacts = emptyList(),
            jobLog = ""
        )
        runCatching { workspaceController().loadRuns(repo.fullName, s.selectedBranch) }
            .onSuccess {
                mutableState.value = mutableState.value.copy(workflowRuns = it, loading = false)
            }
            .onFailure {
                mutableState.value = mutableState.value.copy(
                    loading = false,
                    error = it.message ?: "Cannot load workflow runs"
                )
            }
    }

    private fun workspaceController(): WorkspaceController =
        WorkspaceController(GithubWorkspaceService(workspaceApi, token))

    private fun openFile(item: RepoContent) = viewModelScope.launch {
        val s = mutableState.value
        val repo = s.selectedRepo ?: return@launch
        mutableState.value = s.copy(loading = true, error = null)
        runCatching { repository.file(token, repo, s.selectedBranch, item.path) }
            .onSuccess {
                mutableState.value = mutableState.value.copy(
                    openedFile = it,
                    fileText = it.decodedText(),
                    editing = false,
                    draftText = "",
                    commitMessage = "",
                    saved = false,
                    loading = false
                )
            }
            .onFailure {
                mutableState.value = mutableState.value.copy(
                    loading = false,
                    error = it.message ?: "Cannot load file"
                )
            }
    }

    private fun openPath(repo: GithubRepo, branch: String, path: String) = viewModelScope.launch {
        mutableState.value = mutableState.value.copy(
            loading = true,
            error = null,
            selectedRepo = repo,
            selectedBranch = branch,
            path = path
        )
        runCatching { repository.contents(token, repo, branch, path) }
            .onSuccess { mutableState.value = mutableState.value.copy(contents = it, loading = false) }
            .onFailure {
                mutableState.value = mutableState.value.copy(
                    loading = false,
                    error = it.message ?: "Cannot load repository"
                )
            }
    }
}
