package com.mojealterego.codexandroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mojealterego.codexandroid.data.*
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val api = Retrofit.Builder().baseUrl("https://api.github.com/")
            .addConverterFactory(GsonConverterFactory.create()).build().create(GithubApi::class.java)
        val repository = GithubRepository(api)
        val tokenStore = TokenStore(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val vm: MainViewModel = viewModel(factory = SimpleViewModelFactory { MainViewModel(repository) })
                CodexHome(vm, tokenStore)
            }
        }
    }
}

@Composable
private fun CodexHome(vm: MainViewModel, tokenStore: TokenStore) {
    val state by vm.state.collectAsState()
    var token by remember { mutableStateOf(tokenStore.githubToken().orEmpty()) }
    BackHandler(enabled = state.selectedRepo != null) { vm.back() }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("CODEX ANDROID", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            state.selectedRepo?.let { repo ->
                Text(repo.fullName, color = MaterialTheme.colorScheme.primary)
                Text("/" + state.path, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = vm::back) { Text("← Wstecz") }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.openedFile != null) {
                    Text(state.openedFile.name, fontWeight = FontWeight.SemiBold)
                    Surface(Modifier.fillMaxWidth().weight(1f), tonalElevation = 2.dp) {
                        Text(state.fileText, modifier = Modifier.padding(12.dp), fontFamily = FontFamily.Monospace)
                    }
                } else LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(state.contents, key = { it.path }) { item ->
                        ListItem(
                            headlineContent = { Text((if (item.type == "dir") "▸ " else "") + item.name) },
                            supportingContent = { Text(if (item.type == "dir") "folder" else item.size.toString() + " B") },
                            modifier = Modifier.clickable { vm.openItem(item) }
                        )
                    }
                }
            } ?: run {
                Text("GitHub workspace", color = MaterialTheme.colorScheme.primary)
                OutlinedTextField(token, { token = it }, label = { Text("GitHub token") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = { tokenStore.saveGithubToken(token); vm.load(token) },
                    enabled = token.isNotBlank() && !state.loading, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.loading) "Łączenie…" else "Połącz z GitHub")
                }
                if (state.repositories.isNotEmpty()) {
                    OutlinedTextField(state.query, vm::search, label = { Text("Szukaj repozytorium") }, modifier = Modifier.fillMaxWidth())
                    Text(state.visibleRepositories.size.toString() + " repozytoriów")
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.visibleRepositories, key = { it.id }) { repo ->
                        ElevatedCard(Modifier.fillMaxWidth().clickable { vm.openRepo(repo) }) {
                            Column(Modifier.padding(16.dp)) {
                                Text(repo.name, fontWeight = FontWeight.SemiBold)
                                Text(repo.fullName, style = MaterialTheme.typography.bodySmall)
                                val visibility = if (repo.private) "PRIVATE" else "PUBLIC"
                                Text(visibility + " · " + (repo.language ?: "—") + " · " + repo.defaultBranch,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
