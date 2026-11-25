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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import com.anisync.android.presentation.components.rememberParsedRichText
import com.anisync.android.presentation.forum.components.ThreadBodyItem
import com.anisync.android.presentation.util.LocalMainNavBarInset
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.Lock
import com.anisync.android.domain.ActivityType
import com.anisync.android.domain.UserActivity
import com.anisync.android.presentation.components.richtext.RichTextInputScreen
import com.anisync.android.presentation.profile.components.ActivityCard
import com.anisync.android.presentation.components.ConnectedToggleSegment
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import androidx.compose.ui.layout.ContentScale
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
    val favorite: Boolean = false, val folderId: String = "default", val createdAt: Long = System.currentTimeMillis(),
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
fun LocalNotesScreen(onBackClick: () -> Unit, onOpenNote: (String, Boolean) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var data by remember { mutableStateOf(loadNotes(context)) }
    var treeUri by remember { mutableStateOf(prefs.getString(TREE, null)?.let(Uri::parse)) }
    var status by remember { mutableStateOf("Catatan tersimpan di perangkat") }
    var folderId by remember { mutableStateOf("all") }
    var query by remember { mutableStateOf("") }
    var archived by remember { mutableStateOf(false) }
    var trash by remember { mutableStateOf(false) }
    var folderDialog by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf("") }
    var editingFolderId by remember { mutableStateOf<String?>(null) }
    var folderMenuId by remember { mutableStateOf<String?>(null) }
    var movingNoteId by remember { mutableStateOf<String?>(null) }
    var sortNewestFirst by remember { mutableStateOf(true) }
    var favoriteOnly by remember { mutableStateOf(false) }
    var bottomDestination by remember { mutableStateOf("home") }
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

    val visible = remember(data, folderId, query, archived, trash, sortNewestFirst, favoriteOnly) {
        data.notes.filter {
            (if (trash) it.deleted else if (archived) it.archived && !it.deleted else !it.archived && !it.deleted) &&
                (folderId == "all" || it.folderId == folderId) &&
                (!favoriteOnly || it.favorite) &&
                (query.isBlank() || it.title.contains(query, true) || it.body.contains(query, true))
        }.sortedWith(compareByDescending<LocalNote> { it.pinned }.thenBy { if (sortNewestFirst) -it.updatedAt else it.updatedAt })
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {},
        floatingActionButton = {
            if (!trash) {
                Box(
                    modifier = Modifier
                        .navigationBarsPadding()
                ) {
                    FloatingActionButton(onClick = {
                        val item = LocalNote(folderId = folderId.takeUnless { it == "all" } ?: data.folders.firstOrNull()?.id ?: "default")
                        data = data.copy(notes = listOf(item) + data.notes)
                        prefs.edit().putString(PAYLOAD, notesJson.encodeToString(data)).apply()
                        onOpenNote(item.id, true)
                    }) {
                        Icon(Icons.Default.NoteAdd, "Buat catatan")
                    }
                }
            }
        }
    ) { inset ->
        Column(
            Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(inset)
        ) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding()
                    .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Cari catatan") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        unfocusedBorderColor = MaterialTheme.colorScheme.surfaceContainer,
                        focusedBorderColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                )
                Box {
                    IconButton(onClick = { backupMenu = true }) {
                        Icon(Icons.Default.MoreVert, "Menu lainnya")
                    }
                    DropdownMenu(backupMenu, { backupMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(if (favoriteOnly) "Tampilkan semua catatan" else "Favorit") },
                            leadingIcon = { Icon(Icons.Default.Star, null) },
                            onClick = { backupMenu = false; favoriteOnly = !favoriteOnly; archived = false; trash = false; folderId = "all" }
                        )
                        DropdownMenuItem(text = { Text(if (archived) "Kembali ke catatan" else "Arsip") }, leadingIcon = { Icon(Icons.Default.Archive, null) }, onClick = { backupMenu = false; archived = !archived; trash = false; favoriteOnly = false; folderId = "all" })
                        DropdownMenuItem(text = { Text(if (trash) "Kembali ke catatan" else "Sampah") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { backupMenu = false; trash = !trash; archived = false; favoriteOnly = false; folderId = "all" })
                        DropdownMenuItem(text = { Text("Pilih folder cadangan") }, leadingIcon = { Icon(Icons.Default.Folder, null) }, onClick = { backupMenu = false; folderPicker.launch(null) })
                        DropdownMenuItem(text = { Text("Pulihkan dari file") }, leadingIcon = { Icon(Icons.Default.Restore, null) }, onClick = { backupMenu = false; importPicker.launch(arrayOf("application/json", "text/json")) })
                        DropdownMenuItem(text = { Text("Ekspor cadangan") }, leadingIcon = { Icon(Icons.Default.Download, null) }, onClick = { backupMenu = false; exportPicker.launch(BACKUP_FILE) })
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = folderId == "all" && !favoriteOnly && !archived && !trash,
                    onClick = { folderId = "all"; favoriteOnly = false; archived = false; trash = false },
                    label = { Text("Semua") }
                )
                data.folders.forEach { folder ->
                    FilterChip(
                        selected = folderId == folder.id,
                        onClick = { folderId = if (folderId == folder.id) "all" else folder.id; favoriteOnly = false; archived = false; trash = false },
                        label = { Text(folder.name) },
                        leadingIcon = { Icon(Icons.Default.Folder, null, modifier = Modifier.size(15.dp)) }
                    )
                }
                FilterChip(
                    selected = false,
                    onClick = { editingFolderId = null; newFolder = ""; folderDialog = true },
                    label = { Text("Buat folder +") }
                )
            }
            if (visible.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(if (trash) Icons.Default.Delete else Icons.Default.NoteAdd, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
                    Text(
                        if (trash) "Sampah masih kosong" else if (archived) "Belum ada arsip" else if (favoriteOnly) "Belum ada favorit" else if (query.isNotBlank()) "Catatan tidak ditemukan" else "Belum ada catatan",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (query.isNotBlank()) "Coba kata kunci lain." else "Catatan yang kamu simpan akan muncul di sini.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(visible, key = { it.id }) { item ->
                        var menu by remember(item.id) { mutableStateOf(false) }
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable { onOpenNote(item.id, false) },
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer
                        ) {
                            Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        item.title.ifBlank { "Tanpa judul" },
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Box {
                                        IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
                                            Icon(Icons.Default.MoreVert, "Opsi catatan")
                                        }
                                        DropdownMenu(menu, { menu = false }) {
                                            if (item.deleted) {
                                                DropdownMenuItem(text = { Text("Pulihkan") }, leadingIcon = { Icon(Icons.Default.Restore, null) }, onClick = { menu = false; data = data.copy(notes = data.notes.map { if (it.id == item.id) it.copy(deleted = false, archived = false) else it }) })
                                                DropdownMenuItem(text = { Text("Hapus permanen") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { menu = false; data = data.copy(notes = data.notes.filterNot { it.id == item.id }) })
                                            } else {
                                                DropdownMenuItem(text = { Text(if (item.pinned) "Lepas pin" else "Sematkan") }, leadingIcon = { Icon(Icons.Default.PushPin, null) }, onClick = { menu = false; data = data.copy(notes = data.notes.map { if (it.id == item.id) it.copy(pinned = !it.pinned) else it }) })
                                                DropdownMenuItem(text = { Text(if (item.archived) "Keluarkan dari arsip" else "Arsipkan") }, leadingIcon = { Icon(Icons.Default.Archive, null) }, onClick = { menu = false; data = data.copy(notes = data.notes.map { if (it.id == item.id) it.copy(archived = !it.archived) else it }) })
                                                DropdownMenuItem(text = { Text("Pindahkan ke folder…") }, onClick = { menu = false; movingNoteId = item.id })
                                                DropdownMenuItem(text = { Text("Pindahkan ke sampah") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { menu = false; data = data.copy(notes = data.notes.map { if (it.id == item.id) it.copy(deleted = true) else it }) })
                                            }
                                        }
                                    }
                                }
                                val previewImage = remember(item.body) {
                                    Regex("""!\\[[^]]*]\\((https?://[^)]+)\\)|<img[^>]+src=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
                                        .find(item.body)?.let { match ->
                                            match.groupValues.getOrNull(1).orEmpty().ifBlank { match.groupValues.getOrNull(2).orEmpty() }
                                        }?.takeIf { it.isNotBlank() }
                                }
                                val snippet = item.body
                                    .replace(Regex("<[^>]*>"), " ")
                                    .replace(Regex("!\\[[^]]*]\\([^)]*\\)"), "Gambar")
                                    .replace(Regex("[#*_~`>]"), "")
                                    .replace(Regex("\\s+"), " ")
                                    .trim()
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (snippet.isNotBlank()) {
                                        Text(
                                            snippet,
                                            modifier = Modifier.weight(1f),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (previewImage != null) {
                                        AsyncImage(
                                            model = ImageRequest.Builder(LocalContext.current).data(previewImage).crossfade(true).build(),
                                            contentDescription = "Gambar catatan",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.padding(start = 10.dp).size(width = 56.dp, height = 64.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                        )
                                    }
                                }
                                Text(
                                    java.text.SimpleDateFormat("d MMM · HH:mm", java.util.Locale("id", "ID")).format(java.util.Date(item.updatedAt)),
                                    modifier = Modifier.padding(top = 8.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    if (movingNoteId != null) AlertDialog(
        onDismissRequest = { movingNoteId = null },
        title = { Text("Pindahkan catatan ke folder") },
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
        title = { Text(if (editingFolderId == null) "Buat folder" else "Ganti nama folder") },
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


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalNoteDetailScreenRoute(noteId: String, openInEditMode: Boolean = false, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var data by remember(noteId) { mutableStateOf(loadNotes(context)) }
    var editing by remember(noteId) { mutableStateOf(openInEditMode) }
    val backupTreeUri = remember { prefs.getString(TREE, null)?.let(Uri::parse) }
    val note = data.notes.firstOrNull { it.id == noteId }

    fun updateNote(transform: (LocalNote) -> LocalNote) {
        val updated = data.copy(notes = data.notes.map { if (it.id == noteId) transform(it) else it })
        data = updated
        prefs.edit().putString(PAYLOAD, notesJson.encodeToString(updated)).apply()
    }

    LaunchedEffect(data, backupTreeUri) {
        delay(500)
        withContext(Dispatchers.IO) {
            prefs.edit().putString(PAYLOAD, notesJson.encodeToString(data)).apply()
            backupTreeUri?.let { saveTree(context, it, data) }
        }
    }

    BackHandler(enabled = editing) { editing = false }

    if (note == null) {
        LaunchedEffect(noteId) { onBack() }
    } else if (editing) {
        key(note.id) {
            RichTextInputScreen(
                title = "Edit catatan",
                placeholder = "Tulis catatan dengan Markdown...",
                initialBody = note.body,
                isSubmitting = false,
                submitLabel = "Simpan",
                onBodyChange = { body ->
                    updateNote { if (it.body != body) it.copy(body = body, updatedAt = System.currentTimeMillis()) else it }
                },
                onSubmit = { body ->
                    updateNote { it.copy(body = body, updatedAt = System.currentTimeMillis()) }
                    editing = false
                },
                onDismiss = { editing = false },
                hasExternalUnsavedChanges = false,
                confirmDiscardChanges = false,
                headerSlot = {
                    androidx.compose.foundation.text.BasicTextField(
                        value = note.title,
                        onValueChange = { value -> updateNote { it.copy(title = value, updatedAt = System.currentTimeMillis()) } },
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
        LocalNoteDetailScreen(
            note = note,
            onBack = onBack,
            onEdit = { editing = true },
            onTogglePin = { updateNote { it.copy(pinned = !it.pinned) } },
            onArchive = {
                updateNote { it.copy(archived = true) }
                onBack()
            },
            onTrash = {
                updateNote { it.copy(deleted = true) }
                onBack()
            }
        )
    }
}

private fun bodyWithoutDuplicateNoteTitle(title: String, body: String): String {
    val normalizedTitle = title.trim()
    if (normalizedTitle.isBlank()) return body

    val trimmedBody = body.trimStart()
    val firstLine = trimmedBody.lineSequence().firstOrNull().orEmpty().trim()
    val markdownHeading = firstLine.replace(Regex("^#{1,6}\\s+"), "").trim()
    if (markdownHeading.equals(normalizedTitle, ignoreCase = true)) {
        return trimmedBody.substringAfter(firstLine).trimStart()
    }

    val htmlHeading = Regex(
        "^<h[1-6]\\b[^>]*>\\s*([^<]*?)\\s*</h[1-6]>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    ).find(trimmedBody)
    if (htmlHeading?.groupValues?.getOrNull(1)?.trim()?.equals(normalizedTitle, ignoreCase = true) == true) {
        return trimmedBody.removeRange(htmlHeading.range).trimStart()
    }
    return body
}

@Composable
private fun LocalNoteDetailScreen(
    note: LocalNote,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onTogglePin: () -> Unit,
    onArchive: () -> Unit,
    onTrash: () -> Unit
) {
    var menuExpanded by remember(note.id) { mutableStateOf(false) }
    val dateFormat = remember { java.text.SimpleDateFormat("d MMM yyyy · HH:mm", java.util.Locale("id", "ID")) }
    val displayedBody = remember(note.title, note.body) { bodyWithoutDuplicateNoteTitle(note.title, note.body) }
    val parsedBody = rememberParsedRichText(displayedBody)

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(52.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onTogglePin) {
                Icon(Icons.Default.PushPin, contentDescription = if (note.pinned) "Lepas pin" else "Sematkan",
                    tint = if (note.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "Edit catatan") }
            Box {
                IconButton(onClick = { menuExpanded = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Opsi catatan") }
                DropdownMenu(menuExpanded, { menuExpanded = false }) {
                    DropdownMenuItem(text = { Text("Arsipkan") }, leadingIcon = { Icon(Icons.Default.Archive, null) }, onClick = { menuExpanded = false; onArchive() })
                    DropdownMenuItem(text = { Text("Pindahkan ke sampah") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { menuExpanded = false; onTrash() })
                }
            }
        }
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            item {
                Text(note.title.ifBlank { "Catatan" }, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                if (note.pinned) {
                    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.PushPin, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                        Text(" Disematkan", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Text(
                    "${dateFormat.format(java.util.Date(note.createdAt))}  |  ${dateFormat.format(java.util.Date(note.updatedAt))}",
                    modifier = Modifier.padding(top = 12.dp, bottom = 24.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            parsedBody?.let { body ->
                item {
                    ThreadBodyItem(
                        body = body,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.25f
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}
