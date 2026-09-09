package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Artifact
import com.example.data.model.ArtifactStatus
import com.example.data.model.ArtifactType
import com.example.data.model.PlanItemStatus
import com.example.ui.theme.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration

@Composable
fun ClaudeArtifactCard(
    artifact: Artifact,
    onExecute: (Artifact) -> Unit,
    onSaveContent: (Artifact, String) -> Unit,
    onDangerConfirmRequest: (Artifact) -> Unit,
    modifier: Modifier = Modifier,
    onReject: (Artifact) -> Unit = {},
    onAnswerQuestion: (Artifact, String) -> Unit = { _, _ -> }
) {
    var isExpanded by remember { mutableStateOf(artifact.isExpanded) }
    var isEditing by remember { mutableStateOf(false) }
    var editableContent by remember(artifact.content) { mutableStateOf(artifact.content) }
    val clipboardManager = LocalClipboardManager.current
    var copiedToast by remember { mutableStateOf(false) }

    val shape = RoundedCornerShape(12.dp)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), shape),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = shape,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Artifact Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    // Type Icon
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (artifact.isDangerous) ClaudeDanger.copy(alpha = 0.15f)
                                else ClaudeTerracotta.copy(alpha = 0.12f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when (artifact.type) {
                                ArtifactType.TERMINAL_COMMAND -> Icons.Default.Terminal
                                ArtifactType.FILE_CREATE, ArtifactType.FILE_EDIT -> Icons.Default.Description
                                ArtifactType.CODE_SNIPPET -> Icons.Default.Code
                                ArtifactType.SYSTEM_INFO -> Icons.Default.Info
                                ArtifactType.PLAN -> Icons.Default.Checklist
                                ArtifactType.QUESTION -> Icons.Default.HelpOutline
                            },
                            contentDescription = "Тип артефакта",
                            tint = if (artifact.isDangerous) ClaudeDanger else ClaudeTerracotta,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column {
                        Text(
                            text = artifact.title,
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            maxLines = 1
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (artifact.targetPath != null) {
                                Text(
                                    text = artifact.targetPath,
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    ),
                                    maxLines = 1
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            if (artifact.language != null && artifact.type != ArtifactType.PLAN && artifact.type != ArtifactType.QUESTION) {
                                Text(
                                    text = artifact.language.uppercase(),
                                    style = TextStyle(
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = ClaudeTerracotta
                                    )
                                )
                            }
                        }
                    }
                }

                // Status & Actions
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Status Badge
                    when (artifact.status) {
                        ArtifactStatus.EXECUTING -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = ClaudeTerracotta
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        ArtifactStatus.SUCCESS -> {
                            Surface(
                                color = ClaudeSuccess.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = when (artifact.type) {
                                        ArtifactType.QUESTION -> "Отвечено"
                                        ArtifactType.FILE_CREATE, ArtifactType.FILE_EDIT -> "Сохранено"
                                        else -> "Готово (${artifact.exitCode ?: 0})"
                                    },
                                    color = ClaudeSuccess,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        ArtifactStatus.FAILED -> {
                            Surface(
                                color = ClaudeDanger.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = if (artifact.exitCode != null) "Код ${artifact.exitCode}" else "Ошибка",
                                    color = ClaudeDanger,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        ArtifactStatus.AWAITING_CONFIRMATION -> {
                            Surface(
                                color = ClaudeWarning.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = "Подтверждение",
                                    color = ClaudeWarning,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        else -> {}
                    }

                    // Action: Run & Reject Buttons
                    if (artifact.isExecutable() && artifact.status != ArtifactStatus.EXECUTING) {
                        if (artifact.status == ArtifactStatus.IDLE || artifact.status == ArtifactStatus.AWAITING_CONFIRMATION) {
                            OutlinedButton(
                                onClick = { onReject(artifact) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Text(
                                    text = "Отклонить",
                                    fontSize = 11.sp
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                        }

                        Button(
                            onClick = {
                                if (artifact.isDangerous) {
                                    onDangerConfirmRequest(artifact)
                                } else {
                                    onExecute(artifact)
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (artifact.isDangerous) ClaudeDanger else ClaudeTerracotta,
                                contentColor = Color.White
                            ),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Выполнить",
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Выполнить",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }

                }
            }

            // Divider between header and body
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                thickness = 1.dp
            )

            // Artifact Body (Expanded)
            AnimatedVisibility(visible = true) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    when (artifact.type) {
                        ArtifactType.PLAN -> {
                            val completed = artifact.planItems.count { it.status == PlanItemStatus.COMPLETED }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(7.dp)
                            ) {
                                if (artifact.planItems.isNotEmpty()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Прогресс", style = MaterialTheme.typography.labelMedium)
                                        Text("$completed / ${artifact.planItems.size}", style = MaterialTheme.typography.labelMedium, color = ClaudeTerracotta)
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    LinearProgressIndicator(
                                        progress = { completed.toFloat() / artifact.planItems.size },
                                        modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                                        color = ClaudeTerracotta,
                                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                                if (artifact.planItems.isEmpty() && artifact.content.isNotBlank()) {
                                    Text(
                                        text = artifact.content,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    )
                                }
                                artifact.planItems.forEach { item ->
                                    Row(verticalAlignment = Alignment.Top) {
                                        Icon(
                                            imageVector = when (item.status) {
                                                PlanItemStatus.COMPLETED -> Icons.Default.CheckCircle
                                                PlanItemStatus.IN_PROGRESS -> Icons.Default.Pending
                                                PlanItemStatus.PENDING -> Icons.Default.RadioButtonUnchecked
                                            },
                                            contentDescription = null,
                                            tint = when (item.status) {
                                                PlanItemStatus.COMPLETED -> ClaudeSuccess
                                                PlanItemStatus.IN_PROGRESS -> ClaudeTerracotta
                                                PlanItemStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                            },
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = item.content,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = if (item.status == PlanItemStatus.COMPLETED) {
                                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                                                } else {
                                                    MaterialTheme.colorScheme.onSurface
                                                },
                                                textDecoration = if (item.status == PlanItemStatus.COMPLETED) TextDecoration.LineThrough else null,
                                                fontWeight = if (item.status == PlanItemStatus.IN_PROGRESS) FontWeight.SemiBold else FontWeight.Normal,
                                                lineHeight = 18.sp
                                            ),
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }
                        ArtifactType.QUESTION -> {
                            // Вопрос агента: пользователь обязан ответить (как в opencode)
                            var customAnswer by remember { mutableStateOf("") }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = artifact.content,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                )
                                if (artifact.status == ArtifactStatus.IDLE || artifact.status == ArtifactStatus.AWAITING_CONFIRMATION) {
                                    artifact.questionOptions.forEachIndexed { index, opt ->
                                        Surface(
                                            onClick = { onAnswerQuestion(artifact, opt) },
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(12.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant,
                                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                                        ) {
                                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Surface(shape = RoundedCornerShape(7.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                                    Text(
                                                        text = "${index + 1}",
                                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(10.dp))
                                            Text(
                                                text = opt,
                                                fontSize = 13.sp,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                textAlign = TextAlign.Start,
                                                modifier = Modifier.weight(1f)
                                            )
                                            }
                                        }
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedTextField(
                                            value = customAnswer,
                                            onValueChange = { customAnswer = it },
                                            placeholder = { Text("Ваш ответ...", fontSize = 12.sp) },
                                            singleLine = true,
                                            textStyle = TextStyle(fontSize = 13.sp),
                                            modifier = Modifier.weight(1f)
                                        )
                                        Button(
                                            onClick = {
                                                if (customAnswer.isNotBlank()) {
                                                    onAnswerQuestion(artifact, customAnswer)
                                                    customAnswer = ""
                                                }
                                            },
                                            enabled = customAnswer.isNotBlank(),
                                            shape = RoundedCornerShape(10.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = ClaudeTerracotta,
                                                contentColor = Color.White
                                            )
                                        ) {
                                            Text("Ответить", fontSize = 12.sp)
                                        }
                                    }
                                } else {
                                    Surface(
                                        color = ClaudeSuccess.copy(alpha = 0.1f),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                tint = ClaudeSuccess,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "Ответ: ${artifact.executionOutput ?: ""}",
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        else -> {
                            // Toolbar for Artifact (Edit, Copy)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isEditing) "Редактирование содержимого" else "Содержимое файла / команды",
                                    style = TextStyle(
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.Medium
                                    )
                                )

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    // Edit toggle — только для артефактов с реальным файлом
                                    if (artifact.targetPath != null) {
                                        TextButton(
                                            onClick = {
                                                if (isEditing) {
                                                    onSaveContent(artifact, editableContent)
                                                }
                                                isEditing = !isEditing
                                            },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            modifier = Modifier.height(26.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (isEditing) Icons.Default.Check else Icons.Default.Edit,
                                                contentDescription = "Править",
                                                tint = ClaudeTerracotta,
                                                modifier = Modifier.size(12.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = if (isEditing) "Сохранить" else "Править",
                                                fontSize = 11.sp,
                                                color = ClaudeTerracotta
                                            )
                                        }
                                    }

                                    // Copy button
                                    TextButton(
                                        onClick = {
                                            clipboardManager.setText(AnnotatedString(editableContent))
                                            copiedToast = true
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier.height(26.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (copiedToast) Icons.Default.Check else Icons.Default.ContentCopy,
                                            contentDescription = "Копировать",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = if (copiedToast) "Скопировано" else "Копировать",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            // Content Canvas (Monospaced styled box)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(ClaudeTerminalBackground)
                                    .padding(12.dp)
                            ) {
                                if (isEditing) {
                                    BasicTextField(
                                        value = editableContent,
                                        onValueChange = { editableContent = it },
                                        textStyle = TextStyle(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                            color = ClaudeTerminalForeground,
                                            lineHeight = 18.sp
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                } else {
                                    Text(
                                        text = editableContent.ifEmpty { "(пусто)" },
                                        style = TextStyle(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                            color = ClaudeTerminalForeground,
                                            lineHeight = 18.sp
                                        )
                                    )
                                }
                            }
                        }
                    }

                    // Output Console (if command was executed) — у плана и вопроса не показывается
                    if (artifact.type != ArtifactType.PLAN && artifact.type != ArtifactType.QUESTION && !artifact.executionOutput.isNullOrEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(ClaudeTerminalBackground)
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "ВЫВОД",
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = ClaudeTerminalPrompt
                                    )
                                )
                                Text(
                                    text = "код: ${artifact.exitCode ?: 0}",
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        color = if (artifact.exitCode == 0) ClaudeSuccess else ClaudeDanger
                                    )
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = artifact.executionOutput,
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = ClaudeTerminalForeground,
                                    lineHeight = 16.sp
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
