package com.anisync.android.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.anisync.android.cloudstream.CloudStreamPlugin
import com.anisync.android.cloudstream.CloudStreamRepository
import com.anisync.android.cloudstream.CloudStreamRepositoryManager
import kotlinx.coroutines.launch

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CloudStreamSettingsScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val manager = remember(context) { CloudStreamRepositoryManager(context) }
    val scope = rememberCoroutineScope()
    var repositories by remember { mutableStateOf<List<CloudStreamRepository>>(emptyList()) }
    var installed by remember { mutableStateOf(manager.installedIds()) }
    var repositoryUrl by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            loading = true
            error = null
            try { repositories = manager.allRepositories() }
            catch (t: Throwable) { error = t.message ?: "Gagal memuat repository" }
            finally { loading = false }
        }
    }
    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Provider") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                },
                actions = {
                    IconButton(onClick = { refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Tambah repository")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "Tambahkan URL repo.json CloudStream untuk melihat dan memasang extension anime. Hanya provider anime yang terdaftar akan ditampilkan. Pasang extension dari repository yang kamu percaya.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            if (loading) item { CircularProgressIndicator() }
            error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            if (repositories.isEmpty() && !loading) {
                item { Text("Belum ada repository. Tekan + untuk menambahkan repo.json.") }
            }
            items(repositories, key = { it.url }) { repository ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(repository.name, style = MaterialTheme.typography.titleMedium)
                                Text(repository.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = {
                                manager.removeRepository(repository.url)
                                repositories = repositories.filterNot { it.url == repository.url }
                            }) { Icon(Icons.Default.Delete, contentDescription = "Hapus repository") }
                        }
                        Text("${repository.plugins.size} extension tersedia", style = MaterialTheme.typography.labelMedium)
                        repository.plugins.forEach { plugin ->
                            CloudStreamPluginRow(
                                plugin = plugin,
                                isInstalled = installed.contains(plugin.installId),
                                onInstall = {
                                    scope.launch {
                                        loading = true
                                        error = null
                                        try {
                                            manager.install(plugin)
                                            installed = manager.installedIds()
                                        } catch (t: Throwable) { error = t.message ?: "Gagal memasang extension" }
                                        finally { loading = false }
                                    }
                                },
                                onUninstall = {
                                    manager.uninstall(plugin)
                                    installed = manager.installedIds()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Tambah repository") },
            text = {
                OutlinedTextField(
                    value = repositoryUrl,
                    onValueChange = { repositoryUrl = it },
                    label = { Text("URL repo.json") },
                    placeholder = { Text("https://example.com/repo.json") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    enabled = repositoryUrl.isNotBlank() && !loading,
                    onClick = {
                        val value = repositoryUrl.trim()
                        scope.launch {
                            loading = true
                            error = null
                            try {
                                val repo = manager.addRepository(value)
                                repositories = (repositories.filterNot { it.url == repo.url } + repo)
                                repositoryUrl = ""
                                showAddDialog = false
                            } catch (t: Throwable) { error = t.message ?: "Repository tidak valid" }
                            finally { loading = false }
                        }
                    }
                ) { Text("Tambah") }
            },
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("Batal") } }
        )
    }
}

@Composable
private fun CloudStreamPluginRow(
    plugin: CloudStreamPlugin,
    isInstalled: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text(plugin.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                listOfNotNull(plugin.language, plugin.types.joinToString().takeIf { it.isNotBlank() }, "v${plugin.version}").joinToString(" • "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (isInstalled) {
            TextButton(onClick = onUninstall) { Text("Hapus") }
        } else {
            TextButton(onClick = onInstall) {
                Icon(Icons.Default.Download, contentDescription = null)
                Text("Pasang")
            }
        }
    }
}
