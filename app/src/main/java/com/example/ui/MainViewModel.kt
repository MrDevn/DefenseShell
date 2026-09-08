package com.example.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.db.AppDatabase
import com.example.data.db.entities.CommandLogEntity
import com.example.data.db.entities.ConversationEntity
import com.example.data.db.entities.CustomConnectionEntity
import com.example.data.db.entities.FileLogEntity
import com.example.data.db.entities.GrantedFolderEntity
import com.example.data.db.entities.MessageEntity
import com.example.data.model.*
import com.example.domain.ai.AiAgentService
import com.example.domain.filesystem.FileSystemEngine
import com.example.domain.terminal.TerminalEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val chatDao = db.chatDao()
    private val commandLogDao = db.commandLogDao()
    private val fileLogDao = db.fileLogDao()
    private val customConnectionDao = db.customConnectionDao()
    private val grantedFolderDao = db.grantedFolderDao()

    val fileSystemEngine = FileSystemEngine(application)
    val terminalEngine = TerminalEngine(application, fileSystemEngine)
    val aiAgentService = AiAgentService(fileSystemEngine)

    // Agent Operation Mode: Safety (confirm every step) vs Extra (autonomous)
    private val _operationMode = MutableStateFlow(AgentOperationMode.SAFETY)
    val operationMode: StateFlow<AgentOperationMode> = _operationMode.asStateFlow()

    // Granted SAF Folders (UserLand style persistence)
    val grantedFolders: StateFlow<List<GrantedFolderEntity>> = grantedFolderDao.getAllGrantedFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Pending SAF Folder Permission Request (target folder path)
    private val _pendingFolderPermission = MutableStateFlow<String?>(null)
    val pendingFolderPermission: StateFlow<String?> = _pendingFolderPermission.asStateFlow()

    // Device Storage Permission
    private val _hasStoragePermission = MutableStateFlow(fileSystemEngine.hasAllFilesAccess())
    val hasStoragePermission: StateFlow<Boolean> = _hasStoragePermission.asStateFlow()

    // Real Working Directory on Device
    private val _currentWorkingDir = MutableStateFlow(fileSystemEngine.defaultWorkingDir.absolutePath)
    val currentWorkingDir: StateFlow<String> = _currentWorkingDir.asStateFlow()

    private val _pinnedDirectory = MutableStateFlow(fileSystemEngine.defaultWorkingDir.absolutePath)
    val pinnedDirectory: StateFlow<String> = _pinnedDirectory.asStateFlow()

    // File list
    private val _fileItems = MutableStateFlow<List<FileSystemItem>>(emptyList())
    val fileItems: StateFlow<List<FileSystemItem>> = _fileItems.asStateFlow()

    // Standard Shortcuts for Quick Jumps
    val standardShortcuts: Map<String, String> by lazy {
        fileSystemEngine.standardDirectories.mapValues { it.value.absolutePath }
    }

    // Conversations & Active Chat
    private val _activeConversationId = MutableStateFlow<String>("")
    val activeConversationId: StateFlow<String> = _activeConversationId.asStateFlow()

    val conversations: StateFlow<List<ConversationEntity>> = chatDao.getAllConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val currentMessages: StateFlow<List<ChatMessage>> = _activeConversationId
        .flatMapLatest { id ->
            if (id.isEmpty()) flowOf(emptyList())
            else chatDao.getMessagesForConversation(id).map { list ->
                list.map { parseEntityToMessage(it) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Execution & Safety
    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _pendingDangerousArtifact = MutableStateFlow<Artifact?>(null)
    val pendingDangerousArtifact: StateFlow<Artifact?> = _pendingDangerousArtifact.asStateFlow()

    private val _activeArtifact = MutableStateFlow<Artifact?>(null)
    val activeArtifact: StateFlow<Artifact?> = _activeArtifact.asStateFlow()

    // Audit logs
    val recentCommandLogs: StateFlow<List<CommandLogEntity>> = commandLogDao.getRecentLogs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentFileLogs: StateFlow<List<FileLogEntity>> = fileLogDao.getRecentFileLogs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Custom Connections (Only "Пользовательский" type, no hardcoded presets)
    val customConnections: StateFlow<List<CustomConnection>> = customConnectionDao.getAllConnections()
        .map { list -> list.map { it.toCustomConnection() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeConnection: StateFlow<CustomConnection?> = customConnectionDao.getActiveConnection()
        .map { it?.toCustomConnection() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        checkStoragePermission()
        refreshFiles()
        initializeDefaultConversation()
    }

    fun checkStoragePermission() {
        _hasStoragePermission.value = fileSystemEngine.hasAllFilesAccess()
    }

    fun getStorageSettingsIntent(): Intent {
        return fileSystemEngine.createAllFilesAccessIntent()
    }

    private fun initializeDefaultConversation() {
        viewModelScope.launch {
            val convList = chatDao.getAllConversations().first()
            if (convList.isEmpty()) {
                val newId = UUID.randomUUID().toString()
                val conv = ConversationEntity(
                    id = newId,
                    title = "Файловый агент",
                    pinnedDirectory = _currentWorkingDir.value
                )
                chatDao.insertConversation(conv)
                _activeConversationId.value = newId

                val welcomeMessage = MessageEntity(
                    id = UUID.randomUUID().toString(),
                    conversationId = newId,
                    role = "ASSISTANT",
                    content = """
Привет! Я ClaudeShell — автономный ИИ-агент с реальным доступом к файловой системе Android и терминалу.

⚙️ Подключение: работает исключительно через **Пользовательский** тип подключения (Custom API). Нажмите на индикатор подключения вверху, чтобы настроить ваш `base-url`, `api-key` и `model-id`.

📁 Доступ к файлам: устройство доступно напрямую в каталоге `${_currentWorkingDir.value}`. Вы можете попросить меня найти, создать, отредактировать или удалить любые файлы, запустить shell-скрипт или автоматизировать задачу.
""".trimIndent(),
                    artifactsJson = "[]"
                )
                chatDao.insertMessage(welcomeMessage)
            } else {
                _activeConversationId.value = convList.first().id
            }
        }
    }

    // -------------------------------------------------------------
    // Custom Connection Management
    // -------------------------------------------------------------

    fun saveCustomConnection(conn: CustomConnection) {
        viewModelScope.launch(Dispatchers.IO) {
            val entity = conn.toEntity()
            customConnectionDao.insertConnection(entity)
            if (conn.isActive || customConnections.value.isEmpty()) {
                customConnectionDao.setActiveConnection(conn.id)
            }
        }
    }

    fun selectActiveConnection(conn: CustomConnection) {
        viewModelScope.launch(Dispatchers.IO) {
            customConnectionDao.setActiveConnection(conn.id)
        }
    }

    fun deleteCustomConnection(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            customConnectionDao.deleteConnection(id)
            // If deleted active connection, activate the next one if available
            val remaining = customConnectionDao.getAllConnections().first()
            if (remaining.isNotEmpty() && remaining.none { it.isActive }) {
                customConnectionDao.setActiveConnection(remaining.first().id)
            }
        }
    }

    suspend fun testConnection(conn: CustomConnection): Result<String> {
        return aiAgentService.testConnection(conn)
    }

    // -------------------------------------------------------------
    // Real File Operations & Navigation
    // -------------------------------------------------------------

    fun refreshFiles(targetDir: String? = null) {
        checkStoragePermission()
        viewModelScope.launch(Dispatchers.IO) {
            val dir = targetDir ?: _currentWorkingDir.value
            _fileItems.value = fileSystemEngine.listFiles(dir)
        }
    }

    fun navigateToDirectory(path: String) {
        _currentWorkingDir.value = path
        refreshFiles(path)
    }

    fun navigateUpDirectory() {
        val current = File(_currentWorkingDir.value)
        val parent = current.parentFile
        if (parent != null && parent.exists() && parent.canRead()) {
            _currentWorkingDir.value = parent.absolutePath
            refreshFiles(parent.absolutePath)
        }
    }

    fun pinDirectory(dir: String) {
        _pinnedDirectory.value = dir
    }

    fun createNewFile(fileName: String, content: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val targetFile = File(_currentWorkingDir.value, fileName)
                val (_, backup) = fileSystemEngine.writeFile(targetFile.absolutePath, content)
                fileLogDao.insertFileLog(
                    FileLogEntity(
                        operation = "CREATE",
                        path = targetFile.absolutePath,
                        details = "Создан файл '${targetFile.name}' (${content.length} символов)",
                        status = "SUCCESS",
                        backupContent = backup
                    )
                )
                refreshFiles()
            } catch (e: Exception) {
                fileLogDao.insertFileLog(
                    FileLogEntity(
                        operation = "CREATE",
                        path = "${_currentWorkingDir.value}/$fileName",
                        details = "Ошибка создания: ${e.message}",
                        status = "FAILED"
                    )
                )
            }
        }
    }

    fun createNewFolder(folderName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val targetFolder = File(_currentWorkingDir.value, folderName)
                val success = fileSystemEngine.createDirectory(targetFolder.absolutePath)
                fileLogDao.insertFileLog(
                    FileLogEntity(
                        operation = "CREATE_DIR",
                        path = targetFolder.absolutePath,
                        details = "Создана папка '${targetFolder.name}'",
                        status = if (success) "SUCCESS" else "FAILED"
                    )
                )
                refreshFiles()
            } catch (e: Exception) {
                fileLogDao.insertFileLog(
                    FileLogEntity(
                        operation = "CREATE_DIR",
                        path = "${_currentWorkingDir.value}/$folderName",
                        details = "Ошибка: ${e.message}",
                        status = "FAILED"
                    )
                )
            }
        }
    }

    fun renameFileItem(item: FileSystemItem, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val success = fileSystemEngine.renameItem(item.path, newName)
                fileLogDao.insertFileLog(
                    FileLogEntity(
                        operation = "RENAME",
                        path = item.path,
                        details = "Переименовано в '$newName'",
                        status = if (success) "SUCCESS" else "FAILED"
                    )
                )
                refreshFiles()
            } catch (e: Exception) {
                fileLogDao.insertFileLog(
                    FileLogEntity(
                        operation = "RENAME",
                        path = item.path,
                        details = "Ошибка: ${e.message}",
                        status = "FAILED"
                    )
                )
            }
        }
    }

    fun deleteFileItem(item: FileSystemItem) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val (success, backup) = fileSystemEngine.deleteItem(item.path)
                fileLogDao.insertFileLog(
                    FileLogEntity(
                        operation = "DELETE",
                        path = item.path,
                        details = if (item.isDirectory) "Удалена папка со всем содержимым" else "Удален файл (${item.formattedSize})",
                        status = if (success) "SUCCESS" else "FAILED",
                        backupContent = backup
                    )
                )
                refreshFiles()
            } catch (e: Exception) {
                fileLogDao.insertFileLog(
                    FileLogEntity(
                        operation = "DELETE",
                        path = item.path,
                        details = "Ошибка удаления: ${e.message}",
                        status = "FAILED"
                    )
                )
            }
        }
    }

    fun undoFileLog(log: FileLogEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            if (!log.backupContent.isNullOrBlank()) {
                fileSystemEngine.writeFile(log.path, log.backupContent)
                fileLogDao.insertFileLog(
                    FileLogEntity(
                        operation = "RESTORE",
                        path = log.path,
                        details = "Восстановлен файл из резервной копии журнала #${log.id}",
                        status = "SUCCESS"
                    )
                )
                refreshFiles()
            }
        }
    }

    fun openFileAsArtifact(item: FileSystemItem) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val content = fileSystemEngine.readFile(item.path)
                val artifact = Artifact(
                    id = UUID.randomUUID().toString(),
                    title = item.name,
                    type = ArtifactType.FILE_EDIT,
                    targetPath = item.path,
                    content = content,
                    language = when (item.extension.lowercase()) {
                        "py" -> "python"
                        "sh", "bash" -> "bash"
                        "json" -> "json"
                        "md" -> "markdown"
                        "kt" -> "kotlin"
                        "js", "ts" -> "javascript"
                        else -> "text"
                    },
                    status = ArtifactStatus.IDLE
                )
                _activeArtifact.value = artifact
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun setActiveArtifact(artifact: Artifact?) {
        _activeArtifact.value = artifact
    }

    fun saveArtifactContent(artifact: Artifact, newContent: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val path = artifact.targetPath
            if (path != null) {
                val (_, backup) = fileSystemEngine.writeFile(path, newContent)
                fileLogDao.insertFileLog(
                    FileLogEntity(
                        operation = "WRITE",
                        path = path,
                        details = "Сохранены изменения файла через редактор",
                        status = "SUCCESS",
                        backupContent = backup
                    )
                )
                refreshFiles()
            }
        }
    }

    // -------------------------------------------------------------
    // Terminal Execution & Danger Confirmation
    // -------------------------------------------------------------

    fun executeTerminalCommand(cmd: String) {
        viewModelScope.launch {
            val (isDang, reason) = fileSystemEngine.checkDangerousCommand(cmd)
            if (isDang) {
                _pendingDangerousArtifact.value = Artifact(
                    id = UUID.randomUUID().toString(),
                    title = "Опасная команда терминала",
                    type = ArtifactType.TERMINAL_COMMAND,
                    command = cmd,
                    content = cmd,
                    isDangerous = true,
                    dangerReason = reason,
                    status = ArtifactStatus.IDLE
                )
                return@launch
            }

            runTerminalCommandDirect(cmd)
        }
    }

    private suspend fun runTerminalCommandDirect(cmd: String) {
        val log = terminalEngine.executeCommand(
            command = cmd,
            workingDir = _currentWorkingDir.value,
            source = "USER"
        )
        commandLogDao.insertLog(
            CommandLogEntity(
                command = log.command,
                workingDir = log.workingDir,
                exitCode = log.exitCode,
                output = log.output,
                errorOutput = log.errorOutput,
                durationMs = log.durationMs,
                source = log.source
            )
        )

        if (log.workingDir != _currentWorkingDir.value) {
            _currentWorkingDir.value = log.workingDir
            refreshFiles(log.workingDir)
        } else {
            refreshFiles()
        }
    }

    fun clearTerminalLogs() {
        viewModelScope.launch {
            commandLogDao.clearLogs()
        }
    }

    fun clearFileLogs() {
        viewModelScope.launch {
            fileLogDao.clearFileLogs()
        }
    }

    fun setOperationMode(mode: AgentOperationMode) {
        _operationMode.value = mode
    }

    fun toggleOperationMode() {
        _operationMode.value = if (_operationMode.value == AgentOperationMode.SAFETY) {
            AgentOperationMode.EXTRA
        } else {
            AgentOperationMode.SAFETY
        }
    }

    fun requestFolderPermission(targetPath: String) {
        _pendingFolderPermission.value = fileSystemEngine.extractRequiredFolder(targetPath)
    }

    fun dismissFolderPermission() {
        onFolderPermissionDenied(_pendingFolderPermission.value)
    }

    fun onFolderPermissionGranted(uri: Uri, targetPath: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            val granted = fileSystemEngine.registerPersistableFolderUri(uri, targetPath)
            grantedFolderDao.insertGrantedFolder(granted)
            _pendingFolderPermission.value = null

            val convId = _activeConversationId.value
            chatDao.insertMessage(
                MessageEntity(
                    id = UUID.randomUUID().toString(),
                    conversationId = convId,
                    role = MessageRole.ASSISTANT.name,
                    content = "✅ Разрешение на доступ к папке **${granted.displayName}** (`${granted.folderPath}`) успешно получено и сохранено в системе (SAF). Продолжаю выполнение задачи...",
                    artifactsJson = "[]"
                )
            )

            // Auto-execute pending artifacts if in EXTRA mode or if previously blocked
            val msgs = currentMessages.value
            val lastAssistantMsg = msgs.lastOrNull { it.role == MessageRole.ASSISTANT && it.artifacts.isNotEmpty() }
            if (lastAssistantMsg != null) {
                for (artifact in lastAssistantMsg.artifacts) {
                    if (artifact.isExecutable() && artifact.status == ArtifactStatus.IDLE) {
                        if (_operationMode.value == AgentOperationMode.EXTRA || !artifact.isDangerous) {
                            executeArtifact(artifact)
                        }
                    }
                }
            }
        }
    }

    fun onFolderPermissionDenied(targetPath: String?) {
        viewModelScope.launch {
            _pendingFolderPermission.value = null
            val convId = _activeConversationId.value
            chatDao.insertMessage(
                MessageEntity(
                    id = UUID.randomUUID().toString(),
                    conversationId = convId,
                    role = MessageRole.ASSISTANT.name,
                    content = "❌ Доступ к папке `${targetPath ?: "устройства"}` был отклонен пользователем. Агент не может продолжить работу с этой папкой без системного разрешения (SAF).",
                    artifactsJson = "[]"
                )
            )
        }
    }

    fun revokeFolderPermission(folderId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            grantedFolderDao.deleteGrantedFolder(folderId)
        }
    }

    fun rejectArtifact(artifact: Artifact) {
        updateArtifactInCurrentMessages(
            artifact.copy(
                status = ArtifactStatus.FAILED,
                executionOutput = "Действие отклонено пользователем (Режим Safety)."
            )
        )
        if (_pendingDangerousArtifact.value?.id == artifact.id) {
            _pendingDangerousArtifact.value = null
        }
    }

    fun dismissDangerousDialog() {
        _pendingDangerousArtifact.value = null
    }

    fun requestDangerousConfirmation(artifact: Artifact) {
        _pendingDangerousArtifact.value = artifact
    }

    fun confirmDangerousArtifact() {
        val artifact = _pendingDangerousArtifact.value ?: return
        _pendingDangerousArtifact.value = null
        executeArtifact(artifact)
    }

    fun executeArtifact(artifact: Artifact) {
        viewModelScope.launch {
            // Check folder authorization before executing
            val targetPath = artifact.targetPath
            if (targetPath != null && !fileSystemEngine.isFolderAuthorized(targetPath, grantedFolders.value)) {
                _pendingFolderPermission.value = fileSystemEngine.extractRequiredFolder(targetPath)
                return@launch
            }

            when (artifact.type) {
                ArtifactType.TERMINAL_COMMAND -> {
                    val cmdToRun = artifact.command ?: artifact.content

                    // If command references an unauthorized path, trigger SAF
                    val extractedPath = extractPathFromCommand(cmdToRun)
                    if (extractedPath != null && !fileSystemEngine.isFolderAuthorized(extractedPath, grantedFolders.value)) {
                        _pendingFolderPermission.value = fileSystemEngine.extractRequiredFolder(extractedPath)
                        return@launch
                    }

                    val log = terminalEngine.executeCommand(
                        command = cmdToRun,
                        workingDir = _currentWorkingDir.value,
                        source = "AGENT"
                    )

                    commandLogDao.insertLog(
                        CommandLogEntity(
                            command = log.command,
                            workingDir = log.workingDir,
                            exitCode = log.exitCode,
                            output = log.output,
                            errorOutput = log.errorOutput,
                            durationMs = log.durationMs,
                            source = log.source
                        )
                    )

                    updateArtifactInCurrentMessages(
                        artifact.copy(
                            status = if (log.exitCode == 0) ArtifactStatus.SUCCESS else ArtifactStatus.FAILED,
                            exitCode = log.exitCode,
                            executionOutput = log.output.ifEmpty { log.errorOutput ?: "(Команда завершена, код: ${log.exitCode})" }
                        )
                    )

                    if (log.workingDir != _currentWorkingDir.value) {
                        _currentWorkingDir.value = log.workingDir
                    }
                    refreshFiles()
                }
                ArtifactType.FILE_CREATE, ArtifactType.FILE_EDIT -> {
                    try {
                        val path = artifact.targetPath ?: "${_currentWorkingDir.value}/output.txt"
                        val (_, backup) = fileSystemEngine.writeFile(path, artifact.content)
                        fileLogDao.insertFileLog(
                            FileLogEntity(
                                operation = if (artifact.type == ArtifactType.FILE_CREATE) "CREATE" else "WRITE",
                                path = path,
                                details = "Выполнен артефакт агента: ${artifact.title}",
                                status = "SUCCESS",
                                backupContent = backup
                            )
                        )
                        updateArtifactInCurrentMessages(
                            artifact.copy(
                                status = ArtifactStatus.SUCCESS,
                                executionOutput = "Файл успешно сохранен на устройстве:\n$path"
                            )
                        )
                        refreshFiles()
                    } catch (e: Exception) {
                        updateArtifactInCurrentMessages(
                            artifact.copy(
                                status = ArtifactStatus.FAILED,
                                executionOutput = "Ошибка сохранения: ${e.message}"
                            )
                        )
                    }
                }
                else -> {}
            }
        }
    }

    private fun extractPathFromCommand(cmd: String): String? {
        val tokens = cmd.split("\\s+".toRegex())
        for (token in tokens) {
            val clean = token.trim('\'', '"', ';', '|', '&')
            if (clean.startsWith("/sdcard/") || clean.startsWith("/storage/emulated/0/") || clean.startsWith("${fileSystemEngine.androidMountDisplayRoot}/")) {
                return clean
            }
        }
        return null
    }

    private fun updateArtifactInCurrentMessages(updatedArtifact: Artifact) {
        viewModelScope.launch {
            val convId = _activeConversationId.value
            val messages = currentMessages.value
            for (msg in messages) {
                val artifactIdx = msg.artifacts.indexOfFirst { it.id == updatedArtifact.id }
                if (artifactIdx != -1) {
                    val updatedArtifacts = msg.artifacts.toMutableList().apply {
                        this[artifactIdx] = updatedArtifact
                    }
                    chatDao.insertMessage(
                        MessageEntity(
                            id = msg.id,
                            conversationId = convId,
                            role = msg.role.name,
                            content = msg.content,
                            artifactsJson = serializeArtifacts(updatedArtifacts),
                            timestamp = msg.timestamp
                        )
                    )
                    break
                }
            }
        }
    }

    // -------------------------------------------------------------
    // Real Chat Messaging (Streaming & Error Handling)
    // -------------------------------------------------------------

    fun sendMessage(userPrompt: String) {
        if (userPrompt.isBlank() || _isGenerating.value) return
        val prompt = userPrompt.trim()

        viewModelScope.launch {
            val convId = _activeConversationId.value
            val userMsgId = UUID.randomUUID().toString()

            // 1. Insert user message
            chatDao.insertMessage(
                MessageEntity(
                    id = userMsgId,
                    conversationId = convId,
                    role = MessageRole.USER.name,
                    content = prompt,
                    artifactsJson = "[]"
                )
            )

            // Check active custom connection
            val activeConn = activeConnection.value
            if (activeConn == null) {
                val warningEntity = MessageEntity(
                    id = UUID.randomUUID().toString(),
                    conversationId = convId,
                    role = MessageRole.ASSISTANT.name,
                    content = """
⚠️ **Пользовательское подключение не настроено**

Для отправки запросов модели добавьте и активируйте подключение:
1. Нажмите на плашку подключения вверху экрана или в меню
2. Укажите:
   - **provider-id**: Название (например: *My DeepSeek*, *OpenRouter*)
   - **base-url**: Адрес API (например: *https://api.openai.com/v1*)
   - **api-key**: Ключ авторизации
   - **model-id**: Имя модели (например: *deepseek-chat*, *gpt-4o*)
""".trimIndent(),
                    artifactsJson = "[]"
                )
                chatDao.insertMessage(warningEntity)
                return@launch
            }

            _isGenerating.value = true

            // Insert initial assistant message for streaming
            val assistantMsgId = UUID.randomUUID().toString()
            val initialAssistantEntity = MessageEntity(
                id = assistantMsgId,
                conversationId = convId,
                role = MessageRole.ASSISTANT.name,
                content = "...",
                artifactsJson = "[]"
            )
            chatDao.insertMessage(initialAssistantEntity)

            var lastStreamUpdateTime = 0L

            try {
                val result = aiAgentService.executeCustomPrompt(
                    prompt = prompt,
                    connection = activeConn,
                    workingDir = _currentWorkingDir.value,
                    conversationHistory = currentMessages.value,
                    operationMode = _operationMode.value,
                    grantedFolders = grantedFolders.value,
                    onPartialText = { streamedText ->
                        val now = System.currentTimeMillis()
                        // Update DB throttled every 150ms to keep UI smooth
                        if (now - lastStreamUpdateTime > 150) {
                            lastStreamUpdateTime = now
                            viewModelScope.launch {
                                chatDao.insertMessage(
                                    MessageEntity(
                                        id = assistantMsgId,
                                        conversationId = convId,
                                        role = MessageRole.ASSISTANT.name,
                                        content = streamedText,
                                        artifactsJson = "[]"
                                    )
                                )
                            }
                        }
                    }
                )

                // Final save of completed message with artifacts
                val finalAssistantEntity = MessageEntity(
                    id = assistantMsgId,
                    conversationId = convId,
                    role = MessageRole.ASSISTANT.name,
                    content = result.responseText,
                    artifactsJson = serializeArtifacts(result.artifacts)
                )
                chatDao.insertMessage(finalAssistantEntity)

                // Check if any artifact requires an unauthorized folder
                val unauthorizedFolder = result.artifacts.firstNotNullOfOrNull { art ->
                    val path = art.targetPath ?: if (art.type == ArtifactType.TERMINAL_COMMAND) {
                        extractPathFromCommand(art.command ?: art.content)
                    } else null
                    if (path != null && !fileSystemEngine.isFolderAuthorized(path, grantedFolders.value)) {
                        fileSystemEngine.extractRequiredFolder(path)
                    } else null
                }

                if (unauthorizedFolder != null) {
                    _pendingFolderPermission.value = unauthorizedFolder
                } else if (_operationMode.value == AgentOperationMode.EXTRA) {
                    // EXTRA MODE: Autonomous execution of all executable artifacts!
                    for (art in result.artifacts) {
                        if (art.isExecutable() && art.status == ArtifactStatus.IDLE) {
                            executeArtifact(art)
                        }
                    }
                } else {
                    // SAFETY MODE: Step-by-step confirmation. If any artifact is dangerous, prompt user
                    val dangerous = result.artifacts.firstOrNull { it.isDangerous }
                    if (dangerous != null) {
                        _pendingDangerousArtifact.value = dangerous
                    }
                }

                // Update conversation title if first user message
                if (currentMessages.value.size <= 3) {
                    val titleSnippet = if (prompt.length > 28) prompt.take(28) + "..." else prompt
                    val conv = chatDao.getConversationById(convId)
                    if (conv != null) {
                        chatDao.insertConversation(conv.copy(title = titleSnippet, updatedAt = System.currentTimeMillis()))
                    }
                }
            } catch (e: Exception) {
                val errorText = "❌ ${e.message ?: "Неизвестная ошибка при запросе к API"}"
                chatDao.insertMessage(
                    MessageEntity(
                        id = assistantMsgId,
                        conversationId = convId,
                        role = MessageRole.ASSISTANT.name,
                        content = errorText,
                        artifactsJson = "[]"
                    )
                )
            } finally {
                _isGenerating.value = false
            }
        }
    }

    fun startNewConversation() {
        viewModelScope.launch {
            val newId = UUID.randomUUID().toString()
            val conv = ConversationEntity(
                id = newId,
                title = "Новая сессия",
                pinnedDirectory = _currentWorkingDir.value
            )
            chatDao.insertConversation(conv)
            _activeConversationId.value = newId
        }
    }

    fun selectConversation(id: String) {
        _activeConversationId.value = id
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            chatDao.deleteConversation(id)
            chatDao.deleteMessagesForConversation(id)
            if (_activeConversationId.value == id) {
                val list = chatDao.getAllConversations().first()
                if (list.isNotEmpty()) {
                    _activeConversationId.value = list.first().id
                } else {
                    startNewConversation()
                }
            }
        }
    }

    // -------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------

    private fun parseEntityToMessage(entity: MessageEntity): ChatMessage {
        val role = when (entity.role) {
            "USER" -> MessageRole.USER
            "ASSISTANT" -> MessageRole.ASSISTANT
            else -> MessageRole.SYSTEM
        }
        return ChatMessage(
            id = entity.id,
            conversationId = entity.conversationId,
            role = role,
            content = entity.content,
            artifacts = deserializeArtifacts(entity.artifactsJson),
            timestamp = entity.timestamp
        )
    }

    private fun serializeArtifacts(artifacts: List<Artifact>): String {
        val array = JSONArray()
        for (a in artifacts) {
            val obj = JSONObject().apply {
                put("id", a.id)
                put("title", a.title)
                put("type", a.type.name)
                put("language", a.language)
                put("content", a.content)
                put("command", a.command ?: "")
                put("targetPath", a.targetPath ?: "")
                put("isDangerous", a.isDangerous)
                put("dangerReason", a.dangerReason ?: "")
                put("status", a.status.name)
                put("exitCode", a.exitCode ?: -1)
                put("executionOutput", a.executionOutput ?: "")
            }
            array.put(obj)
        }
        return array.toString()
    }

    private fun deserializeArtifacts(jsonStr: String): List<Artifact> {
        val result = mutableListOf<Artifact>()
        if (jsonStr.isBlank()) return result
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val exit = obj.optInt("exitCode", -1)
                result.add(
                    Artifact(
                        id = obj.getString("id"),
                        title = obj.getString("title"),
                        type = ArtifactType.valueOf(obj.getString("type")),
                        language = obj.optString("language", "text"),
                        content = obj.getString("content"),
                        command = obj.optString("command").ifEmpty { null },
                        targetPath = obj.optString("targetPath").ifEmpty { null },
                        isDangerous = obj.optBoolean("isDangerous", false),
                        dangerReason = obj.optString("dangerReason").ifEmpty { null },
                        status = ArtifactStatus.valueOf(obj.optString("status", ArtifactStatus.IDLE.name)),
                        exitCode = if (exit == -1) null else exit,
                        executionOutput = obj.optString("executionOutput").ifEmpty { null }
                    )
                )
            }
        } catch (_: Exception) {}
        return result
    }

    private fun CustomConnectionEntity.toCustomConnection(): CustomConnection {
        return CustomConnection(
            id = id,
            providerId = providerId,
            baseUrl = baseUrl,
            apiKey = apiKey,
            modelId = modelId,
            authHeaderFormat = authHeaderFormat,
            customHeadersJson = customHeadersJson,
            requestBodyTemplate = requestBodyTemplate,
            isActive = isActive,
            createdAt = createdAt
        )
    }

    private fun CustomConnection.toEntity(): CustomConnectionEntity {
        return CustomConnectionEntity(
            id = id,
            providerId = providerId,
            baseUrl = baseUrl,
            apiKey = apiKey,
            modelId = modelId,
            authHeaderFormat = authHeaderFormat,
            customHeadersJson = customHeadersJson,
            requestBodyTemplate = requestBodyTemplate,
            isActive = isActive,
            createdAt = createdAt
        )
    }
}
