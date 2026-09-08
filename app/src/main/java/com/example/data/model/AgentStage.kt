package com.example.data.model

/**
 * Этапы работы агента, отображаемые в UI (Thinking → Responding → Executing).
 */
enum class AgentStage {
    IDLE,
    CONNECTING,
    THINKING,
    RESPONDING,
    EXECUTING
}
