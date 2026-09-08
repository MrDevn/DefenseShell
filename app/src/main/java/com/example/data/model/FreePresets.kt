package com.example.data.model

/**
 * Встроенные БЕСПЛАТНЫЕ модели — доступны всем пользователям сразу, без настройки.
 * Общий free-tier API-ключ и base-url зашиты в приложение намеренно:
 * вкладка «Free» в диалоге подключений работает у каждого без ввода данных.
 */
object FreePresets {

    const val FREE_BASE_URL = "https://opencode.ai/zen/v1"
    const val FREE_API_KEY = "sk-wwIwp8AXQXDPexWDCXjio9wtmHVtAUk3Jx3LyN28IIYzdtn6GBwF0LLwO19WXXCM"

    val freeModelIds = listOf(
        "deepseek-v4-flash-free",
        "muse-spark-1.3-contributor-free",
        "muse-spark-1.2-contributor-free",
        "mimo-v2.5-free",
        "ling-3.0-flash-fin-free",
        "nemotron-3-ultra-free",
        "nemotron-3.5-lightning-free"
    )

    /** Стабильный id, чтобы повторная активация не дублировала подключение (Room REPLACE). */
    fun connectionId(modelId: String): String = "free-$modelId"

    fun toConnection(modelId: String): CustomConnection = CustomConnection(
        id = connectionId(modelId),
        providerId = "Free",
        baseUrl = FREE_BASE_URL,
        apiKey = FREE_API_KEY,
        modelId = modelId,
        isActive = true
    )

    fun isFreeConnection(conn: CustomConnection): Boolean = conn.id.startsWith("free-")
}
