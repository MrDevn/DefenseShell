package com.example.domain.github

import android.util.Base64
import com.example.data.model.GitHubRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

data class PushResult(
    val updated: Int,
    val created: Int,
    val deleted: Int,
    val errors: List<String>
) {
    val isEmpty: Boolean get() = updated == 0 && created == 0 && deleted == 0
}

/**
 * Работа с GitHub REST API: проверка токена, список репозиториев,
 * скачивание репозитория (zipball) в рабочую папку и отправка изменений
 * через contents API (без бинарника git — чистый HTTP).
 *
 * Для отслеживания изменений в скачанной папке хранится манифест
 * (.codestudio-manifest.json): git blob SHA и SHA-256 каждого файла.
 */
class GitHubService {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private fun authRequest(url: String, token: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "CodeStudio-Android")

    /** Возвращает login пользователя или null, если токен недействителен. */
    suspend fun validateToken(token: String): String? = withContext(Dispatchers.IO) {
        try {
            http.newCall(authRequest("https://api.github.com/user", token).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string() ?: return@withContext null
                JSONObject(body).optString("login").ifBlank { null }
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun listRepos(token: String): List<GitHubRepo> = withContext(Dispatchers.IO) {
        val repos = mutableListOf<GitHubRepo>()
        try {
            val url = "https://api.github.com/user/repos?per_page=100&sort=pushed&affiliation=owner,collaborator,organization_member"
            http.newCall(authRequest(url, token).build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("GitHub API: HTTP ${resp.code}")
                }
                val arr = JSONArray(resp.body?.string() ?: "[]")
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val fullName = o.optString("full_name")
                    if (fullName.isBlank()) continue
                    repos.add(
                        GitHubRepo(
                            fullName = fullName,
                            name = o.optString("name"),
                            isPrivate = o.optBoolean("private", false),
                            defaultBranch = o.optString("default_branch", "main").ifBlank { "main" },
                            updatedAt = o.optString("pushed_at").take(10)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            if (e is IllegalStateException) throw e
            throw IllegalStateException("Не удалось загрузить репозитории: ${e.localizedMessage ?: e.javaClass.simpleName}")
        }
        repos
    }

    /** Скачивает репозиторий (zipball ветки) в destDir и создаёт манифест для будущего push. */
    suspend fun downloadRepo(token: String, repo: GitHubRepo, destDir: File): File = withContext(Dispatchers.IO) {
        try {
            if (destDir.exists()) destDir.deleteRecursively()
            destDir.mkdirs()

            val url = "https://api.github.com/repos/${repo.fullName}/zipball/${repo.defaultBranch}"
            http.newCall(authRequest(url, token).build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("HTTP ${resp.code} при скачивании ${repo.fullName}")
                }
                val body = resp.body ?: throw IllegalStateException("Пустой ответ при скачивании")
                val manifestFiles = JSONObject()
                val buffer = ByteArray(16384)

                ZipInputStream(body.byteStream()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        // zipball: всё содержимое лежит под одной верхней папкой "owner-repo-sha/"
                        val relPath = entry.name.substringAfter('/', "")
                        if (!entry.isDirectory && relPath.isNotEmpty() && !relPath.contains("..")) {
                            val out = File(destDir, relPath)
                            out.parentFile?.mkdirs()
                            val bytes = ByteArrayOutputStream()
                            var n: Int
                            while (zis.read(buffer).also { n = it } > 0) {
                                bytes.write(buffer, 0, n)
                            }
                            val content = bytes.toByteArray()
                            FileOutputStream(out).use { it.write(content) }
                            manifestFiles.put(
                                relPath,
                                JSONObject().apply {
                                    put("blob", gitBlobSha(content))
                                    put("hash", sha256Hex(content))
                                }
                            )
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }

                val manifest = JSONObject().apply {
                    put("fullName", repo.fullName)
                    put("branch", repo.defaultBranch)
                    put("files", manifestFiles)
                }
                File(destDir, MANIFEST_NAME).writeText(manifest.toString(), Charsets.UTF_8)
            }
            destDir
        } catch (e: Exception) {
            if (e is IllegalStateException) throw e
            throw IllegalStateException("Ошибка скачивания репозитория: ${e.localizedMessage ?: e.javaClass.simpleName}")
        }
    }

    /**
     * Отправляет локальные изменения обратно в репозиторий через contents API:
     * изменённые/новые файлы — PUT, удалённые локально — DELETE.
     */
    suspend fun pushRepo(token: String, repo: GitHubRepo, dir: File, commitMessage: String): PushResult = withContext(Dispatchers.IO) {
        val manifestFile = File(dir, MANIFEST_NAME)
        if (!manifestFile.exists()) {
            throw IllegalStateException("Папка не скачана через CodeStudio (нет манифеста) — сначала нажмите «Скачать»")
        }
        val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))
        val branch = manifest.optString("branch", repo.defaultBranch).ifBlank { repo.defaultBranch }
        val files = manifest.optJSONObject("files") ?: JSONObject()

        val localFiles = mutableMapOf<String, File>()
        dir.walkTopDown().forEach { f ->
            if (f.isFile && f.name != MANIFEST_NAME) {
                localFiles[f.relativeTo(dir).invariantSeparatorsPath] = f
            }
        }

        var updated = 0
        var created = 0
        var deleted = 0
        val errors = mutableListOf<String>()

        for ((path, file) in localFiles) {
            val content = file.readBytes()
            val hash = sha256Hex(content)
            val known = files.optJSONObject(path)
            if (known != null && known.optString("hash") == hash) continue // файл не менялся

            val bodyJson = JSONObject().apply {
                put("message", commitMessage)
                put("content", Base64.encodeToString(content, Base64.NO_WRAP))
                put("branch", branch)
                if (known != null) put("sha", known.optString("blob"))
            }
            try {
                val req = authRequest("https://api.github.com/repos/${repo.fullName}/contents/$path", token)
                    .put(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val respJson = JSONObject(resp.body?.string() ?: "{}")
                        val newSha = respJson.optJSONObject("content")?.optString("sha")?.ifBlank { null } ?: gitBlobSha(content)
                        files.put(path, JSONObject().apply {
                            put("blob", newSha)
                            put("hash", hash)
                        })
                        if (known != null) updated++ else created++
                    } else {
                        errors.add("$path: HTTP ${resp.code}")
                    }
                }
            } catch (e: Exception) {
                errors.add("$path: ${e.localizedMessage ?: e.javaClass.simpleName}")
            }
        }

        // Файлы, удалённые локально, — удаляем и в репозитории
        val toDelete = mutableListOf<String>()
        val keys = files.keys()
        while (keys.hasNext()) {
            val p = keys.next()
            if (!localFiles.containsKey(p)) toDelete.add(p)
        }
        for (path in toDelete) {
            val known = files.optJSONObject(path) ?: continue
            val bodyJson = JSONObject().apply {
                put("message", commitMessage)
                put("sha", known.optString("blob"))
                put("branch", branch)
            }
            try {
                val req = authRequest("https://api.github.com/repos/${repo.fullName}/contents/$path", token)
                    .delete(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        files.remove(path)
                        deleted++
                    } else {
                        errors.add("$path (удаление): HTTP ${resp.code}")
                    }
                }
            } catch (e: Exception) {
                errors.add("$path (удаление): ${e.localizedMessage ?: e.javaClass.simpleName}")
            }
        }

        manifest.put("files", files)
        manifestFile.writeText(manifest.toString(), Charsets.UTF_8)
        PushResult(updated, created, deleted, errors)
    }

    private fun gitBlobSha(content: ByteArray): String {
        val header = "blob ${content.size}\u0000".toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(header)
        digest.update(content)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256Hex(content: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(content).joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val MANIFEST_NAME = ".codestudio-manifest.json"
    }
}
