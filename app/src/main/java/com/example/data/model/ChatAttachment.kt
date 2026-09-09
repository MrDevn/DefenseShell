package com.example.data.model

data class ChatAttachment(
    val name: String,
    val mimeType: String,
    val bytes: ByteArray
) {
    val isImage: Boolean
        get() = mimeType.startsWith("image/") || name.substringAfterLast('.', "").lowercase() in setOf(
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif"
        )
}
