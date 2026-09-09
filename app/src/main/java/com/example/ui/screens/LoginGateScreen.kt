package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.example.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.ClaudeDanger
import com.example.ui.theme.ClaudeTerracotta
import kotlinx.coroutines.launch

/**
 * Экран входа при первом запуске: «Войти как гость» или «Войти через GitHub».
 * GitHub-вход по Personal Access Token (scope: repo) — даёт доступ ко всем
 * репозиториям пользователя для скачивания и редактирования.
 */
@Composable
fun LoginGateScreen(
    onGuest: () -> Unit,
    onGitHubLogin: suspend (String) -> Result<String>,
    modifier: Modifier = Modifier
) {
    var showTokenDialog by remember { mutableStateOf(false) }
    var tokenInput by remember { mutableStateOf("") }
    var isBusy by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var tokenVisible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(32.dp)
                .widthIn(max = 360.dp)
        ) {
            // Круглый логотип на экране входа (иконка лаунчера остаётся стандартной)
            Image(
                painter = painterResource(R.drawable.logo),
                contentDescription = "CodeStudio",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(88.dp)
                    .clip(CircleShape)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "CodeStudio",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Автономный ИИ-агент: файлы, терминал и репозитории GitHub",
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(36.dp))

            Button(
                onClick = { showTokenDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ClaudeTerracotta,
                    contentColor = Color.White
                )
            ) {
                Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Войти через GitHub", fontWeight = FontWeight.SemiBold)
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedButton(
                onClick = onGuest,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Войти как гость",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.height(22.dp))

            Text(
                text = "Гость — локальный агент без доступа к репозиториям.\nGitHub — просмотр и редактирование всех ваших репозиториев.",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    lineHeight = 15.sp
                ),
                textAlign = TextAlign.Center
            )
        }
    }

    if (showTokenDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!isBusy) {
                    showTokenDialog = false
                    errorText = null
                }
            },
            title = { Text("Вход через GitHub", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Введите Personal Access Token с правом repo.\n\nGitHub → Settings → Developer settings → Personal access tokens → Tokens (classic).",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                    OutlinedTextField(
                        value = tokenInput,
                        onValueChange = { tokenInput = it },
                        label = { Text("Токен (ghp_… или github_pat_…)") },
                        singleLine = true,
                        visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { tokenVisible = !tokenVisible }) {
                                Icon(
                                    imageVector = if (tokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = "Показать/скрыть токен"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (errorText != null) {
                        Text(
                            text = errorText!!,
                            style = MaterialTheme.typography.bodySmall.copy(color = ClaudeDanger)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (tokenInput.isBlank() || isBusy) return@Button
                        isBusy = true
                        errorText = null
                        scope.launch {
                            val result = onGitHubLogin(tokenInput.trim())
                            isBusy = false
                            if (result.isFailure) {
                                errorText = result.exceptionOrNull()?.message ?: "Не удалось войти"
                            } else {
                                showTokenDialog = false
                                tokenInput = ""
                            }
                        }
                    },
                    enabled = !isBusy && tokenInput.isNotBlank(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                ) {
                    if (isBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                    } else {
                        Text("Войти")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { if (!isBusy) showTokenDialog = false }) {
                    Text("Отмена", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            shape = RoundedCornerShape(16.dp)
        )
    }
}
