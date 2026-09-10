package com.example.domain.terminal

import android.content.Context
import android.os.Build
import com.example.domain.filesystem.FileSystemEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Реальное proot-окружение Linux внутри приложения (аналог того, как это делают
 * Termux + proot-distro или UserLAnd), а не заглушка-имитация файловой структуры.
 *
 * Важно: работает только потому, что `targetSdk` приложения намеренно понижен до
 * 28 (см. app/build.gradle.kts). Начиная с targetSdk 29+ SELinux запрещает
 * execve() любых исполняемых файлов, которые приложение само записало в свою
 * приватную папку данных во время работы — это сделало бы невозможным запуск
 * скачанного proot и любых бинарников внутри rootfs (та же причина, по которой
 * Termux распространяется только через F-Droid с targetSdk 28, а не через
 * Play Store).
 *
 * Порядок работы при первом запуске (требует интернет на устройстве):
 *   1. Скачать статический бинарник `proot` из репозитория пакетов Termux
 *      (это обычный Android/bionic ELF, запускается напрямую, без rootfs).
 *   2. Скачать минимальный rootfs Alpine Linux (официальный образ, проверяется
 *      по SHA256 из latest-releases.yaml) и распаковать его в
 *      [FileSystemEngine.linuxRootDir], не затирая уже существующие там данные
 *      пользователя (например, склонированные репозитории).
 *   3. Настроить DNS внутри rootfs и установить OpenJDK + bash через `apk`.
 * После этого [isBootstrapped] становится true, и все последующие команды
 * терминала выполняются через proot, а не через системный Android `sh`.
 */
class ProotEnvironment(
    private val context: Context,
    private val fileSystemEngine: FileSystemEngine
) {

    companion object {
        private const val PROOT_VERSION = "5.1.107.92"
        // SHA256 официальных .deb-пакетов proot из репозитория Termux,
        // зафиксированы для конкретной версии, чтобы исключить подмену файла.
        private val PROOT_SHA256 = mapOf(
            "aarch64" to "1f1c983509701f6826f568482c70673ee453a9ba38c9f5fa445a472d6b7524e9",
            "x86_64" to "70236632826c30ec0245082b633bbc7ef1e9fa5531bd51bd4f20231bfcdc999b",
            "arm" to "245927a1d20a059af367b02058d823023054e4cc186e762304f2f3d5a729a7e1",
            "i686" to "4c7cab199ca6bb1f8fa89b1da3f24d7b9775a4100987290fae3f916a14884817"
        )
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .build()

    /** Бинарник proot, скачанный отдельно от rootfs — он исполняется на самом Android. */
    val prootBinary: File = File(context.filesDir, "proot-bin/proot")

    /** Маркер успешного завершения полной настройки (proot + rootfs + JDK). */
    private val bootstrapMarker: File = File(context.filesDir, "proot-bin/.bootstrap-complete")

    val rootfsDir: File get() = fileSystemEngine.linuxRootDir

    val isBootstrapped: Boolean
        get() = bootstrapMarker.exists() &&
            prootBinary.exists() && prootBinary.canExecute() &&
            File(rootfsDir, "bin/sh").exists()

    val isSupportedArch: Boolean get() = termuxArch() != null && alpineArch() != null

    fun termuxArch(): String? = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a" -> "aarch64"
        "armeabi-v7a" -> "arm"
        "x86_64" -> "x86_64"
        "x86" -> "i686"
        else -> null
    }

    fun alpineArch(): String? = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a" -> "aarch64"
        "armeabi-v7a" -> "armv7"
        "x86_64" -> "x86_64"
        "x86" -> "x86"
        else -> null
    }

    /**
     * Полная настройка окружения: скачать proot, rootfs Alpine, установить JDK.
     * Идемпотентна: повторный вызов после успешного завершения ничего не делает.
     */
    suspend fun bootstrap(onProgress: (String) -> Unit = {}): Result<Unit> = withContext(Dispatchers.IO) {
        if (isBootstrapped) return@withContext Result.success(Unit)

        val termuxArch = termuxArch()
        val alpineArch = alpineArch()
        if (termuxArch == null || alpineArch == null) {
            return@withContext Result.failure(
                IllegalStateException("Архитектура процессора не поддерживается: ${Build.SUPPORTED_ABIS.joinToString()}")
            )
        }

        try {
            prootBinary.parentFile?.mkdirs()

            onProgress("Скачивание proot...")
            val prootDebUrl = "https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_${PROOT_VERSION}_${termuxArch}.deb"
            val prootDebFile = File(context.cacheDir, "proot-$PROOT_VERSION-$termuxArch.deb")
            downloadFile(prootDebUrl, prootDebFile, PROOT_SHA256[termuxArch])
            onProgress("Распаковка proot...")
            extractProotFromDeb(prootDebFile, prootBinary)
            prootBinary.setExecutable(true, false)
            prootDebFile.delete()
            if (!prootBinary.canExecute()) {
                return@withContext Result.failure(
                    IllegalStateException("Не удалось сделать proot исполняемым — вероятно, targetSdk приложения выше 28")
                )
            }

            onProgress("Определение актуальной версии Alpine Linux...")
            val rootfsInfo = fetchAlpineMinirootfsInfo(alpineArch)
                ?: return@withContext Result.failure(IllegalStateException("Не удалось получить метаданные rootfs Alpine"))

            val rootfsUrl = "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/$alpineArch/${rootfsInfo.file}"
            val rootfsFile = File(context.cacheDir, rootfsInfo.file)
            onProgress("Скачивание rootfs Alpine Linux (~4 МБ)...")
            downloadFile(rootfsUrl, rootfsFile, rootfsInfo.sha256)
            onProgress("Распаковка rootfs...")
            rootfsDir.mkdirs()
            extractTarGz(rootfsFile, rootfsDir)
            rootfsFile.delete()

            onProgress("Настройка DNS...")
            File(rootfsDir, "etc").mkdirs()
            File(rootfsDir, "etc/resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")

            // Домашняя папка агента создаётся FileSystemEngine отдельно и не входит
            // в архив Alpine, поэтому её нужно (пере)создать после распаковки.
            fileSystemEngine.agentHomeDir.mkdirs()

            onProgress("Установка OpenJDK 17 и bash (требуется интернет, может занять несколько минут)...")
            val installResult = runProotCommand("apk add --no-cache openjdk17 bash", "/")
            if (installResult.exitCode != 0) {
                return@withContext Result.failure(
                    IllegalStateException("Не удалось установить пакеты внутри rootfs: ${installResult.errorOutput ?: installResult.output}")
                )
            }

            bootstrapMarker.parentFile?.mkdirs()
            bootstrapMarker.writeText("proot=$PROOT_VERSION\nalpine=${rootfsInfo.file}\n")
            onProgress("Окружение Linux готово")
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Строит полный список аргументов для запуска [command] внутри rootfs через
     * proot. [workingDirVirtualPath] — путь уже в системе координат rootfs
     * (например, "/home/codestudio/Defense"), а не реальный путь на Android.
     */
    fun buildProotCommand(command: String, workingDirVirtualPath: String): List<String> {
        return listOf(
            prootBinary.absolutePath,
            "-r", rootfsDir.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "${fileSystemEngine.primaryStorageDir.absolutePath}:/mnt/android",
            "-w", workingDirVirtualPath,
            "-0",
            "/bin/sh", "-c", command
        )
    }

    /**
     * Преобразует реальный путь на Android в путь внутри rootfs (для proot).
     * Возвращает null, если путь не принадлежит ни rootfs, ни основному хранилищу.
     */
    fun toVirtualPath(realDir: File): String? {
        val rootPath = rootfsDir.absolutePath
        val realPath = realDir.absolutePath
        return when {
            realPath == rootPath -> "/"
            realPath.startsWith("$rootPath/") -> realPath.removePrefix(rootPath)
            realPath == fileSystemEngine.primaryStorageDir.absolutePath -> "/mnt/android"
            realPath.startsWith(fileSystemEngine.primaryStorageDir.absolutePath + "/") -> {
                val rel = realPath.removePrefix(fileSystemEngine.primaryStorageDir.absolutePath).removePrefix("/")
                "/mnt/android/$rel"
            }
            else -> null
        }
    }

    private suspend fun runProotCommand(command: String, workDirVirtual: String): ProcessResult = withContext(Dispatchers.IO) {
        val argv = buildProotCommand(command, workDirVirtual)
        val process = ProcessBuilder(argv)
            .directory(rootfsDir)
            .redirectErrorStream(false)
            .start()

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val outThread = Thread {
            runCatching { process.inputStream.bufferedReader().forEachLine { stdout.append(it).append('\n') } }
        }
        val errThread = Thread {
            runCatching { process.errorStream.bufferedReader().forEachLine { stderr.append(it).append('\n') } }
        }
        outThread.start()
        errThread.start()

        val finished = process.waitFor(10, TimeUnit.MINUTES)
        if (!finished) {
            process.destroy()
            outThread.join(500)
            errThread.join(500)
            return@withContext ProcessResult(124, stdout.toString(), "Команда превысила лимит времени (10 минут)")
        }
        outThread.join(2000)
        errThread.join(2000)
        ProcessResult(process.exitValue(), stdout.toString().trimEnd(), stderr.toString().trimEnd().ifEmpty { null })
    }

    private fun downloadFile(url: String, dest: File, expectedSha256: String?) {
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code} при скачивании $url")
            val body = response.body ?: throw IllegalStateException("Пустой ответ при скачивании $url")
            dest.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
        if (expectedSha256 != null) {
            val actual = sha256(dest)
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                dest.delete()
                throw IllegalStateException("Нарушена целостность файла $url: ожидался SHA256 $expectedSha256, получен $actual")
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Достаёт бинарник proot из data.tar.xz внутри .deb-пакета Termux. */
    private fun extractProotFromDeb(debFile: File, outFile: File) {
        debFile.inputStream().buffered().use { bis ->
            ArArchiveInputStream(bis).use { ar ->
                var found = false
                var entry = ar.nextEntry
                while (entry != null) {
                    if (entry.name == "data.tar.xz") {
                        XZCompressorInputStream(ar).use { xzIn ->
                            TarArchiveInputStream(xzIn).use { tarIn ->
                                var tarEntry = tarIn.nextTarEntry
                                while (tarEntry != null) {
                                    val normalized = tarEntry.name.removePrefix("./")
                                    if (normalized == "usr/bin/proot") {
                                        outFile.parentFile?.mkdirs()
                                        FileOutputStream(outFile).use { out -> tarIn.copyTo(out) }
                                        found = true
                                        break
                                    }
                                    tarEntry = tarIn.nextTarEntry
                                }
                            }
                        }
                        break
                    }
                    entry = ar.nextEntry
                }
                if (!found) throw IllegalStateException("Внутри .deb-пакета не найден usr/bin/proot")
            }
        }
    }

    private fun extractTarGz(archive: File, destDir: File) {
        val destCanonical = destDir.canonicalPath
        archive.inputStream().buffered().use { bis ->
            GzipCompressorInputStream(bis).use { gzIn ->
                TarArchiveInputStream(gzIn).use { tarIn ->
                    var entry = tarIn.nextTarEntry
                    while (entry != null) {
                        val outFile = File(destDir, entry.name)
                        // Защита от zip-slip: путь не должен выходить за пределы destDir
                        if (!outFile.canonicalPath.startsWith(destCanonical)) {
                            entry = tarIn.nextTarEntry
                            continue
                        }
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { out -> tarIn.copyTo(out) }
                            // tar хранит права доступа в восьмеричном поле mode; Android API
                            // не восстанавливает их автоматически, поэтому выставляем бит
                            // исполнения вручную — иначе /bin/sh, busybox и т.д. не запустятся.
                            // 0x49 = 0100|0010|0001 (восьмеричные биты исполнения owner/group/other).
                            val anyExecBit = entry.mode and 0x49 != 0
                            if (anyExecBit) outFile.setExecutable(true, false)
                        }
                        entry = tarIn.nextTarEntry
                    }
                }
            }
        }
    }

    /**
     * Разбирает https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/<arch>/latest-releases.yaml
     * и находит запись для flavor=alpine-minirootfs: имя файла и SHA256.
     */
    private fun fetchAlpineMinirootfsInfo(arch: String): AlpineReleaseInfo? {
        val url = "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/$arch/latest-releases.yaml"
        val request = Request.Builder().url(url).build()
        val text = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string() ?: return null
        }

        var currentFlavor: String? = null
        var currentFile: String? = null
        var currentSha256: String? = null
        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            when {
                line.startsWith("-") -> {
                    if (currentFlavor == "alpine-minirootfs" && currentFile != null && currentSha256 != null) {
                        return AlpineReleaseInfo(currentFile!!, currentSha256!!)
                    }
                    currentFlavor = null
                    currentFile = null
                    currentSha256 = null
                }
                line.startsWith("flavor:") -> currentFlavor = line.substringAfter("flavor:").trim().trim('"')
                line.startsWith("file:") -> currentFile = line.substringAfter("file:").trim().trim('"')
                line.startsWith("sha256:") -> currentSha256 = line.substringAfter("sha256:").trim().trim('"')
            }
        }
        if (currentFlavor == "alpine-minirootfs" && currentFile != null && currentSha256 != null) {
            return AlpineReleaseInfo(currentFile!!, currentSha256!!)
        }
        return null
    }

    private data class AlpineReleaseInfo(val file: String, val sha256: String)

    private data class ProcessResult(val exitCode: Int, val output: String, val errorOutput: String?)
}
