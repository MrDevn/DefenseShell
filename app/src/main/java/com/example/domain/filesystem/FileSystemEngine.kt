package com.example.domain.filesystem

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.example.data.db.entities.GrantedFolderEntity
import com.example.data.model.FileSystemItem
import java.io.File

class FileSystemEngine(private val context: Context) {

    /**
     * Primary external storage root on the real device (/storage/emulated/0).
     */
    val primaryStorageDir: File by lazy {
        val ext = Environment.getExternalStorageDirectory()
        if (ext != null && (ext.exists() || ext.canRead())) {
            ext
        } else {
            context.filesDir
        }
    }

    /**
     * Private "virtual Linux root" for the agent — analog of the proot rootfs
     * UserLand/Termux keep under /data/data/<package>/files/... . It lives in the
     * app's private sandbox (context.filesDir), which Android always lets the app
     * read/write without any SAF prompt or MANAGE_EXTERNAL_STORAGE permission,
     * and which is invisible to the user through the normal system file manager —
     * exactly like a real Linux distro root is invisible/irrelevant from "outside".
     *
     * NOTE: this gives us the *storage location and directory layout* of a proot
     * environment. It does not, by itself, give us real proot process isolation
     * (a chroot-like namespace with its own libc/coreutils ELF binaries). Wiring
     * that up requires bundling a per-ABI static `proot` binary plus a Linux
     * rootfs archive (e.g. Alpine/Debian minirootfs) as app assets and extracting
     * them here on first launch, then exec-ing commands through proot instead of
     * plain ProcessBuilder("sh"). Those binary assets aren't part of this change —
     * see ProotEnvironment.kt for the extension point.
     */
    val linuxRootDir: File by lazy {
        File(context.filesDir, "rootfs")
    }

    /**
     * Dedicated Agent Home directory: <app-private>/rootfs/home/claudeshell
     * Shown to the user/agent as the virtual path /home/claudeshell — the real
     * $HOME analog, matching how UserLand exposes /home/userland/.
     */
    val agentHomeDir: File by lazy {
        File(linuxRootDir, "home/claudeshell")
    }

    /** Virtual path the agent/UI should display for $HOME (not a real OS path). */
    val homeDisplayPath: String = "/home/claudeshell"

    /** Virtual mount point under which SAF-granted Android folders are exposed. */
    val androidMountDisplayRoot: String = "/mnt/android"

    val defaultWorkingDir: File
        get() = agentHomeDir

    init {
        ensureHomeDirectoryExists()
    }

    /**
     * Automatically creates the agent's home directory (and a minimal FHS-style
     * skeleton around it: /tmp, /etc, /var/log) if they don't exist yet.
     */
    fun ensureHomeDirectoryExists() {
        try {
            agentHomeDir.mkdirs()
            File(linuxRootDir, "tmp").mkdirs()
            File(linuxRootDir, "etc").mkdirs()
            File(linuxRootDir, "var/log").mkdirs()

            val readme = File(agentHomeDir, "README.md")
            if (!readme.exists()) {
                readme.writeText(
                    """# Домашняя директория ClaudeShell (${'$'}HOME)
Путь: $homeDisplayPath

Это виртуальная домашняя директория агента внутри приватного песочничного
хранилища приложения (аналог /home/ в UserLand/Termux) — не в /sdcard/.

В этой директории агент полностью автономен:
- Создание, изменение, удаление файлов и папок
- Запуск shell-скриптов и терминальных команд
- Сохранение логов, кэша и конфигураций

Доступ к реальным папкам устройства (Download, DCIM, произвольные папки) — это
отдельный механизм: они запрашиваются через системный проводник (Storage Access
Framework, кнопка «Использовать эту папку») и после этого видны здесь как
$androidMountDisplayRoot/<имя_папки>.
""".trimIndent(),
                    Charsets.UTF_8
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Checks if a path is inside the agent's home directory.
     * Within home, all operations are completely autonomous without permission prompts.
     */
    fun isInsideHome(path: String): Boolean {
        return try {
            val resolved = resolvePath(path).canonicalFile
            val home = agentHomeDir.canonicalFile
            resolved.absolutePath.startsWith(home.absolutePath)
        } catch (_: Exception) {
            path.startsWith(agentHomeDir.absolutePath) ||
                path.startsWith(homeDisplayPath) ||
                path.startsWith("~/") || path == "~"
        }
    }

    /**
     * Checks if an external folder has already been authorized via SAF.
     */
    fun isFolderAuthorized(path: String, grantedFolders: List<GrantedFolderEntity>): Boolean {
        if (isInsideHome(path)) return true
        return try {
            val targetFile = resolvePath(path).canonicalFile
            for (granted in grantedFolders) {
                val grantedFile = resolvePath(granted.folderPath).canonicalFile
                if (targetFile.absolutePath.startsWith(grantedFile.absolutePath)) {
                    return true
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Extracts the target folder path that needs SAF authorization.
     */
    fun extractRequiredFolder(path: String): String {
        val file = resolvePath(path)
        val folder = if (file.isDirectory) file else (file.parentFile ?: file)
        return folder.absolutePath
    }

    /**
     * Takes persistable URI permission for SAF and creates a GrantedFolderEntity.
     */
    fun registerPersistableFolderUri(uri: Uri, requestedPath: String? = null): GrantedFolderEntity {
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val docId = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (_: Exception) {
            null
        }

        val folderPath = when {
            docId != null && docId.startsWith("primary:") -> {
                val rel = docId.removePrefix("primary:")
                File(primaryStorageDir, rel).absolutePath
            }
            !requestedPath.isNullOrBlank() -> resolvePath(requestedPath).absolutePath
            else -> resolvePath(uri.lastPathSegment ?: "external").absolutePath
        }

        val displayName = File(folderPath).name.ifEmpty { "Папка устройства" }

        return GrantedFolderEntity(
            id = folderPath,
            folderPath = folderPath,
            treeUriString = uri.toString(),
            displayName = displayName,
            grantedAt = System.currentTimeMillis()
        )
    }

    /**
     * Standard device folder shortcuts for fast user navigation.
     */
    val standardDirectories: Map<String, File> by lazy {
        val map = linkedMapOf<String, File>()

        map["Домашняя папка (\$HOME)"] = agentHomeDir
        map["Хранилище (/sdcard)"] = primaryStorageDir

        val download = File(primaryStorageDir, "Download")
        if (download.exists() || download.mkdirs()) map["Download"] = download

        val documents = File(primaryStorageDir, "Documents")
        if (documents.exists() || documents.mkdirs()) map["Documents"] = documents

        val dcim = File(primaryStorageDir, "DCIM")
        if (dcim.exists()) map["DCIM"] = dcim

        val pictures = File(primaryStorageDir, "Pictures")
        if (pictures.exists()) map["Pictures"] = pictures

        val music = File(primaryStorageDir, "Music")
        if (music.exists()) map["Music"] = music

        map["Песочница приложения"] = context.filesDir
        map
    }

    /**
     * Checks whether the app has full file access on the device.
     */
    fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            val read = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            val write = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            read && write
        }
    }

    /**
     * Creates an intent to open Android System Settings for All Files Access.
     */
    fun createAllFilesAccessIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } catch (_: Exception) {
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    /**
     * Lists real files and folders in the target directory on the device.
     */
    fun listFiles(dirPath: String): List<FileSystemItem> {
        val targetDir = resolvePath(dirPath)
        if (!targetDir.exists() || !targetDir.isDirectory) {
            return emptyList()
        }

        val files = targetDir.listFiles() ?: return emptyList()
        return files.map { file ->
            FileSystemItem(
                name = file.name,
                path = file.absolutePath,
                isDirectory = file.isDirectory,
                sizeBytes = if (file.isDirectory) 0L else file.length(),
                lastModified = file.lastModified(),
                extension = file.extension,
                isHidden = file.name.startsWith("."),
                childCount = if (file.isDirectory) file.list()?.size ?: 0 else 0
            )
        }.sortedWith(
            compareByDescending<FileSystemItem> { it.isDirectory }
                .thenBy { it.name.lowercase() }
        )
    }

    /**
     * Reads text content of a real file on the device.
     */
    fun readFile(filePath: String): String {
        val targetFile = resolvePath(filePath)
        if (!targetFile.exists()) {
            throw IllegalArgumentException("Файл не найден: $filePath")
        }
        if (targetFile.isDirectory) {
            throw IllegalArgumentException("Указанный путь является папкой, а не файлом: $filePath")
        }
        if (targetFile.length() > 10 * 1024 * 1024) {
            throw IllegalStateException("Файл слишком большой для текстового редактора (> 10 MB)")
        }
        return targetFile.readText(Charsets.UTF_8)
    }

    /**
     * Writes content to a real file on the device.
     * Returns previous content if the file already existed (for undo/audit).
     */
    fun writeFile(filePath: String, content: String): Pair<File, String?> {
        val targetFile = resolvePath(filePath)
        val parent = targetFile.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }

        val backupContent = if (targetFile.exists() && targetFile.isFile && targetFile.length() < 2 * 1024 * 1024) {
            try { targetFile.readText(Charsets.UTF_8) } catch (_: Exception) { null }
        } else {
            null
        }

        targetFile.writeText(content, Charsets.UTF_8)
        return Pair(targetFile, backupContent)
    }

    /**
     * Creates a real directory on the device.
     */
    fun createDirectory(dirPath: String): Boolean {
        val targetDir = resolvePath(dirPath)
        return targetDir.mkdirs() || targetDir.exists()
    }

    /**
     * Deletes a real file or folder recursively.
     * Returns previous file text for undo if it was a small file.
     */
    fun deleteItem(path: String): Pair<Boolean, String?> {
        val target = resolvePath(path)
        if (!target.exists()) {
            return Pair(false, null)
        }

        val backup = if (target.isFile && target.length() < 2 * 1024 * 1024) {
            try { target.readText(Charsets.UTF_8) } catch (_: Exception) { null }
        } else {
            null
        }

        val success = if (target.isDirectory) {
            target.deleteRecursively()
        } else {
            target.delete()
        }

        return Pair(success, backup)
    }

    /**
     * Renames a real file or folder on the device.
     */
    fun renameItem(path: String, newName: String): Boolean {
        val target = resolvePath(path)
        if (!target.exists()) return false
        val newTarget = File(target.parentFile ?: target, newName)
        return target.renameTo(newTarget)
    }

    /**
     * Moves a real file or folder to a new destination directory.
     */
    fun moveItem(sourcePath: String, destinationDirPath: String): Boolean {
        val source = resolvePath(sourcePath)
        val destDir = resolvePath(destinationDirPath)
        if (!source.exists() || !destDir.isDirectory) return false
        val newFile = File(destDir, source.name)
        return source.renameTo(newFile)
    }

    /**
     * Recursively searches for files matching a pattern or age criteria.
     */
    fun searchFiles(
        baseDirPath: String,
        extensionFilter: String? = null,
        namePattern: String? = null,
        minAgeDays: Int? = null,
        maxDepth: Int = 4
    ): List<FileSystemItem> {
        val startDir = resolvePath(baseDirPath)
        if (!startDir.exists() || !startDir.isDirectory) return emptyList()

        val results = mutableListOf<FileSystemItem>()
        val currentTime = System.currentTimeMillis()
        val minAgeMillis = minAgeDays?.let { it * 24L * 60L * 60L * 1000L }

        fun walk(dir: File, currentDepth: Int) {
            if (currentDepth > maxDepth) return
            val list = dir.listFiles() ?: return

            for (file in list) {
                if (file.isDirectory) {
                    walk(file, currentDepth + 1)
                } else {
                    var matches = true

                    if (!extensionFilter.isNullOrBlank()) {
                        val cleanExt = extensionFilter.removePrefix(".").lowercase()
                        if (file.extension.lowercase() != cleanExt) {
                            matches = false
                        }
                    }

                    if (matches && !namePattern.isNullOrBlank()) {
                        if (!file.name.contains(namePattern, ignoreCase = true)) {
                            matches = false
                        }
                    }

                    if (matches && minAgeMillis != null) {
                        val age = currentTime - file.lastModified()
                        if (age < minAgeMillis) {
                            matches = false
                        }
                    }

                    if (matches) {
                        results.add(
                            FileSystemItem(
                                name = file.name,
                                path = file.absolutePath,
                                isDirectory = false,
                                sizeBytes = file.length(),
                                lastModified = file.lastModified(),
                                extension = file.extension,
                                isHidden = file.name.startsWith(".")
                            )
                        )
                    }
                }
            }
        }

        walk(startDir, 0)
        return results
    }

    /**
     * Resolves a file path string to an absolute File.
     * ~, $HOME and /home/claudeshell all resolve to agentHomeDir (the private
     * rootfs home). /mnt/android/<name> and /sdcard/... resolve to real Android
     * storage (the separate, SAF-gated mechanism). Any other absolute path is
     * treated as living inside the virtual Linux root (linuxRootDir), since the
     * app has no way to touch the device's real "/" without root.
     */
    fun resolvePath(path: String, baseDir: String? = null): File {
        val cleanPath = path.trim()
        val base = if (!baseDir.isNullOrBlank()) File(baseDir) else agentHomeDir

        return when {
            cleanPath.startsWith("~/") -> {
                File(agentHomeDir, cleanPath.removePrefix("~/"))
            }
            cleanPath == "~" || cleanPath == "\$HOME" -> {
                agentHomeDir
            }
            cleanPath == homeDisplayPath || cleanPath.startsWith("$homeDisplayPath/") -> {
                File(agentHomeDir, cleanPath.removePrefix(homeDisplayPath).removePrefix("/"))
            }
            cleanPath == androidMountDisplayRoot || cleanPath.startsWith("$androidMountDisplayRoot/") -> {
                File(primaryStorageDir, cleanPath.removePrefix(androidMountDisplayRoot).removePrefix("/"))
            }
            cleanPath.startsWith("/sdcard/") -> {
                File(primaryStorageDir, cleanPath.removePrefix("/sdcard/"))
            }
            cleanPath == "/sdcard" -> {
                primaryStorageDir
            }
            cleanPath.startsWith("/") -> {
                // Real sandbox paths (already under the app's private data dir,
                // e.g. agentHomeDir.absolutePath) must be used as-is, otherwise
                // they would get doubly nested under linuxRootDir.
                val sandboxRoots = listOf(context.filesDir.absolutePath, context.dataDir.absolutePath)
                if (sandboxRoots.any { cleanPath == it || cleanPath.startsWith("$it/") }) {
                    File(cleanPath)
                } else {
                    // Any other absolute path (/etc, /tmp, /usr, ...) lives inside the
                    // app-private virtual root, not the real Android filesystem root.
                    File(linuxRootDir, cleanPath.removePrefix("/"))
                }
            }
            else -> {
                File(base, cleanPath)
            }
        }
    }

    /**
     * Checks if a command or file operation is potentially dangerous,
     * requiring explicit user confirmation before executing.
     */
    fun checkDangerousOperation(operation: String, target: String): Pair<Boolean, String?> {
        val lowerTarget = target.lowercase().trim()
        val lowerOp = operation.lowercase().trim()

        if (lowerOp.contains("delete") || lowerOp.contains("rm") || lowerOp.contains("wipe")) {
            if (lowerTarget == "/" || lowerTarget == "/storage" || lowerTarget == "/storage/emulated/0" || lowerTarget == "~") {
                return Pair(true, "Попытка удаления корневого каталога хранилища или системы!")
            }
            val file = resolvePath(target)
            if (file.isDirectory) {
                val count = file.list()?.size ?: 0
                return Pair(true, "Удаление папки '$target' (${count} элементов внутри). Это действие необратимо.")
            }
            return Pair(true, "Удаление файла '$target'.")
        }

        return Pair(false, null)
    }

    fun checkDangerousCommand(command: String): Pair<Boolean, String?> {
        val lower = command.trim().lowercase()

        val dangerousPatterns = listOf(
            "rm -rf /" to "Попытка рекурсивного удаления корневого раздела системы",
            "rm -rf *" to "Рекурсивное удаление всех файлов в текущей директории",
            "rm -rf ." to "Рекурсивное удаление текущей директории",
            "rm -r" to "Рекурсивное удаление файлов и папок",
            "rm " to "Команда удаления файлов",
            ":(){ :|:& };:" to "Вредоносный скрипт fork bomb",
            "mkfs" to "Форматирование файловой системы",
            "dd if=" to "Прямая поблочная запись в накопитель",
            "chmod 777" to "Сброс прав доступа на общедоступные",
            "> /dev/" to "Прямая запись в системное устройство"
        )

        for ((pattern, reason) in dangerousPatterns) {
            if (lower.contains(pattern)) {
                return Pair(true, reason)
            }
        }

        return Pair(false, null)
    }
}
