package com.example.data.model

enum class ArtifactType {
    FILE_CREATE,
    FILE_EDIT,
    TERMINAL_COMMAND,
    CODE_SNIPPET,
    SYSTEM_INFO
}

enum class ArtifactStatus {
    IDLE,
    AWAITING_CONFIRMATION,
    EXECUTING,
    SUCCESS,
    FAILED,
    REJECTED
}

data class Artifact(
    val id: String,
    val title: String,
    val type: ArtifactType,
    val language: String? = "bash",
    val targetPath: String? = null,
    val content: String = "",
    val command: String? = null,
    val isDangerous: Boolean = false,
    val dangerReason: String? = null,
    val status: ArtifactStatus = ArtifactStatus.IDLE,
    val exitCode: Int? = null,
    val executionOutput: String? = null,
    val isExpanded: Boolean = true
) {
    fun isExecutable(): Boolean = type == ArtifactType.TERMINAL_COMMAND || targetPath?.endsWith(".sh") == true || targetPath?.endsWith(".py") == true
}
