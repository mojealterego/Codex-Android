package com.mojealterego.codexandroid

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mojealterego.codexandroid.data.*
import com.mojealterego.codexandroid.editor.EditDraft
import com.mojealterego.codexandroid.github.*
import java.io.File
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val retrofit = Retrofit.Builder()
            .baseUrl("https://api.github.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        val api = retrofit.create(GithubApi::class.java)
        val workspaceApi = retrofit.create(GithubWorkspaceApi::class.java)
        val artifactApi = retrofit.create(GithubArtifactApi::class.java)
        val repository = GithubRepository(api)
        val tokenStore = TokenStore(this)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val vm: MainViewModel = viewModel(
                    factory = SimpleViewModelFactory {
                        MainViewModel(
                            repository = repository,
                            workspaceApi = workspaceApi,
                            artifactApi = artifactApi,
                            artifactCacheDirectory = File(cacheDir, "artifacts")
                        )
                    }
                )
                CodexHome(vm, tokenStore)
            }
        }
    }
}

@Composable
private fun CodexHome(vm: MainViewModel, tokenStore: TokenStore) {
    val state by vm.state.collectAsState()
    var token by remember { mutableStateOf(tokenStore.githubToken().orEmpty()) }
    var branchMenu by remember { mutableStateOf(false) }

    BackHandler(enabled = state.selectedRepo != null) { vm.back() }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "CODEX ANDROID",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            state.selectedRepo?.let { repo ->
                Text(repo.fullName, color = MaterialTheme.colorScheme.primary)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box {
                        OutlinedButton(
                            onClick = { branchMenu = true },
                            enabled = !state.loading
                        ) {
                            Text("Branch: " + state.selectedBranch + " ▾")
                        }
                        DropdownMenu(
                            expanded = branchMenu,
                            onDismissRequest = { branchMenu = false }
                        ) {
                            state.branches.forEach { branch ->
                                DropdownMenuItem(
                                    text = { Text(branch.name) },
                                    onClick = {
                                        branchMenu = false
                                        vm.selectBranch(branch.name)
                                    }
                                )
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = vm::refreshWorkspace,
                        enabled = !state.loading
                    ) {
                        Text("Odśwież")
                    }
                }

                WorkspaceTabs(state.workspaceSection, vm::selectWorkspaceSection)

                if (state.loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }

                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }

                when (state.workspaceSection) {
                    WorkspaceSection.FILES -> FilesWorkspace(state, vm)
                    WorkspaceSection.COMMITS -> CommitsWorkspace(state)
                    WorkspaceSection.CI -> CiWorkspace(state, vm)
                    WorkspaceSection.PULL_REQUEST -> PullRequestWorkspace(state, repo, vm)
                }

                OutlinedButton(onClick = vm::back) {
                    Text("← Wstecz")
                }
            } ?: run {
                Text("GitHub workspace", color = MaterialTheme.colorScheme.primary)

                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("GitHub token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = {
                        tokenStore.saveGithubToken(token)
                        vm.load(token)
                    },
                    enabled = token.isNotBlank() && !state.loading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (state.loading) "Łączenie…" else "Połącz z GitHub")
                }

                if (state.repositories.isNotEmpty()) {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = vm::search,
                        label = { Text("Szukaj repozytorium") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(state.visibleRepositories.size.toString() + " repozytoriów")
                }

                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.visibleRepositories, key = { it.id }) { repo ->
                        ElevatedCard(
                            Modifier.fillMaxWidth().clickable { vm.openRepo(repo) }
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(repo.name, fontWeight = FontWeight.SemiBold)
                                Text(repo.fullName, style = MaterialTheme.typography.bodySmall)
                                val visibility = if (repo.private) "PRIVATE" else "PUBLIC"
                                Text(
                                    visibility + " · " + (repo.language ?: "—") + " · " + repo.defaultBranch,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceTabs(
    selected: WorkspaceSection,
    onSelected: (WorkspaceSection) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        listOf(
            WorkspaceSection.FILES to "Pliki",
            WorkspaceSection.COMMITS to "Commity",
            WorkspaceSection.CI to "CI",
            WorkspaceSection.PULL_REQUEST to "PR"
        ).forEach { (section, label) ->
            FilterChip(
                selected = selected == section,
                onClick = { onSelected(section) },
                label = { Text(label) }
            )
        }
    }
}

@Composable
private fun ColumnScope.FilesWorkspace(state: MainUiState, vm: MainViewModel) {
    Text("/" + state.path, style = MaterialTheme.typography.bodySmall)

    val openedFile = state.openedFile
    if (openedFile != null) {
        Text(openedFile.name, fontWeight = FontWeight.SemiBold)

        if (state.editing) {
            OutlinedTextField(
                value = state.draftText,
                onValueChange = vm::updateDraft,
                modifier = Modifier.fillMaxWidth().weight(1f),
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                label = { Text("Edycja") }
            )

            val draft = EditDraft(
                openedFile.path,
                openedFile.sha,
                state.fileText,
                state.draftText
            )

            if (draft.isDirty) {
                Text("DIFF", fontWeight = FontWeight.Bold)
                Surface(
                    Modifier.fillMaxWidth().heightIn(max = 180.dp),
                    tonalElevation = 2.dp
                ) {
                    Text(
                        draft.diff(),
                        modifier = Modifier.padding(10.dp),
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            OutlinedTextField(
                value = state.commitMessage,
                onValueChange = vm::updateCommitMessage,
                label = { Text("Commit message") },
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = vm::save,
                enabled = draft.isDirty &&
                    state.commitMessage.isNotBlank() &&
                    !state.loading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (state.loading) "Zapisywanie…"
                    else "Commit & push → " + state.selectedBranch
                )
            }
        } else {
            Surface(
                Modifier.fillMaxWidth().weight(1f),
                tonalElevation = 2.dp
            ) {
                Text(
                    state.fileText,
                    modifier = Modifier.padding(12.dp),
                    fontFamily = FontFamily.Monospace
                )
            }

            if (state.saved) {
                Text("Zapisano w GitHub: " + state.selectedBranch)
            }

            Button(
                onClick = vm::edit,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Edytuj plik")
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(state.contents, key = { it.path }) { item ->
                ListItem(
                    headlineContent = {
                        Text((if (item.type == "dir") "▸ " else "") + item.name)
                    },
                    supportingContent = {
                        Text(
                            if (item.type == "dir") "folder"
                            else item.size.toString() + " B"
                        )
                    },
                    modifier = Modifier.clickable { vm.openItem(item) }
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.CommitsWorkspace(state: MainUiState) {
    Text("Historia: " + state.selectedBranch, fontWeight = FontWeight.SemiBold)

    LazyColumn(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(state.commits, key = { it.sha }) { item ->
            ListItem(
                headlineContent = {
                    Text(item.commit.message.lineSequence().firstOrNull().orEmpty())
                },
                supportingContent = {
                    val author = item.commit.author?.name ?: "unknown"
                    val date = item.commit.author?.date ?: ""
                    Text(item.sha.take(8) + " · " + author + if (date.isBlank()) "" else " · " + date)
                }
            )
        }
    }
}

@Composable
private fun ColumnScope.CiWorkspace(state: MainUiState, vm: MainViewModel) {
    val context = LocalContext.current

    when {
        state.selectedJobId != null -> {
            Text("Log joba #" + state.selectedJobId, fontWeight = FontWeight.SemiBold)
            Surface(
                modifier = Modifier.fillMaxWidth().weight(1f),
                tonalElevation = 2.dp
            ) {
                LazyColumn(Modifier.fillMaxSize().padding(10.dp)) {
                    item {
                        Text(
                            state.jobLog.ifBlank { "Brak logu." },
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        state.selectedRunId != null -> {
            Text("Run #" + state.selectedRunId, fontWeight = FontWeight.SemiBold)

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item {
                    Text("Joby", fontWeight = FontWeight.Bold)
                }

                items(state.workflowJobs, key = { it.id }) { job ->
                    ListItem(
                        headlineContent = { Text(job.name) },
                        supportingContent = { Text(job.ciState.name) },
                        modifier = Modifier.clickable { vm.openJob(job) }
                    )
                }

                item {
                    Spacer(Modifier.height(8.dp))
                    Text("Artefakty", fontWeight = FontWeight.Bold)
                }

                items(state.artifacts, key = { it.id }) { artifact ->
                    ListItem(
                        headlineContent = { Text(artifact.name) },
                        supportingContent = {
                            val stateLabel = if (artifact.expired) "EXPIRED" else "AVAILABLE"
                            Text(
                                stateLabel + " · " +
                                    artifact.sizeInBytes.toString() + " B" +
                                    (artifact.digest?.let { " · " + it.take(24) } ?: "")
                            )
                        },
                        trailingContent = {
                            when {
                                state.downloadingArtifactId == artifact.id -> {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp
                                    )
                                }

                                state.downloadedArtifactId == artifact.id &&
                                    !state.downloadedApkPath.isNullOrBlank() -> {
                                    Button(
                                        onClick = {
                                            launchApkInstaller(
                                                context,
                                                requireNotNull(state.downloadedApkPath)
                                            )
                                        }
                                    ) {
                                        Text("Instaluj")
                                    }
                                }

                                !artifact.expired &&
                                    artifact.digest?.startsWith("sha256:", ignoreCase = true) == true -> {
                                    OutlinedButton(
                                        onClick = { vm.downloadArtifact(artifact) },
                                        enabled = state.downloadingArtifactId == null
                                    ) {
                                        Text("Pobierz")
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }

        else -> {
            Text("GitHub Actions · " + state.selectedBranch, fontWeight = FontWeight.SemiBold)

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(state.workflowRuns, key = { it.id }) { run ->
                    ListItem(
                        headlineContent = {
                            Text(run.name ?: "Workflow #" + run.id)
                        },
                        supportingContent = {
                            Text(run.ciState.name + " · " + (run.headSha?.take(8) ?: "—"))
                        },
                        modifier = Modifier.clickable { vm.openRun(run) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.PullRequestWorkspace(
    state: MainUiState,
    repo: GithubRepo,
    vm: MainViewModel
) {
    Column(
        modifier = Modifier.fillMaxWidth().weight(1f),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Pull Request", fontWeight = FontWeight.SemiBold)
        Text("Head: " + state.selectedBranch, style = MaterialTheme.typography.bodySmall)
        Text("Base: " + repo.defaultBranch, style = MaterialTheme.typography.bodySmall)

        OutlinedTextField(
            value = state.prTitle,
            onValueChange = vm::updatePrTitle,
            label = { Text("Tytuł PR") },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = state.prBody,
            onValueChange = vm::updatePrBody,
            label = { Text("Opis") },
            modifier = Modifier.fillMaxWidth().weight(1f)
        )

        Button(
            onClick = vm::createPullRequest,
            enabled = canCreatePullRequest(
                state.selectedBranch,
                repo.defaultBranch,
                state.prTitle
            ) && !state.loading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Utwórz Pull Request")
        }

        state.createdPullRequest?.let { pr ->
            Text(
                "PR #" + pr.number + " · " + pr.state + " · " + pr.title,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}


private fun launchApkInstaller(context: Context, apkPath: String) {
    val apk = File(apkPath)
    if (!apk.isFile || apk.length() <= 0L) {
        Toast.makeText(context, "Plik APK nie istnieje.", Toast.LENGTH_SHORT).show()
        return
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        !context.packageManager.canRequestPackageInstalls()
    ) {
        val settingsIntent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:" + context.packageName)
        )
        context.startActivity(settingsIntent)
        Toast.makeText(
            context,
            "Włącz instalowanie z tego źródła i ponownie wybierz Instaluj.",
            Toast.LENGTH_LONG
        ).show()
        return
    }

    val uri = FileProvider.getUriForFile(
        context,
        context.packageName + ".files",
        apk
    )

    val installIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    context.startActivity(installIntent)
}
