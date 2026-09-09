package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
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
import com.example.domain.ai.AgentExecutionResult
import com.example.domain.ai.AiAgentService
import com.example.domain.filesystem.FileSystemEngine
import com.example.domain.github.GitHubService
import com.example.domain.terminal.TerminalEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import androidx.core.content.ContextCompat
import com.example.AgentForegroundService
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

    // Этапы мышления агента: Thinking → Responding → Executing
    private val _agentStage = MutableStateFlow(AgentStage.IDLE)
    val agentStage: StateFlow<AgentStage> = _agentStage.asStateFlow()

    private val _thinkingText = MutableStateFlow("")
    val thinkingText: StateFlow<String> = _thinkingText.asStateFlow()

    // Текущая джоба генерации (для кнопки «Стоп»)
    private var generationJob: Job? = null

    @Volatile
    private var stopRequested = false

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

    suspend fun listModels(provider: String, apiKey: String): Result<List<String>> {
        return aiAgentService.listModels(provider, apiKey)
    }

    // -------------------------------------------------------------
    // Вход (гость / GitHub) и репозитории
    // -------------------------------------------------------------

    private val authPrefs = application.getSharedPreferences("codestudio_auth", Context.MODE_PRIVATE)
    private val gitHubService = GitHubService()

    private val _entryMode = MutableStateFlow(authPrefs.getString("entry_mode", null))
    val entryMode: StateFlow<String?> = _entryMode.asStateFlow()

    private val _githubLogin = MutableStateFlow(authPrefs.getString("github_login", null))
    val githubLogin: StateFlow<String?> = _githubLogin.asStateFlow()

    private var githubToken: String? = authPrefs.getString("github_token", null)

    private val _githubRepos = MutableStateFlow<List<GitHubRepo>>(emptyList())
    val githubRepos: StateFlow<List<GitHubRepo>> = _githubRepos.asStateFlow()

    private val _githubDownloaded = MutableStateFlow<Set<String>>(emptySet())
    val githubDownloaded: StateFlow<Set<String>> = _githubDownloaded.asStateFlow()

    private val _githubStatus = MutableStateFlow<String?>(null)
    val githubStatus: StateFlow<String?> = _githubStatus.asStateFlow()

    private val _githubBusy = MutableStateFlow(false)
    val githubBusy: StateFlow<Boolean> = _githubBusy.asStateFlow()

    val gitHubReposRoot: File
        get() = File(fileSystemEngine.agentHomeDir, "repos")

    fun loginAsGuest() {
        authPrefs.edit().putString("entry_mode", "guest").apply()
        _entryMode.value = "guest"
    }

    suspend fun loginWithGitHub(token: String): Result<String> {
        return try {
            val login = gitHubService.validateToken(token)
            if (login == null) {
                Result.failure(Exception("Токен недействителен или GitHub недоступен. Нужен Personal Access Token с правом repo."))
            } else {
                authPrefs.edit()
                    .putString("entry_mode", "github")
                    .putString("github_token", token)
                    .putString("github_login", login)
                    .apply()
                githubToken = token
                _githubLogin.value = login
                _entryMode.value = "github"
                Result.success(login)
            }
        } catch (e: Exception) {
            Result.failure(Exception(e.localizedMessage ?: e.javaClass.simpleName))
        }
    }

    fun logoutGitHub() {
        authPrefs.edit()
            .remove("github_token")
            .remove("github_login")
            .remove("entry_mode")
            .apply()
        githubToken = null
        _githubLogin.value = null
        _entryMode.value = null
        _githubRepos.value = emptyList()
        _githubStatus.value = null
    }

    fun loadGitHubRepos() {
        val token = githubToken ?: return
        viewModelScope.launch {
            _githubBusy.value = true
            _githubStatus.value = "Загрузка репозиториев..."
            try {
                _githubRepos.value = gitHubService.listRepos(token)
                refreshDownloadedRepos()
                _githubStatus.value = null
            } catch (e: Exception) {
                _githubStatus.value = e.message ?: "Ошибка загрузки репозиториев"
            } finally {
                _githubBusy.value = false
            }
        }
    }

    fun refreshDownloadedRepos() {
        val root = gitHubReposRoot
        _githubDownloaded.value = if (root.exists()) {
            root.listFiles()?.filter { it.isDirectory }?.map { it.name }?.toSet() ?: emptySet()
        } else {
            emptySet()
        }
    }

    fun downloadGitHubRepo(repo: GitHubRepo) {
        val token = githubToken ?: return
        viewModelScope.launch {
            _githubBusy.value = true
            _githubStatus.value = "Скачивание ${repo.fullName}..."
            try {
                val dest = gitHubService.downloadRepo(token, repo, File(gitHubReposRoot, repo.name))
                refreshDownloadedRepos()
                refreshFiles()
                _githubStatus.value = "${repo.fullName} скачан в ${dest.absolutePath}"
            } catch (e: Exception) {
                _githubStatus.value = e.message ?: "Ошибка скачивания"
            } finally {
                _githubBusy.value = false
            }
        }
    }

    fun pushGitHubRepo(repo: GitHubRepo) {
        val token = githubToken ?: return
        viewModelScope.launch {
            _githubBusy.value = true
            _githubStatus.value = "Отправка изменений ${repo.fullName}..."
            try {
                val result = gitHubService.pushRepo(
                    token = token,
                    repo = repo,
                    dir = File(gitHubReposRoot, repo.name),
                    commitMessage = "CodeStudio: обновление файлов"
                )
                _githubStatus.value = when {
                    result.isEmpty && result.errors.isEmpty() -> "${repo.name}: изменений нет"
                    else -> buildString {
                        append(repo.name).append(": ")
                        val parts = mutableListOf<String>()
                        if (result.created > 0) parts.add("создано ${result.created}")
                        if (result.updated > 0) parts.add("обновлено ${result.updated}")
                        if (result.deleted > 0) parts.add("удалено ${result.deleted}")
                        if (result.errors.isNotEmpty()) parts.add("ошибок ${result.errors.size}")
                        append(parts.joinToString(", "))
                    }
                }
            } catch (e: Exception) {
                _githubStatus.value = e.message ?: "Ошибка отправки изменений"
            } finally {
                _githubBusy.value = false
            }
        }
    }

    fun openGitHubRepo(repo: GitHubRepo) {
        val dir = File(gitHubReposRoot, repo.name)
        if (dir.exists()) {
            navigateToDirectory(dir.absolutePath)
        }
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

    fun moveFileItem(item: FileSystemItem, destinationDir: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val source = File(item.path)
            val destDir = File(destinationDir)

            // Сначала быстрый rename (в пределах одной ФС), при неудаче — копирование + удаление
            var success = fileSystemEngine.moveItem(item.path, destinationDir)
            if (!success && source.exists() && destDir.isDirectory) {
                success = try {
                    val target = File(destDir, source.name)
                    val copied = if (source.isDirectory) {
                        source.copyRecursively(target, overwrite = true)
                    } else {
                        source.copyTo(target, overwrite = true)
                        true
                    }
                    if (copied) source.deleteRecursively() else false
                } catch (e: Exception) {
                    false
                }
            }

            fileLogDao.insertFileLog(
                FileLogEntity(
                    operation = "MOVE",
                    path = item.path,
                    details = if (success) {
                        "Перемещено '${item.name}' в '$destinationDir'"
                    } else {
                        "Не удалось переместить '${item.name}' в '$destinationDir'"
                    },
                    status = if (success) "SUCCESS" else "FAILED"
                )
            )

            if (success && _currentWorkingDir.value.startsWith(item.path)) {
                navigateToDirectory(destinationDir)
            } else {
                refreshFiles()
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
            val ext = item.extension.lowercase()

            // Бинарные файлы (фото, архивы, медиа) нельзя открывать текстовым редактором —
            // чтение мегабайтов байтов в строку и рендер раньше вешали UI
            val binaryExtensions = setOf(
                "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "heic",
                "mp3", "wav", "flac", "ogg", "m4a", "aac",
                "mp4", "mkv", "avi", "mov", "webm",
                "zip", "rar", "7z", "tar", "gz", "bz2", "xz",
                "apk", "aab", "dex", "so", "jar", "class",
                "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
                "ttf", "otf", "woff", "woff2", "jks", "keystore", "db", "sqlite"
            )
            if (ext in binaryExtensions) {
                showInfoCard(
                    "Файл не открыт",
                    "«${item.name}» — бинарный файл ($ext). Текстовый редактор поддерживает только текстовые файлы."
                )
                return@launch
            }
            if (item.sizeBytes > 2 * 1024 * 1024) {
                showInfoCard(
                    "Файл слишком большой",
                    "«${item.name}» (${item.formattedSize}) превышает лимит редактора 2 МБ."
                )
                return@launch
            }

            try {
                val content = fileSystemEngine.readFile(item.path)
                val artifact = Artifact(
                    id = UUID.randomUUID().toString(),
                    title = item.name,
                    type = ArtifactType.FILE_EDIT,
                    targetPath = item.path,
                    content = content,
                    language = when (ext) {
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
                showInfoCard(
                    "Не удалось открыть файл",
                    "«${item.name}»: ${e.message ?: e.javaClass.simpleName}"
                )
            }
        }
    }

    private fun showInfoCard(title: String, text: String) {
        _activeArtifact.value = Artifact(
            id = UUID.randomUUID().toString(),
            title = title,
            type = ArtifactType.SYSTEM_INFO,
            content = text,
            language = null,
            status = ArtifactStatus.IDLE
        )
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
            // Сохраняем правку и в самом артефакте, чтобы «Выполнить» не перезаписало файл старым содержимым
            updateArtifactSuspend(artifact.copy(content = newContent))
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
            // Закрываем диалог запроса СРАЗУ, чтобы он не «залипал» при любых сбоях ниже
            _pendingFolderPermission.value = null

            val granted = fileSystemEngine.registerPersistableFolderUri(uri, targetPath)
            try {
                grantedFolderDao.insertGrantedFolder(granted)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Ждём, пока список выданных папок реально обновится (Room Flow асинхронный),
            // иначе автовыполнение артефактов снова запросит то же самое разрешение
            withTimeoutOrNull(3000) {
                grantedFolders.first { list -> list.any { it.id == granted.id } }
            }

            val convId = _activeConversationId.value

            // Ищем сообщение модели с артефактами ДО вставки подтверждения,
            // иначе подтверждение само станет «последним сообщением ассистента» и агент «заглохнет»
            val msgs = chatDao.getMessagesForConversation(convId).first().map { parseEntityToMessage(it) }
            val lastAssistantMsg = msgs.lastOrNull { it.role == MessageRole.ASSISTANT }

            chatDao.insertMessage(
                MessageEntity(
                    id = UUID.randomUUID().toString(),
                    conversationId = convId,
                    role = MessageRole.SYSTEM.name,
                    content = "Разрешение на доступ к папке **${granted.displayName}** (`${granted.folderPath}`) получено и сохранено в системе (SAF). Продолжаю выполнение задачи...",
                    artifactsJson = "[]"
                )
            )

            // Auto-execute pending artifacts if in EXTRA mode or if previously blocked
            if (lastAssistantMsg != null && lastAssistantMsg.artifacts.isNotEmpty()) {
                for (artifact in lastAssistantMsg.artifacts) {
                    if (artifact.status == ArtifactStatus.IDLE && artifact.isAgentExecutable()) {
                        if (_operationMode.value != AgentOperationMode.SAFETY || !artifact.isDangerous) {
                            executeArtifactInternal(artifact)
                            if (_pendingFolderPermission.value != null) break
                        }
                    }
                }
                // Возобновляем цепочку агента после выполнения разблокированных действий
                maybeContinueAgentChain()
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
                    content = "Доступ к папке `${targetPath ?: "устройства"}` был отклонен пользователем. Агент не может продолжить работу с этой папкой без системного разрешения (SAF).",
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
        viewModelScope.launch {
            updateArtifactSuspend(
                artifact.copy(
                    status = ArtifactStatus.FAILED,
                    executionOutput = "Действие отклонено пользователем (Режим Safety)."
                )
            )
            if (_pendingDangerousArtifact.value?.id == artifact.id) {
                _pendingDangerousArtifact.value = null
            }
            maybeContinueAgentChain()
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
            executeArtifactInternal(artifact)
            // После выполнения — автоматически продолжаем цепочку агента, если все артефакты обработаны
            maybeContinueAgentChain()
        }
    }

    private suspend fun executeArtifactInternal(artifact: Artifact): Artifact {
        // Check folder authorization before executing
        val targetPath = artifact.targetPath
        if (targetPath != null && !fileSystemEngine.isFolderAuthorized(targetPath, grantedFolders.value)) {
            _pendingFolderPermission.value = fileSystemEngine.extractRequiredFolder(targetPath)
            return artifact
        }

        return when (artifact.type) {
            ArtifactType.TERMINAL_COMMAND, ArtifactType.CODE_SNIPPET -> {
                val cmdToRun = artifact.command
                    ?: if (artifact.type == ArtifactType.TERMINAL_COMMAND) artifact.content else null
                if (cmdToRun.isNullOrBlank()) return artifact

                // If command references an unauthorized path, trigger SAF
                val extractedPath = extractPathFromCommand(cmdToRun)
                if (extractedPath != null && !fileSystemEngine.isFolderAuthorized(extractedPath, grantedFolders.value)) {
                    _pendingFolderPermission.value = fileSystemEngine.extractRequiredFolder(extractedPath)
                    return artifact
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

                val updated = artifact.copy(
                    status = if (log.exitCode == 0) ArtifactStatus.SUCCESS else ArtifactStatus.FAILED,
                    exitCode = log.exitCode,
                    executionOutput = log.output.ifEmpty { log.errorOutput ?: "(Команда завершена, код: ${log.exitCode})" }
                )
                updateArtifactSuspend(updated)

                if (log.workingDir != _currentWorkingDir.value) {
                    _currentWorkingDir.value = log.workingDir
                }
                refreshFiles()
                updated
            }
            ArtifactType.FILE_CREATE, ArtifactType.FILE_EDIT -> {
                val updated = try {
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
                    artifact.copy(
                        status = ArtifactStatus.SUCCESS,
                        executionOutput = "Файл успешно сохранен на устройстве:\n$path"
                    )
                } catch (e: Exception) {
                    artifact.copy(
                        status = ArtifactStatus.FAILED,
                        executionOutput = "Ошибка сохранения: ${e.message}"
                    )
                }
                updateArtifactSuspend(updated)
                refreshFiles()
                updated
            }
            else -> artifact
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

    private suspend fun updateArtifactSuspend(updatedArtifact: Artifact) {
        val convId = _activeConversationId.value
        if (convId.isEmpty()) return
        val messages = chatDao.getMessagesForConversation(convId).first().map { parseEntityToMessage(it) }
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

    // -------------------------------------------------------------
    // Real Chat Messaging (Streaming & Error Handling)
    // -------------------------------------------------------------

    fun sendMessage(userPrompt: String, attachments: List<ChatAttachment> = emptyList()) {
        if ((userPrompt.isBlank() && attachments.isEmpty()) || _isGenerating.value) return
        val prompt = userPrompt.trim()
        val storedPrompt = buildString {
            append(prompt.ifBlank { "Посмотри прикреплённые материалы." })
            if (attachments.isNotEmpty()) {
                append("\n\nПрикреплено: ")
                append(attachments.joinToString { it.name })
            }
        }

        generationJob = viewModelScope.launch {
            val convId = _activeConversationId.value
            stopRequested = false

            // 1. Insert user message
            chatDao.insertMessage(
                MessageEntity(
                    id = UUID.randomUUID().toString(),
                    conversationId = convId,
                    role = MessageRole.USER.name,
                    content = storedPrompt,
                    artifactsJson = "[]"
                )
            )

            // Обновляем заголовок сессии после первого пользовательского сообщения
            val existingMsgs = chatDao.getMessagesForConversation(convId).first()
            if (existingMsgs.size <= 2) {
                val conv = chatDao.getConversationById(convId)
                if (conv != null) {
                val titleSnippet = if (storedPrompt.length > 28) storedPrompt.take(28) + "..." else storedPrompt
                    chatDao.insertConversation(conv.copy(title = titleSnippet, updatedAt = System.currentTimeMillis()))
                }
            }

            // Check active custom connection
            val activeConn = activeConnection.value
            if (activeConn == null) {
                val warningEntity = MessageEntity(
                    id = UUID.randomUUID().toString(),
                    conversationId = convId,
                    role = MessageRole.ASSISTANT.name,
                    content = """
**Подключение не настроено**

Добавьте и активируйте подключение:
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

            startAgentForegroundService()
            runAgentLoop(prompt, activeConn, attachments)
        }
    }

    /**
     * Остановка генерации и всей автономной цепочки пользователем (кнопка «Стоп»).
     */
    fun stopGeneration() {
        stopRequested = true
        generationJob?.cancel()
        stopAgentForegroundService()
    }

    private fun startAgentForegroundService() {
        val intent = Intent(getApplication(), AgentForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(getApplication(), intent)
        } else {
            getApplication<Application>().startService(intent)
        }
    }

    private fun stopAgentForegroundService() {
        getApplication<Application>().stopService(
            Intent(getApplication(), AgentForegroundService::class.java)
        )
    }

    /**
     * Ответ пользователя на вопрос агента (как question-tool в opencode):
     * ответ сохраняется в чат, и агент продолжает выполнение задачи.
     */
    fun answerAgentQuestion(artifact: Artifact, answer: String) {
        val trimmed = answer.trim()
        if (trimmed.isBlank() || _isGenerating.value) return
        val conn = activeConnection.value ?: return

        generationJob = viewModelScope.launch {
            stopRequested = false
            startAgentForegroundService()

            // Отмечаем вопрос отвеченным
            updateArtifactSuspend(
                artifact.copy(status = ArtifactStatus.SUCCESS, executionOutput = trimmed)
            )

            // Ответ пользователя — обычным сообщением в чат
            chatDao.insertMessage(
                MessageEntity(
                    id = UUID.randomUUID().toString(),
                    conversationId = _activeConversationId.value,
                    role = MessageRole.USER.name,
                    content = trimmed,
                    artifactsJson = "[]"
                )
            )

            runAgentLoop(
                "Мой ответ на твой вопрос «${artifact.content}»: «$trimmed». Продолжай задачу с учётом ответа и не задавай этот вопрос повторно. Если задача полностью выполнена — подведи итог без артефактов.",
                conn
            )
        }
    }

    /**
     * Цикл агента: запрос → ответ → выполнение артефактов → возврат результатов модели → ...
     * Агент НЕ останавливается, пока задача не выполнена полностью
     * (или пока пользователь не нажал «Стоп» / не исчерпан лимит итераций).
     */
    private suspend fun runAgentLoop(
        initialPrompt: String,
        conn: CustomConnection,
        initialAttachments: List<ChatAttachment> = emptyList()
    ) {
        var prompt = initialPrompt
        var iteration = 0
        _isGenerating.value = true
        try {
            while (iteration < MAX_AGENT_ITERATIONS && !stopRequested) {
                iteration++
                val result = requestModelTurn(
                    prompt,
                    conn,
                    if (iteration == 0) initialAttachments else emptyList()
                ) ?: break
                val artifacts = result.artifacts

                if (artifacts.isEmpty()) {
                    // Модель спросила «продолжить?» — продолжаем автоматически, не останавливаемся
                    val lower = result.responseText.lowercase()
                    val asksToContinue = lower.contains("продолжить?") || lower.contains("продолжать?") ||
                        lower.contains("продолжение?") || lower.contains("continue?") ||
                        lower.contains("нужно ли мне продолжить") || lower.contains("хотите, чтобы я продолжил") ||
                        lower.contains("мне продолжить?")
                    if (asksToContinue && iteration < MAX_AGENT_ITERATIONS && !stopRequested) {
                        insertSystemMessage("Агент запросил продолжение — выполняю задачу дальше автоматически.")
                        prompt = "Продолжай выполнение задачи с того места, где остановился. Не спрашивай подтверждений — действуй. Если задача полностью выполнена — подведи итог без артефактов."
                        continue
                    }
                    break // Задача выполнена — модель ответила без артефактов
                }

                // Как в opencode: если агент задал вопрос — он ОБЯЗАН дождаться ответа пользователя.
                // Цепочка возобновится через answerAgentQuestion().
                val pendingQuestion = artifacts.firstOrNull { it.type == ArtifactType.QUESTION }
                if (pendingQuestion != null) {
                    break
                }

                val unauthorizedFolder = findUnauthorizedFolder(artifacts)
                if (unauthorizedFolder != null) {
                    // Пауза на запрос SAF-разрешения; цепочка продолжится после выдачи разрешения
                    _pendingFolderPermission.value = unauthorizedFolder
                    break
                }

                if (_operationMode.value != AgentOperationMode.SAFETY) {
                    // EXTRA: автономное выполнение всех действий и возврат результатов модели
                    _agentStage.value = AgentStage.EXECUTING
                    val executed = mutableListOf<Artifact>()
                    for (art in artifacts) {
                        if (stopRequested) break
                        if (art.status == ArtifactStatus.IDLE && art.isAgentExecutable()) {
                            executed.add(executeArtifactInternal(art))
                            if (_pendingFolderPermission.value != null) break
                        }
                    }
                    if (stopRequested || _pendingFolderPermission.value != null) break

                    // План — информационный артефакт: если кроме него выполнять нечего, ход завершён
                    val actionable = artifacts.filter { it.type != ArtifactType.PLAN }
                    val forSummary = executed.ifEmpty { actionable }
                    if (forSummary.isEmpty()) break

                    val summary = buildExecutionSummary(forSummary)
                    insertSystemMessage(summary)
                    prompt = buildContinuationPrompt(summary)
                } else {
                    // SAFETY: артефакты выполняет пользователь из карточек; цепочка продолжится
                    // автоматически через maybeContinueAgentChain(), когда все будут обработаны.
                    val dangerous = artifacts.firstOrNull { it.isDangerous }
                    if (dangerous != null) {
                        _pendingDangerousArtifact.value = dangerous
                    }
                    break
                }
            }
            if (iteration >= MAX_AGENT_ITERATIONS && !stopRequested) {
                insertSystemMessage("Достигнут лимит автономных итераций ($MAX_AGENT_ITERATIONS). Цепочка выполнения остановлена — отправьте сообщение, чтобы продолжить.")
            }
        } catch (_: CancellationException) {
            // Остановлено пользователем — частичный ответ уже сохранён в requestModelTurn
        } finally {
            _isGenerating.value = false
            _agentStage.value = AgentStage.IDLE
            _thinkingText.value = ""
            stopAgentForegroundService()
        }
    }

    /**
     * Одна итерация запроса к модели: стримит Thinking (рассуждения) и ответ, сохраняет сообщение.
     * Возвращает null при ошибке или остановке пользователем.
     */
    private suspend fun requestModelTurn(
        prompt: String,
        conn: CustomConnection,
        attachments: List<ChatAttachment> = emptyList()
    ): AgentExecutionResult? {
        val convId = _activeConversationId.value
        val assistantMsgId = UUID.randomUUID().toString()

        _thinkingText.value = ""
        _agentStage.value = AgentStage.CONNECTING

        chatDao.insertMessage(
            MessageEntity(
                id = assistantMsgId,
                conversationId = convId,
                role = MessageRole.ASSISTANT.name,
                content = "...",
                artifactsJson = "[]"
            )
        )

        var lastStreamUpdateTime = 0L
        var lastPartialText = ""
        var streamWriteJob: Job? = null

        return try {
            val result = aiAgentService.executeCustomPrompt(
                prompt = prompt,
                connection = conn,
                workingDir = _currentWorkingDir.value,
                conversationHistory = currentMessages.value,
                operationMode = _operationMode.value,
                grantedFolders = grantedFolders.value,
                attachments = attachments,
                onPartialReasoning = { reasoningText ->
                    // Этап "Thinking" — модель анализирует задачу перед ответом
                    _agentStage.value = AgentStage.THINKING
                    _thinkingText.value = reasoningText
                },
                onRetry = { attemptNo, delaySec ->
                    // Провайдер не ответил — повтор с нарастающей задержкой
                    _agentStage.value = AgentStage.RETRYING
                    _thinkingText.value = "Провайдер не отвечает — повторная попытка #$attemptNo через ${delaySec} с..."
                },
                onPartialText = { streamedText ->
                    lastPartialText = streamedText
                    _agentStage.value = AgentStage.RESPONDING
                    val now = System.currentTimeMillis()
                    // Update DB throttled every 150ms to keep UI smooth
                    if (now - lastStreamUpdateTime > 150) {
                        lastStreamUpdateTime = now
                        streamWriteJob = viewModelScope.launch {
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

            streamWriteJob?.join()

            // Final save of completed message with artifacts
            chatDao.insertMessage(
                MessageEntity(
                    id = assistantMsgId,
                    conversationId = convId,
                    role = MessageRole.ASSISTANT.name,
                    content = result.responseText,
                    artifactsJson = serializeArtifacts(result.artifacts)
                )
            )
            result
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                streamWriteJob?.join()
                chatDao.insertMessage(
                    MessageEntity(
                        id = assistantMsgId,
                        conversationId = convId,
                        role = MessageRole.ASSISTANT.name,
                        content = stoppedText(lastPartialText),
                        artifactsJson = "[]"
                    )
                )
            }
            throw e
        } catch (e: Exception) {
            val content = if (stopRequested) {
                stoppedText(lastPartialText)
            } else {
                "Ошибка: ${e.message ?: "неизвестная ошибка при запросе к API"}"
            }
            withContext(NonCancellable) {
                streamWriteJob?.join()
                chatDao.insertMessage(
                    MessageEntity(
                        id = assistantMsgId,
                        conversationId = convId,
                        role = MessageRole.ASSISTANT.name,
                        content = content,
                        artifactsJson = "[]"
                    )
                )
            }
            null
        }
    }

    private fun stoppedText(partial: String): String {
        val base = partial.trim()
        return if (base.isEmpty()) {
            "Генерация остановлена пользователем."
        } else {
            "$base\n\n_Остановлено пользователем._"
        }
    }

    private fun findUnauthorizedFolder(artifacts: List<Artifact>): String? {
        return artifacts.firstNotNullOfOrNull { art ->
            val path = art.targetPath ?: extractPathFromCommand(art.command ?: "")
            if (path != null && !fileSystemEngine.isFolderAuthorized(path, grantedFolders.value)) {
                fileSystemEngine.extractRequiredFolder(path)
            } else null
        }
    }

    private suspend fun insertSystemMessage(content: String) {
        chatDao.insertMessage(
            MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = _activeConversationId.value,
                role = MessageRole.SYSTEM.name,
                content = content,
                artifactsJson = "[]"
            )
        )
    }

    private fun buildExecutionSummary(artifacts: List<Artifact>): String {
        val sb = StringBuilder("Результаты выполнения действий:\n")
        for (a in artifacts) {
            val statusLabel = when (a.status) {
                ArtifactStatus.SUCCESS -> "УСПЕХ"
                ArtifactStatus.FAILED -> "ОШИБКА"
                ArtifactStatus.REJECTED -> "ОТКЛОНЕНО ПОЛЬЗОВАТЕЛЕМ"
                else -> "НЕ ВЫПОЛНЕНО"
            }
            sb.append("- ${a.title}: $statusLabel")
            a.exitCode?.let { sb.append(" (код выхода: $it)") }
            sb.append("\n")
            val out = a.executionOutput?.trim()
            if (!out.isNullOrBlank()) {
                sb.append("```\n").append(out.take(1200)).append("\n```\n")
            }
        }
        return sb.toString()
    }

    private fun buildContinuationPrompt(summary: String): String {
        return summary + "\n\nПроанализируй результаты выполнения и ПРОДОЛЖАЙ исходную задачу без остановок и вопросов. " +
            "Если нужны новые действия — сгенерируй следующие артефакты. Если действие завершилось ошибкой — попробуй другой подход. " +
            "Если задача полностью выполнена — подведи краткий итог БЕЗ артефактов."
    }

    private fun Artifact.isAgentExecutable(): Boolean =
        isExecutable() ||
            type == ArtifactType.FILE_CREATE ||
            type == ArtifactType.FILE_EDIT ||
            (type == ArtifactType.CODE_SNIPPET && !command.isNullOrBlank())

    /**
     * Автопродолжение цепочки агента после того, как пользователь завершил все артефакты
     * последнего сообщения ассистента (режим Safety / возобновление после SAF-разрешения).
     */
    private suspend fun maybeContinueAgentChain() {
        if (_isGenerating.value || stopRequested) return
        val conn = activeConnection.value ?: return
        val convId = _activeConversationId.value
        if (convId.isEmpty()) return

        val messages = chatDao.getMessagesForConversation(convId).first().map { parseEntityToMessage(it) }
        // Берём именно ПОСЛЕДНЕЕ сообщение ассистента: если после артефактов уже был
        // итоговый текстовый ответ — цепочка завершена и перезапускать её не нужно.
        val lastAssistant = messages.lastOrNull { it.role == MessageRole.ASSISTANT } ?: return
        // План и вопрос — информационные артефакты, в проверке «всё ли выполнено» не участвуют
        val arts = lastAssistant.artifacts.filter { it.type != ArtifactType.PLAN && it.type != ArtifactType.QUESTION }
        if (arts.isEmpty()) return

        val stillPending = arts.any {
            it.status == ArtifactStatus.IDLE ||
                it.status == ArtifactStatus.EXECUTING ||
                it.status == ArtifactStatus.AWAITING_CONFIRMATION
        }
        if (stillPending) return

        val anySuccess = arts.any { it.status == ArtifactStatus.SUCCESS }
        val anyFailure = arts.any { it.status == ArtifactStatus.FAILED }
        val isExtra = _operationMode.value != AgentOperationMode.SAFETY
        // Safety: продолжаем, только если хоть что-то реально выполнено успешно;
        // Extra: продолжаем и после ошибок, чтобы агент исправил их сам.
        if (!anySuccess && !(isExtra && anyFailure)) return

        val summary = buildExecutionSummary(arts)
        generationJob = viewModelScope.launch {
            insertSystemMessage(summary)
            runAgentLoop(buildContinuationPrompt(summary), conn)
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
                val planArr = JSONArray()
                for (pi in a.planItems) {
                    planArr.put(JSONObject().apply {
                        put("content", pi.content)
                        put("status", pi.status.name)
                    })
                }
                put("planItems", planArr)
                val optArr = JSONArray()
                for (opt in a.questionOptions) {
                    optArr.put(opt)
                }
                put("questionOptions", optArr)
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

                val planItems = mutableListOf<PlanItem>()
                obj.optJSONArray("planItems")?.let { arr ->
                    for (j in 0 until arr.length()) {
                        val po = arr.optJSONObject(j) ?: continue
                        val content = po.optString("content")
                        if (content.isBlank()) continue
                        val status = runCatching { PlanItemStatus.valueOf(po.optString("status", PlanItemStatus.PENDING.name)) }
                            .getOrDefault(PlanItemStatus.PENDING)
                        planItems.add(PlanItem(content, status))
                    }
                }

                val questionOptions = mutableListOf<String>()
                obj.optJSONArray("questionOptions")?.let { arr ->
                    for (j in 0 until arr.length()) {
                        val opt = arr.optString(j)
                        if (opt.isNotBlank()) questionOptions.add(opt)
                    }
                }

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
                        executionOutput = obj.optString("executionOutput").ifEmpty { null },
                        planItems = planItems,
                        questionOptions = questionOptions
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

    companion object {
        // Максимум автономных итераций «запрос → выполнение → возврат результатов» за одну цепочку
        private const val MAX_AGENT_ITERATIONS = 12
    }
}
