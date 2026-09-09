package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.model.AgentOperationMode
import com.example.ui.theme.ClaudeTerracotta

@Composable
fun ModeSelectionDialog(
    currentMode: AgentOperationMode,
    onModeSelected: (AgentOperationMode) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedMode by remember { mutableStateOf(currentMode) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(ClaudeTerracotta.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (selectedMode == AgentOperationMode.SAFETY) Icons.Default.Security else Icons.Default.Bolt,
                            contentDescription = null,
                            tint = ClaudeTerracotta,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Режим работы агента",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Safety Mode Card
                ModeOptionCard(
                    mode = AgentOperationMode.SAFETY,
                    isSelected = selectedMode == AgentOperationMode.SAFETY,
                    icon = Icons.Default.Security,
                    title = "Safety (Безопасный)",
                    subtitle = "Подтверждение каждого шага",
                    description = "Перед созданием файлов или запуском команд агент показывает детали операции и ждет вашего нажатия «Выполнить» или «Отклонить».",
                    onClick = { selectedMode = AgentOperationMode.SAFETY }
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Extra Mode Card
                ModeOptionCard(
                    mode = AgentOperationMode.EXTRA,
                    isSelected = selectedMode == AgentOperationMode.EXTRA,
                    icon = Icons.Default.Bolt,
                    title = "Extra (Автономный)",
                    subtitle = "Полная автономность",
                    description = "Агент сам выполняет цепочки команд и создает файлы от начала до конца в пределах домашней папки (\$HOME) и выданных разрешений.",
                    onClick = { selectedMode = AgentOperationMode.EXTRA }
                )

                Spacer(modifier = Modifier.height(12.dp))

                ModeOptionCard(
                    mode = AgentOperationMode.FAST,
                    isSelected = selectedMode == AgentOperationMode.FAST,
                    icon = Icons.Default.Bolt,
                    title = "Fast (Быстрый)",
                    subtitle = "Extra без лишних размышлений",
                    description = "Агент действует автономно, отвечает коротко и использует минимальный контекст, чтобы быстрее переходить к результату.",
                    onClick = { selectedMode = AgentOperationMode.FAST }
                )

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Отмена", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onModeSelected(selectedMode)
                            onDismiss()
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ClaudeTerracotta,
                            contentColor = Color.White
                        )
                    ) {
                        Text("Применить")
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeOptionCard(
    mode: AgentOperationMode,
    isSelected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    description: String,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    val bgColor = if (isSelected) ClaudeTerracotta.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(if (isSelected) 2.dp else 1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable { onClick() },
        color = bgColor,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface
                        )
                    )
                }

                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Selected",
                        tint = ClaudeTerracotta,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
            )
        }
    }
}
