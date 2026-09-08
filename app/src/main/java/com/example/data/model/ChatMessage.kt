package com.example.data.model

enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}

data class ChatMessage(
    val id: String,
    val conversationId: String,
    val role: MessageRole,
    val content: String,
    val artifacts: List<Artifact> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
)
