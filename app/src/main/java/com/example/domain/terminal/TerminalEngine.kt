package com.example.domain.terminal

import android.content.Context
import com.example.data.model.CommandLog
import com.example.domain.filesystem.FileSystemEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class TerminalEngine(
    private val context: Context,
    private val fileSystemEngine: FileSystemEngine
) {

    suspend fun executeCommand(
        command: String,
        workingDir: String = fileSystemEngine.defaultWorkingDir.absolutePath,
        source: String = "USER"
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

        // Try executing using system shell ProcessBuilder
        try {
            val processBuilder = ProcessBuilder("sh", "-c", trimmedCmd)
            processBuilder.directory(workDirFile)

            val env = processBuilder.environment()
            env["HOME"] = fileSystemEngine.defaultWorkingDir.absolutePath
            env["PWD"] = workDirFile.absolutePath
            env["TMPDIR"] = context.cacheDir.absolutePath
            env["TERM"] = "xterm-256color"
            env["PATH"] = "${env["PATH"]}:/system/bin:/system/xbin:/vendor/bin"

            val process = processBuilder.start()

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

            val finished = process.waitFor(15, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                return@withContext CommandLog(
                    command = trimmedCmd,
                    workingDir = workDirFile.absolutePath,
                    exitCode = 124,
                    output = stdoutLines.toString(),
                    errorOutput = "Command timed out after 15 seconds.",
                    durationMs = System.currentTimeMillis() - startTime,
                    source = source
                )
            }

            outThread.join(500)
            errThread.join(500)

            val exitCode = process.exitValue()
            val out = stdoutLines.toString().trimEnd()
            val err = stderrLines.toString().trimEnd().ifEmpty { null }

            CommandLog(
                command = trimmedCmd,
                workingDir = workDirFile.absolutePath,
                exitCode = exitCode,
                output = out,
                errorOutput = err,
                durationMs = System.currentTimeMillis() - startTime,
                source = source
            )
        } catch (e: Exception) {
            // Built-in fallback interpreter for common shell commands
            handleBuiltinCommand(trimmedCmd, workDirFile, startTime, source, e.message)
        }
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
ClaudeShell Terminal Commands:
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
