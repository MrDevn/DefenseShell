package com.example.domain.ai

import android.util.Base64
import com.example.data.db.entities.GrantedFolderEntity
import com.example.data.model.AgentOperationMode
import com.example.data.model.Artifact
import com.example.data.model.ArtifactStatus
import com.example.data.model.ArtifactType
import com.example.data.model.ChatMessage
import com.example.data.model.ChatAttachment
import com.example.data.model.CustomConnection
import com.example.data.model.MessageRole
import com.example.data.model.PlanItem
import com.example.data.model.PlanItemStatus
import com.example.domain.filesystem.FileSystemEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.TimeUnit

data class AgentExecutionResult(
    val responseText: String,
    val artifacts: List<Artifact>,
    val rawModelOutput: String
)

class AiAgentService(
    private val fileSystemEngine: FileSystemEngine
) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Executes a real HTTP request to the user's Custom connection.
     * Streams the text output chunk-by-chunk via [onPartialText] if streaming is supported.
     * Model reasoning ("Thinking") is streamed separately via [onPartialReasoning] when the
     * provider supplies it (DeepSeek reasoning_content, OpenRouter/OpenAI reasoning, Ollama).
     * The in-flight HTTP call is cancelled automatically when the calling coroutine is cancelled
     * (user pressed Stop).
     * NO MOCK RESPONSES, NO HARDCODED PRESETS.
     */
    suspend fun executeCustomPrompt(
        prompt: String,
        connection: CustomConnection,
        workingDir: String,
        conversationHistory: List<ChatMessage>,
        operationMode: AgentOperationMode = AgentOperationMode.SAFETY,
        grantedFolders: List<GrantedFolderEntity> = emptyList(),
        attachments: List<ChatAttachment> = emptyList(),
        onPartialText: ((String) -> Unit)? = null,
        onPartialReasoning: ((String) -> Unit)? = null,
        onRetry: ((Int, Long) -> Unit)? = null
    ): AgentExecutionResult = withContext(Dispatchers.IO) {
        if (connection.baseUrl.isBlank()) {
            throw IllegalArgumentException("Ошибка: не указан base-url для подключения '${connection.providerId}'. Укажите корректный адрес API.")
        }
        if (connection.modelId.isBlank()) {
            throw IllegalArgumentException("Ошибка: не указан model-id для подключения '${connection.providerId}'. Укажите имя модели.")
        }

        if (connection.providerId.equals("Gemini", ignoreCase = true)) {
            return@withContext executeGeminiPrompt(prompt, connection, attachments, onPartialText)
        }

        val url = connection.completionsUrl
        val systemPrompt = buildSystemPrompt(workingDir, operationMode, grantedFolders)

        // Build messages array
        val messagesJson = JSONArray()
        messagesJson.put(JSONObject().apply {
            put("role", "system")
            put("content", systemPrompt)
        })

        // Add previous conversation messages (last 10 to keep within context limits)
        val recentHistory = conversationHistory.takeLast(10)
        for (msg in recentHistory) {
            val roleStr = when (msg.role) {
                MessageRole.USER -> "user"
                MessageRole.ASSISTANT -> "assistant"
                MessageRole.SYSTEM -> "system"
            }
            messagesJson.put(JSONObject().apply {
                put("role", roleStr)
                put("content", msg.content)
            })
        }

        // Add current prompt
        messagesJson.put(JSONObject().apply {
            put("role", "user")
            put("content", buildUserContent(prompt, attachments))
        })

        // Build Request Body
        val requestBodyString = if (connection.requestBodyTemplate.isNotBlank()) {
            try {
                connection.requestBodyTemplate
                    .replace("{{model}}", connection.modelId)
                    .replace("{{messages}}", messagesJson.toString())
                    .replace("{{stream}}", "true")
            } catch (_: Exception) {
                    buildDefaultOpenAiBody(connection.modelId, messagesJson, stream = true)
            }
        } else {
            buildDefaultOpenAiBody(connection.modelId, messagesJson, stream = true)
        }

        val requestBuilder = Request.Builder()
            .url(url)
            .post(requestBodyString.toRequestBody("application/json; charset=utf-8".toMediaType()))

        // Add Authorization header
        if (connection.apiKey.isNotBlank()) {
            val authHeaderVal = try {
                String.format(connection.authHeaderFormat, connection.apiKey)
            } catch (_: Exception) {
                "Bearer ${connection.apiKey}"
            }
            requestBuilder.header("Authorization", authHeaderVal)
        }

        // Add custom headers
        if (connection.customHeadersJson.isNotBlank()) {
            try {
                val headersObj = JSONObject(connection.customHeadersJson)
                val keys = headersObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = headersObj.getString(key)
                    requestBuilder.header(key, value)
                }
            } catch (_: Exception) {}
        }

        val request = requestBuilder.build()

        // Retry: при сетевых ошибках и HTTP 429/5xx повторяем запрос
        // с нарастающей задержкой — 1с, 3с, 5с, 7с, 9с
        val retryDelaysSec = listOf(1L, 3L, 5L, 7L, 9L)
        var attempt = 0
        var response: Response? = null

        while (true) {
            ensureActive()
            if (attempt > 0) {
                val delaySec = retryDelaysSec[attempt - 1]
                onRetry?.invoke(attempt, delaySec)
                delay(delaySec * 1000L)
            }
            val call = httpClient.newCall(request)
            // Отмена HTTP-запроса при остановке генерации пользователем
            coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
            try {
                val resp = call.execute()
                if (!resp.isSuccessful) {
                    val code = resp.code
                    if ((code == 429 || code in 500..599) && attempt < retryDelaysSec.size) {
                        val errBody = resp.body?.string() ?: ""
                        resp.close()
                        attempt++
                        continue
                    }
                    val errorBody = resp.body?.string() ?: "(пустой ответ)"
                    resp.close()
                    throw IllegalStateException(parseApiError(code, errorBody))
                }
                response = resp
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                throw e
            } catch (e: Exception) {
                if (attempt < retryDelaysSec.size) {
                    attempt++
                    continue
                }
                ensureActive()
                throw IllegalStateException("Сетевая ошибка при запросе к ${connection.cleanBaseUrl}: ${e.localizedMessage ?: e.javaClass.simpleName}. Проверьте доступность API и интернет-соединение.")
            }
        }

        val fullResponseText = StringBuilder()
        val fullReasoningText = StringBuilder()
        var isSseStream = false

        try {
            val activeResponse = response ?: throw IllegalStateException("Нет ответа от сервера")

            val contentType = activeResponse.header("Content-Type", "") ?: ""
            isSseStream = contentType.contains("text/event-stream") || contentType.contains("stream")

            val responseBody = activeResponse.body ?: throw IllegalStateException("Сервер вернул пустой ответ (HTTP ${activeResponse.code})")

            val reader = BufferedReader(InputStreamReader(responseBody.byteStream(), Charsets.UTF_8))

            if (isSseStream) {
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    ensureActive()
                    val currentLine = line?.trim() ?: continue
                    if (currentLine.isEmpty() || currentLine.startsWith(":")) continue // Ping / comment

                    if (currentLine.startsWith("data:")) {
                        val dataContent = currentLine.removePrefix("data:").trim()
                        if (dataContent == "[DONE]") {
                            break
                        }

                        try {
                            val chunkJson = JSONObject(dataContent)
                            val deltaReasoning = extractReasoningFromChunk(chunkJson)
                            if (deltaReasoning.isNotEmpty()) {
                                fullReasoningText.append(deltaReasoning)
                                onPartialReasoning?.invoke(fullReasoningText.toString())
                            }
                            val deltaContent = extractDeltaFromChunk(chunkJson)
                            if (deltaContent.isNotEmpty()) {
                                fullResponseText.append(deltaContent)
                                onPartialText?.invoke(fullResponseText.toString())
                            }
                        } catch (_: Exception) {
                            // Non-JSON SSE event
                        }
                    }
                }
            } else {
                // Non-streaming direct response
                val rawBody = reader.readText()
                try {
                    val reasoning = extractReasoningFromChunk(JSONObject(rawBody))
                    if (reasoning.isNotEmpty()) {
                        fullReasoningText.append(reasoning)
                        onPartialReasoning?.invoke(fullReasoningText.toString())
                    }
                } catch (_: Exception) {}
                val parsedContent = extractContentFromNonStreaming(rawBody)
                fullResponseText.append(parsedContent)
                onPartialText?.invoke(fullResponseText.toString())
            }

        } catch (e: Exception) {
            // Если корутина отменена (пользователь нажал «Стоп») — пробрасываем отмену, а не сетевую ошибку
            ensureActive()
            if (e is CancellationException) {
                throw e
            }
            if (e is IllegalStateException || e is IllegalArgumentException) {
                throw e
            }
            throw IllegalStateException("Сетевая ошибка при запросе к ${connection.cleanBaseUrl}: ${e.localizedMessage ?: e.javaClass.simpleName}. Проверьте доступность API и интернет-соединение.")
        }

        val rawText = fullResponseText.toString().trim()
        if (rawText.isEmpty()) {
            throw IllegalStateException("API вернул пустой текст ответа. Проверьте параметры модели '${connection.modelId}'.")
        }

        val (cleanText, artifacts) = parseArtifactsFromModelOutput(rawText)
        AgentExecutionResult(
            responseText = cleanText,
            artifacts = artifacts,
            rawModelOutput = rawText
        )
    }

    private fun executeGeminiPrompt(
        prompt: String,
        connection: CustomConnection,
        attachments: List<ChatAttachment>,
        onPartialText: ((String) -> Unit)?
    ): AgentExecutionResult {
        if (connection.apiKey.isBlank()) throw IllegalArgumentException("Для Gemini требуется API-ключ.")
        val imageInstruction = if (attachments.any { it.isImage }) {
            "\n\nAnalyze the attached image content directly and answer from what you see in it."
        } else {
            ""
        }
        val parts = JSONArray().put(JSONObject().put("text", prompt + imageInstruction))
        attachments.filter { it.isImage }.forEach { attachment ->
            parts.put(JSONObject().put("inline_data", JSONObject()
                .put("mime_type", attachment.mimeType)
                .put("data", Base64.encodeToString(attachment.bytes, Base64.NO_WRAP))))
        }
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            .put("generationConfig", JSONObject().put("temperature", 0.7))
            .toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val key = java.net.URLEncoder.encode(connection.apiKey, "UTF-8")
        val request = Request.Builder()
            .url("${connection.cleanBaseUrl}/models/${connection.modelId}:generateContent?key=$key")
            .post(body)
            .header("Accept", "application/json")
            .build()
        return httpClient.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("Gemini: HTTP ${response.code}: $raw")
            val candidates = JSONObject(raw).optJSONArray("candidates") ?: JSONArray()
            val responseParts = candidates.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
            val text = buildString {
                for (index in 0 until responseParts.length()) append(responseParts.optJSONObject(index)?.optString("text").orEmpty())
            }.trim()
            onPartialText?.invoke(text)
            AgentExecutionResult(text, emptyList(), raw)
        }
    }

    /**
     * Tests a Custom connection with a lightweight ping request to verify URL, key, and model.
     */
    suspend fun testConnection(connection: CustomConnection): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (connection.providerId.equals("Gemini", ignoreCase = true)) {
                val result = executeGeminiPrompt(
                    prompt = "Respond with just OK.",
                    connection = connection,
                    attachments = emptyList(),
                    onPartialText = null
                )
                return@withContext Result.success("Gemini подключён: ${result.responseText}")
            }
            val url = connection.completionsUrl
            val messagesJson = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "Hello! Respond with just 'OK'.")
                })
            }

            val bodyJson = JSONObject().apply {
                put("model", connection.modelId)
                put("messages", messagesJson)
                put("max_tokens", 10)
                put("stream", false)
            }

            val reqBuilder = Request.Builder()
                .url(url)
                .post(bodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))

            if (connection.apiKey.isNotBlank()) {
                val authHeader = try {
                    String.format(connection.authHeaderFormat, connection.apiKey)
                } catch (_: Exception) {
                    "Bearer ${connection.apiKey}"
                }
                reqBuilder.header("Authorization", authHeader)
            }

            // Custom headers
            if (connection.customHeadersJson.isNotBlank()) {
                try {
                    val hObj = JSONObject(connection.customHeadersJson)
                    val k = hObj.keys()
                    while (k.hasNext()) {
                        val key = k.next()
                        reqBuilder.header(key, hObj.getString(key))
                    }
                } catch (_: Exception) {}
            }

            val resp = httpClient.newCall(reqBuilder.build()).execute()
            if (!resp.isSuccessful) {
                val err = resp.body?.string() ?: ""
                Result.failure(Exception(parseApiError(resp.code, err)))
            } else {
                val body = resp.body?.string() ?: ""
                val text = extractContentFromNonStreaming(body)
                Result.success("Подключение успешно! Ответ модели: $text")
            }
        } catch (e: Exception) {
            Result.failure(Exception("Не удалось подключиться: ${e.localizedMessage ?: e.javaClass.simpleName}"))
        }
    }

    suspend fun listModels(provider: String, apiKey: String): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().get().apply {
                if (provider.equals("Gemini", ignoreCase = true)) {
                    url("https://generativelanguage.googleapis.com/v1beta/models?key=${java.net.URLEncoder.encode(apiKey, "UTF-8")}")
                } else {
                    url("https://api.openai.com/v1/models")
                    header("Authorization", "Bearer $apiKey")
                }
            }.build()
            httpClient.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) return@withContext Result.failure(Exception("HTTP ${response.code}: $raw"))
                val json = JSONObject(raw)
                val data = json.optJSONArray("data") ?: json.optJSONArray("models") ?: JSONArray()
                val models = buildList {
                    for (index in 0 until data.length()) {
                        val model = data.optJSONObject(index) ?: continue
                        val id = model.optString("id").ifBlank { model.optString("name").removePrefix("models/") }
                        if (id.isNotBlank() && (provider.equals("OpenAI", true) || provider.equals("Gemini", true))) add(id)
                    }
                }
                Result.success(models)
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private fun buildDefaultOpenAiBody(modelId: String, messages: JSONArray, stream: Boolean): String {
        val obj = JSONObject()
        obj.put("model", modelId)
        obj.put("messages", messages)
        obj.put("stream", stream)
        obj.put("temperature", 0.7)
        return obj.toString()
    }

    private fun buildUserContent(prompt: String, attachments: List<ChatAttachment>): Any {
        if (attachments.isEmpty()) return prompt

        val textContext = buildString {
            append(prompt)
            attachments.filterNot { it.isImage }.forEach { attachment ->
                append("\n\n--- Файл: ${attachment.name} ---\n")
                append(attachment.bytes.toString(Charsets.UTF_8))
            }
        }
        val images = attachments.filter { it.isImage }
        if (images.isEmpty()) return textContext

        return JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", (textContext.ifBlank { "Опиши прикреплённые изображения." }) +
                    "\n\nПроанализируй содержимое прикреплённых изображений напрямую по пикселям.")
            })
            images.forEach { attachment ->
                val encoded = Base64.encodeToString(attachment.bytes, Base64.NO_WRAP)
                put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().put("url", "data:${attachment.mimeType};base64,$encoded"))
                })
            }
        }
    }

    private fun extractDeltaFromChunk(json: JSONObject): String {
        // 1. OpenAI / OpenRouter / DeepSeek format
        val choices = json.optJSONArray("choices")
        if (choices != null && choices.length() > 0) {
            val choice = choices.getJSONObject(0)
            val delta = choice.optJSONObject("delta")
            if (delta != null && delta.has("content")) {
                return delta.optString("content", "")
            }
            if (choice.has("text")) {
                return choice.optString("text", "")
            }
        }

        // 2. Ollama / custom format
        val message = json.optJSONObject("message")
        if (message != null && message.has("content")) {
            return message.optString("content", "")
        }
        if (json.has("response")) {
            return json.optString("response", "")
        }

        return ""
    }

    /**
     * Извлекает "мысли" модели (reasoning / thinking) из чанка стрима или полного ответа.
     * Поддерживаемые форматы: DeepSeek (delta.reasoning_content), OpenRouter/OpenAI
     * (delta.reasoning), Ollama (message.reasoning_content / message.reasoning).
     */
    private fun extractReasoningFromChunk(json: JSONObject): String {
        val choices = json.optJSONArray("choices")
        if (choices != null && choices.length() > 0) {
            val choice = choices.getJSONObject(0)
            for (key in listOf("delta", "message")) {
                val part = choice.optJSONObject(key) ?: continue
                if (part.has("reasoning_content")) return part.optString("reasoning_content", "")
                if (part.has("reasoning")) return part.optString("reasoning", "")
            }
            if (choice.has("reasoning")) return choice.optString("reasoning", "")
        }

        val message = json.optJSONObject("message")
        if (message != null) {
            if (message.has("reasoning_content")) return message.optString("reasoning_content", "")
            if (message.has("reasoning")) return message.optString("reasoning", "")
        }

        return ""
    }

    private fun extractContentFromNonStreaming(rawBody: String): String {
        return try {
            val json = JSONObject(rawBody)
            val choices = json.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val choice = choices.getJSONObject(0)
                val msg = choice.optJSONObject("message")
                if (msg != null && msg.has("content")) {
                    return msg.optString("content", "")
                }
                if (choice.has("text")) {
                    return choice.optString("text", "")
                }
            }
            if (json.has("response")) {
                return json.optString("response", "")
            }
            rawBody
        } catch (_: Exception) {
            rawBody
        }
    }

    private fun parseApiError(statusCode: Int, errorBody: String): String {
        var serverMessage = ""
        try {
            val json = JSONObject(errorBody)
            if (json.has("error")) {
                val errObj = json.optJSONObject("error")
                serverMessage = errObj?.optString("message") ?: json.optString("error")
            } else if (json.has("message")) {
                serverMessage = json.optString("message")
            } else if (json.has("detail")) {
                serverMessage = json.optString("detail")
            }
        } catch (_: Exception) {
            serverMessage = errorBody.take(300)
        }

        val prefix = when (statusCode) {
            401 -> "Ошибка авторизации (HTTP 401): неверный или отсутствующий api-key."
            403 -> "Доступ запрещен (HTTP 403): проверьте права доступа или баланс аккаунта."
            404 -> "Эндпоинт не найден (HTTP 404): проверьте корректность base-url и model-id."
            429 -> "Превышен лимит запросов (HTTP 429 Rate Limit): исчерпаны квоты токенов."
            500, 502, 503 -> "Ошибка сервера провайдера (HTTP $statusCode)."
            else -> "Ошибка API (HTTP $statusCode)."
        }

        return if (serverMessage.isNotBlank()) "$prefix\nСообщение сервера: $serverMessage" else prefix
    }

    private fun buildSystemPrompt(
        workingDir: String,
        operationMode: AgentOperationMode,
        grantedFolders: List<GrantedFolderEntity>
    ): String {
        val rootDir = fileSystemEngine.primaryStorageDir.absolutePath
        val homeDir = fileSystemEngine.agentHomeDir.absolutePath
        val homeDisplay = fileSystemEngine.homeDisplayPath

        val grantedListStr = if (grantedFolders.isEmpty()) {
            "(Внешние папки пока не добавлены)"
        } else {
            grantedFolders.joinToString("\n") { "- ${it.folderPath} (${it.displayName})" }
        }

        val modeInstructions = when (operationMode) {
            AgentOperationMode.EXTRA -> """
РЕЖИМ РАБОТЫ: EXTRA — ПОЛНАЯ АВТОНОМНОСТЬ
В этом режиме твои действия выполняются АВТОМАТИЧЕСКИ без пошагового запроса подтверждения у пользователя (в пределах разрешённых директорий).
Генерируй все необходимые команды и файлы для полного выполнения задачи от начала до конца.
""".trimIndent()
            AgentOperationMode.SAFETY -> """
РЕЖИМ РАБОТЫ: SAFETY — ПОДТВЕРЖДЕНИЕ КАЖДОГО ШАГА
В этом режиме перед каждым выполнением действия пользователь видит параметры и нажимает «Выполнить».
Описывай свои шаги понятно и прозрачно.
""".trimIndent()
        }

        return """
Ты — CodeStudio, автономный ИИ-агент с реальным доступом к файловой системе Android устройства и терминалу.

$modeInstructions

ПРАВИЛО НЕПРЕРЫВНОЙ РАБОТЫ:
Не останавливайся на полпути и НИКОГДА не спрашивай «продолжить?» — действуй сам. Выполняй задачу от начала до конца.
После выполнения твоих действий приложение автоматически пришлёт тебе их результаты (вывод команд, статусы файлов) отдельным системным сообщением — проанализируй их и продолжай работу, пока задача не будет полностью выполнена.
Если действие завершилось ошибкой — изучи вывод, исправь подход и попробуй снова другим способом.
Когда задача полностью выполнена — подведи краткий итог и НЕ генерируй новые артефакты.

ДОМАШНЯЯ ДИРЕКТОРИЯ АГЕНТА (${'$'}HOME / ~):
$homeDisplay (реальный путь: $homeDir)
Внутри этой папки ты обладаешь полной свободой: создавай файлы, проекты, shell-скрипты, директории и логи. Для домашней директории не требуется никаких дополнительных системных подтверждений.

ТЕКУЩАЯ РАБОЧАЯ ДИРЕКТОРИЯ: $workingDir
ОСНОВНОЕ ХРАНИЛИЩЕ: $rootDir

РАЗРЕШЁННЫЕ ВНЕШНИЕ ПАПКИ УСТРОЙСТВА (видны также как ${fileSystemEngine.androidMountDisplayRoot}/<имя>):
$grantedListStr

ПРАВИЛО ДОСТУПА К ПАПКАМ (UserLand style):
Домашняя директория ($homeDisplay) — это твоя песочница, полная свобода без подтверждений.
Реальные папки устройства (Download, DCIM, /sdcard/...) — это ОТДЕЛЬНЫЙ механизм: если пользователь просит поработать с внешней папкой, которой ещё нет в списке разрешённых, сгенерируй артефакт действия с целевым путём к этой папке. Приложение покажет системный диалог выбора папки (Storage Access Framework) с кнопкой «Использовать эту папку». После подтверждения она станет доступна и появится в списке выше.

ФОРМАТЫ АРТЕФАКТОВ ДЛЯ ДЕЙСТВИЙ:

1. Терминальная команда (выполняется в терминале устройства):
```artifact:terminal
cmd: ls -la
title: Просмотр содержимого директории
```

2. Создание или перезапись файла:
```artifact:file
path: $homeDir/report.txt
title: Создание отчета report.txt
action: create
Содержимое файла...
```

3. Операции с файлами (удаление, поиск, переименование):
```artifact:file_op
action: delete
path: $homeDir/temp.log
title: Удаление файла temp.log
```

или для поиска:
```artifact:file_op
action: search
path: $homeDir
pattern: .log
title: Поиск log-файлов в домашней папке
```

4. План многошаговой задачи (как в opencode). Для ЛЮБОЙ многошаговой задачи сначала создай план, а затем действуй. После получения результатов обновляй план: выполненное помечай [x], текущий шаг — [~]:
```artifact:plan
title: План задачи
- [x] Выполненный шаг
- [~] Шаг, который выполняется сейчас
- [ ] Шаг ещё не начат
```
Для простых одношаговых задач план не нужен.

5. Вопрос пользователю — ТОЛЬКО когда без его выбора или данных действительно нельзя продолжить:
```artifact:question
question: Текст вопроса
options: Вариант 1, Вариант 2, Вариант 3
```
Приложение покажет вопрос пользователю, и он ОБЯЗАН ответить; ответ придёт следующим сообщением. Никогда не повторяй вопрос, на который уже получен ответ. Если вопросов нет — не генерируй artifact:question.

Отвечай вежливо, кратко и структурированно на русском языке. Не используй эмодзи — только чистый текст и markdown.
        """.trimIndent()
    }

    private fun parseArtifactsFromModelOutput(output: String): Pair<String, List<Artifact>> {
        val artifacts = mutableListOf<Artifact>()
        val cleanOutput = StringBuilder()

        val lines = output.lines()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]

            if (line.trim().startsWith("```artifact:terminal")) {
                val blockLines = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    blockLines.add(lines[i])
                    i++
                }
                if (i < lines.size) i++ // Skip closing ```

                var cmd = ""
                var title = "Терминальная команда"
                for (bLine in blockLines) {
                    when {
                        bLine.startsWith("cmd:") -> cmd = bLine.removePrefix("cmd:").trim()
                        bLine.startsWith("title:") -> title = bLine.removePrefix("title:").trim()
                    }
                }

                if (cmd.isNotEmpty()) {
                    val (isDang, reason) = fileSystemEngine.checkDangerousCommand(cmd)
                    artifacts.add(
                        Artifact(
                            id = UUID.randomUUID().toString(),
                            title = title,
                            type = ArtifactType.TERMINAL_COMMAND,
                            command = cmd,
                            content = cmd,
                            language = "bash",
                            isDangerous = isDang,
                            dangerReason = reason,
                            status = ArtifactStatus.IDLE
                        )
                    )
                }
            } else if (line.trim().startsWith("```artifact:file_op")) {
                val blockLines = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    blockLines.add(lines[i])
                    i++
                }
                if (i < lines.size) i++

                var action = "delete"
                var targetPath = ""
                var title = "Файловая операция"
                var pattern = ""

                for (bLine in blockLines) {
                    when {
                        bLine.startsWith("action:") -> action = bLine.removePrefix("action:").trim()
                        bLine.startsWith("path:") -> targetPath = bLine.removePrefix("path:").trim()
                        bLine.startsWith("title:") -> title = bLine.removePrefix("title:").trim()
                        bLine.startsWith("pattern:") -> pattern = bLine.removePrefix("pattern:").trim()
                    }
                }

                val isDelete = action.equals("delete", ignoreCase = true)
                val (isDang, reason) = if (isDelete) {
                    fileSystemEngine.checkDangerousOperation("delete", targetPath)
                } else {
                    Pair(false, null)
                }

                artifacts.add(
                    Artifact(
                        id = UUID.randomUUID().toString(),
                        title = title,
                        type = if (isDelete) ArtifactType.FILE_CREATE else ArtifactType.CODE_SNIPPET,
                        command = if (isDelete) "rm -rf $targetPath" else "find $targetPath -name '*$pattern*'",
                        targetPath = targetPath,
                        content = if (isDelete) "Удаление $targetPath" else "Поиск в $targetPath по шаблону $pattern",
                        language = "text",
                        isDangerous = isDang,
                        dangerReason = reason,
                        status = ArtifactStatus.IDLE
                    )
                )
            } else if (line.trim().startsWith("```artifact:file")) {
                val headerLines = mutableListOf<String>()
                val contentLines = mutableListOf<String>()
                i++

                var inContent = false
                var path = "file.txt"
                var title = "Файл"
                var action = "create"

                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    val cur = lines[i]
                    if (!inContent) {
                        when {
                            cur.startsWith("path:") -> path = cur.removePrefix("path:").trim()
                            cur.startsWith("title:") -> title = cur.removePrefix("title:").trim()
                            cur.startsWith("action:") -> action = cur.removePrefix("action:").trim()
                            else -> {
                                inContent = true
                                contentLines.add(cur)
                            }
                        }
                    } else {
                        contentLines.add(cur)
                    }
                    i++
                }
                if (i < lines.size) i++

                val fileContent = contentLines.joinToString("\n")
                val ext = path.substringAfterLast(".", "txt")
                val lang = when (ext) {
                    "py" -> "python"
                    "sh", "bash" -> "bash"
                    "json" -> "json"
                    "kt" -> "kotlin"
                    "js", "ts" -> "javascript"
                    "md" -> "markdown"
                    else -> "text"
                }

                artifacts.add(
                    Artifact(
                        id = UUID.randomUUID().toString(),
                        title = title,
                        type = if (action == "edit") ArtifactType.FILE_EDIT else ArtifactType.FILE_CREATE,
                        targetPath = path,
                        content = fileContent,
                        language = lang,
                        status = ArtifactStatus.IDLE
                    )
                )
            } else if (line.trim().startsWith("```artifact:plan")) {
                val blockLines = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    blockLines.add(lines[i])
                    i++
                }
                if (i < lines.size) i++

                var planTitle = "План агента"
                val planItems = mutableListOf<PlanItem>()
                for (bLine in blockLines) {
                    val t = bLine.trim()
                    if (t.isEmpty()) continue
                    if (t.startsWith("title:")) {
                        planTitle = t.removePrefix("title:").trim().ifBlank { planTitle }
                        continue
                    }
                    var status = PlanItemStatus.PENDING
                    val content = when {
                        t.startsWith("- [x]", ignoreCase = true) || t.startsWith("[x]", ignoreCase = true) -> {
                            status = PlanItemStatus.COMPLETED
                            t.replaceFirst(Regex("^-?\\s*\\[[xX]]\\s*"), "")
                        }
                        t.startsWith("- [~]") || t.startsWith("[~]") || t.startsWith("- [>]") || t.startsWith("[>]") -> {
                            status = PlanItemStatus.IN_PROGRESS
                            t.replaceFirst(Regex("^-?\\s*\\[[~>]]\\s*"), "")
                        }
                        t.startsWith("- [ ]") || t.startsWith("[ ]") -> t.replaceFirst(Regex("^-?\\s*\\[ ]\\s*"), "")
                        else -> t.replaceFirst(Regex("^([-*•]|\\d+[.)])\\s*"), "")
                    }
                    if (content.isNotBlank()) {
                        planItems.add(PlanItem(content.trim(), status))
                    }
                }

                if (planItems.isNotEmpty()) {
                    artifacts.add(
                        Artifact(
                            id = UUID.randomUUID().toString(),
                            title = planTitle,
                            type = ArtifactType.PLAN,
                            content = planItems.joinToString("\n") { p ->
                                val mark = when (p.status) {
                                    PlanItemStatus.COMPLETED -> "[x]"
                                    PlanItemStatus.IN_PROGRESS -> "[~]"
                                    PlanItemStatus.PENDING -> "[ ]"
                                }
                                "- $mark ${p.content}"
                            },
                            language = null,
                            planItems = planItems,
                            status = ArtifactStatus.IDLE
                        )
                    )
                }
            } else if (line.trim().startsWith("```artifact:question")) {
                val blockLines = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    blockLines.add(lines[i])
                    i++
                }
                if (i < lines.size) i++

                var questionText = ""
                val options = mutableListOf<String>()
                for (bLine in blockLines) {
                    val t = bLine.trim()
                    if (t.isEmpty()) continue
                    when {
                        t.startsWith("question:") -> questionText = t.removePrefix("question:").trim()
                        t.startsWith("options:") -> options.addAll(
                            t.removePrefix("options:").split(",").map { it.trim().removeSurrounding("\"") }.filter { it.isNotEmpty() }
                        )
                        t.startsWith("option:") -> options.add(t.removePrefix("option:").trim())
                        else -> if (questionText.isEmpty()) questionText = t
                    }
                }

                if (questionText.isNotEmpty()) {
                    artifacts.add(
                        Artifact(
                            id = UUID.randomUUID().toString(),
                            title = "Вопрос агента",
                            type = ArtifactType.QUESTION,
                            content = questionText,
                            language = null,
                            questionOptions = options,
                            status = ArtifactStatus.IDLE
                        )
                    )
                }
            } else {
                cleanOutput.append(line).append("\n")
                i++
            }
        }

        return Pair(cleanOutput.toString().trim(), artifacts)
    }
}
