package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.CommandLog
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun TerminalView(
    workingDirectory: String,
    commandHistory: List<CommandLog>,
    onExecuteCommand: (String) -> Unit,
    onClearTerminal: () -> Unit,
    modifier: Modifier = Modifier
) {
    var inputCommand by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    val quickCommands = listOf(
        "ls -la",
        "pwd",
        "sh welcome.sh",
        "cat agent_config.json",
        "python demo.py",
        "help",
        "clear"
    )

    LaunchedEffect(commandHistory.size) {
        if (commandHistory.isNotEmpty()) {
            listState.animateScrollToItem(commandHistory.size - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ClaudeTerminalBackground)
    ) {
        // Terminal Top Header Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF131211))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(ClaudeTerracotta)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "claudeshell @ android",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = ClaudeTerminalForeground
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    color = Color(0xFF262422),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = workingDirectory.substringAfterLast("/"),
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = ClaudeTerracotta
                        ),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            IconButton(
                onClick = onClearTerminal,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "Clear Terminal",
                    tint = ClaudeTerminalForeground.copy(alpha = 0.6f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // Quick Command Chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF161514))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Quick:",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = ClaudeTerminalForeground.copy(alpha = 0.5f)
                )
            )

            quickCommands.forEach { cmd ->
                Surface(
                    color = Color(0xFF252321),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            if (cmd == "clear") {
                                onClearTerminal()
                            } else {
                                onExecuteCommand(cmd)
                            }
                        }
                ) {
                    Text(
                        text = cmd,
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = ClaudeTerracottaLight
                        ),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        }

        HorizontalDivider(color = Color(0xFF252321), thickness = 1.dp)

        // Output Console Feed
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    text = "Welcome to ClaudeShell Linux Sandbox v1.0.0\nType commands below or ask the AI agent in the Chat tab.",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = ClaudeTerminalForeground.copy(alpha = 0.6f),
                        lineHeight = 16.sp
                    )
                )
                Spacer(modifier = Modifier.height(6.dp))
            }

            items(commandHistory) { entry ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Command Prompt line
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "$ ",
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = ClaudeTerminalPrompt
                            )
                        )
                        Text(
                            text = entry.command,
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = ClaudeTerminalForeground
                            )
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = if (entry.exitCode == 0) "exit: 0" else "exit: ${entry.exitCode}",
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = if (entry.exitCode == 0) ClaudeSuccess else ClaudeDanger
                            )
                        )
                    }

                    // Stdout
                    if (entry.output.isNotEmpty()) {
                        Text(
                            text = entry.output,
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = ClaudeTerminalForeground,
                                lineHeight = 17.sp
                            ),
                            modifier = Modifier.padding(start = 12.dp, top = 2.dp)
                        )
                    }

                    // Stderr
                    if (!entry.errorOutput.isNullOrEmpty()) {
                        Text(
                            text = entry.errorOutput,
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = ClaudeDanger,
                                lineHeight = 17.sp
                            ),
                            modifier = Modifier.padding(start = 12.dp, top = 2.dp)
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = Color(0xFF252321), thickness = 1.dp)

        // Terminal Command Input Field
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF131211))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = ClaudeTerminalPrompt
                ),
                modifier = Modifier.padding(end = 8.dp)
            )

            OutlinedTextField(
                value = inputCommand,
                onValueChange = { inputCommand = it },
                placeholder = {
                    Text(
                        text = "Enter bash command...",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = ClaudeTerminalForeground.copy(alpha = 0.4f)
                    )
                },
                singleLine = true,
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = ClaudeTerminalForeground
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (inputCommand.isNotBlank()) {
                            onExecuteCommand(inputCommand)
                            inputCommand = ""
                        }
                    }
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ClaudeTerracotta,
                    unfocusedBorderColor = Color(0xFF2D2A26),
                    focusedContainerColor = Color(0xFF1B1A18),
                    unfocusedContainerColor = Color(0xFF1B1A18)
                ),
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp)
            )

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = {
                    if (inputCommand.isNotBlank()) {
                        onExecuteCommand(inputCommand)
                        inputCommand = ""
                    }
                },
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(ClaudeTerracotta)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Run Command",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
