package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
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
import com.example.ui.theme.*

@Composable
fun ClaudeArtifactCard(
    artifact: Artifact,
    onExecute: (Artifact) -> Unit,
    onSaveContent: (Artifact, String) -> Unit,
    onDangerConfirmRequest: (Artifact) -> Unit,
    modifier: Modifier = Modifier,
    onReject: (Artifact) -> Unit = {}
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
                    .clickable { isExpanded = !isExpanded }
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
                            },
                            contentDescription = "Artifact Type",
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
                            if (artifact.language != null) {
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
                                    text = "Done (${artifact.exitCode ?: 0})",
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
                                    text = "Exit ${artifact.exitCode ?: 1}",
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
                                    text = "Confirm Req.",
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

                    // Expand / Collapse Chevron
                    IconButton(
                        onClick = { isExpanded = !isExpanded },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = "Toggle Expand",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Divider between header and body
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                thickness = 1.dp
            )

            // Artifact Body (Expanded)
            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.fillMaxWidth()) {
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
                            text = if (isEditing) "Editing artifact content" else "Artifact Code / Payload",
                            style = TextStyle(
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Medium
                            )
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Edit toggle
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
                                    contentDescription = "Edit",
                                    tint = ClaudeTerracotta,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (isEditing) "Save" else "Edit",
                                    fontSize = 11.sp,
                                    color = ClaudeTerracotta
                                )
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
                                    contentDescription = "Copy",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (copiedToast) "Copied" else "Copy",
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
                                text = editableContent.ifEmpty { "(empty)" },
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = ClaudeTerminalForeground,
                                    lineHeight = 18.sp
                                )
                            )
                        }
                    }

                    // Output Console (if command was executed)
                    if (!artifact.executionOutput.isNullOrEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF100F0E))
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "EXECUTION OUTPUT",
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = ClaudeTerminalPrompt
                                    )
                                )
                                Text(
                                    text = "exit: ${artifact.exitCode ?: 0}",
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
