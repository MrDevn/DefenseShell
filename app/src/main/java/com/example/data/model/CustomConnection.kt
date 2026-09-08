package com.example.data.model

import java.util.UUID

/**
 * Represents a user-defined "Custom" (Пользовательский) connection.
 * There are NO built-in provider presets. Every connection is explicitly configured by the user.
 */
data class CustomConnection(
    val id: String = UUID.randomUUID().toString(),
    val providerId: String, // User-defined name / identifier (e.g. "My DeepSeek", "OpenRouter", "Local LM Studio")
    val baseUrl: String, // Full or base API URL (e.g. "https://api.deepseek.com/v1")
    val apiKey: String, // Secret authorization key
    val modelId: String, // Exact model identifier (e.g. "deepseek-chat", "gpt-4o", "claude-3-5-sonnet")
    val authHeaderFormat: String = "Bearer %s", // Authorization header format
    val customHeadersJson: String = "{}", // Extra HTTP headers in JSON key-value format
    val requestBodyTemplate: String = "", // Custom request body JSON template, or empty for OpenAI standard
    val isActive: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    val cleanBaseUrl: String
        get() {
            var url = baseUrl.trim()
            if (url.endsWith("/")) {
                url = url.dropLast(1)
            }
            return url
        }

    val completionsUrl: String
        get() {
            val url = cleanBaseUrl
            return when {
                url.endsWith("/chat/completions") -> url
                url.endsWith("/v1") -> "$url/chat/completions"
                else -> "$url/chat/completions"
            }
        }
}
