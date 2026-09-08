package com.example.data.model

data class CommandLog(
    val id: Long = 0,
    val command: String,
    val workingDir: String,
    val exitCode: Int,
    val output: String,
    val errorOutput: String? = null,
    val durationMs: Long = 0,
    val source: String = "USER", // "USER" or "AGENT"
    val timestamp: Long = System.currentTimeMillis()
)
