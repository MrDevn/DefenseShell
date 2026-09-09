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
    JAILBREAK(
        title = "JailBreak",
        badgeLabel = "JailBreak",
        description = "Без подтверждений и внутренних ограничений агента. Системные ограничения Android и правила API-провайдера сохраняются"
    )
}
