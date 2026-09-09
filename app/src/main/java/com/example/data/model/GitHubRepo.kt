package com.example.data.model

data class GitHubRepo(
    val fullName: String,
    val name: String,
    val isPrivate: Boolean,
    val defaultBranch: String,
    val updatedAt: String
)
