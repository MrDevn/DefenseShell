package com.example.domain.terminal

import android.content.Context
import com.example.data.model.CommandLog
import com.example.domain.filesystem.FileSystemEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class TerminalEngine(
    private val context: Context,
    private val fileSystemEngine: FileSystemEngine,
    private val prootEnvironment: ProotEnvironment = ProotEnvironment(context, fileSystemEngine)
) {

    @Volatile
    private var activeProcess: Process? = null

    fun cancelCurrentCommand() {
        activeProcess?.let { process ->
            stopProcess(process)
        }
        activeProcess = null
    }

    private fun stopProcess(process: Process) {
        process.destroy()
        // destroyForcibly was added after the app's minSdk; use it when present.
        runCatching {
            process.javaClass.getMethod("destroyForcibly").invoke(process)
        }
    }

    val isProotReady: Boolean get() = prootEnvironment.isBootstrapped

    /** Команды явной настройки Linux-окружения (снимают cooldown после неудачи). */
    private val setupCommands = setOf("setup-ubuntu", "ubuntu-setup", "proot-setup", "install-ubuntu")

    suspend fun executeCommand(
        command: String,
        workingDir: String = fileSystemEngine.defaultWorkingDir.absolutePath,
        source: String = "USER",
        onBootstrapProgress: ((String?) -> Unit)? = null,
        allowBootstrapRetry: Boolean = true
    ): CommandLog = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val trimmedCmd = command.trim()

        if (trimmedCmd.isEmpty()) {
            return@withContext CommandLog(
                command = "",
                workingDir = workingDir,
                exitCode = 0,
                output = "",
                durationMs = 0,
                source = source
            )
        }

        val workDirFile = File(workingDir).let { if (it.exists() && it.isDirectory) it else fileSystemEngine.defaultWorkingDir }

        // Intercept cd command directly to update directory
        if (trimmedCmd == "cd" || trimmedCmd.startsWith("cd ")) {
            val target = trimmedCmd.removePrefix("cd").trim()
            val newDir = when {
                target.isEmpty() || target == "~" -> fileSystemEngine.defaultWorkingDir
                target.startsWith("/") -> File(target)
                target == ".." -> workDirFile.parentFile ?: workDirFile
                else -> File(workDirFile, target)
            }

            return@withContext if (newDir.exists() && newDir.isDirectory) {
                CommandLog(
                    command = trimmedCmd,
                    workingDir = newDir.absolutePath,
                    exitCode = 0,
                    output = "Directory changed to ${newDir.absolutePath}",
                    durationMs = System.currentTimeMillis() - startTime,
                    source = source
                )
            } else {
                CommandLog(
                    command = trimmedCmd,
                    workingDir = workDirFile.absolutePath,
                    exitCode = 1,
                    output = "",
                    errorOutput = "cd: no such file or directory: $target",
                    durationMs = System.currentTimeMillis() - startTime,
                    source = source
                )
            }
        }

        // Явная настройка Linux-окружения: снимает cooldown после неудачи и
        // показывает подробный результат вместо молчаливого fallback на Android sh.
        if (trimmedCmd in setupCommands) {
            prootEnvironment.resetFailureCooldown()
            val setup = prootEnvironment.bootstrap { msg -> onBootstrapProgress?.invoke(msg) }
            onBootstrapProgress?.invoke(null)
            return@withContext if (setup.isSuccess) {
                CommandLog(
                    command = trimmedCmd,
                    workingDir = workDirFile.absolutePath,
                    exitCode = 0,
                    output = "Окружение Ubuntu готово: доступны java, javac, git, curl, unzip.",
                    durationMs = System.currentTimeMillis() - startTime,
                    source = source
                )
            } else {
                CommandLog(
                    command = trimmedCmd,
                    workingDir = workDirFile.absolutePath,
                    exitCode = 1,
                    output = "",
                    errorOutput = "Не удалось настроить окружение: ${setup.exceptionOrNull()?.message}",
                    durationMs = System.currentTimeMillis() - startTime,
                    source = source
                )
            }
        }

        // Run commands in a real bash login shell when available. This keeps
        // Gradle, shell scripts, pipes and environment expansion working.
        val androidShell = if (File("/system/bin/bash").canExecute() || File("/data/data/${context.packageName}/files/usr/bin/bash").canExecute()) {
            "bash"
        } else {
            "sh"
        }

        try {
            val usingProot: Boolean
            val virtualWorkDir: String?
            var bootstrapFailure: String? = null

            if (prootEnvironment.isBootstrapped) {
                usingProot = true
                virtualWorkDir = prootEnvironment.toVirtualPath(workDirFile)
            } else if (
                prootEnvironment.isSupportedArch &&
                allowBootstrapRetry &&
                !prootEnvironment.isInFailureCooldown
            ) {
                // Первый запуск любой команды в терминале автоматически настраивает
                // полноценное Linux-окружение (proot + Ubuntu rootfs + OpenJDK).
                // Без него команды вроде "./gradlew build" физически не могут
                // работать: в голом Android нет ни настоящей Linux-файловой
                // системы, ни JVM. После недавней неудачи повтор откладывается,
                // чтобы терминал не зависал на каждой команде.
                val bootstrapResult = prootEnvironment.bootstrap { msg -> onBootstrapProgress?.invoke(msg) }
                onBootstrapProgress?.invoke(null)
                if (bootstrapResult.isSuccess) {
                    usingProot = true
                    virtualWorkDir = prootEnvironment.toVirtualPath(workDirFile)
                } else {
                    // Не блокируем команду полностью: выполняем её напрямую через
                    // системный shell Android, но честно сообщаем, что полноценное
                    // Linux-окружение (Gradle, JDK) сейчас недоступно.
                    bootstrapFailure = bootstrapResult.exceptionOrNull()?.message
                    usingProot = false
                    virtualWorkDir = null
                }
            } else {
                usingProot = false
                virtualWorkDir = null
                // Команда пойдёт через системный shell Android: честно помечаем,
                // что полноценного Linux-окружения сейчас нет (иначе пользователь
                // видит лишь "apt: not found" и не понимает причину).
                if (bootstrapFailure == null &&
                    prootEnvironment.isSupportedArch &&
                    !prootEnvironment.isBootstrapped
                ) {
                    bootstrapFailure = "окружение Ubuntu ещё не настроено"
                }
            }

            val processBuilder = if (usingProot && virtualWorkDir != null) {
                ProcessBuilder(prootEnvironment.buildProotCommand(trimmedCmd, virtualWorkDir))
                    .apply { directory(workDirFile) }
            } else {
                ProcessBuilder(androidShell, "-lc", trimmedCmd).apply { directory(workDirFile) }
            }

            val env = processBuilder.environment()
            if (usingProot && virtualWorkDir != null) {
                // Внутри Ubuntu не должно быть переменных окружения Android
                // (/system/bin и т.д.) — это ломает java и сборочные инструменты.
                // requiredEnv дополнительно содержит PROOT_LOADER/PROOT_LOADER_32
                // (без них Termux-сборка proot ищет loader по чужому пути и падает)
                // и JAVA_HOME, без которого gradlew отказывается работать.
                env.clear()
                env.putAll(prootEnvironment.requiredEnv)
            } else {
                env["HOME"] = fileSystemEngine.defaultWorkingDir.absolutePath
                env["PWD"] = workDirFile.absolutePath
                env["TMPDIR"] = context.cacheDir.absolutePath
                env["TERM"] = "xterm-256color"
                env["PATH"] = "${env["PATH"]}:/system/bin:/system/xbin:/vendor/bin:/data/data/${context.packageName}/files/usr/bin"
                env["SHELL"] = androidShell
            }

            val process = processBuilder.start()
            activeProcess = process
            val cancellationHandle = kotlin.coroutines.coroutineContext[Job]?.invokeOnCompletion { cause ->
                if (cause != null) {
                    stopProcess(process)
                    if (activeProcess === process) activeProcess = null
                }
            }

            val stdoutLines = StringBuilder()
            val stderrLines = StringBuilder()

            val outReader = BufferedReader(InputStreamReader(process.inputStream))
            val errReader = BufferedReader(InputStreamReader(process.errorStream))

            val outThread = Thread {
                try {
                    outReader.forEachLine { line ->
                        stdoutLines.append(line).append("\n")
                    }
                } catch (_: Exception) {}
            }

            val errThread = Thread {
                try {
                    errReader.forEachLine { line ->
                        stderrLines.append(line).append("\n")
                    }
                } catch (_: Exception) {}
            }

            outThread.start()
            errThread.start()

            // Полноценная сборка Gradle (особенно первая, со скачиванием
            // дистрибутива) занимает заметно больше 15 секунд, поэтому лимит
            // увеличен до 15 минут — этого достаточно даже для холодного старта.
            val finished = process.waitFor(15, TimeUnit.MINUTES)
            if (!finished) {
                stopProcess(process)
                cancellationHandle?.dispose()
                if (activeProcess === process) activeProcess = null
                return@withContext CommandLog(
                    command = trimmedCmd,
                    workingDir = workDirFile.absolutePath,
                    exitCode = 124,
                    output = stdoutLines.toString(),
                    errorOutput = "Command timed out after 15 minutes.",
                    durationMs = System.currentTimeMillis() - startTime,
                    source = source
                )
            }

            outThread.join(500)
            errThread.join(500)

            val exitCode = process.exitValue()
            val out = stdoutLines.toString().trimEnd()
            val err = stderrLines.toString().trimEnd().ifEmpty { null }
            cancellationHandle?.dispose()
            if (activeProcess === process) activeProcess = null

            // Android (targetSdk 29+) forbids execve() on scripts/binaries the app
            // itself wrote into its private data directory, even after chmod +x —
            // this surfaces as exit code 126 "Permission denied" (e.g. "./gradlew").
            // Workaround applies only to the plain-Android-shell path (не proot):
            // re-run the same command through an explicitly-invoked interpreter
            // ("sh ./gradlew ..." instead of "./gradlew ..."), since the interpreter
            // binary itself lives in /system/bin and is always executable.
            if (!usingProot && exitCode == 126 && err?.contains("Permission denied") == true && canRetryViaInterpreter(trimmedCmd)) {
                return@withContext executeCommand(
                    command = "$androidShell $trimmedCmd",
                    workingDir = workDirFile.absolutePath,
                    source = source,
                    onBootstrapProgress = onBootstrapProgress,
                    allowBootstrapRetry = false
                )
            }

            val bootstrapNote = bootstrapFailure?.let {
                "Linux-окружение недоступно ($it). Команда выполнена через системный shell Android, " +
                    "поэтому Gradle/JDK и пакеты apt в ней не работают. " +
                    "Запустите 'setup-ubuntu', чтобы скачать и настроить Ubuntu + OpenJDK."
            }
            val combinedErr = listOfNotNull(bootstrapNote, err).joinToString("\n").ifEmpty { null }

            CommandLog(
                command = trimmedCmd,
                workingDir = workDirFile.absolutePath,
                exitCode = exitCode,
                output = out,
                errorOutput = combinedErr,
                durationMs = System.currentTimeMillis() - startTime,
                source = source
            )
        } catch (e: Exception) {
            // Built-in fallback interpreter for common shell commands
            handleBuiltinCommand(trimmedCmd, workDirFile, startTime, source, e.message)
        }
    }

    /**
     * True when [cmd] looks like a direct invocation of a local script
     * ("./gradlew build", "./script.sh", "gradlew") that failed with a
     * permission/exec error and could plausibly succeed if re-run as
     * "sh <cmd>" instead. Guards against infinite recursion by refusing to
     * retry a command that is already prefixed with an interpreter.
     */
    private fun canRetryViaInterpreter(cmd: String): Boolean {
        val first = cmd.split("\\s+".toRegex()).firstOrNull() ?: return false
        if (first in setOf("sh", "bash", "dash", "zsh", "ksh")) return false
        return first.startsWith("./") || first.endsWith(".sh") || first == "gradlew"
    }

    private fun handleBuiltinCommand(
        cmd: String,
        workDir: File,
        startTime: Long,
        source: String,
        systemError: String?
    ): CommandLog {
        val parts = cmd.split("\\s+".toRegex())
        val name = parts.firstOrNull() ?: ""
        val args = parts.drop(1)

        val duration = System.currentTimeMillis() - startTime

        return when (name) {
            "pwd" -> CommandLog(
                command = cmd,
                workingDir = workDir.absolutePath,
                exitCode = 0,
                output = workDir.absolutePath,
                durationMs = duration,
                source = source
            )
            "ls" -> {
                val files = fileSystemEngine.listFiles(workDir.absolutePath)
                val out = files.joinToString("\n") { item ->
                    val typeChar = if (item.isDirectory) "d" else "-"
                    "$typeChar  ${item.name.padEnd(24)}  ${item.formattedSize}"
                }
                CommandLog(
                    command = cmd,
                    workingDir = workDir.absolutePath,
                    exitCode = 0,
                    output = if (out.isEmpty()) "(empty directory)" else out,
                    durationMs = duration,
                    source = source
                )
            }
            "echo" -> CommandLog(
                command = cmd,
                workingDir = workDir.absolutePath,
                exitCode = 0,
                output = args.joinToString(" ").replace("\"", ""),
                durationMs = duration,
                source = source
            )
            "cat" -> {
                val filename = args.firstOrNull()
                if (filename != null) {
                    val file = File(workDir, filename)
                    if (file.exists() && file.isFile) {
                        CommandLog(
                            command = cmd,
                            workingDir = workDir.absolutePath,
                            exitCode = 0,
                            output = file.readText(),
                            durationMs = duration,
                            source = source
                        )
                    } else {
                        CommandLog(
                            command = cmd,
                            workingDir = workDir.absolutePath,
                            exitCode = 1,
                            output = "",
                            errorOutput = "cat: $filename: No such file",
                            durationMs = duration,
                            source = source
                        )
                    }
                } else {
                    CommandLog(command = cmd, workingDir = workDir.absolutePath, exitCode = 1, output = "", errorOutput = "cat: missing operand", durationMs = duration, source = source)
                }
            }
            "help" -> CommandLog(
                command = cmd,
                workingDir = workDir.absolutePath,
                exitCode = 0,
                output = """
CodeStudio Terminal Commands:
  ls [-la]              List directory contents
  pwd                   Print current working directory
  cd <dir>              Change working directory
  cat <file>            Display file contents
  echo <text>           Print text
  mkdir <dir>           Create new directory
  touch <file>          Create empty file
  rm <file>             Remove file (requires confirmation if dangerous)
  sh <script.sh>        Execute shell script
  python <script.py>    Execute python script
  clear                 Clear terminal screen
  help                  Show this reference
""".trimIndent(),
                durationMs = duration,
                source = source
            )
            else -> CommandLog(
                command = cmd,
                workingDir = workDir.absolutePath,
                exitCode = 127,
                output = "",
                errorOutput = "Command execution error: ${systemError ?: "command not found: $name"}",
                durationMs = duration,
                source = source
            )
        }
    }
}
