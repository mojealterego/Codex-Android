package com.mojealterego.codexandroid

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mojealterego.codexandroid.agent.*
import com.mojealterego.codexandroid.data.*
import com.mojealterego.codexandroid.git.*
import com.mojealterego.codexandroid.github.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient

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
    val createdPullRequest: GithubPullRequestResponse? = null,
    val downloadingArtifactId: Long? = null,
    val downloadedArtifactId: Long? = null,
    val downloadedApkPath: String? = null,
    val agentBackendUrl: String = "",
    val agentBackendToken: String = "",
    val agentTask: String = "",
    val agentModel: String = "gpt-6-astra",
    val agentSession: AgentSessionResponse? = null,
    val agentEvents: List<AgentStreamEvent> = emptyList(),
    val agentStreaming: Boolean = false,
    val agentChanges: AgentChangeSetResponse? = null,
    val agentChangeSetDraft: ChangeSetDraft? = null,
    val agentCommitMessage: String = "Apply reviewed agent changes",
    val agentPublishResult: PublishResult? = null,
    val agentSteerMessage: String = "",
    val agentControlBusy: Boolean = false,
    val agentCancelRequested: Boolean = false,
    val agentRecovery: AgentRecoveryResponse? = null,
    val agentStreamDisconnected: Boolean = false
) {
    val visibleRepositories get() = filterRepositories(repositories, query)
    val selectedBranchHeadSha: String
        get() = branches.firstOrNull { it.name == selectedBranch }?.commit?.sha.orEmpty()
}

class MainViewModel(
    private val repository: GithubRepository,
    private val workspaceApi: GithubWorkspaceApi,
    private val artifactApi: GithubArtifactApi,
    private val gitDataApi: GithubGitDataApi,
    private val artifactCacheDirectory: File,
    private val agentHttpClient: OkHttpClient,
    private val agentSessionPersistence: AgentSessionPersistence
) : ViewModel() {
    private val mutableState = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = mutableState.asStateFlow()
    private var token: String = ""
    private var agentStreamJob: Job? = null
    private var pendingAgentIdempotencyKey: String? = null
    private var pendingAgentSteerKey: String? = null

    fun search(value: String) {
        mutableState.value = mutableState.value.copy(query = value)
    }

    fun configureAgentBackend(value: String) {
        mutableState.value = mutableState.value.copy(agentBackendUrl = value.trim())
    }

    fun configureAgentBackendToken(value: String) {
        mutableState.value = mutableState.value.copy(agentBackendToken = value.trim())
    }

    fun load(value: String) = viewModelScope.launch {
        token = value
        mutableState.value = mutableState.value.copy(loading = true, error = null)
        runCatching { repository.loadAll(value) }
            .onSuccess {
                mutableState.value = mutableState.value.copy(
                    repositories = it,
                    loading = false
                )
            }
            .onFailure {
                mutableState.value = mutableState.value.copy(
                    loading = false,
                    error = it.message ?: "GitHub request failed"
                )
            }
    }

    fun openRepo(repo: GithubRepo) = viewModelScope.launch {
        cancelAgentStream()
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
            createdPullRequest = null,
            downloadingArtifactId = null,
            downloadedArtifactId = null,
            downloadedApkPath = null,
            agentTask = "",
            agentSession = persisted?.toAgentSessionResponse(),
            agentEvents = emptyList(),
            agentStreaming = false,
            agentChanges = null,
            agentChangeSetDraft = null,
            agentPublishResult = null,
            agentRecovery = null,
            agentStreamDisconnected = false
        )
        pendingAgentIdempotencyKey = null

        runCatching {
            val branches = repository.branches(token, repo)
            val persisted = agentSessionPersistence.load()?.takeIf { session ->
                session.repository == repo.fullName &&
                    branches.any { it.name == session.baseBranch }
            }
            val targetBranch = persisted?.baseBranch ?: repo.defaultBranch
            val contents = repository.contents(token, repo, targetBranch, "")
            Triple(branches, contents, persisted)
        }.onSuccess { (branches, contents, persisted) ->
            mutableState.value = mutableState.value.copy(
                branches = branches,
                selectedBranch = persisted?.baseBranch ?: repo.defaultBranch,
                contents = contents,
                path = "",
                workspaceSection = if (persisted != null) {
                    WorkspaceSection.AGENT
                } else {
                    WorkspaceSection.FILES
                },
                agentSession = persisted?.toAgentSessionResponse(),
                agentStreaming = false,
                agentStreamDisconnected = persisted != null,
                agentRecovery = null,
                loading = false
            )
        }.onFailure {
            mutableState.value = mutableState.value.copy(
                loading = false,
                error = it.message ?: "Cannot load repository"
            )
        }
    }

    fun selectBranch(branch: String) {
        val repo = mutableState.value.selectedRepo ?: return
        if (branch == mutableState.value.selectedBranch) return

        val persisted = agentSessionPersistence.load()?.takeIf {
            it.matches(repo.fullName, branch)
        }

        cancelAgentStream()
        pendingAgentIdempotencyKey = null
        mutableState.value = mutableState.value.copy(
            selectedBranch = branch,
            workspaceSection = if (persisted != null) {
                WorkspaceSection.AGENT
            } else {
                WorkspaceSection.FILES
            },
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
            createdPullRequest = null,
            downloadingArtifactId = null,
            downloadedArtifactId = null,
            downloadedApkPath = null,
            agentTask = "",
            agentSession = null,
            agentEvents = emptyList(),
            agentStreaming = false,
            agentChanges = null,
            agentChangeSetDraft = null,
            agentPublishResult = null,
            agentRecovery = null,
            agentStreamDisconnected = persisted != null
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
            WorkspaceSection.FILES,
            WorkspaceSection.AGENT,
            WorkspaceSection.PULL_REQUEST -> Unit
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
            WorkspaceSection.AGENT,
            WorkspaceSection.PULL_REQUEST -> Unit
        }
    }

    fun edit() {
        mutableState.value = mutableState.value.copy(
            editing = true,
            draftText = mutableState.value.fileText,
            saved = false
        )
    }

    fun updateDraft(value: String) {
        mutableState.value = mutableState.value.copy(
            draftText = value,
            saved = false
        )
    }

    fun updateCommitMessage(value: String) {
        mutableState.value = mutableState.value.copy(commitMessage = value)
    }

    fun updatePrTitle(value: String) {
        mutableState.value = mutableState.value.copy(
            prTitle = value,
            createdPullRequest = null
        )
    }

    fun updatePrBody(value: String) {
        mutableState.value = mutableState.value.copy(
            prBody = value,
            createdPullRequest = null
        )
    }

    fun updateAgentTask(value: String) {
        if (mutableState.value.agentSession != null) return
        pendingAgentIdempotencyKey = null
        mutableState.value = mutableState.value.copy(
            agentTask = value,
            error = null
        )
    }

    fun updateAgentModel(value: String) {
        if (mutableState.value.agentSession != null) return
        pendingAgentIdempotencyKey = null
        mutableState.value = mutableState.value.copy(
            agentModel = value,
            error = null
        )
    }

    fun updateAgentSteerMessage(value: String) {
        pendingAgentSteerKey = null
        mutableState.value = mutableState.value.copy(
            agentSteerMessage = value,
            error = null
        )
    }

    fun steerAgent() = viewModelScope.launch {
        val s = mutableState.value
        val session = s.agentSession ?: return@launch
        if (
            s.agentSteerMessage.isBlank() ||
            s.agentControlBusy ||
            s.agentCancelRequested
        ) return@launch

        val key = pendingAgentSteerKey
            ?: UUID.randomUUID().toString().also {
                pendingAgentSteerKey = it
            }

        mutableState.value = s.copy(
            agentControlBusy = true,
            error = null
        )

        runCatching {
            AgentBffClient(
                s.agentBackendUrl,
                agentHttpClient,
                accessToken = s.agentBackendToken
            ).steer(
                sessionId = session.sessionId,
                message = s.agentSteerMessage,
                idempotencyKey = key
            )
        }.onSuccess {
            pendingAgentSteerKey = null
            mutableState.value = mutableState.value.copy(
                agentControlBusy = false,
                agentSteerMessage = "",
                agentStreaming = true,
                agentCancelRequested = false,
                error = null
            )
        }.onFailure {
            mutableState.value = mutableState.value.copy(
                agentControlBusy = false,
                error = it.message ?: "Nie można wysłać instrukcji do agenta."
            )
        }
    }

    fun cancelAgent() = viewModelScope.launch {
        val s = mutableState.value
        val session = s.agentSession ?: return@launch
        if (!s.agentStreaming || s.agentControlBusy || s.agentCancelRequested) {
            return@launch
        }

        mutableState.value = s.copy(
            agentControlBusy = true,
            error = null
        )

        runCatching {
            AgentBffClient(
                s.agentBackendUrl,
                agentHttpClient,
                accessToken = s.agentBackendToken
            ).cancel(session.sessionId)
        }.onSuccess {
            mutableState.value = mutableState.value.copy(
                agentControlBusy = false,
                agentCancelRequested = true,
                error = null
            )
        }.onFailure {
            mutableState.value = mutableState.value.copy(
                agentControlBusy = false,
                agentCancelRequested = false,
                error = it.message ?: "Nie można anulować turnu agenta."
            )
        }
    }

    fun recoverAgentSession() {
        val s = mutableState.value
        val session = s.agentSession ?: return
        if (s.agentControlBusy) return

        val client = AgentBffClient(
            s.agentBackendUrl,
            agentHttpClient,
            accessToken = s.agentBackendToken
        )
        val connected = CompletableDeferred<Unit>()

        mutableState.value = s.copy(
            agentControlBusy = true,
            error = null
        )

        streamAgentEvents(
            client = client,
            sessionId = session.sessionId,
            onConnected = {
                connected.complete(Unit)
            }
        )

        viewModelScope.launch {
            runCatching {
                withTimeout(15_000L) {
                    connected.await()
                }
                client.recover(session.sessionId)
            }.onSuccess { recovery ->
                val current = mutableState.value
                if (current.agentSession?.sessionId != session.sessionId) {
                    return@onSuccess
                }

                mutableState.value = current.copy(
                    agentRecovery = recovery,
                    agentControlBusy = false,
                    agentStreamDisconnected = false,
                    agentStreaming = recovery.status == "in_progress",
                    error = if (recovery.status == "failed") {
                        recovery.error ?: "Sesja agenta zakończyła się błędem."
                    } else {
                        null
                    }
                )
            }.onFailure {
                val current = mutableState.value
                if (current.agentSession?.sessionId == session.sessionId) {
                    mutableState.value = current.copy(
                        agentControlBusy = false,
                        agentStreamDisconnected = true,
                        error = it.message ?: "Nie można odtworzyć sesji agenta."
                    )
                }
            }
        }
    }

    fun updateAgentCommitMessage(value: String) {
        val s = mutableState.value.copy(agentCommitMessage = value)
        val rebuilt = s.agentChanges?.let { changes ->
            runCatching {
                changes.toChangeSetDraft(
                    targetBranch = s.selectedBranch,
                    currentHeadSha = s.selectedBranchHeadSha,
                    commitMessage = value
                )
            }.getOrNull()
        }
        mutableState.value = s.copy(agentChangeSetDraft = rebuilt)
    }

    fun save() = viewModelScope.launch {
        val s = mutableState.value
        val repo = s.selectedRepo ?: return@launch
        val file = s.openedFile ?: return@launch
        if (s.draftText == s.fileText) return@launch

        mutableState.value = s.copy(loading = true, error = null)
        runCatching {
            repository.updateFile(
                token,
                repo,
                s.selectedBranch,
                file,
                s.draftText,
                s.commitMessage
            )
        }.onSuccess { response ->
            mutableState.value = mutableState.value.copy(
                openedFile = file.copy(
                    sha = response.content.sha,
                    content = ""
                ),
                fileText = s.draftText,
                editing = false,
                commitMessage = "",
                saved = true,
                loading = false
            )
        }.onFailure {
            mutableState.value = mutableState.value.copy(
                loading = false,
                error = it.message ?: "Cannot save file"
            )
        }
    }

    fun startAgent() {
        val s = mutableState.value
        val repo = s.selectedRepo ?: return
        val headSha = s.selectedBranchHeadSha

        if (!canStartAgent(
                branch = s.selectedBranch,
                headSha = headSha,
                backendUrl = s.agentBackendUrl,
                task = s.agentTask
            )
        ) {
            mutableState.value = s.copy(
                error = "Agent wymaga brancha codex/*, przypiętego HEAD, URL BFF i zadania."
            )
            return
        }
        if (s.agentSession != null || s.loading) return

        val key = pendingAgentIdempotencyKey
            ?: UUID.randomUUID().toString().also {
                pendingAgentIdempotencyKey = it
            }
        val backendUrl = s.agentBackendUrl
        val backendToken = s.agentBackendToken

        mutableState.value = s.copy(
            loading = true,
            error = null,
            agentEvents = emptyList(),
            agentChanges = null,
            agentChangeSetDraft = null,
            agentPublishResult = null,
            agentRecovery = null,
            agentStreamDisconnected = false
        )

        viewModelScope.launch {
            val clientResult = runCatching {
                val client = AgentBffClient(
                    backendUrl,
                    agentHttpClient,
                    accessToken = backendToken
                )
                client to client.startSession(
                    StartAgentSessionRequest(
                        repository = repo.fullName,
                        baseBranch = s.selectedBranch,
                        baseSha = headSha,
                        task = s.agentTask,
                        model = s.agentModel
                    ),
                    idempotencyKey = key
                )
            }

            clientResult.onSuccess { (client, session) ->
                pendingAgentIdempotencyKey = null
                agentSessionPersistence.save(
                    session.toPersistedAgentSession()
                )
                mutableState.value = mutableState.value.copy(
                    loading = false,
                    error = null,
                    agentSession = session,
                    agentStreaming = true
                )
                streamAgentEvents(client, session.sessionId)
            }.onFailure {
                mutableState.value = mutableState.value.copy(
                    loading = false,
                    agentStreaming = false,
                    error = it.message ?: "Nie można uruchomić sesji agenta."
                )
            }
        }
    }

    fun loadAgentChanges() = viewModelScope.launch {
        val s = mutableState.value
        val session = s.agentSession ?: return@launch
        if (s.loading) return@launch

        mutableState.value = s.copy(
            loading = true,
            error = null,
            agentPublishResult = null
        )

        runCatching {
            val client = AgentBffClient(
                s.agentBackendUrl,
                agentHttpClient,
                accessToken = s.agentBackendToken
            )
            val changes = client.loadChanges(session.sessionId)
            val draft = changes.toChangeSetDraft(
                targetBranch = s.selectedBranch,
                currentHeadSha = s.selectedBranchHeadSha,
                commitMessage = s.agentCommitMessage
            )
            changes to draft
        }.onSuccess { (changes, draft) ->
            mutableState.value = mutableState.value.copy(
                loading = false,
                error = null,
                agentChanges = changes,
                agentChangeSetDraft = draft
            )
        }.onFailure {
            mutableState.value = mutableState.value.copy(
                loading = false,
                agentChanges = null,
                agentChangeSetDraft = null,
                error = it.message ?: "Nie można pobrać zmian agenta."
            )
        }
    }

    fun discardAgentChanges() {
        mutableState.value = mutableState.value.copy(
            agentChanges = null,
            agentChangeSetDraft = null,
            agentPublishResult = null,
            error = null
        )
    }

    fun publishAgentChanges() = viewModelScope.launch {
        val s = mutableState.value
        val repo = s.selectedRepo ?: return@launch
        val draft = s.agentChangeSetDraft ?: return@launch
        if (s.loading || s.agentPublishResult != null) return@launch

        mutableState.value = s.copy(loading = true, error = null)

        runCatching {
            GitDataPublisher(
                RetrofitGitDataTransport(gitDataApi, token)
            ).publish(
                repoFullName = repo.fullName,
                draft = draft
            )
        }.onSuccess { result ->
            val updatedBranches = mutableState.value.branches.map { branch ->
                if (branch.name == draft.targetBranch) {
                    branch.copy(commit = GithubBranchCommit(result.commitSha))
                } else {
                    branch
                }
            }
            mutableState.value = mutableState.value.copy(
                loading = false,
                error = null,
                branches = updatedBranches,
                agentPublishResult = result,
                agentChangeSetDraft = null
            )
        }.onFailure {
            mutableState.value = mutableState.value.copy(
                loading = false,
                error = it.message ?: "Nie można opublikować zmian agenta."
            )
        }
    }

    fun resetAgentSession() {
        cancelAgentStream()
        agentSessionPersistence.clear()
        pendingAgentIdempotencyKey = null
        pendingAgentSteerKey = null
        mutableState.value = mutableState.value.copy(
            error = null,
            agentTask = "",
            agentSession = null,
            agentEvents = emptyList(),
            agentStreaming = false,
            agentChanges = null,
            agentChangeSetDraft = null,
            agentPublishResult = null,
            agentSteerMessage = "",
            agentControlBusy = false,
            agentCancelRequested = false,
            agentRecovery = null,
            agentStreamDisconnected = false
        )
    }

    fun createPullRequest() = viewModelScope.launch {
        val s = mutableState.value
        val repo = s.selectedRepo ?: return@launch
        if (!canCreatePullRequest(
                s.selectedBranch,
                repo.defaultBranch,
                s.prTitle
            )
        ) return@launch

        mutableState.value = s.copy(
            loading = true,
            error = null,
            createdPullRequest = null
        )
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
        runCatching {
            workspaceController().loadRunDetails(repo.fullName, run.id)
        }.onSuccess { details ->
            mutableState.value = mutableState.value.copy(
                workflowJobs = details.jobs,
                artifacts = details.artifacts,
                loading = false
            )
        }.onFailure {
            mutableState.value = mutableState.value.copy(
                loading = false,
                error = it.message ?: "Cannot load workflow run"
            )
        }
    }

    fun openJob(job: GithubWorkflowJob) = viewModelScope.launch {
        val s = mutableState.value
        val repo = s.selectedRepo ?: return@launch
        mutableState.value = s.copy(
            loading = true,
            error = null,
            selectedJobId = job.id,
            jobLog = ""
        )
        runCatching {
            workspaceController().loadJobLog(repo.fullName, job.id)
        }.onSuccess {
            mutableState.value = mutableState.value.copy(
                jobLog = it,
                loading = false
            )
        }.onFailure {
            mutableState.value = mutableState.value.copy(
                loading = false,
                error = it.message ?: "Cannot load workflow log"
            )
        }
    }

    fun downloadArtifact(artifact: GithubArtifact) = viewModelScope.launch {
        val s = mutableState.value
        val repo = s.selectedRepo ?: return@launch

        if (s.downloadingArtifactId != null) return@launch
        if (artifact.expired) {
            mutableState.value = s.copy(
                error = "Artifact wygasł i nie może zostać pobrany."
            )
            return@launch
        }

        val digest = artifact.digest
        if (digest.isNullOrBlank()) {
            mutableState.value = s.copy(
                error = "Artifact nie ma digestu SHA-256."
            )
            return@launch
        }

        mutableState.value = s.copy(
            downloadingArtifactId = artifact.id,
            downloadedArtifactId = null,
            downloadedApkPath = null,
            error = null
        )

        runCatching {
            withContext(Dispatchers.IO) {
                val artifactDirectory = File(
                    artifactCacheDirectory,
                    artifact.id.toString()
                )
                if (artifactDirectory.exists()) {
                    artifactDirectory.deleteRecursively()
                }
                require(
                    artifactDirectory.mkdirs() ||
                        artifactDirectory.isDirectory
                ) {
                    "Nie można utworzyć katalogu artefaktu."
                }

                val archive = File(artifactDirectory, "artifact.zip")
                val apkDirectory = File(artifactDirectory, "apk")

                GithubArtifactDownloadService(
                    artifactApi,
                    token
                ).download(
                    repoFullName = repo.fullName,
                    artifactId = artifact.id,
                    destination = archive
                )

                ArtifactArchive.verifyAndExtractApk(
                    archive = archive,
                    expectedDigest = digest,
                    outputDirectory = apkDirectory
                )
            }
        }.onSuccess { apk ->
            mutableState.value = mutableState.value.copy(
                downloadingArtifactId = null,
                downloadedArtifactId = artifact.id,
                downloadedApkPath = apk.absolutePath,
                error = null
            )
        }.onFailure {
            mutableState.value = mutableState.value.copy(
                downloadingArtifactId = null,
                downloadedArtifactId = null,
                downloadedApkPath = null,
                error = it.message ?: "Nie można pobrać artefaktu APK."
            )
        }
    }

    fun openItem(item: RepoContent) {
        if (item.type == "file") {
            openFile(item)
            return
        }
        val repo = mutableState.value.selectedRepo ?: return
        if (item.type == "dir") {
            openPath(
                repo,
                mutableState.value.selectedBranch,
                item.path
            )
        }
    }

    fun back() {
        val s = mutableState.value
        if (s.selectedRepo == null) return

        if (
            s.workspaceSection == WorkspaceSection.CI &&
            s.selectedJobId != null
        ) {
            mutableState.value = s.copy(
                selectedJobId = null,
                jobLog = "",
                error = null
            )
            return
        }
        if (
            s.workspaceSection == WorkspaceSection.CI &&
            s.selectedRunId != null
        ) {
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
            mutableState.value = s.copy(
                workspaceSection = WorkspaceSection.FILES,
                error = null
            )
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
            cancelAgentStream()
            pendingAgentIdempotencyKey = null
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
                agentSession = null,
                agentEvents = emptyList(),
                agentStreaming = false,
                agentChanges = null,
                agentChangeSetDraft = null,
                agentPublishResult = null,
                error = null
            )
        } else {
            val parent = s.path.substringBeforeLast('/', "")
            openPath(s.selectedRepo, s.selectedBranch, parent)
        }
    }

    private fun streamAgentEvents(
        client: AgentBffClient,
        sessionId: String,
        onConnected: () -> Unit = {}
    ) {
        cancelAgentStream()
        agentStreamJob = viewModelScope.launch {
            try {
                client.streamEvents(
                    sessionId = sessionId,
                    onConnected = {
                        val current = mutableState.value
                        if (current.agentSession?.sessionId == sessionId) {
                            mutableState.value = current.copy(
                                agentStreamDisconnected = false
                            )
                        }
                        onConnected()
                    }
                ) { event ->
                    val current = mutableState.value
                    if (current.agentSession?.sessionId != sessionId) {
                        return@streamEvents
                    }

                    val events = (current.agentEvents + event).takeLast(200)
                    val terminal = isRootTurnTerminal(event)
                    mutableState.value = current.copy(
                        agentEvents = events,
                        agentStreaming = if (terminal) false else current.agentStreaming,
                        agentCancelRequested = if (terminal) false else current.agentCancelRequested,
                        agentControlBusy = if (terminal) false else current.agentControlBusy
                    )
                }
            } catch (_: CancellationException) {
                throw CancellationException()
            } catch (error: Exception) {
                val current = mutableState.value
                if (current.agentSession?.sessionId == sessionId) {
                    mutableState.value = current.copy(
                        agentStreaming = false,
                        agentStreamDisconnected = true,
                        error = error.message ?: "Strumień agenta został przerwany."
                    )
                }
            } finally {
                val current = mutableState.value
                if (current.agentSession?.sessionId == sessionId) {
                    mutableState.value = current.copy(agentStreaming = false)
                }
            }
        }
    }

    private fun cancelAgentStream() {
        agentStreamJob?.cancel()
        agentStreamJob = null
    }

    private fun loadCommits() = viewModelScope.launch {
        val s = mutableState.value
        val repo = s.selectedRepo ?: return@launch
        mutableState.value = s.copy(loading = true, error = null)
        runCatching {
            workspaceController().loadCommits(
                repo.fullName,
                s.selectedBranch
            )
        }.onSuccess {
            mutableState.value = mutableState.value.copy(
                commits = it,
                loading = false
            )
        }.onFailure {
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
        runCatching {
            workspaceController().loadRuns(
                repo.fullName,
                s.selectedBranch
            )
        }.onSuccess {
            mutableState.value = mutableState.value.copy(
                workflowRuns = it,
                loading = false
            )
        }.onFailure {
            mutableState.value = mutableState.value.copy(
                loading = false,
                error = it.message ?: "Cannot load workflow runs"
            )
        }
    }

    private fun workspaceController(): WorkspaceController =
        WorkspaceController(
            GithubWorkspaceService(workspaceApi, token)
        )

    private fun openFile(item: RepoContent) = viewModelScope.launch {
        val s = mutableState.value
        val repo = s.selectedRepo ?: return@launch
        mutableState.value = s.copy(loading = true, error = null)
        runCatching {
            repository.file(
                token,
                repo,
                s.selectedBranch,
                item.path
            )
        }.onSuccess {
            mutableState.value = mutableState.value.copy(
                openedFile = it,
                fileText = it.decodedText(),
                editing = false,
                draftText = "",
                commitMessage = "",
                saved = false,
                loading = false
            )
        }.onFailure {
            mutableState.value = mutableState.value.copy(
                loading = false,
                error = it.message ?: "Cannot load file"
            )
        }
    }

    private fun openPath(
        repo: GithubRepo,
        branch: String,
        path: String
    ) = viewModelScope.launch {
        mutableState.value = mutableState.value.copy(
            loading = true,
            error = null,
            selectedRepo = repo,
            selectedBranch = branch,
            path = path
        )
        runCatching {
            repository.contents(token, repo, branch, path)
        }.onSuccess {
            mutableState.value = mutableState.value.copy(
                contents = it,
                loading = false
            )
        }.onFailure {
            mutableState.value = mutableState.value.copy(
                loading = false,
                error = it.message ?: "Cannot load repository"
            )
        }
    }
}
