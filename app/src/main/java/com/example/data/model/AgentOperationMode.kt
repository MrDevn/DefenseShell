package com.example.data.model

enum class AgentOperationMode(
    val title: String,
    val badgeLabel: String,
    val description: String
) {
    SAFETY(
        title = "Safety",
        badgeLabel = "Safety",
        description = "Перед каждым действием агент показывает детали и запрашивает явное подтверждение («Выполнить» / «Отклонить»)"
    ),
    EXTRA(
        title = "Extra",
        badgeLabel = "Extra",
        description = "Полная автономность: действия выполняются автоматически от начала до конца в пределах разрешённых папок"
    ),
    FAST(
        title = "Fast",
        badgeLabel = "Fast",
        description = "Автономный режим с короткими ответами, минимальным контекстом и фокусом на быстрых действиях"
    )
}
