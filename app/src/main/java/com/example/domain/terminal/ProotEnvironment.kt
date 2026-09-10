package com.example.domain.terminal

import android.content.Context
import android.os.Build
import android.system.Os
import com.example.domain.filesystem.FileSystemEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Настоящее Linux-окружение (Ubuntu) внутри приложения — так же, как это делают
 * UserLAnd и Termux + proot-distro, а не имитация структуры папок.
 *
 * Работает только потому, что `targetSdk` намеренно понижен до 28
 * (см. app/build.gradle.kts): начиная с targetSdk 29+ SELinux запрещает
 * execve() любых исполняемых файлов, записанных приложением в свою приватную
 * папку во время работы, — без этого невозможно запустить ни скачанный proot,
 * ни java внутри rootfs.
 *
 * Что происходит при первом запуске терминала (нужен интернет):
 *   1. Скачивается `proot` из официального репозитория Termux — это единственный
 *      рабочий источник proot, собранного под Android/bionic, включая патч
 *      `--link2symlink`, без которого dpkg/apt ломаются на Android-ФС.
 *      Эта сборка НЕ статическая: ей нужны libtalloc.so.2 и libandroid-shmem.so,
 *      а путь к ним и к loader'ам зашит как /data/data/com.termux/... . Поэтому:
 *        - loader/loader32 извлекаются рядом с бинарником и передаются через
 *          PROOT_LOADER / PROOT_LOADER_32;
 *        - зависимости скачиваются отдельными пакетами и подключаются через
 *          LD_LIBRARY_PATH (RUNPATH бинарника указывает на несуществующий у нас
 *          каталог Termux);
 *        - PROOT_TMP_DIR / PROOT_L2S_DIR переназначаются на наши каталоги,
 *          иначе proot пытается писать в /data/data/com.termux/files/usr/tmp.
 *   2. Скачивается официальный образ Ubuntu Base (тот же дистрибутив, что
 *      использует UserLAnd), проверяется по SHA256SUMS с cdimage.ubuntu.com и
 *      распаковывается в [FileSystemEngine.linuxRootDir]. Распаковка обязательно
 *      воспроизводит symlink'и (/bin -> usr/bin и т.д.) и hardlink'и, иначе
 *      Ubuntu не запускается.
 *   3. Внутри Ubuntu через apt ставятся OpenJDK 17 и базовые инструменты, после
 *      чего работают `java`, `./gradlew build`, git, curl и т.д.
 */
class ProotEnvironment(
    private val context: Context,
    private val fileSystemEngine: FileSystemEngine
) {

    companion object {
        private const val PROOT_VERSION = "5.1.107.92"
        private const val LIBTALLOC_VERSION = "2.4.3"
        private const val LIBSHMEM_VERSION = "0.7"
        private const val UBUNTU_RELEASE = "22.04"
        private const val UBUNTU_POINT_RELEASE = "22.04.5"

        private const val TERMUX_REPO = "https://packages.termux.dev/apt/termux-main"

        /** SHA256 официальных пакетов Termux (фиксированы на конкретные версии). */
        private val PROOT_SHA256 = mapOf(
            "aarch64" to "1f1c983509701f6826f568482c70673ee453a9ba38c9f5fa445a472d6b7524e9",
            "x86_64" to "70236632826c30ec0245082b633bbc7ef1e9fa5531bd51bd4f20231bfcdc999b",
            "arm" to "245927a1d20a059af367b02058d823023054e4cc186e762304f2f3d5a729a7e1",
            "i686" to "4c7cab199ca6bb1f8fa89b1da3f24d7b9775a4100987290fae3f916a14884817"
        )
        private val LIBTALLOC_SHA256 = mapOf(
            "aarch64" to "ac81ad623d74c209718b9f3acb2dd702cc8a88c431e820d212229910b4db29da",
            "x86_64" to "7ca2eaae2e53b28228a01301bc410b62845403d6317c25b8e0a7f40681de0628",
            "arm" to "cd56f87007e487c8025fac2df2a27b2bc58102344040a527eaa6fa7527d18f9b",
            "i686" to "7b79f8b5e41d597940551ef9bd5a2fef7978f519300af8fc5c498d34a93f575a"
        )
        private val LIBSHMEM_SHA256 = mapOf(
            "aarch64" to "0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6",
            "x86_64" to "ffa9e4c87467b158b148d0ff92dda796aa038276c2075af3269cdcdb06f25797",
            "arm" to "5832fd11dca9be2a288dd8fbc2b2799b289c812c7a8764f1f8234c425aa64ce5",
            "i686" to "e9ccecee1aeed7dd70ac93bf44a6ba1bf6d4cb9559aabeb086acdc89accb4ba4"
        )

        // Пакеты, без которых бессмысленна полноценная сборка проектов.
    private const val APT_PACKAGES =
            "openjdk-17-jdk-headless ca-certificates git curl unzip zip file less nano"

        private const val TEMURIN_JDK_URL =
            "https://api.adoptium.net/v3/binary/latest/17/ga/linux/%s/jdk/hotspot/normal/eclipse"

        /** Пауза перед автоматическим повтором настройки после неудачи. */
        private const val BOOTSTRAP_RETRY_COOLDOWN_MS = 2 * 60 * 1000L

        // Переменные, которые нужны только самому proot на стороне Android;
        // внутри Ubuntu их быть не должно (пути там не существуют).
        private val HOST_ONLY_ENV = listOf(
            "LD_LIBRARY_PATH", "PROOT_LOADER", "PROOT_LOADER_32",
            "PROOT_TMP_DIR", "PROOT_L2S_DIR"
        )
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.MINUTES)
        .build()

    /** Каталог proot вместе с loader'ами, зависимостями и рабочими папками. */
    private val prootRoot = File(context.filesDir, "proot-bin")
    private val prootLibDir = File(prootRoot, "lib")
    private val prootTmpDir = File(prootRoot, "tmp")
    private val prootL2sDir = File(prootRoot, "l2s")

    val prootBinary: File = File(prootRoot, "bin/proot")
    private val loaderBinary: File = File(prootRoot, "libexec/proot/loader")
    private val loader32Binary: File = File(prootRoot, "libexec/proot/loader32")

    private val bootstrapMarker: File = File(prootRoot, ".bootstrap-complete")
    private val prootStageMarker: File = File(prootRoot, ".stage-proot")
    private val rootfsStageMarker: File = File(prootRoot, ".stage-rootfs")
    private val aptStageMarker: File = File(prootRoot, ".stage-apt")

    @Volatile
    private var lastFailureAt: Long = 0L

    val rootfsDir: File get() = fileSystemEngine.linuxRootDir

    val isBootstrapped: Boolean
        get() = bootstrapMarker.exists() &&
            prootFilesPresent() && rootfsLooksUsable() &&
            detectJavaHome() != null

    val isSupportedArch: Boolean get() = termuxArch() != null && ubuntuArch() != null

    /** Архитектура в терминах репозитория Termux (для бинарников). */
    fun termuxArch(): String? = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a" -> "aarch64"
        "armeabi-v7a" -> "arm"
        "x86_64" -> "x86_64"
        "x86" -> "i686"
        else -> null
    }

    /** Архитектура в терминах образов Ubuntu Base. Для x86 (i386) образов нет. */
    fun ubuntuArch(): String? = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a" -> "arm64"
        "armeabi-v7a" -> "armhf"
        "x86_64" -> "amd64"
        else -> null
    }

    /**
     * Окружение, обязательное для запуска proot и для жизни Ubuntu внутри него.
     */
    val requiredEnv: Map<String, String>
        get() {
            val env = linkedMapOf(
                // Termux-сборка proot ищет loader по чужому пути /data/data/com.termux/...
                "PROOT_LOADER" to loaderBinary.absolutePath,
                "PROOT_LOADER_32" to loader32Binary.absolutePath,
                // ...и свой tmp — без переопределения proot падает на записи.
                "PROOT_TMP_DIR" to prootTmpDir.absolutePath,
                "PROOT_L2S_DIR" to prootL2sDir.absolutePath,
                // RUNPATH бинарника указывает на каталог Termux, которого у нас нет,
                // поэтому libtalloc.so.2 и libandroid-shmem.so ищем через LD_LIBRARY_PATH.
                "LD_LIBRARY_PATH" to prootLibDir.absolutePath,
                "HOME" to fileSystemEngine.homeDisplayPath,
                "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "TERM" to "xterm-256color",
                "LANG" to "C.UTF-8",
                "TMPDIR" to "/tmp",
                "DEBIAN_FRONTEND" to "noninteractive"
            )
            // JAVA_HOME выставляем только если каталог реально существует: gradlew
            // доверяет JAVA_HOME больше, чем PATH, и с неверным значением падает с
            // "JAVA_HOME is set to an invalid directory". Без него gradlew берёт
            // java из PATH, что тоже корректно работает.
            detectJavaHome()?.let {
                env["JAVA_HOME"] = it
                env["PATH"] = "$it/bin:${env["PATH"]}"
            }
            return env
        }

    /** Определяет установленный JDK внутри rootfs (путь в координатах Ubuntu). */
    private fun detectJavaHome(): String? {
        val bundled = File(rootfsDir, "opt/temurin-jdk-17")
        if (File(bundled, "bin/java").isFile) return "/opt/temurin-jdk-17"
        val jvmDir = File(rootfsDir, "usr/lib/jvm")
        val defaultJava = File(jvmDir, "default-java")
        if (File(defaultJava, "bin/java").isFile) return "/usr/lib/jvm/default-java"
        val candidate = jvmDir.listFiles()
            ?.filter { it.name.startsWith("java-") && File(it, "bin/java").isFile }
            ?.sortedByDescending { it.name }
            ?.firstOrNull()
        return candidate?.let { "/usr/lib/jvm/${it.name}" }
    }

    /**
     * Полная настройка окружения. Идемпотентна: после успеха повторный вызов
     * сразу возвращает успех.
     */
    suspend fun bootstrap(onProgress: (String) -> Unit = {}): Result<Unit> {
        val result = doBootstrap(onProgress)
        // После неудачи не долбим сеть на каждой команде: терминал должен
        // оставаться отзывчивым, а повторить настройку можно явно (setup-ubuntu)
        // или просто через [BOOTSTRAP_RETRY_COOLDOWN_MS].
        lastFailureAt = if (result.isFailure) System.currentTimeMillis() else 0L
        return result
    }

    /**
     * True, если предыдущая попытка настройки провалилась совсем недавно и
     * автоматический повтор пока отложен.
     */
    val isInFailureCooldown: Boolean
        get() = System.currentTimeMillis() - lastFailureAt < BOOTSTRAP_RETRY_COOLDOWN_MS

    /** Сбрасывает cooldown, чтобы следующая команда снова пробовала настройку. */
    fun resetFailureCooldown() {
        lastFailureAt = 0L
    }

    private suspend fun doBootstrap(onProgress: (String) -> Unit = {}): Result<Unit> = withContext(Dispatchers.IO) {
        if (isBootstrapped) return@withContext Result.success(Unit)

        val termuxArch = termuxArch()
        val ubuntuArch = ubuntuArch()
        if (termuxArch == null || ubuntuArch == null) {
            return@withContext Result.failure(
                IllegalStateException(
                    "Архитектура ${Build.SUPPORTED_ABIS.joinToString()} не поддерживается: " +
                        "для неё нет образа Ubuntu Base"
                )
            )
        }

        try {
            prootRoot.mkdirs()
            prootLibDir.mkdirs()
            prootTmpDir.mkdirs()
            prootL2sDir.mkdirs()

            // Настройка разбита на этапы с маркерами: при повторной попытке
            // (например, если apt упал из-за сети) уже скачанные proot и Ubuntu
            // не загружаются заново.

            // --- Этап 1. proot + loader'ы + зависимости ------------------------------
            val prootStage = "proot=$PROOT_VERSION;talloc=$LIBTALLOC_VERSION;" +
                "shmem=$LIBSHMEM_VERSION;arch=$termuxArch"
            if (!stageDone(prootStageMarker, prootStage) || !prootFilesPresent()) {
                onProgress("Скачивание proot...")
                fetchTermuxPackage(
                    poolPath = "pool/main/p/proot/proot_${PROOT_VERSION}_${termuxArch}.deb",
                    sha256 = PROOT_SHA256[termuxArch]
                ) { deb -> extractProotBinaries(deb, prootRoot) }

                onProgress("Скачивание библиотек для proot...")
                fetchTermuxPackage(
                    poolPath = "pool/main/libt/libtalloc/libtalloc_${LIBTALLOC_VERSION}_${termuxArch}.deb",
                    sha256 = LIBTALLOC_SHA256[termuxArch]
                ) { deb -> extractSharedLibs(deb, prootLibDir) }
                fetchTermuxPackage(
                    poolPath = "pool/main/liba/libandroid-shmem/libandroid-shmem_${LIBSHMEM_VERSION}_${termuxArch}.deb",
                    sha256 = LIBSHMEM_SHA256[termuxArch]
                ) { deb -> extractSharedLibs(deb, prootLibDir) }

                listOf(prootBinary, loaderBinary, loader32Binary).forEach { it.setExecutable(true, false) }
                if (!prootFilesPresent()) {
                    return@withContext Result.failure(
                        IllegalStateException("Не удалось распаковать proot, его loader'ы или зависимости")
                    )
                }
                markStage(prootStageMarker, prootStage)
            }

            // --- Этап 2. Ubuntu rootfs -----------------------------------------------
            val rootfsStage = "ubuntu=$UBUNTU_POINT_RELEASE;arch=$ubuntuArch"
            if (!stageDone(rootfsStageMarker, rootfsStage) || !rootfsLooksUsable()) {
                // Гибрид из двух разных дистрибутивов неработоспособен: если раньше
                // сюда распаковывали Alpine, зачищаем rootfs, сохраняя домашнюю папку.
                if (File(rootfsDir, "etc/alpine-release").exists()) {
                    onProgress("Удаление старого окружения (ваши файлы сохраняются)...")
                    wipeRootfsPreservingHome()
                }

                onProgress("Определение контрольных сумм Ubuntu Base...")
                val rootfsName = "ubuntu-base-$UBUNTU_POINT_RELEASE-base-$ubuntuArch.tar.gz"
                val rootfsUrl =
                    "https://cdimage.ubuntu.com/ubuntu-base/releases/$UBUNTU_RELEASE/release/$rootfsName"
                val rootfsFile = File(context.cacheDir, rootfsName)
                val expectedSha = fetchUbuntuSha256(rootfsName)

                onProgress("Скачивание Ubuntu $UBUNTU_POINT_RELEASE (~30 МБ)...")
                downloadFile(rootfsUrl, rootfsFile, expectedSha)

                onProgress("Распаковка Ubuntu rootfs...")
                rootfsDir.mkdirs()
                extractTarGz(rootfsFile, rootfsDir)
                rootfsFile.delete()
                markStage(rootfsStageMarker, rootfsStage)
            }

            // --- Этап 3. Настройка гостевой системы ----------------------------------
            // Выполняется всегда: конфиг apt может отсутствовать даже при уже
            // распакованном rootfs (например, после прошлой неудачной попытки).
            onProgress("Настройка сети, домашней папки и apt...")
            configureGuest()

            // --- Этап 4. JDK и инструменты -------------------------------------------
            val aptStage = "apt=$UBUNTU_POINT_RELEASE;jdk=17"
            if (!stageDone(aptStageMarker, aptStage) || detectJavaHome() == null) {
                onProgress("Установка OpenJDK 17 и инструментов через apt (нужен интернет, несколько минут)...")
                // APT::Sandbox::User=root обязателен: в proot root поддельный, и
                // штатный сброс привилегий apt на пользователя _apt падает с
                // "Could not switch saved set-user-ID". update дополнительно
                // повторяем трижды — сеть на телефоне нестабильна.
                val apt = "apt-get -o APT::Sandbox::User=root"
                val aptCommand =
                    "ok=0; for i in 1 2 3; do $apt update -y && ok=1 && break; sleep 3; done; " +
                        "if [ \"\$ok\" != \"1\" ]; then echo 'apt-get update: 3 неудачные попытки' >&2; exit 100; fi; " +
                        "($apt install -y --no-install-recommends $APT_PACKAGES || " +
                        "$apt -f install -y) && dpkg --configure -a"
                val install = runProotCommand(
                    aptCommand,
                    workDirVirtual = "/",
                    home = "/root"
                )
                if (detectJavaHome() == null) {
                    onProgress("apt не установил Java, скачивание рабочего OpenJDK напрямую...")
                    runCatching { installTemurinJdk(termuxArch) }
                        .onFailure { fallbackError ->
                            return@withContext Result.failure(
                                IllegalStateException(
                                    "apt-get завершился с кодом ${install.exitCode}, а fallback JDK тоже не установлен: " +
                                        "${fallbackError.message}\n" +
                                        "apt stdout:\n${install.output.takeLast(1400)}\n" +
                                        "apt stderr:\n${install.errorOutput.orEmpty().takeLast(1000)}"
                                )
                            )
                        }
                }
                if (detectJavaHome() == null) {
                    return@withContext Result.failure(
                        IllegalStateException("После настройки не найден рабочий Java runtime")
                    )
                }
                markStage(aptStageMarker, aptStage)
            }

            bootstrapMarker.parentFile?.mkdirs()
            bootstrapMarker.writeText("$prootStage;$rootfsStage;$aptStage\n")
            onProgress("Окружение Ubuntu готово")
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Полный список аргументов proot для запуска [command] внутри Ubuntu.
     * [workingDirVirtualPath] — путь в системе координат rootfs
     * (например, "/home/codestudio/Defense"), а не реальный путь Android.
     */
    fun buildProotCommand(command: String, workingDirVirtualPath: String): List<String> {
        // Переменные proot/LD_LIBRARY_PATH нужны только на стороне Android;
        // внутри Ubuntu эти пути не существуют и могут ломать тулчейны.
        val cleanup = HOST_ONLY_ENV.joinToString(" ") { "unset $it;" }
        return listOf(
            prootBinary.absolutePath,
            // Android-ФС не поддерживает hardlink'и, а dpkg/apt и часть тулчейнов
            // на них полагаются — без этого флага Ubuntu внутри proot ломается.
            "--link2symlink",
            "--kill-on-exit",
            "-r", rootfsDir.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            // В Android-овском /dev нет /dev/shm, а он нужен JVM и некоторым
            // инструментам; подставляем вместо него обычную папку внутри rootfs.
            "-b", "${File(rootfsDir, "var/shm").absolutePath}:/dev/shm",
            "-b", "${fileSystemEngine.primaryStorageDir.absolutePath}:/mnt/android",
            "-w", workingDirVirtualPath,
            "-0",
            "/bin/bash", "-lc", "$cleanup $command"
        )
    }

    /**
     * Реальный путь Android -> путь внутри rootfs. Null, если путь не относится
     * ни к rootfs, ни к основному хранилищу.
     */
    fun toVirtualPath(realDir: File): String? {
        val rootPath = rootfsDir.absolutePath
        val realPath = realDir.absolutePath
        val storagePath = fileSystemEngine.primaryStorageDir.absolutePath
        return when {
            realPath == rootPath -> "/"
            realPath.startsWith("$rootPath/") -> realPath.removePrefix(rootPath)
            realPath == storagePath -> "/mnt/android"
            realPath.startsWith("$storagePath/") ->
                "/mnt/android/" + realPath.removePrefix(storagePath).removePrefix("/")
            else -> null
        }
    }

    // ------------------------------------------------------------------
    // Шаги настройки
    // ------------------------------------------------------------------

    private fun stageDone(marker: File, expected: String): Boolean =
        marker.exists() && runCatching { marker.readText().trim() == expected }.getOrDefault(false)

    private fun markStage(marker: File, value: String) {
        marker.parentFile?.mkdirs()
        marker.writeText("$value\n")
    }

    private fun prootFilesPresent(): Boolean =
        prootBinary.canExecute() &&
            loaderBinary.exists() && loader32Binary.exists() &&
            File(prootLibDir, "libtalloc.so.2").exists() &&
            File(prootLibDir, "libandroid-shmem.so").exists()

    private fun rootfsLooksUsable(): Boolean =
        File(rootfsDir, "bin/bash").exists() &&
            File(rootfsDir, "etc/os-release").exists() &&
            !File(rootfsDir, "etc/alpine-release").exists()

    /**
     * Базовая настройка гостевой Ubuntu: DNS, домашняя папка, passwd и критичный
     * для proot конфиг apt.
     */
    private fun configureGuest() {
        File(rootfsDir, "etc").mkdirs()

        // В ubuntu-base /etc/resolv.conf — симлинк на /run/systemd/resolve/...,
        // которого у нас нет: запись через симлинк упала бы с FileNotFoundException.
        // Поэтому сначала убираем симлинк и создаём обычный файл.
        val resolvConf = File(rootfsDir, "etc/resolv.conf")
        runCatching { Os.remove(resolvConf.absolutePath) }
        resolvConf.parentFile?.mkdirs()
        resolvConf.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")

        File(rootfsDir, "root").mkdirs()
        File(rootfsDir, "tmp").mkdirs()
        // Каталог подменяется на /dev/shm внутри proot (в Android-овском /dev его нет).
        File(rootfsDir, "var/shm").mkdirs()
        // Пакеты Ubuntu не должны запускать systemd-службы во время postinst:
        // внутри proot нет настоящего init и такие попытки ломают dpkg.
        val policyRc = File(rootfsDir, "usr/sbin/policy-rc.d")
        policyRc.parentFile?.mkdirs()
        policyRc.writeText("#!/bin/sh\nexit 101\n")
        policyRc.setExecutable(true, false)
        val systemctlStub = File(rootfsDir, "usr/local/bin/systemctl")
        systemctlStub.parentFile?.mkdirs()
        systemctlStub.writeText("#!/bin/sh\nexit 0\n")
        systemctlStub.setExecutable(true, false)
        fileSystemEngine.agentHomeDir.mkdirs()
        ensurePasswdEntry()

        // apt по умолчанию сбрасывает привилегии своих http-методов на пользователя
        // _apt. В proot root поддельный (-0), поэтому setresuid/getresuid дают
        // несогласованный результат и метод падает:
        //   "Could not switch saved set-user-ID" / "Method http has died unexpectedly".
        // Отключение песочницы apt — штатный способ работы в proot-окружениях
        // (так же делают UserLAnd и Termux proot-distro).
        val aptConfDir = File(rootfsDir, "etc/apt/apt.conf.d")
        aptConfDir.mkdirs()
        File(aptConfDir, "99proot-sandbox").writeText("APT::Sandbox::User \"root\";\n")
    }

    private fun fetchTermuxPackage(poolPath: String, sha256: String?, consume: (File) -> Unit) {
        val fileName = poolPath.substringAfterLast('/')
        val dest = File(context.cacheDir, fileName)
        downloadFile("$TERMUX_REPO/$poolPath", dest, sha256)
        try {
            consume(dest)
        } finally {
            dest.delete()
        }
    }

    /**
     * Создаёт пользователя codestudio. UID/GID намеренно 1001: в ubuntu-base уже
     * есть пользователь ubuntu с uid 1000, а дубликаты uid ломают dpkg и apt.
     */
    private fun ensurePasswdEntry() {
        val home = fileSystemEngine.homeDisplayPath
        val passwd = File(rootfsDir, "etc/passwd")
        val passwdLine = "codestudio:x:1001:1001:CodeStudio Agent:$home:/bin/bash"
        val existingPasswd = if (passwd.exists()) passwd.readText() else ""
        if (existingPasswd.lines().none { it.startsWith("codestudio:") }) {
            val prefix = if (existingPasswd.isNotBlank() && !existingPasswd.endsWith("\n")) "\n" else ""
            passwd.appendText("$prefix$passwdLine\n")
        }

        val group = File(rootfsDir, "etc/group")
        val groupLine = "codestudio:x:1001:"
        val existingGroup = if (group.exists()) group.readText() else ""
        if (existingGroup.lines().none { it.startsWith("codestudio:") }) {
            val prefix = if (existingGroup.isNotBlank() && !existingGroup.endsWith("\n")) "\n" else ""
            group.appendText("$prefix$groupLine\n")
        }
    }

    private fun wipeRootfsPreservingHome() {
        val home = fileSystemEngine.agentHomeDir
        val backup = File(context.filesDir, "home-backup-tmp")
        val hadContent = home.exists() && (home.listFiles()?.isNotEmpty() == true)
        if (hadContent) {
            backup.deleteRecursively()
            // Не смогли сохранить — rootfs не трогаем, чтобы не потерять файлы.
            if (!home.renameTo(backup)) return
        }
        rootfsDir.deleteRecursively()
        rootfsDir.mkdirs()
        if (hadContent && backup.exists()) {
            home.parentFile?.mkdirs()
            backup.renameTo(home)
        }
    }

    private suspend fun runProotCommand(
        command: String,
        workDirVirtual: String,
        home: String
    ): ProcessResult = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(buildProotCommand(command, workDirVirtual))
            .directory(rootfsDir)
            .apply {
                environment().clear()
                environment().putAll(requiredEnv)
                environment()["HOME"] = home
            }
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

        val finished = process.waitFor(20, TimeUnit.MINUTES)
        if (!finished) {
            process.destroy()
            outThread.join(500)
            errThread.join(500)
            return@withContext ProcessResult(124, stdout.toString(), "Команда превысила лимит времени (20 минут)")
        }
        outThread.join(3000)
        errThread.join(3000)
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
                throw IllegalStateException(
                    "Нарушена целостность $url: ожидался SHA256 $expectedSha256, получен $actual"
                )
            }
        }
    }

    /**
     * Запасной способ установки JDK без dpkg. Нужен для proot, где postinst
     * openjdk иногда ломается на Android-ядре или неполном systemd.
     */
    private fun installTemurinJdk(termuxArch: String) {
        val apiArch = when (termuxArch) {
            "aarch64" -> "aarch64"
            "arm" -> "arm"
            "x86_64" -> "x64"
            else -> throw IllegalStateException("Temurin JDK не поддерживает архитектуру $termuxArch")
        }
        val archive = File(context.cacheDir, "temurin-jdk-17-$apiArch.tar.gz")
        if (!archive.exists() || archive.length() == 0L) {
            downloadFile(TEMURIN_JDK_URL.format(apiArch), archive, null)
        }

        val extractDir = File(rootfsDir, "opt/.temurin-extract")
        extractDir.deleteRecursively()
        extractDir.mkdirs()
        extractTarGz(archive, extractDir)
        val source = extractDir.listFiles()?.firstOrNull { it.isDirectory }
            ?: throw IllegalStateException("В архиве Temurin не найден каталог JDK")
        val target = File(rootfsDir, "opt/temurin-jdk-17")
        target.deleteRecursively()
        if (!source.renameTo(target)) {
            source.copyRecursively(target, overwrite = true)
        }
        extractDir.deleteRecursively()
        if (!File(target, "bin/java").isFile) {
            throw IllegalStateException("Архив Temurin распакован без bin/java")
        }
    }

    private fun fetchUbuntuSha256(fileName: String): String? {
        val url = "https://cdimage.ubuntu.com/ubuntu-base/releases/$UBUNTU_RELEASE/release/SHA256SUMS"
        val text = httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string() ?: return null
        }
        // Формат строки: "<sha256> *<имя файла>"
        return text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.endsWith(fileName) }
            ?.substringBefore(' ')
            ?.takeIf { it.length == 64 }
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

    /**
     * Читает data.tar.* из .deb-пакета Termux и передаёт нужные записи в [handle].
     * Termux хранит файлы по пути data/data/com.termux/files/usr/..., поэтому
     * сопоставление идёт по суффиксу, а не по префиксу "usr/".
     */
    private fun forEachDebEntry(debFile: File, handle: (TarArchiveEntry, TarArchiveInputStream) -> Unit) {
        debFile.inputStream().buffered().use { bis ->
            ArArchiveInputStream(bis).use { ar ->
                var entry = ar.getNextEntry()
                while (entry != null) {
                    val name = entry.name
                    if (name == "data.tar.xz" || name == "data.tar.gz") {
                        val compressed: InputStream =
                            if (name.endsWith(".xz")) XZCompressorInputStream(ar) else GzipCompressorInputStream(ar)
                        compressed.use { compIn ->
                            TarArchiveInputStream(compIn).use { tarIn ->
                                var tarEntry = tarIn.getNextTarEntry()
                                while (tarEntry != null) {
                                    handle(tarEntry, tarIn)
                                    tarEntry = tarIn.getNextTarEntry()
                                }
                            }
                        }
                        return
                    }
                    entry = ar.getNextEntry()
                }
            }
        }
        throw IllegalStateException("Внутри ${debFile.name} не найден data.tar.xz/data.tar.gz")
    }

    /** proot + loader/loader32: без loader'ов proot не запускается в принципе. */
    private fun extractProotBinaries(debFile: File, destRoot: File) {
        val wanted = listOf("bin/proot", "libexec/proot/loader", "libexec/proot/loader32")
        val extracted = mutableSetOf<String>()
        forEachDebEntry(debFile) { entry, tarIn ->
            if (!entry.isFile) return@forEachDebEntry
            val normalized = entry.name.removePrefix("./")
            val match = wanted.firstOrNull { normalized.endsWith(it) } ?: return@forEachDebEntry
            val out = File(destRoot, match)
            out.parentFile?.mkdirs()
            FileOutputStream(out).use { tarIn.copyTo(it) }
            extracted.add(match)
        }
        val missing = wanted.filterNot { it in extracted }
        if (missing.isNotEmpty()) {
            throw IllegalStateException("Внутри .deb-пакета proot не найдены: ${missing.joinToString()}")
        }
    }

    /**
     * Общие библиотеки (libtalloc, libandroid-shmem). Симлинки внутри пакета
     * относительные, поэтому перенос в наш каталог их не ломает.
     */
    private fun extractSharedLibs(debFile: File, libDir: File) {
        libDir.mkdirs()
        forEachDebEntry(debFile) { entry, tarIn ->
            val normalized = entry.name.removePrefix("./")
            val libIndex = normalized.indexOf("usr/lib/")
            if (libIndex < 0) return@forEachDebEntry
            val relative = normalized.substring(libIndex + "usr/lib/".length)
            if (relative.isEmpty() || relative.contains('/')) return@forEachDebEntry
            val out = File(libDir, relative)
            when {
                entry.isSymbolicLink -> {
                    if (out.exists()) runCatching { Os.remove(out.absolutePath) }
                    runCatching { Os.symlink(entry.linkName, out.absolutePath) }
                }
                entry.isFile -> {
                    FileOutputStream(out).use { tarIn.copyTo(it) }
                }
            }
        }
    }

    /**
     * Распаковка tar.gz rootfs. Критично воспроизводить symlink'и и hardlink'и:
     * в Ubuntu каталоги bin, lib и sbin — симлинки на usr, а dpkg активно
     * использует hardlink'и. Без этого rootfs просто не запускается.
     */
    private fun extractTarGz(archive: File, destDir: File) {
        val destCanonical = destDir.canonicalPath
        archive.inputStream().buffered().use { bis ->
            GzipCompressorInputStream(bis).use { gzIn ->
                TarArchiveInputStream(gzIn).use { tarIn ->
                    var entry = tarIn.getNextTarEntry()
                    while (entry != null) {
                        extractTarEntry(entry, tarIn, destDir, destCanonical)
                        entry = tarIn.getNextTarEntry()
                    }
                }
            }
        }
    }

    private fun extractTarEntry(
        entry: TarArchiveEntry,
        tarIn: TarArchiveInputStream,
        destDir: File,
        destCanonical: String
    ) {
        val outFile = File(destDir, entry.name)
        // Защита от zip-slip
        if (!outFile.canonicalPath.startsWith(destCanonical)) return

        when {
            entry.isSymbolicLink -> {
                outFile.parentFile?.mkdirs()
                if (outFile.exists()) runCatching { Os.remove(outFile.absolutePath) }
                runCatching { Os.symlink(entry.linkName, outFile.absolutePath) }
            }
            entry.isLink -> {
                outFile.parentFile?.mkdirs()
                val source = File(destDir, entry.linkName)
                if (outFile.exists()) runCatching { Os.remove(outFile.absolutePath) }
                val linked = runCatching { Os.link(source.absolutePath, outFile.absolutePath) }.isSuccess
                if (!linked && source.exists()) runCatching { source.copyTo(outFile, overwrite = true) }
            }
            entry.isDirectory -> outFile.mkdirs()
            // FIFO и устройства пропускаем: Android не даёт их создавать, а для
            // работы userland-инструментов они не нужны.
            entry.isFile -> {
                outFile.parentFile?.mkdirs()
                FileOutputStream(outFile).use { tarIn.copyTo(it) }
                // Android API не восстанавливает POSIX-права из tar, поэтому бит
                // исполнения выставляем явно — иначе /bin/bash, java и gradlew
                // не запустятся. 0x49 = восьмеричные 0100|0010|0001 (x для всех).
                if (entry.mode and 0x49 != 0) outFile.setExecutable(true, false)
            }
        }
    }

    private data class ProcessResult(val exitCode: Int, val output: String, val errorOutput: String?)
}
