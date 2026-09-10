package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.db.entities.GrantedFolderEntity
import com.example.data.model.FileSystemItem
import com.example.ui.theme.ClaudeDanger
import com.example.ui.theme.ClaudeTerracotta
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun FileManagerView(
    currentPath: String,
    files: List<FileSystemItem>,
    isPinnedRoot: Boolean,
    hasStoragePermission: Boolean,
    standardShortcuts: Map<String, String>,
    onRequestStoragePermission: () -> Unit,
    onNavigateToDir: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onOpenFile: (FileSystemItem) -> Unit,
    onCreateFile: (fileName: String, content: String) -> Unit,
    onCreateFolder: (folderName: String) -> Unit,
    onRenameFile: (item: FileSystemItem, newName: String) -> Unit,
    onMoveFile: (item: FileSystemItem, destinationDir: String) -> Unit = { _, _ -> },
    onDeleteFile: (FileSystemItem) -> Unit,
    onPinCurrentDirectory: (String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    grantedFolders: List<GrantedFolderEntity> = emptyList(),
    onRequestNewFolderPermission: () -> Unit = {},
    onRevokeFolderPermission: (String) -> Unit = {}
) {
    var showNewFileDialog by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var renameTargetItem by remember { mutableStateOf<FileSystemItem?>(null) }
    var deleteConfirmItem by remember { mutableStateOf<FileSystemItem?>(null) }
    var moveTargetItem by remember { mutableStateOf<FileSystemItem?>(null) }
    var moveDestInput by remember { mutableStateOf("") }

    var newFileName by remember { mutableStateOf("") }
    var newFileContent by remember { mutableStateOf("") }
    var newFolderName by remember { mutableStateOf("") }
    var renameInputName by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }

    val dateFormat = remember { SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()) }

    val filteredFiles = remember(files, searchQuery) {
        if (searchQuery.isBlank()) files
        else files.filter { it.name.contains(searchQuery.trim(), ignoreCase = true) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Quick folder shortcuts
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Быстрый переход:",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )

            standardShortcuts.forEach { (title, path) ->
                val isCurrent = currentPath == path
                FilterChip(
                    selected = isCurrent,
                    onClick = { onNavigateToDir(path) },
                    label = { Text(title, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = ClaudeTerracotta,
                        selectedLabelColor = Color.White
                    )
                )
            }

            // Granted SAF Folders
            grantedFolders.forEach { folder ->
                val isCurrent = currentPath == folder.folderPath
                FilterChip(
                    selected = isCurrent,
                    onClick = { onNavigateToDir(folder.folderPath) },
                    label = { Text(folder.displayName, fontSize = 11.sp) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = ClaudeTerracotta,
                        selectedLabelColor = Color.White
                    )
                )
            }

            // Button to proactively add a new SAF folder
            AssistChip(
                onClick = onRequestNewFolderPermission,
                label = { Text("+ Папка (SAF)", fontSize = 11.sp) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = ClaudeTerracotta
                    )
                }
            )
        }

        // Path and Action toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                IconButton(
                    onClick = onNavigateUp,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "На уровень выше",
                        tint = ClaudeTerracotta
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                Column {
                    Text(
                        text = currentPath.substringAfterLast("/").ifEmpty { "Корень" },
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1
                    )
                    Text(
                        text = currentPath,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        maxLines = 1
                    )
                }
            }

            // Toolbar action buttons
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { onPinCurrentDirectory(currentPath) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (isPinnedRoot) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        contentDescription = "Закрепить директорию",
                        tint = if (isPinnedRoot) ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Обновить",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = {
                        newFolderName = ""
                        showNewFolderDialog = true
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CreateNewFolder,
                        contentDescription = "Новая папка",
                        tint = ClaudeTerracotta
                    )
                }

                IconButton(
                    onClick = {
                        newFileName = ""
                        newFileContent = ""
                        showNewFileDialog = true
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.NoteAdd,
                        contentDescription = "Новый файл",
                        tint = ClaudeTerracotta
                    )
                }
            }
        }

        // Search in directory
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Поиск в текущей папке...", fontSize = 12.sp) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Очистить", modifier = Modifier.size(16.dp))
                    }
                }
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )

        // File list
        if (filteredFiles.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = if (searchQuery.isNotEmpty()) "Ничего не найдено по запросу '$searchQuery'" else "Папка пуста",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(filteredFiles, key = { it.path }) { item ->
                    val shape = RoundedCornerShape(8.dp)

                    Surface(
                        shape = shape,
                        color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (item.isDirectory) {
                                    onNavigateToDir(item.path)
                                } else {
                                    onOpenFile(item)
                                }
                            }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            // File / Folder icon
                            Icon(
                                imageVector = when {
                                    item.isDirectory -> Icons.Default.Folder
                                    item.extension in listOf("sh", "bash", "py", "kt", "js", "ts", "json", "xml", "html") -> Icons.Default.Code
                                    item.extension in listOf("jpg", "jpeg", "png", "webp", "gif") -> Icons.Default.Image
                                    item.extension in listOf("mp3", "wav", "flac", "ogg") -> Icons.Default.AudioFile
                                    item.extension in listOf("mp4", "mkv", "avi") -> Icons.Default.VideoFile
                                    item.extension in listOf("zip", "rar", "tar", "gz") -> Icons.Default.Archive
                                    item.extension in listOf("pdf", "doc", "docx", "txt", "md") -> Icons.Default.Description
                                    else -> Icons.Default.InsertDriveFile
                                },
                                contentDescription = null,
                                tint = if (item.isDirectory) ClaudeTerracotta else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.name,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                    maxLines = 1
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = if (item.isDirectory) "${item.childCount} элем." else item.formattedSize,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    )
                                    Text(
                                        text = "•",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    )
                                    Text(
                                        text = dateFormat.format(Date(item.lastModified)),
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    )
                                }
                            }

                            // Edit content button — ручное редактирование файла пользователем
                            if (!item.isDirectory) {
                                IconButton(
                                    onClick = { onOpenFile(item) },
                                    modifier = Modifier.size(30.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.EditNote,
                                        contentDescription = "Редактировать содержимое",
                                        tint = ClaudeTerracotta,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            // Move button — переместить файл/папку в другую директорию
                            IconButton(
                                onClick = {
                                    moveTargetItem = item
                                    moveDestInput = currentPath
                                },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DriveFileMove,
                                    contentDescription = "Переместить",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            // Rename button
                            IconButton(
                                onClick = {
                                    renameTargetItem = item
                                    renameInputName = item.name
                                },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DriveFileRenameOutline,
                                    contentDescription = "Переименовать",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            // Delete button
                            IconButton(
                                onClick = { deleteConfirmItem = item },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = "Удалить",
                                    tint = ClaudeDanger,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Delete Confirmation Dialog
    if (deleteConfirmItem != null) {
        val target = deleteConfirmItem!!
        AlertDialog(
            onDismissRequest = { deleteConfirmItem = null },
            title = {
                Text(
                    text = if (target.isDirectory) "Удалить папку?" else "Удалить файл?",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(
                        text = "Вы действительно хотите безвозвратно удалить:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = target.path,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            color = ClaudeDanger
                        )
                    )
                    if (target.isDirectory && target.childCount > 0) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Внимание: папка содержит ${target.childCount} вложенных элементов. Все они будут удалены.",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = ClaudeDanger
                            )
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteFile(target)
                        deleteConfirmItem = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeDanger)
                ) {
                    Text("Удалить")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmItem = null }) {
                    Text("Отмена")
                }
            }
        )
    }

    // Move dialog — выбор папки назначения
    if (moveTargetItem != null) {
        AlertDialog(
            onDismissRequest = { moveTargetItem = null },
            title = { Text("Переместить", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Куда переместить «${moveTargetItem!!.name}»:",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = moveDestInput,
                        onValueChange = { moveDestInput = it },
                        label = { Text("Путь к папке назначения") },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "Быстрый выбор:",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        standardShortcuts.forEach { (label, path) ->
                            AssistChip(
                                onClick = { moveDestInput = path },
                                label = { Text(label, fontSize = 10.sp) }
                            )
                        }
                        grantedFolders.forEach { folder ->
                            AssistChip(
                                onClick = { moveDestInput = folder.folderPath },
                                label = { Text(folder.displayName, fontSize = 10.sp) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val dest = moveDestInput.trim()
                        if (dest.isNotEmpty()) {
                            onMoveFile(moveTargetItem!!, dest)
                            moveTargetItem = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                ) {
                    Text("Переместить")
                }
            },
            dismissButton = {
                TextButton(onClick = { moveTargetItem = null }) {
                    Text("Отмена", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Rename Dialog
    if (renameTargetItem != null) {
        val target = renameTargetItem!!
        AlertDialog(
            onDismissRequest = { renameTargetItem = null },
            title = { Text("Переименовать", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = renameInputName,
                    onValueChange = { renameInputName = it },
                    label = { Text("Новое имя") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (renameInputName.isNotBlank() && renameInputName != target.name) {
                            onRenameFile(target, renameInputName.trim())
                        }
                        renameTargetItem = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                ) {
                    Text("Сохранить")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTargetItem = null }) {
                    Text("Отмена")
                }
            }
        )
    }

    // New File Dialog
    if (showNewFileDialog) {
        AlertDialog(
            onDismissRequest = { showNewFileDialog = false },
            title = { Text("Создать новый файл", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newFileName,
                        onValueChange = { newFileName = it },
                        label = { Text("Имя файла") },
                        placeholder = { Text("script.sh или note.txt") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = newFileContent,
                        onValueChange = { newFileContent = it },
                        label = { Text("Содержимое (опционально)") },
                        placeholder = { Text("Текст или код...") },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newFileName.isNotBlank()) {
                            onCreateFile(newFileName.trim(), newFileContent)
                            showNewFileDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                ) {
                    Text("Создать")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewFileDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }

    // New Folder Dialog
    if (showNewFolderDialog) {
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            title = { Text("Создать новую папку", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text("Имя папки") },
                    placeholder = { Text("MyProjects, logs, etc.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newFolderName.isNotBlank()) {
                            onCreateFolder(newFolderName.trim())
                            showNewFolderDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                ) {
                    Text("Создать")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }
}
