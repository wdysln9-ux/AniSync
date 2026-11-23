package com.anisync.android.presentation.notes

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import com.anisync.android.presentation.components.AsyncRichTextRenderer
import com.anisync.android.domain.ActivityType
import com.anisync.android.domain.UserActivity
import com.anisync.android.presentation.activity.ActivityHeaderBlock
import com.anisync.android.presentation.components.richtext.RichTextInputScreen
import com.anisync.android.presentation.profile.components.ActivityCard
import com.anisync.android.presentation.profile.components.ActivityPostBody
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
    var editingFolderId by remember { mutableStateOf<String?>(null) }
    var folderMenuId by remember { mutableStateOf<String?>(null) }
    var movingNoteId by remember { mutableStateOf<String?>(null) }
    var sortNewestFirst by remember { mutableStateOf(true) }
    var backupMenu by remember { mutableStateOf(false) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            treeUri = uri
            prefs.edit().putString(TREE, uri.toString()).apply()
            val existingFile = DocumentFile.fromTreeUri(context, uri)?.findFile(BACKUP_FILE)
            val restored = runCatching {
                existingFile?.uri?.let { fileUri ->
                    context.contentResolver.openInputStream(fileUri)?.bufferedReader()?.use {
                        notesJson.decodeFromString<LocalNotesBackup>(it.readText())
                    }
                }
            }.getOrNull()
            if (restored != null && data.notes.isEmpty()) {
                data = restored
                status = "Cadangan lama ditemukan • catatan dipulihkan"
            } else {
                status = if (saveTree(context, uri, data)) "Cadangan aktif" else "Folder dipilih, tetapi penulisan cadangan gagal"
            }
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
        val savedToTree = withContext(Dispatchers.IO) {
            prefs.edit().putString(PAYLOAD, notesJson.encodeToString(snapshot)).apply()
            treeUri?.let { uri -> saveTree(context, uri, snapshot) }
        }
        if (treeUri != null) status = if (savedToTree == true) "Tersimpan • cadangan diperbarui" else "Tersimpan lokal • cadangan gagal"
    }

    val note = data.notes.firstOrNull { it.id == editing }
    var preview by remember(editing) { mutableStateOf(true) }
    BackHandler(enabled = note != null) { editing = null }
    val visible = remember(data, folderId, query, archived, trash, sortNewestFirst) {
        data.notes.filter {
            (if (trash) it.deleted else if (archived) it.archived && !it.deleted else !it.archived && !it.deleted) &&
                (folderId == "all" || it.folderId == folderId) &&
                (query.isBlank() || it.title.contains(query, true) || it.body.contains(query, true))
        }.sortedWith(compareByDescending<LocalNote> { it.pinned }.thenBy { if (sortNewestFirst) -it.updatedAt else it.updatedAt })
    }

    Scaffold(
        topBar = {
            if (note == null || preview) TopAppBar(
                title = { Column { Text(if (note == null) "Catatan" else if (preview) "Catatan" else "Edit catatan", fontWeight = FontWeight.Bold)
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
            val item = LocalNote(folderId = folderId.takeUnless { it == "all" } ?: data.folders.firstOrNull()?.id ?: "default")
            data = data.copy(notes = listOf(item) + data.notes); editing = item.id
        }) { Icon(Icons.Default.NoteAdd, "Buat catatan") } }
    ) { inset ->
        if (note != null) {
            if (!preview) {
                key(note.id) {
                    RichTextInputScreen(
                        title = "Edit catatan",
                        placeholder = "Tulis catatan dengan Markdown...",
                        initialBody = note.body,
                        isSubmitting = false,
                        submitLabel = "Simpan",
                        onBodyChange = { body ->
                            data = data.copy(notes = data.notes.map {
                                if (it.id == note.id && it.body != body) it.copy(body = body, updatedAt = System.currentTimeMillis()) else it
                            })
                        },
                        onSubmit = { body ->
                            data = data.copy(notes = data.notes.map {
                                if (it.id == note.id) it.copy(body = body, updatedAt = System.currentTimeMillis()) else it
                            })
                            preview = true
                        },
                        onDismiss = { preview = true },
                        hasExternalUnsavedChanges = false,
                        headerSlot = {
                            androidx.compose.foundation.text.BasicTextField(
                                value = note.title,
                                onValueChange = { value ->
                                    data = data.copy(notes = data.notes.map {
                                        if (it.id == note.id) it.copy(title = value, updatedAt = System.currentTimeMillis()) else it
                                    })
                                },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                                textStyle = MaterialTheme.typography.headlineSmall.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Bold
                                ),
                                decorationBox = { inner ->
                                    Box {
                                        if (note.title.isEmpty()) Text("Judul catatan", color = MaterialTheme.colorScheme.outline)
                                        inner()
                                    }
                                }
                            )
                            Text(
                                "Dibuat " + java.text.SimpleDateFormat("d MMM yyyy · HH:mm", java.util.Locale("id", "ID")).format(java.util.Date(note.createdAt)),
                                modifier = Modifier.padding(horizontal = 16.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                            Text(
                                "Diperbarui " + java.text.SimpleDateFormat("d MMM yyyy · HH:mm", java.util.Locale("id", "ID")).format(java.util.Date(note.updatedAt)),
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                            HorizontalDivider(modifier = Modifier.padding(top = 10.dp))
                        }
                    )
                }
            } else {
                Column(
                    Modifier.fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(inset)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { preview = false }) { Text("Edit catatan") }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = {
                            data = data.copy(notes = data.notes.map {
                                if (it.id == note.id) it.copy(pinned = !it.pinned) else it
                            })
                        }) {
                            Icon(Icons.Default.PushPin, "Sematkan", tint = if (note.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        var noteActionsExpanded by remember(note.id) { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { noteActionsExpanded = true }) { Icon(Icons.Default.MoreVert, "Opsi catatan") }
                            DropdownMenu(noteActionsExpanded, { noteActionsExpanded = false }) {
                                DropdownMenuItem(text = { Text("Arsipkan") }, leadingIcon = { Icon(Icons.Default.Archive, null) },
                                    onClick = {
                                        noteActionsExpanded = false
                                        data = data.copy(notes = data.notes.map { if (it.id == note.id) it.copy(archived = true) else it })
                                        editing = null
                                    })
                                DropdownMenuItem(text = { Text("Pindahkan ke sampah") }, leadingIcon = { Icon(Icons.Default.Delete, null) },
                                    onClick = {
                                        noteActionsExpanded = false
                                        data = data.copy(notes = data.notes.map { if (it.id == note.id) it.copy(deleted = true) else it })
                                        editing = null
                                    })
                            }
                        }
                    }
                    Column(
                        modifier = Modifier.fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface)
                            .verticalScroll(rememberScrollState())
                    ) {
                        ActivityHeaderBlock(
                            authorName = "Catatan lokal",
                            authorAvatarUrl = null,
                            authorDonatorTier = 0,
                            authorDonatorBadge = null,
                            createdAt = note.createdAt / 1000L,
                            isMessage = false,
                            isPrivate = false,
                            recipientName = null,
                            recipientAvatarUrl = null,
                            onUserClick = {}
                        )
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = note.title.ifBlank { "Tanpa judul" },
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "Dibuat " + java.text.SimpleDateFormat("d MMM yyyy · HH:mm", java.util.Locale("id", "ID")).format(java.util.Date(note.createdAt)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                            Text(
                                "Diperbarui " + java.text.SimpleDateFormat("d MMM yyyy · HH:mm", java.util.Locale("id", "ID")).format(java.util.Date(note.updatedAt)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                            HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp))
                            if (note.body.isBlank()) {
                                Text("Belum ada isi.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                ActivityPostBody(
                                    html = note.body,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        } else Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(inset)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)) {
                Text(
                    text = if (trash) "Sampah" else if (archived) "Arsip" else "Semua catatan",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (trash) "Catatan yang dihapus bisa dipulihkan dari sini." else if (archived) "Catatan yang kamu simpan untuk nanti." else "Semua ide dan hal pentingmu, tersusun rapi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Cari catatan...") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedBorderColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    focusedBorderColor = MaterialTheme.colorScheme.primary
                )
            )
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(folderId == "all" && !archived && !trash, { folderId = "all"; archived = false; trash = false }, label = { Text("Semua") })
                data.folders.forEach { folder ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(folderId == folder.id && !archived && !trash,
                            { folderId = folder.id; archived = false; trash = false }, label = { Text(folder.name) })
                        Box {
                            TextButton(onClick = { folderMenuId = folder.id }) { Text("⋮") }
                            DropdownMenu(expanded = folderMenuId == folder.id, onDismissRequest = { folderMenuId = null }) {
                                DropdownMenuItem(text = { Text("Ganti nama tab") }, onClick = {
                                    folderMenuId = null; editingFolderId = folder.id; newFolder = folder.name; folderDialog = true
                                })
                                if (data.folders.size > 1) DropdownMenuItem(text = { Text("Hapus tab") }, onClick = {
                                    folderMenuId = null
                                    val fallback = data.folders.firstOrNull { it.id != folder.id }?.id ?: "default"
                                    data = data.copy(
                                        folders = data.folders.filterNot { it.id == folder.id },
                                        notes = data.notes.map { if (it.folderId == folder.id) it.copy(folderId = fallback) else it }
                                    )
                                    if (folderId == folder.id) folderId = "all"
                                })
                            }
                        }
                    }
                }
                FilterChip(archived, { archived = !archived; trash = false }, label = { Text("Arsip") })
                FilterChip(trash, { trash = !trash; archived = false }, label = { Text("Sampah") })
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${visible.size} catatan", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { editingFolderId = null; newFolder = ""; folderDialog = true }) { Icon(Icons.Default.Add, null); Text("Folder") }
                TextButton(onClick = { sortNewestFirst = !sortNewestFirst }) { Text(if (sortNewestFirst) "Terbaru" else "Terlama") }
            }
            if (visible.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 32.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = if (trash) Icons.Default.Delete else Icons.Default.NoteAdd,
                        contentDescription = null,
                        modifier = Modifier.size(54.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = if (trash) "Sampah masih kosong" else if (archived) "Belum ada arsip" else if (query.isNotBlank()) "Catatan tidak ditemukan" else "Belum ada catatan",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (trash) "Catatan yang kamu hapus akan muncul di sini." else if (archived) "Catatan yang diarsipkan akan tersimpan di sini." else if (query.isNotBlank()) "Coba kata kunci lain." else "Mulai tulis ide atau hal penting yang ingin kamu simpan.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!trash && !archived && query.isBlank()) {
                        FilledTonalButton(onClick = {
                            val item = LocalNote(folderId = folderId.takeUnless { it == "all" } ?: data.folders.firstOrNull()?.id ?: "default")
                            data = data.copy(notes = listOf(item) + data.notes)
                            editing = item.id
                        }) { Icon(Icons.Default.NoteAdd, null); Text("Buat catatan") }
                    }
                }
            } else LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 104.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(visible, key = { it.id }) { item ->
                    var menu by remember { mutableStateOf(false) }
                    val activityPost = remember(item) {
                        UserActivity(
                            id = item.id.hashCode(),
                            type = ActivityType.TEXT,
                            timestamp = item.createdAt,
                            text = item.body.ifBlank { " " },
                            userName = "Catatan lokal",
                            isPinned = item.pinned
                        )
                    }
                    Box(modifier = Modifier.fillMaxWidth()) {
                        ActivityCard(
                            activity = activityPost,
                            onClick = { editing = item.id },
                            showAuthor = true,
                            showEngagement = false,
                            showOverflow = false,
                            pinnedLabel = item.pinned,
                            postTitle = item.title.ifBlank { "Tanpa judul" },
                            postFooter = {
                                HorizontalDivider(
                                    modifier = Modifier.padding(top = 12.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        data.folders.firstOrNull { it.id == item.folderId }?.name ?: "Catatan",
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            "Dibuat " + java.text.SimpleDateFormat("d MMM yyyy · HH:mm", java.util.Locale("id", "ID")).format(java.util.Date(item.createdAt)),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            "Diperbarui " + java.text.SimpleDateFormat("d MMM yyyy · HH:mm", java.util.Locale("id", "ID")).format(java.util.Date(item.updatedAt)),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        )
                        Box(modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp)) {
                            IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.MoreVert, "Opsi catatan")
                            }
                            DropdownMenu(menu, { menu = false }) {
                                if (item.deleted) {
                                    DropdownMenuItem(text = { Text("Pulihkan") }, leadingIcon = { Icon(Icons.Default.Restore, null) },
                                        onClick = { menu = false; data = data.copy(notes = data.notes.map { if (it.id == item.id) it.copy(deleted = false, archived = false) else it }) })
                                    DropdownMenuItem(text = { Text("Hapus permanen") }, leadingIcon = { Icon(Icons.Default.Delete, null) },
                                        onClick = { menu = false; data = data.copy(notes = data.notes.filterNot { it.id == item.id }) })
                                } else {
                                    DropdownMenuItem(text = { Text(if (item.pinned) "Lepas pin" else "Sematkan") }, leadingIcon = { Icon(Icons.Default.PushPin, null) },
                                        onClick = { menu = false; data = data.copy(notes = data.notes.map { if (it.id == item.id) it.copy(pinned = !it.pinned) else it }) })
                                    DropdownMenuItem(text = { Text(if (item.archived) "Keluarkan dari arsip" else "Arsipkan") }, leadingIcon = { Icon(Icons.Default.Archive, null) },
                                        onClick = { menu = false; data = data.copy(notes = data.notes.map { if (it.id == item.id) it.copy(archived = !it.archived) else it }) })
                                    if (!archived) DropdownMenuItem(text = { Text("Pindahkan ke folder…") }, onClick = { menu = false; movingNoteId = item.id })
                                    DropdownMenuItem(text = { Text("Pindahkan ke sampah") }, leadingIcon = { Icon(Icons.Default.Delete, null) },
                                        onClick = { menu = false; data = data.copy(notes = data.notes.map { if (it.id == item.id) it.copy(deleted = true) else it }) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (movingNoteId != null) AlertDialog(
        onDismissRequest = { movingNoteId = null },
        title = { Text("Pindahkan catatan ke tab") },
        text = {
            Column {
                data.folders.forEach { folder ->
                    TextButton(onClick = {
                        val id = movingNoteId
                        data = data.copy(notes = data.notes.map { if (it.id == id) it.copy(folderId = folder.id, updatedAt = System.currentTimeMillis()) else it })
                        movingNoteId = null
                    }, modifier = Modifier.fillMaxWidth()) { Text(folder.name) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { movingNoteId = null }) { Text("Batal") } }
    )
    if (folderDialog) AlertDialog(onDismissRequest = { folderDialog = false; newFolder = ""; editingFolderId = null },
        title = { Text(if (editingFolderId == null) "Buat tab" else "Ganti nama tab") },
        text = { OutlinedTextField(newFolder, { newFolder = it }, label = { Text("Nama folder") }, singleLine = true) },
        confirmButton = { TextButton(onClick = {
            if (newFolder.isNotBlank()) {
                val currentId = editingFolderId
                if (currentId == null) {
                    val folder = LocalNoteFolder(UUID.randomUUID().toString(), newFolder.trim())
                    data = data.copy(folders = data.folders + folder)
                    folderId = folder.id
                } else {
                    data = data.copy(folders = data.folders.map { if (it.id == currentId) it.copy(name = newFolder.trim()) else it })
                }
            }
            folderDialog = false; newFolder = ""; editingFolderId = null
        }) { Text(if (editingFolderId == null) "Buat" else "Simpan") } },
        dismissButton = { TextButton(onClick = { folderDialog = false; editingFolderId = null; newFolder = "" }) { Text("Batal") } })
}
