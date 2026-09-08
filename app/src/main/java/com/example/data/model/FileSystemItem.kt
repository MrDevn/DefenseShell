package com.example.data.model

data class FileSystemItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = 0L,
    val lastModified: Long = 0L,
    val extension: String = "",
    val isHidden: Boolean = false,
    val childCount: Int = 0
) {
    val formattedSize: String
        get() {
            if (isDirectory) return "$childCount items"
            return when {
                sizeBytes < 1024 -> "$sizeBytes B"
                sizeBytes < 1024 * 1024 -> "${sizeBytes / 1024} KB"
                else -> "${String.format("%.1f", sizeBytes / (1024.0 * 1024.0))} MB"
            }
        }
}
