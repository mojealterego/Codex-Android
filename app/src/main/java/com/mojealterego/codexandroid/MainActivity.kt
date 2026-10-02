package com.mojealterego.codexandroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("CODEX ANDROID", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
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
                    ElevatedCard(Modifier.fillMaxWidth()) {
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
