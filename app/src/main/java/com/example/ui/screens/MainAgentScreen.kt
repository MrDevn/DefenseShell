package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.example.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.*
import com.example.ui.MainViewModel
import com.example.ui.components.*
import com.example.ui.theme.*
import kotlinx.coroutines.launch

enum class AppNavigationTab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    CHAT("Чат", Icons.Default.ChatBubbleOutline),
    TERMINAL("Терминал", Icons.Default.Terminal),
    FILES("Файлы", Icons.Default.FolderOpen),
    REPOS("Репо", Icons.Default.CloudQueue),
    AUDIT_LOGS("Аудит", Icons.Default.History)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAgentScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()

    var activeTab by remember { mutableStateOf(AppNavigationTab.CHAT) }
    var showCustomConnectionDialog by remember { mutableStateOf(false) }
    var showModeSelectionDialog by remember { mutableStateOf(false) }

    val customConnections by viewModel.customConnections.collectAsStateWithLifecycle()
    val activeConnection by viewModel.activeConnection.collectAsStateWithLifecycle()
    val hasStoragePermission by viewModel.hasStoragePermission.collectAsStateWithLifecycle()

    val workingDir by viewModel.currentWorkingDir.collectAsStateWithLifecycle()
    val pinnedDir by viewModel.pinnedDirectory.collectAsStateWithLifecycle()
    val isGenerating by viewModel.isGenerating.collectAsStateWithLifecycle()
    val messages by viewModel.currentMessages.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val activeConvId by viewModel.activeConversationId.collectAsStateWithLifecycle()
    val fileItems by viewModel.fileItems.collectAsStateWithLifecycle()
    val recentLogs by viewModel.recentCommandLogs.collectAsStateWithLifecycle()
    val recentFileLogs by viewModel.recentFileLogs.collectAsStateWithLifecycle()
    val pendingDanger by viewModel.pendingDangerousArtifact.collectAsStateWithLifecycle()
    val activeArtifact by viewModel.activeArtifact.collectAsStateWithLifecycle()
    val agentStage by viewModel.agentStage.collectAsStateWithLifecycle()
    val thinkingText by viewModel.thinkingText.collectAsStateWithLifecycle()
    val githubLogin by viewModel.githubLogin.collectAsStateWithLifecycle()
    val githubRepos by viewModel.githubRepos.collectAsStateWithLifecycle()
    val githubDownloaded by viewModel.githubDownloaded.collectAsStateWithLifecycle()
    val githubStatus by viewModel.githubStatus.collectAsStateWithLifecycle()
    val githubBusy by viewModel.githubBusy.collectAsStateWithLifecycle()

    val pendingFolderPermission by viewModel.pendingFolderPermission.collectAsStateWithLifecycle()
    val operationMode by viewModel.operationMode.collectAsStateWithLifecycle()
    val grantedFolders by viewModel.grantedFolders.collectAsStateWithLifecycle()

    // Activity Result Launcher for Storage Access Framework (SAF) folder picker
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.onFolderPermissionGranted(uri, pendingFolderPermission)
        } else {
            viewModel.dismissFolderPermission()
        }
    }

    // Auto-refresh storage permission when returning to the app from system settings
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.checkStoragePermission()
                viewModel.refreshFiles()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Folder Permission Dialog (SAF like UserLand)
    if (pendingFolderPermission != null) {
        FolderPermissionDialog(
            folderPath = pendingFolderPermission!!,
            onPickFolder = {
                folderPickerLauncher.launch(null)
            },
            onDismiss = {
                viewModel.dismissFolderPermission()
            }
        )
    }

    // Agent Mode Selection Dialog (Safety vs Extra)
    if (showModeSelectionDialog) {
        ModeSelectionDialog(
            currentMode = operationMode,
            onModeSelected = { mode ->
                viewModel.setOperationMode(mode)
            },
            onDismiss = { showModeSelectionDialog = false }
        )
    }

    // Safety Confirmation Dialog for destructive operations
    if (pendingDanger != null) {
        DangerousConfirmationDialog(
            reason = pendingDanger!!.dangerReason ?: "Обнаружена потенциально деструктивная операция.",
            commandOrPath = pendingDanger!!.command ?: pendingDanger!!.targetPath ?: pendingDanger!!.content,
            onConfirm = { viewModel.confirmDangerousArtifact() },
            onDismiss = { viewModel.dismissDangerousDialog() }
        )
    }

    // Custom Connection Modal Dialog
    if (showCustomConnectionDialog) {
        CustomConnectionDialog(
            connections = customConnections,
            activeConnection = activeConnection,
            onSelectConnection = { conn ->
                viewModel.selectActiveConnection(conn)
                showCustomConnectionDialog = false
            },
            onSaveConnection = { conn ->
                viewModel.saveCustomConnection(conn)
            },
            onDeleteConnection = { id ->
                viewModel.deleteCustomConnection(id)
            },
            onTestConnection = { conn ->
                viewModel.testConnection(conn)
            },
            onDismiss = { showCustomConnectionDialog = false }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(300.dp),
                drawerContainerColor = MaterialTheme.colorScheme.surface
            ) {
                // Drawer Header
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Круглый логотип в меню
                        Image(
                            painter = painterResource(R.drawable.logo),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "CodeStudio",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "AI-агент файловой системы и терминала",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // New Session Button
                    Button(
                        onClick = {
                            viewModel.startNewConversation()
                            coroutineScope.launch { drawerState.close() }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("new_chat_button"),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ClaudeTerracotta,
                            contentColor = Color.White
                        )
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "Новая сессия", fontWeight = FontWeight.SemiBold)
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Pinned Directory Card
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = null,
                                tint = ClaudeTerracotta,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Рабочая папка",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 10.sp
                                    )
                                )
                                Text(
                                    text = workingDir,
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    ),
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

                // Sessions List
                Text(
                    text = "СЕССИИ",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 1.sp
                    ),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                )

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp)
                ) {
                    items(conversations) { conv ->
                        val isSelected = conv.id == activeConvId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) ClaudeTerracotta.copy(alpha = 0.12f) else Color.Transparent)
                                .clickable {
                                    viewModel.selectConversation(conv.id)
                                    coroutineScope.launch { drawerState.close() }
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ChatBubbleOutline,
                                    contentDescription = null,
                                    tint = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = conv.title,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        color = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                    ),
                                    maxLines = 1
                                )
                            }

                            if (conversations.size > 1) {
                                IconButton(
                                    onClick = { viewModel.deleteConversation(conv.id) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Удалить сессию",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

                // Drawer Footer: Custom Connection Selector
                Column(modifier = Modifier.padding(14.dp)) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                coroutineScope.launch { drawerState.close() }
                                showCustomConnectionDialog = true
                            },
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CloudQueue,
                                    contentDescription = null,
                                    tint = if (activeConnection != null) ClaudeTerracotta else ClaudeDanger,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = activeConnection?.providerId ?: "Настроить подключение",
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                        maxLines = 1
                                    )
                                    Text(
                                        text = if (activeConnection != null) "Модель: ${activeConnection!!.modelId}" else "Пользовательский API",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 10.sp
                                        ),
                                        maxLines = 1
                                    )
                                }
                            }
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = "Настройки подключения",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                // Top Navigation Bar
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .statusBarsPadding()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f, fill = false)
                        ) {
                            IconButton(
                                onClick = { coroutineScope.launch { drawerState.open() } },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Menu,
                                    contentDescription = "Меню",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            // Custom Connection Pill in Header
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (activeConnection != null) MaterialTheme.colorScheme.outline.copy(alpha = 0.5f) else ClaudeDanger.copy(alpha = 0.5f)
                                ),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .clickable { showCustomConnectionDialog = true }
                                    .testTag("connection_selector_chip")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(if (activeConnection != null) ClaudeSuccess else ClaudeDanger)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (activeConnection != null) {
                                            "${activeConnection!!.providerId} (${activeConnection!!.modelId})"
                                        } else {
                                            "Настроить API"
                                        },
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontWeight = FontWeight.Medium,
                                            fontSize = 12.sp,
                                            color = if (activeConnection != null) MaterialTheme.colorScheme.onSurface else ClaudeDanger
                                        ),
                                        maxLines = 1
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Path Badge / Pin
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable {
                                        activeTab = AppNavigationTab.FILES
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Folder,
                                        contentDescription = null,
                                        tint = ClaudeTerracotta,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = workingDir.substringAfterLast("/").ifEmpty { "Хранилище" },
                                        style = TextStyle(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            // Agent Operation Mode Chip (Safety vs Extra) — крайний справа
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = if (operationMode == AgentOperationMode.EXTRA) ClaudeTerracotta.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant,
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (operationMode == AgentOperationMode.EXTRA) ClaudeTerracotta else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                                ),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .clickable { showModeSelectionDialog = true }
                                    .testTag("mode_selector_chip")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (operationMode == AgentOperationMode.EXTRA) Icons.Default.Bolt else Icons.Default.Security,
                                        contentDescription = null,
                                        tint = if (operationMode == AgentOperationMode.EXTRA) ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (operationMode == AgentOperationMode.EXTRA) "Extra" else "Safety",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 11.sp,
                                            color = if (operationMode == AgentOperationMode.EXTRA) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface
                                        )
                                    )
                                }
                            }
                        }
                    }

                    // Navigation Tab Row
                    // Вкладка «Репо» видна только после входа через GitHub
                    val visibleTabs = AppNavigationTab.values().filter {
                        it != AppNavigationTab.REPOS || githubLogin != null
                    }
                    // Ровные вкладки-квадратики со скруглёнными краями, без подписей
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        visibleTabs.forEach { tab ->
                            val selected = activeTab == tab
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp)
                                    .clip(RoundedCornerShape(11.dp))
                                    .background(
                                        if (selected) ClaudeTerracotta
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    )
                                    .clickable {
                                        activeTab = tab
                                        if (tab == AppNavigationTab.REPOS && githubRepos.isEmpty() && !githubBusy) {
                                            viewModel.loadGitHubRepos()
                                        }
                                    }
                                    .testTag("tab_${tab.name}"),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = tab.label,
                                    tint = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                        thickness = 1.dp
                    )
                }
            },
            modifier = modifier.fillMaxSize()
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                when (activeTab) {
                    AppNavigationTab.CHAT -> {
                        ClaudeChatView(
                            messages = messages,
                            isGenerating = isGenerating,
                            activeConnection = activeConnection,
                            operationMode = operationMode,
                            agentStage = agentStage,
                            thinkingText = thinkingText,
                            onOpenModeSelection = { showModeSelectionDialog = true },
                            onOpenConnectionSettings = { showCustomConnectionDialog = true },
                            onSendMessage = { prompt -> viewModel.sendMessage(prompt) },
                            onStopGeneration = { viewModel.stopGeneration() },
                            onAnswerQuestion = { artifact, answer -> viewModel.answerAgentQuestion(artifact, answer) },
                            onExecuteArtifact = { artifact -> viewModel.executeArtifact(artifact) },
                            onRejectArtifact = { artifact -> viewModel.rejectArtifact(artifact) },
                            onSaveArtifactContent = { artifact, content -> viewModel.saveArtifactContent(artifact, content) },
                            onDangerConfirmRequest = { artifact -> viewModel.requestDangerousConfirmation(artifact) },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    AppNavigationTab.TERMINAL -> {
                        TerminalView(
                            workingDirectory = workingDir,
                            commandHistory = recentLogs.map {
                                CommandLog(
                                    id = it.id,
                                    command = it.command,
                                    workingDir = it.workingDir,
                                    exitCode = it.exitCode,
                                    output = it.output,
                                    errorOutput = it.errorOutput,
                                    durationMs = it.durationMs,
                                    source = it.source,
                                    timestamp = it.timestamp
                                )
                            },
                            onExecuteCommand = { cmd -> viewModel.executeTerminalCommand(cmd) },
                            onClearTerminal = { viewModel.clearTerminalLogs() },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    AppNavigationTab.FILES -> {
                        FileManagerView(
                            currentPath = workingDir,
                            files = fileItems,
                            isPinnedRoot = workingDir == pinnedDir,
                            hasStoragePermission = hasStoragePermission,
                            standardShortcuts = viewModel.standardShortcuts,
                            grantedFolders = grantedFolders,
                            onRequestStoragePermission = {
                                context.startActivity(viewModel.getStorageSettingsIntent())
                            },
                            onRequestNewFolderPermission = {
                                folderPickerLauncher.launch(null)
                            },
                            onRevokeFolderPermission = { folderId ->
                                viewModel.revokeFolderPermission(folderId)
                            },
                            onNavigateToDir = { path -> viewModel.navigateToDirectory(path) },
                            onNavigateUp = { viewModel.navigateUpDirectory() },
                            onOpenFile = { file -> viewModel.openFileAsArtifact(file) },
                            onCreateFile = { name, content -> viewModel.createNewFile(name, content) },
                            onCreateFolder = { name -> viewModel.createNewFolder(name) },
                            onRenameFile = { item, newName -> viewModel.renameFileItem(item, newName) },
                            onMoveFile = { item, dest -> viewModel.moveFileItem(item, dest) },
                            onDeleteFile = { file -> viewModel.deleteFileItem(file) },
                            onPinCurrentDirectory = { dir -> viewModel.pinDirectory(dir) },
                            onRefresh = { viewModel.refreshFiles() },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    AppNavigationTab.REPOS -> {
                        ReposView(
                            login = githubLogin ?: "",
                            repos = githubRepos,
                            downloadedNames = githubDownloaded,
                            statusText = githubStatus,
                            isLoading = githubBusy,
                            onRefresh = { viewModel.loadGitHubRepos() },
                            onDownload = { repo -> viewModel.downloadGitHubRepo(repo) },
                            onPush = { repo -> viewModel.pushGitHubRepo(repo) },
                            onOpen = { repo ->
                                viewModel.openGitHubRepo(repo)
                                activeTab = AppNavigationTab.FILES
                            },
                            onLogout = {
                                viewModel.logoutGitHub()
                                activeTab = AppNavigationTab.CHAT
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    AppNavigationTab.AUDIT_LOGS -> {
                        CommandLogsView(
                            commandLogs = recentLogs,
                            fileLogs = recentFileLogs,
                            onClearCommandLogs = { viewModel.clearTerminalLogs() },
                            onClearFileLogs = { viewModel.clearFileLogs() },
                            onUndoFileLog = { viewModel.undoFileLog(it) },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                // Active Artifact Dialog
                if (activeArtifact != null) {
                    AlertDialog(
                        onDismissRequest = { viewModel.setActiveArtifact(null) },
                        title = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = activeArtifact!!.title,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                IconButton(
                                    onClick = { viewModel.setActiveArtifact(null) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(imageVector = Icons.Default.Close, contentDescription = "Закрыть")
                                }
                            }
                        },
                        text = {
                            ClaudeArtifactCard(
                                artifact = activeArtifact!!,
                                onExecute = { viewModel.executeArtifact(it) },
                                onSaveContent = { art, content -> viewModel.saveArtifactContent(art, content) },
                                onDangerConfirmRequest = { viewModel.requestDangerousConfirmation(it) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = { viewModel.setActiveArtifact(null) },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                            ) {
                                Text("Готово")
                            }
                        },
                        shape = RoundedCornerShape(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ClaudeChatView(
    messages: List<ChatMessage>,
    isGenerating: Boolean,
    activeConnection: CustomConnection?,
    onOpenConnectionSettings: () -> Unit,
    onSendMessage: (String) -> Unit,
    onExecuteArtifact: (Artifact) -> Unit,
    onSaveArtifactContent: (Artifact, String) -> Unit,
    onDangerConfirmRequest: (Artifact) -> Unit,
    modifier: Modifier = Modifier,
    operationMode: AgentOperationMode = AgentOperationMode.SAFETY,
    agentStage: AgentStage = AgentStage.IDLE,
    thinkingText: String = "",
    onStopGeneration: () -> Unit = {},
    onAnswerQuestion: (Artifact, String) -> Unit = { _, _ -> },
    onOpenModeSelection: () -> Unit = {},
    onRejectArtifact: (Artifact) -> Unit = {}
) {
    var promptInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val quickPrompts = listOf(
        "Покажи файлы в ~/ (\$HOME)",
        "Создай тестовый скрипт в \$HOME",
        "Покажи файлы в текущей папке",
        "Проверь свободное место на диске"
    )

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Автоскролл вниз, пока агент думает/пишет/выполняет — контент растёт, размер списка не меняется
    val lastContentLength = messages.lastOrNull()?.content?.length ?: 0
    LaunchedEffect(lastContentLength, thinkingText.length, agentStage, isGenerating) {
        if (messages.isNotEmpty()) {
            listState.scrollToItem(messages.size)
        }
    }

    // При появлении клавиатуры прокручиваем чат вниз, чтобы последние сообщения были видны
    val imeBottomInset = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(imeBottomInset) {
        if (imeBottomInset > 0 && messages.isNotEmpty()) {
            listState.scrollToItem(messages.size)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Connection warning banner if not configured
        if (activeConnection == null) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenConnectionSettings() }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.WarningAmber,
                        contentDescription = null,
                        tint = ClaudeDanger,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Подключение не настроено",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 12.sp
                            )
                        )
                        Text(
                            text = "Добавьте base-url, api-key и model-id",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp
                            )
                        )
                    }
                    Button(
                        onClick = onOpenConnectionSettings,
                        colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("Настроить", fontSize = 11.sp)
                    }
                }
            }
        }

        // Message Feed
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxHeight()
                    .widthIn(max = 760.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(messages) { msg ->
                    ClaudeMessageRow(
                        message = msg,
                        onExecuteArtifact = onExecuteArtifact,
                        onRejectArtifact = onRejectArtifact,
                        onSaveArtifactContent = onSaveArtifactContent,
                        onDangerConfirmRequest = onDangerConfirmRequest,
                        onAnswerQuestion = onAnswerQuestion
                    )
                }

                if (isGenerating) {
                    item {
                        Column(modifier = Modifier.padding(start = 4.dp, top = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(ClaudeTerracotta.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (agentStage == AgentStage.THINKING) {
                                        Icon(
                                            imageVector = Icons.Default.Psychology,
                                            contentDescription = null,
                                            tint = ClaudeTerracotta,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    } else {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(14.dp),
                                            strokeWidth = 2.dp,
                                            color = ClaudeTerracotta
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = when (agentStage) {
                                        AgentStage.CONNECTING -> "Подключение к API..."
                                        AgentStage.RETRYING -> thinkingText.ifBlank { "Повторная попытка..." }
                                        AgentStage.THINKING -> "Thinking — анализ задачи..."
                                        AgentStage.RESPONDING -> "Генерация ответа..."
                                        AgentStage.EXECUTING -> "Выполнение действий..."
                                        AgentStage.IDLE -> "Отправка запроса к API и генерация ответа..."
                                    },
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }

                            // Панель мыслей агента (стрим reasoning_content модели)
                            if (agentStage == AgentStage.THINKING && thinkingText.isNotBlank()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(12.dp),
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        ClaudeTerracotta.copy(alpha = 0.25f)
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text(
                                            text = "Мысли агента",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontWeight = FontWeight.SemiBold,
                                                color = ClaudeTerracotta,
                                                fontSize = 10.sp
                                            )
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = thinkingText.takeLast(900),
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontStyle = FontStyle.Italic,
                                                fontSize = 11.sp,
                                                lineHeight = 16.sp
                                            ),
                                            maxLines = 10,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Quick Suggestion Chips
        if (messages.size <= 2) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                quickPrompts.forEach { suggestion ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onSendMessage(suggestion) }
                    ) {
                        Text(
                            text = suggestion,
                            style = TextStyle(
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }
        }

        // Mode Status Bar
        Surface(
            color = if (operationMode == AgentOperationMode.EXTRA) ClaudeTerracotta.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenModeSelection() }
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .widthIn(max = 760.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = if (operationMode == AgentOperationMode.EXTRA) Icons.Default.Bolt else Icons.Default.Security,
                        contentDescription = null,
                        tint = if (operationMode == AgentOperationMode.EXTRA) ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (operationMode == AgentOperationMode.EXTRA)
                            "Режим Extra: команды и операции выполняются автоматически"
                        else
                            "Режим Safety: требуется подтверждение выполнения",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            color = if (operationMode == AgentOperationMode.EXTRA) ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        maxLines = 1
                    )
                }

                Text(
                    text = "Изменить",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ClaudeTerracotta
                    )
                )
            }
        }

        // Centralized Minimalist Input Bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // Поднимаем поле ввода над клавиатурой (edge-to-edge: adjustResize не работает,
                // поэтому явно учитываем IME insets вместе с navigation bar)
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 760.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
                shadowElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = promptInput,
                        onValueChange = { promptInput = it },
                        placeholder = {
                            Text(
                                text = "Сообщение для CodeStudio (файлы, скрипты, команды)...",
                                style = TextStyle(
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            )
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (promptInput.isNotBlank() && !isGenerating) {
                                    onSendMessage(promptInput)
                                    promptInput = ""
                                }
                            }
                        ),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        textStyle = TextStyle(
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("chat_input_field")
                    )

                    Spacer(modifier = Modifier.width(6.dp))

                    IconButton(
                        onClick = {
                            if (isGenerating) {
                                onStopGeneration()
                            } else if (promptInput.isNotBlank()) {
                                onSendMessage(promptInput)
                                promptInput = ""
                            }
                        },
                        enabled = isGenerating || promptInput.isNotBlank(),
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(
                                if (isGenerating || promptInput.isNotBlank()) ClaudeTerracotta
                                else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                            )
                            .testTag("send_button")
                    ) {
                        Icon(
                            imageVector = if (isGenerating) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send,
                            contentDescription = if (isGenerating) "Остановить генерацию" else "Отправить",
                            tint = Color.White,
                            modifier = Modifier.size(if (isGenerating) 18.dp else 16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ClaudeMessageRow(
    message: ChatMessage,
    onExecuteArtifact: (Artifact) -> Unit,
    onSaveArtifactContent: (Artifact, String) -> Unit,
    onDangerConfirmRequest: (Artifact) -> Unit,
    modifier: Modifier = Modifier,
    onRejectArtifact: (Artifact) -> Unit = {},
    onAnswerQuestion: (Artifact, String) -> Unit = { _, _ -> }
) {
    val isUser = message.role == MessageRole.USER

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Avatar badge
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(
                        if (isUser) MaterialTheme.colorScheme.surfaceVariant
                        else ClaudeTerracotta.copy(alpha = 0.15f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isUser) Icons.Default.Person else Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = if (isUser) MaterialTheme.colorScheme.onSurfaceVariant else ClaudeTerracotta,
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                // Header (Role label)
                Text(
                    text = if (isUser) "Вы" else "CodeStudio",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Text body
                if (message.content.isNotEmpty()) {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = 21.sp
                        )
                    )
                }

                // Inline Claude Artifact Cards
                if (message.artifacts.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        message.artifacts.forEach { artifact ->
                            ClaudeArtifactCard(
                                artifact = artifact,
                                onExecute = onExecuteArtifact,
                                onReject = onRejectArtifact,
                                onSaveContent = onSaveArtifactContent,
                                onDangerConfirmRequest = onDangerConfirmRequest,
                                onAnswerQuestion = onAnswerQuestion
                            )
                        }
                    }
                }
            }
        }
    }
}
