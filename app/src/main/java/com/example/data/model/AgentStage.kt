package com.example.data.model

/**
 * Этапы работы агента, отображаемые в UI (Thinking → Responding → Executing).
 */
enum class AgentStage {
    IDLE,
    CONNECTING,
    RETRYING,
    THINKING,
    RESPONDING,
    EXECUTING
}
