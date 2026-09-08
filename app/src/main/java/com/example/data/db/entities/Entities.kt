package com.example.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val connectionId: String? = null,
    val pinnedDirectory: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String, // "USER", "ASSISTANT", "SYSTEM"
    val content: String,
    val artifactsJson: String = "[]",
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "command_logs")
data class CommandLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val command: String,
    val workingDir: String,
    val exitCode: Int,
    val output: String,
    val errorOutput: String? = null,
    val durationMs: Long = 0,
    val source: String, // "USER" or "AGENT"
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "file_logs")
data class FileLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val operation: String, // "CREATE", "WRITE", "DELETE", "RENAME", "MOVE", "READ"
    val path: String,
    val details: String,
    val status: String, // "SUCCESS", "FAILED"
    val backupContent: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "custom_connections")
data class CustomConnectionEntity(
    @PrimaryKey val id: String,
    val providerId: String,
    val baseUrl: String,
    val apiKey: String,
    val modelId: String,
    val authHeaderFormat: String = "Bearer %s",
    val customHeadersJson: String = "{}",
    val requestBodyTemplate: String = "",
    val isActive: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "granted_folders")
data class GrantedFolderEntity(
    @PrimaryKey val id: String, // Normalized path or UUID
    val folderPath: String,     // e.g. "/storage/emulated/0/Defense"
    val treeUriString: String,  // content://...
    val displayName: String,    // "Defense"
    val grantedAt: Long = System.currentTimeMillis()
)

