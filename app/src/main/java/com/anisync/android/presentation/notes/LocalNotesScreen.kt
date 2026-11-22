package com.anisync.android.presentation.notes

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable private data class LocalNote(
    val id: String = UUID.randomUUID().toString(), val title: String = "", val body: String = "",
    val folderId: String = "default", val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(), val pinned: Boolean = false,
    val archived: Boolean = false, val deleted: Boolean = false
)
@Serializable private data class LocalNoteFolder(val id: String, val name: String)
@Serializable private data class LocalNotesBackup(
    val version: Int = 1, val notes: List<LocalNote> = emptyList(),
    val folders: List<LocalNoteFolder> = listOf(LocalNoteFolder("default", "Catatan"))
)

private val notesJson = Json { ignoreUnknownKeys = true; prettyPrint = true }
private const val PREFS = "local_notes_store"
private const val PAYLOAD = "payload"
private const val TREE = "backup_tree_uri"
private const val BACKUP_FILE = "anisync-local-notes.json"

private fun loadNotes(context: Context): LocalNotesBackup {
    val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PAYLOAD, null)
        ?: return LocalNotesBackup()
    return runCatching { notesJson.decodeFromString<LocalNotesBackup>(raw) }.getOrDefault(LocalNotesBackup())
}
private fun saveTree(context: Context, uri: Uri, data: LocalNotesBackup): Boolean = runCatching {
    val folder = DocumentFile.fromTreeUri(context, uri) ?: return false
    val file = folder.findFile(BACKUP_FILE) ?: folder.createFile("application/json", BACKUP_FILE) ?: return false
    context.contentResolver.openOutputStream(file.uri, "wt")?.bufferedWriter()?.use {
        it.write(notesJson.encodeToString(data))
    } ?: return false
    true
}.getOrDefault(false)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalNotesScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var data by remember { mutableStateOf(loadNotes(context)) }
    var treeUri by remember { mutableStateOf(prefs.getString(TREE, null)?.let(Uri::parse)) }
    var status by remember { mutableStateOf("Catatan tersimpan di perangkat") }
    var folderId by remember { mutableStateOf("all") }
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<String?>(null) }
    var archived by remember { mutableStateOf(false) }
    var trash by remember { mutableStateOf(false) }
    var folderDialog by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf("") }
    var backupMenu by remember { mutableStateOf(false) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            treeUri = uri
            prefs.edit().putString(TREE, uri.toString()).apply()
            status = if (saveTree(context, uri, data)) "Cadangan aktif" else "Folder dipilih, tetapi penulisan cadangan gagal"
        }
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            val ok = runCatching { context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                it.write(notesJson.encodeToString(data))
            }; true }.getOrDefault(false)
            status = if (ok) "Cadangan berhasil diekspor" else "Ekspor gagal"
        }
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val restored = runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use {
                notesJson.decodeFromString<LocalNotesBackup>(it.readText())
            } }.getOrNull()
            if (restored != null) { data = restored; status = "Catatan berhasil dipulihkan" }
            else status = "File cadangan tidak valid"
        }
    }

    LaunchedEffect(data, treeUri) {
        delay(500)
        val snapshot = data
        withContext(Dispatchers.IO) {
            prefs.edit().putString(PAYLOAD, notesJson.encodeToString(snapshot)).apply()
            treeUri?.let { uri ->
                val ok = saveTree(context, uri, snapshot)
                status = if (ok) "Tersimpan • cadangan diperbarui" else "Tersimpan lokal • cadangan gagal"
            }
        }
    }

    val note = data.notes.firstOrNull { it.id == editing }
    val visible = remember(data, folderId, query, archived, trash) {
        data.notes.filter {
            (if (trash) it.deleted else if (archived) it.archived && !it.deleted else !it.archived && !it.deleted) &&
                (folderId == "all" || it.folderId == folderId) &&
                (query.isBlank() || it.title.contains(query, true) || it.body.contains(query, true))
        }.sortedWith(compareByDescending<LocalNote> { it.pinned }.thenByDescending { it.updatedAt })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column { Text(if (note == null) "Catatan lokal" else "Edit catatan", fontWeight = FontWeight.Bold)
                    if (note == null) Text(status, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                navigationIcon = { IconButton(onClick = { if (note != null) editing = null else onBackClick() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali") } },
                actions = { if (note == null) Box {
                    IconButton(onClick = { backupMenu = true }) { Icon(Icons.Default.Backup, "Cadangan") }
                    DropdownMenu(backupMenu, { backupMenu = false }) {
                        DropdownMenuItem(text = { Text("Pilih folder cadangan") }, leadingIcon = { Icon(Icons.Default.Folder, null) },
                            onClick = { backupMenu = false; folderPicker.launch(null) })
                        DropdownMenuItem(text = { Text("Pulihkan dari file") }, leadingIcon = { Icon(Icons.Default.Restore, null) },
                            onClick = { backupMenu = false; importPicker.launch(arrayOf("application/json", "text/json")) })
                        DropdownMenuItem(text = { Text("Ekspor cadangan") }, leadingIcon = { Icon(Icons.Default.Download, null) },
                            onClick = { backupMenu = false; exportPicker.launch(BACKUP_FILE) })
                    }
                } }
            )
        },
        floatingActionButton = { if (note == null && !trash) FloatingActionButton(onClick = {
            val item = LocalNote(folderId = folderId.takeUnless { it == "all" } ?: data.folders.first().id)
            data = data.copy(notes = listOf(item) + data.notes); editing = item.id
        }) { Icon(Icons.Default.NoteAdd, "Buat catatan") } }
    ) { inset ->
        if (note != null) {
            Column(Modifier.fillMaxSize().padding(inset).padding(horizontal = 18.dp, vertical = 10.dp)) {
                var preview by remember(note.id) { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { preview = !preview }) { Text(if (preview) "Edit" else "Pratinjau") }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { data = data.copy(notes = data.notes.map { if (it.id == note.id) it.copy(pinned = !it.pinned) else it }) }) {
                        Icon(Icons.Default.PushPin, "Sematkan") }
                    IconButton(onClick = { data = data.copy(notes = data.notes.map { if (it.id == note.id) it.copy(archived = true) else it }); editing = null }) {
                        Icon(Icons.Default.Archive, "Arsipkan") }
                    IconButton(onClick = { data = data.copy(notes = data.notes.map { if (it.id == note.id) it.copy(deleted = true) else it }); editing = null }) {
                        Icon(Icons.Default.Delete, "Hapus") }
                }
                BasicTextField(value = note.title, onValueChange = { v -> data = data.copy(notes = data.notes.map {
                    if (it.id == note.id) it.copy(title = v, updatedAt = System.currentTimeMillis()) else it
                }) }, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    textStyle = MaterialTheme.typography.headlineSmall.copy(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold),
                    decorationBox = { inner -> Box { if (note.title.isEmpty()) Text("Judul catatan", color = MaterialTheme.colorScheme.outline); inner() } })
                HorizontalDivider()
                if (preview) Text(note.body.ifBlank { "Belum ada isi." }, Modifier.fillMaxSize().padding(top = 14.dp),
                    style = MaterialTheme.typography.bodyLarge)
                else {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("**tebal**", "*miring*", "~~coret~~", "# Judul", "- [ ] Checklist", "> Kutipan", "\`kode\`", "[tautan](url)", "||spoiler||").forEach { token ->
                            AssistChip(onClick = { data = data.copy(notes = data.notes.map {
                                if (it.id == note.id) it.copy(body = it.body + (if (it.body.isBlank()) "" else "\\n") + token) else it
                            }) }, label = { Text(token.take(12)) })
                        }
                    }
                    BasicTextField(value = note.body, onValueChange = { v -> data = data.copy(notes = data.notes.map {
                        if (it.id == note.id) it.copy(body = v, updatedAt = System.currentTimeMillis()) else it
                    }) }, modifier = Modifier.fillMaxSize().padding(top = 10.dp),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        decorationBox = { inner -> Box { if (note.body.isEmpty()) Text("Tulis catatan dengan Markdown...", color = MaterialTheme.colorScheme.outline); inner() } })
                }
            }
        } else Column(Modifier.fillMaxSize().padding(inset)) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Cari catatan...") }, leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true, shape = RoundedCornerShape(18.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(folderId == "all" && !archived && !trash, { folderId = "all"; archived = false; trash = false }, label = { Text("Semua") })
                data.folders.forEach { folder -> FilterChip(folderId == folder.id && !archived && !trash,
                    { folderId = folder.id; archived = false; trash = false }, label = { Text(folder.name) }) }
                FilterChip(archived, { archived = !archived; trash = false }, label = { Text("Arsip") })
                FilterChip(trash, { trash = !trash; archived = false }, label = { Text("Sampah") })
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${visible.size} catatan", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { folderDialog = true }) { Icon(Icons.Default.Add, null); Text("Folder") }
            }
            if (visible.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (trash) "Sampah masih kosong" else if (archived) "Belum ada arsip" else "Belum ada catatan. Tekan + untuk mulai.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp, bottom = 90.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(visible, key = { it.id }) { item ->
                    var menu by remember { mutableStateOf(false) }
                    Surface(Modifier.fillMaxWidth().clickable { editing = item.id },
                        shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(item.title.ifBlank { "Tanpa judul" }, Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (item.pinned) Icon(Icons.Default.PushPin, null, tint = MaterialTheme.colorScheme.primary)
                                Box {
                                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Opsi") }
                                    DropdownMenu(menu, { menu = false }) {
                                        if (item.deleted) DropdownMenuItem(text = { Text("Pulihkan") }, leadingIcon = { Icon(Icons.Default.Restore, null) },
                                            onClick = { menu = false; data = data.copy(notes = data.notes.map { if (it.id == item.id) it.copy(deleted = false, archived = false) else it }) })
                                        else {
                                            DropdownMenuItem(text = { Text(if (item.pinned) "Lepas pin" else "Sematkan") }, leadingIcon = { Icon(Icons.Default.PushPin, null) },
                                                onClick = { menu = false; data = data.copy(notes = data.notes.map { if (it.id == item.id) it.copy(pinned = !it.pinned) else it }) })
                                            DropdownMenuItem(text = { Text(if (item.archived) "Keluarkan dari arsip" else "Arsipkan") }, leadingIcon = { Icon(Icons.Default.Archive, null) },
                                                onClick = { menu = false; data = data.copy(notes = data.notes.map { if (it.id == item.id) it.copy(archived = !it.archived) else it }) })
                                            DropdownMenuItem(text = { Text("Pindahkan ke sampah") }, leadingIcon = { Icon(Icons.Default.Delete, null) },
                                                onClick = { menu = false; data = data.copy(notes = data.notes.map { if (it.id == item.id) it.copy(deleted = true) else it }) })
                                        }
                                    }
                                }
                            }
                            if (item.body.isNotBlank()) Text(item.body, maxLines = 3, overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(data.folders.firstOrNull { it.id == item.folderId }?.name ?: "Catatan",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
        }
    }
    if (folderDialog) AlertDialog(onDismissRequest = { folderDialog = false; newFolder = "" },
        title = { Text("Buat folder") },
        text = { OutlinedTextField(newFolder, { newFolder = it }, label = { Text("Nama folder") }, singleLine = true) },
        confirmButton = { TextButton(onClick = {
            if (newFolder.isNotBlank()) {
                val folder = LocalNoteFolder(UUID.randomUUID().toString(), newFolder.trim())
                data = data.copy(folders = data.folders + folder); folderId = folder.id
            }
            folderDialog = false; newFolder = ""
        }) { Text("Buat") } },
        dismissButton = { TextButton(onClick = { folderDialog = false }) { Text("Batal") } })
}
