package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
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
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream

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
    var showConnectionNotice by remember { mutableStateOf(false) }

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

    LaunchedEffect(activeConnection) {
        if (activeConnection == null) {
            showConnectionNotice = true
            delay(7_000)
            showConnectionNotice = false
        } else {
            showConnectionNotice = false
        }
    }

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
    var pendingAttachments by remember { mutableStateOf<List<ChatAttachment>>(emptyList()) }
    var attachmentError by remember { mutableStateOf<String?>(null) }
    val attachmentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        attachmentError = null
        val newAttachments = uris.mapNotNull { uri ->
            runCatching {
                val resolver = context.contentResolver
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
                val mime = resolver.getType(uri)?.takeUnless { it == "application/octet-stream" }
                    ?: imageMimeTypeFromName(name)
                val bytes = resolver.openInputStream(uri)?.use { stream ->
                    stream.readLimitedBytes(MAX_ATTACHMENT_BYTES)
                } ?: return@runCatching null
                val supported = mime.startsWith("image/") || isSupportedTextAttachment(name, mime)
                require(supported) { "Формат $name не поддерживается" }
                ChatAttachment(name, mime, bytes)
            }.onFailure { attachmentError = it.message }.getOrNull()
        }
        pendingAttachments = (pendingAttachments + newAttachments).distinctBy { it.name }.take(4)
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
            onLoadModels = { provider, key -> viewModel.listModels(provider, key) },
            onDismiss = { showCustomConnectionDialog = false }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(312.dp),
                drawerContainerColor = MaterialTheme.colorScheme.background
            ) {
                // Drawer Header
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 24.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Круглый логотип в меню
                        Image(
                            painter = painterResource(R.drawable.logo),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(11.dp))
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "CodeStudio",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "AI-агент файловой системы и терминала",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // New Session Button
                    Button(
                        onClick = {
                            viewModel.startNewConversation()
                            coroutineScope.launch { drawerState.close() }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("new_chat_button"),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "Новая сессия", fontWeight = FontWeight.SemiBold)
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Pinned Directory Card
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
                        ),
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

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))

                Text(
                    text = "РАЗДЕЛЫ",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 1.sp
                    ),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                )
                val drawerTabs = AppNavigationTab.values().filter {
                    it != AppNavigationTab.REPOS || githubLogin != null
                }
                drawerTabs.forEach { tab ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 2.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (activeTab == tab) MaterialTheme.colorScheme.primaryContainer
                                else Color.Transparent
                            )
                            .clickable {
                                activeTab = tab
                                if (tab == AppNavigationTab.REPOS && githubRepos.isEmpty() && !githubBusy) {
                                    viewModel.loadGitHubRepos()
                                }
                                coroutineScope.launch { drawerState.close() }
                            }
                            .testTag("tab_${tab.name}")
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = null,
                            tint = if (activeTab == tab) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(19.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = tab.label,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = if (activeTab == tab) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (activeTab == tab) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                            )
                        )
                    }
                }

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
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
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
                                    tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = conv.title,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
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
                        .background(MaterialTheme.colorScheme.background)
                        .statusBarsPadding()
                ) {
                    AnimatedVisibility(
                        visible = showConnectionNotice && activeConnection == null,
                        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
                    ) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                                .clickable { showCustomConnectionDialog = true },
                            shape = RoundedCornerShape(14.dp),
                            color = ClaudeDanger.copy(alpha = 0.14f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, ClaudeDanger.copy(alpha = 0.45f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = ClaudeDanger, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Подключение не настроено",
                                    style = MaterialTheme.typography.labelMedium.copy(color = ClaudeDanger, fontWeight = FontWeight.SemiBold)
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Start
                    ) {
                        IconButton(
                            onClick = { coroutineScope.launch { drawerState.open() } },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.Menu, contentDescription = "Меню")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = activeTab.label,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.offset(x = (-2).dp)
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Surface(
                            shape = CircleShape,
                            color = if (operationMode != AgentOperationMode.SAFETY) ClaudeTerracotta.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (operationMode != AgentOperationMode.SAFETY) ClaudeTerracotta else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                            ),
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .clickable { showModeSelectionDialog = true }
                                .testTag("mode_selector_chip")
                        ) {
                            Icon(
                                imageVector = if (operationMode != AgentOperationMode.SAFETY) Icons.Default.Bolt else Icons.Default.Security,
                                contentDescription = operationMode.title,
                                tint = if (operationMode != AgentOperationMode.SAFETY) ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { showCustomConnectionDialog = true }
                                .testTag("connection_selector_chip"),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (activeConnection != null) MaterialTheme.colorScheme.outline.copy(alpha = 0.55f) else ClaudeDanger.copy(alpha = 0.5f)
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(if (activeConnection != null) ClaudeSuccess else ClaudeDanger)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = if (activeConnection != null) {
                                        "${activeConnection!!.providerId} · ${activeConnection!!.modelId}"
                                    } else {
                                        "Настроить API"
                                    },
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 11.sp,
                                        color = if (activeConnection != null) MaterialTheme.colorScheme.onSurface else ClaudeDanger
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                    }

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
                            attachments = pendingAttachments,
                            attachmentError = attachmentError,
                            onPickAttachments = { attachmentPicker.launch(arrayOf("image/*", "text/*", "application/json", "application/xml", "application/octet-stream")) },
                            onRemoveAttachment = { attachment -> pendingAttachments = pendingAttachments - attachment },
                            onDismissAttachmentError = { attachmentError = null },
                            onSendMessage = { prompt ->
                                viewModel.sendMessage(prompt, pendingAttachments)
                                pendingAttachments = emptyList()
                            },
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
    ,attachments: List<ChatAttachment> = emptyList(),
    attachmentError: String? = null,
    onPickAttachments: () -> Unit = {},
    onRemoveAttachment: (ChatAttachment) -> Unit = {},
    onDismissAttachmentError: () -> Unit = {}
) {
    var promptInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

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
                    .padding(horizontal = 18.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                if (messages.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier.fillParentMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "Чем займёмся сегодня?",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Я готов, когда ты готов.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
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
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (agentStage == AgentStage.THINKING) {
                                        Icon(
                                            imageVector = Icons.Default.Psychology,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
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

        // Centralized Minimalist Input Bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // Поднимаем поле ввода над клавиатурой (edge-to-edge: adjustResize не работает,
                // поэтому явно учитываем IME insets вместе с navigation bar)
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .padding(horizontal = 12.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 380.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF1A1A1A),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)),
                shadowElevation = 3.dp
            ) {
                Column {
                    if (attachments.isNotEmpty()) {
                        Row(
                            modifier = Modifier.padding(start = 7.dp, top = 6.dp, end = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            attachments.forEach { attachment ->
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = Color.White.copy(alpha = 0.10f),
                                    modifier = Modifier.clickable { onRemoveAttachment(attachment) }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = if (attachment.mimeType.startsWith("image/")) Icons.Default.Image else Icons.Default.AttachFile,
                                            contentDescription = null,
                                            tint = Color.White.copy(alpha = 0.75f),
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = attachment.name.take(16),
                                            color = Color.White.copy(alpha = 0.82f),
                                            fontSize = 10.sp,
                                            maxLines = 1
                                        )
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Icon(Icons.Default.Close, contentDescription = "Удалить вложение", tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(12.dp))
                                    }
                                }
                            }
                        }
                    }
                    if (attachmentError != null) {
                        Text(
                            text = attachmentError ?: "",
                            color = ClaudeDanger,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 12.dp, top = 4.dp, end = 12.dp)
                        )
                    }
                    Row(
                        modifier = Modifier.padding(start = 4.dp, end = 5.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                    IconButton(
                        onClick = onPickAttachments,
                        modifier = Modifier
                            .size(30.dp)
                            .offset(x = 2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Добавить вложение",
                            tint = Color.White.copy(alpha = 0.78f),
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    TextField(
                        value = promptInput,
                        onValueChange = { promptInput = it },
                        placeholder = {
                            Text(
                                text = "Спросите или опишите задачу...",
                                style = TextStyle(
                                    fontSize = 13.sp,
                                    color = Color.White.copy(alpha = 0.55f)
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
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        textStyle = TextStyle(
                            fontSize = 13.sp,
                            color = Color.White
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("chat_input_field")
                    )

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
                            .size(26.dp)
                            .offset(x = (-7).dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF3B82F6))
                            .testTag("send_button")
                    ) {
                        Icon(
                            imageVector = if (isGenerating) Icons.Default.Stop else Icons.Default.KeyboardArrowUp,
                            contentDescription = if (isGenerating) "Остановить генерацию" else "Отправить",
                            tint = Color.White,
                            modifier = Modifier.size(if (isGenerating) 15.dp else 14.dp)
                        )
                    }
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
                    .size(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (isUser) MaterialTheme.colorScheme.surfaceVariant
                        else MaterialTheme.colorScheme.primaryContainer
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isUser) Icons.Default.Person else Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = if (isUser) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimaryContainer,
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

private const val MAX_ATTACHMENT_BYTES = 4 * 1024 * 1024

private fun isSupportedTextAttachment(name: String, mimeType: String): Boolean {
    if (mimeType.startsWith("text/")) return true
    return name.substringAfterLast('.', "").lowercase() in setOf(
        "txt", "md", "java", "kt", "kts", "xml", "json", "yaml", "yml", "html", "css",
        "js", "ts", "tsx", "jsx", "py", "sh", "c", "cpp", "h", "hpp", "gradle", "properties"
    )
}

private fun imageMimeTypeFromName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    "bmp" -> "image/bmp"
    "heic" -> "image/heic"
    "heif" -> "image/heif"
    else -> "application/octet-stream"
}

private fun java.io.InputStream.readLimitedBytes(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        require(total <= limit) { "Файл больше 4 МБ" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
