package com.example.data.model

enum class ArtifactType {
    FILE_CREATE,
    FILE_EDIT,
    TERMINAL_COMMAND,
    CODE_SNIPPET,
    SYSTEM_INFO,
    PLAN,
    QUESTION
}

enum class ArtifactStatus {
    IDLE,
    AWAITING_CONFIRMATION,
    EXECUTING,
    SUCCESS,
    FAILED,
    REJECTED
}

/**
 * Пункт плана агента (как todo-списки в opencode).
 */
enum class PlanItemStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED
}

data class PlanItem(
    val content: String,
    val status: PlanItemStatus = PlanItemStatus.PENDING
)

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
    val isExpanded: Boolean = true,
    val planItems: List<PlanItem> = emptyList(),
    val questionOptions: List<String> = emptyList()
) {
    fun isExecutable(): Boolean = type == ArtifactType.TERMINAL_COMMAND || targetPath?.endsWith(".sh") == true || targetPath?.endsWith(".py") == true
}
